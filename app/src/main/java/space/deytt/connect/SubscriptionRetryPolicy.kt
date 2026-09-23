package space.deytt.connect

import java.io.IOException

/** Retry only failures that can be fixed by reopening the HTTP connection. */
object SubscriptionRetryPolicy {
    fun shouldRetry(error: IOException): Boolean =
        error is SubscriptionHttpFailure && error.statusCode in TRANSIENT_HTTP_CODES ||
            containsMessage(error, "unexpected end of stream") ||
            containsMessage(error, "connection reset") ||
            containsMessage(error, "timeout")

    fun delayMillis(attempt: Int): Long = 350L * (attempt + 1).coerceIn(1, 3)

    const val MAX_ATTEMPTS = 3
    private val TRANSIENT_HTTP_CODES = setOf(502, 503, 504)

    private fun containsMessage(error: Throwable, needle: String): Boolean {
        var current: Throwable? = error
        repeat(8) {
            if (current?.message.orEmpty().contains(needle, ignoreCase = true)) return true
            current = current?.cause
        }
        return false
    }
}

class SubscriptionHttpFailure(
    val statusCode: Int,
    message: String,
) : IOException(message)
