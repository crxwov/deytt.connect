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
            {"type": "hysteria2", "tag": "nl • обход 1"},
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
    fun listsOnlyUsableRoutes() {
        val routes = ProfileRoutes.options(config)

        assertEquals(
            listOf("🇪🇺 автоподбор", "nl • обход 1", "vpn основной (amneziawg)"),
            routes.map(RouteOption::tag),
        )
        assertEquals("Автоподбор", routes.first().label)
    }

    @Test
    fun changesDefaultRouteAndRemoteDnsDetour() {
        val selected = ProfileRoutes.select(config, "nl • обход 1")
        val servers = JSONObject(selected).getJSONObject("dns").getJSONArray("servers")

        assertEquals("nl • обход 1", ProfileRoutes.selected(selected))
        assertEquals("nl • обход 1", servers.getJSONObject(0).getString("detour"))
        assertFalse(servers.getJSONObject(1).has("detour"))
    }
}
