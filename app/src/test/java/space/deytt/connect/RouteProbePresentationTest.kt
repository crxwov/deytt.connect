package space.deytt.connect

import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteProbePresentationTest {
    @Test
    fun speedStateKeepsThePingThatWasAlreadyMeasured() {
        assertEquals(
            "Задержка: 500 мс\nСкорость измеряется…",
            RouteProbePresentation.speedProgress("500 мс", english = false),
        )
        assertEquals(
            "Ping: 500 ms\nSpeed test in progress…",
            RouteProbePresentation.speedProgress("500 ms", english = true),
        )
    }

    @Test
    fun speedStateCanStartBeforePingHasArrived() {
        assertEquals("Скорость измеряется…", RouteProbePresentation.speedProgress(null, english = false))
    }

    @Test
    fun timeoutIsReportedAsAUsefulCauseInsteadOfUnavailable() {
        assertEquals("сервер не ответил вовремя", RouteProbePresentation.failure(SocketTimeoutException(), english = false))
    }
}
