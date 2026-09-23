package space.deytt.connect

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteLatencyTest {
    @Test
    fun resolvesConcreteAndNestedRouteTargets() {
        val config = """
            {"outbounds":[
              {"type":"selector","tag":"route:DE","outbounds":["route:DE:VLESS"]},
              {"type":"vless","tag":"route:DE:VLESS","server":"de.example.com","server_port":443}
            ]}
        """.trimIndent()
        val route = DeyttRoute("de", "DE", "Германия", "🇩🇪", RouteProtocol.VLESS, TunnelEngine.LIBBOX, "route:DE")

        assertEquals(LatencyTarget("de.example.com", 443), RouteLatency.target(config, route))
    }

    @Test
    fun parsesIpv4AndIpv6AmneziaEndpoints() {
        val route = DeyttRoute("awg", "AWG", "Основной", "◈", RouteProtocol.AWG31, TunnelEngine.AMNEZIAWG)

        assertEquals(
            LatencyTarget("vpn.example.com", 51820),
            RouteLatency.target("{}", route, "[Peer]\nEndpoint = vpn.example.com:51820"),
        )
        assertEquals(
            LatencyTarget("2001:db8::1", 443),
            RouteLatency.target("{}", route, "[Peer]\nEndpoint = [2001:db8::1]:443"),
        )
    }
}
