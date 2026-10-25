package space.deytt.connect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePageRefreshGateTest {
    @Test
    fun refreshWaitsForActiveAndQueuedDiagnosticsThenRunsOnce() {
        val gate = RoutePageRefreshGate()

        assertFalse(gate.request(busy = true))
        assertFalse(gate.takeIfReady(busy = true))
        assertTrue(gate.takeIfReady(busy = false))
        assertFalse(gate.takeIfReady(busy = false))
    }

    @Test
    fun idleRefreshIsImmediateAndDoesNotLeaveDeferredWork() {
        val gate = RoutePageRefreshGate()

        assertFalse(gate.request(busy = true))
        assertTrue(gate.request(busy = false))
        assertFalse(gate.takeIfReady(busy = false))
    }
}
