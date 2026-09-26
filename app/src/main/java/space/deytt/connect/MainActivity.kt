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
import android.net.TrafficStats
import android.view.Gravity
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
import androidx.viewpager2.widget.ViewPager2
import space.deytt.connect.AppLanguage.uiCopy
import space.deytt.connect.DeyttUi.button
import space.deytt.connect.DeyttUi.actionLabel
import space.deytt.connect.DeyttUi.brandHeader
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.rounded
import space.deytt.connect.DeyttUi.screen
import space.deytt.connect.DeyttUi.sectionLabel
import space.deytt.connect.DeyttUi.spacer
import space.deytt.connect.DeyttUi.text
import space.deytt.connect.DeyttUi.note
import space.deytt.connect.DeyttUi.mapPanel
import space.deytt.connect.DeyttUi.mono

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var statusDot: View
    private lateinit var globe: RouteGlobeView
    private lateinit var action: TextView
    private lateinit var latencyText: TextView
    private lateinit var pager: ViewPager2
    private lateinit var navigationBar: DeyttUi.PrimaryNavigationBar
    private lateinit var pageAdapter: TopLevelViewPagerAdapter
    private lateinit var primaryPages: PrimaryPages
    private lateinit var statusInset: View
    private lateinit var originPlaceText: TextView
    private lateinit var originHintText: TextView
    private lateinit var middleNode: View
    private lateinit var firstHopArrow: View
    private lateinit var secondHopArrow: View
    private lateinit var middlePlaceText: TextView
    private lateinit var destinationText: TextView
    private lateinit var egressHintText: TextView
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
    private var locationRequestInFlight = false
    private var locationLookupFailed = false
    private var locationRequestGeneration = 0
    private var egressRequestInFlight = false
    private var egressLookupFailed = false
    private var egressRequestGeneration = 0
    private var pendingRouteProbeId: String? = null
    private var pendingRouteProbeAuthorization: (() -> Unit)? = null
    private var selectedRouteLatencyInFlight = false
    private val locationExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val accountExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var accountGeneration = 0
    private val connectionProgressHandler = Handler(Looper.getMainLooper())
    private val selectedRouteLatencyHandler = Handler(Looper.getMainLooper())
    private val trafficSampleHandler = Handler(Looper.getMainLooper())
    private val mapTrafficActivity = TrafficActivityWindow(TRAFFIC_VISIBILITY_WINDOW_MS)
    private val trafficSample = object : Runnable {
        override fun run() {
            if (!shouldSampleMapTraffic()) {
                mapTrafficActivity.reset()
                if (::globe.isInitialized) globe.setTrafficEnabled(false)
                return
            }
            // UID counters omit other apps routed through Android's system VPN.
            // Device totals are sampled only while the VPN transport is active.
            val trafficActive = mapTrafficActivity.observe(
                TrafficStats.getTotalRxBytes(),
                TrafficStats.getTotalTxBytes(),
                SystemClock.elapsedRealtime(),
            )
            if (::globe.isInitialized) globe.setTrafficEnabled(trafficActive)
            trafficSampleHandler.postDelayed(this, TRAFFIC_SAMPLE_INTERVAL_MS)
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
            if (!RouteProbeClient.isRunning(this@MainActivity)) measureSelectedRoute()
            selectedRouteLatencyHandler.postDelayed(this, SELECTED_ROUTE_LATENCY_INTERVAL_MS)
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val phase = intent?.getStringExtra(ConnectVpnService.STATE_PHASE)
                ?.let { runCatching { VpnPhase.valueOf(it) }.getOrNull() }
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
            if (event.getStringExtra(RouteProbeClient.EXTRA_REQUEST_ID) != pendingRouteProbeId) return
            val selected = SelectedRouteStore(this@MainActivity).read()
            val tag = event.getStringExtra(RouteProbeClient.EXTRA_ROUTE_TAG).orEmpty()
            if (tag.isNotBlank() && tag != selected.configTag) return
            if (!::latencyText.isInitialized) return
            val elapsed = event.getLongExtra(RouteProbeClient.EXTRA_MILLISECONDS, -1L)
            val error = event.getStringExtra(RouteProbeClient.EXTRA_ERROR)
            when {
                elapsed >= 0L -> {
                    latencyText.text = formatLatency(elapsed)
                    latencyText.setTextColor(DeyttUi.MINT)
                    latencyText.contentDescription = uiCopy("Задержка через выбранный выход: ${elapsed} миллисекунд, два HTTPS-запроса HEAD или GET через прокси")
                }
                !error.isNullOrBlank() -> {
                    latencyText.text = uiCopy(if (error.contains("10 с")) "тайм-аут" else "нет ответа")
                    latencyText.setTextColor(DeyttUi.CORAL)
                    latencyText.contentDescription = uiCopy("Проверка выхода через прокси: $error")
                }
            }
            if (event.getBooleanExtra(RouteProbeClient.EXTRA_COMPLETE, false)) {
                pendingRouteProbeId = null
                latencyText.isEnabled = true
                latencyText.alpha = 1f
                if (::action.isInitialized) {
                    action.isEnabled = true
                    action.alpha = 1f
                }
                if (elapsed < 0L && error.isNullOrBlank()) latencyText.text = uiCopy("пинг")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { data ->
            val incomingUrl = data.getQueryParameter("url") ?: data.getQueryParameter("subscription")
            if (!incomingUrl.isNullOrBlank()) {
                startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_SUBSCRIPTION_URL, incomingUrl))
                finish()
                return
            }
        }
        if (SubscriptionStore(this).readCurrent() == null) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        initialPage = savedInstanceState?.getInt(STATE_SELECTED_TAB)
            ?: intent?.getIntExtra(EXTRA_START_TAB, 0)?.coerceIn(0, 3)
            ?: 0
        // Clear approximate coordinates persisted by pre-consent builds.
        // Location approval is remembered, location data itself is not.
        IpNetworkLocationStore(this).clear()
        currentNetworkLocation = null
        buildScreen()
        window.decorView.post { showNetworkLocationConsentIfNeeded() }
        requestNotificationPermissionIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ConnectVpnService.ACTION_STATUS)
        ContextCompat.registerReceiver(this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(
            this,
            routeProbeReceiver,
            IntentFilter(RouteProbeClient.ACTION_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (::statusText.isInitialized) {
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
        if (::primaryPages.isInitialized) primaryPages.startForegroundUpdates()
        selectedRouteLatencyHandler.removeCallbacks(selectedRouteLatencyTick)
        selectedRouteLatencyHandler.post(selectedRouteLatencyTick)
    }

    override fun onStop() {
        runCatching { unregisterReceiver(statusReceiver) }
        runCatching { unregisterReceiver(routeProbeReceiver) }
        if (::primaryPages.isInitialized) primaryPages.stopForegroundUpdates()
        if (RouteProbeClient.isRunning(this)) {
            runCatching { RouteProbeClient.cancel(this) }
            pendingRouteProbeId = null
            if (::latencyText.isInitialized) {
                latencyText.text = "пинг"
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
        trafficSampleHandler.removeCallbacks(trafficSample)
        if (::globe.isInitialized) globe.setTrafficEnabled(false)
        if (::pageAdapter.isInitialized) {
            pageAdapter.cachedPages().forEach { (_, page) -> DeyttUi.setStarfieldMotion(page, active = false) }
        }
        if (::statusInset.isInitialized) DeyttUi.setStarfieldMotion(statusInset, active = false)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::pager.isInitialized) outState.putInt(STATE_SELECTED_TAB, pager.currentItem)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getIntExtra(EXTRA_START_TAB, -1).takeIf { it in 0..3 }?.let { selectTab(it) }
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
        if (::primaryPages.isInitialized) primaryPages.close()
        locationRequestGeneration++
        locationExecutor.shutdownNow()
        accountGeneration++
        accountExecutor.shutdownNow()
        trafficSampleHandler.removeCallbacksAndMessages(null)
        selectedRouteLatencyHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    internal fun updateTelegramIdentity(username: String?, avatar: Bitmap?) {
        val account = username?.trim()?.removePrefix("@")?.takeIf(String::isNotBlank)
        telegramAccountName?.text = account?.let { "./$it" } ?: uiCopy("./c · без аккаунта")
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
    }

    internal fun updateTelegramUsername(username: String?) {
        val account = username?.trim()?.removePrefix("@")?.takeIf(String::isNotBlank)
        telegramAccountName?.text = account?.let { "./$it" } ?: uiCopy("./c · без аккаунта")
        if (telegramAvatarImage?.drawable == null) {
            telegramAvatarFallback?.text = account?.take(1)?.uppercase() ?: "•"
            telegramAvatarFallback?.visibility = View.VISIBLE
        }
    }

    internal fun refreshTelegramAccount() {
        if (isFinishing || isDestroyed) return
        val token = TelegramSessionStore.read(this)
        if (token == null) {
            accountGeneration++
            updateTelegramIdentity(null, null)
            return
        }
        val generation = ++accountGeneration
        accountExecutor.execute {
            val result = runCatching {
                val profile = TelegramPairingClient.profile(token)
                    ?: throw TelegramPairingException("profile_unavailable")
                val username = profile.optString("username").ifBlank { profile.optString("first_name") }
                val imageBytes = TelegramPairingClient.avatar(token)
                val image = imageBytes?.let(::decodeTelegramAvatar)
                username to image
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != accountGeneration ||
                    TelegramSessionStore.read(this) != token
                ) return@runOnUiThread
                result.onSuccess { (username, image) -> updateTelegramIdentity(username, image) }
                result.onFailure { error ->
                    if ((error as? TelegramPairingException)?.code == "session_expired") {
                        TelegramSessionStore.clear(this)
                        updateTelegramIdentity(null, null)
                        refreshAccountViews()
                    }
                }
            }
        }
    }

    internal fun refreshAccountViews() {
        if (!::pageAdapter.isInitialized) return
        for (position in 0..3) pageAdapter.refresh(position)
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

        primaryPages = PrimaryPages(this)
        pageAdapter = TopLevelViewPagerAdapter { position ->
            if (position == 0) buildHomePage() else primaryPages.create(position)
        }
        pager = ViewPager2(this).apply {
            orientation = ViewPager2.ORIENTATION_HORIZONTAL
            offscreenPageLimit = 1
            isUserInputEnabled = true
            adapter = pageAdapter
        }
        navigationBar = DeyttUi.PrimaryNavigationBar(this, initialPage) { selectTab(it) }
        // The inset is clipped to system bars but draws against the page's coordinate space.
        statusInset = View(this).apply { setBackgroundColor(DeyttUi.BG) }
        pager.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val viewportHeight = bottom - top
            if (viewportHeight > 0 && viewportHeight != oldBottom - oldTop) {
                statusInset.background = DeyttUi.starfieldBackground(this@MainActivity, viewportHeight)
                updatePrimaryPageMotion(pager.currentItem)
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(DeyttUi.BG)
            clipChildren = false
            clipToPadding = false
        }
        root.addView(statusInset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
        root.addView(pager, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(navigationBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)))
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
            navigationBar.setSystemBottomInset(bottom)
            (navigationBar.layoutParams as LinearLayout.LayoutParams).apply {
                height = dp(66) + bottom
                navigationBar.layoutParams = this
            }
            insets
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                navigationBar.setPageOffset(position, positionOffset)
            }

            override fun onPageSelected(position: Int) {
                navigationBar.setSelectedPage(position)
                updatePrimaryPageMotion(position)
                updateMapTrafficSampling()
            }
        })
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        pager.setCurrentItem(initialPage, false)
        root.post { updatePrimaryPageMotion(pager.currentItem) }
    }

    private fun buildHomePage(): View {
        val root = screen(withBackdrop = true)
        val accountHeader = brandHeader()
        telegramAccountName = accountHeader.findViewWithTag("telegram-account-name")
        telegramAvatarFallback = accountHeader.findViewWithTag("telegram-avatar-fallback")
        telegramAvatarImage = accountHeader.findViewWithTag("telegram-avatar-image")
        root.addView(accountHeader)
        root.addView(spacer(14, this))

        globe = RouteGlobeView(this).apply {
            onMapNodeTapped = ::showMapRoutePicker
            focus(SelectedRouteStore(this@MainActivity).read().id, animate = false)
            setAvailableLocations(availableMapLocations())
            setUserLocation(currentNetworkLocation)
        }
        root.addView(mapPanel(globe), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(312)))
        root.addView(spacer(10, this))
        root.addView(buildRouteFlow())
        updateRouteFlow(SelectedRouteStore(this).read())
        updateNetworkLocationViews()
        root.addView(spacer(16, this))

        val connectionPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(19), dp(18), dp(19), dp(18))
            background = rounded(DeyttUi.SURFACE_2, 23f, DeyttUi.LINE)
        }
        connectionPanel.addView(sectionLabel("СОСТОЯНИЕ КАНАЛА"))
        val statusLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this).apply {
            contentDescription = uiCopy("Состояние соединения")
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(DeyttUi.MUTED)
            }
        }
        statusLine.addView(statusDot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(11) })
        statusText = text("Не подключено", 25f, DeyttUi.TEXT).apply {
            gravity = Gravity.START
            letterSpacing = -.025f
            maxLines = 2
        }
        statusLine.addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        connectionPanel.addView(statusLine)
        detailText = text("Готов к безопасному соединению", 12f, DeyttUi.MUTED).apply {
            gravity = Gravity.START
            setPadding(dp(20), dp(7), 0, 0)
        }
        connectionPanel.addView(detailText)
        action = button("Подключить").apply { setOnClickListener { toggleTunnel() } }
        connectionPanel.addView(action, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            topMargin = dp(18)
        })
        root.addView(connectionPanel)

        root.addView(spacer(16, this))
        root.addView(buildTrafficSummary())
        getSharedPreferences("profile_settings", MODE_PRIVATE).getString("subscription_warning", null)
            ?.takeIf(String::isNotBlank)
            ?.let { warning ->
                root.addView(spacer(12, this))
                val compactWarning = if (warning.contains("AmneziaWG 1.5") && warning.contains("AmneziaWG 3.1")) {
                    "Профили AmneziaWG 1.5 и 3.1 не загружены. Обновите подписку."
                } else warning
                root.addView(note(compactWarning, DeyttUi.AMBER))
            }
        rebuildRouteRow()
        renderStoredState()
        return ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(Color.TRANSPARENT)
            addView(root, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
    }

    private fun rebuildRouteRow() {
        latencyGeneration++
        val selected = SelectedRouteStore(this).read()
        if (::globe.isInitialized) globe.focus(selected.id, animate = false)
        updateRouteFlow(selected)
        if (::latencyText.isInitialized) {
            latencyText.text = uiCopy("пинг")
            latencyText.isEnabled = true
            latencyText.alpha = 1f
            latencyText.textSize = 9f
            latencyText.setTextColor(DeyttUi.MUTED)
            latencyText.contentDescription = uiCopy("Проверить задержку через выход ${selected.title}: два HTTPS-запроса HEAD или GET, тайм-аут 10 секунд")
        }
    }

    private fun toggleTunnel() {
        if (RouteProbeClient.isRunning(this)) {
            if (::detailText.isInitialized) detailText.text = "Сначала дождитесь проверки маршрута."
            return
        }
        val saved = VpnStateStore(this).read()
        val decision = VpnControlDecision.decide(
            saved.phase,
            ConnectVpnService.isRunning(),
            AwgTunnelController.isRunning(),
        )
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
        pendingRoute = SelectedRouteStore(this).read()
        val permission = VpnService.prepare(this)
        if (permission != null) startActivityForResult(permission, VPN_PERMISSION_REQUEST) else startSelectedTunnel()
    }

    internal fun selectTab(index: Int) {
        if (!::pager.isInitialized || index !in 0..3) return
        pager.setCurrentItem(index, true)
    }

    internal fun openSetup() {
        startActivity(Intent(this, SetupActivity::class.java))
    }

    internal fun selectRoute(route: DeyttRoute) {
        if (SelectedRouteStore(this).read().id == route.id) return
        val connected = ConnectVpnService.isRunning() || AwgTunnelController.isRunning()
        if (connected) {
            AlertDialog.Builder(this)
                .setTitle("Изменить точку выхода?")
                .setMessage("Текущее соединение завершится. После выбора подключитесь снова, чтобы применить новый маршрут.")
                .setNegativeButton("Оставить подключение", null)
                .setPositiveButton("Остановить и сменить") { _, _ -> applyRouteSelection(route) }
                .show()
            return
        }
        applyRouteSelection(route)
    }

    private fun applyRouteSelection(route: DeyttRoute) {
        if (RouteProbeClient.isRunning(this)) {
            runCatching { RouteProbeClient.cancel(this) }
            pendingRouteProbeId = null
        }
        if (ConnectVpnService.isRunning()) {
            startService(Intent(this, ConnectVpnService::class.java).setAction(ConnectVpnService.ACTION_STOP))
        }
        AwgTunnelController.stop(this)
        if (route.engine == TunnelEngine.LIBBOX) {
            val config = SubscriptionStore(this).readCurrent() ?: return
            SubscriptionStore(this).saveValidated(ProfileRoutes.select(config, route.configTag))
        }
        SelectedRouteStore(this).save(route)
        pageAdapter.refresh(1)
        rebuildRouteRow()
        renderStoredState()
        selectTab(0)
    }

    private fun showMapRoutePicker(node: String) {
        if (node == "user") {
            val place = currentNetworkLocation?.placeLabel ?: "Точка входа с этого устройства"
            AlertDialog.Builder(this)
                .setTitle("Точка входа")
                .setMessage("$place — примерное место по IP. Это не сервер выхода и на выбор маршрута не влияет.")
                .setPositiveButton("Понятно", null)
                .show()
            return
        }
        val countryCode = when (node) {
            "nl" -> "NL"
            "de" -> "DE"
            "fi" -> "FI"
            "ru" -> "RU"
            else -> return
        }
        val config = SubscriptionStore(this).readCurrent() ?: return
        val routes = RouteCatalog.from(config, AwgProfileStore(this).profiles())
        val automatic = routes.firstOrNull { it.protocol == RouteProtocol.AUTO }
        val countryRoutes = routes.filter {
            it.engine == TunnelEngine.LIBBOX && it.countryCode == countryCode &&
                it.protocol in setOf(RouteProtocol.VLESS, RouteProtocol.TROJAN, RouteProtocol.HYSTERIA2)
        }
        val doubleRoute = routes.firstOrNull {
            it.engine == TunnelEngine.LIBBOX && it.protocol == RouteProtocol.RU_DE
        }.takeIf { countryCode == "RU" || countryCode == "DE" }
        val choices = listOfNotNull(automatic) + countryRoutes + listOfNotNull(doubleRoute)
        val countryName = countryRoutes.firstOrNull()?.country ?: when (countryCode) {
            "NL" -> "Нидерланды"
            "DE" -> "Германия"
            "FI" -> "Финляндия"
            else -> "Россия"
        }
        if (choices.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(countryName)
                .setMessage("Для этой точки пока нет маршрута в подписке.")
                .setPositiveButton("Открыть маршруты") { _, _ -> selectTab(1) }
                .setNegativeButton("Закрыть", null)
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
        panel.addView(mono("ВЫХОД  ·  ${countryName.uppercase()}", 9f, DeyttUi.SKY, 600).apply {
            letterSpacing = .08f
            setPadding(0, dp(18), 0, dp(5))
        })
        panel.addView(text(countryName, 23f, DeyttUi.TEXT).apply {
            letterSpacing = -.03f
            setPadding(0, 0, 0, dp(4))
        })
        panel.addView(text("Выберите способ подключения или проверьте протоколы.", 12f, DeyttUi.MUTED).apply {
            setPadding(0, 0, 0, dp(12))
        })
        if (ConnectVpnService.isRunning() || AwgTunnelController.isRunning()) {
            panel.addView(note("Смена выхода остановит текущее соединение. Новый маршрут нужно будет запустить снова.", DeyttUi.AMBER))
            panel.addView(spacer(8, this))
        }

        val options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        choices.forEachIndexed { index, route ->
            val selected = route.id == selectedId
            val title = when (route.protocol) {
                RouteProtocol.AUTO -> "Автоподбор"
                RouteProtocol.RU_DE -> "Россия → Германия"
                else -> route.protocol.title
            }
            val detail = when (route.protocol) {
                RouteProtocol.AUTO -> "Выбрать доступный выход автоматически"
                RouteProtocol.RU_DE -> "Двойной маршрут · Санкт-Петербург → Франкфурт"
                else -> route.protocol.detail
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
                contentDescription = "$title. $detail${if (selected) ". Текущий маршрут" else ""}"
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
                if (selected) addView(mono("ВЫБРАН", 8f, DeyttUi.MINT, 600))
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
            panel.addView(button("Сравнить протоколы", secondary = true).apply {
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, ProtocolActivity::class.java).putExtra("country", comparableCode))
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

    private fun updatePrimaryPageMotion(selected: Int) {
        if (!::pageAdapter.isInitialized) return
        val reduced = getSharedPreferences("profile_settings", MODE_PRIVATE)
            .getBoolean("reduced_motion", false)
        pageAdapter.cachedPages().forEach { (position, page) ->
            DeyttUi.setStarfieldMotion(page, active = position == selected && !isFinishing, reducedMotion = reduced)
        }
        DeyttUi.setStarfieldMotion(statusInset, active = selected in 0..3 && !isFinishing, reducedMotion = reduced)
    }

    private fun buildRouteFlow(): View {
        val route = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = rounded(DeyttUi.SURFACE, 19f, DeyttUi.LINE)
        }
        fun node(label: String, alignEnd: Boolean = false): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = if (alignEnd) Gravity.END else Gravity.START
                addView(mono(label, 8f, DeyttUi.MUTED, 600).apply {
                    gravity = if (alignEnd) Gravity.END else Gravity.START
                })
            }
        fun arrow(): View = text("→", 15f, DeyttUi.SKY, android.graphics.Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            contentDescription = uiCopy("направление маршрута")
        }
        fun weighted(view: View) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { gravity = Gravity.TOP }

        val origin = node("ВХОД").apply {
            originPlaceText = text("Ищем регион…", 11f, DeyttUi.TEXT, android.graphics.Typeface.BOLD).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(5), 0, 0)
            }
            addView(originPlaceText)
            originHintText = text("примерно по IP", 9f, DeyttUi.MUTED).apply { setPadding(0, dp(3), 0, 0) }
            addView(originHintText)
        }
        val transit = node("ТРАНЗИТ").apply {
            middlePlaceText = text("🇷🇺 Петербург", 11f, DeyttUi.TEXT, android.graphics.Typeface.BOLD).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(5), 0, 0)
            }
            addView(middlePlaceText)
        }
        val destination = node("ВЫХОД", alignEnd = true).apply {
            destinationText = text("Автоподбор", 11f, DeyttUi.TEXT, android.graphics.Typeface.BOLD).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.END
                setPadding(0, dp(5), 0, 0)
                setOnClickListener { selectTab(1) }
            }
            addView(destinationText)
            egressHintText = text("примерно по IP после подключения", 8f, DeyttUi.MUTED).apply {
                gravity = Gravity.END
                setPadding(0, dp(2), 0, 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            addView(egressHintText)
            latencyText = actionLabel().apply {
                minHeight = dp(24)
                minimumHeight = dp(24)
                setPadding(0, dp(2), 0, 0)
                gravity = Gravity.END
                setOnClickListener { measureSelectedRoute() }
            }
            addView(latencyText)
        }
        route.addView(origin, weighted(origin))
        firstHopArrow = arrow()
        route.addView(firstHopArrow, LinearLayout.LayoutParams(dp(16), dp(24)))
        middleNode = transit
        route.addView(transit, weighted(transit))
        secondHopArrow = arrow()
        route.addView(secondHopArrow, LinearLayout.LayoutParams(dp(16), dp(24)))
        route.addView(destination, weighted(destination))
        route.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        route.contentDescription = uiCopy("Путь от входа через транзит к выбранному выходу")
        return route
    }

    private fun updateRouteFlow(selected: SelectedRoute) {
        if (!::destinationText.isInitialized) return
        val isRuDe = selected.id.contains("RU-DE", ignoreCase = true)
        destinationText.text = if (isRuDe) "🇩🇪 " + uiCopy("Франкфурт") else uiCopy(selected.title)
        if (::middleNode.isInitialized) {
            middleNode.visibility = if (isRuDe) View.VISIBLE else View.GONE
            secondHopArrow.visibility = if (isRuDe) View.VISIBLE else View.GONE
            firstHopArrow.visibility = View.VISIBLE
        }
        updateNetworkLocationViews()
    }

    private fun updateNetworkLocationViews() {
        if (!::originPlaceText.isInitialized || !::originHintText.isInitialized) return
        val enabled = isNetworkLocationEnabled()
        val location = currentNetworkLocation
        val tunnelActive = ConnectVpnService.isRunning() || AwgTunnelController.isRunning()
        originPlaceText.text = when {
            !enabled -> uiCopy("Место скрыто")
            location != null -> AppLanguage.locationLabel(this, location.placeLabel)
            locationRequestInFlight -> uiCopy("Определяем регион…")
            tunnelActive -> uiCopy("Исходная сеть скрыта во время подключения")
            else -> uiCopy("Регион недоступен")
        }
        originHintText.text = when {
            !enabled -> uiCopy("отключено в настройках")
            locationRequestInFlight -> uiCopy("примерно по IP")
            location != null -> uiCopy("примерно по IP · только в памяти")
            locationLookupFailed -> uiCopy("геолокация временно недоступна")
            tunnelActive -> uiCopy("отключите соединение для определения сети")
            else -> uiCopy("ожидает сетевого запроса")
        }
        if (::destinationText.isInitialized && ::egressHintText.isInitialized) {
            val selected = SelectedRouteStore(this).read()
            val isRuDe = selected.id.contains("RU-DE", ignoreCase = true)
            val selectedLabel = if (isRuDe) "🇩🇪 " + uiCopy("Франкфурт") else uiCopy(selected.title)
            when {
                !enabled -> {
                    destinationText.text = selectedLabel
                    egressHintText.text = uiCopy("отключено в настройках")
                }
                tunnelActive && currentEgressLocation != null -> {
                    destinationText.text = AppLanguage.locationLabel(this, currentEgressLocation!!.placeLabel)
                    destinationText.contentDescription = uiCopy("Фактический выход, регион определён примерно по IP")
                    egressHintText.text = uiCopy("выход · примерно по IP · только в памяти")
                }
                tunnelActive && egressRequestInFlight -> {
                    egressHintText.text = uiCopy("определяем регион выхода…")
                }
                tunnelActive && egressLookupFailed -> {
                    egressHintText.text = uiCopy("регион выхода по IP недоступен")
                }
                tunnelActive -> {
                    egressHintText.text = uiCopy("выход · примерно по IP")
                }
                else -> {
                    destinationText.text = selectedLabel
                    destinationText.contentDescription = uiCopy("Выбранный выход: ${selected.title}")
                    egressHintText.text = uiCopy("регион выхода появится после подключения")
                }
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
        val routes = runCatching { RouteCatalog.from(config, AwgProfileStore(this).profiles()) }.getOrDefault(emptyList())
        return routes.flatMap { route ->
            when (route.countryCode.uppercase()) {
                "NL" -> listOf("nl")
                "DE" -> listOf("de")
                "FI" -> listOf("fi")
                "RU" -> listOf("ru")
                "RU-DE" -> listOf("ru", "de")
                else -> emptyList()
            }
        }.toSet()
    }

    internal fun setReducedMotionEnabled(enabled: Boolean) {
        getSharedPreferences(PROFILE_SETTINGS, MODE_PRIVATE).edit { putBoolean("reduced_motion", enabled) }
        if (::pager.isInitialized) updatePrimaryPageMotion(pager.currentItem)
    }

    private fun refreshNetworkLocation(force: Boolean = false) {
        if (!isNetworkLocationEnabled()) {
            updateNetworkLocationViews()
            return
        }
        val tunnelActive = ConnectVpnService.isRunning() || AwgTunnelController.isRunning()
        if (tunnelActive) {
            locationRequestGeneration++
            locationRequestInFlight = false
            if (ConnectVpnService.isRunning()) {
                val config = SubscriptionStore(this).readCurrent()
                if (config != null && !RouteProxyProbe.isApplicationRoutedByTunnel(config, packageName)) {
                    egressLookupFailed = true
                    egressRequestInFlight = false
                    updateNetworkLocationViews()
                    return
                }
            }
            if (currentEgressLocation != null && !force || egressRequestInFlight) {
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
                latencyText.text = "пинг"
                latencyText.isEnabled = true
                latencyText.alpha = 1f
                latencyText.contentDescription = "Для проверки через выход требуется системное разрешение Android"
            }
            return
        }
        if (requestCode == VPN_PERMISSION_REQUEST && resultCode == RESULT_OK) startSelectedTunnel()
    }

    private fun startSelectedTunnel() {
        val route = pendingRoute ?: SelectedRouteStore(this).read()
        val libboxRunning = ConnectVpnService.isRunning()
        val awgRunning = AwgTunnelController.isRunning()
        if (libboxRunning) {
            startService(Intent(this, ConnectVpnService::class.java).setAction(ConnectVpnService.ACTION_STOP))
        }
        if (awgRunning) {
            AwgTunnelController.stop(this, publishStatus = false)
        }
        if (libboxRunning || awgRunning || AwgTunnelController.isStopping()) {
            awaitEnginesStopped(action = { startSelectedTunnelAfterStop(route) })
            return
        }
        startSelectedTunnelAfterStop(route)
    }

    private fun startSelectedTunnelAfterStop(route: SelectedRoute) {
        if (route.engine == TunnelEngine.AMNEZIAWG) {
            val store = AwgProfileStore(this)
            val config = store.read(route.id)
            if (config == null) {
                renderStatus(VpnPhase.ERROR, "Ошибка запуска соединения", "Обновите подписку: профиль ${route.subtitle} отсутствует")
                return
            }
            AwgTunnelController.start(this, config, route.id)
        } else {
            val intent = Intent(this, ConnectVpnService::class.java).setAction(ConnectVpnService.ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        }
    }

    private fun awaitEnginesStopped(action: () -> Unit, attempt: Int = 0) {
        if (isFinishing || isDestroyed) return
        if (!ConnectVpnService.isRunning() &&
            !AwgTunnelController.isRunning() &&
            !AwgTunnelController.isStopping()
        ) {
            action()
            return
        }
        if (attempt >= 100) {
            renderStatus(VpnPhase.ERROR, "Не удалось завершить предыдущее соединение", "Попробуйте отключить его ещё раз")
            return
        }
        window.decorView.postDelayed({ awaitEnginesStopped(action, attempt + 1) }, 50L)
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

    private fun shouldSampleMapTraffic(): Boolean =
        ::globe.isInitialized && ::pager.isInitialized && pager.currentItem == 0 &&
            renderedPhase == VpnPhase.CONNECTED && isSystemTunnelActive()

    private fun updateMapTrafficSampling() {
        trafficSampleHandler.removeCallbacks(trafficSample)
        mapTrafficActivity.reset()
        if (shouldSampleMapTraffic()) trafficSampleHandler.post(trafficSample)
        else if (::globe.isInitialized) globe.setTrafficEnabled(false)
    }

    private fun renderStatus(phase: VpnPhase?, status: String?, error: String?) {
        if (!::statusText.isInitialized) return
        val value = status ?: "Соединение выключено"
        val displayValue = when (value) {
            "VPN подключён" -> "Подключено"
            "VPN отключён", "Соединение выключено" -> "Не подключено"
            else -> value
        }
        val currentPhase = phase ?: VpnPhase.IDLE
        val previousPhase = renderedPhase
        val phaseChanged = renderedPhase != null && renderedPhase != currentPhase
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
        statusText.text = uiCopy(displayValue)
        detailText.text = uiCopy(when (currentPhase) {
            VpnPhase.STARTING, VpnPhase.CHECKING -> progressDetail(currentPhase, connectionElapsedSeconds())
            VpnPhase.CONNECTED -> "Соединение активно"
            VpnPhase.STOPPING -> "Завершаем работу туннеля"
            VpnPhase.ERROR -> error ?: "Попробуйте ещё раз или выберите другой маршрут"
            VpnPhase.IDLE -> "Готово к подключению"
        })
        if (isConnecting) {
            connectionProgressHandler.removeCallbacks(connectionProgressTick)
            connectionProgressHandler.postDelayed(connectionProgressTick, CONNECTION_PROGRESS_INTERVAL_MS)
        }
        val animationsEnabled =
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()) && !isReducedMotionEnabled()
        if (phaseChanged && animationsEnabled) {
            statusText.alpha = .72f
            statusText.translationY = dp(4).toFloat()
            statusText.animate().alpha(1f).translationY(0f).setDuration(210L).start()
        }
        if (::statusDot.isInitialized) {
            val color = when (currentPhase) {
                VpnPhase.CONNECTED -> DeyttUi.MINT
                VpnPhase.ERROR -> DeyttUi.CORAL
                VpnPhase.STARTING, VpnPhase.CHECKING -> DeyttUi.SKY
                else -> DeyttUi.MUTED
            }
            statusDot.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(color)
            }
            statusDot.contentDescription = uiCopy(displayValue)
            if (phaseChanged && animationsEnabled) {
                statusDot.scaleX = .7f
                statusDot.scaleY = .7f
                statusDot.animate().scaleX(1f).scaleY(1f).setDuration(300L).start()
            }
        }
        action.text = uiCopy(when (currentPhase) {
            VpnPhase.STARTING, VpnPhase.CHECKING, VpnPhase.CONNECTED -> "Отключить"
            VpnPhase.STOPPING -> "Отключаем…"
            VpnPhase.ERROR -> "Повторить"
            VpnPhase.IDLE -> "Подключить"
        })
        action.contentDescription = action.text.toString()
        action.isEnabled = currentPhase != VpnPhase.STOPPING
        action.alpha = if (action.isEnabled) 1f else .66f
        if (::globe.isInitialized) {
            updateMapTrafficSampling()
        }
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
        if (!::detailText.isInitialized) return
        detailText.text = progressDetail(phase, connectionElapsedSeconds())
    }

    private fun Long.twoDigits(): String = if (this < 10L) "0$this" else toString()

    private fun measureSelectedRoute() {
        if (!::latencyText.isInitialized) return
        if (RouteProbeClient.isRunning(this) || selectedRouteLatencyInFlight) return
        val generation = ++latencyGeneration
        val selected = SelectedRouteStore(this).read()
        val config = SubscriptionStore(this).readCurrent() ?: return
        val route = RouteCatalog.from(config, AwgProfileStore(this).profiles())
            .firstOrNull { it.id == selected.id } ?: return
        val method = RouteProbePreferences.method(this)
        latencyText.text = uiCopy("проверка…")
        latencyText.isEnabled = false
        latencyText.alpha = .65f
        val tunnelActive = ConnectVpnService.isRunning() || AwgTunnelController.isRunning()
        if (tunnelActive) {
            if (route.id != selected.id ||
                (route.engine == TunnelEngine.LIBBOX && !RouteProxyProbe.isApplicationRoutedByTunnel(config, packageName))
            ) {
                latencyText.text = uiCopy("отключите соединение")
                latencyText.isEnabled = true
                latencyText.alpha = 1f
                latencyText.textSize = 8f
                latencyText.setTextColor(DeyttUi.AMBER)
                latencyText.contentDescription = uiCopy("Чтобы проверить другой выход, сначала отключите текущее соединение")
                return
            }
            if (route.engine == TunnelEngine.AMNEZIAWG && !AwgTunnelController.isRunning()) {
                latencyText.text = uiCopy("подключите AmneziaWG")
                latencyText.isEnabled = true
                latencyText.alpha = 1f
                latencyText.textSize = 8f
                latencyText.setTextColor(DeyttUi.AMBER)
                latencyText.contentDescription = uiCopy("Для HTTP-проверки подключите выбранный профиль AmneziaWG")
                return
            }
            selectedRouteLatencyInFlight = true
            LatencyExecutor.pool.execute {
                val elapsed = runCatching { RouteProxyProbe.measureThroughSystemVpn(method) }.getOrNull()
                runOnUiThread {
                    selectedRouteLatencyInFlight = false
                    if (generation == latencyGeneration && !isFinishing && !isDestroyed) {
                        latencyText.text = elapsed?.let(::formatLatency) ?: uiCopy("нет ответа")
                        latencyText.isEnabled = true
                        latencyText.alpha = 1f
                        latencyText.setTextColor(if (elapsed != null) DeyttUi.MINT else DeyttUi.CORAL)
                        latencyText.contentDescription = elapsed?.let {
                            uiCopy("Два HTTPS-запроса ${method.wireValue} через активное соединение заняли ${it} миллисекунд")
                        } ?: uiCopy("HTTPS-проверка через активное соединение не ответила за 10 секунд")
                    }
                }
            }
            return
        }
        if (route.engine == TunnelEngine.AMNEZIAWG) {
            latencyText.text = uiCopy("подключите AmneziaWG")
            latencyText.isEnabled = true
            latencyText.alpha = 1f
            latencyText.setTextColor(DeyttUi.AMBER)
            latencyText.contentDescription = uiCopy("Для HTTP-проверки подключите выбранный профиль AmneziaWG")
            return
        }
        val vpnPermission = VpnService.prepare(this)
        if (vpnPermission != null) {
            latencyText.text = uiCopy("пинг")
            latencyText.isEnabled = true
            latencyText.alpha = 1f
            showRouteProbePermissionSheet(vpnPermission)
            return
        }
        try {
            pendingRouteProbeId = RouteProbeClient.start(
                this, config, listOf(route.configTag), method, TelegramSessionStore.read(this),
            )
            if (::action.isInitialized) {
                action.isEnabled = false
                action.alpha = .65f
            }
            latencyText.text = uiCopy("через прокси…")
            latencyText.isEnabled = true
            latencyText.alpha = 1f
            latencyText.textSize = 8f
            latencyText.setTextColor(DeyttUi.SKY)
            latencyText.contentDescription = uiCopy("Проверяю выход через локальный прокси методом ${method.wireValue}, два запроса, тайм-аут 10 секунд")
        } catch (_: Throwable) {
            if (generation == latencyGeneration && !isFinishing && !isDestroyed) {
                latencyText.text = uiCopy("ошибка")
                latencyText.isEnabled = true
                latencyText.alpha = 1f
                latencyText.setTextColor(DeyttUi.CORAL)
                latencyText.contentDescription = uiCopy("Не удалось запустить проверку через прокси")
            }
        }
    }

    private fun showRouteProbePermissionSheet(permission: Intent) {
        val dialog = Dialog(this)
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(18))
            background = rounded(DeyttUi.SURFACE, 24f, DeyttUi.LINE)
        }
        sheet.addView(mono("ДИАГНОСТИКА · РАЗОВЫЙ ДОСТУП", 9f, DeyttUi.MUTED, 650))
        sheet.addView(text("Проверить выбранный маршрут?", 19f, DeyttUi.TEXT, android.graphics.Typeface.BOLD).apply {
            setPadding(0, dp(10), 0, dp(7))
        })
        sheet.addView(text(
            "Android запросит разовое разрешение для HTTP-проверки через выбранный выход. VPN-туннель при этом не запускается.",
            13f,
            DeyttUi.MUTED,
        ).apply { setLineSpacing(dp(3).toFloat(), 1f) })
        sheet.addView(button("Продолжить").apply {
            setOnClickListener {
                dialog.dismiss()
                startActivityForResult(permission, ROUTE_PROBE_PERMISSION_REQUEST)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(16) })
        sheet.addView(text("отмена", 13f, DeyttUi.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(13), 0, dp(2))
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setContentView(sheet)
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
        private const val SELECTED_ROUTE_LATENCY_INTERVAL_MS = 10_000L
        private const val TRAFFIC_SAMPLE_INTERVAL_MS = 700L
        private const val TRAFFIC_VISIBILITY_WINDOW_MS = 3_500L
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
