package space.deytt.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveRouteSelectionTest {
    @Test
    fun mapsOnlyKnownFirstPartyOutboundTagsToRoutes() {
        assertEquals(ActiveRouteSelection("DE", "de"), ActiveRouteSelection.fromOutboundTag("route:DE:VLESS"))
        assertEquals(ActiveRouteSelection("NL", "nl"), ActiveRouteSelection.fromOutboundTag("route:NL"))
        assertEquals(ActiveRouteSelection("FI", "fi"), ActiveRouteSelection.fromOutboundTag("route:FI:TROJAN"))
        assertEquals(ActiveRouteSelection("RU", "ru"), ActiveRouteSelection.fromOutboundTag("route:RU:HYSTERIA2"))
        assertEquals(ActiveRouteSelection("DE", "ru-de"), ActiveRouteSelection.fromOutboundTag("route:RU-DE:CHAIN"))
        assertNull(ActiveRouteSelection.fromOutboundTag("unrelated outbound"))
    }
}
