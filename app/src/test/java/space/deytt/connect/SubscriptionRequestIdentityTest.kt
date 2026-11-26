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
        assertFalse(headers.containsKey("X-Deytt-Client"))
        assertFalse(headers.getValue("X-Device-Model").contains('\n'))
        assertEquals("install-id", headers["X-HWID"])
    }

    @Test fun hardwareIdDerivationIsDeterministicForSameAndroidId() {
        val id1 = java.util.UUID.nameUUIDFromBytes("deytt-connect-android:test-android-id-123".toByteArray(Charsets.UTF_8)).toString()
        val id2 = java.util.UUID.nameUUIDFromBytes("deytt-connect-android:test-android-id-123".toByteArray(Charsets.UTF_8)).toString()
        val otherId = java.util.UUID.nameUUIDFromBytes("deytt-connect-android:different-device".toByteArray(Charsets.UTF_8)).toString()
        assertEquals(id1, id2)
        assertNotEquals(id1, otherId)
        assertTrue(id1.matches(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")))
    }
}
