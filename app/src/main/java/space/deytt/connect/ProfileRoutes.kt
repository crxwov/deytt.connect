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
        "NL" to Pair("🇳🇱", "Нидерланды"),
        "DE" to Pair("🇩🇪", "Германия"),
        "RU" to Pair("🇷🇺", "Россия"),
        "FI" to Pair("🇫🇮", "Финляндия"),
        "NL-DE" to Pair("🇳🇱", "Нидерланды → Германия"),
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
            if (outbound.optString("type") != "urltest") continue
            val tag = outbound.optString("tag").trim()
            when {
                tag.contains("автоподбор", ignoreCase = true) -> result += RouteOption(
                    tag = tag,
                    label = "Автоподбор",
                    flag = "✦",
                    detail = "Самый быстрый доступный маршрут",
                    recommended = true,
                )
                tag.startsWith(ROUTE_PREFIX) -> {
                    val code = tag.removePrefix(ROUTE_PREFIX).uppercase()
                    val presentation = countryPresentation[code] ?: continue
                    result += RouteOption(
                        tag = tag,
                        label = presentation.second,
                        flag = presentation.first,
                        detail = "Автовыбор рабочего узла · Hysteria 2",
                    )
                }
            }
        }
        return result.distinctBy(RouteOption::tag)
    }

    fun select(config: String, tag: String): String {
        require(options(config).any { it.tag == tag }) { "Выбранный маршрут отсутствует в подписке" }
        val root = parse(config)
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
        throw IllegalArgumentException("Сохранённый профиль повреждён", error)
    }

}
