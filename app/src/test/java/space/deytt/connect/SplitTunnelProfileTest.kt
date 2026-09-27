package space.deytt.connect

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SplitTunnelProfileTest {
    private val config = """{
      "inbounds":[{"type":"tun","address":["172.19.0.1/30"]}],
      "outbounds":[{"type":"direct","tag":"direct"},{"type":"vless","tag":"selected"}],
      "dns":{"servers":[{"type":"local","tag":"local-dns"},{"type":"https","tag":"remote-dns","detour":"selected"}],"final":"remote-dns"},
      "route":{"final":"selected","rules":[
        {"action":"sniff"},{"protocol":"dns","action":"hijack-dns"},
        {"ip_is_private":true,"outbound":"direct"},
        {"domain_suffix":["yandex.net","vk.com","st.ozone.ru"],"outbound":"direct"},
        {"domain_suffix":["restricted.example"],"port":443,"outbound":"direct"},
        {"domain_suffix":["discord.com"],"outbound":"selected"}
      ]}
    }"""

    @Test fun bypassDnsMatchesTrafficAndPreservesSelectedTransport() {
        val root = JSONObject(SplitTunnelProfile.apply(config))
        assertEquals("selected", root.getJSONObject("route").getString("final"))
        val rules = root.getJSONObject("route").getJSONArray("rules")
        assertEquals("sniff", rules.getJSONObject(0).getString("action"))
        assertEquals("hijack-dns", rules.getJSONObject(1).getString("action"))
        val suffixes = rules.getJSONObject(2).getJSONArray("domain_suffix")
        val domains = (0 until suffixes.length()).map(suffixes::getString).toSet()
        assertEquals(setOf("ru", "xn--p1ai", "yandex.net", "vk.com", "st.ozone.ru"), domains)
        assertFalse(domains.contains("ozone.ru"))
        assertFalse(domains.contains("discord.com"))
        assertFalse(domains.contains("restricted.example"))
        val dns = root.getJSONObject("dns")
        assertEquals(suffixes.toString(), dns.getJSONArray("rules").getJSONObject(0).getJSONArray("domain_suffix").toString())
        assertEquals("local-dns", dns.getJSONArray("rules").getJSONObject(0).getString("server"))
        assertTrue(dns.getBoolean("reverse_mapping"))
        assertEquals("cp.cloudflare.com", rules.getJSONObject(3).getJSONArray("domain").getString(0))
    }

    @Test fun awgUsesSameExceptionsAndNoOtherVpnTransport() {
        val root = JSONObject(SplitTunnelProfile.forAwg(config, 23456, "random-user", "random-password"))
        val outbounds = root.getJSONArray("outbounds")
        assertEquals(2, outbounds.length())
        val socks = outbounds.getJSONObject(1)
        assertEquals("socks", socks.getString("type"))
        assertEquals("127.0.0.1", socks.getString("server"))
        assertEquals(23456, socks.getInt("server_port"))
        assertEquals("random-password", socks.getString("password"))
        assertEquals(SplitTunnelProfile.AWG_OUTBOUND, root.getJSONObject("route").getString("final"))
        val rules = root.getJSONObject("route").getJSONArray("rules")
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            if (rule.has("outbound")) assertEquals("direct", rule.getString("outbound"))
        }
        assertEquals(SplitTunnelProfile.AWG_OUTBOUND,
            root.getJSONObject("dns").getJSONArray("servers").getJSONObject(1).getString("detour"))
        ProfileValidator.validate(root.toString())
    }
}
