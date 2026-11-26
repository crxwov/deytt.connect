package space.deytt.connect

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class CdnLteProfileTest {
    private fun metadata(path: String): String {
        val outbound = JSONObject()
            .put("tag", "route:DE:LTE")
            .put("protocol", "vless")
            .put(
                "settings",
                JSONObject().put(
                    "vnext",
                    JSONArray().put(
                        JSONObject()
                            .put("address", "front.deytt.space")
                            .put("port", 443)
                            .put(
                                "users",
                                JSONArray().put(
                                    JSONObject()
                                        .put("id", "12345678-1234-1234-1234-123456789abc")
                                        .put("encryption", "none"),
                                ),
                            ),
                    ),
                ),
            )
            .put(
                "streamSettings",
                JSONObject()
                    .put("network", "xhttp")
                    .put("security", "tls")
                    .put(
                        "tlsSettings",
                        JSONObject()
                            .put("serverName", "front.deytt.space")
                            .put("alpn", JSONArray().put("h2").put("http/1.1"))
                            .put("fingerprint", "firefox"),
                    )
                    .put(
                        "xhttpSettings",
                        JSONObject()
                            .put("path", path)
                            .put("mode", "packet-up")
                            .put(
                                "extra",
                                JSONObject()
                                    .put("uplinkHTTPMethod", "GET")
                                    .put("uplinkDataPlacement", "header")
                                    .put("uplinkDataKey", "data")
                                    .put("noSSEHeader", true)
                                    .put("scMaxEachPostBytes", 4096)
                                    .put("scMinPostsIntervalMs", 30)
                                    .put("serverMaxHeaderBytes", 32768),
                            ),
                    ),
            )
        return LocalBase64.encode(
            JSONObject().put("version", 1).put("outbound", outbound).toString().toByteArray(),
        )
    }

    private fun profile(): String = JSONObject()
        .put(
            "outbounds",
            JSONArray().put(JSONObject().put("type", "selector").put("tag", "автоподбор")),
        )
        .toString()

    @Test fun acceptsRotatedServerPath() {
        val updated = CdnLteProfile.attach(
            profile(),
            metadata("/assets/e3a1c9f047bc6d82a5710fb4c3928e65/stream/"),
        )
        assertTrue(JSONObject(updated).optJSONObject("deytt")?.has("cdn_lte") == true)
    }

    @Test fun stillAcceptsLegacyPath() {
        CdnLteProfile.attach(profile(), metadata("/api/v2/cdn-lte-stream/"))
    }

    @Test fun rejectsMalformedPath() {
        listOf("", "/", "assets/stream/", "/x".repeat(129)).forEach { path ->
            assertTrue(
                "path must be rejected: $path",
                runCatching { CdnLteProfile.attach(profile(), metadata(path)) }.isFailure,
            )
        }
    }

    @Test fun rejectsMetadataTwice() {
        val withLte = CdnLteProfile.attach(profile(), metadata("/assets/abc/stream/"))
        assertTrue(runCatching { CdnLteProfile.attach(withLte, metadata("/assets/abc/stream/")) }.isFailure)
    }
}
