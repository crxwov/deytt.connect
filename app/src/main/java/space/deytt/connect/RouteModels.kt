package space.deytt.connect

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

enum class TunnelEngine { LIBBOX, AMNEZIAWG, OPENFLUX }

enum class RouteProtocol(val title: String, val detail: String) {
    AUTO("автоподбор", "приложение выберет лучший доступный маршрут"),
    RU_DE("ru → de", "двойной маршрут через россию и германию"),
    VLESS("vless", "websocket + tls"),
    TROJAN("trojan", "websocket + tls"),
    HYSTERIA2("hysteria 2", "быстрый quic-маршрут"),
    TUIC("tuic", "оптимизированный quic-маршрут"),
    LTE("lte", "обход белых списков через CDN"),
    AWG15("amneziawg 1.5", "маскировка трафика"),
    AWG31("amneziawg 3.1", "новая маскировка трафика"),
    OPENFLUX("обход · германия", "через cups → германию");

    val bypassNotificationLabel: String
        get() = when (this) {
            AUTO -> "auto"
            RU_DE -> "ru-de"
            VLESS -> "vless"
            TROJAN -> "trojan"
            HYSTERIA2 -> "hysteria"
            TUIC -> "tuic"
            LTE -> "lte"
            AWG15 -> "awg 1.5"
            AWG31 -> "awg 3.1"
            OPENFLUX -> "openflux"
        }
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
    val countryCode: String = "",
    val flag: String = "",
    val protocol: RouteProtocol = RouteProtocol.AUTO,
)

class SelectedRouteStore(context: Context) {
    private val prefs = context.getSharedPreferences("selected_route", Context.MODE_PRIVATE)

    fun save(route: DeyttRoute) {
        prefs.edit {
            putString("id", route.id)
            putString("title", when (route.protocol) {
                RouteProtocol.AUTO -> route.protocol.title
                RouteProtocol.LTE -> "${route.flag} lte"
                else -> "${route.flag} ${route.country}"
            })
            putString(
                "subtitle",
                when (route.protocol) {
                    RouteProtocol.AUTO -> "лучший доступный маршрут"
                    RouteProtocol.LTE -> "обход белых списков через CDN"
                    else -> route.protocol.title
                },
            )
            putString("engine", route.engine.name)
            putString("tag", route.configTag)
            putString("country_code", route.countryCode)
            putString("flag", route.flag)
            putString("protocol", route.protocol.name)
        }
    }

    fun read(): SelectedRoute {
        val id = prefs.getString("id", "auto") ?: "auto"
        var countryCode = prefs.getString("country_code", "").orEmpty()
        var flag = prefs.getString("flag", "").orEmpty()
        val protocolStr = prefs.getString("protocol", null)
        var protocol = if (protocolStr != null) {
            runCatching { RouteProtocol.valueOf(protocolStr) }.getOrDefault(RouteProtocol.AUTO)
        } else {
            RouteProtocol.AUTO
        }
        val configTag = prefs.getString("tag", "").orEmpty()
        val engine = runCatching {
            TunnelEngine.valueOf(prefs.getString("engine", TunnelEngine.LIBBOX.name)!!)
        }.getOrDefault(TunnelEngine.LIBBOX)

        if (countryCode.isBlank() || protocolStr == null) {
            if (id == "auto") {
                countryCode = "AUTO"
                flag = "✦"
                protocol = RouteProtocol.AUTO
            } else if (configTag.startsWith("route:")) {
                val parts = configTag.split(':')
                if (parts.size >= 3) {
                    countryCode = parts[1]
                    flag = RouteCatalog.flag(countryCode)
                    protocol = when (parts[2]) {
                        "CHAIN" -> RouteProtocol.RU_DE
                        "VLESS" -> RouteProtocol.VLESS
                        "TROJAN" -> RouteProtocol.TROJAN
                        "HYSTERIA2" -> RouteProtocol.HYSTERIA2
                        "TUIC" -> RouteProtocol.TUIC
                        else -> protocol
                    }
                }
            } else if (engine == TunnelEngine.OPENFLUX) {
                countryCode = "DE"
                flag = RouteCatalog.flag("DE")
                protocol = RouteProtocol.OPENFLUX
            } else if (engine == TunnelEngine.AMNEZIAWG) {
                val mark = Regex("^(NL|DE|RU|FI|IT)(?:$|[-_\\s:])", RegexOption.IGNORE_CASE)
                    .find(id)?.groupValues?.get(1)?.uppercase()
                if (mark != null) {
                    countryCode = mark
                    flag = RouteCatalog.flag(mark)
                }
            }
        }

        return SelectedRoute(
            id = id,
            title = prefs.getString("title", "автоподбор") ?: "автоподбор",
            subtitle = prefs.getString("subtitle", "лучший доступный маршрут") ?: "лучший доступный маршрут",
            engine = engine,
            configTag = configTag,
            countryCode = countryCode,
            flag = flag,
            protocol = protocol,
        )
    }
}

object RouteCatalog {
    private val countries = mapOf(
        "NL" to ("🇳🇱" to "нидерланды"),
        "DE" to ("🇩🇪" to "германия"),
        "RU" to ("🇷🇺" to "россия"),
        "FI" to ("🇫🇮" to "финляндия"),
        "IT" to ("🇮🇹" to "италия"),
    )

    fun flag(code: String): String = when (code.trim().uppercase()) {
        "NL" -> "🇳🇱"
        "DE" -> "🇩🇪"
        "RU" -> "🇷🇺"
        "FI" -> "🇫🇮"
        "IT" -> "🇮🇹"
        "AUTO" -> "✦"
        else -> "🌐"
    }

    private val awgCountries = mapOf(
        "NL" to ("🇳🇱" to "нидерланды"),
        "DE" to ("🇩🇪" to "германия"),
        "RU" to ("🇷🇺" to "россия"),
        "FI" to ("🇫🇮" to "финляндия"),
        "IT" to ("🇮🇹" to "италия"),
    )

    /** Derive visible countries from the same supported catalog used to parse routes. */
    fun countryGroups(routes: List<DeyttRoute>): List<Pair<String, List<DeyttRoute>>> {
        val grouped = routes.groupBy { it.countryCode }
        return (countries.keys + "AWG_UNKNOWN").mapNotNull { code ->
            grouped[code]?.distinctBy { it.id }?.takeIf { it.isNotEmpty() }?.let { code to it }
        }
    }

    private fun awgCountry(profile: AwgProfile): Pair<String, Pair<String, String>>? {
        val mark = Regex("^(NL|DE|RU|FI|IT)(?:$|[-_\\s])", RegexOption.IGNORE_CASE)
            .find(profile.shortLabel.trim())?.groupValues?.get(1)?.uppercase()
            ?: return null
        return awgCountries[mark]?.let { mark to it }
    }

    @Suppress("UNUSED_PARAMETER")
    fun from(config: String, awgProfiles: List<AwgProfile>, openFluxProfiles: List<OpenFluxProfile> = emptyList()): List<DeyttRoute> {
        val automaticTag = autoTag(config)
        require(automaticTag.isNotBlank()) { "В подписке отсутствует автоподбор" }
        val routes = mutableListOf(
            DeyttRoute("auto", "AUTO", "автоподбор", "✦", RouteProtocol.AUTO, TunnelEngine.LIBBOX, automaticTag),
        )
        val outbounds = JSONObject(config).optJSONArray("outbounds")
        if (outbounds != null) {
            for (index in 0 until outbounds.length()) {
                val tag = outbounds.optJSONObject(index)?.optString("tag").orEmpty()
                val parts = tag.split(':')
                if (parts.size != 3 || parts[0] != "route") continue
                if (parts[2] == "LTE") {
                    if (tag == CdnLteProfile.ROUTE_TAG && CdnLteProfile.hasValidMetadata(config)) {
                        routes += DeyttRoute(tag, "DE", "lte", "🇪🇺", RouteProtocol.LTE, TunnelEngine.LIBBOX, tag)
                    }
                    continue
                }
                val presentation = countries[parts[1]] ?: continue
                val protocol = when (parts[2]) {
                    "VLESS" -> RouteProtocol.VLESS
                    "TROJAN" -> RouteProtocol.TROJAN
                    "HYSTERIA2" -> RouteProtocol.HYSTERIA2
                    "TUIC" -> RouteProtocol.TUIC
                    else -> continue
                }
                routes += DeyttRoute(tag, parts[1], presentation.second, presentation.first, protocol, TunnelEngine.LIBBOX, tag)
            }
        }
        for (profile in awgProfiles) {
            if (profile.version !in setOf("15", "31")) continue
            val country = awgCountry(profile)
            val protocol = when (profile.version) {
                "15" -> RouteProtocol.AWG15
                "31" -> RouteProtocol.AWG31
                else -> error("Unsupported AmneziaWG version: ${profile.version}")
            }
            routes += DeyttRoute(
                profile.id,
                country?.first ?: "AWG_UNKNOWN",
                country?.second?.second ?: "регион не указан",
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
