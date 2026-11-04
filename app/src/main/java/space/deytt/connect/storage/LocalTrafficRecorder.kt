package space.deytt.connect

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Tracks real local network traffic transferred by DateConnect's VPN tunnel
 * and combines it with remote server stats (e.g. from Happ / subscription link).
 *
 * Persists cumulative total bytes and daily history locally so usage is never lost
 * across process restarts or device reboots.
 */
object LocalTrafficRecorder {
    private const val PREFS_NAME = "space.deytt.connect.local_traffic"
    private const val KEY_TOTAL_BYTES = "total_local_bytes"
    private const val KEY_LAST_RX = "last_rx_bytes"
    private const val KEY_LAST_TX = "last_tx_bytes"
    private const val KEY_DAILY_PREFIX = "daily_bytes_"
    private const val KEY_RECORDED_DAYS = "recorded_days_list"

    private val lock = Any()

    private fun todayKey(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    /**
     * Samples TrafficStats for the app UID and records any newly transferred bytes.
     * Call this periodically while VPN is active or on tunnel stop.
     */
    fun sample(context: Context): Long = synchronized(lock) {
        val currentRx = TrafficStats.getUidRxBytes(Process.myUid())
        val currentTx = TrafficStats.getUidTxBytes(Process.myUid())
        if (currentRx < 0L && currentTx < 0L) return 0L

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastRx = prefs.getLong(KEY_LAST_RX, -1L)
        val lastTx = prefs.getLong(KEY_LAST_TX, -1L)

        var delta = 0L
        if (lastRx >= 0L && currentRx >= lastRx) {
            delta += (currentRx - lastRx)
        } else if (lastRx >= 0L && currentRx < lastRx) {
            // Device rebooted, traffic stats reset
            delta += currentRx.coerceAtLeast(0L)
        }

        if (lastTx >= 0L && currentTx >= lastTx) {
            delta += (currentTx - lastTx)
        } else if (lastTx >= 0L && currentTx < lastTx) {
            delta += currentTx.coerceAtLeast(0L)
        }

        val today = todayKey()
        val currentTotal = prefs.getLong(KEY_TOTAL_BYTES, 0L)
        val todayBytes = prefs.getLong(KEY_DAILY_PREFIX + today, 0L)

        prefs.edit {
            putLong(KEY_LAST_RX, currentRx)
            putLong(KEY_LAST_TX, currentTx)
            if (delta > 0L) {
                putLong(KEY_TOTAL_BYTES, currentTotal + delta)
                putLong(KEY_DAILY_PREFIX + today, todayBytes + delta)
                // Add today to recorded days list
                val daysSet = prefs.getStringSet(KEY_RECORDED_DAYS, emptySet())?.toMutableSet() ?: mutableSetOf()
                if (!daysSet.contains(today)) {
                    daysSet.add(today)
                    putStringSet(KEY_RECORDED_DAYS, daysSet)
                }
            }
        }
        return delta
    }

    /**
     * Explicitly add transferred bytes (e.g. from tunnel callbacks).
     */
    fun recordBytes(context: Context, bytes: Long) = synchronized(lock) {
        if (bytes <= 0L) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = todayKey()
        val currentTotal = prefs.getLong(KEY_TOTAL_BYTES, 0L)
        val todayBytes = prefs.getLong(KEY_DAILY_PREFIX + today, 0L)

        val daysSet = prefs.getStringSet(KEY_RECORDED_DAYS, emptySet())?.toMutableSet() ?: mutableSetOf()
        daysSet.add(today)

        prefs.edit {
            putLong(KEY_TOTAL_BYTES, currentTotal + bytes)
            putLong(KEY_DAILY_PREFIX + today, todayBytes + bytes)
            putStringSet(KEY_RECORDED_DAYS, daysSet)
        }
    }

    /**
     * Total lifetime bytes recorded locally by DateConnect.
     */
    fun getTotalBytes(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_TOTAL_BYTES, 0L).coerceAtLeast(0L)
    }

    /**
     * Returns real recorded daily usage for up to [maxDays].
     * Never invents or generates fake figures.
     */
    fun getDailyHistory(context: Context, maxDays: Int = 14): List<Pair<String, Long>> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val daysSet = prefs.getStringSet(KEY_RECORDED_DAYS, emptySet()) ?: emptySet()
        val sortedDays = daysSet.sorted().takeLast(maxDays)
        return sortedDays.map { day ->
            day to prefs.getLong(KEY_DAILY_PREFIX + day, 0L)
        }
    }
}
