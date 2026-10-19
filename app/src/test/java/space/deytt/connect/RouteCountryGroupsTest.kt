package space.deytt.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RouteCountryGroupsTest {
    private val config = """
        {"outbounds":[
          {"type":"urltest","tag":"автоподбор"},
          {"type":"vless","tag":"route:DE:VLESS"},
          {"type":"vless","tag":"route:IT:VLESS"},
          {"type":"trojan","tag":"route:RU-DE:CHAIN"}
        ]}
    """.trimIndent()

    @Test
    fun countryPresentationKeepsItalyNetworkAndAmneziaProfiles() {
        val routes = RouteCatalog.from(config, listOf(
            AwgProfile("awg15:it", "15", "Italy", "IT", "valid-awg15"),
            AwgProfile("awg31:unknown", "31", "Unknown", "Unknown", "valid-awg31"),
        ))
        val groups = RouteCatalog.countryGroups(routes).toMap()

        assertEquals(listOf("DE", "IT", "AWG_UNKNOWN"), groups.keys.toList())
        assertEquals(listOf("route:IT:VLESS", "awg15:it"), groups.getValue("IT").map { it.id })
        assertFalse(groups.containsKey("AUTO"))
        assertFalse(groups.containsKey("RU-DE"))
        assertEquals(1, groups.getValue("AWG_UNKNOWN").size)
    }

    @Test
    fun doesNotInventItalyWhenSubscriptionHasNoItalyProfile() {
        val routes = RouteCatalog.from(config.replace("route:IT:VLESS", "unrelated"), emptyList())

        assertEquals(listOf("DE"), RouteCatalog.countryGroups(routes).map { it.first })
    }
}
