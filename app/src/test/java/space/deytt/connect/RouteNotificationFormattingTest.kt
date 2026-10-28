package space.deytt.connect

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteNotificationFormattingTest {

    @Test
    fun flagsMatchSupportedCountries() {
        assertEquals("🇳🇱", RouteCatalog.flag("NL"))
        assertEquals("🇩🇪", RouteCatalog.flag("DE"))
        assertEquals("🇷🇺", RouteCatalog.flag("RU"))
        assertEquals("🇫🇮", RouteCatalog.flag("FI"))
        assertEquals("🇮🇹", RouteCatalog.flag("IT"))
        assertEquals("🇷🇺→🇩🇪", RouteCatalog.flag("RU-DE"))
        assertEquals("✦", RouteCatalog.flag("AUTO"))
        assertEquals("🌐", RouteCatalog.flag("UNKNOWN"))
    }

    @Test
    fun protocolNotificationLabelsFollowBypassNames() {
        assertEquals("auto", RouteProtocol.AUTO.bypassNotificationLabel)
        assertEquals("ru-de", RouteProtocol.RU_DE.bypassNotificationLabel)
        assertEquals("vless", RouteProtocol.VLESS.bypassNotificationLabel)
        assertEquals("trojan", RouteProtocol.TROJAN.bypassNotificationLabel)
        assertEquals("hysteria", RouteProtocol.HYSTERIA2.bypassNotificationLabel)
        assertEquals("tuic", RouteProtocol.TUIC.bypassNotificationLabel)
        assertEquals("awg 1.5", RouteProtocol.AWG15.bypassNotificationLabel)
        assertEquals("awg 3.1", RouteProtocol.AWG31.bypassNotificationLabel)
        assertEquals("openflux", RouteProtocol.OPENFLUX.bypassNotificationLabel)
    }

    @Test
    fun manualNotificationFormatMatchesSpecification() {
        fun formatManual(flag: String, countryCode: String, protocol: RouteProtocol): String {
            val resolvedFlag = flag.ifBlank { RouteCatalog.flag(countryCode) }
            val country = countryCode.lowercase()
            val label = protocol.bypassNotificationLabel
            return "$resolvedFlag ./$country $label"
        }

        assertEquals("🇩🇪 ./de hysteria", formatManual("🇩🇪", "DE", RouteProtocol.HYSTERIA2))
        assertEquals("🇳🇱 ./nl tuic", formatManual("🇳🇱", "NL", RouteProtocol.TUIC))
        assertEquals("🇳🇱 ./nl vless", formatManual("🇳🇱", "NL", RouteProtocol.VLESS))
        assertEquals("🇫🇮 ./fi trojan", formatManual("🇫🇮", "FI", RouteProtocol.TROJAN))
        assertEquals("🇷🇺 ./ru awg 1.5", formatManual("🇷🇺", "RU", RouteProtocol.AWG15))
        assertEquals("🇩🇪 ./de awg 3.1", formatManual("🇩🇪", "DE", RouteProtocol.AWG31))
        assertEquals("🇩🇪 ./de openflux", formatManual("🇩🇪", "DE", RouteProtocol.OPENFLUX))
    }

    @Test
    fun autoNotificationFormatMatchesSpecification() {
        fun formatAuto(route: ActiveRouteSelection?): String {
            return if (route != null) {
                val flag = RouteCatalog.flag(route.exitCountry)
                val country = route.routeKey.lowercase()
                "$flag ./auto $country"
            } else {
                "✦ ./auto"
            }
        }

        assertEquals("🇩🇪 ./auto de", formatAuto(ActiveRouteSelection("DE", "de")))
        assertEquals("🇳🇱 ./auto nl", formatAuto(ActiveRouteSelection("NL", "nl")))
        assertEquals("🇫🇮 ./auto fi", formatAuto(ActiveRouteSelection("FI", "fi")))
        assertEquals("🇷🇺 ./auto ru", formatAuto(ActiveRouteSelection("RU", "ru")))
        assertEquals("🇮🇹 ./auto it", formatAuto(ActiveRouteSelection("IT", "it")))
        assertEquals("✦ ./auto", formatAuto(null))
    }
}
