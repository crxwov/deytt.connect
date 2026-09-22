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
    private val legacyTunFields = setOf(
        "inet4_address",
        "inet6_address",
        "inet4_route_address",
        "inet6_route_address",
        "inet4_route_exclude_address",
        "inet6_route_exclude_address",
    )

    fun validate(content: String): ProfileSummary {
        val root = try {
            JSONObject(content)
        } catch (error: Exception) {
            throw IllegalArgumentException("Сервер вернул невалидный JSON", error)
        }

        val inbounds = root.optJSONArray("inbounds")
            ?: throw IllegalArgumentException("В подписке отсутствует inbounds")
        val tun = findType(inbounds, "tun")
        if (tun == null) {
            throw IllegalArgumentException("Подписка не содержит системный TUN-вход")
        }
        if (legacyTunFields.any(tun::has)) {
            throw IllegalArgumentException(
                "Профиль устарел: импортируйте подписку заново для обновления TUN-конфигурации",
            )
        }
        if (!tun.has("address")) {
            throw IllegalArgumentException("В подписке не задан современный адрес TUN")
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
        if ((0 until outbounds.length()).any { index ->
                outbounds.optJSONObject(index)?.optString("type") == "dns"
            }) {
            throw IllegalArgumentException(
                "Профиль устарел: обновите подписку для нового DNS-маршрута",
            )
        }
        if ((0 until outbounds.length()).any { index ->
                outbounds.optJSONObject(index)?.optString("type") == "wireguard"
            }) {
            throw IllegalArgumentException(
                "Профиль устарел: обновите подписку для нового WireGuard-маршрута",
            )
        }
        if ((0 until outbounds.length()).any { index ->
                outbounds.optJSONObject(index)?.optJSONObject("tls")?.has("sni") == true
            }) {
            throw IllegalArgumentException(
                "Профиль устарел: обновите подписку для нового TLS-маршрута",
            )
        }

        val dns = root.optJSONObject("dns")
        val dnsServers = dns?.optJSONArray("servers")
        if (dnsServers != null && (0 until dnsServers.length()).any { index ->
                dnsServers.optJSONObject(index)?.has("address") == true
            }) {
            throw IllegalArgumentException(
                "Профиль устарел: обновите подписку для нового DNS-формата",
            )
        }

        val route = root.optJSONObject("route")
            ?: throw IllegalArgumentException("В подписке отсутствует route")
        if (route.optString("final").isBlank()) {
            throw IllegalArgumentException("В подписке не задан маршрут по умолчанию")
        }

        val routeRules = route.optJSONArray("rules")
        val hasDnsHijack = routeRules != null && (0 until routeRules.length()).any { index ->
            routeRules.optJSONObject(index)?.let { rule ->
                rule.optString("protocol") == "dns" &&
                    rule.optString("action") == "hijack-dns"
            } == true
        }
        if (!hasDnsHijack) {
            throw IllegalArgumentException(
                "В подписке отсутствует современный DNS-перехват",
            )
        }

        return ProfileSummary(outbounds.length(), protocols, hasTunInbound = true)
    }

    private fun findType(items: JSONArray, expected: String): JSONObject? {
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index)
            if (item?.optString("type") == expected) return item
        }
        return null
    }
}
