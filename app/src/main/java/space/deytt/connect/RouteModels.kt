package space.deytt.connect

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

enum class TunnelEngine { LIBBOX, AMNEZIAWG }

enum class RouteProtocol(val title: String, val detail: String) {
    AUTO("Автоподбор", "Приложение выберет лучший доступный маршрут"),
    RU_DE("RU → DE", "Двойной маршрут через Россию и Германию"),
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
    val profileName: String? = null,
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
        "RU-DE" to ("🇷🇺→🇩🇪" to "RU → DE"),
    )

    private val awgCountries = mapOf(
        "NL" to ("🇳🇱" to "Нидерланды"),
        "DE" to ("🇩🇪" to "Германия"),
        "RU" to ("🇷🇺" to "Россия"),
        "FI" to ("🇫🇮" to "Финляндия"),
    )

    private fun awgCountry(profile: AwgProfile): Pair<String, Pair<String, String>>? {
        val mark = Regex("^(NL|DE|RU|FI)(?:$|[-_\\s])", RegexOption.IGNORE_CASE)
            .find(profile.shortLabel.trim())?.groupValues?.get(1)?.uppercase()
            ?: return null
        return awgCountries[mark]?.let { mark to it }
    }

    fun from(config: String, awg15: Boolean, awg31: Boolean): List<DeyttRoute> {
        val legacyProfiles = listOfNotNull(
            if (awg15) AwgProfile("awg15", "15", "Основной", "AWG", "") else null,
            if (awg31) AwgProfile("awg31", "31", "Основной", "AWG", "") else null,
        )
        return from(config, legacyProfiles)
    }

    fun from(config: String, awgProfiles: List<AwgProfile>): List<DeyttRoute> {
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
                    "CHAIN" -> RouteProtocol.RU_DE
                    "VLESS" -> RouteProtocol.VLESS
                    "TROJAN" -> RouteProtocol.TROJAN
                    "HYSTERIA2" -> RouteProtocol.HYSTERIA2
                    else -> continue
                }
                routes += DeyttRoute(tag, parts[1], presentation.second, presentation.first, protocol, TunnelEngine.LIBBOX, tag)
            }
        }
        awgProfiles.forEach { profile ->
            val protocol = if (profile.version == "31") RouteProtocol.AWG31 else RouteProtocol.AWG15
            val country = awgCountry(profile)
            routes += DeyttRoute(
                profile.id,
                country?.first ?: "AWG_UNKNOWN",
                country?.second?.second ?: "Регион не указан",
                country?.second?.first ?: "AWG_MARK",
                protocol,
                TunnelEngine.AMNEZIAWG,
                profileName = profile.label,
            )
        }
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
