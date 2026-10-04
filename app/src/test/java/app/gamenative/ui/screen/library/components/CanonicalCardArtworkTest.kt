package app.gamenative.ui.screen.library.components

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.library.canonical.CanonicalCardArtwork
import app.gamenative.library.canonical.CanonicalCardKey
import app.gamenative.ui.data.LibraryCard
import app.gamenative.ui.enums.PaneType
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalCardArtworkTest {
    private val context = mockk<Context>()

    @Test
    fun cachedSteamCapsulesRetainSourceCapsuleFailureFallbackForEveryOwnedStore() {
        GameSource.entries.forEach { source ->
            val urls = getGridImageUrl(context, card(source), PaneType.GRID_CAPSULE)
            assertEquals("steam-capsule", urls.primary)
            assertEquals("source-capsule", urls.fallback)
        }
    }

    @Test
    fun cachedSteamHeadersRetainSourceHeaderFailureFallbackForEveryOwnedStore() {
        GameSource.entries.forEach { source ->
            val urls = getGridImageUrl(context, card(source), PaneType.GRID_HERO)
            assertEquals("steam-header", urls.primary)
            assertEquals("source-header", urls.fallback)
        }
    }

    @Test
    fun missingSourceHeaderUsesItsHeroOrIconAndNeverRetriesTheSameUrl() {
        val fallback = CanonicalCardArtwork("source-icon", "", "", "source-hero", 1.2f)
        val card = card(GameSource.GOG).copy(artworkFallback = fallback)
        assertEquals("source-hero", getGridImageUrl(context, card, PaneType.GRID_HERO).fallback)
        assertEquals("source-icon", getGridImageUrl(context, card, PaneType.GRID_CAPSULE).fallback)
        val duplicate = card.copy(artworkFallback = fallback.copy(headerImageUrl = "steam-header"))
        assertEquals("source-hero", getGridImageUrl(context, duplicate, PaneType.GRID_HERO).fallback)
    }

    @Test
    fun sourceOnlyCardsKeepTheirExistingImageSelection() {
        val card = card(GameSource.GOG).copy(artworkFallback = null)
        assertEquals(GridImageUrls("steam-header", "steam-hero"), getGridImageUrl(context, card, PaneType.GRID_HERO))
        assertEquals(GridImageUrls("steam-capsule", "steam-icon"), getGridImageUrl(context, card, PaneType.GRID_CAPSULE))
    }

    private fun card(source: GameSource) = LibraryCard.canonical(
        key = CanonicalCardKey.Grouped(CanonicalGameId.parse("11111111-1111-1111-1111-111111111111")),
        index = 0,
        name = "Cached Steam title",
        iconUrl = "steam-icon",
        capsuleImageUrl = "steam-capsule",
        headerImageUrl = "steam-header",
        heroImageUrl = "steam-hero",
        ownedSources = setOf(source),
        artworkFallback = CanonicalCardArtwork("source-icon", "source-capsule", "source-header", "source-hero", 1.2f),
    )
}
