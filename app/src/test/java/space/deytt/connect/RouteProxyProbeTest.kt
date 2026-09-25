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
}
