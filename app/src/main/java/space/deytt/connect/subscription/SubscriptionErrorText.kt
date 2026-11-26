package space.deytt.connect

/** Keep transport internals out of the import screen while retaining a useful retry hint. */
object SubscriptionErrorText {
    fun isAppDeviceSlotConflict(error: Throwable): Boolean = errorChain(error)
        .filterIsInstance<SubscriptionHttpFailure>()
        .any { it.statusCode == 409 && it.code == "app_device_limit_reached" }

    fun userMessage(error: Throwable): String {
        val raw = errorChainMessage(error)
        val diagnostic = SubscriptionRetryPolicy.diagnosticCode(error)
        val httpFailure = errorChain(error).filterIsInstance<SubscriptionHttpFailure>().firstOrNull()
        return when {
            httpFailure?.statusCode == 401 && httpFailure.code == "session_expired" ->
                "Сессия Telegram истекла. Войдите через Telegram снова, чтобы обновить подписку."
            httpFailure?.code == "subscription_inactive" ->
                "Подписка неактивна. Проверьте срок действия и тариф в Telegram."
            httpFailure?.code == "session_owner_mismatch" ->
                "Подписка принадлежит другому аккаунту. Войдите в нужный аккаунт Telegram."
            httpFailure?.code == "device_identity_required" ->
                "не удалось определить устройство. перезапустите приложение и повторите попытку."

            httpFailure?.statusCode == 409 && httpFailure.code == "app_device_limit_reached" ->
                "deytt.connect использует один слот на телефон. выйдите из приложения на старом телефоне, чтобы освободить слот, затем повторите проверку. компьютерные подключения и happ используют отдельные слоты. приложение не отключает устройство автоматически; если старый телефон недоступен или слот не освободился, обратитесь в поддержку."
            httpFailure?.statusCode == 409 ->
                "Достигнут лимит устройств. Войдите через Telegram для отдельного места deytt.connect или обратитесь в поддержку."
            httpFailure?.statusCode == 403 && httpFailure.code in setOf("blocked", "device_blocked", "user_blocked") ->
                "Доступ к подписке или этому устройству заблокирован. Обратитесь в поддержку."

            diagnostic == "cancelled" -> "Загрузка отменена. Повторите обновление, когда будете готовы."
            diagnostic == "dns" -> "Не удалось найти сервер подписки. Проверьте интернет и DNS или попробуйте другую сеть."
            diagnostic == "connect" -> "Не удалось подключиться к серверу подписки. Проверьте интернет или попробуйте другую сеть."
            diagnostic in setOf("tls", "tls_trust") ->
                "Не удалось установить защищённое соединение. Проверьте дату и время телефона или попробуйте другую сеть."
            diagnostic == "storage" -> "Не удалось сохранить подписку. Проверьте свободное место на телефоне и повторите обновление."
            diagnostic == "invalid_response" ->
                "Сервер вернул некорректную подписку. Повторите обновление позже или обратитесь в поддержку."
            httpFailure?.statusCode == 429 ->
                "Слишком много запросов. Подождите немного и повторите обновление."
            httpFailure?.statusCode in 300..399 ->
                "Сервер перенаправляет запрос. Получите актуальную ссылку через Telegram."
            diagnostic == "connection_interrupted" || raw.contains("unexpected end of stream", ignoreCase = true) ||
                raw.contains("connection reset", ignoreCase = true) ->
                "Сервер оборвал соединение. Проверьте ссылку и повторите обновление."
            diagnostic == "deadline" || raw.contains("timeout", ignoreCase = true) || raw.contains("timed out", ignoreCase = true) ->
                "Сервер не ответил вовремя. Повторите обновление через несколько секунд."
            httpFailure?.statusCode?.let { it == 408 || it == 500 || it in 502..504 } == true ->
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
