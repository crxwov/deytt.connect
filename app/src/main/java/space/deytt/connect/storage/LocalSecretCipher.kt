package space.deytt.connect

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object LocalSecretCipher {
    const val PREFIX = "ENC1:"
    private const val KEY_ALIAS = "deytt_connect_local_storage_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE_BYTES = 12
    private const val TAG_SIZE_BITS = 128

    private val fallbackKey: SecretKey by lazy {
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    fun encrypt(plaintext: String): String {
        if (plaintext.startsWith(PREFIX)) return plaintext
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        check(iv != null && iv.size == IV_SIZE_BYTES) { "Unexpected AES-GCM IV length" }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return PREFIX + base64Encode(combined)
    }

    fun decryptOrPlaintext(raw: String): String {
        if (!raw.startsWith(PREFIX)) return raw
        val decoded = base64Decode(raw.removePrefix(PREFIX))
        require(decoded.size > IV_SIZE_BYTES) { "Damaged encrypted local payload" }
        val iv = decoded.copyOfRange(0, IV_SIZE_BYTES)
        val ciphertext = decoded.copyOfRange(IV_SIZE_BYTES, decoded.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_SIZE_BITS, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        return runCatching {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@runCatching it }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
                generateKey()
            }
        }.getOrElse { fallbackKey }
    }

    private fun base64Encode(bytes: ByteArray): String = LocalBase64.encode(bytes)

    private fun base64Decode(encoded: String): ByteArray = LocalBase64.decode(encoded)
}

/** RFC 4648 encoding shared by Android API 24 and host-JVM storage checks. */
internal object LocalBase64 {
    private const val STANDARD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val URL_SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(bytes: ByteArray): String = buildString((bytes.size + 2) / 3 * 4) {
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 255)
            bits += 8
            while (bits >= 6) {
                bits -= 6
                append(STANDARD[(buffer ushr bits) and 63])
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) append(STANDARD[buffer shl (6 - bits)])
        while (length % 4 != 0) append('=')
    }

    fun decode(encoded: String, urlSafe: Boolean = false): ByteArray {
        val body = encoded.trimEnd('=')
        val padding = encoded.length - body.length
        require(padding <= 2 && body.length % 4 != 1) { "Invalid Base64 length" }
        require(padding == 0 || (encoded.length % 4 == 0 && padding == (4 - body.length % 4) % 4)) {
            "Invalid Base64 padding"
        }
        val alphabet = if (urlSafe) URL_SAFE else STANDARD
        val result = ByteArray(body.length * 6 / 8)
        var position = 0
        var buffer = 0
        var bits = 0
        for (character in body) {
            val value = alphabet.indexOf(character)
            require(value >= 0) { "Invalid Base64 character" }
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                result[position++] = (buffer ushr bits).toByte()
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        require(buffer == 0) { "Noncanonical Base64 padding bits" }
        return result
    }
}
