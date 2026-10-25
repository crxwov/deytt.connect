package space.deytt.connect

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupAnimationViewTest {
    @Test
    fun systemSplashWaitsForAlignmentAndHoldWithoutExtendingLateReady() {
        assertEquals(1_320L, StartupAnimationView.systemSplashExitDelayMillis(null))
        assertEquals(1_220L, StartupAnimationView.systemSplashExitDelayMillis(100L))
        assertEquals(0L, StartupAnimationView.systemSplashExitDelayMillis(1_320L))
        assertEquals(0L, StartupAnimationView.systemSplashExitDelayMillis(1_600L))
    }
}
