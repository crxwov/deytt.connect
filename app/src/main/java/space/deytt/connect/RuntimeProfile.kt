package space.deytt.connect

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
            if (inbound.optString("type") == "tun") inbound.put("dns_mode", "hijack")
        }

        val dns = root.optJSONObject("dns")
            ?: throw IllegalArgumentException("В профиле отсутствуют настройки DNS")
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
        route.put("default_domain_resolver", "local-dns")
        return root.toString()
    }
}
