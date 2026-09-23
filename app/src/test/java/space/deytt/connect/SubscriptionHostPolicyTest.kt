package space.deytt.connect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionHostPolicyTest {
    @Test
    fun acceptsOnlyFirstPartyDomainAndSubdomains() {
        assertTrue(SubscriptionHostPolicy.isAllowed("deytt.space"))
        assertTrue(SubscriptionHostPolicy.isAllowed("sub.deytt.space"))
        assertTrue(SubscriptionHostPolicy.isAllowed("SUB.DEYTT.SPACE."))
        assertFalse(SubscriptionHostPolicy.isAllowed("example.com"))
        assertFalse(SubscriptionHostPolicy.isAllowed("deytt.space.example.com"))
        assertFalse(SubscriptionHostPolicy.isAllowed(null))
    }
}
