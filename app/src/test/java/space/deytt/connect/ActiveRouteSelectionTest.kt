package space.deytt.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveRouteSelectionTest {
    @Test
    fun mapsOnlyKnownFirstPartyOutboundTagsToRoutes() {
        assertEquals(ActiveRouteSelection("DE", "de", "route:DE:VLESS"), ActiveRouteSelection.fromOutboundTag("route:DE:VLESS"))
        assertEquals(ActiveRouteSelection("NL", "nl", "route:NL"), ActiveRouteSelection.fromOutboundTag("route:NL"))
        assertEquals(ActiveRouteSelection("FI", "fi", "route:FI:TROJAN"), ActiveRouteSelection.fromOutboundTag("route:FI:TROJAN"))
        assertEquals(ActiveRouteSelection("RU", "ru", "route:RU:HYSTERIA2"), ActiveRouteSelection.fromOutboundTag("route:RU:HYSTERIA2"))
        assertEquals(ActiveRouteSelection("IT", "it", "route:IT:VLESS"), ActiveRouteSelection.fromOutboundTag("route:IT:VLESS"))
        assertEquals(ActiveRouteSelection("DE", "ru-de", "route:RU-DE:CHAIN"), ActiveRouteSelection.fromOutboundTag("route:RU-DE:CHAIN"))
        assertNull(ActiveRouteSelection.fromOutboundTag("unrelated outbound"))
    }
}
