package space.deytt.connect

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.viewpager2.widget.ViewPager2
import space.deytt.connect.AppLanguage.uiCopy
import space.deytt.connect.DeyttUi.brandHeader
import space.deytt.connect.DeyttUi.button
import space.deytt.connect.DeyttUi.connectionGlassBackground
import space.deytt.connect.DeyttUi.displayText
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.mono
import space.deytt.connect.DeyttUi.note
import space.deytt.connect.DeyttUi.pressFeedback
import space.deytt.connect.DeyttUi.screen
import space.deytt.connect.DeyttUi.spacer
import space.deytt.connect.DeyttUi.text

class MainActivity : Activity() {
    private lateinit var globe: RouteGlobeView
    private lateinit var globeBackdrop: FrameLayout
    private lateinit var globeStage: FrameLayout
    private lateinit var shellRoot: LinearLayout
    private lateinit var pager: ViewPager2
    private lateinit var navigationBar: DeyttUi.PrimaryNavigationBar
    private lateinit var pageAdapter: TopLevelViewPagerAdapter
    private lateinit var primaryPages: PrimaryPages
    private lateinit var statusInset: View
    private lateinit var homeDestinationTitle: TextView
    private lateinit var homeDestinationFlag: TextView
    private lateinit var action: TextView
    private lateinit var routeTitleText: TextView

    private var homeMapSlot: FrameLayout? = null
    private var pageMotionEnabled = true
    private var pendingRoute: SelectedRoute? = null
    private var initialPage = 0
    private var hasStartedBefore = false
    private var cachedRouteCatalog: List<DeyttRoute>? = null
    private var cachedSubscriptionContent: String? = null
    private var cachedSelectedRoute: SelectedRoute? = null

    private val floatingBannerPresenter: FloatingBannerPresenter by lazy {
        FloatingBannerPresenter(this, { if (::globeStage.isInitialized) globeStage else null }, { if (::statusInset.isInitialized) statusInset else null })
    }
    private val telegramIdentityBridge: TelegramIdentityBridge by lazy {
        TelegramIdentityBridge(this, { if (::primaryPages.isInitialized) primaryPages.updateTelegramAvatar(it) }, { refreshAccountViews() })
    }
    private val networkLocationCoordinator: NetworkLocationCoordinator by lazy {
        NetworkLocationCoordinator(this, ::isHomeApplicationRoutedByTunnel, { if (::pager.isInitialized) updatePrimaryPageMotion() }, { ::globe.isInitialized }, { if (::globe.isInitialized) globe else null }, { tunnelConnectionCoordinator.currentAutoRouteKey })
    }
    private val mapRoutePickerSheet: MapRoutePickerSheet by lazy {
        MapRoutePickerSheet(this, ::selectRoute, ::selectTab, { networkLocationCoordinator.currentNetworkLocation })
    }
    private val homeMetricExplanationSheet: HomeMetricExplanationSheet by lazy {
        HomeMetricExplanationSheet(this, { homeMetricsCoordinator.setMapTrafficActive(it) },
            { if (::globeStage.isInitialized) DeyttUi.setStarfieldMotion(globeStage, active = it) },
            { paused -> if (homeMetricsCoordinator.isQualityStripInitialized()) { homeMetricsCoordinator.pingValueProgress.setPaused(paused); homeMetricsCoordinator.speedValueProgress.setPaused(paused) } },
            { if (homeMetricsCoordinator.homeSpeedForeground && !isFinishing && !isDestroyed) { updatePrimaryPageMotion(); homeMetricsCoordinator.sampleMapTraffic() } },
            { if (::globe.isInitialized) globe.selectTrafficFlow(it) })
    }
    private val homeMetricsCoordinator: HomeMetricsCoordinator by lazy {
        HomeMetricsCoordinator(this, ::getCachedSelectedRoute, ::getCachedRouteCatalog, ::getCachedSubscriptionContent,
            { tunnelConnectionCoordinator.renderedPhase }, { homeMetricExplanationSheet.showHomeMetricExplanation(it) },
            { homeMetricExplanationSheet.isTrafficExplanationShowing() }, { ::pager.isInitialized && pager.currentItem == 0 },
            { !::pager.isInitialized || pager.scrollState == ViewPager2.SCROLL_STATE_IDLE }, { tunnelConnectionCoordinator.homeConnectQueued },
            { awgMeasurementCoordinator.temporaryAwgMeasurement }, { awgMeasurementCoordinator.requestRouteProbePermission { homeMetricsCoordinator.measureSelectedRoute() } },
            { if (::globe.isInitialized) globe.setTrafficActive(it) }, { tunnelConnectionCoordinator.currentAutoRouteKey },
            { tunnelConnectionCoordinator.currentAutoOutboundTag }, { if (::routeTitleText.isInitialized) routeTitleText else null })
    }
    private val tunnelConnectionCoordinator: TunnelConnectionCoordinator by lazy {
        TunnelConnectionCoordinator(this,
            getActionButton = { if (::action.isInitialized) action else null }, getGlobe = { if (::globe.isInitialized) globe else null },
            getPrimaryPages = { if (::primaryPages.isInitialized) primaryPages else null }, refreshPageAdapter = { if (::pageAdapter.isInitialized) pageAdapter.refresh(it) },
            rebuildRouteRow = ::rebuildRouteRow, cancelHomeRouteDownload = { homeMetricsCoordinator.cancelHomeRouteDownload() },
            cancelSelectedRouteLatency = { homeMetricsCoordinator.cancelSelectedRouteLatency() }, clearHomeMetricSamples = { homeMetricsCoordinator.clearHomeMetricSamples() },
            scheduleHomeMeasurements = { homeMetricsCoordinator.scheduleHomeMeasurements() }, sampleMapTraffic = { homeMetricsCoordinator.sampleMapTraffic() },
            refreshNetworkLocation = ::refreshNetworkLocation, isTemporaryAwgMeasurement = { awgMeasurementCoordinator.temporaryAwgMeasurement },
            restoreAwgMeasurement = { awgMeasurementCoordinator.restoreAwgMeasurement?.invoke() },
            cancelAwgMeasurementForSelection = { awgMeasurementCoordinator.cancelAwgMeasurementForSelection?.invoke() == true },
            clearPendingRouteProbe = { homeMetricsCoordinator.pendingRouteProbeId = null; homeMetricsCoordinator.pendingRouteProbeMetricKey = null },
            selectTab = ::selectTab, invalidateRouteCatalogCache = ::invalidateRouteCatalogCache, updateQualityStrip = { homeMetricsCoordinator.updateQualityStrip() },
            updateNetworkLocationViews = { networkLocationCoordinator.updateNetworkLocationViews() }, setSelectedDestination = ::setSelectedDestination,
            onConnectionGenerationIncremented = { homeMetricsCoordinator.homeConnectionGeneration++ }, onRouteSelected = { cachedSelectedRoute = null },
            migrateRemovedSelectedRouteDelegate = ::migrateRemovedSelectedRoute)
    }
    private val awgMeasurementCoordinator: AwgMeasurementCoordinator by lazy {
        AwgMeasurementCoordinator(this, { homeMetricsCoordinator.cancelHomeRouteDownload() },
            { homeMetricsCoordinator.pendingRouteProbeId = null; homeMetricsCoordinator.pendingRouteProbeMetricKey = null },
            { tunnelConnectionCoordinator.pendingRoute = null; pendingRoute = null }, { refreshNetworkLocation(force = true) },
            ::rebuildRouteRow, { tunnelConnectionCoordinator.renderStoredState() }, { tunnelConnectionCoordinator.isSystemTunnelActive() },
            { waitForDiag, onFail, act -> tunnelConnectionCoordinator.awaitEnginesStopped(act, waitForDiagnostics = waitForDiag, onFailure = onFail) })
    }
    private val subscriptionRefreshCoordinator: SubscriptionRefreshCoordinator by lazy {
        SubscriptionRefreshCoordinator(this, invalidateCache = ::invalidateRouteCatalogCache, rebuildRouteRow = ::rebuildRouteRow,
            onSetupRequested = ::openSetup, refreshPages = { if (::pageAdapter.isInitialized) { pageAdapter.refresh(1); pageAdapter.refresh(2) } })
    }

    private val globalSubscriptionRefreshListener = {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                val isEn = AppLanguage.current(this@MainActivity) == AppLanguage.EN
                if (SubscriptionRefreshState.currentPhase == SubscriptionRefreshState.Phase.SUCCESS) {
                    val count = SubscriptionRefreshState.lastSuccessRoutesCount
                    val text = if (isEn) (if (count > 0) "Subscription updated · $count " + (if (count == 1) "route" else "routes") else "Subscription updated successfully")
                    else (if (count > 0) "Подписка обновлена · $count маршрутов" else "Подписка успешно обновлена")
                    showFloatingBanner(text, if (isEn) "Routes and keys synchronized" else "Маршруты и ключи синхронизированы", isSuccess = true)
                } else if (SubscriptionRefreshState.currentPhase == SubscriptionRefreshState.Phase.FAILURE) {
                    val msg = SubscriptionRefreshState.lastMessage.ifBlank { if (isEn) "Update failed" else "Ошибка обновления" }
                    showFloatingBanner(if (isEn) "Subscription update failed" else "Ошибка обновления подписки", uiCopy(msg), isSuccess = false)
                }
            }
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ConnectVpnService.ACTION_ACTIVE_ROUTE) {
                val prevKey = tunnelConnectionCoordinator.currentAutoRouteKey
                val prevTag = tunnelConnectionCoordinator.currentAutoOutboundTag
                tunnelConnectionCoordinator.currentAutoRouteKey = intent.getStringExtra(ConnectVpnService.EXTRA_ACTIVE_ROUTE_KEY)?.takeIf { it in setOf("nl", "de", "fi", "ru", "it", "ru-de") }
                tunnelConnectionCoordinator.currentAutoOutboundTag = intent.getStringExtra(ConnectVpnService.EXTRA_ACTIVE_OUTBOUND_TAG)
                if (::globe.isInitialized) {
                    globe.setActiveAutoRoute(tunnelConnectionCoordinator.currentAutoRouteKey)
                    if (SelectedRouteStore(this@MainActivity).read().id == "auto") {
                        globe.setConnectionPhase(tunnelConnectionCoordinator.renderedPhase.name, isAuto = true, targetRoute = tunnelConnectionCoordinator.currentAutoRouteKey)
                    }
                }
                val sel = SelectedRouteStore(this@MainActivity).read()
                if ((prevKey != tunnelConnectionCoordinator.currentAutoRouteKey || prevTag != tunnelConnectionCoordinator.currentAutoOutboundTag) && sel.id == "auto" && sel.protocol == RouteProtocol.AUTO) {
                    tunnelConnectionCoordinator.updateRouteFlow(sel)
                    homeMetricsCoordinator.cancelHomeRouteDownload(); homeMetricsCoordinator.clearHomeMetricSamples()
                    homeMetricsCoordinator.homeSpeedAttemptAt = 0L; homeMetricsCoordinator.updateQualityStrip()
                    if (tunnelConnectionCoordinator.renderedPhase == VpnPhase.CONNECTED) homeMetricsCoordinator.scheduleHomeMeasurements()
                }
                return
            }
            val phase = intent?.getStringExtra(ConnectVpnService.STATE_PHASE)?.let { runCatching { VpnPhase.valueOf(it) }.getOrNull() }
            if (ConnectVpnService.isRunning() || phase == VpnPhase.ERROR) { tunnelConnectionCoordinator.pendingRoute = null; pendingRoute = null }
            tunnelConnectionCoordinator.renderStatus(phase, intent?.getStringExtra(ConnectVpnService.EXTRA_STATUS), intent?.getStringExtra(ConnectVpnService.EXTRA_ERROR))
            if (phase == VpnPhase.CONNECTED || phase == VpnPhase.IDLE || phase == VpnPhase.ERROR) refreshNetworkLocation() else networkLocationCoordinator.updateNetworkLocationViews()
        }
    }

    private val routeProbeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val event = intent ?: return
            if (event.getBooleanExtra(RouteProbeClient.EXTRA_COMPLETE, false)) RouteProbeClient.clear(this@MainActivity, event.getStringExtra(RouteProbeClient.EXTRA_REQUEST_ID).orEmpty())
            if (::primaryPages.isInitialized) primaryPages.onRouteProbeEvent(event)
            val reqId = event.getStringExtra(RouteProbeClient.EXTRA_REQUEST_ID).orEmpty()
            if (reqId.isBlank() || reqId != homeMetricsCoordinator.pendingRouteProbeId) return
            val sel = SelectedRouteStore(this@MainActivity).read()
            val tag = event.getStringExtra(RouteProbeClient.EXTRA_ROUTE_TAG).orEmpty()
            val metricKey = homeMetricsCoordinator.currentHomeRouteMetricKey(sel.id) ?: return
            fun resetPending() {
                homeMetricsCoordinator.pendingRouteProbeId = null; homeMetricsCoordinator.pendingRouteProbeMetricKey = null
                if (homeMetricsCoordinator.isQualityStripInitialized()) { homeMetricsCoordinator.latencyText.isEnabled = true; homeMetricsCoordinator.latencyText.alpha = 1f }
                if (::action.isInitialized) { action.isEnabled = true; action.alpha = 1f }
                homeMetricsCoordinator.updateQualityStrip()
            }
            if ((tag.isNotBlank() && tag != sel.configTag) || (homeMetricsCoordinator.pendingRouteProbeMetricKey != null && homeMetricsCoordinator.pendingRouteProbeMetricKey != metricKey)) {
                if (event.getBooleanExtra(RouteProbeClient.EXTRA_COMPLETE, false)) resetPending()
                return
            }
            if (!homeMetricsCoordinator.isQualityStripInitialized()) return
            val stage = event.getStringExtra(RouteProbeClient.EXTRA_STAGE).orEmpty()
            val elapsed = event.getLongExtra(RouteProbeClient.EXTRA_MILLISECONDS, -1L)
            val error = event.getStringExtra(RouteProbeClient.EXTRA_ERROR)
            if (stage == "cancelled") { if (event.getBooleanExtra(RouteProbeClient.EXTRA_COMPLETE, false) && reqId == homeMetricsCoordinator.pendingRouteProbeId) resetPending(); return }
            if (stage == "latency_sample") {
                if (RouteProbeStatistics.isValidLatencyMillis(elapsed)) homeMetricsCoordinator.homePingSamples += elapsed
                RouteProbeStatistics.medianLatencyMillis(homeMetricsCoordinator.homePingSamples)?.let { homeMetricsCoordinator.lastRouteLatency = metricKey to it; homeMetricsCoordinator.homePingErrorKey = null }
                homeMetricsCoordinator.latencyText.setTextColor(DeyttUi.SKY); homeMetricsCoordinator.updateQualityStrip(); return
            }
            if (stage == "latency_retry") { homeMetricsCoordinator.latencyText.setTextColor(DeyttUi.SKY); homeMetricsCoordinator.updateQualityStrip(); return }
            when {
                RouteProbeStatistics.isValidLatencyMillis(elapsed) -> {
                    homeMetricsCoordinator.pendingRouteProbeHasOutcome = true; homeMetricsCoordinator.lastRouteLatency = metricKey to elapsed; homeMetricsCoordinator.homePingErrorKey = null
                    homeMetricsCoordinator.latencyText.setTextColor(DeyttUi.MINT)
                    val en = AppLanguage.current(this@MainActivity) == AppLanguage.EN
                    homeMetricsCoordinator.latencyText.contentDescription = if (en) "Median of three HTTPS response times via selected route after TLS: $elapsed milliseconds"
                    else "Медиана трёх HTTPS-ответов через выбранный выход после установки TLS: $elapsed миллисекунд"
                }
                !error.isNullOrBlank() -> {
                    homeMetricsCoordinator.pendingRouteProbeHasOutcome = true; homeMetricsCoordinator.homePingErrorKey = metricKey
                    homeMetricsCoordinator.latencyText.setTextColor(DeyttUi.CORAL); homeMetricsCoordinator.latencyText.contentDescription = uiCopy("Проверка выхода через прокси: $error")
                }
            }
            homeMetricsCoordinator.updateQualityStrip()
            if (event.getBooleanExtra(RouteProbeClient.EXTRA_COMPLETE, false)) {
                if (!homeMetricsCoordinator.pendingRouteProbeHasOutcome) { homeMetricsCoordinator.homePingErrorKey = metricKey; homeMetricsCoordinator.latencyText.setTextColor(DeyttUi.CORAL) }
                resetPending()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (openIncomingSubscription(intent)) { finish(); return }
        if (SubscriptionStore(this).readCurrent() == null) { startActivity(Intent(this, SetupActivity::class.java)); finish(); return }
        StartupAnimationView.configureSystemSplash(this); migrateRemovedSelectedRoute()
        initialPage = savedInstanceState?.getInt(STATE_SELECTED_TAB) ?: intent?.getIntExtra(EXTRA_START_TAB, 0)?.coerceIn(0, 3) ?: 0
        IpNetworkLocationStore(this).clear(); buildScreen()
        SubscriptionRefreshWorker.schedule(this)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) showStartupAnimation()
        window.decorView.post { networkLocationCoordinator.showNetworkLocationConsentIfNeeded() }
        requestNotificationPermissionIfNeeded()
        SubscriptionRefreshState.addListener(globalSubscriptionRefreshListener)
    }

    override fun onStart() {
        super.onStart()
        homeMetricsCoordinator.homeSpeedForeground = true; homeMetricsCoordinator.mapTrafficWindow.reset(); homeMetricsCoordinator.startMapTrafficTick()
        tunnelConnectionCoordinator.currentAutoRouteKey = getSharedPreferences(ConnectVpnService.STATE_PREFS, MODE_PRIVATE)
            .getString(ConnectVpnService.EXTRA_ACTIVE_ROUTE_KEY, null)?.takeIf { it in setOf("nl", "de", "fi", "ru", "it", "ru-de") }
        tunnelConnectionCoordinator.currentAutoOutboundTag = ConnectVpnService.activeAutoOutboundTag(this)
        val filter = IntentFilter().apply { addAction(ConnectVpnService.ACTION_STATUS); addAction(ConnectVpnService.ACTION_ACTIVE_ROUTE) }
        ContextCompat.registerReceiver(this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, routeProbeReceiver, IntentFilter(RouteProbeClient.ACTION_RESULT), ContextCompat.RECEIVER_NOT_EXPORTED)
        if (::action.isInitialized) { tunnelConnectionCoordinator.renderStoredState(); rebuildRouteRow() }
        if (hasStartedBefore && ::pageAdapter.isInitialized) { pageAdapter.refresh(1); pageAdapter.refresh(2) }
        hasStartedBefore = true; refreshNetworkLocation(); refreshTelegramAccount(); refreshSubscriptionIfNeeded()
        if (::primaryPages.isInitialized) primaryPages.startForegroundUpdates()
        homeMetricsCoordinator.startSelectedRouteLatencyTick()
        if (::pager.isInitialized) updatePrimaryPageMotion()
    }

    override fun onStop() {
        homeMetricsCoordinator.homeSpeedForeground = false; homeMetricExplanationSheet.dismiss(); mapRoutePickerSheet.dismiss()
        homeMetricsCoordinator.stopMapTrafficTick(); homeMetricsCoordinator.mapTrafficWindow.reset(); homeMetricsCoordinator.setMapTrafficActive(false)
        homeMetricsCoordinator.cancelHomeRouteDownload(); tunnelConnectionCoordinator.homeConnectQueued = false
        tunnelConnectionCoordinator.homeConnectGeneration++; homeMetricsCoordinator.cancelSelectedRouteLatency()
        awgMeasurementCoordinator.cancelAwgMeasurement()
        runCatching { unregisterReceiver(statusReceiver) }; runCatching { unregisterReceiver(routeProbeReceiver) }
        if (::primaryPages.isInitialized) primaryPages.stopForegroundUpdates()
        if (RouteProbeClient.isRunning(this)) {
            runCatching { RouteProbeClient.cancel(this) }
            homeMetricsCoordinator.pendingRouteProbeId = null; homeMetricsCoordinator.pendingRouteProbeMetricKey = null
            if (homeMetricsCoordinator.isQualityStripInitialized()) { homeMetricsCoordinator.latencyText.text = "—"; homeMetricsCoordinator.latencyText.isEnabled = true; homeMetricsCoordinator.latencyText.alpha = 1f }
            if (::action.isInitialized) { action.isEnabled = true; action.alpha = 1f }
        }
        homeMetricsCoordinator.stopSelectedRouteLatencyTick(); tunnelConnectionCoordinator.close()
        if (::globeStage.isInitialized) DeyttUi.setStarfieldMotion(globeStage, active = false)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::pager.isInitialized) outState.putInt(STATE_SELECTED_TAB, pager.currentItem)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (openIncomingSubscription(intent)) return
        intent.getIntExtra(EXTRA_START_TAB, -1).takeIf { it in 0..3 }?.let { selectTab(it) }
    }

    private fun openIncomingSubscription(incoming: Intent?): Boolean {
        val url = SubscriptionImportLink.parse(incoming?.action, incoming?.dataString) ?: return false
        startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_SUBSCRIPTION_URL, url))
        return true
    }

    @Deprecated("Back navigation is delegated to the selected primary tab")
    override fun onBackPressed() {
        if (::pager.isInitialized && pager.currentItem != 0) pager.setCurrentItem(0, true) else super.onBackPressed()
    }

    override fun onDestroy() {
        SubscriptionRefreshState.removeListener(globalSubscriptionRefreshListener)
        floatingBannerPresenter.close(); subscriptionRefreshCoordinator.close(); awgMeasurementCoordinator.cancelAwgMeasurement()
        networkLocationCoordinator.close(); telegramIdentityBridge.close(); homeMetricsCoordinator.close(); tunnelConnectionCoordinator.close()
        if (::primaryPages.isInitialized) primaryPages.close()
        super.onDestroy()
    }

    @Deprecated("Android VPN permission API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ROUTE_PROBE_PERMISSION_REQUEST) {
            val continuation = awgMeasurementCoordinator.pendingRouteProbeAuthorization
            awgMeasurementCoordinator.pendingRouteProbeAuthorization = null
            if (resultCode == RESULT_OK) {
                if (continuation != null) continuation() else homeMetricsCoordinator.measureSelectedRoute()
            } else if (homeMetricsCoordinator.isQualityStripInitialized() && continuation == null) {
                homeMetricsCoordinator.latencyText.text = "—"; homeMetricsCoordinator.latencyText.isEnabled = true; homeMetricsCoordinator.latencyText.alpha = 1f
                homeMetricsCoordinator.latencyText.contentDescription = uiCopy("Для проверки через выход требуется системное разрешение Android")
            }
            return
        }
        if (requestCode == VPN_PERMISSION_REQUEST) {
            if (resultCode == RESULT_OK && tunnelConnectionCoordinator.pendingRoute != null) tunnelConnectionCoordinator.startSelectedTunnel() else tunnelConnectionCoordinator.pendingRoute = null
            pendingRoute = tunnelConnectionCoordinator.pendingRoute
        }
    }

    private fun buildScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT; window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { window.isStatusBarContrastEnforced = false; window.isNavigationBarContrastEnforced = false }
        WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false }

        globe = RouteGlobeView(this).apply {
            setOverviewMode(true); setConnected(ConnectVpnService.isRunning() || AwgTunnelController.isRunning())
            onMapNodeTapped = { mapRoutePickerSheet.show(it) }; onTrafficTapped = { homeMetricExplanationSheet.showTrafficExplanation(it) }
            focus(SelectedRouteStore(this@MainActivity).read().id, animate = false)
            setAvailableLocations(availableMapLocations().map { it.key }.toSet())
            setUserLocation(networkLocationCoordinator.currentNetworkLocation)
            val sel = SelectedRouteStore(this@MainActivity).read(); val auto = sel.id == "auto"
            setConnectionPhase(VpnStateStore(this@MainActivity).read().phase.name, auto, if (auto) tunnelConnectionCoordinator.currentAutoRouteKey else sel.id)
        }
        tunnelConnectionCoordinator.globe = globe
        globeBackdrop = FrameLayout(this).apply { tag = "globe-underlay"; clipChildren = false; clipToPadding = false; addView(globe, FrameLayout.LayoutParams(-1, -1)) }
        primaryPages = PrimaryPages(this) { if (::pageAdapter.isInitialized) pageAdapter.flushDeferredRouteRefresh() }
        tunnelConnectionCoordinator.primaryPages = primaryPages

        pageAdapter = TopLevelViewPagerAdapter(shouldDeferRouteRefresh = { ::primaryPages.isInitialized && primaryPages.hasActiveRouteDiagnostics() }) { pos ->
            if (pos == 0) buildHomePage() else primaryPages.create(pos)
        }
        pager = ViewPager2(this).apply {
            orientation = ViewPager2.ORIENTATION_HORIZONTAL; offscreenPageLimit = 3; isUserInputEnabled = true; adapter = pageAdapter
            setPageTransformer { page, pos -> val dist = kotlin.math.abs(pos).coerceAtMost(1f); page.alpha = if (pageMotionEnabled) 1f - .16f * dist else 1f; val s = if (pageMotionEnabled) 1f - .025f * dist else 1f; page.scaleX = s; page.scaleY = s }
        }
        navigationBar = DeyttUi.PrimaryNavigationBar(this, initialPage) { selectTab(it) }
        val dock = FrameLayout(this).apply { clipChildren = false; clipToPadding = false; setPadding(dp(12), 0, dp(12), dp(8)); addView(navigationBar, FrameLayout.LayoutParams(-1, dp(66))) }
        val accountHeader = brandHeader().apply { tag = "shell-header"; setPadding(dp(20), dp(6), dp(20), dp(6)) }
        telegramIdentityBridge.bindViews(accountHeader.findViewWithTag("telegram-account-name"), accountHeader.findViewWithTag("telegram-avatar-fallback"), accountHeader.findViewWithTag("telegram-avatar-image"))
        accountHeader.setOnClickListener { selectTab(2) }
        TelegramIdentityCache.bind(TelegramSessionStore.read(this))
        updateTelegramIdentity(TelegramIdentityCache.username, TelegramIdentityCache.avatar)
        statusInset = View(this)
        pager.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top > 0 && bottom - top != oldBottom - oldTop) { updateHomeMapAnchor(); updatePrimaryPageMotion() }
        }
        shellRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; tag = "shell-starfield"; clipChildren = false; clipToPadding = false
            addView(statusInset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
            addView(accountHeader, LinearLayout.LayoutParams(-1, dp(64))); addView(primaryPages.createUpdateBanner())
            addView(FrameLayout(this@MainActivity).apply { clipChildren = false; addView(pager, FrameLayout.LayoutParams(-1, -1)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(dock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(82)))
        }
        ViewCompat.setOnApplyWindowInsetsListener(shellRoot) { _, insets ->
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()).bottom
            val statusParams = statusInset.layoutParams as LinearLayout.LayoutParams
            if (statusParams.height != status) { statusParams.height = status; statusInset.layoutParams = statusParams }
            val targetDockHeight = dp(82) + bottom
            val dockParams = dock.layoutParams as LinearLayout.LayoutParams
            if (dockParams.height != targetDockHeight) { dock.setPadding(dp(12), 0, dp(12), dp(8) + bottom); dockParams.height = targetDockHeight; dock.layoutParams = dockParams }
            insets
        }
        var lastHapticPage = initialPage
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            private fun confirmSelection() { navigationBar.setSelectedPage(pager.currentItem); if (pager.currentItem != lastHapticPage) { pager.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); lastHapticPage = pager.currentItem } }
            override fun onPageScrolled(pos: Int, off: Float, px: Int) { navigationBar.setPageOffset(pos, off); val prog = (pos + off).coerceIn(0f, 1f); globe.setBackdropProgress(if (pageMotionEnabled) prog else if (prog >= .5f) 1f else 0f) }
            override fun onPageSelected(pos: Int) {
                homeMetricsCoordinator.homeSpeedOnHome = pos == 0
                if (pos != 0) { homeMetricsCoordinator.cancelHomeRouteDownload(); homeMetricsCoordinator.setMapTrafficActive(false) } else homeMetricsCoordinator.sampleMapTraffic()
                if (pager.scrollState == ViewPager2.SCROLL_STATE_IDLE) confirmSelection()
            }
            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager2.SCROLL_STATE_DRAGGING || state == ViewPager2.SCROLL_STATE_SETTLING) { if (::globeStage.isInitialized) DeyttUi.setStarfieldMotion(globeStage, active = false) }
                else if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    confirmSelection(); syncGlobeHost(pager.currentItem); updateHomeMapAnchor(); if (::globeStage.isInitialized) updatePrimaryPageMotion()
                    pager.postOnAnimation { if (pager.scrollState == ViewPager2.SCROLL_STATE_IDLE && pager.currentItem == 0) { homeMetricsCoordinator.updateQualityStrip(); homeMetricsCoordinator.measureHomeRouteDownload() } }
                }
            }
        })
        globeStage = FrameLayout(this).apply {
            tag = "globe-stage"; background = DeyttUi.starfieldBackground(this@MainActivity)
            addView(globeBackdrop, FrameLayout.LayoutParams(-1, -1)); addView(shellRoot, FrameLayout.LayoutParams(-1, -1))
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateHomeMapAnchor() }
        }
        setContentView(globeStage); ViewCompat.requestApplyInsets(shellRoot); pager.setCurrentItem(initialPage, false)
        shellRoot.post { syncGlobeHost(pager.currentItem); updatePrimaryPageMotion() }
    }

    private fun buildHomePage(): View {
        val root = screen(withBackdrop = true)
        val mapSlot = FrameLayout(this).apply {
            tag = "home-map-gesture"; clipChildren = false; clipToPadding = false; minimumHeight = dp(128)
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateHomeMapAnchor() }
            setOnTouchListener { view, event ->
                RouteGlobeView.parentInterceptPolicy(event.actionMasked, true)?.let { view.parent?.requestDisallowInterceptTouchEvent(it) }
                val slotLoc = IntArray(2).also(view::getLocationOnScreen); val globeLoc = IntArray(2).also(globe::getLocationOnScreen)
                val forwarded = MotionEvent.obtain(event)
                try { forwarded.offsetLocation((slotLoc[0] - globeLoc[0]).toFloat(), (slotLoc[1] - globeLoc[1]).toFloat()); globe.dispatchTouchEvent(forwarded) } finally { forwarded.recycle() }
            }
        }
        homeMapSlot = mapSlot; root.addView(mapSlot, LinearLayout.LayoutParams(-1, 0, 1f))
        val routeHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.START; minimumHeight = dp(72); isClickable = true; isFocusable = true
            setOnClickListener { selectTab(1) }; pressFeedback(this)
        }
        homeDestinationFlag = text("◎", 24f).apply { gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        routeHeading.addView(homeDestinationFlag, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) })
        routeHeading.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.START
            homeDestinationTitle = displayText("", 25f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
            addView(homeDestinationTitle); routeTitleText = mono("", 12f, DeyttUi.SKY).apply { setPadding(0, dp(4), 0, 0) }; addView(routeTitleText)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(routeHeading); root.addView(spacer(12, this))
        action = button("Подключить").apply {
            tag = "connection-action"; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 13f; setPadding(dp(14), dp(4), dp(14), dp(4))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE; background = connectionGlassBackground(VpnPhase.IDLE)
            setOnClickListener { tunnelConnectionCoordinator.toggleTunnel() }
        }
        tunnelConnectionCoordinator.actionButton = action; root.addView(action, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(spacer(12, this)); root.addView(homeMetricsCoordinator.buildQualityStrip())
        val refreshBtn = button("Обновить замер", secondary = true).apply {
            tag = "home-refresh"; setOnClickListener { homeMetricsCoordinator.homeSpeedAttemptAt = 0L; homeMetricsCoordinator.measureHomeRouteDownload(); homeMetricsCoordinator.measureSelectedRoute() }
        }
        root.addView(refreshBtn, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
        tunnelConnectionCoordinator.updateRouteFlow(SelectedRouteStore(this).read()); networkLocationCoordinator.updateNetworkLocationViews()
        root.addView(spacer(12, this))
        getSharedPreferences("profile_settings", MODE_PRIVATE).getString("subscription_warning", null)?.takeIf(String::isNotBlank)?.let { warning ->
            val compactWarning = if (warning.startsWith("профили amneziawg ") && warning.contains(" не загружены.")) "профиль amneziawg 3.1 не загружен. обновите подписку." else warning
            root.addView(note(compactWarning, DeyttUi.AMBER).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; contentDescription = uiCopy(warning); setPadding(dp(12), dp(7), dp(12), dp(7)) })
        }
        rebuildRouteRow(); tunnelConnectionCoordinator.renderStoredState()
        root.post { if (::pager.isInitialized) syncGlobeHost(pager.currentItem) }
        return root
    }

    private fun updateHomeMapAnchor() {
        val slot = homeMapSlot ?: return
        if (!::globe.isInitialized || !::pager.isInitialized || pager.currentItem != 0 || pager.scrollState != ViewPager2.SCROLL_STATE_IDLE || slot.width <= 0 || slot.height <= 0) return
        val sLoc = IntArray(2).also(slot::getLocationOnScreen); val gLoc = IntArray(2).also(globe::getLocationOnScreen)
        globe.setViewportAnchor(sLoc[0] - gLoc[0], sLoc[1] - gLoc[1], slot.width, slot.height)
    }

    private fun syncGlobeHost(page: Int) {
        if (!::globe.isInitialized || !::globeBackdrop.isInitialized) return
        if (page == 0) homeMapSlot?.post { updateHomeMapAnchor() }
        globe.setBackdropStage(page != 0)
        if (!::pager.isInitialized || pager.scrollState == ViewPager2.SCROLL_STATE_IDLE) globe.setBackdropProgress(if (page == 0) 0f else 1f)
        globe.importantForAccessibility = if (page == 0) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private fun showStartupAnimation() {
        val startup = StartupAnimationView(this, isReducedMotionEnabled()); globeStage.addView(startup, FrameLayout.LayoutParams(-1, -1)); startup.start()
    }

    private fun updatePrimaryPageMotion() {
        if (!::pageAdapter.isInitialized) return
        val reduced = isReducedMotionEnabled() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled())
        pageMotionEnabled = !reduced
        if (::globe.isInitialized) globe.setReducedMotion(reduced)
        if (::globeStage.isInitialized) DeyttUi.setStarfieldMotion(globeStage, active = !isFinishing && !homeMetricExplanationSheet.isTrafficExplanationShowing(), reducedMotion = reduced)
        pager.requestTransform()
    }

    private fun setSelectedDestination(label: String) {
        val hasFlag = label.codePointAt(0) in 0x1F1E6..0x1F1FF
        val flag = if (hasFlag) label.substringBefore(' ') else "◎"; val title = if (hasFlag) label.substringAfter(' ') else label
        if (::homeDestinationTitle.isInitialized) {
            homeDestinationFlag.text = flag; homeDestinationTitle.text = title.lowercase(java.util.Locale.ROOT); homeDestinationTitle.contentDescription = uiCopy("Выбранный выход: $title")
        }
    }

    private fun isHomeApplicationRoutedByTunnel(config: String): Boolean =
        UserSplitTunnelStore(this).let { RouteProxyProbe.isApplicationRoutedByTunnel(config, packageName, customPackages = it.readPackages(), splitMode = it.readMode()) }

    private fun migrateRemovedSelectedRoute(): Boolean {
        if (ConnectVpnService.isRunning() || AwgTunnelController.isRunning() || tunnelConnectionCoordinator.isSystemTunnelActive()) return false
        val store = SelectedRouteStore(this); val selected = store.read()
        if (selected.id != "openflux:DE" && selected.engine != TunnelEngine.OPENFLUX) return false
        val config = SubscriptionStore(this).readCurrent() ?: return false
        val automatic = RouteCatalog.from(config, AwgProfileStore(this).profiles()).firstOrNull { it.protocol == RouteProtocol.AUTO } ?: return false
        store.save(automatic); return true
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
    }

    internal fun selectRoute(route: DeyttRoute, navigateHome: Boolean = true): Unit = tunnelConnectionCoordinator.selectRoute(route, navigateHome)

    internal fun selectTab(index: Int): Unit {
        if (!::pager.isInitialized || index !in 0..3) return
        pager.setCurrentItem(index, !isReducedMotionEnabled() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()))
    }

    internal fun openSetup(): Unit = startActivity(Intent(this, SetupActivity::class.java))

    internal fun rebuildRouteRow(): Unit {
        cachedSelectedRoute = null; val selected = getCachedSelectedRoute()
        if (::globe.isInitialized) globe.focus(selected.id, animate = false)
        tunnelConnectionCoordinator.updateRouteFlow(selected); homeMetricsCoordinator.updateQualityStrip()
    }

    internal fun refreshNetworkLocation(force: Boolean = false): Unit = networkLocationCoordinator.refreshNetworkLocation(force)

    internal fun showFloatingBanner(title: String, subtitle: String? = null, isSuccess: Boolean = true): Unit =
        floatingBannerPresenter.showFloatingBanner(title, subtitle, isSuccess)

    internal fun refreshSubscriptionManually(onComplete: ((Boolean, String) -> Unit)? = null): Unit =
        subscriptionRefreshCoordinator.refreshSubscriptionManually(onComplete)

    internal fun refreshSubscriptionIfNeeded(): Unit = subscriptionRefreshCoordinator.refreshSubscriptionIfNeeded()

    internal fun requestAwgMeasurement(route: DeyttRoute, onComplete: (Boolean) -> Unit = {}, measure: ((Boolean) -> Unit) -> Unit): Unit =
        awgMeasurementCoordinator.requestAwgMeasurement(route, onComplete, measure)

    internal fun requestAwgMeasurements(routes: List<DeyttRoute>, onComplete: (Boolean) -> Unit, onRouteFailure: (DeyttRoute) -> Unit, onRouteStarting: (DeyttRoute) -> Unit = {}, measure: (DeyttRoute, (Boolean) -> Unit) -> Unit): Unit =
        awgMeasurementCoordinator.requestAwgMeasurements(routes, onComplete, onRouteFailure, onRouteStarting, measure)

    internal fun cancelAwgMeasurement(): Unit = awgMeasurementCoordinator.cancelAwgMeasurement()

    internal fun requestRouteProbePermission(afterGrant: () -> Unit): Boolean = awgMeasurementCoordinator.requestRouteProbePermission(afterGrant)

    internal fun updateTelegramIdentity(username: String?, avatar: Bitmap?): Unit = telegramIdentityBridge.updateTelegramIdentity(username, avatar)

    internal fun updateTelegramUsername(username: String?): Unit = telegramIdentityBridge.updateTelegramUsername(username)

    internal fun refreshTelegramAccount(): Unit = telegramIdentityBridge.refreshTelegramAccount()

    internal fun refreshAccountViews(): Unit = telegramIdentityBridge.refreshAccountViews()

    internal fun isNetworkLocationEnabled(): Boolean = networkLocationCoordinator.isNetworkLocationEnabled()

    internal fun setNetworkLocationEnabled(enabled: Boolean): Unit = networkLocationCoordinator.setNetworkLocationEnabled(enabled)

    internal fun isReducedMotionEnabled(): Boolean = networkLocationCoordinator.isReducedMotionEnabled()

    internal fun setReducedMotionEnabled(enabled: Boolean): Unit = networkLocationCoordinator.setReducedMotionEnabled(enabled)

    internal fun getCachedRouteCatalog(): List<DeyttRoute> {
        cachedRouteCatalog?.let { return it }
        val config = getCachedSubscriptionContent() ?: return emptyList()
        val routes = runCatching { RouteCatalog.from(config, AwgProfileStore(this).profiles(), OpenFluxProfileStore(this).profiles(this)) }.getOrNull().orEmpty()
        cachedRouteCatalog = routes; return routes
    }

    internal fun getCachedSubscriptionContent(): String? {
        cachedSubscriptionContent?.let { return it }
        val content = SubscriptionStore(this).readCurrent(); cachedSubscriptionContent = content; return content
    }

    internal fun getCachedSelectedRoute(): SelectedRoute {
        cachedSelectedRoute?.let { return it }
        val selected = SelectedRouteStore(this).read(); cachedSelectedRoute = selected; return selected
    }

    internal fun invalidateRouteCatalogCache(): Unit { cachedRouteCatalog = null; cachedSubscriptionContent = null; cachedSelectedRoute = null }

    internal fun refreshActiveRouteCatalog(): Unit { invalidateRouteCatalogCache(); rebuildRouteRow() }

    internal fun isGlobeInitialized(): Boolean = ::globe.isInitialized

    internal fun getGlobe(): RouteGlobeView = globe

    data class RouteMapLocation(val key: String) { override fun toString(): String = key }

    fun availableMapLocations(): List<RouteMapLocation> {
        val config = SubscriptionStore(this).readCurrent().orEmpty()
        val routes = runCatching { RouteCatalog.from(config, AwgProfileStore(this).profiles(), OpenFluxProfileStore(this).profiles(this)) }.getOrDefault(emptyList())
        return routes.flatMap { route ->
            when (route.countryCode.uppercase()) {
                "NL" -> listOf(RouteMapLocation("nl")); "DE" -> listOf(RouteMapLocation("de")); "FI" -> listOf(RouteMapLocation("fi"))
                "RU" -> listOf(RouteMapLocation("ru")); "IT" -> listOf(RouteMapLocation("it")); else -> emptyList()
            }
        }.distinct()
    }

    companion object {
        const val EXTRA_START_TAB = "space.deytt.connect.extra.START_TAB"
        private const val STATE_SELECTED_TAB = "selected_primary_tab"
        private const val VPN_PERMISSION_REQUEST = 701
        private const val NOTIFICATION_REQUEST = 702
        private const val ROUTE_PROBE_PERMISSION_REQUEST = 703
    }
}
