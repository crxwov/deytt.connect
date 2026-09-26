package space.deytt.connect

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal object RouteProbePresentation {
    fun speedProgress(latency: String?, english: Boolean): String {
        val measuredPing = latency?.takeIf(String::isNotBlank)?.let {
            if (english) "Ping: $it\n" else "Задержка: $it\n"
        }.orEmpty()
        return if (english) measuredPing + "Speed test in progress…"
        else measuredPing + "Скорость измеряется…"
    }

    fun failure(error: Throwable, english: Boolean): String {
        val causes = generateSequence(error) { it.cause }.toList()
        return when {
            causes.any { it is SocketTimeoutException } ->
                if (english) "server timed out" else "сервер не ответил вовремя"
            causes.any { it is UnknownHostException } ->
                if (english) "server address could not be resolved" else "не удалось найти сервер"
            causes.any { it is IOException } ->
                if (english) "no response from server" else "нет ответа от сервера"
            else -> if (english) "speed unavailable" else "скорость недоступна"
        }
    }
}
