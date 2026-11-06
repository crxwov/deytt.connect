package space.deytt.connect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePageRefreshGateTest {
    @Test
    fun refreshWaitsForActiveAndQueuedDiagnosticsThenRunsOnce() {
        val gate = RoutePageRefreshGate()

        assertFalse(gate.request(shouldDefer = true))
        assertFalse(gate.takeIfReady(shouldDefer = true))
        assertTrue(gate.takeIfReady(shouldDefer = false))
        assertFalse(gate.takeIfReady(shouldDefer = false))
    }

    @Test
    fun idleRefreshIsImmediateAndDoesNotLeaveDeferredWork() {
        val gate = RoutePageRefreshGate()

        assertFalse(gate.request(shouldDefer = true))
        assertTrue(gate.request(shouldDefer = false))
        assertFalse(gate.takeIfReady(shouldDefer = false))
    }
}
