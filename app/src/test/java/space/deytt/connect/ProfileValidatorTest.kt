package space.deytt.connect

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProfileValidatorTest {
    @Test
    fun acceptsCurrentModernProfileWithAutomaticRoutesAndWireguardEndpoint() {
        val summary = SubscriptionClient.validateDownloadedProfile(validProfile().toString())
        assertTrue(summary.hasTunInbound)
        assertEquals(setOf("vless", "trojan", "hysteria2", "wireguard"), summary.protocols)
    }

    @Test
    fun rejectsUiIncompatibleMissingAutoBeforeCommit() {
        val profile = validProfile()
        profile.getJSONArray("outbounds").getJSONObject(0).put("tag", "ordinary-selector")
        profile.getJSONObject("route").put("final", "ordinary-selector")
        // Structural engine validation alone previously accepted this profile,
        // then the main-thread success callback crashed after persisting it.
        ProfileValidator.validate(profile.toString())
        assertPayloadRejected(profile)
    }

    @Test
    fun emptySelectorsCannotPretendToBeNetworkConnectivity() {
        val profile = validProfile().removeEndpoints()
        profile.put("outbounds", JSONArray().put(JSONObject()
            .put("type", "urltest").put("tag", "🇪🇺 автоподбор").put("outbounds", JSONArray())))
        assertPayloadRejected(profile)
    }

    @Test
    fun rejectsUnknownFinalTagAndDuplicateOrMissingOutboundTags() {
        assertPayloadRejected(validProfile().apply { getJSONObject("route").put("final", "missing") })
        assertPayloadRejected(validProfile().apply { getJSONArray("outbounds").getJSONObject(1).remove("tag") })
        assertPayloadRejected(validProfile().apply { getJSONArray("outbounds").getJSONObject(1).put("tag", JSONObject.NULL) })
        assertPayloadRejected(validProfile().apply { getJSONArray("outbounds").getJSONObject(1).put("tag", "🇪🇺 автоподбор") })
        assertPayloadRejected(validProfile().apply { getJSONArray("endpoints").getJSONObject(0).put("tag", "route:NL:VLESS") })
    }

    @Test
    fun allowsFinalToReferenceModernWireguardEndpoint() {
        SubscriptionClient.validateDownloadedProfile(validProfile().apply {
            getJSONObject("route").put("final", "vpn основной (amneziawg)")
        }.toString())
    }

    @Test
    fun doesNotRejectNewEngineNetworkTypeJustBecauseNotInKnownProtocolList() {
        SubscriptionClient.validateDownloadedProfile(validProfile().apply {
            getJSONArray("outbounds").getJSONObject(1).put("type", "future-network-protocol")
        }.toString())
    }

    private fun assertPayloadRejected(profile: JSONObject) {
        assertTrue(runCatching { SubscriptionClient.validateDownloadedProfile(profile.toString()) }
            .exceptionOrNull() is SubscriptionPayloadException)
    }

    private fun JSONObject.removeEndpoints() = apply { remove("endpoints") }

    private fun validProfile() = JSONObject("""
        {
          "inbounds": [{"type":"tun","tag":"tun-in","address":["172.19.0.1/30"],"auto_route":true}],
          "outbounds": [
            {"type":"urltest","tag":"🇪🇺 автоподбор","outbounds":["route:NL:VLESS","route:NL:TROJAN","route:NL:HYSTERIA2"]},
            {"type":"vless","tag":"route:NL:VLESS","server":"edge.example.invalid","server_port":443,"uuid":"00000000-0000-0000-0000-000000000001","tls":{"enabled":true,"server_name":"edge.example.invalid"}},
            {"type":"trojan","tag":"route:NL:TROJAN","server":"edge.example.invalid","server_port":443,"password":"test-only"},
            {"type":"hysteria2","tag":"route:NL:HYSTERIA2","server":"edge.example.invalid","server_port":443,"password":"test-only"},
            {"type":"direct","tag":"direct"}
          ],
          "endpoints": [{"type":"wireguard","tag":"vpn основной (amneziawg)"}],
          "dns": {"servers":[{"type":"https","tag":"remote-dns","server":"resolver.example.invalid"},{"type":"local","tag":"local-dns"}]},
          "route": {"final":"🇪🇺 автоподбор","rules":[{"protocol":"dns","action":"hijack-dns"}]}
        }
    """.trimIndent())
}
