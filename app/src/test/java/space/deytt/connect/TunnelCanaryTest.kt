package space.deytt.connect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

class TunnelCanaryTest {
    @Test
    fun replacedVpnIsNeverUsedToVerifyNewConnection() {
        assertEquals("new-vpn", TunnelCanary.currentNetwork(listOf("old-vpn", "new-vpn"), setOf("old-vpn")))
        assertNull(TunnelCanary.currentNetwork(listOf("old-vpn"), setOf("old-vpn")))
        assertNull(TunnelCanary.currentNetwork(emptyList<String>(), setOf("old-vpn")))
    }

    @Test
    fun transportProbeRequiresSuccessfulHttpResponse() {
        assertTrue(TunnelCanary.acceptsHttpResponse(200, null))
        assertTrue(TunnelCanary.acceptsHttpResponse(204, null))
        assertFalse(TunnelCanary.acceptsHttpResponse(301, null))
        assertFalse(TunnelCanary.acceptsHttpResponse(403, null))
        assertFalse(TunnelCanary.acceptsHttpResponse(429, null))
        assertFalse(TunnelCanary.acceptsHttpResponse(-1, null))
    }

    @Test
    fun connectivityProbeCanRequireExactStatus() {
        assertTrue(TunnelCanary.acceptsHttpResponse(204, 204))
        assertFalse(TunnelCanary.acceptsHttpResponse(200, 204))
        assertFalse(TunnelCanary.acceptsHttpResponse(503, 204))
    }

    @Test
    fun oneUnavailableProviderDoesNotRejectWorkingVpn() {
        val attempted = mutableListOf<String>()
        TunnelCanary.verifyAny(checkpoint = {}) { endpoint ->
            attempted += endpoint.url
            if (attempted.size == 1) throw TunnelCanary.HttpFailure(403)
        }
        assertEquals(TunnelCanary.endpoints.take(2).map { it.url }, attempted)
    }

    @Test
    fun allEndpointsMustFailBeforeRejectingTunnel() {
        var attempts = 0
        val failure = IOException("offline")
        try {
            TunnelCanary.verifyAny(checkpoint = {}) {
                attempts++
                throw failure
            }
            throw AssertionError("An offline tunnel was accepted")
        } catch (error: IOException) {
            assertSame(failure, error)
        }
        assertEquals(TunnelCanary.endpoints.size, attempts)
    }

    @Test
    fun cancellationDoesNotTryAnotherEndpoint() {
        var attempts = 0
        val cancelled = CancellationException("cancelled")
        try {
            TunnelCanary.verifyAny(checkpoint = {}) {
                attempts++
                throw cancelled
            }
            throw AssertionError("Cancellation was ignored")
        } catch (error: CancellationException) {
            assertSame(cancelled, error)
        }
        assertEquals(1, attempts)
    }

    @Test
    fun stoppedGenerationCannotAcceptLateSuccess() {
        var checkpoints = 0
        try {
            TunnelCanary.verifyAny(checkpoint = {
                if (++checkpoints == 2) throw CancellationException("stopped")
            }) { }
            throw AssertionError("A stopped generation was accepted")
        } catch (_: CancellationException) { }
        assertEquals(2, checkpoints)
    }
}
