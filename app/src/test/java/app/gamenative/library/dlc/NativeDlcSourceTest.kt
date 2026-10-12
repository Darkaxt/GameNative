package app.gamenative.library.dlc

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import org.junit.Assert.*
import org.junit.Test

class NativeDlcSourceTest {
    @Test
    fun productionSourceDispatchUsesGogNativeIdentityAndNeverSteamAssociation() = runTest {
        val urls = mutableListOf<HttpUrl>()
        var steamRequests = 0
        val http = transport { url ->
            urls += url
            if (url.host == "api.gog.com") """{"id":42,"game_type":"game","dlcs":{"products":[{"id":43}]},"expanded_dlcs":[{"id":43,"title":"Expansion","game_type":"dlc"}]}""" else """{"owned":[42,43]}"""
        }
        val source = NativeDlcSource(http, { "synthetic-token" }, { EpicDlcSession("a".repeat(32), "synthetic-token") }, { steamRequests++; emptyList() }, { emptyMap() })
        val ref = SourceOwnedCopyReference.Gog(key(GameSource.GOG, "42"), "42")
        assertEquals(listOf("43"), source.catalog(ref).items.map { it.id })
        assertTrue(source.entitlements(ref).complete)
        assertEquals(listOf("api.gog.com", "embed.gog.com"), urls.map { it.host })
        assertEquals(0, steamRequests)
    }

    @Test
    fun productionSteamSourceValidatesEachChildAgainstItsExactNativeParent() = runTest {
        val urls = mutableListOf<HttpUrl>()
        val source = NativeDlcSource(transport { url ->
            urls += url
            if (url.queryParameter("appids") == "42") """{"42":{"success":true,"data":{"steam_appid":42,"type":"game","dlc":[43,44]}}}""" else if (url.queryParameter("appids") == "43") """{"43":{"success":true,"data":{"steam_appid":43,"type":"dlc","name":"Expansion","fullgame":{"appid":"42"}}}}""" else """{"44":{"success":true,"data":{"steam_appid":44,"type":"dlc","name":"Wrong parent expansion","fullgame":{"appid":"99"}}}}"""
        }, { "synthetic-token" }, { EpicDlcSession("a".repeat(32), "synthetic-token") }, { listOf(SteamDlcLicense(10, 123L, 7)) }, { mapOf(10 to setOf("43")) })
        val ref = SourceOwnedCopyReference.Steam(key(GameSource.STEAM, "42"), 42)
        val catalog = source.catalog(ref)
        assertEquals(listOf("43", "44"), catalog.items.map { it.id })
        assertEquals("Expansion", catalog.items.first().title)
        assertNull(catalog.items.last().title)
        assertFalse(catalog.items.last().associationVerified)
        assertFalse(catalog.complete)
        assertEquals(listOf("42", "43", "44"), urls.map { it.queryParameter("appids") })
        assertEquals(setOf("43"), source.entitlements(ref).ownedIds)
    }

    @Test
    fun unsupportedNativeSourceCannotFabricateSteamDlcFromPresentation() = runTest {
        var requests = 0
        val source = NativeDlcSource(transport { requests++; "{}" }, { "synthetic-token" }, { EpicDlcSession("a".repeat(32), "synthetic-token") }, { emptyList() }, { emptyMap() })
        val ref = SourceOwnedCopyReference.Custom(key(GameSource.CUSTOM_GAME, "42"), 42)
        var rejected = false
        try { source.catalog(ref) } catch (_: DlcTransportException) { rejected = true }
        assertTrue(rejected)
        assertEquals(0, requests)
    }

    private fun transport(response: (HttpUrl) -> String) = object : DlcJsonTransport {
        override suspend fun get(url: HttpUrl, bearer: String?): JsonElement = Json.parseToJsonElement(response(url))
    }
    private fun key(source: GameSource, id: String) = OwnedCopyKey(AccountScope.parse("a".repeat(64)), source, id)
}
