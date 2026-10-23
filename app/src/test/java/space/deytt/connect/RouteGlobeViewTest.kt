package space.deytt.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import android.view.MotionEvent

class RouteGlobeViewTest {
    @Test
    fun parentInterceptIsReleasedForSingleFingerAndLockedOnlyForPinch() {
        assertNull(RouteGlobeView.parentInterceptPolicy(MotionEvent.ACTION_DOWN, 1))
        assertNull(RouteGlobeView.parentInterceptPolicy(MotionEvent.ACTION_MOVE, 1))
        assertNull(RouteGlobeView.parentInterceptPolicy(MotionEvent.ACTION_POINTER_DOWN, 1))
        assertEquals(true, RouteGlobeView.parentInterceptPolicy(MotionEvent.ACTION_POINTER_DOWN, 2))
        assertEquals(true, RouteGlobeView.parentInterceptPolicy(MotionEvent.ACTION_POINTER_UP, 3))
        assertEquals(false, RouteGlobeView.parentInterceptPolicy(MotionEvent.ACTION_POINTER_UP, 2))
        assertEquals(false, RouteGlobeView.parentInterceptPolicy(MotionEvent.ACTION_UP, 1))
        assertEquals(false, RouteGlobeView.parentInterceptPolicy(MotionEvent.ACTION_CANCEL, 1))
    }

    @Test
    fun mapsPersistedProfileIdsToTheirRoute() {
        assertEquals("de", RouteGlobeView.routeKeyFor("route:DE:VLESS"))
        assertEquals("de", RouteGlobeView.routeKeyFor("openflux:DE"))
        assertEquals("nl", RouteGlobeView.routeKeyFor("route:NL:HYSTERIA"))
        assertEquals("fi", RouteGlobeView.routeKeyFor("route:FI:TROJAN"))
        assertEquals("ru", RouteGlobeView.routeKeyFor("route:RU:AWG31"))
        assertEquals("it", RouteGlobeView.routeKeyFor("awg15:IT"))
        assertEquals("ru-de", RouteGlobeView.routeKeyFor("route:RU-DE:DOUBLE"))
        assertEquals("auto", RouteGlobeView.routeKeyFor("auto"))
    }
}
