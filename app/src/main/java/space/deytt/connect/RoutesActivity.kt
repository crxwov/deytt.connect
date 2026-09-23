package space.deytt.connect

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import space.deytt.connect.DeyttUi.actionLabel
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.header
import space.deytt.connect.DeyttUi.present
import space.deytt.connect.DeyttUi.row
import space.deytt.connect.DeyttUi.screen
import space.deytt.connect.DeyttUi.sectionLabel
import space.deytt.connect.DeyttUi.spacer
import space.deytt.connect.DeyttUi.text
import space.deytt.connect.DeyttUi.note

class RoutesActivity : Activity() {
    private var latencyGeneration = 0
    private lateinit var globe: RouteGlobeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = SubscriptionStore(this).readCurrent() ?: run { finish(); return }
        val awg = AwgProfileStore(this)
        val routes = RouteCatalog.from(config, awg.profiles())
        val root = screen(withBackdrop = true)
        root.addView(header("маршруты", "Выберите направление", true))
        root.addView(spacer(10, this))
        root.addView(note("Проверка задержки запускается вручную. Она показывает доступность точки, а не заменяет проверку соединения."))
        root.addView(spacer(12, this))
        globe = RouteGlobeView(this)
        globe.focus("AUTO", animate = false)
        root.addView(globe, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)))
        root.addView(text("точка на карте — выбранное направление", 11f, DeyttUi.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        })
        root.addView(spacer(18, this))
        root.addView(sectionLabel("быстрый выбор"))

        routes.firstOrNull { it.protocol == RouteProtocol.AUTO }?.let { auto ->
            addMeasuredRow(root, "Автоподбор", "Самый быстрый доступный маршрут", "AUTO", listOf(auto)) {
                globe.focus("AUTO")
                select(auto)
            }
            root.addView(spacer(12, this))
        }

        routes.firstOrNull { it.protocol == RouteProtocol.RU_DE }?.let { chain ->
            addMeasuredRow(root, "RU → DE", "Двойной маршрут для устойчивого обхода", "RU", listOf(chain)) {
                globe.focus("RU-DE")
                select(chain)
            }
            root.addView(spacer(12, this))
        }

        root.addView(spacer(10, this))
        root.addView(sectionLabel("направления"))
        routes.filter { it.countryCode in setOf("NL", "DE", "RU", "FI") }
            .groupBy { it.countryCode }
            .forEach { (code, countryRoutes) ->
                val first = countryRoutes.first()
                val protocols = countryRoutes.joinToString(" · ") { it.protocol.title }
                addMeasuredRow(root, first.country, protocols, code, countryRoutes) {
                    globe.focus(code)
                    startActivity(Intent(this@RoutesActivity, ProtocolActivity::class.java).putExtra("country", code))
                }
                root.addView(spacer(12, this))
            }

        root.addView(spacer(10, this))
        root.addView(sectionLabel("amneziawg"))
        listOf("15" to "AmneziaWG 1.5", "31" to "AmneziaWG 3.1").forEach { (version, title) ->
            val familyRoutes = routes.filter {
                it.engine == TunnelEngine.AMNEZIAWG &&
                    ((version == "15" && it.protocol == RouteProtocol.AWG15) ||
                        (version == "31" && it.protocol == RouteProtocol.AWG31))
            }
            val displayVersion = if (version == "15") "1.5" else "3.1"
            val item = if (familyRoutes.isEmpty()) {
                row(title, "Профили не загружены · обновите подписку", displayVersion, "обновить").apply {
                    alpha = .78f
                    setOnClickListener {
                        startActivity(Intent(this@RoutesActivity, SetupActivity::class.java))
                    }
                }
            } else {
                row(
                    title,
                    "${familyRoutes.size} ${if (familyRoutes.size == 1) "сервер" else "сервера"} · выбрать точку и проверить",
                    displayVersion,
                    "открыть",
                ).apply {
                    setOnClickListener {
                        globe.focus(familyRoutes.first().id.uppercase())
                        startActivity(
                            Intent(this@RoutesActivity, ProtocolActivity::class.java)
                                .putExtra("country", "AWG")
                                .putExtra("version", version),
                        )
                    }
                }
            }
            root.addView(item)
            root.addView(spacer(12, this))
        }
        present(root)
    }

    private fun addMeasuredRow(
        root: LinearLayout,
        title: String,
        subtitle: String,
        leading: String,
        routes: List<DeyttRoute>,
        onClick: () -> Unit,
    ) {
        val latency = actionLabel("проверить")
        val item = row(title, subtitle, leading, "").apply { setOnClickListener { onClick() } }
        latency.setOnClickListener { measure(routes, latency) }
        item.addView(latency, LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(item)
    }

    private fun measure(routes: List<DeyttRoute>, view: TextView) {
        val generation = ++latencyGeneration
        val config = SubscriptionStore(this).readCurrent() ?: return
        val awg = AwgProfileStore(this)
        val route = routes.firstOrNull()
        if (route == null) {
            view.text = "нет ответа"
            return
        }
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
                if (generation != latencyGeneration || isFinishing || isDestroyed) return@runOnUiThread
                view.text = label
                view.isEnabled = true
                view.alpha = 1f
            }
        }
    }

    override fun onDestroy() {
        latencyGeneration++
        super.onDestroy()
    }

    private fun select(route: DeyttRoute) {
        val config = SubscriptionStore(this).readCurrent() ?: return
        if (ConnectVpnService.isRunning()) {
            startService(Intent(this, ConnectVpnService::class.java).setAction(ConnectVpnService.ACTION_STOP))
        }
        AwgTunnelController.stop(this)
        if (route.engine == TunnelEngine.LIBBOX) {
            SubscriptionStore(this).saveValidated(ProfileRoutes.select(config, route.configTag))
        }
        SelectedRouteStore(this).save(route)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}
