package space.deytt.connect

object SubscriptionHostPolicy {
    fun isAllowed(host: String?): Boolean {
        val normalized = host?.trim()?.trimEnd('.')?.lowercase() ?: return false
        return normalized == "deytt.space" || normalized.endsWith(".deytt.space")
    }
}
