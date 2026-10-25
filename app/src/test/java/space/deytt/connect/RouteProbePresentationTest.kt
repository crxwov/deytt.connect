package space.deytt.connect

import java.net.SocketTimeoutException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteProbePresentationTest {
    @Test
    fun speedStateKeepsTheHttpsResponseTimeThatWasAlreadyMeasured() {
        assertEquals(
            "Отклик: 500 мс · Скорость: —",
            RouteProbePresentation.speedProgress("500 мс", english = false),
        )
        assertEquals(
            "Response: 500 ms · Speed: —",
            RouteProbePresentation.speedProgress("500 ms", english = true),
        )
    }

    @Test
    fun speedStateCanStartBeforeResponseTimeHasArrived() {
        assertEquals("Отклик: — · Скорость: —", RouteProbePresentation.speedProgress(null, english = false))
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
            "Response: 500 ms · Speed: ↓ 8.0 Mbps · measuring…",
            RouteProbePresentation.speedProgress("500 ms", english = true, bytesPerSecond = 1_000_000L),
        )
    }

    @Test
    fun reportsExplicitRouteFailureMessages() {
        assertEquals("маршрут не ответил", RouteProbePresentation.failureMessage("маршрут не ответил", english = false))
        assertEquals("route did not respond", RouteProbePresentation.failureMessage("маршрут не ответил", english = true))
        assertEquals("сбой запуска проверки", RouteProbePresentation.failureMessage("Не удалось запустить диагностику", english = false))
        assertEquals("failed to start probe", RouteProbePresentation.failureMessage("Не удалось запустить диагностику", english = true))
    }

    @Test
    fun homeMetricKeyChangesWhenRouteSessionConfigAutoPathOrConnectionChanges() {
        val base = HomeRouteMetricKey("auto", "session-a", "tag-a", "de", "outbound-de-1", 1L)
        assertEquals(base, base.copy())
        assertEquals(false, base == base.copy(routeId = "nl"))
        assertEquals(false, base == base.copy(sessionToken = "session-b"))
        assertEquals(false, base == base.copy(configTag = "tag-b"))
        assertEquals(false, base == base.copy(activeAutoRouteKey = "nl"))
        assertEquals(false, base == base.copy(activeAutoOutboundTag = "outbound-de-2"))
        assertEquals(false, base == base.copy(connectionGeneration = 2L))
    }

    @Test
    fun importedProfilesExplainMissingSpeedAuthorizationOnlyWhenConnected() {
        val imported = HomeRouteMetricKey("auto", null, "tag-a", "de", "outbound-de-1", 1L)
        assertEquals(true, HomeMetricPresentation.speedNeedsLogin(true, imported))
        assertEquals(false, HomeMetricPresentation.speedNeedsLogin(false, imported))
        assertEquals(false, HomeMetricPresentation.speedNeedsLogin(true, imported.copy(sessionToken = "session-a")))
        assertEquals(false, HomeMetricPresentation.speedNeedsLogin(true, null))
    }

    @Test
    fun homeMetricValueIsVisibleOnlyForTheFreshConnectedRouteContext() {
        val current = HomeRouteMetricKey("auto", "session-a", "tag-a", "de", "outbound-de-1", 3L)
        assertEquals(42L, HomeMetricPresentation.value(true, current, current, 42L))
        assertNull(HomeMetricPresentation.value(false, current, current, 42L))
        assertNull(HomeMetricPresentation.value(true, current, current.copy(activeAutoOutboundTag = "outbound-de-2"), 42L))
        assertNull(HomeMetricPresentation.value(true, current, current.copy(connectionGeneration = 2L), 42L))
    }

    @Test
    fun splitTunnelBypassLabelIsClearInBothLanguages() {
        assertEquals("в обход vpn", HomeMetricPresentation.bypassLabel(english = false))
        assertEquals("vpn bypassed", HomeMetricPresentation.bypassLabel(english = true))
    }

    @Test
    fun onlyTheCurrentLatencyRequestCanReleaseItsInFlightState() {
        assertEquals(true, HomeMetricPresentation.ownsRequest(requestGeneration = 7, currentGeneration = 7))
        assertEquals(false, HomeMetricPresentation.ownsRequest(requestGeneration = 6, currentGeneration = 7))
    }
}
