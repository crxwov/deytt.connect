package space.deytt.connect

import org.json.JSONArray
import org.json.JSONObject

data class ProfileSummary(
    val outboundCount: Int,
    val protocols: Set<String>,
    val hasTunInbound: Boolean,
)

object ProfileValidator {
    private val networkProtocols = setOf("hysteria2", "vless", "trojan", "wireguard", "shadowsocks")

    fun validate(content: String): ProfileSummary {
        val root = try {
            JSONObject(content)
        } catch (error: Exception) {
            throw IllegalArgumentException("Сервер вернул невалидный JSON", error)
        }

        val inbounds = root.optJSONArray("inbounds")
            ?: throw IllegalArgumentException("В подписке отсутствует inbounds")
        val hasTun = hasType(inbounds, "tun")
        if (!hasTun) {
            throw IllegalArgumentException("Подписка не содержит системный TUN-вход")
        }

        val outbounds = root.optJSONArray("outbounds")
            ?: throw IllegalArgumentException("В подписке отсутствует outbounds")
        val protocols = mutableSetOf<String>()
        var hasNetworkOutbound = false
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            val type = outbound.optString("type").lowercase()
            if (type in networkProtocols) {
                protocols += type
                hasNetworkOutbound = true
            }
            if (type == "urltest" || type == "selector") {
                hasNetworkOutbound = true
            }
        }
        if (!hasNetworkOutbound) {
            throw IllegalArgumentException("В подписке нет рабочего сетевого выхода")
        }

        val route = root.optJSONObject("route")
            ?: throw IllegalArgumentException("В подписке отсутствует route")
        if (route.optString("final").isBlank()) {
            throw IllegalArgumentException("В подписке не задан маршрут по умолчанию")
        }

        return ProfileSummary(outbounds.length(), protocols, hasTun)
    }

    private fun hasType(items: JSONArray, expected: String): Boolean {
        for (index in 0 until items.length()) {
            if (items.optJSONObject(index)?.optString("type") == expected) return true
        }
        return false
    }
}

