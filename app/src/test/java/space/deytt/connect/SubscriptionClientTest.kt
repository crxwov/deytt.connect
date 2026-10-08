package space.deytt.connect

import java.io.IOException
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionClientTest {
    @Test
    fun longServerBackoffReturnsControlWithoutHammeringEndpoint() {
        val transport = FakeTransport(SubscriptionClient.SubscriptionHttpResponse(429, retryAfterMillis = 60_000))
        val error = runCatching {
            SubscriptionClient.requestForTest("https://deytt.space/sub/token", "application/json", true, transport)
        }.exceptionOrNull()
        assertTrue(error is SubscriptionHttpFailure)
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun parsesBothRetryAfterFormatsAndBoundsUntrustedValues() {
        assertEquals(2_000L, SubscriptionClient.parseRetryAfter("2"))
        assertEquals(86_400_000L, SubscriptionClient.parseRetryAfter("9999999999999"))
        assertEquals(2_000L, SubscriptionClient.parseRetryAfter("Thu, 01 Jan 1970 00:00:02 GMT", 0))
        assertEquals(null, SubscriptionClient.parseRetryAfter("garbage"))
    }

    @Test
    fun retriesDnsAndConnectFailuresWithBoundedAttempts() {
        val transport = FakeTransport(java.net.UnknownHostException(), java.net.ConnectException(), java.io.EOFException())
        val error = runCatching {
            SubscriptionClient.requestForTest("https://deytt.space/sub/token", "application/json", true, transport)
        }.exceptionOrNull()
        assertTrue(error is java.io.EOFException)
        assertEquals(3, transport.calls.size)
    }

    @Test
    fun interruptedImportNeverMakesNetworkRequestOrSwallowsCancellationAsOptional() {
        val transport = FakeTransport(SubscriptionClient.SubscriptionHttpResponse(200))
        Thread.currentThread().interrupt()
        try {
            val error = runCatching {
                SubscriptionClient.fetchAwgProfilesForTest("https://deytt.space/sub/token", "amneziawg31", "31", transport)
            }.exceptionOrNull()
            assertTrue(error is SubscriptionCancelledException)
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(transport.calls.isEmpty())
        } finally { Thread.interrupted() }
    }

    @Test
    fun partialHttpResponseCannotBecomeAValidProfile() {
        val transport = FakeTransport(SubscriptionClient.SubscriptionHttpResponse(206, body = "partial"))
        val error = runCatching {
            SubscriptionClient.requestForTest("https://deytt.space/sub/token", "application/json", true, transport)
        }.exceptionOrNull()
        assertTrue(error is SubscriptionPayloadException)
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun rejectsUnexpectedTlsPortBeforeSendingIdentity() {
        assertTrue(runCatching {
            SubscriptionClient.normalizeBaseUrlForTest("https://deytt.space:8443/sub/token")
        }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun malformedAwgManifestDoesNotSilentlyDropProfiles() {
        val result = SubscriptionClient.fetchAwgProfilesForTest("https://deytt.space/sub/token", "amneziawg31", "31",
            FakeTransport(SubscriptionClient.SubscriptionHttpResponse(200, VALID_AWG31_CONFIG,
                awgServers = "[{\"id\":\"nl\"},{\"id\":\"nl\"}]")))
        assertEquals(SubscriptionClient.AwgFetchState.PARTIAL_FAILURE, result.state)
        assertEquals("awg31", result.profiles.single().id)
        assertEquals(VALID_AWG31_CONFIG, result.profiles.single().config)
        assertTrue(!result.warning.isNullOrBlank())
    }

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
    fun importsOnlyTheSupportedAmnezia31Family() {
        AwgProfileStore.validate(VALID_AWG31_CONFIG)
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

        assertEquals(SubscriptionClient.AwgFetchState.AVAILABLE, awg31.state)
        assertEquals("awg31:de", awg31.profiles.single().id)
        AwgProfileStore.validate(awg31.profiles.single().config)
    }

    @Test
    fun fetchesBothRealAwgGenerationsAndPreservesItalianServerMetadata() {
        AwgProfileStore.validate(VALID_AWG_CONFIG)
        AwgProfileStore.validate(VALID_AWG31_CONFIG)
        val transport = FakeTransport(
            SubscriptionClient.SubscriptionHttpResponse(
                200,
                body = VALID_AWG_CONFIG,
                awgServers = """[{"id":"it-01","label":"Milano 01","short_label":"IT"}]""",
            ),
            SubscriptionClient.SubscriptionHttpResponse(
                200,
                body = VALID_AWG31_CONFIG,
                awgServers = """[{"id":"it-31","label":"Milano 31","short_label":"IT"}]""",
            ),
        )

        val results = SubscriptionClient.fetchAwgGenerationsForTest("https://deytt.space/sub/token", transport)
        val profiles = results.flatMap { it.profiles }

        assertEquals(
            listOf("amneziawg", "amneziawg31"),
            transport.calls.map { java.net.URI(it.first).rawQuery.orEmpty().substringAfter("format=") },
        )
        assertEquals(listOf("15", "31"), profiles.map(AwgProfile::version))
        assertEquals(listOf("awg15:it-01", "awg31:it-31"), profiles.map(AwgProfile::id))
        assertEquals(listOf("IT", "IT"), profiles.map(AwgProfile::shortLabel))
        assertTrue(profiles.all { it.config.contains("[Peer]") })
    }

    @Test
    fun unavailableLegacyFormatDoesNotHideAvailable31Profiles() {
        val results = SubscriptionClient.fetchAwgGenerationsForTest(
            "https://deytt.space/sub/token",
            FakeTransport(
                SubscriptionClient.SubscriptionHttpResponse(404),
                SubscriptionClient.SubscriptionHttpResponse(200, VALID_AWG31_CONFIG),
            ),
        )

        assertEquals(SubscriptionClient.AwgFetchState.NOT_AVAILABLE, results[0].state)
        assertEquals(SubscriptionClient.AwgFetchState.AVAILABLE, results[1].state)
        assertTrue(results[0].profiles.isEmpty())
        assertEquals(listOf("awg31"), results[1].profiles.map(AwgProfile::id))
    }

    @Test
    fun timedOutLegacyFormatDoesNotHideAvailable31Profiles() {
        val results = SubscriptionClient.fetchAwgGenerationsForTest(
            "https://deytt.space/sub/token",
            FakeTransport(
                SubscriptionDeadlineException(),
                SubscriptionClient.SubscriptionHttpResponse(200, VALID_AWG31_CONFIG),
            ),
        )

        assertEquals(SubscriptionClient.AwgFetchState.TRANSIENT_FAILURE, results[0].state)
        assertEquals(SubscriptionClient.AwgFetchState.AVAILABLE, results[1].state)
        assertTrue(results[0].profiles.isEmpty())
        assertEquals(listOf("awg31"), results[1].profiles.map(AwgProfile::id))
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

    @Test
    fun deviceSlotRejectionIsExplicitAndNeverRetried() {
        val transport = FakeTransport(SubscriptionClient.SubscriptionHttpResponse(
            409, body = """{"error":"app_device_limit_reached"}""",
        ))
        val error = runCatching {
            SubscriptionClient.requestForTest("https://deytt.space/sub/token", "application/json", true, transport)
        }.exceptionOrNull() as SubscriptionHttpFailure
        assertEquals("app_device_limit_reached", error.code)
        assertEquals(1, transport.calls.size)
        val message = SubscriptionErrorText.userMessage(error)
        assertTrue(message.contains("по одному телефону и одному компьютеру"))
        assertTrue(message.contains("их слоты независимы"))
        assertTrue(message.contains("выйдите из аккаунта на прежнем телефоне"))
        assertTrue(message.contains("Happ использует отдельную квоту"))
    }

    @Test
    fun blockedDeviceIsNotMisreportedAsExpiredLink() {
        val error = runCatching {
            SubscriptionClient.requestForTest("https://deytt.space/sub/token", "application/json", true,
                FakeTransport(SubscriptionClient.SubscriptionHttpResponse(403, body = """{"error":"device_blocked"}""")))
        }.exceptionOrNull() as SubscriptionHttpFailure
        assertTrue(SubscriptionErrorText.userMessage(error).contains("заблокирован"))
    }

    @Test
    fun expiredSessionDuringAwgFetchIsNotHiddenAsOptionalFailure() {
        val error = runCatching {
            SubscriptionClient.fetchAwgProfilesForTest("https://deytt.space/sub/token", "amneziawg31", "31",
                FakeTransport(SubscriptionClient.SubscriptionHttpResponse(401, body = """{"error":"session_expired"}""")))
        }.exceptionOrNull() as SubscriptionHttpFailure
        assertEquals("session_expired", error.code)
        assertTrue(SubscriptionErrorText.userMessage(error).contains("Войдите через Telegram снова"))
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
