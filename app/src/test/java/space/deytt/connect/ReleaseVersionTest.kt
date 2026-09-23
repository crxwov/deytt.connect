package space.deytt.connect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionTest {
    @Test
    fun comparesStableVersionsAndRejectsDowngrades() {
        assertTrue(ReleaseVersion.isNewer("v0.8.0", "0.7.0"))
        assertFalse(ReleaseVersion.isNewer("v0.6.0-debug", "0.7.0"))
        assertFalse(ReleaseVersion.isNewer("not-a-version", "0.7.0"))
    }

    @Test
    fun stableReleaseWinsOverSameVersionPrerelease() {
        assertTrue(ReleaseVersion.isNewer("1.0.0", "1.0.0-debug"))
        assertFalse(ReleaseVersion.isNewer("1.0.0-debug", "1.0.0"))
    }
}
