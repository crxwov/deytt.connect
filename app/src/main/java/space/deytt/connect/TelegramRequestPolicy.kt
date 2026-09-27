package space.deytt.connect

import java.io.IOException
import org.json.JSONObject

/** Retry reads only: a lost POST response must not send another code, payment or message. */
internal object TelegramRequestPolicy {
    fun <T> execute(method: String, budget: SubscriptionRequestBudget, operation: () -> T): T {
        repeat(SubscriptionRetryPolicy.MAX_ATTEMPTS) { attempt ->
            budget.check()
            try {
                return operation()
            } catch (error: IOException) {
                val transportError = if (error is TelegramPairingException && error.statusCode > 0) {
                    SubscriptionHttpFailure(error.statusCode, "Telegram request failed")
                } else error
                if (method != "GET" || attempt == SubscriptionRetryPolicy.MAX_ATTEMPTS - 1 ||
                    !SubscriptionRetryPolicy.shouldRetry(transportError)) throw error
                budget.check()
                val serverDelay = (error as? TelegramPairingException)?.retryAfterMillis ?: 0
                if (serverDelay > 5_000 || serverDelay >= budget.remainingMillis()) throw error
                try {
                    Thread.sleep(minOf(maxOf(SubscriptionRetryPolicy.delayMillis(attempt), serverDelay), budget.remainingMillis().toLong()))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw SubscriptionCancelledException()
                }
            }
        }
        throw SubscriptionDeadlineException()
    }

    fun parseResponse(status: Int, body: String, retryAfter: String? = null): JSONObject {
        val response = runCatching { JSONObject(body) }.getOrNull()
        if (status !in 200..299) {
            throw TelegramPairingException(
                response?.optString("error")?.takeIf { it.isNotBlank() } ?: if (status == 401) "session_expired" else "request_failed",
                status,
                SubscriptionClient.parseRetryAfter(retryAfter),
            )
        }
        return response ?: throw TelegramPairingException("invalid_response")
    }

    fun subscriptionUrl(response: JSONObject): String? {
        val happ = response.optJSONObject("happ") ?: throw TelegramPairingException("invalid_response")
        val available = happ.opt("available") as? Boolean ?: throw TelegramPairingException("invalid_response")
        if (!available) return null
        return happ.optString("sub_url").takeIf { it.isNotBlank() && it != "null" }
            ?: throw TelegramPairingException("invalid_response")
    }
}
