package space.deytt.connect

import android.content.Context
import android.os.Build
import java.io.IOException
import java.util.UUID

/** One installation keeps one slot, regardless of protocol, refresh or Telegram logout. */
internal object SubscriptionRequestIdentity {
    @Synchronized
    fun headers(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences("subscription_device", Context.MODE_PRIVATE)
        val hwid = prefs.getString("install_id", null) ?: UUID.randomUUID().toString().also {
            if (!prefs.edit().putString("install_id", it).commit()) {
                throw IOException("Не удалось сохранить идентификатор устройства")
            }
        }
        return buildHeaders(hwid, TelegramSessionStore.read(context), Build.MODEL, Build.VERSION.RELEASE)
    }

    internal fun buildHeaders(hwid: String, session: String?, model: String, osVersion: String): Map<String, String> =
        buildMap {
            put("X-HWID", hwid)
            put("X-Deytt-Client", "deytt-connect")
            put("X-Device-Os", "Android")
            put("X-Device-Model", headerValue(model))
            put("X-Ver-Os", headerValue(osVersion))
            session?.takeIf { it.isNotBlank() }?.let { put("X-TG-App-Token", it) }
        }

    // Device vendors may use non-ASCII model names; headers cannot carry CR/LF or Unicode.
    private fun headerValue(value: String): String = value.filter { it in ' '..'~' }.take(128)
}
