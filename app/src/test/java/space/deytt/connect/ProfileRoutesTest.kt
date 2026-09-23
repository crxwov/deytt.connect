package space.deytt.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.json.JSONObject

class ProfileRoutesTest {
    private val config = """
        {
          "outbounds": [
            {"type": "urltest", "tag": "🇪🇺 автоподбор"},
            {"type": "urltest", "tag": "route:DE", "outbounds": ["de • обход 1"]},
            {"type": "vless", "tag": "route:DE:VLESS"},
            {"type": "trojan", "tag": "route:DE:TROJAN"},
            {"type": "hysteria2", "tag": "route:DE:HYSTERIA2"},
            {"type": "hysteria2", "tag": "nl • обход 1"},
            {"type": "hysteria2", "tag": "Авито прокси"},
            {"type": "direct", "tag": "direct"}
          ],
          "endpoints": [{"type": "wireguard", "tag": "vpn основной (amneziawg)"}],
          "dns": {"servers": [
            {"tag": "remote-dns", "detour": "🇪🇺 автоподбор"},
            {"tag": "local-dns"}
          ]},
          "route": {"final": "🇪🇺 автоподбор"}
        }
    """.trimIndent()

    @Test
    fun listsOnlyExplicitUserRoutes() {
        val routes = ProfileRoutes.options(config)

        assertEquals(
            listOf("🇪🇺 автоподбор", "route:DE"),
            routes.map(RouteOption::tag),
        )
        assertEquals("Автоподбор", routes.first().label)
        assertEquals("Германия", routes[1].label)
        assertEquals("🇩🇪", routes[1].flag)
    }

    @Test
    fun changesDefaultRouteAndRemoteDnsDetour() {
        val selected = ProfileRoutes.select(config, "route:DE")
        val servers = JSONObject(selected).getJSONObject("dns").getJSONArray("servers")

        assertEquals("route:DE", ProfileRoutes.selected(selected))
        assertEquals("route:DE", servers.getJSONObject(0).getString("detour"))
        assertFalse(servers.getJSONObject(1).has("detour"))
    }

    @Test
    fun exposesEveryFirstPartyProtocolWithoutInternalNames() {
        val routes = RouteCatalog.from(config, awg15 = true, awg31 = true)

        assertEquals(
            listOf(
                RouteProtocol.AUTO,
                RouteProtocol.VLESS,
                RouteProtocol.TROJAN,
                RouteProtocol.HYSTERIA2,
                RouteProtocol.AWG15,
                RouteProtocol.AWG31,
            ),
            routes.map(DeyttRoute::protocol),
        )
        assertFalse(routes.any { it.id.contains("Авито") || it.id.contains("vpn основной") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInternalOutboundSelection() {
        ProfileRoutes.select(config, "Авито прокси")
    }
}
