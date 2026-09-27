package space.deytt.connect

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Only retry transient failures; authorization, cancellation and trust failures win over nested causes. */
object SubscriptionRetryPolicy {
    fun shouldRetry(error: IOException): Boolean {
        val causes = errorChain(error)
        if (causes.any { it is SubscriptionCancelledException || it is SubscriptionDeadlineException }) return false
        causes.filterIsInstance<SubscriptionHttpFailure>().firstOrNull()?.let {
            return it.statusCode in TRANSIENT_HTTP_CODES
        }
        if (causes.any { it is CertificateException || it is SSLPeerUnverifiedException }) return false
        if (causes.any { it is SSLHandshakeException } && !isBrokenConnection(error)) return false
        if (causes.any { it is InterruptedIOException && it !is SocketTimeoutException }) return false
        if (causes.any { it is SSLException } && !isBrokenConnection(error)) return false
        return causes.any {
            it is UnknownHostException || it is ConnectException || it is NoRouteToHostException ||
                it is SocketTimeoutException || it is EOFException || it is SocketException
        } || isBrokenConnection(error) || containsMessage(error, "timeout") || containsMessage(error, "timed out")
    }

    fun delayMillis(attempt: Int): Long = 350L * (attempt + 1).coerceIn(1, 3)

    /** Stable diagnostic code: never includes host, URL, token, payload or exception text. */
    fun diagnosticCode(error: Throwable): String {
        val causes = errorChain(error)
        causes.filterIsInstance<SubscriptionHttpFailure>().firstOrNull()?.let { return "http_${it.statusCode}" }
        return when {
            causes.any { it is SubscriptionCancelledException || it is InterruptedException } -> "cancelled"
            causes.any { it is SubscriptionDeadlineException } -> "deadline"
            causes.any { it is CertificateException || it is SSLPeerUnverifiedException } -> "tls_trust"
            causes.any { it is SSLException } -> "tls"
            causes.any { it is UnknownHostException } -> "dns"
            causes.any { it is SocketTimeoutException } || containsMessage(error, "timed out") ||
                containsMessage(error, "timeout") -> "timeout"
            causes.any { it is ConnectException || it is NoRouteToHostException } -> "connect"
            causes.any { it is EOFException } || isBrokenConnection(error) -> "connection_interrupted"
            causes.any { it is SocketException } -> "connect"
            causes.any { it is SubscriptionPayloadException } -> "invalid_response"
            causes.any { it is SubscriptionStorageException } -> "storage"
            else -> "unknown"
        }
    }

    fun safeFailureSummary(error: Throwable): String {
        errorChain(error).filterIsInstance<SubscriptionHttpFailure>().firstOrNull()?.let { return "HTTP ${it.statusCode}" }
        return when (diagnosticCode(error)) {
            "tls", "tls_trust" -> "ошибка защищённого соединения"
            "dns" -> "сервер не найден"
            "timeout", "deadline" -> "тайм-аут"
            "connection_interrupted" -> "соединение прервано"
            "connect" -> "сервер недоступен"
            "cancelled" -> "загрузка отменена"
            "invalid_response" -> "некорректный ответ сервера"
            else -> "сетевая ошибка"
        }
    }

    const val MAX_ATTEMPTS = 3
    private val TRANSIENT_HTTP_CODES = setOf(408, 429, 500, 502, 503, 504)

    private fun isBrokenConnection(error: Throwable): Boolean =
        errorChain(error).any { it is EOFException || it is SocketException } ||
            containsMessage(error, "unexpected end of stream") || containsMessage(error, "connection reset")

    private fun containsMessage(error: Throwable, needle: String): Boolean =
        errorChain(error).any { it.message.orEmpty().contains(needle, ignoreCase = true) }

    private fun errorChain(error: Throwable): List<Throwable> = buildList {
        var current: Throwable? = error
        repeat(8) {
            val item = current ?: return@buildList
            if (any { it === item }) return@buildList
            add(item)
            current = item.cause
        }
    }
}

class SubscriptionHttpFailure(
    val statusCode: Int, message: String, val code: String = "", val retryAfterMillis: Long? = null,
) : IOException(message)
class SubscriptionCancelledException : InterruptedIOException("Загрузка отменена")
class SubscriptionDeadlineException : IOException("Время загрузки истекло")
class SubscriptionPayloadException : IOException("Сервер вернул некорректную подписку")

class SubscriptionStorageException : IOException("Не удалось сохранить подписку")
