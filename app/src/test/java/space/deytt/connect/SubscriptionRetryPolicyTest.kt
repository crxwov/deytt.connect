package space.deytt.connect

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRetryPolicyTest {
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
