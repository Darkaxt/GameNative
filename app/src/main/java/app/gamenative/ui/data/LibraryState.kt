package app.gamenative.ui.data

import app.gamenative.PrefManager
import app.gamenative.data.GameCompatibilityStatus
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.SteamCollection
import app.gamenative.library.canonical.CanonicalPublicFailure
import app.gamenative.library.canonical.OwnedCopySummary
import app.gamenative.library.discovery.DiscoveryFilterState
import app.gamenative.library.discovery.GameFacet
import app.gamenative.library.discovery.SteamPopularityEnrichmentProgress
import app.gamenative.library.discovery.SteamTagFacet
import app.gamenative.library.discovery.immutableGenreKeys
import app.gamenative.library.discovery.immutableTagIds
import app.gamenative.ui.enums.AppFilter
import app.gamenative.utils.DeviceGameStatsService.DeviceGameStats
import app.gamenative.ui.enums.LibraryTab
import app.gamenative.ui.enums.SortOption
import java.util.EnumSet

data class LibraryState(
    val appInfoSortType: EnumSet<AppFilter> = PrefManager.libraryFilter,
    val cards: List<LibraryCard> = emptyList(),
    val isRefreshing: Boolean = false,

    // Human readable, not 0-indexed
    val totalAppsInFilter: Int = 0,
    val currentPaginationPage: Int = 1,
    val lastPaginationPage: Int = 1,

    val modalBottomSheet: Boolean = false,

    val isSearching: Boolean = false,
    val searchQuery: String = "",

    // App Source filters (Steam / Custom Games / GOG / Epic / Amazon)
    val showSteamInLibrary: Boolean = PrefManager.showSteamInLibrary,
    val showCustomGamesInLibrary: Boolean = PrefManager.showCustomGamesInLibrary,
    val showGOGInLibrary: Boolean = PrefManager.showGOGInLibrary,
    val showEpicInLibrary: Boolean = PrefManager.showEpicInLibrary,
    val showAmazonInLibrary: Boolean = PrefManager.showAmazonInLibrary,

    // Steam collections filter
    val selectedSteamCollectionIds: Set<String> = PrefManager.librarySteamCollections,
    val steamCollections: List<SteamCollection>? = null, // null = not loaded
    val skippedDynamicCollections: Boolean = false,
    val steamCollectionCounts: Map<String, Int> = emptyMap(),

    // Canonical Steam-first genre and tag discovery.
    val discoveryFilters: DiscoveryFilterState = DiscoveryFilterState(
        selectedGenreKeys = immutableGenreKeys(PrefManager.libraryGenreKeys),
        selectedTagIds = immutableTagIds(PrefManager.libraryTagIds),
        tagMatchMode = PrefManager.libraryTagMatchMode,
    ),
    val genreFacets: List<GameFacet> = emptyList(),
    val genreClassifiedCount: Int = 0,
    val genreTotalCount: Int = 0,
    val tagFacets: List<SteamTagFacet> = emptyList(),
    val tagClassifiedCount: Int = 0,
    val tagTotalCount: Int = 0,
    val steamReviewMinimum: Int? = PrefManager.librarySteamReviewMinimum,
    val steamPopularityKnownCount: Int = 0,
    val steamPopularityEligibleCount: Int = 0,
    val steamPopularityProgress: SteamPopularityEnrichmentProgress = SteamPopularityEnrichmentProgress(),

    // Loading state for skeleton loaders
    val isLoading: Boolean = false,

    // Fixed recovery reason while canonical cards are unavailable or unsupported.
    val canonicalPublicFailure: CanonicalPublicFailure? = null,

    // Monotonic in-memory signal for accepted authoritative canonical snapshots.
    // Carries no account, game, title, or source-native identity.
    val canonicalSnapshotRevision: Long = 0L,

    // Refresh counter that increments when custom game images are fetched
    // Used to trigger UI recomposition to show newly downloaded images
    val imageRefreshCounter: Long = 0,

    // Compatibility status map: game name -> compatibility status
    val compatibilityMap: Map<String, GameCompatibilityStatus> = emptyMap(),

    // Device-specific play stats, grouped by platform then game name
    val deviceGameStats: Map<GameSource, Map<String, DeviceGameStats>> = emptyMap(),

    // GPU-specific play stats (across all devices with this GPU), grouped by platform then game name
    val gpuGameStats: Map<GameSource, Map<String, DeviceGameStats>> = emptyMap(),

    // Sort option for the library
    val currentSortOption: SortOption = PrefManager.librarySortOption,

    // Options panel open state
    val isOptionsPanelOpen: Boolean = false,

    // Current library tab for quick filter access
    val currentTab: LibraryTab = LibraryTab.ALL,

    // Per-source game counts for tab badges
    val allCount: Int = 0,
    val steamCount: Int = 0,
    val gogCount: Int = 0,
    val epicCount: Int = 0,
    val amazonCount: Int = 0,
    val localCount: Int = 0,
)

/**
 * Stats shown on a library card. Runs and 5-star reviews are counts that default to 0 when their
 * dataset has no entry (absence means "none recorded"). FPS and session are device measurements
 * that are unknown without a run, so they are null (rendered as "?") and never fall back to GPU.
 */
data class GameCardStats(
    val runsGpu: Int,
    val reviewsDevice: Int,
    val reviewsGpu: Int,
    val fps: Int?,
    val sessionSec: Int?,
)

fun LibraryState.statsFor(item: LibraryItem): GameCardStats? = statsFor(item.gameSource, item.name)

/** Combined device + GPU stats for a game, or null when neither dataset has an entry. */
fun LibraryState.statsFor(source: GameSource, name: String): GameCardStats? {
    val device = deviceGameStats[source]?.get(name)
    val gpu = gpuGameStats[source]?.get(name)
    if (device == null && gpu == null) return null
    return GameCardStats(
        runsGpu = gpu?.successfulRuns ?: 0,
        reviewsDevice = device?.fiveStarReviews ?: 0,
        reviewsGpu = gpu?.fiveStarReviews ?: 0,
        fps = device?.medianFps,
        sessionSec = device?.medianSessionSec,
    )
}

/** Component-wise maximum for the exact source/native-title tuples represented by a card. */
fun LibraryState.statsFor(copies: List<OwnedCopySummary>): GameCardStats? {
    val values = copies.mapNotNull { copy -> statsFor(copy.source, copy.nativeTitle) }
    if (values.isEmpty()) return null
    return GameCardStats(
        runsGpu = values.maxOf(GameCardStats::runsGpu),
        reviewsDevice = values.maxOf(GameCardStats::reviewsDevice),
        reviewsGpu = values.maxOf(GameCardStats::reviewsGpu),
        fps = values.mapNotNull(GameCardStats::fps).maxOrNull(),
        sessionSec = values.mapNotNull(GameCardStats::sessionSec).maxOrNull(),
    )
}
