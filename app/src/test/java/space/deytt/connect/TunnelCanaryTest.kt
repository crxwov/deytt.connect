package space.deytt.connect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelCanaryTest {
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
}
