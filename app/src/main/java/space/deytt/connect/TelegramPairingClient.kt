package space.deytt.connect

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

internal class TelegramPairingException(val code: String) : IOException(code)
internal data class TelegramPairStart(val challenge: String, val botUrl: String, val delivery: String)
internal data class TelegramPairSession(val token: String, val username: String, val firstName: String)

internal object TelegramSessionStore {
    private const val PREFS = "telegram_session"
    private const val TOKEN = "token_ciphertext"
    private const val IV = "token_iv"
    private const val KEY_ALIAS = "deytt.connect.telegram.session.v1"
    private val aad = "deytt.connect:telegram-session:v1".toByteArray(Charsets.UTF_8)

    fun read(context: Context): String? = synchronized(this) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encrypted = prefs.getString(TOKEN, null) ?: return@synchronized null
        val iv = prefs.getString(IV, null) ?: return@synchronized null
        runCatching {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
                updateAAD(aad)
                doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).toString(Charsets.UTF_8)
            }
        }.getOrElse {
            prefs.edit().remove(TOKEN).remove(IV).commit()
            null
        }
    }

    fun save(context: Context, token: String) = synchronized(this) {
        require(token.length in 32..256)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(TOKEN, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .commit())
    }

    fun clear(context: Context) = synchronized(this) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(TOKEN).remove(IV).commit()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }
}

internal object TelegramPairingClient {
    private const val API_BASE = "https://deytt.space"
    private const val SESSION_HEADER = "X-TG-App-Token"
    private const val MAX_JSON_BYTES = 512 * 1024
    private const val MAX_AVATAR_BYTES = 2 * 1024 * 1024

    fun start(username: String): TelegramPairStart {
        val normalized = username.trim().removePrefix("@")
        require(Regex("[A-Za-z0-9_]{5,32}").matches(normalized)) {
            throw TelegramPairingException("invalid_username")
        }
        val response = request("/api/tg/mobile/pair/start", "POST", JSONObject().put("username", normalized), null)
        return TelegramPairStart(
            response.optString("challenge").takeIf(String::isNotBlank)
                ?: throw TelegramPairingException("pairing_unavailable"),
            response.optString("bot_url").takeIf(String::isNotBlank)
                ?: throw TelegramPairingException("pairing_unavailable"),
            response.optString("delivery").ifBlank { "start_required" },
        )
    }

    fun verify(challenge: String, code: String): TelegramPairSession {
        val response = request("/api/tg/mobile/pair/verify", "POST",
            JSONObject().put("challenge", challenge).put("code", code), null)
        val profile = response.optJSONObject("profile") ?: throw TelegramPairingException("pairing_unavailable")
        return TelegramPairSession(
            response.optString("token").takeIf(String::isNotBlank)
                ?: throw TelegramPairingException("pairing_unavailable"),
            profile.optString("username"),
            profile.optString("first_name"),
        )
    }

    fun subscriptionUrl(token: String): String? {
        val happ = request("/api/tg/keys", "GET", null, token).optJSONObject("happ") ?: return null
        if (!happ.optBoolean("available")) return null
        return happ.optString("sub_url").takeIf(String::isNotBlank)
    }

    fun profile(token: String): JSONObject? =
        request("/api/tg/me", "GET", null, token).optJSONObject("profile")

    fun accountSnapshot(token: String): JSONObject = request("/api/tg/me", "GET", null, token)

    fun keys(token: String): JSONObject = request("/api/tg/keys", "GET", null, token)

    fun setHappDeviceBlocked(token: String, deviceId: Long, blocked: Boolean): JSONObject {
        require(deviceId > 0L)
        val action = if (blocked) "revoke" else "restore"
        val result = request("/api/tg/happ/devices/$action", "POST", JSONObject().put("device_id", deviceId), token)
        requireDeviceUpdateConfirmation(result, blocked)
        return result
    }

    internal fun requireDeviceUpdateConfirmation(result: JSONObject, blocked: Boolean) {
        if (!result.optBoolean("ok") || result.isNull("blocked") || result.opt("blocked") !is Boolean || result.optBoolean("blocked") != blocked) {
            throw TelegramPairingException("device_update_unconfirmed")
        }
    }

    fun sessions(token: String): JSONObject = request("/api/tg/sessions", "GET", null, token)

    fun revokeSession(token: String, sessionId: String): JSONObject {
        require(sessionId.isNotBlank() && sessionId.length <= 128)
        return request("/api/tg/sessions/revoke", "POST", JSONObject().put("session_id", sessionId), token)
    }

    fun resetKeys(token: String, scope: String): JSONObject {
        require(scope in setOf("all", "awg", "happ"))
        return request("/api/tg/keys/reset", "POST", JSONObject().put("scope", scope), token)
    }

    fun tariffs(): JSONObject = request("/api/tg/tariffs", "GET", null, null)

    fun quote(
        token: String,
        kind: String,
        plan: String? = null,
        devices: Int? = null,
        months: Int? = null,
        extra: Int? = null,
    ): JSONObject {
        require(kind in setOf("preset", "custom", "add_time", "add_devices"))
        val query = buildList {
            add("kind=${encode(kind)}")
            plan?.takeIf(String::isNotBlank)?.let { add("plan=${encode(it)}") }
            devices?.let { add("devices=$it") }
            months?.let { add("months=$it") }
            extra?.let { add("extra=$it") }
        }.joinToString("&")
        return request("/api/tg/quote?$query", "GET", null, token)
    }

    fun checkout(token: String, kind: String, method: String, plan: String? = null, months: Int? = null, devices: Int? = null, extra: Int? = null): JSONObject {
        require(kind in setOf("preset", "custom", "add_time", "add_devices"))
        require(method in setOf("stars", "platega"))
        val body = JSONObject().put("kind", kind).put("method", method)
        plan?.let { body.put("plan", it) }
        months?.let { body.put("months", it) }
        devices?.let { body.put("devices", it) }
        extra?.let { body.put("extra", it) }
        return request("/api/tg/checkout", "POST", body, token)
    }

    fun paymentStatus(token: String, externalId: String): JSONObject {
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(externalId))
        return request("/api/tg/payment-status?external_id=${encode(externalId)}", "GET", null, token)
    }

    fun supportThread(token: String): JSONObject = request("/api/tg/support", "GET", null, token)

    fun createSupportTicket(token: String, text: String): JSONObject {
        val clean = text.trim()
        require(clean.length in 5..4000)
        return request(
            "/api/tg/support",
            "POST",
            JSONObject().put("text", clean).put("category", "other"),
            token,
        )
    }

    fun sendSupportMessage(token: String, ticketId: Int, text: String): JSONObject {
        val clean = text.trim()
        require(ticketId > 0 && clean.isNotEmpty() && clean.length <= 4000)
        return request(
            "/api/tg/support/messages",
            "POST",
            JSONObject().put("ticket_id", ticketId).put("text", clean),
            token,
        )
    }

    fun closeSupportTicket(token: String, ticketId: Int): JSONObject {
        require(ticketId > 0)
        return request(
            "/api/tg/support/close",
            "POST",
            JSONObject().put("ticket_id", ticketId),
            token,
        )
    }

    fun avatar(token: String): ByteArray? {
        val connection = openConnection("/api/tg/me/avatar", "GET", token)
        try {
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NO_CONTENT || status == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (status !in 200..299) throw TelegramPairingException("avatar_unavailable")
            if (!connection.contentType.orEmpty().startsWith("image/")) return null
            return connection.inputStream.use { readLimited(it, MAX_AVATAR_BYTES) }.takeIf { it.isNotEmpty() }
        } finally {
            connection.disconnect()
        }
    }

    fun logout(token: String) {
        request("/api/tg/logout", "POST", JSONObject(), token)
    }

    private fun request(path: String, method: String, body: JSONObject?, token: String?): JSONObject {
        val connection = openConnection(path, method, token)
        try {
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val payload = stream?.use { readLimited(it, MAX_JSON_BYTES) }?.toString(Charsets.UTF_8).orEmpty()
            val response = runCatching { JSONObject(payload) }.getOrElse { JSONObject() }
            if (status !in 200..299) {
                throw TelegramPairingException(response.optString("error").ifBlank { "request_failed" })
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(path: String, method: String, token: String?): HttpURLConnection {
        require(path.startsWith("/api/tg/"))
        return (URL(API_BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = 15_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json, image/*")
            setRequestProperty("User-Agent", "deytt-connect/${BuildConfig.VERSION_NAME}")
            token?.let { setRequestProperty(SESSION_HEADER, it) }
            if (method == "POST") setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun readLimited(input: java.io.InputStream, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > maximum) throw IOException("response_too_large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
