package space.deytt.connect

import android.content.Context
import android.util.Log

/** Persist only allow-listed metadata. Never record an exception message, URL, account or body. */
internal object SubscriptionLoadDiagnostics {
    fun code(error: Throwable): String = when {
        error is TelegramPairingException && error.statusCode in 100..599 -> "http_${error.statusCode}"
        error is TelegramPairingException && error.code in setOf("invalid_response", "response_too_large") -> "invalid_response"
        else -> SubscriptionRetryPolicy.diagnosticCode(error)
    }

    fun reference(stage: String, error: Throwable): String {
        val safeStage = stage.takeIf { it in setOf("account", "download", "validate", "awg", "save", "pair") } ?: "download"
        return "$safeStage/${code(error)}"
    }

    fun record(context: Context, stage: String, error: Throwable): String {
        val reference = reference(stage, error)
        context.getSharedPreferences("subscription_diagnostics", Context.MODE_PRIVATE).edit()
            .putString("last_failure", reference)
            .putLong("time", System.currentTimeMillis())
            .putString("version", BuildConfig.VERSION_NAME)
            .apply()
        Log.w("SubscriptionLoad", "${BuildConfig.VERSION_NAME} $reference")
        return reference
    }

    fun userMessage(error: Throwable): String {
        if (error is TelegramPairingException) {
            return when (error.code) {
                "session_expired" -> "Сессия Telegram истекла. Войдите через Telegram снова."
                "invalid_response" -> "Сервер вернул неполный ответ. Повторите загрузку через несколько секунд."
                "response_too_large" -> "Ответ сервера слишком большой. Обратитесь в поддержку."
                else -> if (error.statusCode > 0) SubscriptionErrorText.userMessage(
                    SubscriptionHttpFailure(error.statusCode, "Ошибка запроса аккаунта", error.code),
                ) else "Не удалось получить данные аккаунта. Повторите попытку."
            }
        }
        return SubscriptionErrorText.userMessage(error)
    }
}
