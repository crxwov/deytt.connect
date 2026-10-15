package space.deytt.connect

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Schedule from the lease deadline, including profiles cached before connection. */
internal object OpenFluxLeaseSchedule {
    private const val RENEW_INTERVAL_SECONDS = 10 * 60L
    private const val RETRY_INTERVAL_SECONDS = 60L
    private const val REFRESH_MARGIN_SECONDS = 120L

    fun needsRefreshBeforeStart(expiresAt: Long, nowSeconds: Long): Boolean =
        expiresAt - nowSeconds <= REFRESH_MARGIN_SECONDS

    fun renewalDelayMillis(expiresAt: Long, nowSeconds: Long, retry: Boolean = false): Long {
        val interval = if (retry) RETRY_INTERVAL_SECONDS else RENEW_INTERVAL_SECONDS
        val remaining = (expiresAt - nowSeconds).coerceAtLeast(0)
        val beforeMargin = (remaining - REFRESH_MARGIN_SECONDS).coerceAtLeast(0)
        // Once inside the margin, failed renewals keep their bounded backoff
        // instead of issuing an HTTP request on every monitor poll.
        val budget = if (retry && beforeMargin == 0L) remaining else beforeMargin
        return minOf(interval, budget) * 1000
    }
}

/** Secrets deliberately have no generated toString/copy representation. */
class OpenFluxProfile private constructor(
    val id: String,
    val country: String,
    val label: String,
    val expiresAt: Long,
    private val source: String,
) {
    fun nativeJson(): String = source
    fun isCurrent(nowSeconds: Long): Boolean = expiresAt > nowSeconds
    fun sameChannel(other: OpenFluxProfile): Boolean {
        val first = JSONObject(source)
        val second = JSONObject(other.source)
        return listOf("id", "secret", "context").all { first.getString(it) == second.getString(it) } &&
            first.getJSONArray("transports").toString() == second.getJSONArray("transports").toString()
    }
    override fun toString(): String = "OpenFluxProfile($id, expiresAt=$expiresAt)"

    companion object {
        const val MAX_PAYLOAD_BYTES = 256 * 1024
        private const val MAX_PROFILES = 8

        fun parse(payload: String, nowSeconds: Long = System.currentTimeMillis() / 1000): List<OpenFluxProfile> {
            require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) { "Профиль обхода слишком большой" }
            val root = JSONObject(payload)
            require(root.getInt("version") == 1) { "Обновите приложение для этого профиля обхода" }
            val profiles = root.getJSONArray("profiles")
            require(profiles.length() <= MAX_PROFILES)
            val ids = mutableSetOf<String>()
            return (0 until profiles.length()).mapNotNull { index ->
                val item = profiles.getJSONObject(index)
                val country = item.getString("country")
                require(country == "DE")
                val id = item.getString("id")
                require(id == "openflux:$country" && ids.add(id))
                val label = bounded(item, "label", 1, 80)
                val secret = bounded(item, "secret", 43, 43)
                require(secret.matches(Regex("[A-Za-z0-9_-]{43}")))
                require(decodeUrl(secret).size == 32)
                bounded(item, "context", 16, 256)
                val expiry = item.getLong("expires_at")
                require(expiry > 0 && expiry <= nowSeconds + 24 * 60 * 60)
                val transports = item.getJSONArray("transports")
                require(transports.length() in 1..4)
                for (position in 0 until transports.length()) {
                    val transport = transports.getJSONObject(position)
                    require(transport.getString("type") == "cupsonline")
                    require(transport.getString("name") == "cupsonline")
                    val packed = bounded(transport, "url", 1, 64 * 1024)
                    require(packed.matches(Regex("[A-Za-z0-9_-]+")))
                    val rooms = JSONArray(decodeUrl(packed).toString(Charsets.UTF_8))
                    require(rooms.length() in 1..4)
                    for (room in 0 until rooms.length()) {
                        require(rooms.getString(room).matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
                    }
                    require(transport.getInt("priority") == 100)
                }
                if (expiry <= nowSeconds) null else OpenFluxProfile(id, country, label, expiry, item.toString())
            }
        }

        fun encode(profiles: List<OpenFluxProfile>): String = JSONObject().put("version", 1)
            .put("profiles", JSONArray(profiles.map { JSONObject(it.nativeJson()) })).toString()

        private fun bounded(item: JSONObject, key: String, minimum: Int, maximum: Int): String =
            item.getString(key).also { value ->
                require(value.length in minimum..maximum && value.none { it.code < 32 || it.code == 127 })
            }

        private fun decodeUrl(value: String): ByteArray = LocalBase64.decode(value, urlSafe = true)
    }
}

class OpenFluxProfileStore internal constructor(private val directory: File) {
    constructor(context: Context) : this(File(context.filesDir, "subscription"))
    private val file = File(directory, FILE_NAME)

    fun profiles(context: Context): List<OpenFluxProfile> = synchronized(AtomicSubscriptionFile.lock) {
        profiles(owner(context))
    }

    internal fun profiles(owner: String?, nowSeconds: Long = System.currentTimeMillis() / 1000): List<OpenFluxProfile> =
        synchronized(AtomicSubscriptionFile.lock) {
            if (owner == null) return@synchronized emptyList()
            runCatching {
                AtomicSubscriptionFile.recover(directory)
                val raw = file.takeIf { it.isFile && it.length() in 1..(OpenFluxProfile.MAX_PAYLOAD_BYTES * 2L) }
                    ?.readText(Charsets.UTF_8) ?: return@runCatching emptyList()
                // This new store has no plaintext legacy format.
                require(raw.startsWith(LocalSecretCipher.PREFIX))
                val root = JSONObject(LocalSecretCipher.decryptOrPlaintext(raw))
                require(root.getString("owner") == owner)
                OpenFluxProfile.parse(root.getJSONObject("payload").toString(), nowSeconds)
            }.getOrDefault(emptyList())
        }

    internal fun save(owner: String, profiles: List<OpenFluxProfile>) = synchronized(AtomicSubscriptionFile.lock) {
        val payload = OpenFluxProfile.encode(profiles)
        OpenFluxProfile.parse(payload)
        AtomicSubscriptionFile.writeEncrypted(file, JSONObject().put("owner", owner)
            .put("payload", JSONObject(payload)).toString())
    }

    fun clear() = synchronized(AtomicSubscriptionFile.lock) {
        if (file.exists() && !file.delete()) throw java.io.IOException("Не удалось удалить профиль обхода")
    }

    companion object {
        internal const val FILE_NAME = "openflux-profiles.json"

        internal fun owner(context: Context): String? = synchronized(AtomicSubscriptionFile.lock) {
            owner(context, SubscriptionClient.getSavedSubscriptionUrl(context))
        }

        internal fun owner(context: Context, url: String?): String? = synchronized(AtomicSubscriptionFile.lock) {
            url?.let { ownerKey(it, TelegramSessionStore.read(context)) }
        }

        internal fun ownerKey(url: String, session: String?): String = MessageDigest.getInstance("SHA-256")
            .digest((url + "\u0000" + session.orEmpty()).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
