package space.deytt.connect

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import space.deytt.connect.DeyttUi.header
import space.deytt.connect.DeyttUi.actionLabel
import space.deytt.connect.DeyttUi.button
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.present
import space.deytt.connect.DeyttUi.row
import space.deytt.connect.DeyttUi.screen
import space.deytt.connect.DeyttUi.sectionLabel
import space.deytt.connect.DeyttUi.spacer
import space.deytt.connect.DeyttUi.text
import space.deytt.connect.DeyttUi.note

class ProtocolActivity : Activity() {
    private var latencyGeneration = 0
    private var selecting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = intent.getStringExtra("country") ?: run { finish(); return }
        val requestedVersion = intent.getStringExtra("version")
        val config = SubscriptionStore(this).readCurrent() ?: run { finish(); return }
        val awg = AwgProfileStore(this)
        val routes = RouteCatalog.from(config, awg.profiles())
            .filter { route ->
                route.countryCode == code &&
                    (requestedVersion == null ||
                        (requestedVersion == "15" && route.protocol == RouteProtocol.AWG15) ||
                        (requestedVersion == "31" && route.protocol == RouteProtocol.AWG31))
            }
        val title = if (code == "AWG") {
            requestedVersion?.let { "AmneziaWG ${if (it == "31") "3.1" else "1.5"}" } ?: "AmneziaWG"
        } else routes.firstOrNull()?.country ?: "Протокол"
        val root = screen()
        root.addView(header(if (code == "AWG") "AmneziaWG" else "маршрут", title, true))
        root.addView(spacer(2, this))
        val globe = RouteGlobeView(this)
        globe.focus(if (code == "AWG") "AUTO" else code, animate = false)
        root.addView(globe, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(218)))
        root.addView(text("Выберите способ подключения. Проверка задержки запускается отдельно.", 12f, DeyttUi.MUTED).apply {
            gravity = android.view.Gravity.CENTER
            setPadding(dp(6), 0, dp(6), dp(16))
        })
        root.addView(sectionLabel(if (code == "AWG") "серверы и версии" else "способы подключения"))
        if (routes.isEmpty()) {
            root.addView(note("Профили этой версии пока не загружены. Обновите подписку, чтобы загрузить серверы.", DeyttUi.AMBER))
            root.addView(button("обновить подписку").apply {
                setOnClickListener { startActivity(Intent(this@ProtocolActivity, SetupActivity::class.java)) }
            })
        }
        routes.forEach { route ->
            val mark = if (route.engine == TunnelEngine.AMNEZIAWG) route.protocol.title else when (route.protocol) {
                RouteProtocol.VLESS -> "VL"
                RouteProtocol.TROJAN -> "TR"
                RouteProtocol.HYSTERIA2 -> "H2"
                RouteProtocol.AWG15 -> "1.5"
                RouteProtocol.AWG31 -> "3.1"
                else -> "AUTO"
            }
            val rowTitle = if (route.engine == TunnelEngine.AMNEZIAWG) route.country else route.protocol.title
            val rowDetail = if (route.engine == TunnelEngine.AMNEZIAWG) "${route.protocol.title} · ${route.protocol.detail}" else route.protocol.detail
            val latency = actionLabel("проверить")
            val item = row(rowTitle, rowDetail, mark, "").apply {
                setOnClickListener {
                    if (selecting) return@setOnClickListener
                    selecting = true
                    globe.focus(if (route.engine == TunnelEngine.AMNEZIAWG) route.id.uppercase() else route.countryCode)
                    select(route)
                }
            }
            latency.setOnClickListener { measure(route, latency) }
            item.addView(latency, LinearLayout.LayoutParams(dp(86), ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(item)
        }
        present(root)
    }

    private fun measure(route: DeyttRoute, view: android.widget.TextView) {
        val generation = ++latencyGeneration
        val config = SubscriptionStore(this).readCurrent() ?: return
        val awg = AwgProfileStore(this)
        val awgConfig = if (route.engine == TunnelEngine.AMNEZIAWG) awg.read(route.id) else null
        val target = RouteLatency.target(config, route, awgConfig)
        view.text = "проверяю…"
        view.isEnabled = false
        view.alpha = .65f
        if (target == null) {
            view.text = "нет ответа"
            view.isEnabled = true
            view.alpha = 1f
            return
        }
        LatencyExecutor.pool.execute {
            val label = RouteLatency.label(RouteLatency.measure(target))
            runOnUiThread {
                if (generation == latencyGeneration && !isFinishing && !isDestroyed) {
                    view.text = label
                    view.isEnabled = true
                    view.alpha = 1f
                }
            }
        }
    }

    override fun onDestroy() {
        latencyGeneration++
        super.onDestroy()
    }

    private fun select(route: DeyttRoute) {
        if (ConnectVpnService.isRunning()) {
            startService(Intent(this, ConnectVpnService::class.java).setAction(ConnectVpnService.ACTION_STOP))
        }
        AwgTunnelController.stop(this)
        if (route.engine == TunnelEngine.LIBBOX) {
            val config = SubscriptionStore(this).readCurrent() ?: return
            SubscriptionStore(this).saveValidated(ProfileRoutes.select(config, route.configTag))
        }
        SelectedRouteStore(this).save(route)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}
