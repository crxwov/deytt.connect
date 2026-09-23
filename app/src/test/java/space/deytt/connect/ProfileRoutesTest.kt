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

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInternalOutboundSelection() {
        ProfileRoutes.select(config, "Авито прокси")
    }
}
