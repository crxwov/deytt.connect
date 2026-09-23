package space.deytt.connect

/** Keep transport internals out of the import screen while retaining a useful retry hint. */
object SubscriptionErrorText {
    fun userMessage(error: Throwable): String {
        val raw = errorChainMessage(error)
        val httpFailure = errorChain(error).filterIsInstance<SubscriptionHttpFailure>().firstOrNull()
        return when {
            raw.contains("unexpected end of stream", ignoreCase = true) ||
                raw.contains("connection reset", ignoreCase = true) ->
                "Сервер оборвал соединение. Проверьте ссылку и повторите обновление."
            raw.contains("timeout", ignoreCase = true) || raw.contains("timed out", ignoreCase = true) ->
                "Сервер не ответил вовремя. Повторите обновление через несколько секунд."
            httpFailure?.statusCode?.let { it in 502..504 } == true ->
                "Сервер подписки временно недоступен. Повторите обновление через несколько секунд."
            httpFailure?.statusCode == 401 || httpFailure?.statusCode == 403 ->
                "Ссылка на подписку недействительна или срок её действия истёк."
            httpFailure?.statusCode == 404 ->
                "Ссылка на подписку не найдена. Проверьте её и повторите попытку."
            raw.contains("официальный домен", ignoreCase = true) ->
                "Ссылка должна вести на официальный домен deytt.space."
            raw.contains("HTTPS", ignoreCase = true) ->
                "Нужна полная HTTPS-ссылка на подписку."
            else -> "Не удалось обновить подписку. Проверьте ссылку и повторите попытку."
        }
    }

    private fun errorChainMessage(error: Throwable): String {
        return errorChain(error).mapNotNull { it.message?.takeIf(String::isNotBlank) }.joinToString("; ")
    }

    private fun errorChain(error: Throwable): List<Throwable> = buildList {
        var current: Throwable? = error
        repeat(8) {
            current?.let(::add)
            current = current?.cause
        }
    }
}
