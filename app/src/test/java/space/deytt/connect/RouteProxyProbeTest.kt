package space.deytt.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.json.JSONObject

class RouteProxyProbeTest {
    private val profile = """
        {
          "inbounds": [{"type":"tun","tag":"tun-in","address":["172.19.0.1/30"]}],
          "outbounds": [
            {"type":"selector","tag":"route:DE","outbounds":["route:DE:VLESS"]},
            {"type":"vless","tag":"route:DE:VLESS","server":"de.example.com","server_port":443},
            {"type":"direct","tag":"direct"}
          ],
          "dns": {"servers":[{"tag":"local-dns"}]},
          "route": {"final":"direct","rules":[]}
        }
    """.trimIndent()

    @Test
    fun createsLoopbackOnlyProxyAndRemovesTunBeforeProbe() {
        val result = JSONObject(
            RouteProxyProbe.configuration(profile, "route:DE:VLESS", 43871, "probe-user", "ephemeral-password"),
        )
        val inbounds = result.getJSONArray("inbounds")
        val inbound = inbounds.getJSONObject(0)

        assertEquals(1, inbounds.length())
        assertEquals("mixed", inbound.getString("type"))
        assertEquals("127.0.0.1", inbound.getString("listen"))
        assertEquals(43871, inbound.getInt("listen_port"))
        assertEquals("probe-user", inbound.getJSONArray("users").getJSONObject(0).getString("username"))
        assertEquals("ephemeral-password", inbound.getJSONArray("users").getJSONObject(0).getString("password"))
        assertFalse((0 until inbounds.length()).any { inbounds.getJSONObject(it).getString("type") == "tun" })
        assertEquals("route:DE:VLESS", result.getJSONObject("route").getString("final"))
    }

    @Test
    fun parsesHttpStatusLineForTheProxyAndCanaryResponses() {
        assertEquals(200, RouteProxyProbe.parseHttpStatusCode("HTTP/1.1 200 Connection established"))
        assertEquals(204, RouteProxyProbe.parseHttpStatusCode("HTTP/1.1 204 No Content"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownRouteInsteadOfFallingBackToDirect() {
        RouteProxyProbe.configuration(profile, "direct", 43871, "probe-user", "ephemeral-password")
    }
    @Test
    fun batchPinsEveryLoopbackInboundBeforeExistingBypassRules() {
        val source = JSONObject(profile)
        source.getJSONArray("outbounds").put(JSONObject()
            .put("type", "trojan").put("tag", "route:DE:TROJAN").put("server", "test.invalid").put("server_port", 443))
        source.getJSONObject("route").getJSONArray("rules").put(JSONObject().put("outbound", "direct"))
        val result = JSONObject(RouteProxyProbe.batchConfiguration(source.toString(), linkedMapOf(
            "route:DE:VLESS" to RouteProbeSession(42001, "a", "one"),
            "route:DE:TROJAN" to RouteProbeSession(42002, "b", "two"),
        )))
        val inbounds = result.getJSONArray("inbounds")
        val rules = result.getJSONObject("route").getJSONArray("rules")
        assertEquals(2, inbounds.length())
        assertEquals(3, rules.length())
        listOf("route:DE:VLESS", "route:DE:TROJAN").forEachIndexed { index, tag ->
            assertEquals("127.0.0.1", inbounds.getJSONObject(index).getString("listen"))
            assertEquals(inbounds.getJSONObject(index).getString("tag"), rules.getJSONObject(index).getJSONArray("inbound").getString(0))
            assertEquals(tag, rules.getJSONObject(index).getString("outbound"))
        }
        assertEquals("direct", rules.getJSONObject(2).getString("outbound"))
        assertEquals("tun", source.getJSONArray("inbounds").getJSONObject(0).getString("type"))
    }

    @Test
    fun batchUsesDefaultDirectDnsDialerInsteadOfUnsupportedDirectDetour() {
        val source = JSONObject(profile)
        source.getJSONObject("dns").getJSONArray("servers").put(JSONObject()
            .put("tag", "remote-dns").put("detour", "route:DE:VLESS"))
        val result = JSONObject(RouteProxyProbe.batchConfiguration(source.toString(), mapOf(
            "route:DE:VLESS" to RouteProbeSession(42001, "a", "one"),
        )))
        val servers = result.getJSONObject("dns").getJSONArray("servers")
        repeat(servers.length()) { assertFalse(servers.getJSONObject(it).has("detour")) }
        assertEquals("route:DE:VLESS", source.getJSONObject("dns").getJSONArray("servers").getJSONObject(1).getString("detour"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun batchRejectsDuplicatePorts() {
        RouteProxyProbe.batchConfiguration(profile, linkedMapOf(
            "route:DE:VLESS" to RouteProbeSession(42001, "a", "one"),
            "route:DE" to RouteProbeSession(42001, "b", "two"),
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun batchRejectsInternalRoute() {
        RouteProxyProbe.batchConfiguration(profile, mapOf("direct" to RouteProbeSession(42001, "a", "one")))
    }
}
