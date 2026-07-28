package app.gamenative.service

import app.gamenative.service.amazon.AmazonApiClient
import app.gamenative.service.epic.parseEpicLibraryPage
import app.gamenative.service.epic.validateEpicCatalogIdentity
import app.gamenative.service.gog.GOGApiClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnershipProviderParsingTest {
    @Test
    fun missingOrMalformedGogOwnershipFailsClosedWhileVerifiedEmptySucceeds() {
        assertTrue(GOGApiClient.parseOwnedGameIds(JSONObject()).isFailure)
        assertTrue(
            GOGApiClient.parseOwnedGameIds(
                JSONObject().put("owned", JSONArray().put("not-a-game-id")),
            ).isFailure,
        )
        assertTrue(
            GOGApiClient.parseOwnedGameIds(
                JSONObject().put("owned", JSONArray().put(1.5)),
            ).isFailure,
        )

        val empty = GOGApiClient.parseOwnedGameIds(JSONObject().put("owned", JSONArray()))

        assertEquals(emptyList<String>(), empty.getOrThrow())
    }

    @Test
    fun malformedGogDetailsFailClosed() {
        assertTrue(GOGApiClient.parseGameDetails(JSONObject(), "123").isFailure)
        assertTrue(
            GOGApiClient.parseGameDetails(
                JSONObject().put("title", "Game").put("game_type", "unknown"),
                "123",
            ).isFailure,
        )

        val parsed = GOGApiClient.parseGameDetails(
            JSONObject().put("title", "Game").put("game_type", "game"),
            "123",
        ).getOrThrow()

        assertEquals("123", parsed.id)
        assertEquals("Game", parsed.title)
    }

    @Test
    fun malformedAmazonPagesFailClosed() {
        assertTrue(AmazonApiClient.parseEntitlementsPage(JSONObject(), emptySet()).isFailure)
        assertTrue(
            AmazonApiClient.parseEntitlementsPage(
                JSONObject().put("entitlements", JSONArray().put(JSONObject())),
                emptySet(),
            ).isFailure,
        )
        assertTrue(
            AmazonApiClient.parseEntitlementsPage(
                JSONObject().put(
                    "entitlements",
                    JSONArray().put(
                        JSONObject()
                            .put("id", "entitlement")
                            .put("product", JSONObject().put("id", 7)),
                    ),
                ),
                emptySet(),
            ).isFailure,
        )
        assertTrue(
            AmazonApiClient.parseEntitlementsPage(
                JSONObject().put("entitlements", JSONArray()).put("nextToken", 7),
                emptySet(),
            ).isFailure,
        )
        assertTrue(
            AmazonApiClient.parseEntitlementsPage(
                JSONObject().put("entitlements", JSONArray()).put("nextToken", "repeat"),
                setOf("repeat"),
            ).isFailure,
        )
    }

    @Test
    fun verifiedEmptyAmazonPageIsComplete() {
        val page = AmazonApiClient.parseEntitlementsPage(
            JSONObject().put("entitlements", JSONArray()),
            emptySet(),
        ).getOrThrow()

        assertTrue(page.games.isEmpty())
        assertEquals(null, page.nextToken)
    }

    @Test
    fun malformedOrRepeatedEpicCursorFailsClosed() {
        val records = JSONArray()
        assertTrue(parseEpicLibraryPage(JSONObject(), emptySet()).isFailure)
        assertTrue(
            parseEpicLibraryPage(
                JSONObject()
                    .put(
                        "records",
                        JSONArray().put(
                            JSONObject()
                                .put("appName", "visible")
                                .put("namespace", "namespace")
                                .put("catalogItemId", 7)
                                .put("platform", "Windows"),
                        ),
                    )
                    .put("responseMetadata", JSONObject()),
                emptySet(),
            ).isFailure,
        )
        assertTrue(
            parseEpicLibraryPage(
                JSONObject()
                    .put("records", records)
                    .put("responseMetadata", JSONObject().put("nextCursor", 7)),
                emptySet(),
            ).isFailure,
        )
        assertTrue(
            parseEpicLibraryPage(
                JSONObject()
                    .put("records", records)
                    .put("responseMetadata", JSONObject().put("nextCursor", "repeat")),
                setOf("repeat"),
            ).isFailure,
        )
        assertTrue(
            parseEpicLibraryPage(
                JSONObject()
                    .put(
                        "records",
                        JSONArray().put(
                            epicRecord("visible", "namespace", "catalog", "PUBLIC", " Windows "),
                        ),
                    )
                    .put("responseMetadata", JSONObject()),
                emptySet(),
            ).isFailure,
        )
    }

    @Test
    fun epicCatalogIdentityMustMatchTheEnumeratedOwnershipRecord() {
        val catalog = JSONObject().put("id", "catalog").put("namespace", "namespace")

        assertTrue(validateEpicCatalogIdentity(catalog, "namespace", "catalog").isSuccess)
        assertTrue(validateEpicCatalogIdentity(catalog, "other", "catalog").isFailure)
        assertTrue(validateEpicCatalogIdentity(catalog, "namespace", "other").isFailure)
        assertTrue(validateEpicCatalogIdentity(JSONObject(), "namespace", "catalog").isFailure)
    }

    @Test
    fun epicPageUsesCurrentAllLibraryVisibilityExclusions() {
        val records = JSONArray()
            .put(epicRecord("visible", "namespace", "catalog", "PUBLIC", "Windows"))
            .put(epicRecord("private", "namespace", "private-catalog", "PRIVATE", "Windows"))
            .put(epicRecord("android", "namespace", "android-catalog", "PUBLIC", "Android"))
        val page = parseEpicLibraryPage(
            JSONObject().put("records", records).put("responseMetadata", JSONObject()),
            emptySet(),
        ).getOrThrow()

        assertEquals(listOf("visible"), page.items.map { it.appName })
        assertEquals(null, page.nextCursor)
    }

    private fun epicRecord(
        appName: String,
        namespace: String,
        catalogItemId: String,
        sandboxType: String,
        platform: String,
    ): JSONObject = JSONObject()
        .put("appName", appName)
        .put("namespace", namespace)
        .put("catalogItemId", catalogItemId)
        .put("sandboxType", sandboxType)
        .put("platform", JSONArray().put(platform))
}
