package space.deytt.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    @Test fun restrictsReleaseLinksToTheOfficialRepositoryAndHttpsOrigin() {
        val base = "https://github.com/crxwov/deytt.connect/releases/"
        assertTrue(ReleaseUrlPolicy.isOfficialPage(base + "tag/v0.9.0"))
        assertTrue(ReleaseUrlPolicy.isOfficialAsset(base + "download/v0.9.0/app-arm64-v8a-release.apk"))
        for (url in listOf(
            "http://github.com/crxwov/deytt.connect/releases/tag/v1",
            "https://github.com:8443/crxwov/deytt.connect/releases/tag/v1",
            "https://user@github.com/crxwov/deytt.connect/releases/tag/v1",
            "https://github.com/other/repo/releases/tag/v1",
            "https://github.com/crxwov/deyttXconnect/releases/tag/v1",
            base + "tag/v1?redirect=evil",
            base + "tag/v1#fragment",
            base + "tag/../v1",
        )) assertFalse(url, ReleaseUrlPolicy.isOfficialPage(url))
        assertFalse(ReleaseUrlPolicy.isOfficialAsset(base + "tag/v1"))
        assertFalse(ReleaseUrlPolicy.isOfficialAsset(base + "download/v1/%2e%2e/evil.apk"))
        assertFalse(ReleaseUrlPolicy.isOfficialAsset("https://github.com/other/repo/releases/download/v1/app.apk"))
    }

    @Test fun prefersReleaseAndDeviceAbiRegardlessOfAssetOrder() {
        val names = listOf("app-x86_64-release.apk", "app-arm64-v8a-debug.apk", "app-universal-release.apk", "app-arm64-v8a-release.apk")
        assertEquals("app-arm64-v8a-release.apk", ReleaseAssetPolicy.preferredName(names, listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("app-universal-release.apk", ReleaseAssetPolicy.preferredName(names, listOf("armeabi-v7a")))
        assertEquals("app-arm64-v8a-debug.apk", ReleaseAssetPolicy.preferredName(listOf("app-arm64-v8a-debug.apk"), listOf("arm64-v8a")))
        assertNull(ReleaseAssetPolicy.preferredName(listOf("app-x86_64-release.apk", "unrelated.apk"), listOf("arm64-v8a")))
    }

    @Test fun acceptsCurrentSignerOrForwardVerifiedRotationOnly() {
        assertTrue(UpdateSignaturePolicy.accepts(setOf("old"), setOf("old"), emptyList()))
        assertTrue(UpdateSignaturePolicy.accepts(setOf("old"), setOf("new"), listOf("old", "new")))
        assertFalse(UpdateSignaturePolicy.accepts(setOf("new"), setOf("old"), listOf("old")))
        // Sharing an ancestor does not authorize moving between two descendants.
        assertFalse(UpdateSignaturePolicy.accepts(setOf("new"), setOf("other"), listOf("old", "other")))
        assertFalse(UpdateSignaturePolicy.accepts(setOf("old"), setOf("new"), emptyList()))
        assertFalse(UpdateSignaturePolicy.accepts(setOf("old"), setOf("new"), listOf("new", "old")))
        assertFalse(UpdateSignaturePolicy.accepts(emptySet(), emptySet(), emptyList()))
    }

    @Test fun requiresExactMultiSignerIdentity() {
        assertTrue(UpdateSignaturePolicy.accepts(setOf("a", "b"), setOf("b", "a"), emptyList()))
        assertFalse(UpdateSignaturePolicy.accepts(setOf("a", "b"), setOf("a"), listOf("b", "a")))
        assertFalse(UpdateSignaturePolicy.accepts(setOf("a"), setOf("a", "b"), listOf("a", "b")))
    }
}
