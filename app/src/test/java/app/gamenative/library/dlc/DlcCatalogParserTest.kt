package app.gamenative.library.dlc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class DlcCatalogParserTest {
    @Test
    fun exactGogParentRetainsIndividualDlcIdentities() {
        val catalog = parseGogDlcCatalog(json("""{"id":42,"game_type":"game","dlcs":{"products":[{"id":43},{"id":44}]},"expanded_dlcs":[{"id":43,"game_type":"dlc","title":"Expansion A"},{"id":44,"game_type":"dlc","title":"Expansion B"}]}"""), "42")
        assertNotNull(catalog)
        assertEquals(listOf("43", "44"), catalog!!.items.map { it.id })
        assertEquals(listOf("Expansion A", "Expansion B"), catalog.items.map { it.title })
        assertTrue(catalog.complete)
    }

    @Test
    fun advertisedButUnexpandedGogDlcDoesNotDisappear() {
        val catalog = parseGogDlcCatalog(json("""{"id":42,"game_type":"game","dlcs":{"products":[{"id":43},{"id":44}]},"expanded_dlcs":[{"id":43,"game_type":"dlc","title":"Expansion A"}]}"""), "42")
        assertNotNull(catalog)
        assertEquals(listOf("43", "44"), catalog!!.items.map { it.id })
        assertNull(catalog.items.last().title)
        assertFalse(catalog.complete)
    }

    @Test
    fun completeEmptyGogCatalogIsDistinctFromMissingDlcFields() {
        val catalog = parseGogDlcCatalog(json("""{"id":42,"game_type":"game","dlcs":{"products":[]},"expanded_dlcs":[]}"""), "42")
        assertNotNull(catalog)
        assertTrue(catalog!!.complete)
        assertTrue(catalog.items.isEmpty())
        assertNull(parseGogDlcCatalog(json("""{"id":42,"game_type":"game"}"""), "42"))
    }

    @Test
    fun wrongParentCannotSupplyCatalog() {
        assertNull(parseGogDlcCatalog(json("""{"id":41,"game_type":"game","dlcs":{"products":[]},"expanded_dlcs":[]}"""), "42"))
    }

    @Test
    fun expandedSiblingOutsideExactParentListCannotSupplyCatalog() {
        assertNull(parseGogDlcCatalog(json("""{"id":42,"game_type":"game","dlcs":{"products":[{"id":43}]},"expanded_dlcs":[{"id":44,"game_type":"dlc","title":"Sibling"}]}"""), "42"))
    }

    @Test
    fun bundleNameCannotEstablishEditionConstituents() {
        assertNull(parseGogDlcCatalog(json("""{"id":42,"game_type":"pack","title":"Game Deluxe","dlcs":{"products":[{"id":43}]},"expanded_dlcs":[{"id":43,"game_type":"dlc","title":"Expansion"}]}"""), "42"))
    }

    @Test
    fun malformedOrDuplicateNativeIdsCannotEstablishCompleteCatalog() {
        listOf("[{}]", "[{\"id\":-1}]", "[{\"id\":\"43?token=secret\"}]", "[{\"id\":43},{\"id\":43}]").forEach { refs ->
            assertNull(parseGogDlcCatalog(json("""{"id":42,"game_type":"game","dlcs":{"products":$refs},"expanded_dlcs":[]}"""), "42"))
        }
    }

    @Test
    fun oversizedCatalogCannotBeSilentlyTruncatedToComplete() {
        val refs = (1..257).joinToString(",") { "{\"id\":${1000 + it}}" }
        assertNull(parseGogDlcCatalog(json("""{"id":42,"game_type":"game","dlcs":{"products":[$refs]},"expanded_dlcs":[]}"""), "42"))
    }

    @Test
    fun steamCatalogUsesTheExactNativeParentNotAMatchedStoreIdentity() {
        val catalog = parseSteamDlcCatalog(json("""{"steam_appid":42,"type":"game","dlc":[43,44]}"""), "42")
        assertNotNull(catalog)
        assertEquals(listOf("43", "44"), catalog!!.items.map { it.id })
        assertNull(parseSteamDlcCatalog(json("""{"steam_appid":99,"type":"game","dlc":[43]}"""), "42"))
    }

    @Test
    fun malformedOrMissingSteamDlcListCannotBecomeCompleteEmpty() {
        listOf("{}", "{\"steam_appid\":42,\"type\":\"game\"}", "{\"steam_appid\":42,\"type\":\"game\",\"dlc\":[{}]}").forEach { raw ->
            assertNull(parseSteamDlcCatalog(json(raw), "42"))
        }
    }

    private fun json(raw: String) = Json.parseToJsonElement(raw).jsonObject
}
