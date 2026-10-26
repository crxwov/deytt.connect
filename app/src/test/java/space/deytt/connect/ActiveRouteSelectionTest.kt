package space.deytt.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveRouteSelectionTest {
    private val automatic = SelectedRoute("auto", "auto-select", "", TunnelEngine.LIBBOX, "auto-group", protocol = RouteProtocol.AUTO)
    private val manual = SelectedRoute("route:DE:HYSTERIA2", "Germany", "", TunnelEngine.LIBBOX, "route:DE:HYSTERIA2", "DE", "🇩🇪", RouteProtocol.HYSTERIA2)
    private val activeRoute = DeyttRoute("route:DE:HYSTERIA2", "DE", "germany", "🇩🇪", RouteProtocol.HYSTERIA2, TunnelEngine.LIBBOX, "route:DE:HYSTERIA2")
    private val routes = listOf(
        DeyttRoute("auto", "AUTO", "auto-select", "✦", RouteProtocol.AUTO, TunnelEngine.LIBBOX, "auto-group"),
        activeRoute,
    )

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

    @Test
    fun resolvesExactActiveOutboundOnlyForConnectedAutoSelection() {
        assertEquals(activeRoute, ActiveRouteSelection.configuredAutoRoute(automatic, "route:DE:HYSTERIA2", routes, connected = true))
        assertNull(ActiveRouteSelection.configuredAutoRoute(automatic, null, routes, connected = true))
        assertNull(ActiveRouteSelection.configuredAutoRoute(automatic, "route:FI:TROJAN", routes, connected = true))
        assertNull(ActiveRouteSelection.configuredAutoRoute(automatic, "route:DE:HYSTERIA2", routes, connected = false))
        assertNull(ActiveRouteSelection.configuredAutoRoute(manual, "route:DE:HYSTERIA2", routes, connected = true))
    }

    @Test
    fun formatsAutoRouteAsCountryProtocolAndTransport() {
        val vless = activeRoute.copy(countryCode = "NL", protocol = RouteProtocol.VLESS)
        assertEquals("nl./vless+ws", ActiveRouteSelection.autoRouteLabel(vless))
        assertEquals("de./hysteria2", ActiveRouteSelection.autoRouteLabel(activeRoute))
    }
}
