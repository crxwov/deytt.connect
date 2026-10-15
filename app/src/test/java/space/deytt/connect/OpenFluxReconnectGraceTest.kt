package space.deytt.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenFluxReconnectGraceTest {
    @Test
    fun healthyCarrierDoesNotStartDeadline() {
        val grace = OpenFluxReconnectGrace()
        assertEquals(OpenFluxReconnectGrace.State.HEALTHY, grace.observe(true, 0))
        assertEquals(OpenFluxReconnectGrace.State.HEALTHY, grace.observe(true, 60_000))
        assertNull(grace.deadlineMillis)
    }

    @Test
    fun lossAtZeroHasAnInclusiveThirtySecondDeadline() {
        val grace = OpenFluxReconnectGrace()
        assertEquals(OpenFluxReconnectGrace.State.WAIT, grace.observe(false, 0))
        assertEquals(30_000L, grace.deadlineMillis)
        assertEquals(OpenFluxReconnectGrace.State.WAIT, grace.observe(false, 29_999))
        assertEquals(OpenFluxReconnectGrace.State.EXPIRED, grace.observe(false, 30_000))
        assertEquals(OpenFluxReconnectGrace.State.EXPIRED, grace.observe(true, 30_001))
    }

    @Test
    fun recoveredCarrierRequiresProofWithoutResettingDeadline() {
        val grace = OpenFluxReconnectGrace()
        grace.observe(false, 100)
        assertEquals(OpenFluxReconnectGrace.State.VERIFY, grace.observe(true, 200))
        assertEquals(OpenFluxReconnectGrace.State.VERIFY, grace.observe(true, 30_099))
        assertEquals(30_100L, grace.deadlineMillis)
        assertEquals(OpenFluxReconnectGrace.State.EXPIRED, grace.observe(true, 30_100))
    }

    @Test
    fun flappingNeverRestartsGrace() {
        val grace = OpenFluxReconnectGrace()
        grace.observe(false, 5_000)
        assertEquals(OpenFluxReconnectGrace.State.VERIFY, grace.observe(true, 10_000))
        assertEquals(OpenFluxReconnectGrace.State.WAIT, grace.observe(false, 20_000))
        assertEquals(OpenFluxReconnectGrace.State.VERIFY, grace.observe(true, 30_000))
        assertEquals(35_000L, grace.deadlineMillis)
        assertEquals(OpenFluxReconnectGrace.State.EXPIRED, grace.observe(false, 35_000))
    }

    @Test
    fun confirmedProofResetsGraceForTheNextOutage() {
        val grace = OpenFluxReconnectGrace()
        grace.observe(false, 0)
        assertEquals(OpenFluxReconnectGrace.State.VERIFY, grace.observe(true, 1_000))
        grace.confirmed()
        assertNull(grace.deadlineMillis)
        assertEquals(OpenFluxReconnectGrace.State.HEALTHY, grace.observe(true, 2_000))
        assertEquals(OpenFluxReconnectGrace.State.WAIT, grace.observe(false, 40_000))
        assertEquals(70_000L, grace.deadlineMillis)
    }
}
