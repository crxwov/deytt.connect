package space.deytt.connect

import org.junit.Assert.*
import org.junit.Test

class SubscriptionRequestIdentityTest {
    @Test fun identityDoesNotChangeWhenAccountSessionChanges() {
        val first = SubscriptionRequestIdentity.buildHeaders("install-id", "session-a", "Phone", "15")
        val second = SubscriptionRequestIdentity.buildHeaders("install-id", "session-b", "Phone", "15")
        assertEquals(first["X-HWID"], second["X-HWID"])
        assertEquals("session-b", second["X-TG-App-Token"])
        assertEquals("deytt-connect", first["X-Deytt-Client"])
    }

    @Test fun unauthenticatedImportDoesNotClaimAnAuthenticatedSession() {
        val headers = SubscriptionRequestIdentity.buildHeaders("install-id", null, "Phone\r\nInjected: value", "15")
        assertFalse(headers.containsKey("X-TG-App-Token"))
        assertFalse(headers.getValue("X-Device-Model").contains('\n'))
        assertEquals("install-id", headers["X-HWID"])
    }
}
