package space.deytt.connect

import android.animation.ValueAnimator
import android.app.Activity
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.viewpager2.widget.ViewPager2
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
    private lateinit var destinationText: TextView
    private var pendingRoute: SelectedRoute? = null
    private var latencyGeneration = 0
    private var renderedPhase: VpnPhase? = null
    private var initialPage = 0
    private var hasStartedBefore = false
    private var currentNetworkLocation: IpNetworkLocation? = null
    private var locationRequestInFlight = false
    private var locationLookupFailed = false
    private var locationRequestGeneration = 0
    private val locationExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val connectionProgressHandler = Handler(Looper.getMainLooper())
    private val connectionProgressTick = object : Runnable {
        override fun run() {
            val phase = renderedPhase ?: return
            if (phase != VpnPhase.STARTING && phase != VpnPhase.CHECKING) return
            renderConnectionProgressDetail(phase)
            connectionProgressHandler.postDelayed(this, CONNECTION_PROGRESS_INTERVAL_MS)
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
        currentNetworkLocation = if (isNetworkLocationEnabled()) IpNetworkLocationStore(this).read() else null
        buildScreen()
        requestNotificationPermissionIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ConnectVpnService.ACTION_STATUS)
        ContextCompat.registerReceiver(this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
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
    }

    override fun onStop() {
        runCatching { unregisterReceiver(statusReceiver) }
        connectionProgressHandler.removeCallbacks(connectionProgressTick)
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
        super.onDestroy()
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
        statusInset = View(this).apply { background = DeyttUi.starfieldBackground(this@MainActivity) }

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
                if (::globe.isInitialized) globe.setTrafficEnabled(position == 0 && renderedPhase == VpnPhase.CONNECTED)
            }
        })
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        pager.setCurrentItem(initialPage, false)
        root.post { updatePrimaryPageMotion(pager.currentItem) }
    }

    private fun buildHomePage(): View {
        val root = screen(withBackdrop = true)
        root.addView(brandHeader())
        root.addView(spacer(14, this))

        globe = RouteGlobeView(this).apply {
            focus(SelectedRouteStore(this@MainActivity).read().id, animate = false)
            setUserLocation(currentNetworkLocation)
        }
        root.addView(mapPanel(globe), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)))
        root.addView(spacer(10, this))
        root.addView(buildRouteFlow())
        updateRouteFlow(SelectedRouteStore(this).read())
        updateNetworkLocationViews()
        root.addView(spacer(16, this))

        val connectionPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(15), dp(17), dp(16))
            background = rounded(DeyttUi.SURFACE, 20f, DeyttUi.LINE)
        }
        connectionPanel.addView(sectionLabel("состояние соединения"))
        val statusLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this).apply {
            contentDescription = "Состояние соединения"
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(DeyttUi.MUTED)
            }
        }
        statusLine.addView(statusDot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(11) })
        statusText = text("Не подключено", 26f, DeyttUi.TEXT, android.graphics.Typeface.BOLD).apply {
            gravity = Gravity.START
            letterSpacing = -.035f
            maxLines = 2
        }
        statusLine.addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        connectionPanel.addView(statusLine)
        detailText = text("Готово к подключению", 12f, DeyttUi.MUTED).apply {
            gravity = Gravity.START
            setPadding(dp(20), dp(5), 0, 0)
        }
        connectionPanel.addView(detailText)
        action = button("Подключить").apply { setOnClickListener { toggleTunnel() } }
        connectionPanel.addView(action, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
            topMargin = dp(16)
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
            latencyText.text = "пинг"
            latencyText.isEnabled = true
            latencyText.alpha = 1f
            latencyText.textSize = 9f
            latencyText.setTextColor(DeyttUi.MUTED)
            latencyText.contentDescription = "Проверить задержку маршрута ${selected.title}"
        }
    }

    private fun toggleTunnel() {
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

    private fun updatePrimaryPageMotion(selected: Int) {
        if (!::pageAdapter.isInitialized) return
        val reduced = getSharedPreferences("profile_settings", MODE_PRIVATE)
            .getBoolean("reduced_motion", false)
        pageAdapter.cachedPages().forEach { (position, page) ->
            DeyttUi.setStarfieldMotion(page, active = position == selected && !isFinishing, reducedMotion = reduced)
        }
        DeyttUi.setStarfieldMotion(statusInset, active = false, reducedMotion = reduced)
    }

    private fun buildRouteFlow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(15), dp(12), dp(15), dp(12))
        background = rounded(DeyttUi.SURFACE, 18f, DeyttUi.LINE)

        val origin = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(this@MainActivity.mono("ТЕКУЩАЯ СЕТЬ", 8f, DeyttUi.MUTED, 600))
            originPlaceText = this@MainActivity.text("Ищем регион…", 13f, DeyttUi.TEXT, android.graphics.Typeface.BOLD).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(5), 0, 0)
            }
            addView(originPlaceText)
            originHintText = this@MainActivity.text("примерно по IP", 10f, DeyttUi.MUTED).apply { setPadding(0, dp(3), 0, 0) }
            addView(originHintText)
        }
        addView(origin, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.TOP })
        addView(this@MainActivity.text("→", 18f, DeyttUi.SKY, android.graphics.Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
            contentDescription = "направление маршрута"
        })
        val destination = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(this@MainActivity.mono("VPN-ВЫХОД", 8f, DeyttUi.MUTED, 600).apply { gravity = Gravity.END })
            destinationText = this@MainActivity.text("Автоподбор", 13f, DeyttUi.TEXT, android.graphics.Typeface.BOLD).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.END
                setPadding(0, dp(5), 0, 0)
                setOnClickListener { selectTab(1) }
            }
            addView(destinationText)
            val destinationHint = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(this@MainActivity.text("точка выхода", 9f, DeyttUi.MUTED),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                latencyText = this@MainActivity.actionLabel().apply {
                    minHeight = dp(27)
                    minimumHeight = dp(27)
                    setPadding(dp(4), 0, dp(4), 0)
                    setOnClickListener { measureSelectedRoute() }
                }
                addView(latencyText, LinearLayout.LayoutParams(dp(54), dp(27)).apply { gravity = Gravity.END })
            }
            addView(destinationHint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addView(destination, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.TOP })
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Маршрут от текущей сети до VPN-выхода"
    }

    private fun updateRouteFlow(selected: SelectedRoute) {
        if (::destinationText.isInitialized) destinationText.text = selected.title
    }

    private fun updateNetworkLocationViews() {
        if (!::originPlaceText.isInitialized || !::originHintText.isInitialized) return
        val enabled = isNetworkLocationEnabled()
        val location = currentNetworkLocation
        val tunnelActive = ConnectVpnService.isRunning() || AwgTunnelController.isRunning()
        originPlaceText.text = when {
            !enabled -> "Место выключено"
            location != null -> location.placeLabel
            locationRequestInFlight -> "Определяем регион…"
            tunnelActive -> "Сеть до VPN не определена"
            else -> "Регион недоступен"
        }
        originHintText.text = when {
            !enabled -> "отключено в настройках"
            locationRequestInFlight -> "примерно по IP"
            location != null -> "примерно по IP · IP не сохраняем"
            locationLookupFailed -> "геолокация временно недоступна"
            tunnelActive -> "выключите VPN для определения сети"
            else -> "ожидает сетевого запроса"
        }
    }

    internal fun isNetworkLocationEnabled(): Boolean =
        getSharedPreferences(PROFILE_SETTINGS, MODE_PRIVATE).getBoolean(KEY_NETWORK_LOCATION_ENABLED, true)

    internal fun isReducedMotionEnabled(): Boolean =
        getSharedPreferences(PROFILE_SETTINGS, MODE_PRIVATE).getBoolean("reduced_motion", false)

    internal fun setNetworkLocationEnabled(enabled: Boolean) {
        getSharedPreferences(PROFILE_SETTINGS, MODE_PRIVATE).edit { putBoolean(KEY_NETWORK_LOCATION_ENABLED, enabled) }
        if (!enabled) {
            locationRequestGeneration++
            locationRequestInFlight = false
            locationLookupFailed = false
            currentNetworkLocation = null
            IpNetworkLocationStore(this).clear()
            if (::globe.isInitialized) globe.setUserLocation(null)
        }
        updateNetworkLocationViews()
        if (enabled) refreshNetworkLocation(force = true)
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
        val store = IpNetworkLocationStore(this)
        val cached = store.read()
        if (cached != null) {
            currentNetworkLocation = cached
            if (::globe.isInitialized) globe.setUserLocation(cached)
        }
        if (ConnectVpnService.isRunning() || AwgTunnelController.isRunning()) {
            android.util.Log.i(
                "DeyttIpLocation",
                "Lookup skipped while tunnel active (libbox=${ConnectVpnService.isRunning()}, awg=${AwgTunnelController.isRunning()})",
            )
            updateNetworkLocationViews()
            return
        }
        if (locationRequestInFlight || (!force && cached != null && !store.isStale(cached))) {
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
                        IpNetworkLocationStore(this).save(location)
                        currentNetworkLocation = location
                        android.util.Log.i("DeyttIpLocation", "Lookup succeeded; storing approximate place only")
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
            description = if (metadata.totalBytes > 0) {
                "Использовано ${formatBytes(used)} из ${formatBytes(metadata.totalBytes)}. Скачано ${formatBytes(downloaded)}, отправлено ${formatBytes(uploaded)}."
            } else {
                "Передано ${formatBytes(used)} без заданного лимита. Скачано ${formatBytes(downloaded)}, отправлено ${formatBytes(uploaded)}."
            },
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
        if (value <= 0) return "0 Б"
        val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        var amount = value.toDouble()
        var unit = 0
        while (amount >= 1024 && unit < units.lastIndex) {
            amount /= 1024
            unit++
        }
        return if (unit == 0) "${amount.toLong()} ${units[unit]}"
        else String.format(java.util.Locale.US, "%.1f %s", amount, units[unit])
    }

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
        )
        renderStatus(snapshot.phase, snapshot.title, snapshot.detail)
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
        statusText.text = displayValue
        detailText.text = when (currentPhase) {
            VpnPhase.STARTING, VpnPhase.CHECKING -> progressDetail(currentPhase, connectionElapsedSeconds())
            VpnPhase.CONNECTED -> "Соединение активно"
            VpnPhase.STOPPING -> "Завершаем работу туннеля"
            VpnPhase.ERROR -> error ?: "Попробуйте ещё раз или выберите другой маршрут"
            VpnPhase.IDLE -> "Готово к подключению"
        }
        if (isConnecting) {
            connectionProgressHandler.removeCallbacks(connectionProgressTick)
            connectionProgressHandler.postDelayed(connectionProgressTick, CONNECTION_PROGRESS_INTERVAL_MS)
        }
        val animationsEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()
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
            statusDot.contentDescription = displayValue
            if (phaseChanged && animationsEnabled) {
                statusDot.scaleX = .7f
                statusDot.scaleY = .7f
                statusDot.animate().scaleX(1f).scaleY(1f).setDuration(300L).start()
            }
        }
        action.text = when (currentPhase) {
            VpnPhase.STARTING, VpnPhase.CHECKING, VpnPhase.CONNECTED -> "Отключить"
            VpnPhase.STOPPING -> "Отключаем…"
            VpnPhase.ERROR -> "Повторить"
            VpnPhase.IDLE -> "Подключить"
        }
        action.contentDescription = action.text.toString()
        action.isEnabled = currentPhase != VpnPhase.STOPPING
        action.alpha = if (action.isEnabled) 1f else .66f
        if (::globe.isInitialized) {
            globe.setTrafficEnabled(::pager.isInitialized && pager.currentItem == 0 && currentPhase == VpnPhase.CONNECTED)
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
        val generation = ++latencyGeneration
        val selected = SelectedRouteStore(this).read()
        val config = SubscriptionStore(this).readCurrent() ?: return
        val awg = AwgProfileStore(this)
        val route = RouteCatalog.from(config, awg.profiles())
            .firstOrNull { it.id == selected.id } ?: return
        val awgConfig = if (route.engine == TunnelEngine.AMNEZIAWG) awg.read(route.id) else null
        val target = RouteLatency.target(config, route, awgConfig)
        latencyText.text = "проверяю…"
        latencyText.isEnabled = false
        latencyText.alpha = .65f
        if (target == null) {
            latencyText.text = "нет ответа"
            latencyText.isEnabled = true
            latencyText.alpha = 1f
            latencyText.textSize = 8.5f
            latencyText.setTextColor(DeyttUi.CORAL)
            return
        }
        LatencyExecutor.pool.execute {
            val label = RouteLatency.label(RouteLatency.measure(target))
            runOnUiThread {
                if (generation == latencyGeneration && !isFinishing && !isDestroyed) {
                    latencyText.text = label
                    latencyText.isEnabled = true
                    latencyText.alpha = 1f
                    latencyText.textSize = 9f
                    latencyText.setTextColor(if (label.contains("мс", ignoreCase = true)) DeyttUi.MINT else DeyttUi.CORAL)
                    latencyText.contentDescription = "Задержка маршрута ${selected.title}: $label"
                }
            }
        }
    }

    companion object {
        const val EXTRA_START_TAB = "space.deytt.connect.extra.START_TAB"
        private const val STATE_SELECTED_TAB = "selected_primary_tab"
        private const val VPN_PERMISSION_REQUEST = 701
        private const val NOTIFICATION_REQUEST = 702
        private const val PROFILE_SETTINGS = "profile_settings"
        private const val KEY_NETWORK_LOCATION_ENABLED = "network_location_enabled"
        private const val KEY_CONNECTION_STARTED_ELAPSED = "connection_started_elapsed"
        private const val CONNECTION_PROGRESS_INTERVAL_MS = 1_000L
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
