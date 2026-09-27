package space.deytt.connect

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRetryPolicyTest {
    @Test
    fun retriesTypedTransientFailuresWithoutDependingOnMessages() {
        listOf(java.net.UnknownHostException(), java.net.ConnectException(), java.net.NoRouteToHostException(),
            java.io.EOFException(), java.net.SocketTimeoutException(), java.net.SocketException()).forEach {
            assertTrue(it.javaClass.simpleName, SubscriptionRetryPolicy.shouldRetry(it))
        }
        listOf(408, 429, 500, 502, 503, 504).forEach {
            assertTrue(SubscriptionRetryPolicy.shouldRetry(SubscriptionHttpFailure(it, "")))
        }
    }

    @Test
    fun trustCancellationAndAccessFailureOverrideNestedTransientCauses() {
        val tls = javax.net.ssl.SSLHandshakeException("connection reset")
        tls.initCause(java.security.cert.CertificateException("invalid certificate"))
        assertFalse(SubscriptionRetryPolicy.shouldRetry(tls))
        assertFalse(SubscriptionRetryPolicy.shouldRetry(javax.net.ssl.SSLPeerUnverifiedException("timeout")))
        assertFalse(SubscriptionRetryPolicy.shouldRetry(SubscriptionCancelledException()))
        assertFalse(SubscriptionRetryPolicy.shouldRetry(SubscriptionDeadlineException()))
        assertFalse(SubscriptionRetryPolicy.shouldRetry(java.io.InterruptedIOException("timeout")))
        val denied = SubscriptionHttpFailure(403, "timeout")
        denied.initCause(java.io.EOFException())
        assertFalse(SubscriptionRetryPolicy.shouldRetry(denied))
    }

    @Test
    fun transientTlsEofCanRetryButGenericHandshakeCannot() {
        val tls = javax.net.ssl.SSLHandshakeException("peer disappeared")
        tls.initCause(java.io.EOFException())
        assertTrue(SubscriptionRetryPolicy.shouldRetry(tls))
        assertFalse(SubscriptionRetryPolicy.shouldRetry(javax.net.ssl.SSLHandshakeException("handshake failed")))
    }

    @Test
    fun cyclicCauseIsBoundedAndDiagnosticsDoNotExposePayload() {
        val error = java.net.UnknownHostException("https://private.example/token/secret")
        val wrapper = java.io.IOException("secret", error)
        error.initCause(wrapper)
        assertTrue(SubscriptionRetryPolicy.shouldRetry(wrapper))
        assertTrue(SubscriptionRetryPolicy.diagnosticCode(wrapper) == "dns")
        assertFalse(SubscriptionRetryPolicy.safeFailureSummary(wrapper).contains("secret"))
    }

    @Test
    fun retriesGatewayFailuresAndBrokenKeepAliveStreams() {
        assertTrue(SubscriptionRetryPolicy.shouldRetry(SubscriptionHttpFailure(502, "bad gateway")))
        assertTrue(SubscriptionRetryPolicy.shouldRetry(IOException("unexpected end of stream")))
        assertTrue(SubscriptionRetryPolicy.shouldRetry(IOException("Connection reset by peer")))
        assertTrue(SubscriptionRetryPolicy.shouldRetry(IOException("wrapped", IOException("timeout"))))
    }

    @Test
    fun doesNotRetryPermanentResponses() {
        assertFalse(SubscriptionRetryPolicy.shouldRetry(SubscriptionHttpFailure(401, "unauthorized")))
        assertFalse(SubscriptionRetryPolicy.shouldRetry(IOException("certificate pin mismatch")))
    }

    @Test
    fun safeFailureSummaryKeepsHttpStatusAndNeverReturnsErrorText() {
        val summary = SubscriptionRetryPolicy.safeFailureSummary(
            SubscriptionHttpFailure(502, "private request URL must not be shown"),
        )

        assertTrue(summary == "HTTP 502")
        assertFalse(summary.contains("private"))
    }

    @Test
    fun safeFailureSummaryClassifiesTimeoutWithoutRawMessage() {
        assertTrue(SubscriptionRetryPolicy.safeFailureSummary(IOException("timeout")) == "тайм-аут")
    }

    @Test
    fun backoffIsBoundedAndIncreasing() {
        assertTrue(SubscriptionRetryPolicy.delayMillis(1) > SubscriptionRetryPolicy.delayMillis(0))
        assertTrue(SubscriptionRetryPolicy.delayMillis(8) <= 1_050L)
    }
}
