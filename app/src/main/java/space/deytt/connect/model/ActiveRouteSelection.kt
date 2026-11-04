package space.deytt.connect

/** A country/route pair parsed only from sing-box outbound tags, never from IP geolocation. */
data class ActiveRouteSelection(
    val exitCountry: String,
    val routeKey: String,
    val outboundTag: String = "",
) {
    companion object {
        private val directRoute = Regex("^route:(NL|DE|FI|RU|IT)(?::|$)", RegexOption.IGNORE_CASE)

        fun configuredAutoRoute(
            selected: SelectedRoute,
            outboundTag: String?,
            routes: List<DeyttRoute>,
            connected: Boolean,
        ): DeyttRoute? {
            if (!connected || selected.id != "auto" || selected.protocol != RouteProtocol.AUTO) return null
            val tag = outboundTag?.trim()?.takeIf(String::isNotEmpty) ?: return null
            return routes.firstOrNull { it.configTag == tag && it.protocol != RouteProtocol.AUTO }
        }

        fun autoRouteLabel(route: DeyttRoute): String {
            val protocol = route.protocol.title.lowercase(java.util.Locale.ROOT).replace(" ", "")
            val transport = when (route.protocol) {
                RouteProtocol.VLESS, RouteProtocol.TROJAN -> "+ws"
                else -> ""
            }
            return "${route.countryCode.lowercase(java.util.Locale.ROOT)}./$protocol$transport"
        }

        fun fromOutboundTag(tag: String): ActiveRouteSelection? {
            val normalized = tag.trim()
            if (normalized.startsWith("route:RU-DE:", ignoreCase = true)) {
                return ActiveRouteSelection("DE", "ru-de", normalized)
            }
            val country = directRoute.find(normalized)?.groupValues?.get(1)?.uppercase() ?: return null
            val routeKey = when (country) {
                "NL" -> "nl"
                "DE" -> "de"
                "FI" -> "fi"
                "RU" -> "ru"
                "IT" -> "it"
                else -> return null
            }
            return ActiveRouteSelection(country, routeKey, normalized)
        }
    }
}
