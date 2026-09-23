package space.deytt.connect

/** Keep transport internals out of the import screen while retaining a useful retry hint. */
object SubscriptionErrorText {
    fun userMessage(error: Throwable): String {
        val raw = errorChainMessage(error)
        return when {
            raw.contains("unexpected end of stream", ignoreCase = true) ||
                raw.contains("connection reset", ignoreCase = true) ->
                "Сервер оборвал соединение. Проверьте ссылку и повторите обновление."
            raw.contains("timeout", ignoreCase = true) ->
                "Сервер не ответил вовремя. Повторите обновление через несколько секунд."
            error is SubscriptionHttpFailure && error.statusCode in 502..504 ->
                "Сервер подписки временно недоступен. Повторите обновление через несколько секунд."
            else -> raw.takeIf { it.isNotBlank() } ?: "Не удалось обновить подписку"
        }
    }

    private fun errorChainMessage(error: Throwable): String {
        val messages = buildList {
            var current: Throwable? = error
            repeat(8) {
                current?.message?.takeIf(String::isNotBlank)?.let(::add)
                current = current?.cause
            }
        }
        return messages.joinToString("; ")
    }
}
