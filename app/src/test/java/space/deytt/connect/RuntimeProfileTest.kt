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
        assertEquals("mixed", tun.getString("stack"))
        assertTrue(tun.getBoolean("strict_route"))
        val addresses = (0 until tun.getJSONArray("address").length()).map { tun.getJSONArray("address").getString(it) }
        assertTrue(addresses.contains("172.19.0.1/30"))
        assertTrue(addresses.contains("fdfe:dcba:9876::1/126"))
        assertEquals("ipv4_only", root.getJSONObject("dns").getString("strategy"))
        val rules = route.getJSONArray("rules")
        assertEquals(6, rules.getJSONObject(0).getInt("ip_version"))
        assertEquals("reject", rules.getJSONObject(0).getString("action"))
        assertEquals("local", localDns.getString("type"))
        assertFalse(localDns.has("server"))
        assertFalse(localDns.has("server_port"))
        assertFalse(localDns.has("detour"))
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

    @Test
    fun sanitizesHysteriaConflictingServerPortsWhenServerPortPresent() {
        val result = RuntimeProfile.withPrivateCacheFile(
            """{
                "inbounds":[{"type":"tun"}],
                "dns":{"servers":[{"type":"local","tag":"local-dns"}]},
                "route":{"final":"route:NL:HYSTERIA2"},
                "outbounds":[{
                    "type":"hysteria2",
                    "tag":"route:NL:HYSTERIA2",
                    "server":"example.com",
                    "server_port":8443,
                    "server_ports":["8443", "20000:50000"],
                    "hop_interval":"30s"
                }]
            }""",
            "/data/user/0/space.deytt.connect/no_backup/sing-box/cache.db",
        )
        val outbound = JSONObject(result).getJSONArray("outbounds").getJSONObject(0)
        assertEquals(8443, outbound.getInt("server_port"))
        assertFalse(outbound.has("server_ports"))
        assertFalse(outbound.has("hop_interval"))
    }

    @Test
    fun normalizesHysteriaPortRangesWhenOnlyServerPortsPresent() {
        val result = RuntimeProfile.withPrivateCacheFile(
            """{
                "inbounds":[{"type":"tun"}],
                "dns":{"servers":[{"type":"local","tag":"local-dns"}]},
                "route":{"final":"route:NL:HYSTERIA2"},
                "outbounds":[{
                    "type":"hysteria2",
                    "tag":"route:NL:HYSTERIA2",
                    "server":"example.com",
                    "server_ports":["8443", "20000-50000"],
                    "hop_interval":"30s"
                }]
            }""",
            "/data/user/0/space.deytt.connect/no_backup/sing-box/cache.db",
        )
        val outbound = JSONObject(result).getJSONArray("outbounds").getJSONObject(0)
        assertFalse(outbound.has("server_port"))
        val ports = outbound.getJSONArray("server_ports")
        assertEquals(2, ports.length())
        assertEquals("8443:8443", ports.getString(0))
        assertEquals("20000:50000", ports.getString(1))
    }
}
