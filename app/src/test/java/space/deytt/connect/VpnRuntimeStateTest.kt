package space.deytt.connect

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnRuntimeStateTest {
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
