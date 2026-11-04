package space.deytt.connect

import org.json.JSONArray
import org.json.JSONObject

/** Adds Android-only paths without persisting device-specific data in the subscription. */
object RuntimeProfile {
    fun withPrivateCacheFile(config: String, absoluteCachePath: String): String {
        require(absoluteCachePath.startsWith('/')) { "Путь к cache-file должен быть абсолютным" }
        val root = try {
            JSONObject(config)
        } catch (error: Exception) {
            throw IllegalArgumentException("Сохранённый профиль повреждён", error)
        }
        root.remove("deytt")
        val experimental = root.optJSONObject("experimental") ?: JSONObject().also {
            root.put("experimental", it)
        }
        val cacheFile = experimental.optJSONObject("cache_file") ?: JSONObject().also {
            experimental.put("cache_file", it)
        }
        cacheFile.put("enabled", true)
        cacheFile.put("path", absoluteCachePath)
        cacheFile.put("store_fakeip", false)
        cacheFile.put("store_dns", false)

        val inbounds = root.optJSONArray("inbounds")
            ?: throw IllegalArgumentException("В профиле отсутствуют входящие подключения")
        for (index in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(index) ?: continue
            if (inbound.optString("type") == "tun") {
                inbound.put("dns_mode", "hijack")
                val addresses = inbound.optJSONArray("address")
                    ?: JSONArray().also { inbound.put("address", it) }
                val hasIpv6 = (0 until addresses.length()).any {
                    addresses.optString(it).contains(':')
                }
                if (!hasIpv6) {
                    addresses.put("fdfe:dcba:9876::1/126")
                }
                inbound.put("auto_route", true)
                inbound.put("strict_route", true)
                inbound.put("stack", "mixed")
            }
        }

        sanitizeOutbounds(root.optJSONArray("outbounds"))
        sanitizeOutbounds(root.optJSONArray("endpoints"))

        val dns = root.optJSONObject("dns")
            ?: throw IllegalArgumentException("В профиле отсутствуют настройки DNS")
        dns.put("strategy", "ipv4_only")
        val servers = dns.optJSONArray("servers")
            ?: throw IllegalArgumentException("В профиле отсутствуют DNS-серверы")
        var bootstrapFound = false
        for (index in 0 until servers.length()) {
            val server = servers.optJSONObject(index) ?: continue
            if (server.optString("tag") != "local-dns") continue
            server.put("type", "local")
            server.remove("server")
            server.remove("server_port")
            server.remove("detour")
            server.remove("path")
            server.remove("tls")
            bootstrapFound = true
        }
        if (!bootstrapFound) {
            servers.put(
                JSONObject()
                    .put("type", "local")
                    .put("tag", "local-dns"),
            )
        }
        val route = root.optJSONObject("route")
            ?: throw IllegalArgumentException("В профиле отсутствуют правила маршрутизации")
        route.put("default_domain_resolver", if (route.optString("final") == SplitTunnelProfile.OPENFLUX_OUTBOUND) {
            "remote-dns"
        } else "local-dns")

        val rules = route.optJSONArray("rules") ?: JSONArray().also { route.put("rules", it) }
        val hasIpv6Reject = (0 until rules.length()).any { index ->
            val rule = rules.optJSONObject(index)
            rule != null && rule.optInt("ip_version") == 6 && rule.optString("action") == "reject"
        }
        if (!hasIpv6Reject) {
            val mergedRules = JSONArray()
            var inserted = false
            val rejectRule = JSONObject().put("ip_version", 6).put("action", "reject")
            for (index in 0 until rules.length()) {
                val rule = rules.optJSONObject(index) ?: continue
                if (!inserted && rule.optString("action") !in listOf("sniff", "hijack-dns")) {
                    mergedRules.put(rejectRule)
                    inserted = true
                }
                mergedRules.put(rule)
            }
            if (!inserted) {
                mergedRules.put(rejectRule)
            }
            route.put("rules", mergedRules)
        }

        return root.toString()
    }

    fun sanitizeOutbounds(items: org.json.JSONArray?) {
        if (items == null) return
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            sanitizeOutbound(item)
        }
    }

    fun sanitizeOutbound(outbound: JSONObject) {
        val serverPorts = outbound.optJSONArray("server_ports") ?: return
        val hasServerPort = outbound.has("server_port") && outbound.optInt("server_port") > 0
        val validRanges = org.json.JSONArray()
        for (i in 0 until serverPorts.length()) {
            val entry = serverPorts.optString(i).trim()
            if (entry.isEmpty()) continue
            if (entry.all { it.isDigit() }) {
                validRanges.put("$entry:$entry")
            } else if (entry.contains(":") || entry.contains("-")) {
                validRanges.put(entry.replace("-", ":"))
            }
        }
        if (hasServerPort) {
            outbound.remove("server_ports")
            outbound.remove("hop_interval")
            outbound.remove("hop_interval_max")
        } else if (validRanges.length() > 0) {
            outbound.put("server_ports", validRanges)
        } else {
            outbound.remove("server_ports")
            outbound.remove("hop_interval")
            outbound.remove("hop_interval_max")
        }
    }
}
