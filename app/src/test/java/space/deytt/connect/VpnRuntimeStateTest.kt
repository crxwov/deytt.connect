package space.deytt.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnRuntimeStateTest {
    @Test
    fun queuedConnectResumesOnlyForItsUnchangedIdleRouteAndIntent() {
        assertTrue(VpnControlDecision.canResumeQueuedStart("openflux:DE", "openflux:DE", 4L, 4L, false, false))
        assertFalse(VpnControlDecision.canResumeQueuedStart("openflux:DE", "route:IT:VLESS", 4L, 4L, false, false))
        assertFalse(VpnControlDecision.canResumeQueuedStart("openflux:DE", "openflux:DE", 4L, 5L, false, false))
        assertFalse(VpnControlDecision.canResumeQueuedStart("openflux:DE", "openflux:DE", 4L, 4L, true, false))
        assertFalse(VpnControlDecision.canResumeQueuedStart("openflux:DE", "openflux:DE", 4L, 4L, false, true))
    }

    @Test
    fun staleConnectedCopyNeverTurnsTapIntoStop() {
        assertEquals(
            VpnControlAction.START,
            VpnControlDecision.decide(VpnPhase.CONNECTED, libboxRunning = false, awgRunning = false),
        )
        assertEquals(
            VpnPhase.IDLE,
            VpnControlDecision.effectivePhase(VpnPhase.CONNECTED, libboxRunning = false, awgRunning = false),
        )
    }

    @Test
    fun runningEngineAlwaysTurnsTapIntoStop() {
        assertEquals(
            VpnControlAction.STOP,
            VpnControlDecision.decide(VpnPhase.ERROR, libboxRunning = true, awgRunning = false),
        )
        assertEquals(
            VpnControlAction.STOP,
            VpnControlDecision.decide(VpnPhase.IDLE, libboxRunning = false, awgRunning = true),
        )
    }

    @Test
    fun connectedStateWithoutAndroidVpnTransportIsReconciledAsIdle() {
        assertEquals(
            VpnPhase.IDLE,
            VpnControlDecision.effectivePhase(
                VpnPhase.CONNECTED,
                libboxRunning = true,
                awgRunning = false,
                systemTunnelActive = false,
            ),
        )
    }

    @Test
    fun connectingPhaseIsNotClearedBeforeAndroidRegistersTheTunnel() {
        assertEquals(
            VpnPhase.CHECKING,
            VpnControlDecision.effectivePhase(
                VpnPhase.CHECKING,
                libboxRunning = true,
                awgRunning = false,
                systemTunnelActive = false,
            ),
        )
    }
}
