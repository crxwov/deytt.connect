package space.deytt.connect

import android.animation.ValueAnimator
import android.app.Activity
import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.viewpager2.widget.ViewPager2
import space.deytt.connect.AppLanguage.uiCopy
import space.deytt.connect.DeyttUi.button
import space.deytt.connect.DeyttUi.glassButtonBackground
import space.deytt.connect.DeyttUi.actionLabel
import space.deytt.connect.DeyttUi.brandHeader
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.displayText
import space.deytt.connect.DeyttUi.rounded
import space.deytt.connect.DeyttUi.screen
import space.deytt.connect.DeyttUi.sectionLabel
import space.deytt.connect.DeyttUi.spacer
import space.deytt.connect.DeyttUi.text
import space.deytt.connect.DeyttUi.note
import space.deytt.connect.DeyttUi.mono
import space.deytt.connect.DeyttUi.pressFeedback

class MainActivity : Activity() {
    private lateinit var globe: RouteGlobeView
    private lateinit var globeBackdrop: FrameLayout
    private lateinit var globeStage: FrameLayout
    private lateinit var shellRoot: LinearLayout
    private var pageMotionEnabled = true
    private var homeMapSlot: FrameLayout? = null
    private lateinit var homeDestinationTitle: TextView
    private lateinit var homeDestinationFlag: TextView
    private lateinit var action: TextView
    private lateinit var latencyText: TextView
    private lateinit var qualityDots: DiagnosticDotsView
    private lateinit var routeTitleText: TextView
    private lateinit var downloadText: TextView
    private val homePingSamples = mutableListOf<Long>()
    private var temporaryAwgMeasurement = false
    private var restoreAwgMeasurement: (() -> Unit)? = null
    private var cancelAwgMeasurementForSelection: (() -> Boolean)? = null
    private lateinit var pager: ViewPager2
    private lateinit var navigationBar: DeyttUi.PrimaryNavigationBar
    private lateinit var pageAdapter: TopLevelViewPagerAdapter
    private lateinit var primaryPages: PrimaryPages
    private lateinit var statusInset: View
    private var telegramAccountName: TextView? = null
    private var telegramAvatarFallback: TextView? = null
    private var telegramAvatarImage: ImageView? = null
    private var pendingRoute: SelectedRoute? = null
    private var latencyGeneration = 0
    private var renderedPhase: VpnPhase? = null
    private var initialPage = 0
    private var hasStartedBefore = false
    private var currentNetworkLocation: IpNetworkLocation? = null
    private var currentEgressLocation: IpNetworkLocation? = null
    private var currentAutoRouteKey: String? = null
    private var currentAutoOutboundTag: String? = null
    private var locationRequestInFlight = false
    private var locationLookupFailed = false
    private var locationRequestGeneration = 0
    private var egressRequestInFlight = false
    private var egressLookupFailed = false
    private var egressRequestGeneration = 0
    private var pendingRouteProbeId: String? = null
    private var pendingRouteProbeMetricKey: HomeRouteMetricKey? = null
    private var pendingRouteProbeHasOutcome = false
    private var homePingErrorKey: HomeRouteMetricKey? = null
    private var homePingBypassedKey: HomeRouteMetricKey? = null
    private var homeConnectQueued = false
    private var homeConnectGeneration = 0L
    private var lastRouteLatency: Pair<HomeRouteMetricKey, Long>? = null
    private var pendingRouteProbeAuthorization: (() -> Unit)? = null
    private var selectedRouteLatencyInFlight = false
    @Volatile private var homeSpeedForeground = false
    @Volatile private var homeSpeedOnHome = true
    @Volatile private var homeSpeedGeneration = 0
    private var homeSpeedFuture: java.util.concurrent.Future<*>? = null
    private var homeSpeedConnectedRouteId: String? = null
    private var homeSpeedConnectedAt = 0L
    private var homeConnectionGeneration = 0L
    private var homeSpeedAttemptAt = 0L
    private var homeSpeedInFlight = false
    private var homeSpeedError: String? = null
    private var homeSpeedErrorKey: HomeRouteMetricKey? = null
    private var homeSpeedBypassedKey: HomeRouteMetricKey? = null
    private var homeSpeedSample: Pair<HomeRouteMetricKey, Long>? = null
    private var homeSpeedSampleAt = 0L
    private val locationExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val accountExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val subscriptionExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var accountGeneration = 0
    private var subscriptionRefreshInFlight = false
    private var subscriptionRefreshLastStartedAt = 0L
    private val connectionProgressHandler = Handler(Looper.getMainLooper())
    private val selectedRouteLatencyHandler = Handler(Looper.getMainLooper())
    private val mapTrafficHandler = Handler(Looper.getMainLooper())
    private val mapTrafficWindow = TrafficActivityWindow(MAP_TRAFFIC_GRACE_MS)
    private var mapTrafficActive = false
    private val mapTrafficTick = object : Runnable {
        override fun run() {
            if (!homeSpeedForeground || isFinishing || isDestroyed) return
            sampleMapTraffic()
            mapTrafficHandler.postDelayed(this, MAP_TRAFFIC_SAMPLE_INTERVAL_MS)
        }
    }
    private val connectionProgressTick = object : Runnable {
        override fun run() {
            val phase = renderedPhase ?: return
            if (phase != VpnPhase.STARTING && phase != VpnPhase.CHECKING) return
            renderConnectionProgressDetail(phase)
            connectionProgressHandler.postDelayed(this, CONNECTION_PROGRESS_INTERVAL_MS)
        }
    }
    private val selectedRouteLatencyTick = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            if (::pager.isInitialized && pager.currentItem == 0 &&
                renderedPhase == VpnPhase.CONNECTED &&
                VpnService.prepare(this@MainActivity) == null &&
                !RouteProbeClient.isRunning(this@MainActivity)) measureSelectedRoute()
            measureHomeRouteDownload()
            selectedRouteLatencyHandler.postDelayed(this, SELECTED_ROUTE_LATENCY_INTERVAL_MS)
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ConnectVpnService.ACTION_ACTIVE_ROUTE) {
                val previousKey = currentAutoRouteKey
                val previousOutboundTag = currentAutoOutboundTag
                currentAutoRouteKey = intent.getStringExtra(ConnectVpnService.EXTRA_ACTIVE_ROUTE_KEY)
                    ?.takeIf { it in setOf("nl", "de", "fi", "ru", "it", "ru-de") }
                currentAutoOutboundTag = intent.getStringExtra(ConnectVpnService.EXTRA_ACTIVE_OUTBOUND_TAG)
                if (::globe.isInitialized) globe.setActiveAutoRoute(currentAutoRouteKey)
                val selected = SelectedRouteStore(this@MainActivity).read()
                if ((previousKey != currentAutoRouteKey || previousOutboundTag != currentAutoOutboundTag) &&
                    selected.id == "auto" && selected.protocol == RouteProtocol.AUTO
                ) {
                    updateRouteFlow(selected)
                    cancelHomeRouteDownload()
                    clearHomeMetricSamples()
                    homeSpeedAttemptAt = 0L
                    if (::downloadText.isInitialized) updateQualityStrip()
                    if (renderedPhase == VpnPhase.CONNECTED) selectedRouteLatencyHandler.postDelayed({ measureHomeRouteDownload() }, 1_800L)
                }
                return
            }
            val phase = intent?.getStringExtra(ConnectVpnService.STATE_PHASE)
                ?.let { runCatching { VpnPhase.valueOf(it) }.getOrNull() }
            if (ConnectVpnService.isRunning() || phase == VpnPhase.ERROR) pendingRoute = null
            renderStatus(
                phase,
                intent?.getStringExtra(ConnectVpnService.EXTRA_STATUS),
                intent?.getStringExtra(ConnectVpnService.EXTRA_ERROR),
            )
            if (phase == VpnPhase.CONNECTED || phase == VpnPhase.IDLE || phase == VpnPhase.ERROR) {
                refreshNetworkLocation()
            } else {
                updateNetworkLocationViews()
            }
        }
    }

    private val routeProbeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val event = intent ?: return
            if (event.getBooleanExtra(RouteProbeClient.EXTRA_COMPLETE, false)) {
                RouteProbeClient.clear(this@MainActivity, event.getStringExtra(RouteProbeClient.EXTRA_REQUEST_ID).orEmpty())
            }
            if (::primaryPages.isInitialized) primaryPages.onRouteProbeEvent(event)
            val requestId = event.getStringExtra(RouteProbeClient.EXTRA_REQUEST_ID).orEmpty()
            if (requestId.isBlank() || requestId != pendingRouteProbeId) return
            val selected = SelectedRouteStore(this@MainActivity).read()
            val tag = event.getStringExtra(RouteProbeClient.EXTRA_ROUTE_TAG).orEmpty()
            if (tag.isNotBlank() && tag != selected.configTag) {
                if (event.getBooleanExtra(RouteProbeClient.EXTRA_COMPLETE, false)) {
                    pendingRouteProbeId = null
                    pendingRouteProbeMetricKey = null
                    if (::latencyText.isInitialized) {
                        latencyText.isEnabled = true
                        latencyText.alpha = 1f
                    }
                    if (::action.isInitialized) {
                        action.isEnabled = true
                        action.alpha = 1f
                    }
                    updateQualityStrip()
                }
                return
            }
            val metricKey = currentHomeRouteMetricKey(selected.id) ?: return
            if (pendingRouteProbeMetricKey != null && pendingRouteProbeMetricKey != metricKey) {
                if (event.getBooleanExtra(RouteProbeClient.EXTRA_COMPLETE, false)) {
                    pendingRouteProbeId = null
                    pendingRouteProbeMetricKey = null
                    if (::latencyText.isInitialized) {
                        latencyText.isEnabled = true
                        latencyText.alpha = 1f
                    }
                    if (::action.isInitialized) {
                        action.isEnabled = true
                        action.alpha = 1f
                    }
                    updateQualityStrip()
                }
                return
            }
            if (!::latencyText.isInitialized) return
            val stage = event.getStringExtra(RouteProbeClient.EXTRA_STAGE).orEmpty()
            val elapsed = event.getLongExtra(RouteProbeClient.EXTRA_MILLISECONDS, -1L)
            val error = event.getStringExtra(RouteProbeClient.EXTRA_ERROR)
            if (stage == "cancelled") {
                if (event.getBooleanExtra(RouteProbeClient.EXTRA_COMPLETE, false) &&
                    event.getStringExtra(RouteProbeClient.EXTRA_REQUEST_ID) == pendingRouteProbeId
                ) {
                    pendingRouteProbeId = null
                    pendingRouteProbeMetricKey = null
                    latencyText.isEnabled = true
                    latencyText.alpha = 1f
                }
                return
            }
            if (stage == "latency_sample") {
                if (RouteProbeStatistics.isValidLatencyMillis(elapsed)) homePingSamples += elapsed
                RouteProbeStatistics.medianLatencyMillis(homePingSamples)?.let { median ->
                    lastRouteLatency = metricKey to median
                    homePingErrorKey = null
                }
                latencyText.setTextColor(DeyttUi.SKY)
                updateQualityStrip()
                return
            }
            if (stage == "latency_retry") {
                latencyText.setTextColor(DeyttUi.SKY)
                updateQualityStrip()
                return
            }
            when {
                RouteProbeStatistics.isValidLatencyMillis(elapsed) -> {
                    pendingRouteProbeHasOutcome = true
                    lastRouteLatency = metricKey to elapsed
                    homePingErrorKey = null
                    latencyText.setTextColor(DeyttUi.MINT)
                    val english = AppLanguage.current(this@MainActivity) == AppLanguage.EN
                    latencyText.contentDescription = if (english) {
                        "Median of three HTTPS response times via selected route after TLS: $elapsed milliseconds"
                    } else {
                        "Медиана трёх HTTPS-ответов через выбранный выход после установки TLS: $elapsed миллисекунд"
                    }
                }
                !error.isNullOrBlank() -> {
                    pendingRouteProbeHasOutcome = true
                    homePingErrorKey = metricKey
                    latencyText.setTextColor(DeyttUi.CORAL)
                    latencyText.contentDescription = uiCopy("Проверка выхода через прокси: $error")
                }
            }
            updateQualityStrip()
            if (event.getBooleanExtra(RouteProbeClient.EXTRA_COMPLETE, false)) {
                pendingRouteProbeId = null
                pendingRouteProbeMetricKey = null
                latencyText.isEnabled = true
                latencyText.alpha = 1f
                if (::action.isInitialized) {
                    action.isEnabled = true
                    action.alpha = 1f
                }
                // A request's final broadcast only closes the queue. The preceding
                // route broadcast already contains its result or error.
                if (!pendingRouteProbeHasOutcome) {
                    homePingErrorKey = metricKey
                    latencyText.setTextColor(DeyttUi.CORAL)
                    updateQualityStrip()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (openIncomingSubscription(intent)) {
            finish()
            return
        }
        if (SubscriptionStore(this).readCurrent() == null) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        StartupAnimationView.configureSystemSplash(this)
        migrateRemovedSelectedRoute()
        initialPage = savedInstanceState?.getInt(STATE_SELECTED_TAB)
            ?: intent?.getIntExtra(EXTRA_START_TAB, 0)?.coerceIn(0, 3)
            ?: 0
        // Clear approximate coordinates persisted by pre-consent builds.
        // Location approval is remembered, location data itself is not.
        IpNetworkLocationStore(this).clear()
        currentNetworkLocation = OriginLocationMemory.location
        buildScreen()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) showStartupAnimation()
        window.decorView.post { showNetworkLocationConsentIfNeeded() }
        requestNotificationPermissionIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        homeSpeedForeground = true
        mapTrafficWindow.reset()
        mapTrafficHandler.removeCallbacks(mapTrafficTick)
        mapTrafficHandler.post(mapTrafficTick)
        currentAutoRouteKey = getSharedPreferences(ConnectVpnService.STATE_PREFS, MODE_PRIVATE)
            .getString(ConnectVpnService.EXTRA_ACTIVE_ROUTE_KEY, null)
            ?.takeIf { it in setOf("nl", "de", "fi", "ru", "it", "ru-de") }
        currentAutoOutboundTag = ConnectVpnService.activeAutoOutboundTag(this)
        val filter = IntentFilter().apply {
            addAction(ConnectVpnService.ACTION_STATUS)
            addAction(ConnectVpnService.ACTION_ACTIVE_ROUTE)
        }
        ContextCompat.registerReceiver(this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(
            this,
            routeProbeReceiver,
            IntentFilter(RouteProbeClient.ACTION_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (::action.isInitialized) {
            renderStoredState()
            rebuildRouteRow()
        }
        if (hasStartedBefore && ::pageAdapter.isInitialized) {
            pageAdapter.refresh(1)
            pageAdapter.refresh(2)
        }
        hasStartedBefore = true
        refreshNetworkLocation()
        refreshTelegramAccount()
        refreshSubscriptionIfNeeded()
        if (::primaryPages.isInitialized) primaryPages.startForegroundUpdates()
        selectedRouteLatencyHandler.removeCallbacks(selectedRouteLatencyTick)
        selectedRouteLatencyHandler.post(selectedRouteLatencyTick)
        if (::pager.isInitialized) updatePrimaryPageMotion()
    }

    override fun onStop() {
        homeSpeedForeground = false
        mapTrafficHandler.removeCallbacks(mapTrafficTick)
        mapTrafficWindow.reset()
        setMapTrafficActive(false)
        cancelHomeRouteDownload()
        homeConnectQueued = false
        homeConnectGeneration++
        latencyGeneration++
        restoreAwgMeasurement?.invoke()
        runCatching { unregisterReceiver(statusReceiver) }
        runCatching { unregisterReceiver(routeProbeReceiver) }
        if (::primaryPages.isInitialized) primaryPages.stopForegroundUpdates()
        if (RouteProbeClient.isRunning(this)) {
            runCatching { RouteProbeClient.cancel(this) }
            pendingRouteProbeId = null
            pendingRouteProbeMetricKey = null
            if (::latencyText.isInitialized) {
                latencyText.text = "—"
                latencyText.isEnabled = true
                latencyText.alpha = 1f
            }
            if (::action.isInitialized) {
                action.isEnabled = true
                action.alpha = 1f
            }
        }
        selectedRouteLatencyHandler.removeCallbacks(selectedRouteLatencyTick)
        connectionProgressHandler.removeCallbacks(connectionProgressTick)
        if (::globeStage.isInitialized) DeyttUi.setStarfieldMotion(globeStage, active = false)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::pager.isInitialized) outState.putInt(STATE_SELECTED_TAB, pager.currentItem)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (openIncomingSubscription(intent)) return
        intent.getIntExtra(EXTRA_START_TAB, -1).takeIf { it in 0..3 }?.let { selectTab(it) }
    }

    private fun openIncomingSubscription(incoming: Intent?): Boolean {
        val url = SubscriptionImportLink.parse(incoming?.action, incoming?.dataString) ?: return false
        // Forward only the validated value, never the incoming Intent or its grants/extras.
        startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_SUBSCRIPTION_URL, url))
        return true
    }

    @Deprecated("Back navigation is delegated to the selected primary tab")
    override fun onBackPressed() {
        if (::pager.isInitialized && pager.currentItem != 0) {
            pager.setCurrentItem(0, true)
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        restoreAwgMeasurement?.invoke()
        if (::primaryPages.isInitialized) primaryPages.close()
        locationRequestGeneration++
        locationExecutor.shutdownNow()
        accountGeneration++
        accountExecutor.shutdownNow()
        subscriptionExecutor.shutdownNow()
        subscriptionRefreshInFlight = false
        selectedRouteLatencyHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    internal fun updateTelegramIdentity(username: String?, avatar: Bitmap?) {
        val account = username?.trim()?.removePrefix("@")?.takeIf(String::isNotBlank)
        telegramAccountName?.text = account?.let { "./$it" } ?: uiCopy(if (TelegramSessionStore.read(this) != null) "Telegram подключён" else "Войти через Telegram")
        telegramAvatarFallback?.apply {
            text = account?.take(1)?.uppercase() ?: "•"
            visibility = if (avatar == null) View.VISIBLE else View.GONE
        }
        telegramAvatarImage?.apply {
            if (avatar == null) {
                setImageDrawable(null)
                visibility = View.GONE
                alpha = 1f
            } else {
                setImageBitmap(avatar)
                visibility = View.VISIBLE
                alpha = 0f
                animate().alpha(1f).setDuration(190L).start()
            }
        }
        if (::primaryPages.isInitialized) primaryPages.updateTelegramAvatar(avatar)
    }

    internal fun updateTelegramUsername(username: String?) {
        val account = username?.trim()?.removePrefix("@")?.takeIf(String::isNotBlank)
        telegramAccountName?.text = account?.let { "./$it" } ?: uiCopy(if (TelegramSessionStore.read(this) != null) "Telegram подключён" else "Войти через Telegram")
        if (telegramAvatarImage?.drawable == null) {
            telegramAvatarFallback?.text = account?.take(1)?.uppercase() ?: "•"
            telegramAvatarFallback?.visibility = View.VISIBLE
        }
    }

    internal fun refreshTelegramAccount() {
        if (isFinishing || isDestroyed) return
        val token = TelegramSessionStore.read(this)
        TelegramIdentityCache.bind(token)
        updateTelegramIdentity(TelegramIdentityCache.username, TelegramIdentityCache.avatar)
        if (token == null) { accountGeneration++; return }
        val generation = ++accountGeneration
        accountExecutor.execute {
            // A failed photo request must never erase a successfully loaded account.
            val profileResult = runCatching { TelegramPairingClient.profile(token) }
            val imageResult = runCatching { TelegramPairingClient.avatar(token)?.let(::decodeTelegramAvatar) }
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != accountGeneration ||
                    TelegramSessionStore.read(this) != token) return@runOnUiThread
                val failure = profileResult.exceptionOrNull()
                if ((failure as? TelegramPairingException)?.code == "session_expired") {
                    TelegramSessionStore.clear(this)
                    TelegramIdentityCache.bind(null)
                    updateTelegramIdentity(null, null)
                    refreshAccountViews()
                } else {
                    val profile = profileResult.getOrNull()
                    val name = profile?.optString("username")?.ifBlank { profile.optString("first_name") }
                    TelegramIdentityCache.update(token, name, imageResult.getOrNull())
                    updateTelegramIdentity(TelegramIdentityCache.username, TelegramIdentityCache.avatar)
                }
            }
        }
    }

    internal fun refreshAccountViews() {
        if (!::pageAdapter.isInitialized) return
        for (position in 0..3) pageAdapter.refresh(position)
    }

    private fun refreshSubscriptionIfNeeded() {
        if (isFinishing || isDestroyed || subscriptionRefreshInFlight) return
        val url = SubscriptionClient.getSavedSubscriptionUrl(this)?.trim()?.takeIf(String::isNotEmpty)
        if (url == null) {
            android.util.Log.i("DeyttSubscription", "Background refresh skipped: no readable saved subscription URL")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (subscriptionRefreshLastStartedAt > 0 && now - subscriptionRefreshLastStartedAt < 30_000) return

        subscriptionRefreshInFlight = true
        subscriptionRefreshLastStartedAt = now
        subscriptionExecutor.execute {
            val result = runCatching {
                SubscriptionClient.import(this@MainActivity, url, beforeCommit = {
                    if (SubscriptionClient.getSavedSubscriptionUrl(this@MainActivity) != url) {
                        throw SubscriptionCancelledException()
                    }
                })
            }
            result.onFailure { failure ->
                val status = (failure as? SubscriptionHttpFailure)?.statusCode
                android.util.Log.w("DeyttSubscription",
                    "Background refresh failed (${failure.javaClass.simpleName}, HTTP ${status ?: 0})")
            }
            runOnUiThread {
                subscriptionRefreshInFlight = false
                if (isFinishing || isDestroyed || result.isFailure) return@runOnUiThread

                val routes = runCatching {
                    val config = SubscriptionStore(this).readCurrent() ?: return@runCatching emptyList()
                    RouteCatalog.from(config, AwgProfileStore(this).profiles(), OpenFluxProfileStore(this).profiles(this))
                }.getOrNull() ?: return@runOnUiThread
                if (routes.none { it.id == SelectedRouteStore(this).read().id }) {
                    routes.firstOrNull()?.let { SelectedRouteStore(this).save(it) }
                }
                if (::action.isInitialized) rebuildRouteRow()
                if (::pageAdapter.isInitialized) {
                    pageAdapter.refresh(1)
                    pageAdapter.refresh(2)
                }
            }
        }
    }

    private fun decodeTelegramAvatar(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > 144 || bounds.outHeight / sampleSize > 144) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun buildScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        globe = RouteGlobeView(this).apply {
            setOverviewMode(true)
            setConnected(ConnectVpnService.isRunning() || AwgTunnelController.isRunning())
            onMapNodeTapped = ::showMapRoutePicker
            focus(SelectedRouteStore(this@MainActivity).read().id, animate = false)
            setAvailableLocations(availableMapLocations())
            setUserLocation(currentNetworkLocation)
        }
        globeBackdrop = FrameLayout(this).apply {
            tag = "globe-underlay"
            clipChildren = false
            clipToPadding = false
        }
        globeBackdrop.addView(globe, FrameLayout.LayoutParams(-1, -1))
        primaryPages = PrimaryPages(this) {
            if (::pageAdapter.isInitialized) pageAdapter.flushDeferredRouteRefresh()
        }
        pageAdapter = TopLevelViewPagerAdapter(
            shouldDeferRouteRefresh = { ::primaryPages.isInitialized && primaryPages.hasActiveRouteDiagnostics() },
        ) { position ->
            if (position == 0) buildHomePage() else primaryPages.create(position)
        }
        pager = ViewPager2(this).apply {
            orientation = ViewPager2.ORIENTATION_HORIZONTAL
            offscreenPageLimit = 1
            isUserInputEnabled = true
            adapter = pageAdapter
            setPageTransformer { page, position ->
                val distance = kotlin.math.abs(position).coerceAtMost(1f)
                page.alpha = if (pageMotionEnabled) 1f - .16f * distance else 1f
                val scale = if (pageMotionEnabled) 1f - .025f * distance else 1f
                page.scaleX = scale
                page.scaleY = scale
            }
        }
        val pageStage = FrameLayout(this).apply {
            clipChildren = false
            addView(pager, FrameLayout.LayoutParams(-1, -1))
        }
        navigationBar = DeyttUi.PrimaryNavigationBar(this, initialPage) { selectTab(it) }
        val dock = FrameLayout(this).apply {
            setPadding(dp(12), 0, dp(12), dp(8))
            addView(navigationBar, FrameLayout.LayoutParams(-1, dp(66)))
        }
        val accountHeader = brandHeader().apply {
            tag = "shell-header"
            setPadding(dp(20), dp(6), dp(20), dp(6))
        }
        telegramAccountName = accountHeader.findViewWithTag("telegram-account-name")
        telegramAvatarFallback = accountHeader.findViewWithTag("telegram-avatar-fallback")
        telegramAvatarImage = accountHeader.findViewWithTag("telegram-avatar-image")
        accountHeader.setOnClickListener { selectTab(2) }
        TelegramIdentityCache.bind(TelegramSessionStore.read(this))
        updateTelegramIdentity(TelegramIdentityCache.username, TelegramIdentityCache.avatar)
        statusInset = View(this)
        pager.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val viewportHeight = bottom - top
            if (viewportHeight > 0 && viewportHeight != oldBottom - oldTop) {
                updateHomeMapAnchor()
                updatePrimaryPageMotion()
            }
        }

        shellRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "shell-starfield"
            clipChildren = false
            clipToPadding = false
        }
        val root = shellRoot
        root.addView(statusInset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
        root.addView(accountHeader, LinearLayout.LayoutParams(-1, dp(64)))
        root.addView(primaryPages.createUpdateBanner())
        root.addView(pageStage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(dock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(82)))
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val status = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout(),
            ).top
            val bottom = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout(),
            ).bottom
            (statusInset.layoutParams as LinearLayout.LayoutParams).apply {
                height = status
                statusInset.layoutParams = this
            }
            dock.setPadding(dp(12), 0, dp(12), dp(8) + bottom)
            (dock.layoutParams as LinearLayout.LayoutParams).apply {
                height = dp(82) + bottom
                dock.layoutParams = this
            }
            insets
        }
        var lastHapticPage = initialPage
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            private fun confirmSelection() {
                navigationBar.setSelectedPage(pager.currentItem)
                if (pager.currentItem != lastHapticPage) {
                    pager.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    lastHapticPage = pager.currentItem
                }
            }

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                navigationBar.setPageOffset(position, positionOffset)
                val progress = (position + positionOffset).coerceIn(0f, 1f)
                globe.setBackdropProgress(if (isReducedMotionEnabled()) if (progress >= .5f) 1f else 0f else progress)
            }

            override fun onPageSelected(position: Int) {
                syncGlobeHost(position)
                updatePrimaryPageMotion()
                homeSpeedOnHome = position == 0
                if (position == 0) { updateQualityStrip(); measureHomeRouteDownload() }
                else cancelHomeRouteDownload()
                if (pager.scrollState == ViewPager2.SCROLL_STATE_IDLE) confirmSelection()
            }

            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    confirmSelection()
                    syncGlobeHost(pager.currentItem)
                    updateHomeMapAnchor()
                }
            }
        })
        globeStage = FrameLayout(this).apply {
            tag = "globe-stage"
            background = DeyttUi.starfieldBackground(this@MainActivity)
            addView(globeBackdrop, FrameLayout.LayoutParams(-1, -1))
            addView(root, FrameLayout.LayoutParams(-1, -1))
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateHomeMapAnchor() }
        }
        setContentView(globeStage)
        ViewCompat.requestApplyInsets(root)
        pager.setCurrentItem(initialPage, false)
        root.post {
            syncGlobeHost(pager.currentItem)
            updatePrimaryPageMotion()
        }
    }

    private fun updateHomeMapAnchor() {
        val slot = homeMapSlot ?: return
        if (!::globe.isInitialized || !::pager.isInitialized || pager.currentItem != 0 ||
            pager.scrollState != ViewPager2.SCROLL_STATE_IDLE || slot.width <= 0 || slot.height <= 0) return
        val slotLocation = IntArray(2).also(slot::getLocationOnScreen)
        val globeLocation = IntArray(2).also(globe::getLocationOnScreen)
        globe.setViewportAnchor(slotLocation[0] - globeLocation[0], slotLocation[1] - globeLocation[1], slot.width, slot.height)
    }

    private fun migrateRemovedSelectedRoute(): Boolean {
        if (ConnectVpnService.isRunning() || AwgTunnelController.isRunning() || isSystemTunnelActive()) return false
        val store = SelectedRouteStore(this)
        val selected = store.read()
        if (selected.id != "openflux:DE" && selected.engine != TunnelEngine.OPENFLUX) return false
        val config = SubscriptionStore(this).readCurrent() ?: return false
        val automatic = RouteCatalog.from(config, AwgProfileStore(this).profiles())
            .firstOrNull { it.protocol == RouteProtocol.AUTO } ?: return false
        store.save(automatic)
        return true
    }

    private fun syncGlobeHost(page: Int) {
        if (!::globe.isInitialized || !::globeBackdrop.isInitialized) return
        if (page == 0) homeMapSlot?.post { updateHomeMapAnchor() }
        globe.setBackdropStage(page != 0)
        if (!::pager.isInitialized || pager.scrollState == ViewPager2.SCROLL_STATE_IDLE) {
            globe.setBackdropProgress(if (page == 0) 0f else 1f)
        }
        globe.importantForAccessibility = if (page == 0) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private fun showStartupAnimation() {
        val startup = StartupAnimationView(this, isReducedMotionEnabled())
        globeStage.addView(startup, FrameLayout.LayoutParams(-1, -1))
        startup.start()
    }

    private fun buildHomePage(): View {
        val root = screen(withBackdrop = true)
        val mapSlot = FrameLayout(this).apply {
            tag = "home-map-gesture"
            clipChildren = false
            clipToPadding = false
            minimumHeight = dp(128)
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateHomeMapAnchor() }
            setOnTouchListener { view, event ->
                RouteGlobeView.parentInterceptPolicy(event.actionMasked, true)?.let {
                    view.parent?.requestDisallowInterceptTouchEvent(it)
                }
                val slotLocation = IntArray(2).also(view::getLocationOnScreen)
                val globeLocation = IntArray(2).also(globe::getLocationOnScreen)
                val forwarded = MotionEvent.obtain(event)
                try {
                    forwarded.offsetLocation((slotLocation[0] - globeLocation[0]).toFloat(), (slotLocation[1] - globeLocation[1]).toFloat())
                    globe.dispatchTouchEvent(forwarded)
                } finally {
                    forwarded.recycle()
                }
            }
        }
        homeMapSlot = mapSlot
        root.addView(mapSlot, LinearLayout.LayoutParams(-1, 0, 1f))
        val routeHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            minimumHeight = dp(72)
            isClickable = true
            isFocusable = true
            setOnClickListener { selectTab(1) }
            pressFeedback(this)
        }
        homeDestinationFlag = text("◎", 24f).apply {
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        routeHeading.addView(homeDestinationFlag, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            marginEnd = dp(12)
        })
        routeHeading.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            homeDestinationTitle = displayText("", 25f).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            addView(homeDestinationTitle)
            routeTitleText = mono("", 12f, DeyttUi.SKY).apply { setPadding(0, dp(4), 0, 0) }
            addView(routeTitleText)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(routeHeading)
        root.addView(spacer(12, this))
        action = button("Подключить").apply { setOnClickListener { toggleTunnel() } }
        action.apply {
            tag = "connection-action"
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            textSize = 13f
            setPadding(dp(14), dp(4), dp(14), dp(4))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(action, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(spacer(12, this))
        root.addView(buildQualityStrip())
        root.addView(button("Обновить замер", secondary = true).apply {
            tag = "home-refresh"
            setOnClickListener {
                homeSpeedAttemptAt = 0L
                measureHomeRouteDownload()
                measureSelectedRoute()
            }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
        updateRouteFlow(SelectedRouteStore(this).read())
        updateNetworkLocationViews()
        root.addView(spacer(12, this))
        getSharedPreferences("profile_settings", MODE_PRIVATE).getString("subscription_warning", null)
            ?.takeIf(String::isNotBlank)
            ?.let { warning ->
                val compactWarning = if (warning.startsWith("профили amneziawg ") && warning.contains(" не загружены.")) {
                    "профиль amneziawg 3.1 не загружен. обновите подписку."
                } else warning
                root.addView(note(compactWarning, DeyttUi.AMBER).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    contentDescription = uiCopy(warning)
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                })
            }
        rebuildRouteRow()
        renderStoredState()
        root.post { if (::pager.isInitialized) syncGlobeHost(pager.currentItem) }
        return root
    }

    private fun rebuildRouteRow() {
        latencyGeneration++
        val selected = SelectedRouteStore(this).read()
        if (::globe.isInitialized) globe.focus(selected.id, animate = false)
        updateRouteFlow(selected)
        updateQualityStrip()
        if (::latencyText.isInitialized) {
            val metricKey = currentHomeRouteMetricKey(selected.id)
            val cachedLatency = lastRouteLatency?.takeIf {
                renderedPhase == VpnPhase.CONNECTED && it.first == metricKey
            }?.second
            latencyText.text = cachedLatency?.let(::formatLatency) ?: "—"
            latencyText.isEnabled = true
            latencyText.alpha = 1f
            latencyText.textSize = 23f
            latencyText.setTextColor(DeyttUi.MUTED)
            latencyText.contentDescription = uiCopy("Проверить задержку через выход ${selected.title}: два HTTPS-запроса HEAD или GET, тайм-аут 10 секунд")
        }
    }

    private fun toggleTunnel() {
        if (homeConnectQueued) return
        val connectGeneration = ++homeConnectGeneration
        latencyGeneration++
        val saved = VpnStateStore(this).read()
        val decision = VpnControlDecision.decide(
            saved.phase,
            ConnectVpnService.isRunning(),
            AwgTunnelController.isRunning(),
        )
        if (decision == VpnControlAction.START && RouteProbeClient.isRunning(this)) {
            val selected = SelectedRouteStore(this).read()
            pendingRouteProbeId = null
            pendingRouteProbeMetricKey = null
            if (::primaryPages.isInitialized) primaryPages.cancelRouteDiagnosticsForSelection()
            runCatching { RouteProbeClient.cancel(this) }
            queueSelectedTunnel(selected, connectGeneration)
            return
        }
        if (temporaryAwgMeasurement) { restoreAwgMeasurement?.invoke(); return }
        if (decision == VpnControlAction.STOP) {
            val libboxRunning = ConnectVpnService.isRunning()
            renderStatus(VpnPhase.STOPPING, "Отключаем соединение…", null)
            if (libboxRunning) {
                startService(Intent(this, ConnectVpnService::class.java).setAction(ConnectVpnService.ACTION_STOP))
            }
            AwgTunnelController.stop(this, publishStatus = false)
            awaitEnginesStopped(action = {
                VpnStateStore(this).write(VpnPhase.IDLE, VpnStateStore.IDLE_TITLE)
                renderStatus(VpnPhase.IDLE, VpnStateStore.IDLE_TITLE, null)
            })
            return
        }
        requestSelectedTunnel(SelectedRouteStore(this).read())
    }

    private fun requestSelectedTunnel(route: SelectedRoute) {
        pendingRoute = route
        action.isEnabled = true
        action.alpha = 1f
        val permission = VpnService.prepare(this)
        if (permission != null) startActivityForResult(permission, VPN_PERMISSION_REQUEST) else startSelectedTunnel()
    }

    private fun queueSelectedTunnel(selected: SelectedRoute, generation: Long) {
        homeConnectQueued = true
        action.text = uiCopy("Ждём окончания проверки…")
        action.contentDescription = action.text
        action.isEnabled = false
        action.alpha = .65f
        awaitEnginesStopped(waitForDiagnostics = true,
            isCurrent = { homeConnectQueued && generation == homeConnectGeneration },
            onFailure = {
                homeConnectQueued = false
                if (!isFinishing && !isDestroyed) { action.isEnabled = true; action.alpha = 1f; renderStoredState() }
            }, action = {
                homeConnectQueued = false
                if (VpnControlDecision.canResumeQueuedStart(
                        selected.id, SelectedRouteStore(this).read().id, generation, homeConnectGeneration,
                        ConnectVpnService.isRunning(), AwgTunnelController.isRunning(),
                    )) requestSelectedTunnel(selected) else renderStoredState()
            })
    }

    internal fun selectTab(index: Int) {
        if (!::pager.isInitialized || index !in 0..3) return
        pager.setCurrentItem(index, !isReducedMotionEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()))
    }

    internal fun openSetup() {
        startActivity(Intent(this, SetupActivity::class.java))
    }

    internal fun selectRoute(route: DeyttRoute, navigateHome: Boolean = true) {
        if (!temporaryAwgMeasurement && SelectedRouteStore(this).read().id == route.id) {
            if (navigateHome) selectTab(0)
            return
        }
        applyRouteSelection(route, navigateHome)
    }

    private fun applyRouteSelection(route: DeyttRoute, navigateHome: Boolean = true) {
        val config = if (route.engine == TunnelEngine.LIBBOX) SubscriptionStore(this).readCurrent() ?: return else null
        val connectionRequested = homeConnectQueued || pendingRoute != null
        val libboxRunning = ConnectVpnService.isRunning()
        val awgRunning = AwgTunnelController.isRunning()
        val generation = ++homeConnectGeneration
        homeConnectQueued = false
        cancelHomeRouteDownload()
        if (::primaryPages.isInitialized) primaryPages.cancelRouteDiagnosticsForSelection()
        // A manual choice owns the selection immediately; a diagnostic restore
        // must never write the old route or reconnect it after this point.
        val resumeConnection = VpnControlDecision.shouldReconnectOnSelection(
            connectionRequested, libboxRunning, awgRunning, cancelAwgMeasurementForSelection?.invoke(),
        )
        pendingRoute = null
        if (RouteProbeClient.isRunning(this)) {
            runCatching { RouteProbeClient.cancel(this) }
            pendingRouteProbeId = null
            pendingRouteProbeMetricKey = null
        }
        if (ConnectVpnService.isRunning()) {
            startService(Intent(this, ConnectVpnService::class.java).setAction(ConnectVpnService.ACTION_STOP))
        }
        AwgTunnelController.stop(this)
        if (config != null) {
            SubscriptionStore(this).saveValidated(ProfileRoutes.select(config, route.configTag))
        }
        SelectedRouteStore(this).save(route)
        if (navigateHome) pageAdapter.refresh(1)
        rebuildRouteRow()
        renderStoredState()
        if (navigateHome) selectTab(0)
        if (resumeConnection) queueSelectedTunnel(SelectedRouteStore(this).read(), generation)
    }

    internal fun cancelAwgMeasurement() { restoreAwgMeasurement?.invoke() }

    internal fun requestAwgMeasurement(
        route: DeyttRoute,
        onComplete: (Boolean) -> Unit = {},
        measure: ((Boolean) -> Unit) -> Unit,
    ) = requestAwgMeasurements(listOf(route), onComplete, {}, measure = { _, finished -> measure(finished) })

    /** One temporary app-only VPN sequence, followed by restoration of the actual previous VPN. */
    internal fun requestAwgMeasurements(
        routes: List<DeyttRoute>,
        onComplete: (Boolean) -> Unit,
        onRouteFailure: (DeyttRoute) -> Unit,
        onRouteStarting: (DeyttRoute) -> Unit = {},
        measure: (DeyttRoute, (Boolean) -> Unit) -> Unit,
    ) {
        if (temporaryAwgMeasurement || routes.isEmpty() || isFinishing || isDestroyed) {
            onComplete(false)
            return
        }
        val previous = SelectedRouteStore(this).read()
        val config = SubscriptionStore(this).readCurrent()
        val previousRoute = config?.let { RouteCatalog.from(it, AwgProfileStore(this).profiles(), OpenFluxProfileStore(this).profiles(this)) }
            ?.firstOrNull { it.id == previous.id }
        val wasConnected = ConnectVpnService.isRunning() || AwgTunnelController.isRunning()
        if (config == null || previousRoute == null || (!wasConnected && isSystemTunnelActive())) {
            routes.forEach(onRouteFailure)
            onComplete(false)
            return
        }
        if (VpnService.prepare(this) != null) {
            // Country LIBBOX diagnostics normally acquired this already. Avoid
            // automatically replacing another app's VPN permission in a batch.
            routes.forEach(onRouteFailure)
            onComplete(false)
            return
        }
        cancelHomeRouteDownload()
        temporaryAwgMeasurement = true
        runCatching { RouteProbeClient.cancel(this) }
        pendingRouteProbeId = null
        pendingRouteProbeMetricKey = null
        val app = applicationContext
        val handler = Handler(Looper.getMainLooper())
        var restorationRequested = false
        var restorationReconnectRequested = false
        var completionSent = false
        var switchedTunnel = false
        var allSuccessful = true
        var activeStep = 0
        fun complete(restored: Boolean) {
            if (completionSent) return
            completionSent = true
            temporaryAwgMeasurement = false
            restoreAwgMeasurement = null
            cancelAwgMeasurementForSelection = null
            if (restored && wasConnected) refreshNetworkLocation(force = true)
            if (!isFinishing && !isDestroyed) { rebuildRouteRow(); renderStoredState() }
            onComplete(restored && allSuccessful)
        }
        fun restorationFailed() {
            VpnStateStore(app).write(VpnPhase.ERROR, "Не удалось восстановить соединение", "Подключитесь к прежнему маршруту вручную")
            complete(false)
        }
        fun restore() {
            if (restorationRequested) return
            restorationRequested = true
            activeStep++
            if (!switchedTunnel) { complete(true); return }
            AwgTunnelController.stop(app, publishStatus = false)
            SelectedRouteStore(app).save(previousRoute)
            SubscriptionStore(app).saveValidated(config)
            pendingRoute = null
            var waitStarted = SystemClock.elapsedRealtime()
            var reconnectStarted = false
            var reconnectAt = 0L
            var retries = 0
            var transportGoneAt = 0L
            val finish = object : Runnable {
                override fun run() {
                    if (completionSent) return
                    if (SelectedRouteStore(app).read().id != previous.id) { complete(false); return }
                    val state = VpnStateStore(app).read()
                    if (reconnectStarted) {
                        val originalRunning = ConnectVpnService.isRunning()
                        when {
                            state.phase == VpnPhase.CONNECTED && originalRunning -> complete(true)
                            state.phase == VpnPhase.ERROR && !originalRunning && retries == 0 -> {
                                // A VPN replacement can invalidate an in-flight Android
                                // network generation. Retry once after complete teardown.
                                retries++
                                reconnectStarted = false
                                transportGoneAt = 0L
                                waitStarted = SystemClock.elapsedRealtime()
                                handler.postDelayed(this, 100L)
                            }
                            (state.phase == VpnPhase.ERROR && !originalRunning) || SystemClock.elapsedRealtime() - reconnectAt > 45_000L -> restorationFailed()
                            else -> handler.postDelayed(this, 100L)
                        }
                        return
                    }
                    if (AwgTunnelController.isRunning() || AwgTunnelController.isStopping() ||
                        ConnectVpnService.isRunning() || RouteProbeClient.isRunning(app) || isSystemTunnelActive()) {
                        transportGoneAt = 0L
                        if (SystemClock.elapsedRealtime() - waitStarted < 15_000L) handler.postDelayed(this, 100L)
                        else restorationFailed()
                        return
                    }
                    if (transportGoneAt == 0L) transportGoneAt = SystemClock.elapsedRealtime()
                    if (SystemClock.elapsedRealtime() - transportGoneAt < 300L) {
                        handler.postDelayed(this, 100L)
                        return
                    }
                    if (!wasConnected) {
                        VpnStateStore(app).write(VpnPhase.IDLE, VpnStateStore.IDLE_TITLE)
                        complete(true)
                        return
                    }
                    reconnectStarted = true
                    reconnectAt = SystemClock.elapsedRealtime()
                    VpnStateStore(app).write(VpnPhase.STARTING, "Восстанавливаем соединение…")
                    restorationReconnectRequested = true
                    runCatching {
                        ContextCompat.startForegroundService(app,
                            Intent(app, ConnectVpnService::class.java).setAction(ConnectVpnService.ACTION_START))
                    }.onFailure { restorationFailed() }
                    if (!completionSent) handler.postDelayed(this, 100L)
                }
            }
            handler.post(finish)
        }
        restoreAwgMeasurement = ::restore
        cancelAwgMeasurementForSelection = {
            restorationRequested = true
            activeStep++
            if (restorationReconnectRequested) {
                startService(Intent(app, ConnectVpnService::class.java).setAction(ConnectVpnService.ACTION_STOP))
            }
            AwgTunnelController.stop(app, publishStatus = false)
            complete(false)
            wasConnected
        }
        lateinit var advance: (Int) -> Unit
        advance = next@ { index ->
            if (restorationRequested || completionSent) return@next
            if (index >= routes.size || isFinishing || isDestroyed) { restore(); return@next }
            val route = routes[index]
            onRouteStarting(route)
            val step = ++activeStep
            fun failed() {
                if (restorationRequested || step != activeStep) return
                allSuccessful = false
                onRouteFailure(route)
                advance(index + 1)
            }
            fun measureReady() {
                var reported = false
                runCatching {
                    measure(route) { success -> runOnUiThread {
                        if (!reported && !restorationRequested && step == activeStep) {
                            reported = true
                            if (!success) allSuccessful = false
                            advance(index + 1)
                        }
                    } }
                }.onFailure { failed() }
            }
            if (!switchedTunnel && previous.id == route.id && AwgTunnelController.isRunning() &&
                VpnStateStore(app).read().phase == VpnPhase.CONNECTED) {
                measureReady()
                return@next
            }
            switchedTunnel = true
            if (ConnectVpnService.isRunning()) startService(Intent(this, ConnectVpnService::class.java).setAction(ConnectVpnService.ACTION_STOP))
            AwgTunnelController.stop(app, publishStatus = false)
            SelectedRouteStore(app).save(route)
            awaitEnginesStopped(waitForDiagnostics = true, onFailure = { allSuccessful = false; restore() }, action = {
                if (!restorationRequested && step == activeStep) {
                    val raw = AwgProfileStore(app).read(route.id)
                    if (raw == null) failed()
                    else {
                        AwgTunnelController.start(app, raw, route.id, diagnosticOnly = true)
                        val began = SystemClock.elapsedRealtime()
                        val check = object : Runnable {
                            override fun run() {
                                if (restorationRequested || step != activeStep) return
                                val state = VpnStateStore(app).read()
                                when {
                                    isFinishing || isDestroyed -> restore()
                                    state.phase == VpnPhase.ERROR || SystemClock.elapsedRealtime() - began > 30_000L -> failed()
                                    state.phase == VpnPhase.CONNECTED && AwgTunnelController.isRunning() -> measureReady()
                                    else -> handler.postDelayed(this, 100L)
                                }
                            }
                        }
                        handler.postDelayed(check, 100L)
                    }
                }
            })
        }
        advance(0)
    }

    private fun showMapRoutePicker(node: String) {
        val english = AppLanguage.current(this) == AppLanguage.EN
        fun mapCopy(ru: String, en: String): String = (if (english) en else ru).lowercase()
        if (node == "user") {
            val place = currentNetworkLocation?.placeLabel?.let { AppLanguage.locationLabel(this, it) } ?: mapCopy("Точка входа с этого устройства", "This device’s entry point")
            AppDialog.Builder(this)
                .setTitle(mapCopy("Точка входа", "Entry point"))
                .setMessage(mapCopy("$place — примерное место по IP. Это не сервер выхода и на выбор маршрута не влияет.", "$place is an approximate location based on your IP. It is not an exit server and does not affect route selection."))
                .setPositiveButton(mapCopy("Понятно", "Got it"), null)
                .show()
            return
        }
        val countryCode = when (node) {
            "nl" -> "NL"
            "de" -> "DE"
            "fi" -> "FI"
            "ru" -> "RU"
            "it" -> "IT"
            else -> return
        }
        val config = SubscriptionStore(this).readCurrent() ?: return
        val routes = RouteCatalog.from(config, AwgProfileStore(this).profiles(), OpenFluxProfileStore(this).profiles(this))
        val automatic = routes.firstOrNull { it.protocol == RouteProtocol.AUTO }
        val countryRoutes = routes.filter {
            it.countryCode == countryCode && (
                it.engine == TunnelEngine.AMNEZIAWG || it.engine == TunnelEngine.OPENFLUX ||
                    it.engine == TunnelEngine.LIBBOX &&
                    it.protocol in setOf(RouteProtocol.VLESS, RouteProtocol.TROJAN, RouteProtocol.HYSTERIA2)
                )
        }
        val doubleRoute = routes.firstOrNull {
            it.engine == TunnelEngine.LIBBOX && it.protocol == RouteProtocol.RU_DE
        }.takeIf { countryCode == "RU" || countryCode == "DE" }
        val choices = listOfNotNull(automatic) + countryRoutes + listOfNotNull(doubleRoute)
        val countryName = when (countryCode) {
            "NL" -> mapCopy("нидерланды", "netherlands")
            "DE" -> mapCopy("германия", "germany")
            "FI" -> mapCopy("финляндия", "finland")
            "IT" -> mapCopy("италия", "italy")
            else -> mapCopy("россия", "russia")
        }
        if (choices.isEmpty()) {
            AppDialog.Builder(this)
                .setTitle(countryName)
                .setMessage(mapCopy("Для этой точки пока нет маршрута в подписке.", "Your subscription has no route for this location yet."))
                .setPositiveButton(mapCopy("Открыть маршруты", "Open routes")) { _, _ -> selectTab(1) }
                .setNegativeButton(mapCopy("Закрыть", "Close"), null)
                .show()
            return
        }

        val selectedId = SelectedRouteStore(this).read().id
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(22))
            background = rounded(DeyttUi.SURFACE, 26f, DeyttUi.LINE)
        }
        View(this).apply {
            background = rounded(0xFF465368.toInt(), 2f, 0xFF465368.toInt())
        }.also { panel.addView(it, LinearLayout.LayoutParams(dp(34), dp(4)).apply { gravity = Gravity.CENTER }) }
        panel.addView(mono(mapCopy("выход", "exit") + "  ·  $countryName", 9f, DeyttUi.SKY, 600).apply {
            letterSpacing = .08f
            setPadding(0, dp(18), 0, dp(5))
        })
        panel.addView(text(countryName, 23f, DeyttUi.TEXT).apply {
            letterSpacing = -.03f
            setPadding(0, 0, 0, dp(4))
        })
        panel.addView(text(mapCopy("Выберите способ подключения или проверьте протоколы.", "Choose a connection method or compare protocols."), 12f, DeyttUi.MUTED).apply {
            setPadding(0, 0, 0, dp(12))
        })
        if (ConnectVpnService.isRunning() || AwgTunnelController.isRunning()) {
            panel.addView(note(mapCopy("Смена выхода остановит текущее соединение. Новый маршрут нужно будет запустить снова.", "Changing the exit stops your current connection. Connect again to use the new route."), DeyttUi.AMBER))
            panel.addView(spacer(8, this))
        }

        val options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        choices.forEachIndexed { index, route ->
            val selected = route.id == selectedId
            val title = when (route.protocol) {
                RouteProtocol.AUTO -> mapCopy("автоподбор", "auto-select")
                RouteProtocol.RU_DE -> mapCopy("lte + белые списки · ru → de", "lte + whitelist · ru → de")
                else -> route.protocol.title
            }
            val detail = when (route.protocol) {
                RouteProtocol.AUTO -> mapCopy("Выбрать доступный выход автоматически", "Select an available exit automatically")
                RouteProtocol.RU_DE -> mapCopy("Двойной маршрут · Санкт-Петербург → Франкфурт", "Double route · Saint Petersburg → Frankfurt")
                RouteProtocol.HYSTERIA2 -> mapCopy("быстрый quic-маршрут", "fast quic route")
                else -> uiCopy(route.protocol.detail)
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
                setPadding(dp(13), dp(8), dp(13), dp(8))
                background = rounded(if (selected) DeyttUi.SELECTED else DeyttUi.SURFACE_2, 16f,
                    if (selected) DeyttUi.SELECTED_LINE else DeyttUi.LINE)
                isClickable = true
                isFocusable = true
                contentDescription = "$title. $detail${if (selected) mapCopy(". Текущий маршрут", ". Current route") else ""}"
                addView(text(route.flag, 19f, DeyttUi.TEXT).apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(11) })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(text(title, 15f, DeyttUi.TEXT))
                    addView(text(detail, 10.5f, DeyttUi.MUTED).apply {
                        setPadding(0, dp(3), 0, 0)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (selected) addView(mono(mapCopy("выбран", "selected"), 8f, DeyttUi.MINT, 600))
                setOnClickListener {
                    if ((Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()) &&
                        !getSharedPreferences("profile_settings", MODE_PRIVATE).getBoolean("reduced_motion", false)) {
                        animate().cancel()
                        scaleX = .985f
                        scaleY = .985f
                        animate().scaleX(1f).scaleY(1f).setDuration(170L).start()
                    }
                    sheetRouteSelection(route)
                }
            }
            options.addView(row)
            if (index < choices.lastIndex) options.addView(spacer(7, this))
            if ((Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()) &&
                !getSharedPreferences("profile_settings", MODE_PRIVATE).getBoolean("reduced_motion", false)) {
                row.alpha = 0f
                row.translationY = dp(7).toFloat()
                row.postDelayed({
                    if (!isFinishing && !isDestroyed) row.animate().alpha(1f).translationY(0f).setDuration(180L).start()
                }, index * 24L)
            }
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(options)
        }
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val comparableCode = countryCode.takeIf { countryRoutes.size > 1 }
        if (comparableCode != null) {
            panel.addView(button(mapCopy("Сравнить протоколы", "Compare protocols"), secondary = true).apply {
                setOnClickListener {
                    selectTab(1)
                    currentMapRouteSheet?.dismiss()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(12) })
        }

        val dialog = Dialog(this)
        currentMapRouteSheet = dialog
        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(.55f)
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val reducedMotion = getSharedPreferences("profile_settings", MODE_PRIVATE).getBoolean("reduced_motion", false)
        if (!reducedMotion && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled())) {
            panel.alpha = 0f
            panel.translationY = dp(26).toFloat()
            panel.animate().alpha(1f).translationY(0f).setDuration(230L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.7f)).start()
        }
    }

    private var currentMapRouteSheet: Dialog? = null

    private fun sheetRouteSelection(route: DeyttRoute) {
        currentMapRouteSheet?.dismiss()
        currentMapRouteSheet = null
        selectRoute(route)
    }

    private fun updatePrimaryPageMotion() {
        if (!::pageAdapter.isInitialized) return
        val reduced = isReducedMotionEnabled() ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled())
        pageMotionEnabled = !reduced
        if (::globe.isInitialized) globe.setReducedMotion(reduced)
        if (::globeStage.isInitialized) {
            DeyttUi.setStarfieldMotion(globeStage, active = !isFinishing, reducedMotion = reduced)
        }
        pager.requestTransform()
    }

    private fun setSelectedDestination(label: String) {
        val hasFlag = label.codePointAt(0) in 0x1F1E6..0x1F1FF
        val flag = if (hasFlag) label.substringBefore(' ') else "◎"
        val title = if (hasFlag) label.substringAfter(' ') else label
        if (::homeDestinationTitle.isInitialized) {
            homeDestinationFlag.text = flag
            homeDestinationTitle.text = title.lowercase(java.util.Locale.ROOT)
            homeDestinationTitle.contentDescription = uiCopy("Выбранный выход: $title")
        }
    }

    private fun selectedExitLabel(selected: SelectedRoute): String {
        val config = SubscriptionStore(this).readCurrent()
        val routes = config?.let { runCatching { RouteCatalog.from(it, AwgProfileStore(this).profiles(), OpenFluxProfileStore(this).profiles(this)) }.getOrNull() }.orEmpty()
        val activeAutoRoute = ActiveRouteSelection.configuredAutoRoute(
            selected,
            currentAutoOutboundTag,
            routes,
            renderedPhase == VpnPhase.CONNECTED,
        )
        if (activeAutoRoute != null) {
            return "${activeAutoRoute.flag} ./${activeAutoRoute.countryCode.lowercase(java.util.Locale.ROOT)}"
        }
        val route = routes.firstOrNull { it.id == selected.id }
        if (route == null) return uiCopy(selected.title)
        val city = when (route.countryCode) {
            "NL" -> "Амстердам"
            "DE", "RU-DE" -> "Франкфурт"
            "RU" -> "Санкт-Петербург"
            "FI" -> "Хельсинки"
            "IT" -> "Милан"
            else -> return uiCopy(selected.title)
        }
        return (if (route.countryCode == "RU-DE") "🇩🇪" else route.flag) + " " + uiCopy(city)
    }

    private fun updateRouteFlow(selected: SelectedRoute) {
        setSelectedDestination(selectedExitLabel(selected))
        updateNetworkLocationViews()
    }

    private fun updateNetworkLocationViews() {
        val enabled = isNetworkLocationEnabled()
        val tunnelActive = ConnectVpnService.isRunning() || AwgTunnelController.isRunning()
        if (::globe.isInitialized) {
            globe.setConnected(tunnelActive)
            globe.setEgressCountry(if (enabled && tunnelActive) currentEgressLocation?.countryCode else null)
            val isAutoRoute = SelectedRouteStore(this).read().id == "auto"
            if (!tunnelActive || !isAutoRoute || currentAutoRouteKey != null) {
                globe.setActiveAutoRoute(currentAutoRouteKey.takeIf { tunnelActive && isAutoRoute })
            }
        }
    }

    internal fun isNetworkLocationEnabled(): Boolean =
        getSharedPreferences(PROFILE_SETTINGS, MODE_PRIVATE).getBoolean(KEY_NETWORK_LOCATION_ENABLED, false)

    internal fun isReducedMotionEnabled(): Boolean =
        getSharedPreferences(PROFILE_SETTINGS, MODE_PRIVATE).getBoolean("reduced_motion", false)

    internal fun setNetworkLocationEnabled(enabled: Boolean) {
        getSharedPreferences(PROFILE_SETTINGS, MODE_PRIVATE).edit { putBoolean(KEY_NETWORK_LOCATION_ENABLED, enabled) }
        if (!enabled) {
            locationRequestGeneration++
            locationRequestInFlight = false
            locationLookupFailed = false
            egressRequestGeneration++
            egressRequestInFlight = false
            egressLookupFailed = false
            currentNetworkLocation = null
            currentEgressLocation = null
            IpNetworkLocationStore(this).clear()
            OriginLocationMemory.location = null
            if (::globe.isInitialized) globe.setUserLocation(null)
        }
        updateNetworkLocationViews()
        if (enabled) refreshNetworkLocation(force = true)
    }

    private fun showNetworkLocationConsentIfNeeded() {
        val preferences = getSharedPreferences(PROFILE_SETTINGS, MODE_PRIVATE)
        if (preferences.contains(KEY_NETWORK_LOCATION_ENABLED) || isFinishing || isDestroyed) return

        val dialog = Dialog(this)
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
            background = rounded(DeyttUi.SURFACE, 24f, DeyttUi.LINE)
        }
        sheet.addView(mono("КАРТА · ПРИВАТНОСТЬ", 9f, DeyttUi.MUTED, 700))
        sheet.addView(text("Показывать ваш примерный регион?", 20f, DeyttUi.TEXT, android.graphics.Typeface.BOLD).apply {
            setPadding(0, dp(10), 0, dp(8))
        })
        sheet.addView(text(
            "Точка определяется по публичному IP через ipinfo.io — это не GPS и на выбор маршрута не влияет. Запрос видит внешний сервис; приложение не сохраняет адрес или координаты, а держит точку только в памяти до закрытия.",
            13f,
            DeyttUi.MUTED,
        ).apply { setLineSpacing(dp(3).toFloat(), 1f) })
        sheet.addView(button("Показывать на карте").apply {
            setOnClickListener {
                dialog.dismiss()
                setNetworkLocationEnabled(true)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(18) })
        sheet.addView(text("не сейчас", 13f, DeyttUi.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dialog.dismiss()
                setNetworkLocationEnabled(false)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setContentView(sheet)
        dialog.setOnCancelListener { setNetworkLocationEnabled(false) }
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(.62f)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            decorView.setPadding(dp(14), 0, dp(14), dp(20))
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun availableMapLocations(): Set<String> {
        val config = SubscriptionStore(this).readCurrent().orEmpty()
        val routes = runCatching { RouteCatalog.from(config, AwgProfileStore(this).profiles(), OpenFluxProfileStore(this).profiles(this)) }.getOrDefault(emptyList())
        return routes.flatMap { route ->
            when (route.countryCode.uppercase()) {
                "NL" -> listOf("nl")
                "DE" -> listOf("de")
                "FI" -> listOf("fi")
                "RU" -> listOf("ru")
                "IT" -> listOf("it")
                "RU-DE" -> listOf("ru", "de")
                else -> emptyList()
            }
        }.toSet()
    }

    internal fun setReducedMotionEnabled(enabled: Boolean) {
        getSharedPreferences(PROFILE_SETTINGS, MODE_PRIVATE).edit { putBoolean("reduced_motion", enabled) }
        if (::pager.isInitialized) updatePrimaryPageMotion()
    }

    private fun refreshNetworkLocation(force: Boolean = false) {
        if (!isNetworkLocationEnabled()) {
            updateNetworkLocationViews()
            return
        }
        val tunnelActive = ConnectVpnService.isRunning() || AwgTunnelController.isRunning()
        if (tunnelActive) {
            if (currentNetworkLocation == null) refreshOriginOutsideVpn()
            if (ConnectVpnService.isRunning()) {
                val config = SubscriptionStore(this).readCurrent()
                if (config != null && !isHomeApplicationRoutedByTunnel(config)) {
                    egressLookupFailed = true
                    egressRequestInFlight = false
                    updateNetworkLocationViews()
                    return
                }
            }
            // Sample the auto route's public exit once per VPN session. A load
            // balancer can answer separate probes from different exits; using
            // each response as a new map target makes the route appear to jump.
            if (currentEgressLocation != null || egressRequestInFlight) {
                updateNetworkLocationViews()
                return
            }
            val generation = ++egressRequestGeneration
            egressRequestInFlight = true
            egressLookupFailed = false
            updateNetworkLocationViews()
            locationExecutor.execute {
                val result = runCatching { IpNetworkLocationClient.fetch() }
                runOnUiThread {
                    if (generation != egressRequestGeneration || isFinishing || isDestroyed) return@runOnUiThread
                    egressRequestInFlight = false
                    if (isNetworkLocationEnabled() &&
                        (ConnectVpnService.isRunning() || AwgTunnelController.isRunning())
                    ) {
                        result.onSuccess { location ->
                            currentEgressLocation = location
                            egressLookupFailed = false
                            android.util.Log.i("DeyttIpLocation", "Approximate tunnel egress region is available in memory")
                        }
                        result.onFailure { failure ->
                            egressLookupFailed = true
                            android.util.Log.w("DeyttIpLocation", "Tunnel egress lookup failed (${failure.javaClass.simpleName})")
                        }
                    }
                    updateNetworkLocationViews()
                }
            }
            updateNetworkLocationViews()
            return
        }
        if (egressRequestInFlight || currentEgressLocation != null) {
            egressRequestGeneration++
            egressRequestInFlight = false
            egressLookupFailed = false
            currentEgressLocation = null
        }
        if (currentNetworkLocation != null && !force) {
            updateNetworkLocationViews()
            return
        }
        if (locationRequestInFlight) {
            updateNetworkLocationViews()
            return
        }

        val generation = ++locationRequestGeneration
        locationRequestInFlight = true
        locationLookupFailed = false
        android.util.Log.i("DeyttIpLocation", "Lookup started")
        updateNetworkLocationViews()
        locationExecutor.execute {
            val result = runCatching { IpNetworkLocationClient.fetch() }
            runOnUiThread {
                if (generation != locationRequestGeneration || isFinishing || isDestroyed) return@runOnUiThread
                locationRequestInFlight = false
                if (isNetworkLocationEnabled() && !ConnectVpnService.isRunning() && !AwgTunnelController.isRunning()) {
                    result.onSuccess { location ->
                        locationLookupFailed = false
                        currentNetworkLocation = location
                        OriginLocationMemory.location = location
                        android.util.Log.i("DeyttIpLocation", "Approximate location is available in memory")
                        if (::globe.isInitialized) globe.setUserLocation(location)
                    }
                    result.onFailure { failure ->
                        locationLookupFailed = true
                        android.util.Log.w("DeyttIpLocation", "Lookup failed (${failure.javaClass.simpleName})")
                    }
                }
                updateNetworkLocationViews()
            }
        }
    }

    private fun refreshOriginOutsideVpn() {
        if (locationRequestInFlight || !isNetworkLocationEnabled()) return
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val network = manager.allNetworks.firstOrNull { candidate ->
            val capabilities = manager.getNetworkCapabilities(candidate)
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } ?: return
        val generation = ++locationRequestGeneration
        locationRequestInFlight = true
        locationExecutor.execute {
            val result = runCatching { IpNetworkLocationClient.fetch(network) }
            runOnUiThread {
                if (isDestroyed || isFinishing || generation != locationRequestGeneration) return@runOnUiThread
                locationRequestInFlight = false
                if (isNetworkLocationEnabled()) {
                    result.onSuccess {
                        currentNetworkLocation = it
                        OriginLocationMemory.location = it
                        if (::globe.isInitialized) globe.setUserLocation(it)
                    }
                    locationLookupFailed = result.isFailure
                }
                updateNetworkLocationViews()
            }
        }
    }

    private fun buildQualityStrip(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(12), 0, dp(12))
        qualityDots = DiagnosticDotsView(this@MainActivity)
        fun metric(label: String, value: TextView, tagName: String, showQuality: Boolean = false, onClick: () -> Unit) = LinearLayout(this@MainActivity).apply {
            tag = tagName
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minimumHeight = dp(76)
            setPadding(dp(12), 0, dp(12), 0)
            isClickable = true
            isFocusable = true
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(text(label, 11f, DeyttUi.MUTED), LinearLayout.LayoutParams(0, -2, 1f))
                if (showQuality) addView(qualityDots, LinearLayout.LayoutParams(dp(20), dp(18)))
            })
            value.minHeight = dp(38)
            value.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            value.doAfterTextChanged { editable ->
                val content = editable.toString()
                val measured = content == "—" || content.firstOrNull()?.isDigit() == true || content.startsWith("↓")
                value.textSize = if (measured) 23f else 13f
            }
            addView(value, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
            setOnClickListener { onClick() }
            pressFeedback(this)
        }
        latencyText = text("—", 23f, DeyttUi.TEXT, android.graphics.Typeface.BOLD).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.START
            setOnClickListener { measureSelectedRoute() }
        }
        addView(metric(uiCopy("Задержка"), latencyText, "home-latency", onClick = ::measureSelectedRoute), LinearLayout.LayoutParams(0, -2, 1f))
        addView(View(this@MainActivity).apply { setBackgroundColor(DeyttUi.LINE) },
            LinearLayout.LayoutParams(dp(1), dp(56)))
        downloadText = text("—", 23f, DeyttUi.TEXT, android.graphics.Typeface.BOLD).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        addView(metric("Скорость", downloadText, "home-speed", showQuality = true) {
            selectTab(if (TelegramSessionStore.read(this@MainActivity) == null) 2 else 1)
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun updateQualityStrip() {
        if (!::downloadText.isInitialized) return
        val selected = SelectedRouteStore(this).read()
        val config = SubscriptionStore(this).readCurrent()
        val routes = config?.let { runCatching { RouteCatalog.from(it, AwgProfileStore(this).profiles(), OpenFluxProfileStore(this).profiles(this)) }.getOrNull() }.orEmpty()
        val route = routes.firstOrNull { it.id == selected.id }
        val connected = renderedPhase == VpnPhase.CONNECTED
        val activeAutoRoute = ActiveRouteSelection.configuredAutoRoute(selected, currentAutoOutboundTag, routes, connected)
        if (::routeTitleText.isInitialized) routeTitleText.text = when (route?.protocol) {
            RouteProtocol.AUTO -> activeAutoRoute?.let { uiCopy(it.protocol.title).replace(" ", "") } ?: "auto"
            RouteProtocol.RU_DE -> "ru → de"
            else -> route?.let { "${it.countryCode.lowercase()}./${uiCopy(it.protocol.title).replace(" ", "")}" } ?: uiCopy(selected.title)
        }
        val metricKey = currentHomeRouteMetricKey(selected.id)
        val speedNeedsLogin = HomeMetricPresentation.speedNeedsLogin(connected, metricKey)
        val recentSpeedSample = homeSpeedSample?.takeIf {
            SystemClock.elapsedRealtime() - homeSpeedSampleAt in 0..(15 * 60_000L)
        }
        val recentSpeed = HomeMetricPresentation.value(connected, metricKey, recentSpeedSample?.first, recentSpeedSample?.second)
        val speedError = HomeMetricPresentation.value(connected, metricKey, homeSpeedErrorKey, homeSpeedError)
        val speedBypassed = HomeMetricPresentation.isCurrent(connected, metricKey, homeSpeedBypassedKey)
        val mbps = recentSpeed?.times(8.0)?.div(1_000_000.0)
        val en = AppLanguage.current(this) == AppLanguage.EN
        val latency = HomeMetricPresentation.value(connected, metricKey, lastRouteLatency?.first, lastRouteLatency?.second)
            ?.takeIf(RouteProbeStatistics::isValidLatencyMillis)
        val pingError = HomeMetricPresentation.isCurrent(connected, metricKey, homePingErrorKey)
        val pingBypassed = HomeMetricPresentation.isCurrent(connected, metricKey, homePingBypassedKey)
        downloadText.text = when {
            speedBypassed -> HomeMetricPresentation.bypassLabel(en)
            speedNeedsLogin -> if (en) "sign in" else "войдите"
            homeSpeedInFlight && mbps != null -> String.format(
                java.util.Locale.US,
                if (en) "↓ %.1f Mbps…" else "↓ %.1f Мбит/с…",
                mbps,
            )
            homeSpeedInFlight -> if (en) "sampling…" else "замер…"
            speedError != null -> if (en) "unavailable" else "недоступна"
            mbps == null -> "—"
            else -> String.format(java.util.Locale.US, if (en) "%.1f Mbps" else "%.1f Мбит/с", mbps)
        }
        latencyText.text = when {
            pingBypassed -> HomeMetricPresentation.bypassLabel(en)
            pingError -> if (en) "no response" else "нет ответа"
            latency != null -> formatLatency(latency)
            else -> "—"
        }
        latencyText.setTextColor(when {
            pingBypassed -> DeyttUi.AMBER
            pingError -> DeyttUi.CORAL
            connected && (selectedRouteLatencyInFlight || pendingRouteProbeId != null) -> DeyttUi.SKY
            latency != null -> DeyttUi.MINT
            else -> DeyttUi.MUTED
        })
        val sampleSeconds = RouteProxyProbe.QUICK_DOWNLOAD_MILLIS / 1_000
        downloadText.contentDescription = when {
            speedBypassed -> uiCopy("Скорость не измеряется: приложение исключено из VPN текущими настройками обхода")
            speedNeedsLogin -> if (en) "Sign in to Telegram to measure speed. Tap to open your profile."
                else "Для замера скорости войдите через Telegram. Нажмите, чтобы открыть профиль."
            speedError != null -> uiCopy(speedError)
            connected -> uiCopy("Скорость загрузки, проба $sampleSeconds с. Нажмите для проверки маршрутов.")
            else -> uiCopy("Скорость проверяется после подключения")
        }
        latencyText.contentDescription = when {
            pingBypassed -> uiCopy("HTTPS-отклик не проходит через VPN при текущих настройках обхода")
            pingError -> uiCopy("Отклик: нет ответа через выбранный маршрут")
            latency != null -> uiCopy("Время HTTPS-ответа через выбранный маршрут: ${latency} миллисекунд")
            connected -> uiCopy("Проверить время HTTPS-ответа через выбранный маршрут")
            else -> uiCopy("Отклик проверяется после подключения")
        }
        qualityDots.setLoading(
            connected && (selectedRouteLatencyInFlight || pendingRouteProbeId != null || homeSpeedInFlight),
        )
    }

    private fun currentHomeRouteMetricKey(routeId: String): HomeRouteMetricKey? {
        val config = SubscriptionStore(this).readCurrent() ?: return null
        val route = runCatching {
            RouteCatalog.from(config, AwgProfileStore(this).profiles(), OpenFluxProfileStore(this).profiles(this))
                .firstOrNull { it.id == routeId }
        }.getOrNull() ?: return null
        return HomeRouteMetricKey(
            routeId = route.id,
            sessionToken = TelegramSessionStore.read(this),
            configTag = route.configTag,
            activeAutoRouteKey = currentAutoRouteKey.takeIf { route.protocol == RouteProtocol.AUTO },
            activeAutoOutboundTag = currentAutoOutboundTag.takeIf { route.protocol == RouteProtocol.AUTO },
            connectionGeneration = homeConnectionGeneration,
        )
    }

    private fun isHomeApplicationRoutedByTunnel(config: String): Boolean {
        val split = UserSplitTunnelStore(this)
        return RouteProxyProbe.isApplicationRoutedByTunnel(
            config,
            packageName,
            customPackages = split.readPackages(),
            splitMode = split.readMode(),
        )
    }

    private fun clearHomeMetricSamples() {
        homePingSamples.clear()
        lastRouteLatency = null
        homePingErrorKey = null
        homePingBypassedKey = null
        homeSpeedSample = null
        homeSpeedSampleAt = 0L
        homeSpeedError = null
        homeSpeedErrorKey = null
        homeSpeedBypassedKey = null
    }

    private fun cancelHomeRouteDownload() {
        homeSpeedGeneration++
        homeSpeedFuture?.cancel(true)
        homeSpeedFuture = null
        homeSpeedInFlight = false
    }

    /** One bounded real throughput sample after connection, then at most once a minute on Home. */
    private fun measureHomeRouteDownload() {
        if (!homeSpeedForeground || !homeSpeedOnHome || isFinishing || isDestroyed || homeSpeedInFlight) return
        if (renderedPhase != VpnPhase.CONNECTED || homeConnectQueued || temporaryAwgMeasurement || RouteProbeClient.isRunning(this)) return
        val selected = SelectedRouteStore(this).read()
        if (selected.id != homeSpeedConnectedRouteId) return
        val now = SystemClock.elapsedRealtime()
        if (now - homeSpeedConnectedAt < 1_500L || (homeSpeedAttemptAt > 0L && now - homeSpeedAttemptAt < 60_000L)) return
        val token = TelegramSessionStore.read(this) ?: return
        val config = SubscriptionStore(this).readCurrent() ?: return
        val route = RouteCatalog.from(config, AwgProfileStore(this).profiles(), OpenFluxProfileStore(this).profiles(this)).firstOrNull { it.id == selected.id } ?: return
        val metricKey = currentHomeRouteMetricKey(selected.id) ?: return
        if (route.engine == TunnelEngine.LIBBOX && !isHomeApplicationRoutedByTunnel(config)) {
            homeSpeedSample = null
            homeSpeedSampleAt = 0L
            homeSpeedError = null
            homeSpeedErrorKey = null
            homeSpeedBypassedKey = metricKey
            updateQualityStrip()
            return
        }
        if (route.engine == TunnelEngine.LIBBOX && !ConnectVpnService.isRunning()) return
        if (route.engine == TunnelEngine.AMNEZIAWG && !AwgTunnelController.isRunning()) return
        homeSpeedAttemptAt = now
        homeSpeedInFlight = true
        homeSpeedError = null
        homeSpeedErrorKey = metricKey
        homeSpeedBypassedKey = null
        val generation = homeSpeedGeneration
        val transferActive = {
            generation == homeSpeedGeneration && homeSpeedForeground && homeSpeedOnHome &&
                !temporaryAwgMeasurement && !RouteProbeClient.isRunning(this) && renderedPhase == VpnPhase.CONNECTED &&
                (if (route.engine == TunnelEngine.AMNEZIAWG) AwgTunnelController.isRunning() else ConnectVpnService.isRunning())
        }
        val stillCurrent = {
            transferActive() && SelectedRouteStore(this).read().id == selected.id &&
                TelegramSessionStore.read(this) == token && currentHomeRouteMetricKey(selected.id) == metricKey &&
                (route.engine != TunnelEngine.LIBBOX || isHomeApplicationRoutedByTunnel(config))
        }
        updateQualityStrip()
        homeSpeedFuture = LatencyExecutor.pool.submit {
            val result = runCatching {
                RouteProxyProbe.measureSystemDownload(
                    token,
                    stillCurrent,
                    sampleMillis = RouteProxyProbe.QUICK_DOWNLOAD_MILLIS,
                    transferActive = transferActive,
                )
            }
            runOnUiThread {
                if (generation != homeSpeedGeneration) return@runOnUiThread
                homeSpeedInFlight = false
                homeSpeedFuture = null
                if (!stillCurrent() || isFinishing || isDestroyed) {
                    if (!isFinishing && !isDestroyed && renderedPhase == VpnPhase.CONNECTED &&
                        SelectedRouteStore(this).read().id == selected.id &&
                        currentHomeRouteMetricKey(selected.id) == metricKey && route.engine == TunnelEngine.LIBBOX &&
                        !isHomeApplicationRoutedByTunnel(config)
                    ) {
                        homeSpeedSample = null
                        homeSpeedSampleAt = 0L
                        homeSpeedError = null
                        homeSpeedErrorKey = null
                        homeSpeedBypassedKey = metricKey
                        updateQualityStrip()
                    }
                    return@runOnUiThread
                }
                result.getOrNull()?.takeIf { it > 0L }?.let { speed ->
                    homeSpeedError = null
                    homeSpeedErrorKey = null
                    homeSpeedSample = metricKey to speed
                    homeSpeedSampleAt = SystemClock.elapsedRealtime()
                    val latency = lastRouteLatency?.takeIf { it.first == metricKey }?.second
                    if (latency != null) RouteQualityStore.write(this, RouteProbeSample(selected.id, latency, speed))
                } ?: run {
                    homeSpeedError = result.exceptionOrNull()?.let {
                        RouteProbePresentation.failure(it, AppLanguage.current(this) == AppLanguage.EN)
                    }
                    homeSpeedErrorKey = metricKey.takeIf { homeSpeedError != null }
                }
                updateQualityStrip()
            }
        }
    }

    private fun buildTrafficSummary(): View {
        val metadata = SubscriptionMetadataStore(this).read()
        val uploaded = metadata.uploadBytes.coerceAtLeast(0)
        val downloaded = metadata.downloadBytes.coerceAtLeast(0)
        val used = (uploaded.toDouble() + downloaded.toDouble()).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(14))
            background = rounded(DeyttUi.SURFACE, 18f, DeyttUi.LINE)
        }
        panel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(this@MainActivity.mono("ТРАФИК ПОДПИСКИ", 8f, DeyttUi.MUTED, 600),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text(if (metadata.totalBytes > 0) "из ${formatBytes(metadata.totalBytes)}" else "без лимита", 10f, DeyttUi.MUTED))
        })
        panel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(formatBytes(used), 19f, DeyttUi.TEXT, android.graphics.Typeface.BOLD),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { topMargin = dp(5) })
            addView(text(if (used > 0) "передано" else "нет данных", 10f, DeyttUi.MUTED).apply { setPadding(0, dp(5), 0, 0) })
        })
        panel.addView(TrafficUsageBar(
            this,
            uploaded = uploaded,
            downloaded = downloaded,
            quota = metadata.totalBytes,
            description = uiCopy(if (metadata.totalBytes > 0) {
                "Использовано ${formatBytes(used)} из ${formatBytes(metadata.totalBytes)}. Скачано ${formatBytes(downloaded)}, отправлено ${formatBytes(uploaded)}."
            } else {
                "Передано ${formatBytes(used)} без заданного лимита. Скачано ${formatBytes(downloaded)}, отправлено ${formatBytes(uploaded)}."
            }),
        ), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(10) })
        val legend = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(text("↓ ${formatBytes(downloaded)}", 10f, DeyttUi.SKY),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text("↑ ${formatBytes(uploaded)}", 10f, DeyttUi.MINT))
        }
        panel.addView(legend)
        return panel
    }

    private fun formatBytes(value: Long): String {
        val english = AppLanguage.current(this) == AppLanguage.EN
        if (value <= 0) return if (english) "0 B" else "0 Б"
        val units = if (english) arrayOf("B", "KB", "MB", "GB", "TB") else arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        var amount = value.toDouble()
        var unit = 0
        while (amount >= 1024 && unit < units.lastIndex) {
            amount /= 1024
            unit++
        }
        return if (unit == 0) "${amount.toLong()} ${units[unit]}"
        else String.format(java.util.Locale.US, "%.1f %s", amount, units[unit])
    }

    private fun formatLatency(milliseconds: Long): String =
        if (AppLanguage.current(this) == AppLanguage.EN) "$milliseconds ms" else "$milliseconds мс"

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
        }
    }

    @Deprecated("Android VPN permission API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ROUTE_PROBE_PERMISSION_REQUEST) {
            val continuation = pendingRouteProbeAuthorization
            pendingRouteProbeAuthorization = null
            if (resultCode == RESULT_OK) {
                if (continuation != null) continuation() else measureSelectedRoute()
            } else if (::latencyText.isInitialized && continuation == null) {
                latencyText.text = "—"
                latencyText.isEnabled = true
                latencyText.alpha = 1f
                latencyText.contentDescription = uiCopy("Для проверки через выход требуется системное разрешение Android")
            }
            return
        }
        if (requestCode == VPN_PERMISSION_REQUEST) {
            if (resultCode == RESULT_OK && pendingRoute != null) startSelectedTunnel()
            else pendingRoute = null
        }
    }

    private fun startSelectedTunnel() {
        val route = pendingRoute ?: SelectedRouteStore(this).read()
        val generation = homeConnectGeneration
        val libboxRunning = ConnectVpnService.isRunning()
        val awgRunning = AwgTunnelController.isRunning()
        if (libboxRunning) {
            startService(Intent(this, ConnectVpnService::class.java).setAction(ConnectVpnService.ACTION_STOP))
        }
        if (awgRunning) {
            AwgTunnelController.stop(this, publishStatus = false)
        }
        if (libboxRunning || awgRunning || AwgTunnelController.isStopping()) {
            awaitEnginesStopped(isCurrent = {
                generation == homeConnectGeneration && route.id == SelectedRouteStore(this).read().id
            }, action = { startSelectedTunnelAfterStop(route) })
            return
        }
        startSelectedTunnelAfterStop(route)
    }

    private fun startSelectedTunnelAfterStop(route: SelectedRoute) {
        if (route.engine == TunnelEngine.AMNEZIAWG) {
            val store = AwgProfileStore(this)
            val config = store.read(route.id)
            if (config == null) {
                renderStatus(VpnPhase.ERROR, "ошибка запуска соединения", "обновите подписку: профиль ${route.subtitle.lowercase()} отсутствует")
                return
            }
        }
        val intent = Intent(this, ConnectVpnService::class.java).setAction(ConnectVpnService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun awaitEnginesStopped(
        action: () -> Unit,
        attempt: Int = 0,
        waitForDiagnostics: Boolean = false,
        onFailure: (() -> Unit)? = null,
        isCurrent: () -> Boolean = { true },
    ) {
        if (!isCurrent()) return
        if (isFinishing || isDestroyed) {
            onFailure?.invoke()
            return
        }
        if (!ConnectVpnService.isRunning() &&
            !AwgTunnelController.isRunning() &&
            !AwgTunnelController.isStopping() &&
            (!waitForDiagnostics || !RouteProbeClient.isRunning(this))
        ) {
            action()
            return
        }
        if (attempt >= if (waitForDiagnostics) 300 else 100) {
            onFailure?.invoke()
            renderStatus(VpnPhase.ERROR, "Не удалось завершить предыдущее соединение", "Попробуйте отключить его ещё раз")
            return
        }
        window.decorView.postDelayed({
            awaitEnginesStopped(action, attempt + 1, waitForDiagnostics, onFailure, isCurrent)
        }, 50L)
    }

    private fun renderStoredState() {
        val snapshot = VpnStateStore(this).reconcile(
            ConnectVpnService.isRunning(),
            AwgTunnelController.isRunning(),
            systemTunnelActive = isSystemTunnelActive(),
        )
        renderStatus(snapshot.phase, snapshot.title, snapshot.detail)
    }

    private fun isSystemTunnelActive(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return false
        return connectivity.allNetworks.any { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private fun sampleMapTraffic() {
        val connectedOnHome = homeSpeedForeground &&
            ::pager.isInitialized && pager.currentItem == 0 &&
            renderedPhase == VpnPhase.CONNECTED && isSystemTunnelActive()
        val active = if (connectedOnHome) {
            mapTrafficWindow.observe(
                rxBytes = TrafficStats.getTotalRxBytes(),
                txBytes = TrafficStats.getTotalTxBytes(),
                nowElapsedMs = SystemClock.elapsedRealtime(),
            )
        } else {
            mapTrafficWindow.reset()
            false
        }
        setMapTrafficActive(active)
    }

    private fun setMapTrafficActive(active: Boolean) {
        if (mapTrafficActive == active) return
        mapTrafficActive = active
        if (::globe.isInitialized) globe.setTrafficActive(active)
    }

    private fun renderStatus(phase: VpnPhase?, status: String?, error: String?) {
        if (!::action.isInitialized) return
        val currentPhase = phase ?: VpnPhase.IDLE
        val previousPhase = renderedPhase
        val isConnecting = currentPhase == VpnPhase.STARTING || currentPhase == VpnPhase.CHECKING
        if (isConnecting) {
            ensureConnectionProgressStarted(previousPhase)
        } else {
            connectionProgressHandler.removeCallbacks(connectionProgressTick)
            getSharedPreferences(PROFILE_SETTINGS, MODE_PRIVATE).edit {
                remove(KEY_CONNECTION_STARTED_ELAPSED)
            }
        }
        renderedPhase = currentPhase
        if (currentPhase == VpnPhase.IDLE && migrateRemovedSelectedRoute() && ::globe.isInitialized) {
            rebuildRouteRow()
        }
        if (previousPhase != currentPhase) {
            cancelHomeRouteDownload()
            if (currentPhase == VpnPhase.CONNECTED) {
                homeConnectionGeneration++
                clearHomeMetricSamples()
                pendingRouteProbeId = null
                pendingRouteProbeMetricKey = null
                pendingRouteProbeHasOutcome = false
                selectedRouteLatencyInFlight = false
                homeSpeedConnectedRouteId = SelectedRouteStore(this).read().id
                homeSpeedConnectedAt = SystemClock.elapsedRealtime()
                homeSpeedAttemptAt = 0L
                selectedRouteLatencyHandler.postDelayed({ measureHomeRouteDownload() }, 1_800L)
            } else homeSpeedConnectedRouteId = null
            if (!temporaryAwgMeasurement &&
                (currentPhase == VpnPhase.CONNECTED || currentPhase == VpnPhase.IDLE || currentPhase == VpnPhase.ERROR)
            ) {
                refreshNetworkLocation(force = true)
            }
            val selected = SelectedRouteStore(this).read()
            if (selected.id == "auto") updateRouteFlow(selected)
        }
        if (isConnecting) {
            connectionProgressHandler.removeCallbacks(connectionProgressTick)
            connectionProgressHandler.postDelayed(connectionProgressTick, CONNECTION_PROGRESS_INTERVAL_MS)
        }
        val actionLabel = when (currentPhase) {
            VpnPhase.STARTING, VpnPhase.CHECKING -> progressDetail(currentPhase, connectionElapsedSeconds())
            VpnPhase.CONNECTED -> "Подключено"
            VpnPhase.STOPPING -> "Отключаем…"
            VpnPhase.ERROR -> "Ошибка соединения · повторить"
            VpnPhase.IDLE -> "Подключить"
        }
        action.text = uiCopy(actionLabel)
        val errorDetail = if (currentPhase == VpnPhase.ERROR) error ?: status else null
        action.contentDescription = when (currentPhase) {
            VpnPhase.CONNECTED -> uiCopy(actionLabel) + ". " + uiCopy("Отключить")
            else -> uiCopy(actionLabel) + if (!errorDetail.isNullOrBlank()) ". ${uiCopy(errorDetail)}" else ""
        }
        val colors = when (currentPhase) {
            VpnPhase.CONNECTED -> 0xD82AAE86.toInt() to 0xC015765C.toInt()
            VpnPhase.STARTING, VpnPhase.CHECKING -> 0xD92BA5D6.toInt() to 0xC0156A9A.toInt()
            VpnPhase.STOPPING -> 0xB65B6D83.toInt() to 0x9D303E51.toInt()
            VpnPhase.ERROR -> 0xD9D95E78.toInt() to 0xC0833449.toInt()
            VpnPhase.IDLE -> 0xD9269CF2.toInt() to 0xC01066CD.toInt()
        }
        action.background = glassButtonBackground(colors.first, colors.second, 16f)
        action.isEnabled = currentPhase != VpnPhase.STOPPING && !homeConnectQueued
        action.alpha = if (action.isEnabled) 1f else .66f
        if (::downloadText.isInitialized) updateQualityStrip()
    }

    private fun ensureConnectionProgressStarted(previousPhase: VpnPhase?) {
        val prefs = getSharedPreferences(PROFILE_SETTINGS, MODE_PRIVATE)
        val now = SystemClock.elapsedRealtime()
        val previousWasConnecting = previousPhase == VpnPhase.STARTING || previousPhase == VpnPhase.CHECKING
        val savedStart = prefs.getLong(KEY_CONNECTION_STARTED_ELAPSED, 0L)
        if ((!previousWasConnecting && previousPhase != null) || savedStart <= 0L || savedStart > now) {
            prefs.edit { putLong(KEY_CONNECTION_STARTED_ELAPSED, now) }
        }
    }

    private fun connectionElapsedSeconds(): Long {
        val now = SystemClock.elapsedRealtime()
        val startedAt = getSharedPreferences(PROFILE_SETTINGS, MODE_PRIVATE)
            .getLong(KEY_CONNECTION_STARTED_ELAPSED, now)
            .takeIf { it in 1L..now }
            ?: now
        return ((now - startedAt) / 1_000L).coerceAtLeast(0L)
    }

    private fun progressDetail(phase: VpnPhase, elapsedSeconds: Long): String {
        val hours = elapsedSeconds / 3_600L
        val minutes = (elapsedSeconds / 60L) % 60L
        val seconds = elapsedSeconds % 60L
        val elapsed = if (hours > 0L) {
            "$hours:${minutes.twoDigits()}:${seconds.twoDigits()}"
        } else {
            "${(elapsedSeconds / 60L).twoDigits()}:${seconds.twoDigits()}"
        }
        val stage = if (phase == VpnPhase.CHECKING) "Проверяем маршрут и интернет" else "Настраиваем защищённый туннель"
        return "$stage · $elapsed"
    }

    private fun renderConnectionProgressDetail(phase: VpnPhase) {
        if (!::action.isInitialized) return
        action.text = uiCopy(progressDetail(phase, connectionElapsedSeconds()))
        action.contentDescription = action.text.toString()
    }

    private fun Long.twoDigits(): String = if (this < 10L) "0$this" else toString()

    private fun measureSelectedRoute() {
        if (!::latencyText.isInitialized) return
        if (RouteProbeClient.isRunning(this) || selectedRouteLatencyInFlight) return
        if (homeConnectQueued || temporaryAwgMeasurement || renderedPhase in setOf(VpnPhase.STARTING, VpnPhase.CHECKING, VpnPhase.STOPPING)) return
        if (renderedPhase != VpnPhase.CONNECTED) {
            latencyText.contentDescription = uiCopy("HTTPS-отклик проверяется после подключения")
            updateQualityStrip()
            latencyText.text = uiCopy("Отклик: после подключения").substringAfter(": ")
            return
        }
        val tunnelActive = ConnectVpnService.isRunning() || AwgTunnelController.isRunning()
        if (!tunnelActive) {
            latencyText.contentDescription = uiCopy("HTTPS-отклик проверяется после подключения")
            latencyText.isEnabled = true
            latencyText.alpha = 1f
            updateQualityStrip()
            latencyText.text = uiCopy("Отклик: после подключения").substringAfter(": ")
            return
        }
        val generation = ++latencyGeneration
        homePingSamples.clear()
        val selected = SelectedRouteStore(this).read()
        val config = SubscriptionStore(this).readCurrent() ?: return
        val route = RouteCatalog.from(config, AwgProfileStore(this).profiles(), OpenFluxProfileStore(this).profiles(this))
            .firstOrNull { it.id == selected.id } ?: return
        val metricKey = currentHomeRouteMetricKey(selected.id) ?: return
        val method = RouteProbePreferences.method(this)
        val previousLatency = lastRouteLatency?.takeIf {
            renderedPhase == VpnPhase.CONNECTED && it.first == metricKey && RouteProbeStatistics.isValidLatencyMillis(it.second)
        }?.second
        homePingErrorKey = null
        homePingBypassedKey = null
        latencyText.text = previousLatency?.let(::formatLatency) ?: "—"
        latencyText.isEnabled = false
        latencyText.alpha = .65f
        updateQualityStrip()
        if (tunnelActive) {
            val appBypassed = route.engine == TunnelEngine.LIBBOX && !isHomeApplicationRoutedByTunnel(config)
            if (route.id != selected.id || appBypassed) {
                if (appBypassed) {
                    homePingSamples.clear()
                    lastRouteLatency = null
                    homePingBypassedKey = metricKey
                    homePingErrorKey = null
                    updateQualityStrip()
                }
                latencyText.text = uiCopy(if (appBypassed) "Отклик: в обход VPN" else "Отклик: отключите соединение").substringAfter(": ")
                latencyText.isEnabled = true
                latencyText.alpha = 1f
                latencyText.setTextColor(DeyttUi.AMBER)
                latencyText.contentDescription = if (appBypassed) uiCopy("HTTPS-отклик не проходит через VPN при текущих настройках обхода")
                    else uiCopy("Чтобы проверить другой выход, сначала отключите текущее соединение")
                return
            }
            if (route.engine == TunnelEngine.AMNEZIAWG && !AwgTunnelController.isRunning()) {
                latencyText.text = uiCopy("Отклик: подключите AmneziaWG").substringAfter(": ")
                latencyText.isEnabled = true
                latencyText.alpha = 1f
                latencyText.setTextColor(DeyttUi.AMBER)
                latencyText.contentDescription = uiCopy("Для HTTP-проверки подключите выбранный профиль AmneziaWG")
                return
            }
            selectedRouteLatencyInFlight = true
            updateQualityStrip()
            LatencyExecutor.pool.execute {
                val elapsed = runCatching {
                    RouteProxyProbe.measureThroughSystemVpn(method)
                }.getOrNull()
                runOnUiThread {
                    if (!HomeMetricPresentation.ownsRequest(generation, latencyGeneration)) return@runOnUiThread
                    selectedRouteLatencyInFlight = false
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (currentHomeRouteMetricKey(selected.id) != metricKey) {
                        latencyText.isEnabled = true
                        latencyText.alpha = 1f
                        updateQualityStrip()
                        return@runOnUiThread
                    }
                    if (route.engine == TunnelEngine.LIBBOX && !isHomeApplicationRoutedByTunnel(config)) {
                        homePingSamples.clear()
                        lastRouteLatency = null
                        homePingBypassedKey = metricKey
                        homePingErrorKey = null
                        latencyText.isEnabled = true
                        latencyText.alpha = 1f
                        updateQualityStrip()
                        return@runOnUiThread
                    }
                    val validElapsed = elapsed?.takeIf(RouteProbeStatistics::isValidLatencyMillis)
                    if (validElapsed != null) {
                        lastRouteLatency = metricKey to validElapsed
                        homePingErrorKey = null
                    } else {
                        homePingErrorKey = metricKey
                    }
                    if (validElapsed != null && homePingSamples.isEmpty()) homePingSamples += validElapsed
                    latencyText.isEnabled = true
                    latencyText.alpha = 1f
                    latencyText.setTextColor(if (validElapsed != null) DeyttUi.MINT else DeyttUi.CORAL)
                    latencyText.contentDescription = validElapsed?.let {
                        uiCopy("Медиана HTTPS-ответов через активное соединение: ${it} миллисекунд")
                    } ?: uiCopy("HTTPS-проверка через активное соединение не ответила за 8 секунд")
                    updateQualityStrip()
                }
            }
            return
        }
        if (route.engine == TunnelEngine.AMNEZIAWG) {
            latencyText.text = uiCopy("Отклик: подключите AmneziaWG").substringAfter(": ")
            latencyText.isEnabled = true
            latencyText.alpha = 1f
            latencyText.setTextColor(DeyttUi.AMBER)
            latencyText.contentDescription = uiCopy("Для HTTP-проверки подключите выбранный профиль AmneziaWG")
            return
        }
        if (route.engine == TunnelEngine.OPENFLUX) {
            latencyText.text = uiCopy("Отклик: после подключения").substringAfter(": ")
            latencyText.isEnabled = true
            latencyText.alpha = 1f
            return
        }
        val vpnPermission = VpnService.prepare(this)
        if (vpnPermission != null) {
            latencyText.text = "—"
            latencyText.isEnabled = true
            latencyText.alpha = 1f
            showRouteProbePermissionSheet(vpnPermission)
            return
        }
        try {
            pendingRouteProbeHasOutcome = false
            pendingRouteProbeMetricKey = metricKey
            homePingSamples.clear()
            pendingRouteProbeId = RouteProbeClient.start(
                this, config, listOf(route.configTag), method, TelegramSessionStore.read(this), latencyOnly = true,
            )
            if (previousLatency == null) latencyText.text = "—"
            latencyText.isEnabled = true
            latencyText.alpha = 1f
            latencyText.setTextColor(DeyttUi.SKY)
            latencyText.contentDescription = uiCopy("Проверяю выход тремя HTTPS-запросами методом ${method.wireValue}, общий тайм-аут 8 секунд")
            updateQualityStrip()
        } catch (_: Throwable) {
            if (generation == latencyGeneration && !isFinishing && !isDestroyed) {
                homePingErrorKey = metricKey
                latencyText.text = uiCopy("Отклик: ошибка").substringAfter(": ")
                latencyText.isEnabled = true
                latencyText.alpha = 1f
                latencyText.setTextColor(DeyttUi.CORAL)
                latencyText.contentDescription = uiCopy("Не удалось запустить проверку через прокси")
                updateQualityStrip()
            }
        }
    }

    private fun showRouteProbePermissionSheet(permission: Intent) {
        val localized: (String, String) -> String = { ru, en ->
            if (AppLanguage.current(this) == AppLanguage.EN) en else ru
        }
        val dialog = Dialog(this)
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(18))
            background = rounded(DeyttUi.SURFACE, 24f, DeyttUi.LINE)
        }
        sheet.addView(mono(localized("ДИАГНОСТИКА · РАЗОВОЕ РАЗРЕШЕНИЕ", "DIAGNOSTICS · ONE-TIME PERMISSION"), 9f, DeyttUi.MUTED, 650))
        sheet.addView(text(localized("Разрешить проверку маршрутов?", "Allow route diagnostics?"), 19f, DeyttUi.TEXT, android.graphics.Typeface.BOLD).apply {
            setPadding(0, dp(10), 0, dp(7))
        })
        sheet.addView(text(
            localized(
                "Android запросит системное разрешение для замера. Для AmneziaWG тестовый туннель временно подключится, а после проверки приложение вернёт прежний маршрут.",
                "Android will request system permission for route diagnostics. AmneziaWG briefly connects a test tunnel, then restores your previous route.",
            ),
            13f,
            DeyttUi.MUTED,
        ).apply { setLineSpacing(dp(3).toFloat(), 1f) })
        sheet.addView(button(localized("Продолжить", "Continue")).apply {
            setOnClickListener {
                dialog.dismiss()
                startActivityForResult(permission, ROUTE_PROBE_PERMISSION_REQUEST)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(16) })
        sheet.addView(text(localized("отмена", "cancel"), 13f, DeyttUi.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(13), 0, dp(2))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                pendingRouteProbeAuthorization = null
                dialog.dismiss()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setContentView(sheet)
        dialog.setOnCancelListener { pendingRouteProbeAuthorization = null }
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(.62f)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setGravity(Gravity.BOTTOM)
            decorView.setPadding(dp(14), 0, dp(14), dp(20))
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    internal fun requestRouteProbePermission(afterGrant: () -> Unit): Boolean {
        val permission = VpnService.prepare(this) ?: return false
        pendingRouteProbeAuthorization = afterGrant
        showRouteProbePermissionSheet(permission)
        return true
    }

    companion object {
        const val EXTRA_START_TAB = "space.deytt.connect.extra.START_TAB"
        private const val STATE_SELECTED_TAB = "selected_primary_tab"
        private const val VPN_PERMISSION_REQUEST = 701
        private const val NOTIFICATION_REQUEST = 702
        private const val ROUTE_PROBE_PERMISSION_REQUEST = 703
        private const val PROFILE_SETTINGS = "profile_settings"
        private const val KEY_NETWORK_LOCATION_ENABLED = "network_location_enabled"
        private const val KEY_CONNECTION_STARTED_ELAPSED = "connection_started_elapsed"
        private const val CONNECTION_PROGRESS_INTERVAL_MS = 1_000L
        private const val SELECTED_ROUTE_LATENCY_INTERVAL_MS = 5_000L
        private const val MAP_TRAFFIC_SAMPLE_INTERVAL_MS = 420L
        private const val MAP_TRAFFIC_GRACE_MS = 2_800L
    }
}

private class TrafficUsageBar(
    context: Context,
    private val uploaded: Long,
    private val downloaded: Long,
    private val quota: Long,
    description: String,
) : View(context) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val track = android.graphics.RectF()
    private val fill = android.graphics.RectF()

    init {
        contentDescription = description
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        if (h <= 0f || width <= 0) return
        track.set(0f, 0f, width.toFloat(), h)
        paint.color = DeyttUi.SURFACE_2
        canvas.drawRoundRect(track, h / 2f, h / 2f, paint)

        val total = uploaded.toDouble().coerceAtLeast(0.0) + downloaded.toDouble().coerceAtLeast(0.0)
        val fraction = when {
            total <= 0.0 -> 0.0
            quota > 0 -> (total / quota.toDouble()).coerceIn(0.0, 1.0)
            else -> 1.0
        }
        val filledWidth = (width * fraction).toFloat()
        if (filledWidth <= 0f) return
        fill.set(0f, 0f, filledWidth, h)
        val downloadedEnd = filledWidth * (downloaded.toDouble().coerceAtLeast(0.0) / total).toFloat()
        canvas.save()
        canvas.clipRect(0f, 0f, downloadedEnd, h)
        paint.color = DeyttUi.SKY
        canvas.drawRoundRect(fill, h / 2f, h / 2f, paint)
        canvas.restore()
        canvas.save()
        canvas.clipRect(downloadedEnd, 0f, filledWidth, h)
        paint.color = DeyttUi.MINT
        canvas.drawRoundRect(fill, h / 2f, h / 2f, paint)
        canvas.restore()
    }
}
