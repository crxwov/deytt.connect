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

    private fun base64Encode(bytes: ByteArray): String =
        runCatching { android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP) }
            .getOrNull()
            ?: java.util.Base64.getEncoder().encodeToString(bytes)

    private fun base64Decode(encoded: String): ByteArray =
        runCatching { android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP) }
            .getOrNull()
            ?: java.util.Base64.getDecoder().decode(encoded)
}
