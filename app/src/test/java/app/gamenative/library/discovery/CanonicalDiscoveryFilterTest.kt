package app.gamenative.library.discovery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import app.gamenative.data.GameCompatibilityStatus
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
import app.gamenative.library.canonical.OwnedCopyOperation
import app.gamenative.library.canonical.OwnedCopySummary
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.enums.AppFilter
import app.gamenative.ui.enums.LibraryTab
import app.gamenative.ui.enums.SortOption
import app.gamenative.ui.model.CanonicalLibraryFilter
import app.gamenative.utils.DeviceGameStatsService.DeviceGameStats
import java.util.EnumSet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class CanonicalDiscoveryFilterTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PrefManager.init(context)
        clearPreferencesAndAwait()
    }

    @After
    fun tearDown() {
        clearPreferencesAndAwait()
    }

    @Test
    fun multipleSelectedTagsUseExplicitAnyOrAllMatching() {
        val both = card(1, "Both", tagIds = setOf(COOP, MULTIPLAYER))
        val coop = card(2, "Co-op", tagIds = setOf(COOP))
        val unclassified = card(3, "Unclassified")
        val selected = setOf(COOP, MULTIPLAYER)

        val any = project(
            listOf(both, coop, unclassified),
            state(selectedTags = selected, tagMode = TagMatchMode.ANY),
        )
        val all = project(
            listOf(both, coop, unclassified),
            state(selectedTags = selected, tagMode = TagMatchMode.ALL),
        )

        assertEquals(listOf("Both", "Co-op"), any.cards.map { it.name })
        assertEquals(listOf("Both"), all.cards.map { it.name })
        assertEquals(2, any.tagClassifiedCount)
        assertEquals(3, any.tagTotalCount)
    }

    @Test
    fun genreOrAndTagModeCombineWithAnd() {
        val cards = listOf(
            card(1, "Action co-op", genreKeys = setOf(ACTION), tagIds = setOf(COOP)),
            card(2, "Strategy co-op", genreKeys = setOf(STRATEGY), tagIds = setOf(COOP)),
            card(3, "Action multiplayer", genreKeys = setOf(ACTION), tagIds = setOf(MULTIPLAYER)),
            card(
                4,
                "Strategy both",
                genreKeys = setOf(STRATEGY),
                tagIds = setOf(COOP, MULTIPLAYER),
            ),
        )

        val page = project(
            cards,
            state(
                selectedGenres = setOf(ACTION, STRATEGY),
                selectedTags = setOf(COOP, MULTIPLAYER),
                tagMode = TagMatchMode.ALL,
            ),
        )

        assertEquals(listOf("Strategy both"), page.cards.map { it.name })
        assertEquals(1, page.totalCount)
    }

    @Test
    fun multipleSelectedGenresMatchAnyGenreAfterCanonicalGrouping() {
        val action = card(1, "Action", genreKeys = setOf(ACTION), copySources = listOf(GameSource.STEAM, GameSource.GOG))
        val strategy = card(2, "Strategy", genreKeys = setOf(STRATEGY))
        val unclassified = card(3, "Unclassified")

        val page = project(
            cards = listOf(action, strategy, unclassified),
            state = state(selectedGenres = setOf(ACTION, STRATEGY)),
        )

        assertEquals(listOf("Action", "Strategy"), page.cards.map { it.name })
        assertEquals(2, page.totalCount)
        assertEquals(2, page.allCount)
        assertEquals(2, page.genreClassifiedCount)
        assertEquals(3, page.genreTotalCount)
    }

    @Test
    fun genreGroupUsesAndWithEveryExistingFilterGroup() {
        val matchingGenre = card(1, "Candidate", genreKeys = setOf(ACTION))
        val selected = setOf(ACTION)

        assertEquals(0, project(listOf(matchingGenre), state(search = "missing", selectedGenres = selected)).totalCount)
        assertEquals(
            0,
            project(
                listOf(matchingGenre),
                state(tab = LibraryTab.GOG, selectedGenres = selected),
            ).totalCount,
        )
        assertEquals(
            0,
            project(
                listOf(matchingGenre),
                state(filters = filters(AppFilter.APPLICATION), selectedGenres = selected),
            ).totalCount,
        )
        assertEquals(
            0,
            project(
                listOf(matchingGenre),
                state(filters = filters(AppFilter.GAME, AppFilter.INSTALLED), selectedGenres = selected),
            ).totalCount,
        )
        assertEquals(
            0,
            project(
                listOf(matchingGenre),
                state(filters = filters(AppFilter.GAME, AppFilter.COMPATIBLE), selectedGenres = selected),
                compatibility = { GameCompatibilityStatus.NOT_COMPATIBLE },
            ).totalCount,
        )
        assertEquals(
            0,
            project(
                listOf(matchingGenre),
                state(
                    filters = filters(AppFilter.GAME, AppFilter.PLAYABLE),
                    selectedGenres = selected,
                    deviceStats = mapOf(
                        GameSource.STEAM to mapOf(
                            "Candidate STEAM" to DeviceGameStats(1, 20, 0, 60),
                        ),
                    ),
                ),
            ).totalCount,
        )
        assertEquals(
            0,
            project(
                listOf(matchingGenre),
                state(
                    selectedGenres = selected,
                    selectedCollections = setOf("other"),
                    collections = listOf(SteamCollection("other", "Other", setOf(999))),
                ),
            ).totalCount,
        )
    }

    @Test
    fun tagGroupUsesAndWithEveryExistingFilterGroup() {
        val matchingTag = card(10, "Candidate", tagIds = setOf(COOP))
        val selected = setOf(COOP)
        val incompatible: (CanonicalLibraryCard) -> GameCompatibilityStatus? = {
            GameCompatibilityStatus.NOT_COMPATIBLE
        }
        val states = listOf(
            state(search = "missing", selectedTags = selected),
            state(tab = LibraryTab.GOG, selectedTags = selected),
            state(filters = filters(AppFilter.APPLICATION), selectedTags = selected),
            state(filters = filters(AppFilter.GAME, AppFilter.INSTALLED), selectedTags = selected),
            state(filters = filters(AppFilter.GAME, AppFilter.COMPATIBLE), selectedTags = selected),
            state(
                filters = filters(AppFilter.GAME, AppFilter.PLAYABLE),
                selectedTags = selected,
                deviceStats = mapOf(
                    GameSource.STEAM to mapOf(
                        "Candidate STEAM" to DeviceGameStats(1, 20, 0, 60),
                    ),
                ),
            ),
            state(
                selectedTags = selected,
                selectedCollections = setOf("other"),
                collections = listOf(SteamCollection("other", "Other", setOf(999))),
            ),
        )

        states.forEachIndexed { index, filterState ->
            val compatibility = if (index == 4) incompatible else { _: CanonicalLibraryCard -> null }
            assertEquals(0, project(listOf(matchingTag), filterState, compatibility = compatibility).totalCount)
        }
    }

    @Test
    fun genreFilteringRunsBeforeCountsSortAndPaginationBoundaries() {
        val cards = listOf(
            card(1, "Zulu", genreKeys = setOf(ACTION), tagIds = setOf(COOP)),
            card(2, "Alpha", tagIds = setOf(COOP)),
            card(3, "Bravo", genreKeys = setOf(ACTION), tagIds = setOf(COOP)),
            card(4, "Charlie", genreKeys = setOf(ACTION), tagIds = setOf(COOP)),
            card(5, "Wrong tag", genreKeys = setOf(ACTION), tagIds = setOf(MULTIPLAYER)),
        )

        val page = project(
            cards = cards,
            state = state(selectedGenres = setOf(ACTION), selectedTags = setOf(COOP)),
            paginationPage = 1,
            pageSize = 2,
        )

        assertEquals(3, page.totalCount)
        assertEquals(3, page.allCount)
        assertEquals(3, page.sourceCounts.getValue(GameSource.STEAM))
        assertEquals(1, page.lastPage)
        assertEquals(1, page.paginationPage)
        assertEquals(listOf("Bravo", "Charlie", "Zulu"), page.cards.map { it.name })
    }

    @Test
    fun clearingGenresRestoresUnclassifiedCardsImmediately() {
        val cards = listOf(
            card(1, "Classified", genreKeys = setOf(ACTION)),
            card(2, "Unclassified"),
        )

        val filtered = project(cards, state(selectedGenres = setOf(ACTION)))
        val cleared = project(cards, state(selectedGenres = emptySet()))

        assertEquals(listOf("Classified"), filtered.cards.map { it.name })
        assertEquals(listOf("Classified", "Unclassified"), cleared.cards.map { it.name })
        assertEquals(2, cleared.totalCount)
    }

    @Test
    fun unknownTagSelectionsAreIgnoredWithoutBreakingGenresAndClearRestoresUnclassifiedCards() {
        val cards = listOf(
            card(1, "Classified", genreKeys = setOf(ACTION), tagIds = setOf(COOP)),
            card(2, "Genre only", genreKeys = setOf(ACTION)),
            card(3, "Unclassified"),
        )

        val unknown = project(
            cards,
            state(selectedGenres = setOf(ACTION), selectedTags = setOf(999_999)),
        )
        val selected = project(cards, state(selectedTags = setOf(COOP)))
        val cleared = project(cards, state(selectedTags = emptySet()))

        assertEquals(listOf("Classified", "Genre only"), unknown.cards.map { it.name })
        assertEquals(listOf("Classified"), selected.cards.map { it.name })
        assertEquals(listOf("Classified", "Genre only", "Unclassified"), cleared.cards.map { it.name })
    }

    @Test
    fun sortedTagSelectionsAndModeSurvivePreferenceRestart() {
        PrefManager.libraryTagIds = linkedSetOf(MULTIPLAYER, COOP)
        PrefManager.libraryTagMatchMode = TagMatchMode.ALL
        awaitPreference {
            PrefManager.libraryTagIds.size == 2 && PrefManager.libraryTagMatchMode == TagMatchMode.ALL
        }

        PrefManager.init(context)

        assertEquals(listOf(COOP, MULTIPLAYER), PrefManager.libraryTagIds.toList())
        assertEquals(TagMatchMode.ALL, PrefManager.libraryTagMatchMode)
    }

    @Test
    fun sortedGenreSelectionsSurvivePreferenceRestart() {
        PrefManager.libraryGenreKeys = linkedSetOf(STRATEGY, ACTION)
        awaitPreference { PrefManager.libraryGenreKeys.size == 2 }

        PrefManager.init(context)

        assertEquals(listOf(ACTION, STRATEGY), PrefManager.libraryGenreKeys.toList())
    }

    @Test
    fun popularityThresholdEdgesExcludeUnknownOnlyWhenActiveAndClearRestoresThem() {
        val cards = listOf(
            card(1, "Unknown", steamReviewCount = null),
            card(2, "Ninety nine", steamReviewCount = 99),
            card(3, "One hundred", steamReviewCount = 100),
            card(4, "Nine hundred ninety nine", steamReviewCount = 999),
            card(5, "One thousand", steamReviewCount = 1_000),
            card(6, "Nine thousand nine hundred ninety nine", steamReviewCount = 9_999),
            card(7, "Ten thousand", steamReviewCount = 10_000),
        )

        assertEquals(7, project(cards, state(minimumReviews = null)).totalCount)
        assertEquals(
            listOf(
                "Nine hundred ninety nine",
                "Nine thousand nine hundred ninety nine",
                "One hundred",
                "One thousand",
                "Ten thousand",
            ),
            project(cards, state(minimumReviews = 100)).cards.map { it.name },
        )
        assertEquals(
            listOf("Nine thousand nine hundred ninety nine", "One thousand", "Ten thousand"),
            project(cards, state(minimumReviews = 1_000)).cards.map { it.name },
        )
        assertEquals(
            listOf("Ten thousand"),
            project(cards, state(minimumReviews = 10_000)).cards.map { it.name },
        )
        assertEquals(
            listOf(
                "Nine hundred ninety nine",
                "Nine thousand nine hundred ninety nine",
                "Ninety nine",
                "One hundred",
                "One thousand",
                "Ten thousand",
                "Unknown",
            ),
            project(cards, state(minimumReviews = null)).cards.map { it.name },
        )
    }

    @Test
    fun gogOnlyTrustedSteamPopularityPassesAllAndGogButNotSteamOwnershipTab() {
        val cards = listOf(
            card(
                number = 1,
                name = "GOG only",
                copySources = listOf(GameSource.GOG),
                steamAppId = 42,
                steamReviewCount = 100,
            ),
        )

        assertEquals(1, project(cards, state(minimumReviews = 100)).totalCount)
        assertEquals(
            1,
            project(cards, state(tab = LibraryTab.GOG, minimumReviews = 100)).totalCount,
        )
        assertEquals(
            0,
            project(cards, state(tab = LibraryTab.STEAM, minimumReviews = 100)).totalCount,
        )
    }

    @Test
    fun popularitySortUsesDescendingKnownCountsNullLastAndExistingNameTieBreak() {
        val cards = listOf(
            card(1, "Unknown alpha", steamReviewCount = null),
            card(2, "Tie zulu", steamReviewCount = 1_000),
            card(3, "Highest", steamReviewCount = 10_000),
            card(4, "Tie alpha", steamReviewCount = 1_000),
            card(5, "Unknown bravo", steamReviewCount = null),
        )

        val page = project(
            cards,
            state(sort = SortOption.STEAM_REVIEW_COUNT),
        )

        assertEquals(
            listOf("Highest", "Tie alpha", "Tie zulu", "Unknown alpha", "Unknown bravo"),
            page.cards.map { it.name },
        )
    }

    @Test
    fun popularityThresholdAndsWithAllExistingFilterGroupsBeforeCountsAndPagination() {
        val popular = card(
            1,
            "Popular action",
            genreKeys = setOf(ACTION),
            tagIds = setOf(COOP),
            steamReviewCount = 100,
        )
        val unpopular = card(
            2,
            "Unpopular action",
            genreKeys = setOf(ACTION),
            tagIds = setOf(COOP),
            steamReviewCount = 99,
        )
        val page = project(
            cards = listOf(popular, unpopular),
            state = state(
                search = "action",
                selectedGenres = setOf(ACTION),
                selectedTags = setOf(COOP),
                minimumReviews = 100,
            ),
            pageSize = 1,
        )

        assertEquals(1, page.totalCount)
        assertEquals(1, page.allCount)
        assertEquals(1, page.sourceCounts.getValue(GameSource.STEAM))
        assertEquals(0, page.lastPage)
        assertEquals(listOf("Popular action"), page.cards.map { it.name })
        assertEquals(2, page.steamPopularityEligibleCount)
        assertEquals(2, page.steamPopularityKnownCount)
    }

    @Test
    fun popularityThresholdAndSortSurvivePreferenceRestart() {
        PrefManager.librarySteamReviewMinimum = 1_000
        PrefManager.librarySortOption = SortOption.STEAM_REVIEW_COUNT
        awaitPreference {
            PrefManager.librarySteamReviewMinimum == 1_000 &&
                PrefManager.librarySortOption == SortOption.STEAM_REVIEW_COUNT
        }

        PrefManager.init(context)

        assertEquals(1_000, PrefManager.librarySteamReviewMinimum)
        assertEquals(SortOption.STEAM_REVIEW_COUNT, PrefManager.librarySortOption)
    }

    @Test(timeout = 5_000L)
    fun nineHundredPopularityCardsFilterSortCountAndPaginateInOnePass() {
        val cards = List(900) { index ->
            card(
                number = index + 1,
                name = "Game ${index.toString().padStart(3, '0')}",
                steamReviewCount = if (index % 5 == 0) null else index * 100,
            )
        }

        val page = project(
            cards = cards,
            state = state(
                minimumReviews = 10_000,
                sort = SortOption.STEAM_REVIEW_COUNT,
            ),
            paginationPage = 1,
            pageSize = 50,
        )

        assertEquals(640, page.totalCount)
        assertEquals(100, page.cards.size)
        assertEquals(12, page.lastPage)
        assertEquals(900, page.steamPopularityEligibleCount)
        assertEquals(720, page.steamPopularityKnownCount)
        assertEquals("Game 899", page.cards.first().name)
    }

    @Test(timeout = 5_000L)
    fun nineHundredCardsCompleteInOneInMemoryPass() {
        val cards = List(900) { index ->
            card(
                number = index + 1,
                name = "Game ${index.toString().padStart(3, '0')}",
                genreKeys = if (index % 3 == 0) setOf(ACTION) else emptySet(),
                tagIds = if (index % 6 == 0) setOf(COOP, MULTIPLAYER) else emptySet(),
            )
        }

        val page = project(
            cards = cards,
            state = state(
                selectedGenres = setOf(ACTION),
                selectedTags = setOf(COOP, MULTIPLAYER),
                tagMode = TagMatchMode.ALL,
            ),
            pageSize = 50,
        )

        assertEquals(150, page.totalCount)
        assertEquals(50, page.cards.size)
        assertEquals(300, page.genreClassifiedCount)
        assertEquals(150, page.tagClassifiedCount)
        assertEquals(900, page.genreTotalCount)
        assertEquals(900, page.tagTotalCount)
    }

    private fun project(
        cards: List<CanonicalLibraryCard>,
        state: LibraryState,
        paginationPage: Int = 0,
        pageSize: Int = 50,
        compatibility: (CanonicalLibraryCard) -> GameCompatibilityStatus? = { null },
    ) = CanonicalLibraryFilter.project(
        cards = cards,
        state = state,
        paginationPage = paginationPage,
        pageSize = pageSize,
        promotion = null,
        showRecommendations = false,
        compatibility = compatibility,
    )

    private fun state(
        filters: EnumSet<AppFilter> = filters(AppFilter.GAME),
        search: String = "",
        tab: LibraryTab = LibraryTab.ALL,
        selectedGenres: Set<String> = emptySet(),
        selectedTags: Set<Int> = emptySet(),
        tagMode: TagMatchMode = TagMatchMode.ANY,
        tagFacets: List<SteamTagFacet> = listOf(
            SteamTagFacet(COOP, "Co-op"),
            SteamTagFacet(MULTIPLAYER, "Multiplayer"),
        ),
        selectedCollections: Set<String> = emptySet(),
        collections: List<SteamCollection>? = emptyList(),
        deviceStats: Map<GameSource, Map<String, DeviceGameStats>> = emptyMap(),
        minimumReviews: Int? = null,
        sort: SortOption = SortOption.NAME_ASC,
    ) = LibraryState(
        appInfoSortType = filters,
        searchQuery = search,
        currentSortOption = sort,
        steamReviewMinimum = minimumReviews,
        currentTab = tab,
        discoveryFilters = DiscoveryFilterState(
            selectedGenreKeys = selectedGenres,
            selectedTagIds = selectedTags,
            tagMatchMode = tagMode,
        ),
        tagFacets = tagFacets,
        selectedSteamCollectionIds = selectedCollections,
        steamCollections = collections,
        deviceGameStats = deviceStats,
        showSteamInLibrary = true,
        showGOGInLibrary = true,
        showEpicInLibrary = true,
        showAmazonInLibrary = true,
        showCustomGamesInLibrary = true,
    )

    private fun card(
        number: Int,
        name: String,
        genreKeys: Set<String> = emptySet(),
        tagIds: Set<Int> = emptySet(),
        copySources: List<GameSource> = listOf(GameSource.STEAM),
        steamAppId: Int? = number,
        steamReviewCount: Int? = null,
    ): CanonicalLibraryCard {
        val canonicalId = CanonicalGameId.parse(
            "00000000-0000-0000-0000-${number.toString().padStart(12, '0')}",
        )
        val copies = copySources.mapIndexed { index, source -> copy(source, "$number-$index", name) }
        return CanonicalLibraryCard(
            key = CanonicalCardKey.Grouped(canonicalId),
            canonicalId = canonicalId,
            displayName = name,
            appType = CanonicalAppType.GAME,
            iconUrl = "",
            capsuleImageUrl = "",
            headerImageUrl = "",
            heroImageUrl = "",
            gridHeroImageScale = 1f,
            aliases = setOf(name),
            ownedSources = copies.mapTo(linkedSetOf(), OwnedCopySummary::source),
            copies = copies,
            preferredCopy = null,
            steamCollectionAppIds = copies.filter { it.source == GameSource.STEAM }
                .mapNotNull { it.key.stableSourceId.substringBefore('-').toIntOrNull() }
                .toSet(),
            isShared = false,
            steamAppId = steamAppId,
            steamReviewCount = steamReviewCount,
            genreKeys = genreKeys,
            genreLabels = genreKeys.associateWith { key ->
                when (key) {
                    ACTION -> "Action"
                    STRATEGY -> "Strategy"
                    else -> "Genre"
                }
            },
            tagIds = tagIds,
        )
    }

    private fun copy(source: GameSource, id: String, name: String): OwnedCopySummary {
        val key = OwnedCopyKey(AccountScope.parse("a".repeat(64)), source, id)
        return OwnedCopySummary(
            key = key,
            source = source,
            nativeTitle = "$name ${source.name}",
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
            capabilities = setOf(OwnedCopyOperation.OPEN_SOURCE_DETAILS),
            unavailableReason = null,
            canSeparateMatch = true,
            matchMethod = MatchMethod.DIRECT_STEAM,
            confidence = MatchConfidence.VERIFIED,
            decisionSource = MatchDecisionSource.AUTOMATIC,
            decisionCandidateSteamAppId = null,
            decisionResolverVersion = 1,
            decisionRevision = 1L,
        )
    }

    private fun filters(vararg values: AppFilter): EnumSet<AppFilter> =
        if (values.isEmpty()) EnumSet.noneOf(AppFilter::class.java)
        else EnumSet.copyOf(values.toList())

    private fun clearPreferencesAndAwait() {
        if (PrefManager.librarySteamReviewMinimum != null ||
            PrefManager.librarySortOption != SortOption.INSTALLED_FIRST
        ) {
            PrefManager.librarySteamReviewMinimum = null
            PrefManager.librarySortOption = SortOption.INSTALLED_FIRST
            awaitPreference {
                PrefManager.librarySteamReviewMinimum == null &&
                    PrefManager.librarySortOption == SortOption.INSTALLED_FIRST
            }
        }
        if (PrefManager.libraryGenreKeys.isNotEmpty()) {
            PrefManager.libraryGenreKeys = emptySet()
            awaitPreference { PrefManager.libraryGenreKeys.isEmpty() }
        }
        if (PrefManager.libraryTagIds.isNotEmpty() || PrefManager.libraryTagMatchMode != TagMatchMode.ANY) {
            PrefManager.libraryTagIds = emptySet()
            PrefManager.libraryTagMatchMode = TagMatchMode.ANY
            awaitPreference {
                PrefManager.libraryTagIds.isEmpty() && PrefManager.libraryTagMatchMode == TagMatchMode.ANY
            }
        }
    }

    private fun awaitPreference(condition: () -> Boolean) {
        repeat(200) {
            if (condition()) return
            Thread.sleep(10L)
        }
        assertTrue("Preference update did not settle", condition())
    }

    private companion object {
        const val ACTION = "steam:1"
        const val STRATEGY = "steam:2"
        const val COOP = 1685
        const val MULTIPLAYER = 3859
    }
}
