package space.deytt.connect

import java.io.IOException
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionClientTest {
    @Test
    fun canonicalizesFormatServerIdAndPreservesDuplicateSafeQueryValues() {
        assertEquals(
            "https://deytt.space/sub/token?keep=one&keep=two&lang=ru",
            SubscriptionClient.normalizeBaseUrlForTest(
                "https://deytt.space/sub/token?format=hysteria&keep=one&keep=two&server_id=old&lang=ru#fragment",
            ),
        )
    }

    @Test
    fun retries502ThenReturnsResponse() {
        val transport = FakeTransport(
            SubscriptionClient.SubscriptionHttpResponse(502),
            SubscriptionClient.SubscriptionHttpResponse(200, body = "ok"),
        )

        val response = SubscriptionClient.requestForTest(
            "https://deytt.space/sub/token?format=singbox",
            "application/json",
            required = true,
            transport = transport,
        )

        assertEquals("ok", response?.body)
        assertEquals(2, transport.calls.size)
    }

    @Test
    fun retriesWrappedUnexpectedEof() {
        val transport = FakeTransport(
            IOException("wrapped", IOException("unexpected end of stream")),
            SubscriptionClient.SubscriptionHttpResponse(200, body = "ok"),
        )

        val response = SubscriptionClient.requestForTest(
            "https://deytt.space/sub/token?format=singbox",
            "application/json",
            required = true,
            transport = transport,
        )

        assertEquals("ok", response?.body)
        assertEquals(2, transport.calls.size)
    }

    @Test
    fun optional404DoesNotBecomeAnImportFailure() {
        val response = SubscriptionClient.requestForTest(
            "https://deytt.space/sub/token?format=amneziawg31",
            "text/plain",
            required = false,
            transport = FakeTransport(SubscriptionClient.SubscriptionHttpResponse(404)),
        )

        assertEquals(null, response)
    }

    @Test
    fun redirectIsRejectedWithoutFollowingBearerUrl() {
        val error = runCatching {
            SubscriptionClient.requestForTest(
                "https://deytt.space/sub/token?format=singbox",
                "application/json",
                required = true,
                transport = FakeTransport(SubscriptionClient.SubscriptionHttpResponse(302)),
            )
        }.exceptionOrNull()

        assertTrue(error is SubscriptionHttpFailure)
        assertEquals(302, (error as SubscriptionHttpFailure).statusCode)
    }

    @Test
    fun failedServerIdDoesNotHideSuccessfulAwgServers() {
        val servers = """
            [{"id":"nl","label":"Нидерланды","short_label":"NL"},
             {"id":"de","label":"Германия","short_label":"DE"}]
        """.trimIndent()
        val transport = FakeTransport(
            SubscriptionClient.SubscriptionHttpResponse(200, awgServers = servers),
            SubscriptionClient.SubscriptionHttpResponse(502),
            SubscriptionClient.SubscriptionHttpResponse(502),
            SubscriptionClient.SubscriptionHttpResponse(502),
            SubscriptionClient.SubscriptionHttpResponse(200, body = VALID_AWG_CONFIG),
        )

        val result = SubscriptionClient.fetchAwgProfilesForTest(
            "https://deytt.space/sub/token",
            "amneziawg31",
            "31",
            transport,
        )

        assertEquals(SubscriptionClient.AwgFetchState.PARTIAL_FAILURE, result.state)
        assertEquals(listOf("awg31:de"), result.profiles.map(AwgProfile::id))
        assertEquals(setOf("nl"), result.failedIds)
        assertTrue(result.warning.orEmpty().contains("1 сервер"))
        assertFalse(result.warning.orEmpty().contains("IP"))
    }

    @Test
    fun keepsManifestConfigWhenPerServerFetchFails() {
        AwgProfileStore.validate(VALID_AWG31_CONFIG)
        val result = SubscriptionClient.fetchAwgProfilesForTest(
            "https://deytt.space/sub/token",
            "amneziawg31",
            "31",
            FakeTransport(
                SubscriptionClient.SubscriptionHttpResponse(
                    200,
                    body = VALID_AWG31_CONFIG,
                    awgServers = """
                        [{"id":"nl","label":"Нидерланды","short_label":"NL"},
                         {"id":"de","label":"Германия","short_label":"DE"}]
                    """.trimIndent(),
                ),
                SubscriptionClient.SubscriptionHttpResponse(502),
                SubscriptionClient.SubscriptionHttpResponse(502),
                SubscriptionClient.SubscriptionHttpResponse(502),
            ),
        )

        assertEquals(SubscriptionClient.AwgFetchState.PARTIAL_FAILURE, result.state)
        assertEquals(listOf("awg31:nl"), result.profiles.map(AwgProfile::id))
        assertEquals(setOf("de"), result.failedIds)
        assertTrue(result.warning.orEmpty().contains("Доступные точки добавлены"))
    }

    @Test
    fun validAwg15And31FamiliesImportOneAdvertisedServerEach() {
        AwgProfileStore.validate(VALID_AWG31_CONFIG)
        val awg15 = SubscriptionClient.fetchAwgProfilesForTest(
            "https://deytt.space/sub/token",
            "amneziawg",
            "15",
            FakeTransport(
                SubscriptionClient.SubscriptionHttpResponse(
                    200,
                    body = VALID_AWG_CONFIG,
                    awgServers = "[{\"id\":\"nl\",\"label\":\"Нидерланды\",\"short_label\":\"NL\"}]",
                ),
            ),
        )
        val awg31 = SubscriptionClient.fetchAwgProfilesForTest(
            "https://deytt.space/sub/token",
            "amneziawg31",
            "31",
            FakeTransport(
                SubscriptionClient.SubscriptionHttpResponse(
                    200,
                    body = VALID_AWG31_CONFIG,
                    awgServers = "[{\"id\":\"de\",\"label\":\"Германия\",\"short_label\":\"DE\"}]",
                ),
            ),
        )

        assertEquals(SubscriptionClient.AwgFetchState.AVAILABLE, awg15.state)
        assertEquals(SubscriptionClient.AwgFetchState.AVAILABLE, awg31.state)
        assertEquals("awg15:nl", awg15.profiles.single().id)
        assertEquals("awg31:de", awg31.profiles.single().id)
        AwgProfileStore.validate(awg15.profiles.single().config)
        AwgProfileStore.validate(awg31.profiles.single().config)
    }

    @Test
    fun allAdvertisedAwgServersRemainAVisiblePartialFailure() {
        val result = SubscriptionClient.fetchAwgProfilesForTest(
            "https://deytt.space/sub/token",
            "amneziawg31",
            "31",
            FakeTransport(
                SubscriptionClient.SubscriptionHttpResponse(
                    200,
                    awgServers = "[{\"id\":\"nl\",\"label\":\"Нидерланды\"}]",
                ),
                SubscriptionClient.SubscriptionHttpResponse(502),
                SubscriptionClient.SubscriptionHttpResponse(502),
                SubscriptionClient.SubscriptionHttpResponse(502),
            ),
        )

        assertEquals(SubscriptionClient.AwgFetchState.PARTIAL_FAILURE, result.state)
        assertTrue(result.profiles.isEmpty())
        assertEquals(setOf("nl"), result.failedIds)
        assertTrue(result.warning.orEmpty().contains("1 сервер"))
        assertFalse(result.warning.orEmpty().contains("IP"))
    }

    @Test
    fun missingOptionalAwgEndpointIsExplicitAndNonDestructive() {
        val result = SubscriptionClient.fetchAwgProfilesForTest(
            "https://deytt.space/sub/token",
            "amneziawg31",
            "31",
            FakeTransport(SubscriptionClient.SubscriptionHttpResponse(404)),
        )

        assertEquals(SubscriptionClient.AwgFetchState.NOT_AVAILABLE, result.state)
        assertTrue(result.profiles.isEmpty())
    }

    @Test
    fun optional502DoesNotPretendThatOneServerWasMissing() {
        val result = SubscriptionClient.fetchAwgProfilesForTest(
            "https://deytt.space/sub/token",
            "amneziawg31",
            "31",
            FakeTransport(
                SubscriptionClient.SubscriptionHttpResponse(502),
                SubscriptionClient.SubscriptionHttpResponse(502),
                SubscriptionClient.SubscriptionHttpResponse(502),
            ),
        )

        assertEquals(SubscriptionClient.AwgFetchState.TRANSIENT_FAILURE, result.state)
        assertTrue(result.warning.orEmpty().contains("дополнительные профили"))
        assertFalse(result.warning.orEmpty().contains("1 сервер"))
    }

    private class FakeTransport(vararg values: Any) : SubscriptionClient.SubscriptionHttpTransport {
        private val responses = ArrayDeque(values.toList())
        val calls = mutableListOf<Pair<String, String>>()

        override fun get(url: String, accept: String): SubscriptionClient.SubscriptionHttpResponse {
            calls += url to accept
            val next = if (responses.isEmpty()) error("fake transport exhausted") else responses.removeFirst()
            if (next is IOException) throw next
            return next as SubscriptionClient.SubscriptionHttpResponse
        }
    }

    private companion object {
        const val VALID_AWG_CONFIG = """
            [Interface]
            Address = 192.0.2.2/32
            DNS = 192.0.2.0
            PrivateKey = TFlmmEUC7V7VtiDYLKsbP5rySTKLIZq1yn8lMqK83wo=
            [Peer]
            AllowedIPs = 0.0.0.0/0, ::0/0
            Endpoint = awg.example.com:51820
            PersistentKeepalive = 25
            PublicKey = vBN7qyUTb5lJtWYJ8LhbPio1Z4RcyBPGnqFBGn6O6Qg=
        """

        const val VALID_AWG31_CONFIG = """
            [Interface]
            Address = 192.0.2.3/32
            DNS = 192.0.2.0
            PrivateKey = TFlmmEUC7V7VtiDYLKsbP5rySTKLIZq1yn8lMqK83wo=
            Jc = 1
            Jmin = 10
            Jmax = 20
            HeaderProtectionKey = TFlmmEUC7V7VtiDYLKsbP5rySTKLIZq1yn8lMqK83wo=
            RandomTrailers = on
            DisableCookies = off
            [Peer]
            AllowedIPs = 0.0.0.0/0, ::0/0
            Endpoint = awg.example.com:51823
            PersistentKeepalive = 25
            PublicKey = vBN7qyUTb5lJtWYJ8LhbPio1Z4RcyBPGnqFBGn6O6Qg=
        """
    }
}
