package space.deytt.connect

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import space.deytt.connect.DeyttUi.actionLabel
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.header
import space.deytt.connect.DeyttUi.present
import space.deytt.connect.DeyttUi.rounded
import space.deytt.connect.DeyttUi.row
import space.deytt.connect.DeyttUi.screen
import space.deytt.connect.DeyttUi.sectionLabel
import space.deytt.connect.DeyttUi.spacer
import space.deytt.connect.DeyttUi.text
import space.deytt.connect.DeyttUi.note
import space.deytt.connect.DeyttUi.mapPanel

class RoutesActivity : Activity() {
    private var latencyGeneration = 0
    private lateinit var globe: RouteGlobeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = SubscriptionStore(this).readCurrent() ?: run { finish(); return }
        val awg = AwgProfileStore(this)
        val routes = RouteCatalog.from(config, awg.profiles())
        val selectedId = SelectedRouteStore(this).read().id
        val root = screen(withBackdrop = true)
        root.addView(header("deytt. network", "Маршруты"))
        root.addView(spacer(4, this))
        globe = RouteGlobeView(this).apply {
            focus(selectedId, animate = false)
        }
        root.addView(mapPanel(globe), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(194)))
        root.addView(spacer(16, this))
        root.addView(sectionLabel("выходы · задержка"))
        val exitGroup = routeGroup()

        routes.firstOrNull { it.protocol == RouteProtocol.AUTO }?.let { auto ->
            addMeasuredRow(
                exitGroup,
                "Автоподбор",
                "Доступная точка выбирается автоматически",
                "AUTO",
                listOf(auto),
                emphasis = selectedId == auto.id,
            ) {
                globe.focus("AUTO")
                select(auto)
            }
        }

        routes.firstOrNull { it.protocol == RouteProtocol.RU_DE }?.let { chain ->
            addMeasuredRow(
                exitGroup,
                "Россия → Германия",
                "2 этапа · Санкт-Петербург → Франкфурт",
                "2×",
                listOf(chain),
                emphasis = selectedId == chain.id,
            ) {
                globe.focus("RU-DE")
                select(chain)
            }
        }
        root.addView(exitGroup)

        root.addView(spacer(18, this))
        root.addView(sectionLabel("по стране"))
        val countryGroup = routeGroup()
        routes.filter { it.countryCode in setOf("NL", "DE", "RU", "FI") }
            .groupBy { it.countryCode }
            .forEach { (code, countryRoutes) ->
                val first = countryRoutes.first()
                val protocols = countryRoutes.joinToString(" · ") { it.protocol.title }
                addMeasuredRow(countryGroup, first.country, protocols, code, countryRoutes, emphasis = countryRoutes.any { it.id == selectedId }) {
                    globe.focus(code)
                    startActivity(Intent(this@RoutesActivity, ProtocolActivity::class.java).putExtra("country", code))
                }
            }
        root.addView(countryGroup)

        root.addView(spacer(18, this))
        root.addView(sectionLabel("AmneziaWG"))
        val awgGroup = routeGroup()
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
                    "${familyRoutes.size} ${if (familyRoutes.size == 1) "сервер" else "сервера"} · выбрать точку",
                    displayVersion,
                    "открыть",
                    emphasis = familyRoutes.any { it.id == selectedId },
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
            appendToGroup(awgGroup, item)
        }
        root.addView(awgGroup)
        present(root)
    }

    private fun routeGroup() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = this@RoutesActivity.rounded(DeyttUi.SURFACE, 20f, DeyttUi.LINE)
        clipToOutline = true
    }

    private fun appendToGroup(group: LinearLayout, item: View) {
        if (group.childCount > 0) {
            group.addView(View(this).apply { setBackgroundColor(DeyttUi.LINE) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    leftMargin = dp(54)
                    rightMargin = dp(16)
                })
        }
        group.addView(item)
    }

    private fun addMeasuredRow(
        root: LinearLayout,
        title: String,
        subtitle: String,
        leading: String,
        routes: List<DeyttRoute>,
        emphasis: Boolean = false,
        onClick: () -> Unit,
    ) {
        val latency = actionLabel()
        val item = row(title, subtitle, leading, "", emphasis = emphasis).apply { setOnClickListener { onClick() } }
        latency.setOnClickListener { measure(routes, latency) }
        item.addView(latency, LinearLayout.LayoutParams(dp(68), dp(40)))
        appendToGroup(root, item)
    }

    private fun measure(routes: List<DeyttRoute>, view: TextView) {
        val generation = ++latencyGeneration
        val config = SubscriptionStore(this).readCurrent() ?: return
        val awg = AwgProfileStore(this)
        val route = routes.firstOrNull()
        if (route == null) {
            view.text = "нет ответа"
            view.textSize = 8.5f
            view.setTextColor(DeyttUi.CORAL)
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
            view.textSize = 8.5f
            view.setTextColor(DeyttUi.CORAL)
            return
        }
        LatencyExecutor.pool.execute {
            val label = RouteLatency.label(RouteLatency.measure(target))
            runOnUiThread {
                if (generation != latencyGeneration || isFinishing || isDestroyed) return@runOnUiThread
                view.text = label
                view.isEnabled = true
                view.alpha = 1f
                view.textSize = 9f
                view.setTextColor(if (label.contains("мс", ignoreCase = true)) DeyttUi.MINT else DeyttUi.CORAL)
                view.contentDescription = "Задержка маршрута $title: $label"
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
