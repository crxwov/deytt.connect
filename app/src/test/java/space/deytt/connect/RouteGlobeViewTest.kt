package space.deytt.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import android.view.MotionEvent

class RouteGlobeViewTest {
    @Test
    fun parentInterceptFollowsTouchOriginThroughTheWholeGesture() {
        assertEquals(true, RouteGlobeView.parentInterceptPolicy(MotionEvent.ACTION_DOWN, true))
        assertEquals(true, RouteGlobeView.parentInterceptPolicy(MotionEvent.ACTION_MOVE, true))
        assertEquals(true, RouteGlobeView.parentInterceptPolicy(MotionEvent.ACTION_POINTER_DOWN, true))
        assertEquals(true, RouteGlobeView.parentInterceptPolicy(MotionEvent.ACTION_POINTER_UP, true))
        assertEquals(false, RouteGlobeView.parentInterceptPolicy(MotionEvent.ACTION_UP, true))
        assertEquals(false, RouteGlobeView.parentInterceptPolicy(MotionEvent.ACTION_CANCEL, true))
        assertEquals(false, RouteGlobeView.parentInterceptPolicy(MotionEvent.ACTION_DOWN, false))
        assertEquals(false, RouteGlobeView.parentInterceptPolicy(MotionEvent.ACTION_MOVE, false))
        assertEquals(false, RouteGlobeView.parentInterceptPolicy(MotionEvent.ACTION_POINTER_DOWN, false))
        assertEquals(false, RouteGlobeView.parentInterceptPolicy(MotionEvent.ACTION_POINTER_UP, false))
        assertNull(RouteGlobeView.parentInterceptPolicy(MotionEvent.ACTION_HOVER_MOVE, false))
    }

    @Test
    fun visibleGlobeHitTestUsesCurrentScaleAndViewportCircle() {
        assertFalse(RouteGlobeView.isTouchInsideVisibleGlobe(500f, 500f, 1000, 1000, null))
        assertTrue(RouteGlobeView.isTouchInsideVisibleGlobe(500f, 500f, 1000, 1000, .405f))
        assertTrue(RouteGlobeView.isTouchInsideVisibleGlobe(890f, 500f, 1000, 1000, .405f))
        assertFalse(RouteGlobeView.isTouchInsideVisibleGlobe(910f, 500f, 1000, 1000, .405f))
        assertTrue(RouteGlobeView.isTouchInsideVisibleGlobe(890f, 500f, 1000, 1000, .45f))
        assertTrue(RouteGlobeView.isTouchInsideVisibleGlobe(0f, 0f, 1000, 1000, 13.76f))
        assertFalse(RouteGlobeView.isTouchInsideVisibleGlobe(0f, 0f, 0, 1000, .405f))
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
