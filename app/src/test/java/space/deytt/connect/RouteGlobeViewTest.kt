package space.deytt.connect

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteGlobeViewTest {
    @Test
    fun mapsPersistedProfileIdsToTheirRoute() {
        assertEquals("de", RouteGlobeView.routeKeyFor("route:DE:VLESS"))
        assertEquals("nl", RouteGlobeView.routeKeyFor("route:NL:HYSTERIA"))
        assertEquals("fi", RouteGlobeView.routeKeyFor("route:FI:TROJAN"))
        assertEquals("ru", RouteGlobeView.routeKeyFor("route:RU:AWG31"))
        assertEquals("ru-de", RouteGlobeView.routeKeyFor("route:RU-DE:DOUBLE"))
        assertEquals("auto", RouteGlobeView.routeKeyFor("auto"))
    }
}
