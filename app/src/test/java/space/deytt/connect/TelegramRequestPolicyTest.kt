package space.deytt.connect

import java.net.UnknownHostException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TelegramRequestPolicyTest {
    @Test fun retriesReadAfterDnsFailure() {
        var attempts = 0
        val value = TelegramRequestPolicy.execute("GET", SubscriptionRequestBudget(5_000)) {
            if (++attempts == 1) throw UnknownHostException("private host")
            "ok"
        }
        assertEquals("ok", value)
        assertEquals(2, attempts)
    }

    @Test fun neverReplaysPairingOrOtherPosts() {
        var attempts = 0
        try {
            TelegramRequestPolicy.execute("POST", SubscriptionRequestBudget(5_000)) {
                attempts++
                throw TelegramPairingException("request_failed", 503)
            }
            fail("POST must fail")
        } catch (_: TelegramPairingException) { }
        assertEquals(1, attempts)
    }

    @Test fun retriesTemporaryHttpGetButNotExpiredSession() {
        var attempts = 0
        val value = TelegramRequestPolicy.execute("GET", SubscriptionRequestBudget(5_000)) {
            if (++attempts == 1) throw TelegramPairingException("request_failed", 502)
            "ok"
        }
        assertEquals("ok", value)
        assertEquals(2, attempts)
        attempts = 0
        try {
            TelegramRequestPolicy.execute("GET", SubscriptionRequestBudget(5_000)) {
                attempts++
                throw TelegramPairingException("session_expired", 401)
            }
            fail("Session must fail")
        } catch (_: TelegramPairingException) { }
        assertEquals(1, attempts)
    }

    @Test fun malformedSuccessIsNotAnInactiveSubscription() {
        for (body in listOf("", "<html>gateway</html>", "[]")) {
            try {
                TelegramRequestPolicy.parseResponse(200, body)
                fail("Invalid JSON object accepted")
            } catch (error: TelegramPairingException) { assertEquals("invalid_response", error.code) }
        }
        for (body in listOf("{}", "{\"happ\":{}}", "{\"happ\":{\"available\":true}}", "{\"happ\":{\"available\":true,\"sub_url\":null}}")) {
            try {
                TelegramRequestPolicy.subscriptionUrl(JSONObject(body))
                fail("Incomplete active subscription accepted")
            } catch (error: TelegramPairingException) { assertEquals("invalid_response", error.code) }
        }
        assertNull(TelegramRequestPolicy.subscriptionUrl(JSONObject("{\"happ\":{\"available\":false}}")))
    }

    @Test fun unauthorizedHtmlStillRequestsReauthentication() {
        try {
            TelegramRequestPolicy.parseResponse(401, "<html>unauthorized</html>")
            fail("Unauthorized accepted")
        } catch (error: TelegramPairingException) {
            assertEquals("session_expired", error.code)
            assertEquals(401, error.statusCode)
        }
    }

    @Test fun diagnosticReferenceCannotContainSensitiveMessagesOrStages() {
        val error = UnknownHostException("https://private.example/sub/token/secret")
        assertEquals("download/dns", SubscriptionLoadDiagnostics.reference("secret", error))
        assertEquals("account/http_503", SubscriptionLoadDiagnostics.reference("account", TelegramPairingException("secret", 503)))
    }
}
