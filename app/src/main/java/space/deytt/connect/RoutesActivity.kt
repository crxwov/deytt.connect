package space.deytt.connect

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import space.deytt.connect.DeyttUi.header
import space.deytt.connect.DeyttUi.present
import space.deytt.connect.DeyttUi.row
import space.deytt.connect.DeyttUi.screen
import space.deytt.connect.DeyttUi.spacer
import space.deytt.connect.DeyttUi.text

class RoutesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = SubscriptionStore(this).readCurrent() ?: run { finish(); return }
        val awg = AwgProfileStore(this)
        val routes = RouteCatalog.from(config, awg.read15() != null, awg.read31() != null)
        val root = screen()
        root.addView(header("маршруты", "Куда подключиться", true))
        root.addView(spacer(12, this))
        root.addView(text("Сначала выберите направление, затем протокол.", 15f, DeyttUi.MUTED))
        root.addView(spacer(24, this))

        routes.firstOrNull { it.protocol == RouteProtocol.AUTO }?.let { auto ->
            root.addView(row("Автоподбор", "Самый быстрый доступный маршрут", "✦").apply {
                setOnClickListener { select(auto) }
            })
            root.addView(spacer(12, this))
        }

        routes.filter { it.countryCode in setOf("NL", "DE", "RU", "FI") }
            .groupBy { it.countryCode }
            .forEach { (code, countryRoutes) ->
                val first = countryRoutes.first()
                val protocols = countryRoutes.joinToString(" · ") { it.protocol.title }
                root.addView(row(first.country, protocols, first.flag).apply {
                    setOnClickListener {
                        startActivity(Intent(this@RoutesActivity, ProtocolActivity::class.java).putExtra("country", code))
                    }
                })
                root.addView(spacer(12, this))
            }

        val awgRoutes = routes.filter { it.engine == TunnelEngine.AMNEZIAWG }
        if (awgRoutes.isNotEmpty()) {
            root.addView(spacer(10, this))
            root.addView(text("AMNEZIAWG", 12f, DeyttUi.MUTED, android.graphics.Typeface.BOLD).apply { letterSpacing = .18f })
            root.addView(spacer(10, this))
            root.addView(row("Защищённый туннель", awgRoutes.joinToString(" · ") { it.protocol.title }, "◈").apply {
                setOnClickListener {
                    startActivity(Intent(this@RoutesActivity, ProtocolActivity::class.java).putExtra("country", "AWG"))
                }
            })
        }
        present(root)
    }

    private fun select(route: DeyttRoute) {
        val config = SubscriptionStore(this).readCurrent() ?: return
        stopService(Intent(this, ConnectVpnService::class.java).setAction(ConnectVpnService.ACTION_STOP))
        AwgTunnelController.stop(this)
        SubscriptionStore(this).saveValidated(ProfileRoutes.select(config, route.configTag))
        SelectedRouteStore(this).save(route)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}
