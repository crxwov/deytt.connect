package space.deytt.connect

import java.io.IOException
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Retry only failures that can be fixed by reopening the HTTP connection. */
object SubscriptionRetryPolicy {
    fun shouldRetry(error: IOException): Boolean =
        errorChain(error).any { it is SubscriptionHttpFailure && it.statusCode in TRANSIENT_HTTP_CODES } ||
            containsMessage(error, "unexpected end of stream") ||
            containsMessage(error, "connection reset") ||
            containsMessage(error, "timeout") ||
            containsMessage(error, "timed out")

    fun delayMillis(attempt: Int): Long = 350L * (attempt + 1).coerceIn(1, 3)

    /** Describe transport failures without ever including the bearer URL or response body. */
    fun safeFailureSummary(error: Throwable): String {
        val causes = errorChain(error)
        causes.filterIsInstance<SubscriptionHttpFailure>().firstOrNull()?.let {
            return "HTTP ${it.statusCode}"
        }
        return when {
            causes.any { it is SSLException } -> "ошибка защищённого соединения"
            causes.any { it is UnknownHostException } -> "сервер не найден"
            causes.any { it is SocketTimeoutException } || containsMessage(error, "timed out") ||
                containsMessage(error, "timeout") -> "тайм-аут"
            causes.any { it is EOFException } || containsMessage(error, "unexpected end of stream") ||
                containsMessage(error, "connection reset") -> "соединение прервано"
            causes.any { it is ConnectException } -> "сервер недоступен"
            else -> "сетевая ошибка"
        }
    }

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
