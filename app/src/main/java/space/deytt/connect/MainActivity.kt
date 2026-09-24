package space.deytt.connect

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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import space.deytt.connect.DeyttUi.button
import space.deytt.connect.DeyttUi.actionLabel
import space.deytt.connect.DeyttUi.brandHeader
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.present
import space.deytt.connect.DeyttUi.row
import space.deytt.connect.DeyttUi.screen
import space.deytt.connect.DeyttUi.sectionLabel
import space.deytt.connect.DeyttUi.spacer
import space.deytt.connect.DeyttUi.text
import space.deytt.connect.DeyttUi.note

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var statusDot: View
    private lateinit var globe: RouteGlobeView
    private lateinit var action: TextView
    private lateinit var routeRow: LinearLayout
    private lateinit var latencyText: TextView
    private var pendingRoute: SelectedRoute? = null
    private var latencyGeneration = 0

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
        buildScreen()
        requestNotificationPermissionIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        if (!::statusText.isInitialized) return
        val filter = IntentFilter(ConnectVpnService.ACTION_STATUS)
        ContextCompat.registerReceiver(this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        renderStoredState()
        rebuildRouteRow()
    }

    override fun onStop() {
        if (::statusText.isInitialized) runCatching { unregisterReceiver(statusReceiver) }
        super.onStop()
    }

    private fun buildScreen() {
        val root = screen()
        root.addView(brandHeader())
        root.addView(spacer(12, this))

        globe = RouteGlobeView(this).apply {
            focus(SelectedRouteStore(this@MainActivity).read().id, animate = false)
        }
        root.addView(globe, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(222)))

        val statusLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        statusDot = View(this).apply {
            contentDescription = "Состояние соединения"
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(DeyttUi.MUTED)
            }
        }
        statusLine.addView(statusDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(10) })
        statusText = text("Соединение выключено", 22f, DeyttUi.TEXT, android.graphics.Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            letterSpacing = -.02f
        }
        statusLine.addView(statusText)
        root.addView(statusLine)
        detailText = text("Готово к подключению", 13f, DeyttUi.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(7), 0, 0)
        }
        root.addView(detailText)
        root.addView(spacer(17, this))
        action = button("Подключить").apply { setOnClickListener { toggleTunnel() } }
        root.addView(action)
        root.addView(spacer(19, this))

        root.addView(sectionLabel("текущий маршрут"))
        routeRow = LinearLayout(this)
        root.addView(routeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        getSharedPreferences("profile_settings", MODE_PRIVATE).getString("subscription_warning", null)
            ?.takeIf(String::isNotBlank)
            ?.let { warning ->
                root.addView(spacer(12, this))
                val compactWarning = if (warning.contains("AmneziaWG 1.5") && warning.contains("AmneziaWG 3.1")) {
                    "Профили AmneziaWG 1.5 и 3.1 не загружены. Обновите подписку."
                } else warning
                root.addView(note(compactWarning, DeyttUi.AMBER))
            }
        present(root)
        rebuildRouteRow()
        renderStoredState()
    }

    private fun rebuildRouteRow() {
        if (!::routeRow.isInitialized) return
        latencyGeneration++
        val selected = SelectedRouteStore(this).read()
        if (::globe.isInitialized) globe.focus(selected.id, animate = false)
        routeRow.removeAllViews()
        latencyText = actionLabel("проверить").apply {
            setOnClickListener { measureSelectedRoute() }
        }
        val item = row(selected.title, selected.subtitle, "•", "", emphasis = true).apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, RoutesActivity::class.java)) }
        }
        item.addView(latencyText, LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT))
        routeRow.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun toggleTunnel() {
        val saved = VpnStateStore(this).read()
        val decision = VpnControlDecision.decide(
            saved.phase,
            ConnectVpnService.isRunning(),
            AwgTunnelController.isRunning(),
        )
        if (decision == VpnControlAction.STOP) {
            if (ConnectVpnService.isRunning()) {
                startService(Intent(this, ConnectVpnService::class.java).setAction(ConnectVpnService.ACTION_STOP))
            }
            AwgTunnelController.stop(this, publishStatus = !ConnectVpnService.isRunning())
            renderStatus(VpnPhase.IDLE, VpnStateStore.IDLE_TITLE, null)
            return
        }
        pendingRoute = SelectedRouteStore(this).read()
        val permission = VpnService.prepare(this)
        if (permission != null) startActivityForResult(permission, VPN_PERMISSION_REQUEST) else startSelectedTunnel()
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
            "VPN отключён" -> "Соединение выключено"
            else -> value
        }
        val currentPhase = phase ?: VpnPhase.IDLE
        statusText.text = displayValue
        detailText.text = error ?: when (displayValue) {
            "Подключено" -> "Соединение активно"
            "Соединение выключено" -> "Готово к подключению"
            else -> "Проверяем доступ к интернету"
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
        }
        action.text = if (currentPhase in setOf(VpnPhase.STARTING, VpnPhase.CHECKING, VpnPhase.CONNECTED)) "Отключить" else "Подключить"
    }

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
            return
        }
        LatencyExecutor.pool.execute {
            val label = RouteLatency.label(RouteLatency.measure(target))
            runOnUiThread {
                if (generation == latencyGeneration && !isFinishing && !isDestroyed) {
                    latencyText.text = label
                    latencyText.isEnabled = true
                    latencyText.alpha = 1f
                }
            }
        }
    }

    companion object {
        private const val VPN_PERMISSION_REQUEST = 701
        private const val NOTIFICATION_REQUEST = 702
    }
}
