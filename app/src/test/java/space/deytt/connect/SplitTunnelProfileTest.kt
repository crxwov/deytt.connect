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
        assertEquals(setOf("ru", "su", "рф", "xn--p1ai", "yandex.net", "vk.com", "st.ozone.ru"), domains)
        assertEquals(listOf("*.ru", "*.su", "*.рф", "*.xn--p1ai"), SplitTunnelProfile.bypassSites(config))
        assertFalse(domains.contains("ozone.ru"))
        assertFalse(domains.contains("discord.com"))
        assertFalse(domains.contains("restricted.example"))
        val dns = root.getJSONObject("dns")
        assertEquals(suffixes.toString(), dns.getJSONArray("rules").getJSONObject(0).getJSONArray("domain_suffix").toString())
        assertEquals("local-dns", dns.getJSONArray("rules").getJSONObject(0).getString("server"))
        assertTrue(dns.getBoolean("reverse_mapping"))
        assertEquals("cp.cloudflare.com", rules.getJSONObject(3).getJSONArray("domain").getString(0))
    }

    @Test fun applyEnforcesIpv6ProtectionMixedStackAndIpv4OnlyDns() {
        val root = JSONObject(SplitTunnelProfile.apply(config))
        val inbounds = root.getJSONArray("inbounds")
        val tun = inbounds.getJSONObject(0)
        assertEquals("mixed", tun.getString("stack"))
        assertTrue(tun.getBoolean("strict_route"))
        val addresses = (0 until tun.getJSONArray("address").length()).map { tun.getJSONArray("address").getString(it) }
        assertTrue(addresses.contains("172.19.0.1/30"))
        assertTrue(addresses.contains("fdfe:dcba:9876::1/126"))

        assertEquals("ipv4_only", root.getJSONObject("dns").getString("strategy"))

        val rules = root.getJSONObject("route").getJSONArray("rules")
        var foundReject = false
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            if (rule.optInt("ip_version") == 6 && rule.optString("action") == "reject") {
                foundReject = true
                break
            }
        }
        assertTrue(foundReject)
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

    @Test fun openFluxCapturesAndRejectsIpv6AndUsesTcpDnsInsideCarrier() {
        val root = JSONObject(SplitTunnelProfile.forOpenFlux(config, 23456, "random-user", "random-password"))
        assertEquals(SplitTunnelProfile.OPENFLUX_OUTBOUND, root.getJSONObject("route").getString("final"))
        val rules = root.getJSONObject("route").getJSONArray("rules")
        assertEquals(6, rules.getJSONObject(0).getInt("ip_version"))
        assertEquals("reject", rules.getJSONObject(0).getString("action"))
        assertEquals("sniff", rules.getJSONObject(1).getString("action"))
        assertEquals("hijack-dns", rules.getJSONObject(2).getString("action"))
        val inbound = root.getJSONArray("inbounds").getJSONObject(0)
        assertTrue(inbound.getBoolean("auto_route"))
        assertTrue(inbound.getJSONArray("address").toString().contains("fdfe:"))
        val socks = root.getJSONArray("outbounds").getJSONObject(1)
        assertEquals("tcp", socks.getString("network"))
        assertEquals("127.0.0.1", socks.getString("server"))
        assertEquals("random-password", socks.getString("password"))
        val dns = root.getJSONObject("dns")
        assertEquals("ipv4_only", dns.getString("strategy"))
        val remote = dns.getJSONArray("servers").getJSONObject(1)
        assertEquals("https", remote.getString("type"))
        assertEquals(SplitTunnelProfile.OPENFLUX_OUTBOUND, remote.getString("detour"))
        assertEquals("local-dns", dns.getJSONArray("rules").getJSONObject(0).getString("server"))
        assertFalse(root.has("endpoints"))
        val runtime = JSONObject(RuntimeProfile.withPrivateCacheFile(root.toString(), "/private/test/cache.db"))
        assertEquals("remote-dns", runtime.getJSONObject("route").getString("default_domain_resolver"))
        assertEquals("local-dns", runtime.getJSONArray("outbounds").getJSONObject(0).getString("domain_resolver"))
        ProfileValidator.validate(root.toString())
    }

    @Test fun applyIncludesCustomDomainsAndPackagesAndPreservesBaseRules() {
        val customDomains = listOf("custom.example.com", "*.customwild.org")
        val customPackages = listOf("org.telegram.messenger", "com.google.android.youtube")
        val root = JSONObject(SplitTunnelProfile.apply(config, customDomains, customPackages))

        val routeRules = root.getJSONObject("route").getJSONArray("rules")
        val suffixes = (0 until routeRules.length()).flatMap { ruleIndex ->
            routeRules.optJSONObject(ruleIndex)?.optJSONArray("domain_suffix")?.let { values ->
                (0 until values.length()).map(values::getString)
            }.orEmpty()
        }.toSet()
        assertTrue(suffixes.contains("ru"))
        assertTrue(suffixes.contains("xn--p1ai"))
        assertTrue(suffixes.contains("customwild.org"))

        val exact = (0 until routeRules.length()).flatMap { ruleIndex ->
            routeRules.optJSONObject(ruleIndex)?.optJSONArray("domain")?.let { values ->
                (0 until values.length()).map(values::getString)
            }.orEmpty()
        }.toSet()
        assertTrue(exact.contains("cp.cloudflare.com"))
        assertTrue(exact.contains("custom.example.com"))

        val packageRule = (0 until routeRules.length()).map(routeRules::getJSONObject)
            .first { it.has("package_name") }.getJSONArray("package_name")
        val packages = (0 until packageRule.length()).map(packageRule::getString).toSet()
        assertTrue(packages.contains("org.telegram.messenger"))
        assertTrue(packages.contains("com.google.android.youtube"))

        val tunInbound = root.getJSONArray("inbounds").getJSONObject(0)
        val tunExcluded = tunInbound.getJSONArray("exclude_package")
        val tunExcludedSet = (0 until tunExcluded.length()).map(tunExcluded::getString).toSet()
        assertTrue(tunExcludedSet.contains("org.telegram.messenger"))
        assertTrue(tunExcludedSet.contains("com.google.android.youtube"))

        ProfileValidator.validate(root.toString())
    }

    @Test fun userSplitTunnelStoreNormalizesDomainsCorrectly() {
        assertEquals("example.com", UserSplitTunnelStore.normalizeDomain("https://example.com/path?arg=1"))
        assertEquals("example.com", UserSplitTunnelStore.normalizeDomain("http://Example.COM:8080/"))
        assertEquals("*.example.com", UserSplitTunnelStore.normalizeDomain("*.EXAMPLE.com"))
        assertEquals("*.example.com", UserSplitTunnelStore.normalizeDomain(".example.com"))
        assertNull(UserSplitTunnelStore.normalizeDomain("not a domain"))
        assertNull(UserSplitTunnelStore.normalizeDomain(""))
        assertNull(UserSplitTunnelStore.normalizeDomain("http://"))

        assertTrue(UserSplitTunnelStore.isValidPackage("org.telegram.messenger"))
        assertTrue(UserSplitTunnelStore.isValidPackage("com.example.app_1"))
        assertFalse(UserSplitTunnelStore.isValidPackage("invalid package"))
        assertFalse(UserSplitTunnelStore.isValidPackage("1com.example"))
    }

    @Test fun vpnOnlyRoutesSelectedRulesThroughProxyAndEverythingElseDirect() {
        val root = JSONObject(SplitTunnelProfile.apply(
            config, listOf("custom.example.com", "*.customwild.org", "yandex.net"), listOf("org.telegram.messenger"),
            UserSplitTunnelStore.MODE_VPN_ONLY
        ))
        val route = root.getJSONObject("route")
        assertEquals("direct", route.getString("final"))
        val rules = route.getJSONArray("rules")
        assertTrue((0 until rules.length()).any { index ->
            val rule = rules.getJSONObject(index)
            rule.optString("outbound") == "selected" &&
                rule.optJSONArray("domain")?.toString()?.contains("custom.example.com") == true
        })
        assertTrue((0 until rules.length()).any { index ->
            val rule = rules.getJSONObject(index)
            rule.optString("outbound") == "selected" &&
                rule.optJSONArray("package_name")?.toString()?.contains("org.telegram.messenger") == true
        })
        val dns = root.getJSONObject("dns").getJSONArray("rules")
        assertTrue((0 until dns.length()).any { index ->
            val rule = dns.getJSONObject(index)
            rule.optString("server") == "remote-dns" &&
                rule.optJSONArray("domain")?.toString()?.contains("custom.example.com") == true
        })
        val tun = root.getJSONArray("inbounds").getJSONObject(0)
        assertFalse(tun.has("exclude_package"))
        val baseDirectIndex = (0 until rules.length()).first { index ->
            val rule = rules.getJSONObject(index)
            rule.optString("outbound") == "direct" &&
                rule.optJSONArray("domain_suffix")?.toString()?.contains("yandex.net") == true
        }
        val selectedIndex = (0 until rules.length()).first { index ->
            val rule = rules.getJSONObject(index)
            rule.optString("outbound") == "selected" &&
                rule.optJSONArray("domain")?.toString()?.contains("yandex.net") == true
        }
        assertTrue("Subscription bypass must keep precedence over custom rules", baseDirectIndex < selectedIndex)
        ProfileValidator.validate(root.toString())
    }

    @Test fun vpnOnlyAwgKeepsCustomRulesOnAwgOutbound() {
        val root = JSONObject(SplitTunnelProfile.forAwg(
            config, 23456, "random-user", "random-password",
            listOf("custom.example.com"), listOf("org.telegram.messenger"),
            UserSplitTunnelStore.MODE_VPN_ONLY
        ))
        val route = root.getJSONObject("route")
        assertEquals("direct", route.getString("final"))
        val rules = route.getJSONArray("rules")
        assertTrue((0 until rules.length()).any { rules.getJSONObject(it).optString("outbound") == SplitTunnelProfile.AWG_OUTBOUND })
        assertFalse(root.getJSONArray("inbounds").getJSONObject(0).has("exclude_package"))
        ProfileValidator.validate(root.toString())
    }

    @Test fun vpnOnlyOpenFluxKeepsSelectedRulesInsideCarrier() {
        val root = JSONObject(SplitTunnelProfile.forOpenFlux(
            config, 23456, "random-user", "random-password",
            listOf("custom.example.com"), listOf("org.telegram.messenger"),
            UserSplitTunnelStore.MODE_VPN_ONLY
        ))
        val route = root.getJSONObject("route")
        assertEquals("direct", route.getString("final"))
        val rules = route.getJSONArray("rules")
        assertTrue((0 until rules.length()).any { rules.getJSONObject(it).optString("outbound") == SplitTunnelProfile.OPENFLUX_OUTBOUND })
        assertFalse(root.getJSONArray("inbounds").getJSONObject(0).has("exclude_package"))
        assertEquals(SplitTunnelProfile.OPENFLUX_OUTBOUND,
            root.getJSONObject("dns").getJSONArray("servers").getJSONObject(1).getString("detour"))
        ProfileValidator.validate(root.toString())
    }
}
