package space.deytt.connect

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
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
