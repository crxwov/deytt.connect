package space.deytt.connect

/** A recovered carrier stays unverified until a VPN-bound HTTPS probe succeeds. */
internal class OpenFluxReconnectGrace(private val graceMillis: Long = 30_000L) {
    enum class State { HEALTHY, WAIT, VERIFY, EXPIRED }

    private var firstDisconnectedElapsed: Long? = null

    init {
        require(graceMillis > 0)
    }

    val deadlineMillis: Long?
        get() = firstDisconnectedElapsed?.plus(graceMillis)

    fun observe(connected: Boolean, nowMillis: Long): State {
        if (firstDisconnectedElapsed == null) {
            if (connected) return State.HEALTHY
            firstDisconnectedElapsed = nowMillis
        }
        if (nowMillis >= deadlineMillis!!) return State.EXPIRED
        return if (connected) State.VERIFY else State.WAIT
    }

    /** Call only after HTTPS proof and the current operation/owner/lease checks. */
    fun confirmed() {
        firstDisconnectedElapsed = null
    }
}
