package space.deytt.connect

import org.json.JSONArray
import org.json.JSONObject

/** Uses the subscription's bypass list for both DNS and every tunnel transport. */
object SplitTunnelProfile {
    const val AWG_OUTBOUND = "deytt-awg"
    const val OPENFLUX_OUTBOUND = "deytt-openflux"
    private val domainKeys = listOf("domain", "domain_suffix", "domain_regex", "domain_keyword")

    fun bypassSites(config: String): List<String> {
        val rules = JSONObject(apply(config)).getJSONObject("dns").getJSONArray("rules")
        val suffixes = rules.getJSONObject(0).getJSONArray("domain_suffix")
        val exact = rules.getJSONObject(1).getJSONArray("domain")
        return (0 until suffixes.length()).map { "*.${suffixes.getString(it)}" } +
            (0 until exact.length()).map(exact::getString)
    }

    fun apply(
        config: String,
        customDomains: Collection<String> = emptyList(),
        customPackages: Collection<String> = emptyList(),
        mode: String = UserSplitTunnelStore.MODE_BYPASS,
        proxyOutbound: String? = null,
    ): String {
        val root = JSONObject(config)
        val route = root.getJSONObject("route")
        val rules = route.getJSONArray("rules")
        val vpnOnly = mode == UserSplitTunnelStore.MODE_VPN_ONLY
        val selectedOutbound = proxyOutbound ?: route.optString("final")
        // These are the additional explicit rules in the provider's Happ profile.
        val suffixes = linkedSetOf("ru", "xn--p1ai")
        val exact = linkedSetOf("cp.cloudflare.com")
        for (index in 0 until rules.length()) {
            val rule = rules.getJSONObject(index)
            if (rule.optString("outbound") != "direct") continue
            // Only copy plain domain rules; adding a DNS match must not widen a
            // rule constrained by an IP, port, application, or other condition.
            if (rule.keys().asSequence().any { it !in domainKeys + listOf("outbound", "action") }) continue
            rule.optJSONArray("domain_suffix")?.let { values ->
                for (i in 0 until values.length()) suffixes += values.getString(i).removePrefix(".")
            }
            rule.optJSONArray("domain")?.let { values ->
                for (i in 0 until values.length()) exact += values.getString(i)
            }
        }
        val customSuffixes = linkedSetOf<String>()
        val customExact = linkedSetOf<String>()
        for (item in customDomains) {
            val clean = item.trim().lowercase(java.util.Locale.ROOT)
            if (clean.isBlank()) continue
            if (clean.startsWith("*.")) {
                customSuffixes += clean.removePrefix("*.").removePrefix(".")
            } else {
                customExact += clean
            }
        }
        val hasIpv6Reject = (0 until rules.length()).any { index ->
            val rule = rules.optJSONObject(index)
            rule != null && rule.optInt("ip_version") == 6 && rule.optString("action") == "reject"
        }
        val added = JSONArray()
            .put(JSONObject().put("domain_suffix", JSONArray(suffixes)).put("outbound", "direct"))
            .put(JSONObject().put("domain", JSONArray(exact)).put("outbound", "direct"))
        val customRules = JSONArray()
        val customOutbound = if (vpnOnly) selectedOutbound else "direct"
        if (customOutbound.isNotBlank()) {
            if (customSuffixes.isNotEmpty()) customRules.put(JSONObject().put("domain_suffix", JSONArray(customSuffixes)).put("outbound", customOutbound))
            if (customExact.isNotEmpty()) customRules.put(JSONObject().put("domain", JSONArray(customExact)).put("outbound", customOutbound))
        }
        val normalizedPackages = customPackages.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (normalizedPackages.isNotEmpty()) {
            customRules.put(JSONObject().put("package_name", JSONArray(normalizedPackages))
                .put("outbound", if (vpnOnly && selectedOutbound.isNotBlank()) selectedOutbound else "direct"))
        }
        if (!hasIpv6Reject) {
            added.put(JSONObject().put("ip_version", 6).put("action", "reject"))
        }
        // DNS/sniff actions must still run before any final routing decision.
        val merged = JSONArray()
        var inserted = false
        for (index in 0 until rules.length()) {
            val rule = rules.getJSONObject(index)
            if (!inserted && rule.optString("action") !in listOf("sniff", "hijack-dns")) {
                for (i in 0 until added.length()) merged.put(added.getJSONObject(i))
                inserted = true
            }
            merged.put(rule)
        }
        if (!inserted) for (i in 0 until added.length()) merged.put(added.getJSONObject(i))
        for (i in 0 until customRules.length()) merged.put(customRules.getJSONObject(i))
        route.put("rules", merged)
        if (vpnOnly) route.put("final", "direct")

        val inbounds = root.optJSONArray("inbounds")
        if (inbounds != null) {
            for (index in 0 until inbounds.length()) {
                val inbound = inbounds.optJSONObject(index) ?: continue
                if (inbound.optString("type") == "tun") {
                    val addresses = inbound.optJSONArray("address") ?: JSONArray().also { inbound.put("address", it) }
                    val hasIpv6 = (0 until addresses.length()).any {
                        addresses.optString(it).contains(':')
                    }
                    if (!hasIpv6) {
                        addresses.put("fdfe:dcba:9876::1/126")
                    }
                    inbound.put("auto_route", true)
                    inbound.put("strict_route", true)
                    inbound.put("stack", "mixed")
                    if (normalizedPackages.isNotEmpty() && !vpnOnly) {
                        val currentExclude = inbound.optJSONArray("exclude_package") ?: JSONArray().also { inbound.put("exclude_package", it) }
                        val existingSet = (0 until currentExclude.length()).map { currentExclude.optString(it) }.toMutableSet()
                        normalizedPackages.forEach { pkg ->
                            if (existingSet.add(pkg)) {
                                currentExclude.put(pkg)
                            }
                        }
                    }
                }
            }
        }

        val dns = root.getJSONObject("dns")
        dns.put("strategy", "ipv4_only")
        val dnsRules = JSONArray()
            .put(JSONObject().put("domain_suffix", JSONArray(suffixes)).put("action", "route").put("server", "local-dns"))
            .put(JSONObject().put("domain", JSONArray(exact)).put("action", "route").put("server", "local-dns"))
        val customDnsServer = if (vpnOnly) dns.optString("final").ifBlank { "remote-dns" } else "local-dns"
        if (customSuffixes.isNotEmpty()) dnsRules.put(JSONObject().put("domain_suffix", JSONArray(customSuffixes)).put("action", "route").put("server", customDnsServer))
        if (customExact.isNotEmpty()) dnsRules.put(JSONObject().put("domain", JSONArray(customExact)).put("action", "route").put("server", customDnsServer))
        val original = dns.optJSONArray("rules") ?: JSONArray()
        for (i in 0 until original.length()) {
            val rule = original.getJSONObject(i)
            val legacyLocalDomains = rule.optString("server") == "local-dns" &&
                rule.keys().asSequence().all { it in domainKeys + listOf("action", "server") }
            if (!legacyLocalDomains) dnsRules.put(rule)
        }
        dns.put("rules", dnsRules)
        dns.put("reverse_mapping", true)
        return root.toString()
    }

    fun forAwg(
        config: String,
        port: Int,
        username: String,
        password: String,
        customDomains: Collection<String> = emptyList(),
        customPackages: Collection<String> = emptyList(),
        mode: String = UserSplitTunnelStore.MODE_BYPASS,
    ): String {
        return forLocalProxy(config, port, username, password, AWG_OUTBOUND, customDomains, customPackages, mode)
    }

    fun forOpenFlux(
        config: String,
        port: Int,
        username: String,
        password: String,
        customDomains: Collection<String> = emptyList(),
        customPackages: Collection<String> = emptyList(),
        mode: String = UserSplitTunnelStore.MODE_BYPASS,
    ): String {
        val root = JSONObject(forLocalProxy(config, port, username, password, OPENFLUX_OUTBOUND, customDomains, customPackages, mode))
        val route = root.getJSONObject("route")
        val original = route.getJSONArray("rules")
        // The carrier exit supports IPv4 TCP. Capture IPv6 in the same TUN and
        // reject it before direct exceptions so Android cannot leak around it.
        val rules = JSONArray().put(JSONObject().put("ip_version", 6).put("action", "reject"))
        for (i in 0 until original.length()) rules.put(original.getJSONObject(i))
        route.put("rules", rules)
        val inbounds = root.getJSONArray("inbounds")
        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.getJSONObject(i)
            if (inbound.optString("type") != "tun") continue
            val addresses = inbound.getJSONArray("address")
            if ((0 until addresses.length()).none { addresses.getString(it).contains(':') }) {
                addresses.put("fdfe:dcba:9876::1/126")
            }
            inbound.put("auto_route", true)
            inbound.remove("route_address")
            inbound.remove("route_exclude_address")
        }
        // TCP DoH uses the encrypted carrier; local DNS remains only for the
        // existing explicit bypass domains. No UDP or direct remote resolver.
        val dns = root.getJSONObject("dns")
        dns.put("strategy", "ipv4_only")
        val bypassRules = JSONArray()
        val dnsRules = dns.getJSONArray("rules")
        for (i in 0 until dnsRules.length()) {
            val rule = dnsRules.getJSONObject(i)
            if (rule.optString("server") == "local-dns") bypassRules.put(rule)
        }
        dns.put("rules", bypassRules)
        dns.put("servers", JSONArray()
            .put(JSONObject().put("type", "local").put("tag", "local-dns"))
            .put(JSONObject().put("type", "https").put("tag", "remote-dns")
                .put("server", "1.1.1.1").put("server_port", 443).put("path", "/dns-query")
                .put("detour", OPENFLUX_OUTBOUND)))
        dns.put("final", "remote-dns")
        root.getJSONArray("outbounds").getJSONObject(0).put("domain_resolver", "local-dns")
        root.getJSONArray("outbounds").getJSONObject(1).put("network", "tcp")
        return root.toString()
    }

    private fun forLocalProxy(
        config: String,
        port: Int,
        username: String,
        password: String,
        outbound: String,
        customDomains: Collection<String> = emptyList(),
        customPackages: Collection<String> = emptyList(),
        mode: String,
    ): String {
        require(port in 1..65535 && username.isNotBlank() && password.isNotBlank())
        val root = JSONObject(apply(config, customDomains, customPackages, mode, outbound))
        root.put("outbounds", JSONArray()
            .put(JSONObject().put("type", "direct").put("tag", "direct"))
            .put(JSONObject().put("type", "socks").put("tag", outbound)
                .put("server", "127.0.0.1").put("server_port", port).put("version", "5")
                .put("username", username).put("password", password)))
        root.remove("endpoints")
        val route = root.getJSONObject("route")
        val original = route.getJSONArray("rules")
        val rules = JSONArray()
        for (i in 0 until original.length()) {
            val rule = original.getJSONObject(i)
            if (rule.optString("action") in listOf("sniff", "hijack-dns") || rule.optString("outbound") in listOf("direct", outbound)) rules.put(rule)
        }
        route.put("rules", rules).put("final", if (mode == UserSplitTunnelStore.MODE_VPN_ONLY) "direct" else outbound)
        val servers = root.getJSONObject("dns").getJSONArray("servers")
        for (i in 0 until servers.length()) {
            val server = servers.getJSONObject(i)
            if (server.optString("tag") != "local-dns") server.put("detour", outbound)
        }
        return root.toString()
    }
}
