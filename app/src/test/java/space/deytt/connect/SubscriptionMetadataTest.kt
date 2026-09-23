package space.deytt.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionMetadataTest {
    @Test
    fun parsesStandardSubscriptionUserinfo() {
        val metadata = SubscriptionMetadata.parse(
            "deytt · @user",
            "upload=1024; download=2048; total=0; expire=1893456000",
        )

        assertEquals("deytt · @user", metadata.title)
        assertEquals(3072L, metadata.usedBytes)
        assertEquals(0L, metadata.totalBytes)
        assertEquals(1893456000L, metadata.expiresAtSeconds)
    }

    @Test
    fun ignoresMalformedOptionalFields() {
        val metadata = SubscriptionMetadata.parse(null, "upload=nope; download=-2")

        assertEquals("deytt", metadata.title)
        assertEquals(0L, metadata.usedBytes)
        assertNull(metadata.expiresAtSeconds)
    }
}
