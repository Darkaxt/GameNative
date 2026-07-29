package app.gamenative.ui.data

import app.gamenative.data.GameCompatibilityStatus
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.canonical.CanonicalCardKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCardTest {
    @Test
    fun `source cards retain source identity`() {
        val native = LibraryItem(appId = "STEAM_10", name = "Native")

        val source = LibraryCard.fromSource(native)

        assertEquals(native, source.sourceItemOrNull())
        assertEquals("source:STEAM_10", source.composeKey)
        assertTrue(source.identity is LibraryCardIdentity.SourceCopy)
    }

    @Test
    fun `canonical cards never expose a source item and order owned sources`() {
        val canonical = LibraryCard.canonical(
            key = CanonicalCardKey.Grouped(
                CanonicalGameId.parse("11111111-1111-1111-1111-111111111111"),
            ),
            index = 0,
            name = "Canonical",
            ownedSources = setOf(GameSource.STEAM, GameSource.GOG),
        )

        assertNull(canonical.sourceItemOrNull())
        assertEquals(listOf(GameSource.STEAM, GameSource.GOG), canonical.orderedSources)
        assertEquals(
            "canonical:group:11111111-1111-1111-1111-111111111111",
            canonical.composeKey,
        )
    }

    @Test
    fun `independent canonical compose keys are stable opaque digests`() {
        val key = CanonicalCardKey.Independent(
            OwnedCopyKey(
                accountScope = AccountScope.parse("a".repeat(64)),
                source = GameSource.EPIC,
                stableSourceId = "namespace/catalog",
            ),
        )
        val otherKey = CanonicalCardKey.Independent(
            OwnedCopyKey(
                accountScope = AccountScope.parse("a".repeat(64)),
                source = GameSource.EPIC,
                stableSourceId = "other/catalog",
            ),
        )

        val composeKey = LibraryCard.canonical(
            key = key,
            index = 0,
            name = "Independent",
            ownedSources = setOf(GameSource.EPIC),
        ).composeKey
        val repeatedComposeKey = LibraryCard.canonical(
            key = key,
            index = 1,
            name = "Independent again",
            ownedSources = setOf(GameSource.EPIC),
        ).composeKey
        val otherComposeKey = LibraryCard.canonical(
            key = otherKey,
            index = 0,
            name = "Other",
            ownedSources = setOf(GameSource.EPIC),
        ).composeKey

        assertEquals("canonical:copy:EPIC:f79eb40ea74b5f059684461c", composeKey)
        assertEquals(composeKey, repeatedComposeKey)
        assertNotEquals(composeKey, otherComposeKey)
        assertFalse(composeKey.contains("a".repeat(64)))
        assertFalse(composeKey.contains("namespace/catalog"))
    }

    @Test
    fun `promotional IDs remain promotion identities`() {
        val promotion = LibraryItem(
            index = -1,
            appId = "RECOMMENDED_42",
            name = "Promotion",
            isRecommended = true,
            recommendedGameId = "42",
            recSource = "hero",
        )

        val compatibility = GameCompatibilityStatus.COMPATIBLE
        val stats = GameCardStats(
            runsGpu = 4,
            reviewsDevice = 1,
            reviewsGpu = 2,
            fps = 45,
            sessionSec = 1800,
        )

        val card = LibraryCard.fromPromotion(promotion, compatibility, stats)

        assertEquals(LibraryCardIdentity.Promotion("RECOMMENDED_42"), card.identity)
        assertEquals("promotion:RECOMMENDED_42", card.composeKey)
        assertNull(card.sourceItemOrNull())
        assertEquals(compatibility, card.compatibilityStatus)
        assertEquals(stats, card.gameStats)
        assertTrue(card.isRecommended)
        assertEquals("42", card.recommendedGameId)
        assertEquals("hero", card.recSource)
    }

    @Test
    fun `promotion cards preserve complete icon URLs`() {
        val iconUrl = "https://cdn.example.invalid/featured/icon.png"
        val promotion = LibraryItem(
            appId = "FEATURED_campaign",
            name = "Featured",
            iconHash = iconUrl,
            gameSource = GameSource.STEAM,
            isRecommended = true,
            isFeatured = true,
        )

        val card = LibraryCard.fromPromotion(promotion)

        assertEquals(iconUrl, card.iconUrl)
    }

    @Test
    fun `gate off mapping preserves source presentation compatibility and stats`() {
        val item = LibraryItem(
            index = 7,
            appId = "GOG_42",
            name = "Legacy",
            iconHash = "https://example.invalid/icon.png",
            capsuleImageUrl = "capsule",
            headerImageUrl = "header",
            heroImageUrl = "hero",
            gridHeroImageScale = 1.25f,
            isShared = true,
            gameSource = GameSource.GOG,
            compatibilityStatus = GameCompatibilityStatus.NOT_COMPATIBLE,
            sizeBytes = 1234L,
            isInstalled = true,
            isRecommended = false,
            recommendedGameId = "legacy-recommendation-id",
            recRating = 91,
            recDiscount = "-50%",
            recPrice = "$9.99",
            recBasePrice = "$19.99",
            recSeedCount = 3,
            recSeedIconUrl = "seed-icon",
            recStoreCard = true,
            recSource = "legacy-source",
            isFeatured = false,
        )
        val compatibility = GameCompatibilityStatus.GPU_COMPATIBLE
        val stats = GameCardStats(
            runsGpu = 8,
            reviewsDevice = 2,
            reviewsGpu = 5,
            fps = 60,
            sessionSec = 3600,
        )

        val card = LibraryCard.fromSource(item, compatibility, stats)

        assertEquals(item, card.sourceItemOrNull())
        assertEquals(item.index, card.index)
        assertEquals(item.name, card.name)
        assertEquals(item.clientIconUrl, card.iconUrl)
        assertEquals(item.capsuleImageUrl, card.capsuleImageUrl)
        assertEquals(item.headerImageUrl, card.headerImageUrl)
        assertEquals(item.heroImageUrl, card.heroImageUrl)
        assertEquals(item.gridHeroImageScale, card.gridHeroImageScale)
        assertEquals(setOf(GameSource.GOG), card.ownedSources)
        assertEquals(compatibility, card.compatibilityStatus)
        assertEquals(stats, card.gameStats)
        assertEquals(item.sizeBytes, card.sizeBytes)
        assertEquals(item.isInstalled, card.isInstalled)
        assertEquals(item.isShared, card.isShared)
        assertEquals(item.isRecommended, card.isRecommended)
        assertEquals(item.recommendedGameId, card.recommendedGameId)
        assertEquals(item.recRating, card.recRating)
        assertEquals(item.recDiscount, card.recDiscount)
        assertEquals(item.recPrice, card.recPrice)
        assertEquals(item.recBasePrice, card.recBasePrice)
        assertEquals(item.recSeedCount, card.recSeedCount)
        assertEquals(item.recSeedIconUrl, card.recSeedIconUrl)
        assertEquals(item.recStoreCard, card.recStoreCard)
        assertEquals(item.recSource, card.recSource)
        assertEquals(item.isFeatured, card.isFeatured)
    }
}
