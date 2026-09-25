package space.deytt.connect

/** Keeps map traffic motion tied to recent device byte changes while the VPN is active. */
internal class TrafficActivityWindow(private val gracePeriodMs: Long) {
    private var previousRxBytes = -1L
    private var previousTxBytes = -1L
    private var activeUntilElapsedMs = 0L

    init {
        require(gracePeriodMs > 0L)
    }

    fun observe(rxBytes: Long, txBytes: Long, nowElapsedMs: Long): Boolean {
        if (rxBytes < 0L || txBytes < 0L) {
            reset()
            return false
        }
        if (previousRxBytes >= 0L && previousTxBytes >= 0L &&
            (rxBytes > previousRxBytes || txBytes > previousTxBytes)
        ) {
            activeUntilElapsedMs = nowElapsedMs + gracePeriodMs
        }
        previousRxBytes = rxBytes
        previousTxBytes = txBytes
        return nowElapsedMs < activeUntilElapsedMs
    }

    fun reset() {
        previousRxBytes = -1L
        previousTxBytes = -1L
        activeUntilElapsedMs = 0L
    }
}
