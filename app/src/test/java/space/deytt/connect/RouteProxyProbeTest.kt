package space.deytt.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.json.JSONObject
import java.io.IOException

class RouteProxyProbeTest {
    private val profile = """
        {
          "inbounds": [{"type":"tun","tag":"tun-in","address":["172.19.0.1/30"]}],
          "outbounds": [
            {"type":"selector","tag":"route:DE","outbounds":["route:DE:VLESS"]},
            {"type":"vless","tag":"route:DE:VLESS","server":"de.example.com","server_port":443},
            {"type":"direct","tag":"direct"}
          ],
          "dns": {"servers":[{"tag":"local-dns"}]},
          "route": {"final":"direct","rules":[]}
        }
    """.trimIndent()

    @Test
    fun createsLoopbackOnlyProxyAndRemovesTunBeforeProbe() {
        val result = JSONObject(
            RouteProxyProbe.configuration(profile, "route:DE:VLESS", 43871, "probe-user", "ephemeral-password"),
        )
        val inbounds = result.getJSONArray("inbounds")
        val inbound = inbounds.getJSONObject(0)

        assertEquals(1, inbounds.length())
        assertEquals("mixed", inbound.getString("type"))
        assertEquals("127.0.0.1", inbound.getString("listen"))
        assertEquals(43871, inbound.getInt("listen_port"))
        assertEquals("probe-user", inbound.getJSONArray("users").getJSONObject(0).getString("username"))
        assertEquals("ephemeral-password", inbound.getJSONArray("users").getJSONObject(0).getString("password"))
        assertFalse((0 until inbounds.length()).any { inbounds.getJSONObject(it).getString("type") == "tun" })
        assertEquals("route:DE:VLESS", result.getJSONObject("route").getString("final"))
    }

    @Test
    fun parsesHttpStatusLineForTheProxyAndCanaryResponses() {
        assertEquals(200, RouteProxyProbe.parseHttpStatusCode("HTTP/1.1 200 Connection established"))
        assertEquals(204, RouteProxyProbe.parseHttpStatusCode("HTTP/1.1 204 No Content"))
    }

    @Test
    fun acceptsOnlyDocumentedDownloadContentType() {
        RouteProxyProbe.validateDownloadContentType("application/octet-stream")
        RouteProxyProbe.validateDownloadContentType("Application/Octet-Stream; charset=binary")
        listOf(null, "text/html", "application/json").forEach { contentType ->
            try {
                RouteProxyProbe.validateDownloadContentType(contentType)
                fail("Expected invalid Content-Type to be rejected: $contentType")
            } catch (_: IOException) {
                // Expected: successful error pages are not speed samples.
            }
        }
    }

    @Test
    fun boundsDownloadAfterConnectionSetup() {
        assertEquals(1_000, RouteProxyProbe.QUICK_DOWNLOAD_MILLIS)
        assertEquals(2_500, RouteProxyProbe.HOME_DOWNLOAD_MILLIS)
        assertEquals(6_000L, RouteProxyProbe.systemDownloadTaskTimeoutMillis(RouteProxyProbe.QUICK_DOWNLOAD_MILLIS))
    }

    @Test
    fun downloadThroughputTrackerExcludesWarmupSlowStartFromSteadyState() {
        val tracker = DownloadThroughputTracker(startedAtMillis = 1000L, warmupMillis = 400L, minSteadyMillis = 250L)
        // During warmup (e.g. at 1200ms)
        tracker.record(totalBytes = 10_000L, nowMillis = 1200L)
        assertEquals(50_000L, tracker.bytesPerSecond(totalBytes = 10_000L, nowMillis = 1200L))

        // After warmup (at 1500ms, total 100_000B recorded at end of warmup)
        tracker.record(totalBytes = 100_000L, nowMillis = 1500L)
        // Steady state measurement at 2000ms: transferred additional 500_000B in 500ms -> 1_000_000 B/s
        val steadySpeed = tracker.bytesPerSecond(totalBytes = 600_000L, nowMillis = 2000L)
        assertEquals(1_000_000L, steadySpeed)
    }

    @Test
    fun socks5HandshakeNegotiatesUsernamePasswordAuthAndConnect() {
        val clientIn = java.io.PipedInputStream()
        val serverOut = java.io.PipedOutputStream(clientIn)
        val serverIn = java.io.PipedInputStream()
        val clientOut = java.io.PipedOutputStream(serverIn)

        val serverThread = Thread {
            // Read greeting (0x05, 0x01, 0x02)
            val greeting = ByteArray(3)
            serverIn.read(greeting)
            assertEquals(0x05.toByte(), greeting[0])
            assertEquals(0x02.toByte(), greeting[2])
            // Reply auth method accepted: 0x05, 0x02
            serverOut.write(byteArrayOf(0x05, 0x02))
            serverOut.flush()

            // Read auth subnegotiation: 0x01, ulen, user, plen, pass
            val ver = serverIn.read()
            assertEquals(0x01, ver)
            val ulen = serverIn.read()
            val user = ByteArray(ulen)
            serverIn.read(user)
            assertEquals("test-user", String(user))
            val plen = serverIn.read()
            val pass = ByteArray(plen)
            serverIn.read(pass)
            assertEquals("test-pass", String(pass))
            // Reply auth success: 0x01, 0x00
            serverOut.write(byteArrayOf(0x01, 0x00))
            serverOut.flush()

            // Read CONNECT request: 0x05, 0x01, 0x00, 0x03, hlen, host, port
            val req = ByteArray(4)
            serverIn.read(req)
            assertEquals(0x05.toByte(), req[0])
            assertEquals(0x01.toByte(), req[1]) // CONNECT
            assertEquals(0x03.toByte(), req[3]) // DOMAIN
            val hlen = serverIn.read()
            val host = ByteArray(hlen)
            serverIn.read(host)
            assertEquals("speed.cloudflare.com", String(host))
            val p1 = serverIn.read()
            val p2 = serverIn.read()
            assertEquals(443, (p1 shl 8) or p2)
            // Reply success: 0x05, 0x00, 0x00, 0x01, 0,0,0,0, 0,0
            serverOut.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x01, 0xBB.toByte()))
            serverOut.flush()
        }
        serverThread.start()

        RouteProxyProbe.negotiateSocks5Connect(clientIn, clientOut, "test-user", "test-pass", "speed.cloudflare.com", 443)
        serverThread.join(2000)
        assertFalse(serverThread.isAlive)
    }

    @Test
    fun distinguishesPrematureEofFromIntentionalPartialSamples() {
        assertTrue(RouteProxyProbe.isPrematureDownloadEof(12, 32, 32))
        assertTrue(RouteProxyProbe.isPrematureDownloadEof(12, 64, 32))
        assertFalse(RouteProxyProbe.isPrematureDownloadEof(32, 32, 32))
        assertFalse(RouteProxyProbe.isPrematureDownloadEof(16, 32, 16))
        assertFalse(RouteProxyProbe.isPrematureDownloadEof(12, null, 32))
    }

    @Test
    fun boundsSystemDownloadReadTimeoutBySampleAndTotalDeadlines() {
        assertEquals(400, RouteProxyProbe.remainingSystemDownloadReadTimeoutMillis(100, 500, 700))
        assertEquals(200, RouteProxyProbe.remainingSystemDownloadReadTimeoutMillis(100, 500, 300))
        assertNull(RouteProxyProbe.remainingSystemDownloadReadTimeoutMillis(300, 500, 300))
    }

    @Test
    fun checksFastTransferPredicateOnEveryBodyRead() {
        var transferChecks = 0
        repeat(4) {
            assertTrue(RouteProxyProbe.isSystemDownloadTransferActive(
                transferActive = { transferChecks++; true },
                isInterrupted = { false },
            ))
        }
        assertEquals(4, transferChecks)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownRouteInsteadOfFallingBackToDirect() {
        RouteProxyProbe.configuration(profile, "direct", 43871, "probe-user", "ephemeral-password")
    }
    @Test
    fun batchPinsEveryLoopbackInboundBeforeExistingBypassRules() {
        val source = JSONObject(profile)
        source.getJSONArray("outbounds").put(JSONObject()
            .put("type", "trojan").put("tag", "route:DE:TROJAN").put("server", "test.invalid").put("server_port", 443))
        source.getJSONObject("route").getJSONArray("rules").put(JSONObject().put("outbound", "direct"))
        val result = JSONObject(RouteProxyProbe.batchConfiguration(source.toString(), linkedMapOf(
            "route:DE:VLESS" to RouteProbeSession(42001, "a", "one"),
            "route:DE:TROJAN" to RouteProbeSession(42002, "b", "two"),
        )))
        val inbounds = result.getJSONArray("inbounds")
        val rules = result.getJSONObject("route").getJSONArray("rules")
        assertEquals(2, inbounds.length())
        assertEquals(3, rules.length())
        listOf("route:DE:VLESS", "route:DE:TROJAN").forEachIndexed { index, tag ->
            assertEquals("127.0.0.1", inbounds.getJSONObject(index).getString("listen"))
            assertEquals(inbounds.getJSONObject(index).getString("tag"), rules.getJSONObject(index).getJSONArray("inbound").getString(0))
            assertEquals(tag, rules.getJSONObject(index).getString("outbound"))
        }
        assertEquals("direct", rules.getJSONObject(2).getString("outbound"))
        assertEquals("tun", source.getJSONArray("inbounds").getJSONObject(0).getString("type"))
    }

    @Test
    fun batchUsesDefaultDirectDnsDialerInsteadOfUnsupportedDirectDetour() {
        val source = JSONObject(profile)
        source.getJSONObject("dns").getJSONArray("servers").put(JSONObject()
            .put("tag", "remote-dns").put("detour", "route:DE:VLESS"))
        val result = JSONObject(RouteProxyProbe.batchConfiguration(source.toString(), mapOf(
            "route:DE:VLESS" to RouteProbeSession(42001, "a", "one"),
        )))
        val servers = result.getJSONObject("dns").getJSONArray("servers")
        repeat(servers.length()) { assertFalse(servers.getJSONObject(it).has("detour")) }
        assertEquals("local-dns", result.getJSONObject("dns").getString("final"))
        assertEquals(0, result.getJSONObject("dns").getJSONArray("rules").length())
        assertEquals("route:DE:VLESS", source.getJSONObject("dns").getJSONArray("servers").getJSONObject(1).getString("detour"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun batchRejectsDuplicatePorts() {
        RouteProxyProbe.batchConfiguration(profile, linkedMapOf(
            "route:DE:VLESS" to RouteProbeSession(42001, "a", "one"),
            "route:DE" to RouteProbeSession(42001, "b", "two"),
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun batchRejectsInternalRoute() {
        RouteProxyProbe.batchConfiguration(profile, mapOf("direct" to RouteProbeSession(42001, "a", "one")))
    }

    @Test
    fun applicationTunnelCheckAppliesUserPackageSplitPolicy() {
        val packageName = "space.deytt.connect"

        assertTrue(RouteProxyProbe.isApplicationRoutedByTunnel(profile, packageName))
        assertFalse(RouteProxyProbe.isApplicationRoutedByTunnel(
            profile,
            packageName,
            customPackages = listOf(packageName),
            splitMode = UserSplitTunnelStore.MODE_BYPASS,
        ))
        assertTrue(RouteProxyProbe.isApplicationRoutedByTunnel(
            profile,
            packageName,
            customPackages = listOf(packageName),
            splitMode = UserSplitTunnelStore.MODE_VPN_ONLY,
        ))
    }

    @Test
    fun applicationTunnelCheckCombinesProviderAndUserPackageRules() {
        val packageName = "space.deytt.connect"
        val providerProfile = JSONObject(profile).apply {
            val tun = getJSONArray("inbounds").getJSONObject(0)
            tun.put("include_package", org.json.JSONArray().put(packageName))
            tun.put("exclude_package", org.json.JSONArray().put("com.example.provider-excluded"))
        }.toString()

        assertFalse(RouteProxyProbe.isApplicationRoutedByTunnel(
            providerProfile,
            packageName,
            customPackages = listOf(packageName),
            splitMode = UserSplitTunnelStore.MODE_BYPASS,
        ))
        assertTrue(RouteProxyProbe.isApplicationRoutedByTunnel(
            providerProfile,
            packageName,
            customPackages = listOf(packageName),
            splitMode = UserSplitTunnelStore.MODE_VPN_ONLY,
        ))
        assertFalse(RouteProxyProbe.isApplicationRoutedByTunnel(
            JSONObject(providerProfile).apply {
                getJSONArray("inbounds").getJSONObject(0)
                    .put("exclude_package", org.json.JSONArray().put(packageName))
            }.toString(),
            packageName,
            customPackages = listOf(packageName),
            splitMode = UserSplitTunnelStore.MODE_VPN_ONLY,
        ))
    }
}
