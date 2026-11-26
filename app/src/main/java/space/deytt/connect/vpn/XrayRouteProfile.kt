package space.deytt.connect

import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID

internal object XrayRouteProfile {
    private const val AUTO_TAG = "route:AUTO:VLESS"
    private val domain = Regex("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*deytt\\.space")
    private val routeTag = Regex("route:(AUTO|[A-Z]{2}):[A-Z0-9_]{1,20}")

    data class Runtime(val profile: String, val xray: String)

    fun validate(profile: String) {
        routes(JSONObject(profile))
    }

    fun describeTransports(profile: JSONObject): Map<String, String> =
        routes(profile).associate { it.tag to it.stream.getString("network") }

    fun prepare(profile: String, resolve: (String) -> InetAddress): Runtime? {
        val root = JSONObject(profile)
        val routes = routes(root)
        if (routes.isEmpty()) return null

        val loopback = InetAddress.getByName("127.0.0.1")
        val reservations = mutableListOf<ServerSocket>()
        val listeners = JSONArray()
        val exits = JSONArray()
        val bindings = JSONArray()
        val physicalIps = linkedSetOf<String>()
        val outbounds = root.getJSONArray("outbounds")
        val addresses = mutableMapOf<String, InetAddress>()
        try {
            for ((tag, stream, host, port, uuid) in routes) {
                val address = addresses.getOrPut(host) { resolve(host) }
                val ip = requireNotNull(address.hostAddress).substringBefore('%')
                physicalIps.add("$ip/${if (address.address.size == 4) 32 else 128}")
                val socket = ServerSocket(0, 1, loopback).also(reservations::add)
                val inboundTag = "local:$tag"
                listeners.put(JSONObject().put("tag", inboundTag).put("listen", "127.0.0.1")
                    .put("port", socket.localPort).put("protocol", "socks")
                    .put("settings", JSONObject().put("auth", "noauth").put("udp", true)))
                val user = JSONObject().put("id", uuid).put("encryption", "none")
                val endpoint = JSONObject().put("address", ip).put("port", port)
                    .put("users", JSONArray().put(user))
                exits.put(JSONObject().put("tag", tag).put("protocol", "vless")
                    .put("settings", JSONObject().put("vnext", JSONArray().put(endpoint)))
                    .put("streamSettings", stream))
                bindings.put(JSONObject().put("type", "field")
                    .put("inboundTag", JSONArray().put(inboundTag)).put("outboundTag", tag))

                val index = (0 until outbounds.length()).firstOrNull {
                    outbounds.getJSONObject(it).optString("tag") == tag
                } ?: outbounds.length()
                outbounds.put(index, JSONObject().put("tag", tag).put("type", "socks")
                    .put("server", "127.0.0.1").put("server_port", socket.localPort))
                addToSelector(outbounds, tag, "автоподбор", auto = true)
                if (tag != AUTO_TAG) addToSelector(outbounds, tag, tag.substringBeforeLast(':'))
            }
        } finally {
            reservations.forEach { it.close() }
        }
        if (routes.any { it.tag == AUTO_TAG }) {
            val auto = (0 until outbounds.length()).map(outbounds::getJSONObject).firstOrNull {
                it.optString("tag").contains("автоподбор", ignoreCase = true)
            }
            val members = routes.map(Route::tag).filterNot { it.startsWith("route:RU:") }
            auto?.put("outbounds", JSONArray(members))?.remove("default")
        }
        val route = root.getJSONObject("route")
        val rules = route.getJSONArray("rules")
        val updatedRules = JSONArray().put(JSONObject().put("ip_cidr", JSONArray(physicalIps))
            .put("action", "route").put("outbound", "direct"))
        for (index in 0 until rules.length()) updatedRules.put(rules.get(index))
        route.put("rules", updatedRules)
        val xray = JSONObject().put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", listeners).put("outbounds", exits)
            .put("routing", JSONObject().put("rules", bindings))
        return Runtime(root.toString(), xray.toString())
    }

    private fun addToSelector(outbounds: JSONArray, tag: String, selector: String, auto: Boolean = false) {
        if (auto && tag.startsWith("route:RU:")) return
        val group = (0 until outbounds.length()).map(outbounds::getJSONObject).firstOrNull {
            if (auto) it.optString("tag").contains(selector, ignoreCase = true)
            else it.optString("tag") == selector
        } ?: return
        val members = group.getJSONArray("outbounds")
        if ((0 until members.length()).none { members.getString(it) == tag }) members.put(tag)
    }

    private data class Route(
        val tag: String, val stream: JSONObject, val host: String, val port: Int, val uuid: String,
    )

    private fun routes(root: JSONObject): List<Route> {
        val extension = root.optJSONObject("deytt") ?: return emptyList()
        if (!extension.has("xray_routes")) return emptyList()
        val payload = extension.getJSONObject("xray_routes")
        require(payload.optInt("version") == 1)
        val entries = payload.getJSONObject("routes")
        require(entries.length() <= 32)
        return entries.keys().asSequence().map { tag ->
            val entry = entries.getJSONObject(tag)
            require(routeTag.matches(tag) && entry.getString("protocol") == "vless")
            val host = entry.getString("host")
            val sni = entry.getString("sni")
            val port = entry.getInt("port")
            require(domain.matches(host) && domain.matches(sni) && port in 1..65535)
            val uuid = entry.getString("uuid")
            require(UUID.fromString(uuid).toString() == uuid)
            val transport = entry.getString("transport")
            val path = entry.optString("path")
            val fingerprint = entry.getString("fingerprint")
            require(fingerprint.matches(Regex("[a-z0-9_]{1,32}")))
            val alpn = entry.getJSONArray("alpn")
            require(alpn.length() in 1..3 && (0 until alpn.length()).all {
                alpn.getString(it) in setOf("h2", "http/1.1", "h3")
            })
            val tls = JSONObject().put("serverName", sni).put("alpn", alpn).put("fingerprint", fingerprint)
            val stream = JSONObject().put("network", transport).put("security", "tls").put("tlsSettings", tls)
            when (transport) {
                "xhttp", "ws", "httpupgrade" -> {
                    require(path.length in 1..256 && path.startsWith('/') && !path.startsWith("//") &&
                        path.none { it.isWhitespace() || it == '#' || it.isISOControl() })
                    val transportSettings = JSONObject().put("path", path).put("host", sni)
                    if (transport == "xhttp") {
                        val mode = entry.getString("mode")
                        require(mode in setOf("auto", "packet-up", "stream-up", "stream-one"))
                        transportSettings.put("mode", mode)
                    }
                    stream.put("${transport}Settings", transportSettings)
                }
                "tcp" -> Unit
                else -> throw IllegalArgumentException("неподдерживаемый транспорт xray")
            }
            Route(tag, stream, host, port, uuid)
        }.toList()
    }
}
