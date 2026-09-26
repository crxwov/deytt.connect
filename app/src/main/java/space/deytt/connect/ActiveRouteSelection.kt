package space.deytt.connect

/** A country/route pair parsed only from sing-box outbound tags, never from IP geolocation. */
data class ActiveRouteSelection(val exitCountry: String, val routeKey: String) {
    companion object {
        private val directRoute = Regex("^route:(NL|DE|FI|RU)(?::|$)", RegexOption.IGNORE_CASE)

        fun fromOutboundTag(tag: String): ActiveRouteSelection? {
            val normalized = tag.trim()
            if (normalized.startsWith("route:RU-DE:", ignoreCase = true)) {
                return ActiveRouteSelection("DE", "ru-de")
            }
            val country = directRoute.find(normalized)?.groupValues?.get(1)?.uppercase() ?: return null
            val routeKey = when (country) {
                "NL" -> "nl"
                "DE" -> "de"
                "FI" -> "fi"
                "RU" -> "ru"
                else -> return null
            }
            return ActiveRouteSelection(country, routeKey)
        }
    }
}
