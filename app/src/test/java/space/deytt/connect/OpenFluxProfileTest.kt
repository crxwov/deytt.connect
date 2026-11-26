package space.deytt.connect

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class OpenFluxProfileTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = System.currentTimeMillis() / 1000

    private fun profile(expiry: Long = now + 1800): JSONObject = JSONObject()
        .put("id", "openflux:DE").put("country", "DE").put("label", "германия")
        .put("expires_at", expiry).put("secret", "A".repeat(43))
        .put("context", "test-channel-context")
        .put("transports", JSONArray().put(JSONObject().put("type", "cupsonline").put("name", "cupsonline")
            .put("priority", 100).put("url", java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "[\"12345678-1234-1234-1234-123456789abc\"]".toByteArray()))))

    private fun payload(vararg profiles: JSONObject): String = JSONObject().put("version", 1)
        .put("profiles", JSONArray(profiles.toList())).toString()

    @Test fun validatesLeaseAndOmitsExpiredProfilesWithoutExposingSecrets() {
        val profile = OpenFluxProfile.parse(payload(profile()), now).single()
        assertEquals("openflux:DE", profile.id)
        assertEquals(now + 1800, profile.expiresAt)
        assertFalse(profile.toString().contains("A".repeat(43)))
        assertTrue(OpenFluxProfile.parse(payload(profile(now)), now).isEmpty())
        assertFalse(profile.isCurrent(now + 1800))
    }

    @Test fun rejectsUnsupportedDuplicateAndMalformedCarrierContracts() {
        val unsupported = profile().apply { getJSONArray("transports").getJSONObject(0).put("type", "direct") }
        val invalidRooms = profile().apply { getJSONArray("transports").getJSONObject(0).put("url", "https://direct.example") }
        val cases = listOf(payload(unsupported), payload(invalidRooms), payload(profile(), profile()),
            payload(profile().put("secret", "weak")), payload(profile(now + 86401)),
            payload(profile().put("context", "bad\ncontext")), payload(profile()).replace("\"version\":1", "\"version\":2"))
        cases.forEach { assertTrue(runCatching { OpenFluxProfile.parse(it, now) }.isFailure) }
    }

    @Test fun encryptedCacheIsBoundToSubscriptionAndSessionAndExpires() {
        val directory = temporary.newFolder()
        val store = OpenFluxProfileStore(directory)
        val owner = OpenFluxProfileStore.ownerKey("https://deytt.space/sub/test", "account-a")
        store.save(owner, OpenFluxProfile.parse(payload(profile()), now))
        val raw = File(directory, OpenFluxProfileStore.FILE_NAME).readText()
        assertTrue(raw.startsWith(LocalSecretCipher.PREFIX))
        assertFalse(raw.contains("test-channel-context"))
        assertEquals(1, store.profiles(owner, now).size)
        assertTrue(store.profiles(OpenFluxProfileStore.ownerKey("https://deytt.space/sub/test", "account-b"), now).isEmpty())
        assertTrue(store.profiles(OpenFluxProfileStore.ownerKey("https://deytt.space/sub/other", "account-a"), now).isEmpty())
        assertTrue(store.profiles(owner, now + 1800).isEmpty())
        store.clear()
        assertFalse(File(directory, OpenFluxProfileStore.FILE_NAME).exists())
    }

    @Test fun plaintextAndCorruptOptionalCachesFailClosed() {
        val directory = temporary.newFolder()
        val file = File(directory, OpenFluxProfileStore.FILE_NAME)
        file.writeText(payload(profile()))
        assertTrue(OpenFluxProfileStore(directory).profiles("owner", now).isEmpty())
        file.writeText("ENC1:damaged")
        assertTrue(OpenFluxProfileStore(directory).profiles("owner", now).isEmpty())
    }

    @Test fun rollbackRestoresOpenFluxAndCoreTogether() {
        val directory = temporary.newFolder()
        val store = OpenFluxProfileStore(directory)
        val original = OpenFluxProfile.parse(payload(profile()), now)
        store.save("owner", original)
        SubscriptionStore(directory).saveValidated("old core")
        try {
            SubscriptionStore(directory).commitValidated("new core", AwgProfileStore(directory), emptyList()) {
                store.save("owner", emptyList())
                throw IOException("simulated optional disk failure")
            }
            fail("Expected rollback")
        } catch (_: IOException) { }
        assertEquals("old core", SubscriptionStore(directory).readCurrent())
        assertEquals(1, store.profiles("owner", now).size)
    }

    private val routeConfig = """{"outbounds":[{"type":"selector","tag":"автоподбор"},{"type":"vless","tag":"route:DE:VLESS"},{"type":"trojan","tag":"route:RU-DE:CHAIN"}],"route":{"final":"автоподбор"}}"""

    @Test fun managedRouteIsNotExposedForSelection() {
        val routes = RouteCatalog.from(routeConfig, emptyList())
        assertFalse(routes.any { it.protocol == RouteProtocol.OPENFLUX })
        assertTrue(RouteCatalog.countryGroups(routes).toMap().getValue("DE").none { it.protocol == RouteProtocol.OPENFLUX })
        assertTrue(OpenFluxProfile.parse(payload(), now).isEmpty())
    }

    @Test fun expiredLeaseDoesNotExposeRoute() {
        val expiredPayload = payload(profile(now - 1).put("label", "expired channel"))
        val expired = OpenFluxProfile.parse(expiredPayload, now - 1800).single()
        assertFalse(expired.isCurrent(now))
        assertTrue(OpenFluxProfile.parse(expiredPayload, now).isEmpty())
        assertFalse(RouteCatalog.from(routeConfig, emptyList(), listOf(expired)).any { it.protocol == RouteProtocol.OPENFLUX })
    }

    @Test fun currentLeaseRemainsStoredButRoutePickerHidesIt() {
        val profile = OpenFluxProfile.parse(payload(profile().put("label", "managed cups channel")), now).single()
        val original = RouteCatalog.from(routeConfig, emptyList())
        val routes = RouteCatalog.from(routeConfig, emptyList(), listOf(profile, profile))
        assertEquals("managed cups channel", profile.label)
        assertTrue(profile.isCurrent(now))
        assertFalse(routes.any { it.protocol == RouteProtocol.OPENFLUX })
        assertEquals(original, routes)
        assertEquals(RouteProtocol.AUTO, routes.first().protocol)
        assertEquals(TunnelEngine.LIBBOX, routes.first().engine)
        assertEquals("автоподбор", routes.first().configTag)
        assertEquals("автоподбор", ProfileRoutes.selected(routeConfig))
        assertTrue(routes.any { it.protocol == RouteProtocol.RU_DE })
    }

    @Test fun renewalRequiresSameSecretsContextAndCarrierRooms() {
        val original = OpenFluxProfile.parse(payload(profile()), now).single()
        val renewal = OpenFluxProfile.parse(payload(profile(now + 3600)), now).single()
        assertTrue(original.sameChannel(renewal))
        val changed = OpenFluxProfile.parse(payload(profile().put("context", "other-channel-context")), now).single()
        assertFalse(original.sameChannel(changed))
    }
}
