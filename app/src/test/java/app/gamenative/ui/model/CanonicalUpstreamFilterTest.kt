package app.gamenative.ui.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.data.SteamCollection
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.canonical.CanonicalCardKey
import app.gamenative.library.canonical.CanonicalLibraryCard
import app.gamenative.library.canonical.OwnedCopySummary
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.enums.AppFilter
import app.gamenative.ui.enums.LibraryTab
import java.util.EnumSet
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class CanonicalUpstreamFilterTest {
    @Before
    fun setUp() {
        PrefManager.init(ApplicationProvider.getApplicationContext<Context>())
    }

    @Test
    fun curatedSelectionFiltersCanonicalCards() {
        val selected = card(10)
        val other = card(20)
        val state = state().copy(
            curatedLists = listOf(SteamCollection(id = "4:3", name = "4:3", appIds = setOf(10))),
            selectedCuratedListIds = setOf("4:3"),
        )

        assertEquals(listOf("Game 10"), project(listOf(selected, other), state).cards.map { it.name })
    }

    @Test
    fun unloadedCuratedListsFailOpen() {
        val state = state().copy(curatedLists = null, selectedCuratedListIds = setOf("4:3"))

        assertEquals(2, project(listOf(card(10), card(20)), state).totalCount)
    }

    @Test
    fun curatedAndSteamCollectionSectionsIntersect() {
        val state = state().copy(
            curatedLists = listOf(SteamCollection(id = "4:3", name = "4:3", appIds = setOf(10, 20))),
            selectedCuratedListIds = setOf("4:3"),
            steamCollections = listOf(SteamCollection(id = "collection", name = "Collection", appIds = setOf(20, 30))),
            selectedSteamCollectionIds = setOf("collection"),
        )

        assertEquals(listOf("Game 20"), project(listOf(card(10), card(20), card(30)), state).cards.map { it.name })
    }

    @Test
    fun hiddenSteamIsExcludedButHiddenCollectionKeepsItsCount() {
        val state = state().copy(
            showHiddenGamesByDefault = false,
            steamCollections = listOf(SteamCollection(SteamCollection.ID_HIDDEN, "Hidden", setOf(10))),
        )
        val page = project(listOf(card(10), card(20)), state)

        assertEquals(listOf("Game 20"), page.cards.map { it.name })
        assertEquals(1, page.sourceCounts[GameSource.STEAM])
        assertEquals(1, page.steamCollectionCounts[SteamCollection.ID_HIDDEN])
    }

    @Test
    fun selectingHiddenCollectionExplicitlyRevealsItsSteamCopies() {
        val state = state().copy(
            showHiddenGamesByDefault = false,
            steamCollections = listOf(SteamCollection(SteamCollection.ID_HIDDEN, "Hidden", setOf(10))),
            selectedSteamCollectionIds = setOf(SteamCollection.ID_HIDDEN),
        )

        assertEquals(listOf("Game 10"), project(listOf(card(10), card(20)), state).cards.map { it.name })
    }

    @Test
    fun unloadedHiddenMetadataFailsOpen() {
        assertEquals(2, project(listOf(card(10), card(20)), state().copy(showHiddenGamesByDefault = false)).totalCount)
    }

    @Test
    fun visibleGogCopyKeepsMixedCardButDoesNotRevealHiddenSteamTab() {
        val steam = card(10)
        val gog = steam.copies.single().copy(
            key = OwnedCopyKey(AccountScope("a".repeat(64)), GameSource.GOG, "10"),
            source = GameSource.GOG,
        )
        val mixed = steam.copy(copies = steam.copies + gog, ownedSources = setOf(GameSource.STEAM, GameSource.GOG))
        val state = state().copy(
            showHiddenGamesByDefault = false,
            steamCollections = listOf(SteamCollection(SteamCollection.ID_HIDDEN, "Hidden", setOf(10))),
        )

        assertEquals(1, project(listOf(mixed), state).totalCount)
        assertEquals(0, project(listOf(mixed), state.copy(currentTab = LibraryTab.STEAM)).totalCount)
        assertEquals(1, project(listOf(mixed), state.copy(currentTab = LibraryTab.GOG)).totalCount)
        assertEquals(2, mixed.copies.size)
    }

    @Test
    fun hiddenGogCopyDoesNotEraseOwnershipButIsNotRendered() {
        val steam = card(10)
        val gog = steam.copies.single().copy(
            key = OwnedCopyKey(AccountScope("a".repeat(64)), GameSource.GOG, "10"),
            source = GameSource.GOG,
            isHidden = true,
        )
        val card = steam.copy(copies = listOf(gog), ownedSources = setOf(GameSource.GOG), preferredCopy = gog.key, steamCollectionAppIds = emptySet())

        assertEquals(0, project(listOf(card), state().copy(showHiddenGamesByDefault = false)).totalCount)
        assertEquals(1, project(listOf(card), state().copy(showHiddenGamesByDefault = true)).totalCount)
        assertEquals(listOf(gog), card.copies)
    }

    @Test
    fun vrOnlyNeedsVrBucketWhileVrSupportedStillMatchesGameBucket() {
        val only = card(10).let { it.copy(copies = listOf(it.copies.single().copy(isVrOnly = true))) }
        val supported = card(20).let { it.copy(copies = listOf(it.copies.single().copy(isVrSupported = true))) }
        val flat = card(30)

        assertEquals(listOf("Game 20", "Game 30"), project(listOf(only, supported, flat), state()).cards.map { it.name })
        assertEquals(listOf("Game 10", "Game 20"), project(listOf(only, supported, flat), state().copy(appInfoSortType = EnumSet.of(AppFilter.VR))).cards.map { it.name })
    }

    private fun state() = LibraryState(
        appInfoSortType = EnumSet.of(AppFilter.GAME),
        currentTab = LibraryTab.ALL,
        showSteamInLibrary = true,
        showGOGInLibrary = true,
        showEpicInLibrary = true,
        showAmazonInLibrary = true,
        showCustomGamesInLibrary = true,
        selectedSteamCollectionIds = emptySet(),
        selectedCuratedListIds = emptySet(),
        steamReviewMinimum = null,
    )

    private fun project(cards: List<CanonicalLibraryCard>, state: LibraryState) = CanonicalLibraryFilter.project(
        cards = cards,
        state = state,
        paginationPage = 0,
        pageSize = 50,
        promotion = null,
        showRecommendations = false,
        compatibility = { null },
    )

    private fun card(appId: Int): CanonicalLibraryCard {
        val id = CanonicalGameId("00000000-0000-0000-0000-${appId.toString().padStart(12, '0')}")
        val key = OwnedCopyKey(AccountScope("a".repeat(64)), GameSource.STEAM, appId.toString())
        val copy = OwnedCopySummary(
            key = key,
            source = GameSource.STEAM,
            nativeTitle = "Game $appId",
            installPath = null,
            installedSizeBytes = null,
            branchOrVersion = null,
            isInstalled = false,
            isDownloading = false,
            hasPartialDownload = false,
            updateAvailable = false,
            isShared = false,
            lastPlayedEpochMs = null,
            playtimeMinutes = null,
            capabilities = emptySet(),
            unavailableReason = null,
            canSeparateMatch = false,
            matchMethod = MatchMethod.DIRECT_STEAM,
            confidence = MatchConfidence.HIGH,
            decisionSource = MatchDecisionSource.AUTOMATIC,
            decisionCandidateSteamAppId = appId,
            decisionResolverVersion = 5,
            decisionRevision = 1,
        )
        return CanonicalLibraryCard(
            key = CanonicalCardKey.Grouped(id),
            canonicalId = id,
            displayName = "Game $appId",
            appType = CanonicalAppType.GAME,
            iconUrl = "",
            capsuleImageUrl = "",
            headerImageUrl = "",
            heroImageUrl = "",
            gridHeroImageScale = 1f,
            aliases = emptySet(),
            ownedSources = setOf(GameSource.STEAM),
            copies = listOf(copy),
            preferredCopy = key,
            steamCollectionAppIds = setOf(appId),
            isShared = false,
            steamAppId = appId,
        )
    }
}
