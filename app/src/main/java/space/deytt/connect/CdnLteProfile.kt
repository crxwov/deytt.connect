package space.deytt.connect

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.ServerSocket
import java.util.UUID

internal object CdnLteProfile {
    const val ROUTE_TAG = "route:DE:LTE"
    private const val HOST = "front.deytt.space"
    private const val PATH = "/api/v2/cdn-lte-stream/"
    private const val SOCKS_TAG = ROUTE_TAG

    fun attach(profile: String, encodedMetadata: String?): String {
        if (encodedMetadata.isNullOrBlank()) return profile
        require(encodedMetadata.length <= 8192) { "Метаданные LTE-маршрута слишком велики" }
        val metadata = JSONObject(String(Base64.decode(encodedMetadata, Base64.DEFAULT), Charsets.UTF_8))
        require(metadata.optInt("version") == 1) { "Неизвестная версия LTE-маршрута" }
        val outbound = metadata.getJSONObject("outbound")
        validateOutbound(outbound)

        val root = JSONObject(profile)
        val outbounds = root.getJSONArray("outbounds")
        require((0 until outbounds.length()).none {
            outbounds.optJSONObject(it)?.optString("tag") == ROUTE_TAG
        }) { "LTE-маршрут уже присутствует в профиле" }
        val extension = root.optJSONObject("deytt") ?: JSONObject()
        require(!extension.has("cdn_lte")) { "LTE-метаданные уже присутствуют в профиле" }
        extension.put("cdn_lte", outbound)
        root.put("deytt", extension)

        val sock = JSONObject()
            .put("type", "socks")
            .put("tag", SOCKS_TAG)
            .put("server", "127.0.0.1")
            .put("server_port", 1)
        val updated = JSONArray()
        var inserted = false
        for (index in 0 until outbounds.length()) {
            val item = outbounds.getJSONObject(index)
            updated.put(item)
            if (!inserted && item.optString("tag").contains("автоподбор", ignoreCase = true)) {
                require(item.optString("type") in setOf("urltest", "selector")) {
                    "Автоподбор в подписке имеет неверный формат"
                }
                updated.put(sock)
                inserted = true
            }
        }
        require(inserted) { "В подписке отсутствует автоподбор" }
        root.put("outbounds", updated)
        return root.toString()
    }

    fun hasValidMetadata(profile: String): Boolean = runCatching { readOutbound(profile) }.isSuccess

    fun allocateSocksPort(): Int = ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1")).use {
        it.localPort
    }

    fun buildXrayConfig(profile: String, socksPort: Int): String {
        require(socksPort in 1..65535)
        val outbound = readOutbound(profile)
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(
                JSONObject()
                    .put("listen", "127.0.0.1")
                    .put("port", socksPort)
                    .put("protocol", "socks")
                    .put("settings", JSONObject().put("auth", "noauth").put("udp", false)),
            ))
            .put("outbounds", JSONArray().put(outbound))
            .toString()
    }

    fun useSocksPort(profile: String, socksPort: Int): String {
        require(socksPort in 1..65535)
        val root = JSONObject(profile)
        val outbounds = root.getJSONArray("outbounds")
        val socks = (0 until outbounds.length())
            .mapNotNull(outbounds::optJSONObject)
            .firstOrNull { it.optString("tag") == SOCKS_TAG }
            ?: throw IllegalArgumentException("В профиле отсутствует локальный LTE-маршрут")
        require(socks.optString("type") == "socks" && socks.optString("server") == "127.0.0.1")
        socks.put("server_port", socksPort)
        return root.toString()
    }

    private fun readOutbound(profile: String): JSONObject {
        val outbound = JSONObject(profile).optJSONObject("deytt")
            ?.optJSONObject("cdn_lte")
            ?: throw IllegalArgumentException("Сервер не выдал LTE-маршрут для этой подписки")
        validateOutbound(outbound)
        return JSONObject(outbound.toString())
    }

    private fun validateOutbound(outbound: JSONObject) {
        require(outbound.optString("tag") == ROUTE_TAG && outbound.optString("protocol") == "vless")
        val server = outbound.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
        require(server.optString("address") == HOST && server.optInt("port") == 443)
        val users = server.getJSONArray("users")
        require(users.length() == 1)
        val user = users.getJSONObject(0)
        val id = user.optString("id")
        UUID.fromString(id)
        require(user.optString("encryption") == "none")
        val stream = outbound.getJSONObject("streamSettings")
        require(stream.optString("network") == "xhttp" && stream.optString("security") == "tls")
        val tls = stream.getJSONObject("tlsSettings")
        require(tls.optString("serverName") == HOST && tls.optString("fingerprint") == "chrome")
        val alpn = tls.getJSONArray("alpn")
        require((0 until alpn.length()).map { alpn.optString(it) }.containsAll(listOf("h2", "http/1.1")))
        val xhttp = stream.getJSONObject("xhttpSettings")
        require(xhttp.optString("path") == PATH && xhttp.optString("mode") == "packet-up")
        val extra = xhttp.getJSONObject("extra")
        require(extra.optString("uplinkHTTPMethod") == "GET")
        require(extra.optString("uplinkDataPlacement") == "header")
        require(extra.optString("uplinkDataKey") == "data")
        require(extra.optInt("scMaxEachPostBytes") == 4096)
        require(extra.optInt("scMinPostsIntervalMs") == 30)
        require(extra.optInt("serverMaxHeaderBytes") == 32768)
        require(extra.optBoolean("noSSEHeader"))
    }
}
