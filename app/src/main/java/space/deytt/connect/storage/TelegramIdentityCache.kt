package space.deytt.connect

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONObject

/** Keeps identity across activity recreation and caches last profile snapshot for instantaneous UI render. */
internal object TelegramIdentityCache {
    private const val PREFS_CACHE = "telegram_profile_cache"
    private const val KEY_SNAPSHOT = "last_profile_snapshot"

    private var session: String? = null
    var username: String? = null
        private set
    var avatar: Bitmap? = null
        private set

    @Synchronized fun bind(token: String?) {
        if (session == token) return
        session = token
        username = null
        avatar = null
    }

    @Synchronized fun update(token: String, name: String?, image: Bitmap?) {
        bind(token)
        if (!name.isNullOrBlank()) username = name
        if (image != null) avatar = image
    }

    fun saveSnapshot(context: Context, snapshot: JSONObject) {
        context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SNAPSHOT, snapshot.toString())
            .apply()
    }

    fun readSnapshot(context: Context): JSONObject? {
        val raw = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
            .getString(KEY_SNAPSHOT, null) ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    fun clearCache(context: Context) {
        context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SNAPSHOT)
            .apply()
    }
}
