package space.deytt.connect

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale

internal data class HomeRouteMetricKey(
    val routeId: String,
    val sessionToken: String?,
    val configTag: String,
    val activeAutoRouteKey: String?,
    val activeAutoOutboundTag: String?,
    val connectionGeneration: Long,
)

internal object HomeMetricPresentation {
    fun speedNeedsLogin(connected: Boolean, key: HomeRouteMetricKey?): Boolean =
        connected && key != null && key.sessionToken.isNullOrBlank()

    fun bypassLabel(english: Boolean): String = if (english) "vpn bypassed" else "в обход vpn"

    fun ownsRequest(requestGeneration: Int, currentGeneration: Int): Boolean = requestGeneration == currentGeneration

    fun isCurrent(connected: Boolean, currentKey: HomeRouteMetricKey?, sampleKey: HomeRouteMetricKey?): Boolean =
        connected && currentKey != null && currentKey == sampleKey

    fun <T> value(
        connected: Boolean,
        currentKey: HomeRouteMetricKey?,
        sampleKey: HomeRouteMetricKey?,
        value: T?,
    ): T? = value.takeIf { isCurrent(connected, currentKey, sampleKey) }
}

internal object RouteProbePresentation {
    fun speedProgress(latency: String?, english: Boolean, bytesPerSecond: Long? = null): String {
        val measuredResponse = latency?.takeIf(String::isNotBlank)?.let {
            if (english) "Response: $it" else "Отклик: $it"
        } ?: if (english) "Response: —" else "Отклик: —"
        val separator = " · "
        val liveRate = bytesPerSecond?.takeIf { it > 0L }?.let { formatSpeed(it, english) }
        return when {
            english && liveRate != null -> measuredResponse + separator + "Speed: ↓ $liveRate · measuring…"
            english -> measuredResponse + separator + "Speed: —"
            liveRate != null -> measuredResponse + separator + "Скорость: ↓ $liveRate · замер…"
            else -> measuredResponse + separator + "Скорость: —"
        }
    }

    fun failure(error: Throwable, english: Boolean): String {
        val causes = generateSequence(error) { it.cause }.toList()
        val messages = causes.mapNotNull { it.message }
        return failureMessage(messages.joinToString(" "), english, causes)
    }

    fun failureMessage(message: String, english: Boolean): String = failureMessage(message, english, emptyList())

    private fun failureMessage(message: String, english: Boolean, causes: List<Throwable>): String {
        val status = HTTP_STATUS.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return when {
            status == 401 || message.contains("отклонил вход", ignoreCase = true) ->
                if (english) "Reconnect your Telegram account to measure speed" else "Обновите вход через Telegram для замера скорости"
            status == 403 || message.contains("запретил замер", ignoreCase = true) ->
                if (english) "Speed test access was denied" else "Сервер не разрешил замер скорости"
            (status != null && status >= 500) || message.contains("временно недоступен", ignoreCase = true) ->
                if (english) "Speed server is temporarily unavailable" else "Сервер скорости временно недоступен"
            causes.any { it is SocketTimeoutException } || message.contains("тайм-аут", ignoreCase = true) ||
                message.contains("timed out", ignoreCase = true) ->
                if (english) "server timed out" else "сервер не ответил вовремя"
            causes.any { it is UnknownHostException } || message.contains("unknownhost", ignoreCase = true) ->
                if (english) "server address could not be resolved" else "не удалось найти сервер"
            causes.any { it is IOException } || message.contains("http", ignoreCase = true) ->
                if (english) "no response from server" else "нет ответа от сервера"
            message.contains("не ответил", ignoreCase = true) ||
                message.contains("did not respond", ignoreCase = true) ->
                if (english) "route did not respond" else "маршрут не ответил"
            message.contains("не удалось запустить", ignoreCase = true) ||
                message.contains("сбой запуска", ignoreCase = true) ->
                if (english) "failed to start probe" else "сбой запуска проверки"
            else -> if (english) "speed unavailable" else "скорость недоступна"
        }
    }

    fun formatSpeed(bytesPerSecond: Long, english: Boolean): String {
        val bitsPerSecond = bytesPerSecond.coerceAtLeast(0L) * 8.0
        return if (bitsPerSecond >= 1_000_000.0) {
            String.format(Locale.US, "%.1f Mbps", bitsPerSecond / 1_000_000.0)
        } else {
            String.format(Locale.US, "%.0f Kbps", bitsPerSecond / 1_000.0)
        }.let { if (english) it else it.replace("Mbps", "Мбит/с").replace("Kbps", "Кбит/с") }
    }

    private val HTTP_STATUS = Regex("HTTP\\s+(\\d{3})", RegexOption.IGNORE_CASE)
}
