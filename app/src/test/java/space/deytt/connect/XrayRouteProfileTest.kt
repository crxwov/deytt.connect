package space.deytt.connect

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class XrayRouteProfileTest {
    private fun profile(sni: String = "de.deytt.space", transport: String = "xhttp") = """
        {
          "outbounds": [
            {"tag":"автоподбор","type":"urltest","outbounds":["route:DE:VLESS"]},
            {"tag":"route:DE:VLESS","type":"vless","tls":{"reality":{"enabled":true}}},
            {"tag":"route:RU:VLESS","type":"vless"},
            {"tag":"route:IT:VLESS","type":"vless","tls":{"reality":{"enabled":true}}}
          ],
          "route":{"rules":[]},
          "deytt":{"xray_routes":{"version":1,"routes":{
             "route:DE:VLESS":{"protocol":"vless","transport":"$transport","host":"ru.deytt.space",
                "port":18465,"sni":"$sni","uuid":"12345678-1234-1234-1234-123456789abc",
                "path":"/stream","mode":"auto","alpn":["h2"],"fingerprint":"firefox"},
             "route:RU:VLESS":{"protocol":"vless","transport":"xhttp","host":"ru.deytt.space",
                "port":443,"sni":"auto.deytt.space","uuid":"12345678-1234-1234-1234-123456789abc",
                "path":"/stream","mode":"auto","alpn":["h2"],"fingerprint":"firefox"}
          }}}
        }
    """.trimIndent()

    @Test fun `same tags use new transports without adding ru to auto`() {
        val runtime = requireNotNull(XrayRouteProfile.prepare(profile(transport = "ws")) {
            InetAddress.getByName("192.0.2.2")
        })
        val profile = JSONObject(runtime.profile).getJSONArray("outbounds")
        val auto = profile.getJSONObject(0).getJSONArray("outbounds")
        assertEquals("route:DE:VLESS", auto.getString(0))
        assertEquals(1, auto.length())
        assertEquals("socks", profile.getJSONObject(1).getString("type"))
        assertEquals("vless", profile.getJSONObject(3).getString("type"))
        assertNotEquals(profile.getJSONObject(1).getInt("server_port"), profile.getJSONObject(2).getInt("server_port"))
        val xray = JSONObject(runtime.xray)
        val exits = xray.getJSONArray("outbounds")
        assertEquals("ws", exits.getJSONObject(0).getJSONObject("streamSettings").getString("network"))
        assertEquals("xhttp", exits.getJSONObject(1).getJSONObject("streamSettings").getString("network"))
        assertEquals(2, xray.getJSONObject("routing").getJSONArray("rules").length())
        assertFalse(runtime.xray.contains("allowInsecure"))
    }

    @Test fun routeCatalogUsesTheActiveTransportUnderTheSameTag() {
        val routes = RouteCatalog.from(profile(), emptyList()).associateBy { it.id }
        assertEquals("xhttp", routes.getValue("route:DE:VLESS").transportLabel)
        assertEquals("xhttp", routes.getValue("route:RU:VLESS").transportLabel)
        assertEquals("reality", routes.getValue("route:IT:VLESS").transportLabel)
        val switched = RouteCatalog.from(profile(transport = "ws"), emptyList())
        assertEquals("ws", switched.single { it.id == "route:DE:VLESS" }.transportLabel)
    }

    @Test fun `untrusted sni is rejected before use and legacy profiles need no xray`() {
        assertTrue(runCatching { XrayRouteProfile.validate(profile(sni = "untrusted.example")) }.isFailure)
        assertNull(XrayRouteProfile.prepare("{}") { error("legacy profile must not resolve Xray hosts") })
    }
}
