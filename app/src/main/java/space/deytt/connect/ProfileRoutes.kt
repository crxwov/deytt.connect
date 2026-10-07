package space.deytt.connect

import org.json.JSONObject

data class RouteOption(
    val tag: String,
    val label: String,
    val flag: String,
    val detail: String,
    val recommended: Boolean = false,
)

object ProfileRoutes {
    private const val ROUTE_PREFIX = "route:"
    private val countryPresentation = mapOf(
        "NL" to Pair("🇳🇱", "нидерланды"),
        "DE" to Pair("🇩🇪", "германия"),
        "RU" to Pair("🇷🇺", "россия"),
        "FI" to Pair("🇫🇮", "финляндия"),
        "IT" to Pair("🇮🇹", "италия"),
        "RU-DE" to Pair("🇷🇺→🇩🇪", "ru → de"),
    )

    fun selected(config: String): String = parse(config)
        .getJSONObject("route")
        .optString("final")

    fun options(config: String): List<RouteOption> {
        val root = parse(config)
        val source = root.optJSONArray("outbounds") ?: return emptyList()
        val result = mutableListOf<RouteOption>()
        for (index in 0 until source.length()) {
            val outbound = source.optJSONObject(index) ?: continue
            val tag = outbound.optString("tag").trim()
            when {
                tag.contains("автоподбор", ignoreCase = true) -> result += RouteOption(
                    tag = tag,
                    label = "автоподбор",
                    flag = "✦",
                    detail = "самый быстрый доступный маршрут",
                    recommended = true,
                )
                tag.startsWith(ROUTE_PREFIX) && tag.count { it == ':' } == 1 -> {
                    val code = tag.removePrefix(ROUTE_PREFIX).uppercase()
                    val presentation = countryPresentation[code] ?: continue
                    result += RouteOption(
                        tag = tag,
                        label = presentation.second,
                        flag = presentation.first,
                        detail = "vless · trojan · hysteria 2",
                    )
                }
            }
        }
        return result.distinctBy(RouteOption::tag)
    }

    fun select(config: String, tag: String): String {
        val root = parse(config)
        val outbounds = root.optJSONArray("outbounds")
        val publicTag = tag.contains("автоподбор", ignoreCase = true) ||
            (tag.startsWith(ROUTE_PREFIX) && tag.count { it == ':' } in 1..2)
        val exists = publicTag && outbounds != null && (0 until outbounds.length()).any {
            outbounds.optJSONObject(it)?.optString("tag") == tag
        }
        require(exists) {
            "выбранный маршрут отсутствует в подписке"
        }
        root.getJSONObject("route").put("final", tag)
        root.optJSONObject("dns")?.optJSONArray("servers")?.let { servers ->
            for (index in 0 until servers.length()) {
                servers.optJSONObject(index)
                    ?.takeIf { it.has("detour") && it.optString("tag") != "local-dns" }
                    ?.put("detour", tag)
            }
        }
        return root.toString()
    }

    private fun parse(config: String): JSONObject = try {
        JSONObject(config)
    } catch (error: Exception) {
        throw IllegalArgumentException("сохранённый профиль повреждён", error)
    }

}
