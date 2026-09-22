package space.deytt.connect

import org.json.JSONArray
import org.json.JSONObject

data class RouteOption(
    val tag: String,
    val label: String,
)

object ProfileRoutes {
    private val networkTypes = setOf("hysteria2", "vless", "trojan", "shadowsocks", "tuic")

    fun selected(config: String): String = parse(config)
        .getJSONObject("route")
        .optString("final")

    fun options(config: String): List<RouteOption> {
        val root = parse(config)
        val result = linkedMapOf<String, RouteOption>()
        collectOutbounds(root.optJSONArray("outbounds"), result)
        collectOutbounds(root.optJSONArray("endpoints"), result)
        return result.values.toList()
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

    private fun collectOutbounds(source: JSONArray?, result: MutableMap<String, RouteOption>) {
        if (source == null) return
        for (index in 0 until source.length()) {
            val outbound = source.optJSONObject(index) ?: continue
            val type = outbound.optString("type").lowercase()
            val tag = outbound.optString("tag").trim()
            if (tag.isBlank() || type !in networkTypes + setOf("urltest", "selector", "wireguard")) continue
            result.putIfAbsent(tag, RouteOption(tag, presentableLabel(tag)))
        }
    }

    private fun parse(config: String): JSONObject = try {
        JSONObject(config)
    } catch (error: Exception) {
        throw IllegalArgumentException("Сохранённый профиль повреждён", error)
    }

    private fun presentableLabel(tag: String): String = when {
        tag.contains("автоподбор", ignoreCase = true) -> "Автоподбор"
        tag.equals("nl+de", ignoreCase = true) -> "NL + DE"
        else -> tag.replaceFirstChar { char -> char.titlecase() }
    }
}
