package space.deytt.connect

import java.net.URLEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionImportLinkTest {
    private val action = "android.intent.action.VIEW"
    private val url = "https://deytt.space/sub/token/example?format=singbox"
    private fun link(value: String, parameter: String = "url") =
        "deytt.connect://import?$parameter=${URLEncoder.encode(value, "UTF-8")}"

    @Test fun acceptsBothSupportedParametersAndPreservesEncodedToken() {
        assertEquals(url, SubscriptionImportLink.parse(action, link(url)))
        assertEquals(url, SubscriptionImportLink.parse(action, link(url, "subscription")))
        val encodedToken = "https://nl.deytt.space/sub/token/a%2Bb?key=a%26b"
        assertEquals(encodedToken, SubscriptionImportLink.parse(action, link(encodedToken)))
    }

    @Test fun malformedAndOpaqueLinksAreRejectedWithoutThrowing() {
        listOf("deytt.connect:opaque", "deytt.connect://import?url=%", "not a uri", "").forEach {
            assertNull(SubscriptionImportLink.parse(action, it))
        }
        assertNull(SubscriptionImportLink.parse(action, null))
        assertNull(SubscriptionImportLink.parse(action, link(url) + "x".repeat(8192)))
    }

    @Test fun rejectsWrongActionAndUnexpectedOuterTargets() {
        assertNull(SubscriptionImportLink.parse("android.intent.action.MAIN", link(url)))
        listOf("https://import", "deytt.connect://other", "deytt.connect://import/other", "deytt.connect://user@import", "deytt.connect://import:443").forEach {
            assertNull(SubscriptionImportLink.parse(action, link(url).replace("deytt.connect://import", it)))
        }
        assertNull(SubscriptionImportLink.parse(action, link(url) + "#ignored"))
    }

    @Test fun rejectsAmbiguousAndUntrustedSubscriptionUrls() {
        assertNull(SubscriptionImportLink.parse(action, link(url) + "&subscription=" + URLEncoder.encode(url, "UTF-8")))
        assertNull(SubscriptionImportLink.parse(action, link(url) + "&url=" + URLEncoder.encode(url, "UTF-8")))
        listOf("http://deytt.space/sub/token/x", "https://deytt.space.evil.test/sub/token/x", "https://user@deytt.space/sub/token/x", "https://deytt.space:444/sub/token/x", "file:///tmp/profile", "https://deytt.space/sub/token/x#fragment").forEach {
            assertNull(SubscriptionImportLink.parse(action, link(it)))
        }
    }
}
