package space.deytt.connect

import org.json.JSONArray
import org.json.JSONObject

/** Uses the subscription's bypass list for both DNS and every tunnel transport. */
object SplitTunnelProfile {
    const val AWG_OUTBOUND = "deytt-awg"
    private val domainKeys = listOf("domain", "domain_suffix", "domain_regex", "domain_keyword")

    fun bypassSites(config: String): List<String> {
        val rules = JSONObject(apply(config)).getJSONObject("dns").getJSONArray("rules")
        val suffixes = rules.getJSONObject(0).getJSONArray("domain_suffix")
        val exact = rules.getJSONObject(1).getJSONArray("domain")
        return (0 until suffixes.length()).map { "*.${suffixes.getString(it)}" } +
            (0 until exact.length()).map(exact::getString)
    }

    fun apply(config: String): String {
        val root = JSONObject(config)
        val route = root.getJSONObject("route")
        val rules = route.getJSONArray("rules")
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
        val added = JSONArray()
            .put(JSONObject().put("domain_suffix", JSONArray(suffixes)).put("outbound", "direct"))
            .put(JSONObject().put("domain", JSONArray(exact)).put("outbound", "direct"))
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
        route.put("rules", merged)
        val dns = root.getJSONObject("dns")
        val dnsRules = JSONArray()
            .put(JSONObject().put("domain_suffix", JSONArray(suffixes)).put("action", "route").put("server", "local-dns"))
            .put(JSONObject().put("domain", JSONArray(exact)).put("action", "route").put("server", "local-dns"))
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

    fun forAwg(config: String, port: Int, username: String, password: String): String {
        require(port in 1..65535 && username.isNotBlank() && password.isNotBlank())
        val root = JSONObject(apply(config))
        root.put("outbounds", JSONArray()
            .put(JSONObject().put("type", "direct").put("tag", "direct"))
            .put(JSONObject().put("type", "socks").put("tag", AWG_OUTBOUND)
                .put("server", "127.0.0.1").put("server_port", port).put("version", "5")
                .put("username", username).put("password", password)))
        root.remove("endpoints")
        val route = root.getJSONObject("route")
        val original = route.getJSONArray("rules")
        val rules = JSONArray()
        for (i in 0 until original.length()) {
            val rule = original.getJSONObject(i)
            if (rule.optString("action") in listOf("sniff", "hijack-dns") || rule.optString("outbound") == "direct") rules.put(rule)
        }
        route.put("rules", rules).put("final", AWG_OUTBOUND)
        val servers = root.getJSONObject("dns").getJSONArray("servers")
        for (i in 0 until servers.length()) {
            val server = servers.getJSONObject(i)
            if (server.optString("tag") != "local-dns") server.put("detour", AWG_OUTBOUND)
        }
        return root.toString()
    }
}
