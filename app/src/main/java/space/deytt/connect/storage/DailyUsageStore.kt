package space.deytt.connect

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Manages daily VPN traffic statistics for the Profile page.
 * Merges real server historical counters with real local DateConnect tunnel usage.
 * Never invents mock or arbitrary figures.
 */
object DailyUsageStore {
    private const val PREFS_NAME = "space.deytt.connect.daily_usage"
    private const val KEY_HISTORY = "history_json"

    fun getDailyHistory(context: Context, totalBytes: Long): List<Pair<String, Long>> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_HISTORY, null)
        val serverMap = mutableMapOf<String, Long>()
        if (!raw.isNullOrBlank()) {
            runCatching {
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val date = obj.getString("date")
                    val bytes = obj.getLong("bytes")
                    serverMap[date] = bytes
                }
            }
        }

        // Merge with local DateConnect recorded daily usage
        val localHistory = LocalTrafficRecorder.getDailyHistory(context, maxDays = 30)
        for ((date, bytes) in localHistory) {
            val existing = serverMap[date] ?: 0L
            serverMap[date] = existing + bytes
        }

        // If today has local traffic but wasn't in serverMap, ensure it's recorded
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (!serverMap.containsKey(today)) {
            val localToday = LocalTrafficRecorder.getDailyHistory(context, 1)
                .find { it.first == today }?.second ?: 0L
            if (localToday > 0L) {
                serverMap[today] = localToday
            }
        }

        val list = serverMap.toList().sortedBy { it.first }.takeLast(30)
        return list
    }

    fun saveHistory(context: Context, entries: List<Pair<String, Long>>) {
        val array = JSONArray()
        for ((date, bytes) in entries) {
            array.put(JSONObject().apply {
                put("date", date)
                put("bytes", bytes)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HISTORY, array.toString())
            .apply()
    }
}
