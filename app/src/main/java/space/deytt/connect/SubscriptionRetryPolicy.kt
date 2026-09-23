package space.deytt.connect

import java.io.IOException

/** Retry only failures that can be fixed by reopening the HTTP connection. */
object SubscriptionRetryPolicy {
    fun shouldRetry(error: IOException): Boolean =
        errorChain(error).any { it is SubscriptionHttpFailure && it.statusCode in TRANSIENT_HTTP_CODES } ||
            containsMessage(error, "unexpected end of stream") ||
            containsMessage(error, "connection reset") ||
            containsMessage(error, "timeout") ||
            containsMessage(error, "timed out")

    fun delayMillis(attempt: Int): Long = 350L * (attempt + 1).coerceIn(1, 3)

    const val MAX_ATTEMPTS = 3
    private val TRANSIENT_HTTP_CODES = setOf(502, 503, 504)

    private fun containsMessage(error: Throwable, needle: String): Boolean {
        return errorChain(error).any { it.message.orEmpty().contains(needle, ignoreCase = true) }
    }

    private fun errorChain(error: Throwable): List<Throwable> = buildList {
        var current: Throwable? = error
        repeat(8) {
            current?.let(::add)
            current = current?.cause
        }
    }
}

class SubscriptionHttpFailure(
    val statusCode: Int,
    message: String,
) : IOException(message)
