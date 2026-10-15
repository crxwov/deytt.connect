package space.deytt.connect

import org.junit.Assert.*
import org.junit.Test

class LocalBase64Test {
    @Test fun matchesRfc4648VectorsAndAcceptsUnpaddedInput() {
        val vectors = mapOf("" to "", "f" to "Zg==", "fo" to "Zm8=", "foo" to "Zm9v",
            "foob" to "Zm9vYg==", "fooba" to "Zm9vYmE=", "foobar" to "Zm9vYmFy")
        vectors.forEach { (plain, encoded) ->
            assertEquals(encoded, LocalBase64.encode(plain.toByteArray()))
            assertArrayEquals(plain.toByteArray(), LocalBase64.decode(encoded))
            assertArrayEquals(plain.toByteArray(), LocalBase64.decode(encoded.trimEnd('=')))
        }
    }

    @Test fun roundTripsBinaryAcrossEveryPaddingAndAlphabetCase() {
        for (length in 0..257) {
            val bytes = ByteArray(length) { ((it * 79 + length) and 255).toByte() }
            val encoded = java.util.Base64.getEncoder().encodeToString(bytes)
            assertEquals(encoded, LocalBase64.encode(bytes))
            assertArrayEquals(bytes, LocalBase64.decode(encoded))
            val url = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            assertArrayEquals(bytes, LocalBase64.decode(url, urlSafe = true))
        }
    }

    @Test fun rejectsDamagedEncodingAndNoncanonicalTailBits() {
        listOf("A", "====", "Zg=", "Zg===", "Z=g=", "Zg==\n", "Z g==", "Zh==", "Zm9=", "-_8=").forEach {
            assertTrue(it, runCatching { LocalBase64.decode(it) }.isFailure)
        }
        assertTrue(runCatching { LocalBase64.decode("+/8=", urlSafe = true) }.isFailure)
    }

    @Test fun ciphertextStaysEncryptedAndTamperingFailsClosed() {
        val encrypted = LocalSecretCipher.encrypt("profile-secret")
        assertTrue(encrypted.startsWith(LocalSecretCipher.PREFIX))
        assertFalse(encrypted.contains("profile-secret"))
        assertEquals("profile-secret", LocalSecretCipher.decryptOrPlaintext(encrypted))
        val bytes = LocalBase64.decode(encrypted.removePrefix(LocalSecretCipher.PREFIX))
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertTrue(runCatching { LocalSecretCipher.decryptOrPlaintext(LocalSecretCipher.PREFIX + LocalBase64.encode(bytes)) }.isFailure)
    }
}
