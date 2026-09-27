package space.deytt.connect

import java.net.SocketTimeoutException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteProbePresentationTest {
    @Test
    fun speedStateKeepsThePingThatWasAlreadyMeasured() {
        assertEquals(
            "Пинг: 500 мс · Скорость: замер…",
            RouteProbePresentation.speedProgress("500 мс", english = false),
        )
        assertEquals(
            "Ping: 500 ms · Speed: sampling…",
            RouteProbePresentation.speedProgress("500 ms", english = true),
        )
    }

    @Test
    fun speedStateCanStartBeforePingHasArrived() {
        assertEquals("Пинг: — · Скорость: замер…", RouteProbePresentation.speedProgress(null, english = false))
    }

    @Test
    fun timeoutIsReportedAsAUsefulCauseInsteadOfUnavailable() {
        assertEquals("сервер не ответил вовремя", RouteProbePresentation.failure(SocketTimeoutException(), english = false))
    }

    @Test
    fun expiredTelegramSessionIsExplainedForSpeedFailures() {
        assertEquals(
            "Обновите вход через Telegram для замера скорости",
            RouteProbePresentation.failure(IOException("Сервер скорости ответил HTTP 401"), english = false),
        )
    }

    @Test
    fun speedProgressShowsLiveThroughputWithoutDroppingLatency() {
        assertEquals(
            "Ping: 500 ms · Speed: ↓ 8.0 Mbps · measuring…",
            RouteProbePresentation.speedProgress("500 ms", english = true, bytesPerSecond = 1_000_000L),
        )
    }
}
