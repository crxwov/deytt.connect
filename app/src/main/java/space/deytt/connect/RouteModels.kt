package space.deytt.connect

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

enum class TunnelEngine { LIBBOX, AMNEZIAWG }

enum class RouteProtocol(val title: String, val detail: String) {
    AUTO("Автоподбор", "Приложение выберет лучший доступный маршрут"),
    VLESS("VLESS", "WebSocket + TLS"),
    TROJAN("Trojan", "WebSocket + TLS"),
    HYSTERIA2("Hysteria 2", "Быстрый QUIC-маршрут"),
    AWG15("AmneziaWG 1.5", "Основной защищённый туннель"),
    AWG31("AmneziaWG 3.1", "Новая маскировка трафика"),
}

data class DeyttRoute(
    val id: String,
    val countryCode: String,
    val country: String,
    val flag: String,
    val protocol: RouteProtocol,
    val engine: TunnelEngine,
    val configTag: String = "",
)

data class SelectedRoute(
    val id: String,
    val title: String,
    val subtitle: String,
    val engine: TunnelEngine,
    val configTag: String,
)

class SelectedRouteStore(context: Context) {
    private val prefs = context.getSharedPreferences("selected_route", Context.MODE_PRIVATE)

    fun save(route: DeyttRoute) {
        prefs.edit {
            putString("id", route.id)
            putString("title", if (route.protocol == RouteProtocol.AUTO) route.protocol.title else "${route.flag} ${route.country}")
            putString(
                "subtitle",
                if (route.protocol == RouteProtocol.AUTO) "Лучший доступный маршрут" else route.protocol.title,
            )
            putString("engine", route.engine.name)
            putString("tag", route.configTag)
        }
    }

    fun read(): SelectedRoute = SelectedRoute(
        id = prefs.getString("id", "auto") ?: "auto",
        title = prefs.getString("title", "Автоподбор") ?: "Автоподбор",
        subtitle = prefs.getString("subtitle", "Лучший доступный маршрут") ?: "Лучший доступный маршрут",
        engine = runCatching {
            TunnelEngine.valueOf(prefs.getString("engine", TunnelEngine.LIBBOX.name)!!)
        }.getOrDefault(TunnelEngine.LIBBOX),
        configTag = prefs.getString("tag", "") ?: "",
    )
}

object RouteCatalog {
    private val countries = mapOf(
        "NL" to ("🇳🇱" to "Нидерланды"),
        "DE" to ("🇩🇪" to "Германия"),
        "RU" to ("🇷🇺" to "Россия"),
        "FI" to ("🇫🇮" to "Финляндия"),
    )

    fun from(config: String, awg15: Boolean, awg31: Boolean): List<DeyttRoute> {
        val automaticTag = autoTag(config)
        require(automaticTag.isNotBlank()) { "В подписке отсутствует автоподбор" }
        val routes = mutableListOf(
            DeyttRoute("auto", "AUTO", "Автоподбор", "✦", RouteProtocol.AUTO, TunnelEngine.LIBBOX, automaticTag),
        )
        val outbounds = JSONObject(config).optJSONArray("outbounds")
        if (outbounds != null) {
            for (index in 0 until outbounds.length()) {
                val tag = outbounds.optJSONObject(index)?.optString("tag").orEmpty()
                val parts = tag.split(':')
                if (parts.size != 3 || parts[0] != "route") continue
                val presentation = countries[parts[1]] ?: continue
                val protocol = when (parts[2]) {
                    "VLESS" -> RouteProtocol.VLESS
                    "TROJAN" -> RouteProtocol.TROJAN
                    "HYSTERIA2" -> RouteProtocol.HYSTERIA2
                    else -> continue
                }
                routes += DeyttRoute(tag, parts[1], presentation.second, presentation.first, protocol, TunnelEngine.LIBBOX, tag)
            }
        }
        if (awg15) routes += DeyttRoute("awg15", "AWG", "Основной", "◈", RouteProtocol.AWG15, TunnelEngine.AMNEZIAWG)
        if (awg31) routes += DeyttRoute("awg31", "AWG", "Основной", "◈", RouteProtocol.AWG31, TunnelEngine.AMNEZIAWG)
        return routes
    }

    private fun autoTag(config: String): String {
        val outbounds = JSONObject(config).optJSONArray("outbounds") ?: return ""
        for (index in 0 until outbounds.length()) {
            val tag = outbounds.optJSONObject(index)?.optString("tag").orEmpty()
            if (tag.contains("автоподбор", ignoreCase = true)) return tag
        }
        return ""
    }
}
