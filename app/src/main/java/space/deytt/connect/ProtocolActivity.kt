package space.deytt.connect

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import space.deytt.connect.DeyttUi.header
import space.deytt.connect.DeyttUi.present
import space.deytt.connect.DeyttUi.row
import space.deytt.connect.DeyttUi.screen
import space.deytt.connect.DeyttUi.spacer
import space.deytt.connect.DeyttUi.text

class ProtocolActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = intent.getStringExtra("country") ?: run { finish(); return }
        val config = SubscriptionStore(this).readCurrent() ?: run { finish(); return }
        val awg = AwgProfileStore(this)
        val routes = RouteCatalog.from(config, awg.read15() != null, awg.read31() != null)
            .filter { it.countryCode == code }
        val title = if (code == "AWG") "AmneziaWG" else routes.firstOrNull()?.let { "${it.flag} ${it.country}" } ?: "Протокол"
        val root = screen()
        root.addView(header("протокол", title, true))
        root.addView(spacer(12, this))
        root.addView(text("Можно сменить в любой момент. Активным остаётся только один туннель.", 15f, DeyttUi.MUTED))
        root.addView(spacer(24, this))
        routes.forEach { route ->
            val mark = when (route.protocol) {
                RouteProtocol.VLESS -> "V"
                RouteProtocol.TROJAN -> "T"
                RouteProtocol.HYSTERIA2 -> "H"
                RouteProtocol.AWG15 -> "1.5"
                RouteProtocol.AWG31 -> "3.1"
                else -> "✦"
            }
            root.addView(row(route.protocol.title, route.protocol.detail, mark).apply {
                setOnClickListener { select(route) }
            })
            root.addView(spacer(12, this))
        }
        present(root)
    }

    private fun select(route: DeyttRoute) {
        stopService(Intent(this, ConnectVpnService::class.java).setAction(ConnectVpnService.ACTION_STOP))
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
