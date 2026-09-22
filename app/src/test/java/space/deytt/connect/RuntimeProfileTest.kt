package space.deytt.connect

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeProfileTest {
    @Test
    fun addsAndroidCacheAndRepairsDnsBootstrapWithoutChangingSelectedRoute() {
        val result = RuntimeProfile.withPrivateCacheFile(
            """{
                "inbounds":[{"type":"tun","address":["172.19.0.1/30"]}],
                "dns":{"servers":[
                    {"type":"https","tag":"remote-dns","server":"1.1.1.1"},
                    {"type":"local","tag":"local-dns","detour":"direct"}
                ]},
                "route":{"final":"🇪🇺 автоподбор"},
                "outbounds":[]
            }""",
            "/data/user/0/space.deytt.connect/no_backup/sing-box/cache.db",
        )
        val root = JSONObject(result)
        val cache = root.getJSONObject("experimental").getJSONObject("cache_file")
        val route = root.getJSONObject("route")
        val tun = root.getJSONArray("inbounds").getJSONObject(0)
        val localDns = root.getJSONObject("dns").getJSONArray("servers").getJSONObject(1)

        assertEquals("🇪🇺 автоподбор", route.getString("final"))
        assertEquals("local-dns", route.getString("default_domain_resolver"))
        assertEquals("hijack", tun.getString("dns_mode"))
        assertEquals("udp", localDns.getString("type"))
        assertEquals("1.1.1.1", localDns.getString("server"))
        assertEquals(53, localDns.getInt("server_port"))
        assertEquals("direct", localDns.getString("detour"))
        assertEquals("/data/user/0/space.deytt.connect/no_backup/sing-box/cache.db", cache.getString("path"))
        assertTrue(cache.getBoolean("enabled"))
        assertFalse(cache.getBoolean("store_fakeip"))
        assertFalse(cache.getBoolean("store_dns"))
    }

    @Test
    fun addsBootstrapResolverWhenOlderProfileHasNone() {
        val result = RuntimeProfile.withPrivateCacheFile(
            """{
                "inbounds":[{"type":"tun"}],
                "dns":{"servers":[{"type":"https","tag":"remote-dns","server":"1.1.1.1"}]},
                "route":{"final":"proxy"}
            }""",
            "/data/user/0/space.deytt.connect/no_backup/sing-box/cache.db",
        )
        val servers = JSONObject(result).getJSONObject("dns").getJSONArray("servers")

        assertEquals(2, servers.length())
        assertEquals("local-dns", servers.getJSONObject(1).getString("tag"))
    }
}
