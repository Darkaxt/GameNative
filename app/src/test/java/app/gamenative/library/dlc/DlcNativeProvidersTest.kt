package app.gamenative.library.dlc

import app.gamenative.data.canonical.EpicStableSourceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import org.junit.Assert.*
import org.junit.Test

class DlcNativeProvidersTest {
    @Test
    fun gogCatalogUsesExactNativeParentWithoutAccountCredential() = runTest {
        val http = RecordingHttp { """{"id":42,"game_type":"game","dlcs":{"products":[{"id":43}]},"expanded_dlcs":[{"id":43,"title":"Expansion","game_type":"dlc"}]}""" }
        val result = GogDlcProvider(http) { "synthetic-token" }.catalog("42")
        assertNotNull(result)
        assertEquals("43", result!!.items.single().id)
        assertEquals("https://api.gog.com/products/42?expand=expanded_dlcs", http.urls.single().toString())
        assertNull(http.bearers.single())
    }

    @Test
    fun fullGogInventoryIncludesLicensedProductsAbsentFromVisibleLibrary() = runTest {
        val http = RecordingHttp { """{"owned":[42,43,44]}""" }
        val result = GogDlcProvider(http) { "synthetic-token" }.entitlements("42")
        assertEquals(setOf("42", "43", "44"), result.ownedIds)
        assertTrue(result.complete)
        assertEquals("https://embed.gog.com/user/data/games", http.urls.single().toString())
        assertEquals("synthetic-token", http.bearers.single())
    }

    @Test
    fun malformedGogInventoryCannotBecomeAnUnownedResult() = runTest {
        listOf("{}", "{\"owned\":null}", "{\"owned\":[42,{}]}", "{\"owned\":[42,-1]}").forEach { body ->
            val result = GogDlcProvider(RecordingHttp { body }) { "synthetic-token" }.entitlements("42")
            assertFalse(result.complete)
            assertTrue(result.ownedIds.isEmpty())
        }
    }

    @Test
    fun revokedGogParentCannotSupplyCompleteNegativeDlcAuthority() = runTest {
        val result = GogDlcProvider(RecordingHttp { "{\"owned\":[43]}" }) { "synthetic-token" }.entitlements("42")
        assertFalse(result.complete)
        assertTrue(result.ownedIds.isEmpty())
    }

    @Test
    fun nativeGogCancellationIsPropagated() = runTest {
        val http = object : DlcJsonTransport {
            override suspend fun get(url: HttpUrl, bearer: String?): JsonElement = throw CancellationException("cancel")
        }
        var cancelled = false
        try { GogDlcProvider(http) { "synthetic-token" }.entitlements("42") } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }

    @Test
    fun epicCatalogKeepsNamespacesAndAuthoritativeConstituents() = runTest {
        val http = RecordingHttp { """{"base":{"id":"base","namespace":"sandbox","dlcItemList":[{"id":"extra","namespace":"sandbox","title":"Expansion"},{"id":"extra","namespace":"another","title":"Other namespace expansion"}]}}""" }
        val result = EpicDlcProvider(http) { EpicDlcSession("a".repeat(32), "synthetic-token") }.catalog("sandbox", "base")
        assertNotNull(result)
        assertEquals(listOf(EpicStableSourceId.encode("sandbox", "extra"), EpicStableSourceId.encode("another", "extra")), result!!.items.map { it.id })
        assertEquals("/catalog/api/shared/namespace/sandbox/bulk/items", http.urls.single().encodedPath)
        assertEquals("base", http.urls.single().queryParameter("id"))
        assertEquals("true", http.urls.single().queryParameter("includeDLCDetails"))
    }

    @Test
    fun wrongEpicParentNamespaceCannotSupplyACompleteCatalog() = runTest {
        val http = RecordingHttp { """{"base":{"id":"base","namespace":"another","dlcItemList":[]}}""" }
        assertNull(EpicDlcProvider(http) { EpicDlcSession("a".repeat(32), "synthetic-token") }.catalog("sandbox", "base"))
    }

    @Test
    fun epicPaginationJoinsActiveNativeNamespaceAndCatalogIdsOnly() = runTest {
        val http = RecordingHttp { url ->
            if (url.queryParameter("start") == "0") {
                "[" + (1..1000).joinToString(",") { "{\"id\":\"ent$it\",\"namespace\":\"sandbox\",\"catalogItemId\":\"item$it\",\"active\":true,\"status\":\"ACTIVE\",\"consumable\":false}" } + "]"
            } else {
                """[{"id":"inactive","namespace":"sandbox","catalogItemId":"revoked","active":false,"status":"ACTIVE","consumable":false},{"id":"other","namespace":"other","catalogItemId":"item1","active":true,"status":"ACTIVE","consumable":false}]"""
            }
        }
        val result = EpicDlcProvider(http) { EpicDlcSession("a".repeat(32), "synthetic-token") }.entitlements()
        assertTrue(result.complete)
        assertEquals(1001, result.ownedIds.size)
        assertTrue(EpicStableSourceId.encode("other", "item1") in result.ownedIds)
        assertFalse(EpicStableSourceId.encode("sandbox", "revoked") in result.ownedIds)
        assertEquals(listOf("0", "1000"), http.urls.map { it.queryParameter("start") })
        assertEquals(listOf("synthetic-token", "synthetic-token"), http.bearers)
    }

    @Test
    fun interruptedEpicPaginationKeepsProvenPositivesButNoNegatives() = runTest {
        var requests = 0
        val http = object : DlcJsonTransport {
            override suspend fun get(url: HttpUrl, bearer: String?): JsonElement {
                requests++
                if (requests > 1) throw IllegalStateException("private response")
                return Json.parseToJsonElement("[" + (1..1000).joinToString(",") { "{\"id\":\"ent$it\",\"namespace\":\"sandbox\",\"catalogItemId\":\"item$it\",\"active\":true,\"status\":\"ACTIVE\",\"consumable\":false}" } + "]")
            }
        }
        val result = EpicDlcProvider(http) { EpicDlcSession("a".repeat(32), "synthetic-token") }.entitlements()
        assertFalse(result.complete)
        assertEquals(1000, result.ownedIds.size)
    }

    @Test
    fun repeatedEpicPageCannotEstablishCompleteNegativeAuthority() = runTest {
        val http = RecordingHttp { "[" + (1..1000).joinToString(",") { "{\"id\":\"ent$it\",\"namespace\":\"sandbox\",\"catalogItemId\":\"item$it\",\"active\":true,\"status\":\"ACTIVE\",\"consumable\":false}" } + "]" }
        val result = EpicDlcProvider(http) { EpicDlcSession("a".repeat(32), "synthetic-token") }.entitlements()
        assertFalse(result.complete)
        assertEquals(1000, result.ownedIds.size)
        assertEquals(2, http.urls.size)
    }

    @Test
    fun steamOwnershipRequiresExplicitLicensedPackageConstituents() = runTest {
        val licenses = listOf(SteamDlcLicense(10, 123L, 7), SteamDlcLicense(20, 456L, 9))
        var requested = emptyList<SteamDlcLicense>()
        val result = SteamDlcEntitlementProvider(snapshot = { licenses }, packages = { requested = it; mapOf(10 to setOf("43"), 20 to setOf("44")) }).load()
        assertEquals(licenses, requested)
        assertTrue(result.complete)
        assertEquals(setOf("43", "44"), result.ownedIds)
    }

    @Test
    fun missingSteamPackageRetainsPositiveButCannotProveUnowned() = runTest {
        val result = SteamDlcEntitlementProvider(snapshot = { listOf(SteamDlcLicense(10, 123L, 7), SteamDlcLicense(20, 456L, 9)) }, packages = { mapOf(10 to setOf("43"), 99 to setOf("999")) }).load()
        assertFalse(result.complete)
        assertEquals(setOf("43"), result.ownedIds)
    }

    @Test
    fun changedSteamLicenseSnapshotDiscardsPackageResults() = runTest {
        var revision = 1
        val result = SteamDlcEntitlementProvider(snapshot = { listOf(SteamDlcLicense(10, 123L, revision)) }, packages = { revision++; mapOf(10 to setOf("43")) }).load()
        assertFalse(result.complete)
        assertTrue(result.ownedIds.isEmpty())
    }

    @Test
    fun unavailableSteamLicenseSnapshotDoesNotRequestPublicAppTokens() = runTest {
        var requests = 0
        val result = SteamDlcEntitlementProvider(snapshot = { null }, packages = { requests++; emptyMap() }).load()
        assertFalse(result.complete)
        assertTrue(result.ownedIds.isEmpty())
        assertEquals(0, requests)
    }

    @Test
    fun stringEncodedEpicActiveFlagCannotEstablishLicenseAuthority() = runTest {
        val http = RecordingHttp { """[{"id":"entry","namespace":"sandbox","catalogItemId":"extra","active":"true","status":"ACTIVE","consumable":false}]""" }
        val result = EpicDlcProvider(http) { EpicDlcSession("a".repeat(32), "synthetic-token") }.entitlements()
        assertFalse(result.complete)
        assertTrue(result.ownedIds.isEmpty())
    }

    @Test
    fun stringEncodedEpicConsumableFlagCannotEstablishLicenseAuthority() = runTest {
        val http = RecordingHttp { """[{"id":"entry","namespace":"sandbox","catalogItemId":"extra","active":true,"status":"ACTIVE","consumable":"false"}]""" }
        val result = EpicDlcProvider(http) { EpicDlcSession("a".repeat(32), "synthetic-token") }.entitlements()
        assertFalse(result.complete)
        assertTrue(result.ownedIds.isEmpty())
    }

    @Test
    fun cachedEpicResultCannotGrantCurrentLicenseAuthority() = runTest {
        val http = RecordingHttp { """[{"id":"entry","namespace":"sandbox","catalogItemId":"extra","active":true,"status":"ACTIVE","consumable":false,"readFromCache":true}]""" }
        val result = EpicDlcProvider(http) { EpicDlcSession("a".repeat(32), "synthetic-token") }.entitlements()
        assertFalse(result.complete)
        assertTrue(result.ownedIds.isEmpty())
    }

    @Test
    fun malformedEpicCacheFlagCannotEstablishCurrentInventoryAuthority() = runTest {
        val http = RecordingHttp { """[{"id":"entry","namespace":"sandbox","catalogItemId":"extra","active":true,"status":"ACTIVE","consumable":false,"readFromCache":"false"}]""" }
        val result = EpicDlcProvider(http) { EpicDlcSession("a".repeat(32), "synthetic-token") }.entitlements()
        assertFalse(result.complete)
        assertTrue(result.ownedIds.isEmpty())
    }

    private class RecordingHttp(private val response: (HttpUrl) -> String) : DlcJsonTransport {
        val urls = mutableListOf<HttpUrl>()
        val bearers = mutableListOf<String?>()
        override suspend fun get(url: HttpUrl, bearer: String?): JsonElement {
            urls += url
            bearers += bearer
            return Json.parseToJsonElement(response(url))
        }
    }
}
