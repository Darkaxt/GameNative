package app.gamenative.ui.model

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.BuildConfig
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.GameCompatibilityStatus
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.gog.GogRecommendationsRepository
import app.gamenative.data.gog.GogSeedCollector
import app.gamenative.service.gog.GOGAuthManager
import app.gamenative.data.LibraryPlayHistory
import app.gamenative.data.SteamApp
import app.gamenative.data.SteamCollection
import app.gamenative.data.SteamCollectionRepository
import app.gamenative.events.AndroidEvent
import app.gamenative.data.GOGGame
import app.gamenative.data.EpicGame
import app.gamenative.data.AmazonGame
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.diagnostics.DiagnosticArea
import app.gamenative.diagnostics.DiagnosticAttribute
import app.gamenative.diagnostics.DiagnosticEventName
import app.gamenative.diagnostics.DiagnosticOutcome
import app.gamenative.diagnostics.FeatureDiagnostics
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.canonical.CanonicalCardKey
import app.gamenative.library.canonical.CanonicalLibraryCard
import app.gamenative.library.canonical.CanonicalLibraryRepository
import app.gamenative.library.canonical.CanonicalProjectionReadiness
import app.gamenative.library.canonical.CanonicalPublicFailure
import app.gamenative.library.canonical.CanonicalPublicLibraryGate
import app.gamenative.library.canonical.OwnedCopySummary
import app.gamenative.library.canonical.runtime.CanonicalIoDispatcher
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeRegistry
import app.gamenative.library.canonical.stableComposeKey
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import app.gamenative.service.amazon.AmazonArtwork
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import app.gamenative.steam.SteamCollectionFilter
import app.gamenative.ui.data.GameCardStats
import app.gamenative.ui.data.LibraryCard
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.data.statsFor
import app.gamenative.ui.enums.AppFilter
import app.gamenative.ui.enums.LibraryTab
import app.gamenative.ui.enums.LibraryTab.Companion.next
import app.gamenative.ui.enums.LibraryTab.Companion.previous
import app.gamenative.ui.enums.SortOption
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.CustomGameScanner
import app.gamenative.data.RecommendationRepository
import app.gamenative.data.RecommendedGame
import app.gamenative.utils.DeviceGameStatsCache
import app.gamenative.utils.GpuGameStatsCache
import app.gamenative.utils.GameCompatibilityCache
import app.gamenative.utils.GameCompatibilityService
import app.gamenative.utils.HardwareUtils
import app.gamenative.utils.unaccent
import com.winlator.core.GPUInformation
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Collections
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

private const val PLAYABLE_FPS_THRESHOLD = 30
private const val PROVEN_RUNS_THRESHOLD = 5

internal data class CanonicalLibraryPage(
    val cards: List<LibraryCard>,
    val totalCount: Int,
    val paginationPage: Int,
    val lastPage: Int,
    val allCount: Int,
    val sourceCounts: Map<GameSource, Int>,
    val steamCollectionCounts: Map<String, Int>,
    val compatibilityRequestNames: List<String>,
)

internal object CanonicalLibraryCardValidator {
    fun failureOrNull(cards: List<CanonicalLibraryCard>): CanonicalPublicFailure? {
        if (cards.map(CanonicalLibraryCard::key).toSet().size != cards.size) {
            return CanonicalPublicFailure.INVALID_CARD_STATE
        }
        val emittedCopyKeys = hashSetOf<OwnedCopyKey>()
        cards.forEach { card ->
            if (card.copies.isEmpty()) return CanonicalPublicFailure.INVALID_CARD_STATE
            if (card.copies.map(OwnedCopySummary::key).toSet().size != card.copies.size) {
                return CanonicalPublicFailure.INVALID_CARD_STATE
            }
            if (card.copies.any { copy -> !emittedCopyKeys.add(copy.key) }) {
                return CanonicalPublicFailure.INVALID_CARD_STATE
            }
            if (card.copies.any { copy -> copy.source != copy.key.source }) {
                return CanonicalPublicFailure.INVALID_CARD_STATE
            }
            if (card.ownedSources != card.copies.mapTo(linkedSetOf(), OwnedCopySummary::source)) {
                return CanonicalPublicFailure.INVALID_CARD_STATE
            }
            if (card.preferredCopy != null && card.copies.none { copy -> copy.key == card.preferredCopy }) {
                return CanonicalPublicFailure.INVALID_CARD_STATE
            }
            when (val key = card.key) {
                is CanonicalCardKey.Grouped -> if (
                    key.canonicalId != card.canonicalId ||
                    card.copies.any { copy ->
                        copy.confidence != MatchConfidence.VERIFIED &&
                            copy.confidence != MatchConfidence.HIGH
                    }
                ) {
                    return CanonicalPublicFailure.INVALID_CARD_STATE
                }
                is CanonicalCardKey.Independent -> if (
                    card.copies.size != 1 ||
                    card.copies.single().key != key.copyKey ||
                    card.copies.single().confidence == MatchConfidence.VERIFIED ||
                    card.copies.single().confidence == MatchConfidence.HIGH
                ) {
                    return CanonicalPublicFailure.INVALID_CARD_STATE
                }
            }
        }
        return null
    }
}

internal fun canonicalUnsupportedFailure(state: LibraryState): CanonicalPublicFailure? =
    if (state.currentTab == LibraryTab.RECOMMENDED || state.appInfoSortType.contains(AppFilter.EXPIRED)) {
        CanonicalPublicFailure.UNSUPPORTED_LEGACY_CONTEXT
    } else {
        null
    }

internal object CanonicalCompatibilityLookup {
    fun resolve(
        card: CanonicalLibraryCard,
        cachedStatus: (String) -> GameCompatibilityStatus?,
    ): GameCompatibilityStatus? {
        cachedStatus(card.displayName)?.let { return it }
        return card.aliases.asSequence()
            .filter(String::isNotBlank)
            .filterNot { alias -> alias.equals(card.displayName, ignoreCase = true) }
            .sortedWith(compareBy<String> { it.lowercase() }.thenBy { it })
            .firstNotNullOfOrNull(cachedStatus)
    }
}

internal object CanonicalLibraryFilter {
    fun project(
        cards: List<CanonicalLibraryCard>,
        state: LibraryState,
        paginationPage: Int,
        pageSize: Int,
        promotion: LibraryItem?,
        showRecommendations: Boolean,
        compatibility: (CanonicalLibraryCard) -> GameCompatibilityStatus?,
    ): CanonicalLibraryPage {
        require(pageSize > 0)
        val appTypes = buildSet {
            if (state.appInfoSortType.contains(AppFilter.GAME)) add(CanonicalAppType.GAME)
            if (state.appInfoSortType.contains(AppFilter.APPLICATION)) add(CanonicalAppType.APPLICATION)
            if (state.appInfoSortType.contains(AppFilter.TOOL)) add(CanonicalAppType.TOOL)
            if (state.appInfoSortType.contains(AppFilter.DEMO)) add(CanonicalAppType.DEMO)
        }
        val installedOnly = state.currentTab.installedOnly ||
            state.appInfoSortType.contains(AppFilter.INSTALLED)
        val includeShared = state.appInfoSortType.contains(AppFilter.SHARED)

        val beforeCollections = cards.asSequence()
            .filter { card -> card.appType in appTypes }
            .filter { card ->
                state.searchQuery.isEmpty() || sequenceOf(card.displayName)
                    .plus(card.aliases.asSequence().filter(String::isNotBlank))
                    .any { name -> canonicalNameMatches(name, state.searchQuery) }
            }
            .filter { card -> !installedOnly || card.copies.any(OwnedCopySummary::isInstalled) }
            .filter { card -> includeShared || card.copies.none(OwnedCopySummary::isShared) }
            .map { card ->
                CanonicalEntry(
                    card = card,
                    compatibility = compatibility(card),
                    stats = state.statsFor(card.copies),
                    sizeBytes = sizeBytes(card),
                )
            }
            .filter { entry -> passesCompatibility(state, entry.compatibility) }
            .filter { entry -> passesStats(state, entry.stats) }
            .toList()

        val steamCollectionCounts = state.steamCollections?.associate { collection ->
            collection.id to beforeCollections.count { entry ->
                entry.card.steamCollectionAppIds.any(collection.appIds::contains)
            }
        }.orEmpty()
        val allowedSteamAppIds = SteamCollectionFilter.allowedAppIds(
            selectedIds = state.selectedSteamCollectionIds,
            collections = state.steamCollections,
        )
        val afterCollections = if (allowedSteamAppIds == null) {
            beforeCollections
        } else {
            beforeCollections.filter { entry ->
                entry.card.steamCollectionAppIds.any(allowedSteamAppIds::contains)
            }
        }

        val sourceCounts = LibraryCard.OWNED_SOURCE_ORDER.associateWith { source ->
            afterCollections.count { entry -> source in entry.card.ownedSources }
        }
        val allCount = afterCollections.count { entry -> ownsEnabledSource(entry.card, state) }
        val admitted = afterCollections.filter { entry -> admitted(entry.card, state) }
        val sorted = admitted.sortedWith(comparator(state.currentSortOption))
        val totalCount = sorted.size
        val lastPage = if (totalCount == 0) 0 else (totalCount - 1) / pageSize
        val safePage = paginationPage.coerceIn(0, lastPage)
        val endIndex = min((safePage + 1) * pageSize, totalCount)
        val indexed = sorted.mapIndexed { index, entry -> IndexedCanonicalEntry(index, entry) }
            .take(endIndex)
        val compatibilityRequestNames = indexed.map { it.entry.card.displayName }
        var displayCards = indexed.map { indexedEntry ->
            val entry = indexedEntry.entry
            val card = entry.card
            LibraryCard.canonical(
                key = card.key,
                index = indexedEntry.index,
                name = card.displayName,
                iconUrl = card.iconUrl,
                capsuleImageUrl = card.capsuleImageUrl,
                headerImageUrl = card.headerImageUrl,
                heroImageUrl = card.heroImageUrl,
                gridHeroImageScale = card.gridHeroImageScale,
                ownedSources = card.ownedSources,
                compatibilityStatus = entry.compatibility,
                gameStats = entry.stats,
                sizeBytes = entry.sizeBytes ?: 0L,
                isInstalled = card.copies.any(OwnedCopySummary::isInstalled),
                isShared = card.copies.any(OwnedCopySummary::isShared),
            )
        }
        if (
            showRecommendations &&
            state.currentTab == LibraryTab.ALL &&
            state.searchQuery.isEmpty() &&
            promotion != null
        ) {
            val promotionCard = LibraryCard.fromPromotion(
                item = promotion,
                compatibilityStatus = state.compatibilityMap[promotion.name],
                gameStats = state.statsFor(promotion),
            )
            displayCards = listOf(promotionCard) + displayCards.map { card ->
                card.copy(index = card.index + 1)
            }
        }
        return CanonicalLibraryPage(
            cards = immutableList(displayCards),
            totalCount = totalCount,
            paginationPage = safePage,
            lastPage = lastPage,
            allCount = allCount,
            sourceCounts = sourceCounts,
            steamCollectionCounts = steamCollectionCounts,
            compatibilityRequestNames = immutableList(compatibilityRequestNames),
        )
    }

    private fun passesCompatibility(
        state: LibraryState,
        status: GameCompatibilityStatus?,
    ): Boolean {
        if (!state.appInfoSortType.contains(AppFilter.COMPATIBLE)) return true
        return status == null ||
            status == GameCompatibilityStatus.COMPATIBLE ||
            status == GameCompatibilityStatus.GPU_COMPATIBLE
    }

    private fun passesStats(state: LibraryState, stats: GameCardStats?): Boolean {
        val filters = state.appInfoSortType
        if (filters.contains(AppFilter.PLAYABLE) && (stats?.fps ?: 0) < PLAYABLE_FPS_THRESHOLD) return false
        if (filters.contains(AppFilter.FIVE_STAR) && (stats?.reviewsDevice ?: 0) < 1) return false
        if (filters.contains(AppFilter.FIVE_STAR_GPU) && (stats?.reviewsGpu ?: 0) < 1) return false
        if (filters.contains(AppFilter.PROVEN_GPU) && (stats?.runsGpu ?: 0) < PROVEN_RUNS_THRESHOLD) return false
        return true
    }

    private fun ownsEnabledSource(card: CanonicalLibraryCard, state: LibraryState): Boolean =
        (state.showSteamInLibrary && GameSource.STEAM in card.ownedSources) ||
            (state.showGOGInLibrary && GameSource.GOG in card.ownedSources) ||
            (state.showEpicInLibrary && GameSource.EPIC in card.ownedSources) ||
            (state.showAmazonInLibrary && GameSource.AMAZON in card.ownedSources) ||
            (state.showCustomGamesInLibrary && GameSource.CUSTOM_GAME in card.ownedSources)

    private fun admitted(card: CanonicalLibraryCard, state: LibraryState): Boolean = when (state.currentTab) {
        LibraryTab.ALL -> ownsEnabledSource(card, state)
        LibraryTab.STEAM -> GameSource.STEAM in card.ownedSources
        LibraryTab.GOG -> GameSource.GOG in card.ownedSources
        LibraryTab.EPIC -> GameSource.EPIC in card.ownedSources
        LibraryTab.AMAZON -> GameSource.AMAZON in card.ownedSources
        LibraryTab.LOCAL -> GameSource.CUSTOM_GAME in card.ownedSources
        LibraryTab.RECOMMENDED -> false
    }

    private fun comparator(sort: SortOption): Comparator<CanonicalEntry> {
        val name = compareBy<CanonicalEntry> { it.card.displayName.lowercase() }
            .thenBy { it.card.displayName }
            .thenBy { it.card.key.stableComposeKey() }
        return when (sort) {
            SortOption.INSTALLED_FIRST -> compareBy<CanonicalEntry> {
                if (it.card.copies.any(OwnedCopySummary::isInstalled)) 0 else 1
            }.then(name)
            SortOption.NAME_ASC -> name
            SortOption.NAME_DESC -> compareByDescending<CanonicalEntry> { it.card.displayName.lowercase() }
                .thenByDescending { it.card.displayName }
                .thenBy { it.card.key.stableComposeKey() }
            SortOption.RECENTLY_PLAYED -> compareBy<CanonicalEntry> {
                if (it.card.copies.any(OwnedCopySummary::isInstalled)) 0 else 1
            }.thenByDescending { it.card.copies.mapNotNull(OwnedCopySummary::lastPlayedEpochMs).maxOrNull() ?: 0L }
                .then(name)
            SortOption.SIZE_SMALLEST -> compareBy<CanonicalEntry> { it.sizeBytes == null }
                .thenBy { it.sizeBytes ?: Long.MAX_VALUE }
                .then(name)
            SortOption.SIZE_LARGEST -> compareBy<CanonicalEntry> { it.sizeBytes == null }
                .thenByDescending { it.sizeBytes ?: Long.MIN_VALUE }
                .then(name)
            SortOption.FPS_HIGH -> compareByDescending<CanonicalEntry> { it.stats?.fps ?: -1 }.then(name)
            SortOption.RUNS_HIGH -> compareByDescending<CanonicalEntry> { it.stats?.runsGpu ?: -1 }.then(name)
            SortOption.REVIEWS_HIGH -> compareByDescending<CanonicalEntry> { it.stats?.reviewsDevice ?: -1 }.then(name)
            SortOption.REVIEWS_GPU_HIGH -> compareByDescending<CanonicalEntry> { it.stats?.reviewsGpu ?: -1 }.then(name)
        }
    }

    private fun sizeBytes(card: CanonicalLibraryCard): Long? {
        val preferred = card.preferredCopy
            ?.let { key -> card.copies.singleOrNull { copy -> copy.key == key } }
            ?.installedSizeBytes
            ?.takeIf { it >= 0L }
        if (preferred != null) return preferred
        return card.copies.singleOrNull(OwnedCopySummary::isInstalled)
            ?.installedSizeBytes
            ?.takeIf { it >= 0L }
    }

    private data class CanonicalEntry(
        val card: CanonicalLibraryCard,
        val compatibility: GameCompatibilityStatus?,
        val stats: GameCardStats?,
        val sizeBytes: Long?,
    )

    private data class IndexedCanonicalEntry(
        val index: Int,
        val entry: CanonicalEntry,
    )

    private fun <T> immutableList(values: Collection<T>): List<T> =
        if (values.isEmpty()) emptyList()
        else Collections.unmodifiableList(ArrayList(values))
}

private fun canonicalNameMatches(name: String, searchQuery: String): Boolean =
    name.contains(searchQuery, ignoreCase = true) ||
        name.unaccent().contains(searchQuery, ignoreCase = true)

private class InvalidCanonicalCardList(
    val failure: CanonicalPublicFailure,
) : IllegalStateException()

private class CanonicalAssemblyFailure : IllegalStateException()
private class CanonicalCollectionStopped : CancellationException()

private sealed interface LibraryRenderMode {
    data class Legacy(val failure: CanonicalPublicFailure?) : LibraryRenderMode
    data class Canonical(val collectorEpoch: Long) : LibraryRenderMode
    data class WaitingCanonical(val collectorEpoch: Long) : LibraryRenderMode
    data object PendingInput : LibraryRenderMode
}

private data class LibraryRenderToken(
    val generation: Long,
    val inputRevision: Long,
    val mode: LibraryRenderMode,
    val state: LibraryState,
    val paginationPage: Int,
)

private data class FilterRenderRequest(
    val inputRevision: Long,
    val state: LibraryState,
    val paginationPage: Int,
)

private data class LegacyRenderRequest(
    val failure: CanonicalPublicFailure?,
    val filter: FilterRenderRequest,
    val completion: CompletableDeferred<Unit> = CompletableDeferred(),
)

private sealed interface RenderPublicationOutcome {
    val token: LibraryRenderToken

    data class Published(override val token: LibraryRenderToken) : RenderPublicationOutcome
    data class Superseded(override val token: LibraryRenderToken) : RenderPublicationOutcome
    data class Failed(
        override val token: LibraryRenderToken,
        val error: Exception,
    ) : RenderPublicationOutcome
}

private const val MIN_CANONICAL_RETRY_DELAY_MS = 1_000L
private const val MAX_CANONICAL_RETRY_DELAY_MS = 60_000L
private const val MIN_LEGACY_RETRY_DELAY_MS = 1_000L
private const val MAX_LEGACY_RETRY_DELAY_MS = 60_000L

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryPlayHistoryDao: LibraryPlayHistoryDao,
    private val steamAppDao: SteamAppDao,
    private val gogGameDao: GOGGameDao,
    private val epicGameDao: EpicGameDao,
    private val amazonGameDao: AmazonGameDao,
    @ApplicationContext private val context: Context,
    private val canonicalLibraryRepository: CanonicalLibraryRepository,
    private val canonicalPublicLibraryGate: CanonicalPublicLibraryGate,
    private val canonicalProjectionReadiness: CanonicalProjectionReadiness,
    private val runtimeRegistry: OwnedCopyRuntimeRegistry,
    @CanonicalIoDispatcher private val canonicalDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryState(isLoading = true))
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    // Keep the library scroll state. This will last longer as the VM will stay alive.
    var listState: LazyGridState by mutableStateOf(LazyGridState(0, 0))

    private val onInstallStatusChanged: (AndroidEvent.LibraryInstallStatusChanged) -> Unit = { event ->
        val request = supersedeRenderForPendingInput()
        runtimeRegistry.notifyVolatileStateChanged(event.source)
        onFilterApps(request)
    }

    private val onCustomGameImagesFetched: (AndroidEvent.CustomGameImagesFetched) -> Unit = {
        runtimeRegistry.notifyVolatileStateChanged(GameSource.CUSTOM_GAME)
        val request = supersedeRenderForPendingInput(
            transformState = { current ->
                current.copy(imageRefreshCounter = current.imageRefreshCounter + 1)
            },
        )
        onFilterApps(request)
    }

    private val onRecommendationToggleChanged: (AndroidEvent.RecommendationToggleChanged) -> Unit = {
        refreshRecommendationHero()
    }

    // How many items loaded on one page of results
    @Volatile private var paginationCurrentPage: Int = 0
    @Volatile private var lastPageInCurrentFilter: Int = 0
    private val renderLock = Any()
    private val renderGeneration = AtomicLong(0L)
    private val filterInputRevision = AtomicLong(0L)
    @Volatile private var activeRenderToken: LibraryRenderToken? = null
    @Volatile private var latestPublishedToken: LibraryRenderToken? = null
    private var renderJob: Job? = null

    private var legacyPhysicalJob: Job? = null
    private var legacyPhysicalToken: LibraryRenderToken? = null
    private var legacyPhysicalRequest: LegacyRenderRequest? = null
    private var pendingLegacyRequest: LegacyRenderRequest? = null
    private var legacyRetryJob: Job? = null
    private var legacyRetryDeadlineElapsedMs: Long = 0L
    private var legacyRetryDelayMs = MIN_LEGACY_RETRY_DELAY_MS

    @Volatile private var latestCanonicalCards: List<CanonicalLibraryCard>? = null
    @Volatile private var canonicalCollectionHealthy: Boolean = false
    @Volatile private var canonicalCollectionFailure: CanonicalPublicFailure? = null
    private var canonicalSnapshotEpoch: Long = 0L
    private var canonicalCollectorEpoch: Long = 0L
    private var canonicalCollectionJob: Job? = null
    private var canonicalRetryJob: Job? = null
    private var canonicalRetryDeadlineElapsedMs: Long = 0L
    private var canonicalRetryDelayMs: Long = MIN_CANONICAL_RETRY_DELAY_MS

    // Complete and unfiltered app list
    private var appList: List<SteamApp> = emptyList()
    private var gogGameList: List<GOGGame> = emptyList()
    private var epicGameList: List<EpicGame> = emptyList()
    private var amazonGameList: List<AmazonGame> = emptyList()
    private var playHistoryByAppId: Map<String, Long> = emptyMap()

    @Volatile private var steamCollections: List<SteamCollection>? = null

    // Track if this is the first load to apply minimum load time
    private var isFirstLoad = true

    // Cached recommendation (fetched once at startup)
    @Volatile private var cachedRecommendation: RecommendedGame? = null
    @Volatile private var cachedFeatured: app.gamenative.data.FeaturedItem? = null

    // Track debounce job for search
    private var searchDebounceJob: Job? = null
    private val SEARCH_DEBOUNCE_MS = 500L // 500ms debounce

    // Cache GPU name to avoid repeated calls
    private val gpuName: String by lazy {
        try {
            val gpu = GPUInformation.getRenderer(context)
            if (gpu.isNullOrEmpty()) {
                Timber.tag("LibraryViewModel").w("GPU name is null or empty")
                "Unknown GPU"
            } else {
                Timber.tag("LibraryViewModel").d("Retrieved GPU name: $gpu")
                gpu
            }
        } catch (error: Throwable) {
            if (error !is Exception && error !is LinkageError) throw error
            Timber.tag("LibraryViewModel").e(error, "Failed to get GPU name")
            "Unknown GPU"
        }
    }

    init {
        viewModelScope.launch(canonicalDispatcher) {
            canonicalProjectionReadiness.isReady.collect {
                if (canonicalPublicLibraryGate.isEnabled()) {
                    onFilterApps(supersedeRenderForPendingInput())
                }
            }
        }

        viewModelScope.launch(canonicalDispatcher) {
            if (gpuName != "Unknown GPU") {
                DeviceGameStatsCache.refreshIfStale(
                    deviceModel = HardwareUtils.getMachineName(),
                    gpuName = gpuName,
                    modernBuild = BuildConfig.MODERN_ANDROID,
                )
                GpuGameStatsCache.refreshIfStale(
                    gpuName = gpuName,
                    modernBuild = BuildConfig.MODERN_ANDROID,
                )
            } else {
                Timber.tag("LibraryViewModel").w("Skipping device/GPU game stats fetch - GPU name is unknown")
            }
            val request = supersedeRenderForPendingInput(
                transformState = { current ->
                    current.copy(
                        deviceGameStats = DeviceGameStatsCache.getAll(),
                        gpuGameStats = GpuGameStatsCache.getAll(),
                    )
                },
            )
            // Rebuild typed cards now that their display stats are available.
            onFilterApps(request)
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        viewModelScope.launch(canonicalDispatcher) {
            // Re-create the underlying DAO Flow whenever the EXPIRED filter is toggled,
            // so apps with Expired or missing licenses are surfaced/hidden accordingly.
            _state
                .map { it.appInfoSortType.contains(AppFilter.EXPIRED) }
                .distinctUntilChanged()
                .flatMapLatest { includeExpired ->
                    steamAppDao.getAllOwnedApps(includeExpired = includeExpired)
                }
                .collect { apps ->
                    Timber.tag("LibraryViewModel").d("Collecting ${apps.size} apps")
                    // Check if the list has actually changed before triggering a re-filter
                    if (appList != apps) {
                        val request = supersedeRenderForPendingInput(
                            updateInputLocked = { appList = apps },
                        )
                        onFilterApps(request)
                    }
                }
        }

        viewModelScope.launch(canonicalDispatcher) {
            libraryPlayHistoryDao.getAll().collect { entries ->
                val playHistory = entries.associate { it.appId to it.lastPlayed }
                if (playHistoryByAppId != playHistory) {
                    val request = supersedeRenderForPendingInput(
                        updateInputLocked = { playHistoryByAppId = playHistory },
                    )
                    onFilterApps(request)
                }
            }
        }

        // Collect GOG games
        viewModelScope.launch(canonicalDispatcher) {
            gogGameDao.getAll().collect { games ->
                Timber.tag("LibraryViewModel").d("Collecting ${games.size} GOG games")
                // Check if the list has actually changed before triggering a re-filter
                if (gogGameList != games) {
                    val request = supersedeRenderForPendingInput(
                        updateInputLocked = { gogGameList = games },
                    )
                    onFilterApps(request)
                }
            }
        }

        viewModelScope.launch(canonicalDispatcher) {
            epicGameDao.getAll().collect { games ->
                Timber.tag("LibraryViewModel").d("Collecting ${games.size} Epic games")

                val hasChanges = epicGameList.size != games.size || epicGameList != games
                if (hasChanges) {
                    val request = supersedeRenderForPendingInput(
                        updateInputLocked = { epicGameList = games },
                    )
                    onFilterApps(request)
                }
            }
        }

        viewModelScope.launch(canonicalDispatcher) {
            amazonGameDao.getAll().collect { games ->
                Timber.tag("LibraryViewModel").d("Collecting ${games.size} Amazon games")
                val hasChanges = amazonGameList.size != games.size || amazonGameList != games
                if (hasChanges) {
                    val request = supersedeRenderForPendingInput(
                        updateInputLocked = { amazonGameList = games },
                    )
                    onFilterApps(request)
                }
            }
        }

        // Load any cached collections immediately, then observe live updates.
        SteamCollectionRepository.loadFromCache()
        viewModelScope.launch(canonicalDispatcher) {
            SteamCollectionRepository.collections.collect { collections ->
                var removedAny = false
                val request = supersedeRenderForPendingInput(
                    updateInputLocked = { steamCollections = collections },
                    transformState = { current ->
                        val recon = SteamCollectionFilter.reconcile(
                            current.selectedSteamCollectionIds,
                            collections,
                        )
                        removedAny = recon.removedAny
                        if (recon.removedAny) {
                            PrefManager.librarySteamCollections = recon.cleaned
                        }
                        current.copy(
                            steamCollections = collections,
                            selectedSteamCollectionIds = recon.cleaned,
                        )
                    },
                )
                if (removedAny) {
                    SnackbarManager.show(context.getString(R.string.steam_collections_removed))
                }
                onFilterApps(request)
            }
        }
        viewModelScope.launch(canonicalDispatcher) {
            SteamCollectionRepository.skippedDynamic.collect { skipped ->
                _state.update { it.copy(skippedDynamicCollections = skipped) }
            }
        }

        PluviaApp.events.on<AndroidEvent.LibraryInstallStatusChanged, Unit>(onInstallStatusChanged)
        PluviaApp.events.on<AndroidEvent.CustomGameImagesFetched, Unit>(onCustomGameImagesFetched)
        PluviaApp.events.on<AndroidEvent.RecommendationToggleChanged, Unit>(onRecommendationToggleChanged)

        refreshRecommendationHero()
    }

    private fun refreshRecommendationHero() {
        supersedeRenderForPendingInput()
        viewModelScope.launch(canonicalDispatcher) {
            val hero = RecommendationRepository.getHero(context)
            cachedFeatured = hero.featured
            cachedRecommendation = when {
                // A live featured takes the slot (still gated by the showRecommendations
                // toggle at display time), regardless of GOG consent.
                hero.featured != null -> null
                PrefManager.showRecommendations && PrefManager.recDisclosureShown -> runCatching {
                    val owned = GogSeedCollector.collect(
                        context,
                        libraryPlayHistoryDao,
                        gogGameDao,
                        epicGameDao,
                        amazonGameDao,
                    )
                    val userId = GOGAuthManager.getStoredCredentials(context).getOrNull()?.userId
                    val daySeed = System.currentTimeMillis() / (24L * 60 * 60 * 1000)
                    GogRecommendationsRepository.getDailyHero(context, owned, userId, daySeed)
                }.getOrNull() ?: hero.recommendation
                else -> hero.recommendation
            }
            onFilterApps(paginationCurrentPage)
        }
    }

    override fun onCleared() {
        searchDebounceJob?.cancel()
        val jobsToCancel = synchronized(renderLock) {
            canonicalCollectorEpoch += 1L
            val jobs = listOfNotNull(
                renderJob,
                legacyPhysicalJob,
                legacyRetryJob,
                canonicalCollectionJob,
                canonicalRetryJob,
            )
            renderJob = null
            legacyPhysicalJob = null
            legacyPhysicalToken = null
            legacyPhysicalRequest = null
            pendingLegacyRequest?.completion?.complete(Unit)
            pendingLegacyRequest = null
            legacyRetryJob = null
            canonicalCollectionJob = null
            canonicalRetryJob = null
            activeRenderToken = null
            jobs
        }
        jobsToCancel.forEach(Job::cancel)
        PluviaApp.events.off<AndroidEvent.LibraryInstallStatusChanged, Unit>(onInstallStatusChanged)
        PluviaApp.events.off<AndroidEvent.CustomGameImagesFetched, Unit>(onCustomGameImagesFetched)
        PluviaApp.events.off<AndroidEvent.RecommendationToggleChanged, Unit>(onRecommendationToggleChanged)
        super.onCleared()
    }

    fun onModalBottomSheet(value: Boolean) {
        _state.update { it.copy(modalBottomSheet = value) }
    }

    fun onIsSearching(value: Boolean) {
        _state.update { it.copy(isSearching = value) }
        if (!value) {
            onSearchQuery("")
        }
    }

    fun onSourceToggle(source: GameSource) {
        val request = supersedeRenderForPendingInput(
            transformState = { current ->
                when (source) {
                    GameSource.STEAM -> {
                        val newValue = !current.showSteamInLibrary
                        PrefManager.showSteamInLibrary = newValue
                        current.copy(showSteamInLibrary = newValue)
                    }

                    GameSource.CUSTOM_GAME -> {
                        val newValue = !current.showCustomGamesInLibrary
                        PrefManager.showCustomGamesInLibrary = newValue
                        current.copy(showCustomGamesInLibrary = newValue)
                    }
                    GameSource.GOG -> {
                        val newValue = !current.showGOGInLibrary
                        PrefManager.showGOGInLibrary = newValue
                        current.copy(showGOGInLibrary = newValue)
                    }
                    GameSource.EPIC -> {
                        val newValue = !current.showEpicInLibrary
                        PrefManager.showEpicInLibrary = newValue
                        current.copy(showEpicInLibrary = newValue)
                    }
                    GameSource.AMAZON -> {
                        val newValue = !current.showAmazonInLibrary
                        PrefManager.showAmazonInLibrary = newValue
                        current.copy(showAmazonInLibrary = newValue)
                    }
                }
            },
        )
        onFilterApps(request)
    }

    fun onSortOptionChanged(sortOption: SortOption) {
        val request = supersedeRenderForPendingInput(
            paginationPageLocked = { 0 },
            transformState = { current ->
                PrefManager.librarySortOption = sortOption
                current.copy(currentSortOption = sortOption)
            },
        )
        onFilterApps(request)
    }

    fun onOptionsPanelToggle(isOpen: Boolean) {
        _state.update { it.copy(isOptionsPanelOpen = isOpen) }
    }

    fun onTabChanged(tab: LibraryTab) {
        val request = supersedeRenderForPendingInput(
            paginationPageLocked = { 0 },
            transformState = { current -> current.copy(currentTab = tab) },
        )
        onFilterApps(request)
    }

    fun onNextTab() {
        val request = supersedeRenderForPendingInput(
            paginationPageLocked = { 0 },
            transformState = { current ->
                val nextTab = current.currentTab.next()
                Timber.tag("LibraryViewModel").d("Tab next via bumper: ${current.currentTab} -> $nextTab")
                current.copy(currentTab = nextTab)
            },
        )
        onFilterApps(request)
    }

    fun onPreviousTab() {
        val request = supersedeRenderForPendingInput(
            paginationPageLocked = { 0 },
            transformState = { current ->
                val previousTab = current.currentTab.previous()
                Timber.tag("LibraryViewModel").d("Tab previous via bumper: ${current.currentTab} -> $previousTab")
                current.copy(currentTab = previousTab)
            },
        )
        onFilterApps(request)
    }

    fun onSearchQuery(value: String) {
        val request = supersedeRenderForPendingInput(
            paginationPageLocked = { 0 },
            transformState = { current -> current.copy(searchQuery = value) },
        )

        // Cancel previous debounce job
        searchDebounceJob?.cancel()

        // Start new debounce job
        searchDebounceJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            // Only trigger filter after user stops typing
            onFilterApps(request)
        }
    }

    // TODO: include other sort types
    fun onFilterChanged(value: AppFilter) {
        val request = supersedeRenderForPendingInput(
            paginationPageLocked = { 0 },
            transformState = { current ->
                val updatedFilter = EnumSet.copyOf(current.appInfoSortType)
                if (!updatedFilter.remove(value)) updatedFilter.add(value)
                PrefManager.libraryFilter = updatedFilter
                current.copy(appInfoSortType = updatedFilter)
            },
        )
        onFilterApps(request)
    }

    fun onSteamCollectionToggle(id: String) {
        val request = supersedeRenderForPendingInput(
            paginationPageLocked = { 0 },
            transformState = { current ->
                val updated = current.selectedSteamCollectionIds.toMutableSet()
                if (!updated.add(id)) updated.remove(id)
                PrefManager.librarySteamCollections = updated
                current.copy(selectedSteamCollectionIds = updated)
            },
        )
        onFilterApps(request)
    }

    fun onClearSteamCollections() {
        val request = supersedeRenderForPendingInput(
            paginationPageLocked = { 0 },
            transformState = { current ->
                PrefManager.librarySteamCollections = emptySet()
                current.copy(selectedSteamCollectionIds = emptySet())
            },
        )
        onFilterApps(request)
    }

    fun onPageChange(pageIncrement: Int) {
        val request = supersedeRenderForPendingInput(
            paginationPageLocked = {
                val requestedPage = activeRenderToken?.paginationPage ?: paginationCurrentPage
                min(max(0, requestedPage + pageIncrement), lastPageInCurrentFilter)
            },
        )
        onFilterApps(request)
    }

    fun onRefresh() {
        val initialRequest = supersedeRenderForPendingInput(
            paginationPageLocked = { 0 },
            transformState = { current -> current.copy(isRefreshing = true) },
        )
        viewModelScope.launch {
            // Clear compatibility cache on manual refresh to get fresh data
            GameCompatibilityCache.clear()
            DeviceGameStatsCache.clear()
            GpuGameStatsCache.clear()

            try {
                val newApps = SteamService.refreshOwnedGamesFromServer()
                if (newApps > 0) {
                    Timber.tag("LibraryViewModel").i("Queued $newApps newly owned games for PICS sync")
                } else {
                    Timber.tag("LibraryViewModel").d("No newly owned games discovered during refresh")
                }
                if (app.gamenative.service.gog.GOGService.hasStoredCredentials(context)) {
                    Timber.tag("LibraryViewModel").i("Triggering GOG library refresh")
                    app.gamenative.service.gog.GOGService.triggerLibrarySync(context)
                }
                if (AmazonService.hasStoredCredentials(context)) {
                    Timber.tag("LibraryViewModel").i("Triggering Amazon library refresh")
                    AmazonService.triggerLibrarySync(context)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.tag("LibraryViewModel").e(error, "Failed to refresh owned games from server")
            }

            onFilterApps(initialRequest).join()
            if (gpuName != "Unknown GPU") {
                DeviceGameStatsCache.refreshIfStale(
                    deviceModel = HardwareUtils.getMachineName(),
                    gpuName = gpuName,
                    modernBuild = BuildConfig.MODERN_ANDROID,
                )
                GpuGameStatsCache.refreshIfStale(
                    gpuName = gpuName,
                    modernBuild = BuildConfig.MODERN_ANDROID,
                )
            }
            val completionRequest = supersedeRenderIfInputCurrent(
                expectedInputRevision = initialRequest.inputRevision,
                transformState = { current ->
                    current.copy(
                        isRefreshing = false,
                        deviceGameStats = DeviceGameStatsCache.getAll(),
                        gpuGameStats = GpuGameStatsCache.getAll(),
                    )
                },
            )
            if (completionRequest == null) {
                _state.update { current -> current.copy(isRefreshing = false) }
            } else {
                onFilterApps(completionRequest)
            }
        }
    }

    fun addCustomGameFolder(path: String) {
        viewModelScope.launch(canonicalDispatcher) {
            val normalizedPath = File(path).absolutePath
            val libraryItem = CustomGameScanner.createLibraryItemFromFolder(normalizedPath)
            if (libraryItem == null) {
                Timber.tag("LibraryViewModel").w("Selected folder is not a valid custom game: $normalizedPath")
                return@launch
            }

            val manualFolders = PrefManager.customGameManualFolders.toMutableSet()
            if (!manualFolders.contains(normalizedPath)) {
                manualFolders.add(normalizedPath)
                PrefManager.customGameManualFolders = manualFolders
            }

            val request = supersedeRenderForPendingInput()
            CustomGameScanner.invalidateCache()
            onFilterApps(request)
        }
    }

    private fun diagnosticFilterGroups(state: LibraryState): String = buildList {
        if (state.searchQuery.isNotEmpty()) add("search")
        if (state.appInfoSortType.isNotEmpty()) add("app_filter")
        if (state.selectedSteamCollectionIds.isNotEmpty()) add("collection")
        add("source_tab")
    }.joinToString(",")

    /**
     * Returns true if a game satisfies all active stat filters. Applied per-source (like
     * [GameCompatibilityCache]'s compatible filter) so the per-source tab counts stay accurate.
     * Games with no stats data are hidden whenever a stat filter is active.
     */
    private fun passesStatsFilters(state: LibraryState, source: GameSource, name: String): Boolean {
        val filters = state.appInfoSortType
        val playable = filters.contains(AppFilter.PLAYABLE)
        val fiveStar = filters.contains(AppFilter.FIVE_STAR)
        val fiveStarGpu = filters.contains(AppFilter.FIVE_STAR_GPU)
        val proven = filters.contains(AppFilter.PROVEN_GPU)
        if (!playable && !fiveStar && !fiveStarGpu && !proven) return true

        val stats = state.statsFor(source, name)
        if (playable && (stats?.fps ?: 0) < PLAYABLE_FPS_THRESHOLD) return false
        if (fiveStar && (stats?.reviewsDevice ?: 0) < 1) return false
        if (fiveStarGpu && (stats?.reviewsGpu ?: 0) < 1) return false
        if (proven && (stats?.runsGpu ?: 0) < PROVEN_RUNS_THRESHOLD) return false
        return true
    }

    private fun onFilterApps(paginationPage: Int = 0): Job =
        onFilterApps(captureFilterRequest(paginationPage))

    private fun onFilterApps(request: FilterRenderRequest): Job {
        if (!canonicalPublicLibraryGate.isEnabled()) {
            stopCanonicalCollection(request.inputRevision)
            return requestLegacyRender(null, request)
        }
        if (!canonicalProjectionReadiness.isReady.value) {
            stopCanonicalCollection(request.inputRevision)
            return requestLegacyRender(CanonicalPublicFailure.MISSING_PROJECTION_PREREQUISITE, request)
        }
        canonicalUnsupportedFailure(request.state)?.let { failure ->
            stopCanonicalCollection(request.inputRevision)
            return requestLegacyRender(failure, request)
        }

        ensureCanonicalCollection(request.inputRevision)
        return requestCanonicalActivation(request)
    }

    private fun captureFilterRequest(paginationPage: Int): FilterRenderRequest = synchronized(renderLock) {
        FilterRenderRequest(
            inputRevision = filterInputRevision.get(),
            state = _state.value,
            paginationPage = paginationPage,
        )
    }

    private fun ensureCanonicalCollection(expectedInputRevision: Long? = null): Long {
        var jobToStart: Job? = null
        val epoch = synchronized(renderLock) {
            if (expectedInputRevision != null && filterInputRevision.get() != expectedInputRevision) {
                return@synchronized canonicalCollectorEpoch
            }
            val current = canonicalCollectionJob
            if (current != null && !current.isCompleted) {
                canonicalCollectorEpoch
            } else if (
                canonicalRetryJob?.isCompleted == false ||
                canonicalRetryDeadlineElapsedMs > SystemClock.elapsedRealtime()
            ) {
                canonicalSnapshotEpoch.takeIf { it != 0L } ?: canonicalCollectorEpoch
            } else {
                canonicalCollectorEpoch += 1L
                val newEpoch = canonicalCollectorEpoch
                canonicalSnapshotEpoch = newEpoch
                latestCanonicalCards = null
                canonicalCollectionHealthy = false
                canonicalCollectionFailure = null
                val job = viewModelScope.launch(
                    context = canonicalDispatcher,
                    start = CoroutineStart.LAZY,
                ) {
                    collectCanonicalCards(newEpoch)
                }
                canonicalCollectionJob = job
                job.invokeOnCompletion { cause ->
                    handleCanonicalCollectorCompletion(newEpoch, job, cause)
                }
                jobToStart = job
                newEpoch
            }
        }
        jobToStart?.start()
        return epoch
    }

    private suspend fun collectCanonicalCards(collectorEpoch: Long) {
        while (isCollectorActive(collectorEpoch)) {
            var failed = false
            try {
                canonicalLibraryRepository.observeCards().collect { emitted ->
                    if (!isCollectorActive(collectorEpoch)) throw CanonicalCollectionStopped()
                    val frozen = immutableCanonicalCards(emitted)
                    CanonicalLibraryCardValidator.failureOrNull(frozen)?.let { failure ->
                        throw InvalidCanonicalCardList(failure)
                    }
                    val handle = launchCanonicalRenderFromEmission(frozen, collectorEpoch)
                    when (val outcome = handle.outcome.await()) {
                        is RenderPublicationOutcome.Published -> Unit
                        is RenderPublicationOutcome.Superseded -> Unit
                        is RenderPublicationOutcome.Failed -> {
                            if (acceptCanonicalRenderFailure(outcome)) {
                                failed = true
                            }
                            throw outcome.error
                        }
                    }
                }
                if (!isCollectorActive(collectorEpoch)) return
                throw CanonicalAssemblyFailure()
            } catch (error: CanonicalCollectionStopped) {
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: InvalidCanonicalCardList) {
                failed = handleCanonicalCollectionFailure(collectorEpoch, error.failure, error)
            } catch (error: Exception) {
                if (!failed) {
                    failed = handleCanonicalCollectionFailure(
                        collectorEpoch = collectorEpoch,
                        failure = CanonicalPublicFailure.ASSEMBLY_FAILED,
                        error = error,
                    )
                }
            }
            if (!failed) continue
            val retryDelayMs = beginCanonicalRetryCooldown(collectorEpoch) ?: return
            delay(retryDelayMs)
        }
    }

    private fun handleCanonicalCollectionFailure(
        collectorEpoch: Long,
        failure: CanonicalPublicFailure,
        error: Exception,
    ): Boolean {
        val request = synchronized(renderLock) {
            if (!isCollectorActiveLocked(collectorEpoch) || !canonicalModeEligible(_state.value)) {
                null
            } else {
                canonicalCollectionHealthy = false
                canonicalSnapshotEpoch = collectorEpoch
                canonicalCollectionFailure = failure
                captureFilterRequestLocked(paginationCurrentPage)
            }
        } ?: return false
        recordRenderFailure(error)
        requestLegacyRender(failure, request)
        return true
    }

    private fun beginCanonicalRetryCooldown(collectorEpoch: Long): Long? = synchronized(renderLock) {
        if (!isCollectorActiveLocked(collectorEpoch) || !canonicalModeEligible(_state.value)) {
            null
        } else {
            val retryDelayMs = canonicalRetryDelayMs
            canonicalRetryDelayMs = (canonicalRetryDelayMs * 2).coerceAtMost(MAX_CANONICAL_RETRY_DELAY_MS)
            canonicalRetryDeadlineElapsedMs = SystemClock.elapsedRealtime() + retryDelayMs
            retryDelayMs
        }
    }

    private fun handleCanonicalCollectorCompletion(
        collectorEpoch: Long,
        completedJob: Job,
        cause: Throwable?,
    ) {
        if (cause !is CancellationException || cause is CanonicalCollectionStopped) return
        val retryLaunch: Job?
        val request: FilterRenderRequest?
        synchronized(renderLock) {
            val parentActive = viewModelScope.coroutineContext[Job]?.isActive == true
            if (
                !parentActive ||
                canonicalCollectorEpoch != collectorEpoch ||
                canonicalCollectionJob !== completedJob ||
                !canonicalModeEligible(_state.value)
            ) {
                return
            }
            canonicalCollectionJob = null
            canonicalCollectionHealthy = false
            canonicalSnapshotEpoch = collectorEpoch
            canonicalCollectionFailure = CanonicalPublicFailure.ASSEMBLY_FAILED
            retireActiveCollectorTokenLocked(collectorEpoch)
            _state.update { current ->
                current.copy(
                    isLoading = false,
                    canonicalPublicFailure = CanonicalPublicFailure.ASSEMBLY_FAILED,
                )
            }
            request = captureFilterRequestLocked(paginationCurrentPage)
            retryLaunch = scheduleCanonicalRetryLocked(collectorEpoch)
        }
        recordRenderFailure(cause)
        request?.let { requestLegacyRender(CanonicalPublicFailure.ASSEMBLY_FAILED, it) }
        retryLaunch?.start()
    }

    private fun scheduleCanonicalCollectorRestart(
        failedCollectorEpoch: Long,
        failure: CanonicalPublicFailure,
    ) {
        val collectorToCancel: Job?
        val retryToStart: Job?
        synchronized(renderLock) {
            if (
                canonicalSnapshotEpoch != failedCollectorEpoch ||
                canonicalCollectionFailure != failure ||
                !canonicalModeEligible(_state.value)
            ) {
                return
            }
            collectorToCancel = canonicalCollectionJob
            canonicalCollectionJob = null
            retryToStart = scheduleCanonicalRetryLocked(failedCollectorEpoch)
        }
        collectorToCancel?.cancel(CanonicalCollectionStopped())
        retryToStart?.start()
    }

    private fun scheduleCanonicalRetryLocked(failedCollectorEpoch: Long): Job? {
        if (canonicalRetryJob?.isCompleted == false) return null
        val retryDelayMs = canonicalRetryDelayMs
        canonicalRetryDelayMs = (canonicalRetryDelayMs * 2).coerceAtMost(MAX_CANONICAL_RETRY_DELAY_MS)
        canonicalRetryDeadlineElapsedMs = SystemClock.elapsedRealtime() + retryDelayMs
        lateinit var retry: Job
        retry = viewModelScope.launch(
            context = canonicalDispatcher,
            start = CoroutineStart.LAZY,
        ) {
            delay(retryDelayMs)
            val shouldRestart = synchronized(renderLock) {
                if (
                    canonicalRetryJob !== retry ||
                    canonicalSnapshotEpoch != failedCollectorEpoch ||
                    !canonicalModeEligible(_state.value)
                ) {
                    false
                } else {
                    canonicalRetryJob = null
                    canonicalRetryDeadlineElapsedMs = 0L
                    true
                }
            }
            if (shouldRestart) ensureCanonicalCollection()
        }
        canonicalRetryJob = retry
        return retry
    }

    private fun stopCanonicalCollection(expectedInputRevision: Long) {
        val collectorToCancel: Job?
        val retryToCancel: Job?
        synchronized(renderLock) {
            if (filterInputRevision.get() != expectedInputRevision) return
            canonicalCollectorEpoch += 1L
            collectorToCancel = canonicalCollectionJob
            canonicalCollectionJob = null
            retryToCancel = canonicalRetryJob
            canonicalRetryJob = null
            canonicalRetryDeadlineElapsedMs = 0L
            canonicalRetryDelayMs = MIN_CANONICAL_RETRY_DELAY_MS
            canonicalSnapshotEpoch = canonicalCollectorEpoch
            latestCanonicalCards = null
            canonicalCollectionHealthy = false
            canonicalCollectionFailure = null
            val active = activeRenderToken
            if (
                active?.mode is LibraryRenderMode.Canonical ||
                active?.mode is LibraryRenderMode.WaitingCanonical
            ) {
                activeRenderToken = null
                renderJob = null
            }
        }
        collectorToCancel?.cancel(CanonicalCollectionStopped())
        retryToCancel?.cancel()
    }

    private fun isCollectorActive(collectorEpoch: Long): Boolean = synchronized(renderLock) {
        isCollectorActiveLocked(collectorEpoch) && canonicalModeEligible(_state.value)
    }

    private fun isCollectorActiveLocked(collectorEpoch: Long): Boolean =
        canonicalCollectorEpoch == collectorEpoch && canonicalCollectionJob?.isCompleted == false

    private fun canonicalModeEligible(state: LibraryState): Boolean =
        canonicalPublicLibraryGate.isEnabled() &&
            canonicalProjectionReadiness.isReady.value &&
            canonicalUnsupportedFailure(state) == null

    private fun requestCanonicalActivation(request: FilterRenderRequest): Job {
        var canonicalHandle: CanonicalRenderHandle? = null
        var waitingJob: Job? = null
        var previousRender: Job? = null
        var fallback: CanonicalPublicFailure? = null
        synchronized(renderLock) {
            if (!isFilterRequestCurrentLocked(request) || !canonicalModeEligible(request.state)) {
                return completedRenderJob()
            }
            val collectorEpoch = canonicalCollectorEpoch
            val collectorActive = isCollectorActiveLocked(collectorEpoch)
            val snapshotCurrent = canonicalSnapshotEpoch == collectorEpoch
            val snapshot = latestCanonicalCards.takeIf { snapshotCurrent }
            val failure = canonicalCollectionFailure.takeIf { snapshotCurrent }
            when {
                failure != null -> fallback = failure
                snapshot != null && collectorActive -> {
                    canonicalHandle = launchCanonicalRenderLocked(snapshot, collectorEpoch, request)
                    previousRender = canonicalHandle?.previousJob
                }
                collectorActive && snapshot == null -> {
                    val token = newRenderTokenLocked(
                        mode = LibraryRenderMode.WaitingCanonical(collectorEpoch),
                        request = request,
                    )
                    previousRender = renderJob
                    renderJob = null
                    activeRenderToken = token
                    retirePendingLegacyLocked()
                    _state.update { current -> current.copy(isLoading = true, canonicalPublicFailure = null) }
                    waitingJob = completedRenderJob()
                }
                else -> {
                    _state.update { current -> current.copy(isLoading = false) }
                    waitingJob = completedRenderJob()
                }
            }
        }
        previousRender?.cancel()
        canonicalHandle?.let { handle ->
            handle.job.start()
            observeDirectCanonicalOutcome(handle)
            return handle.job
        }
        fallback?.let { return requestLegacyRender(it, request) }
        return waitingJob ?: completedRenderJob()
    }

    // Kept as a narrow activation boundary so a delayed waiting request rechecks the snapshot atomically.
    private fun requestWaitingCanonical(
        paginationPage: Int,
        state: LibraryState,
    ): Job {
        val request = synchronized(renderLock) {
            FilterRenderRequest(filterInputRevision.get(), state, paginationPage)
        }
        ensureCanonicalCollection(request.inputRevision)
        return requestCanonicalActivation(request)
    }

    private fun requestLegacyRender(
        failure: CanonicalPublicFailure?,
        paginationPage: Int,
        state: LibraryState,
    ): Job = requestLegacyRender(
        failure,
        synchronized(renderLock) {
            FilterRenderRequest(filterInputRevision.get(), state, paginationPage)
        },
    )

    private fun requestLegacyRender(
        failure: CanonicalPublicFailure?,
        filter: FilterRenderRequest,
    ): Job {
        val request = LegacyRenderRequest(failure, filter)
        var launch: LegacyWorkerLaunch? = null
        var previousRender: Job? = null
        synchronized(renderLock) {
            if (!isFilterRequestCurrentLocked(filter) || !legacyModeEligible(failure, filter.state)) {
                request.completion.complete(Unit)
                return request.completion
            }
            previousRender = renderJob
            renderJob = null
            if (
                legacyPhysicalJob?.isCompleted == false ||
                legacyRetryJob?.isCompleted == false ||
                legacyRetryDeadlineElapsedMs > SystemClock.elapsedRealtime()
            ) {
                replacePendingLegacyLocked(request)
                installPendingTokenLocked(filter)
                _state.update { current ->
                    current.copy(
                        isLoading = legacyPhysicalJob?.isCompleted == false,
                        canonicalPublicFailure = failure,
                    )
                }
            } else {
                launch = startLegacyWorkerLocked(request)
            }
        }
        previousRender?.cancel()
        launch?.job?.start()
        return request.completion
    }

    private fun startLegacyWorkerLocked(request: LegacyRenderRequest): LegacyWorkerLaunch? {
        if (!isFilterRequestCurrentLocked(request.filter) || !legacyModeEligible(request.failure, request.filter.state)) {
            request.completion.complete(Unit)
            return null
        }
        val token = newRenderTokenLocked(LibraryRenderMode.Legacy(request.failure), request.filter)
        activeRenderToken = token
        latestPublishedToken = null
        _state.update { current ->
            current.copy(isLoading = true, canonicalPublicFailure = request.failure)
        }
        lateinit var job: Job
        job = viewModelScope.launch(
            context = canonicalDispatcher,
            start = CoroutineStart.LAZY,
        ) {
            executeLegacyWorker(job, token, request)
        }
        legacyPhysicalJob = job
        legacyPhysicalToken = token
        legacyPhysicalRequest = request
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                finishLegacyWorker(job, token, request, null, cause)
            }
        }
        return LegacyWorkerLaunch(job)
    }

    private suspend fun executeLegacyWorker(
        owningJob: Job,
        token: LibraryRenderToken,
        request: LegacyRenderRequest,
    ) {
        try {
            val outcome = runLegacyRender(token)
            currentCoroutineContext().ensureActive()
            finishLegacyWorker(owningJob, token, request, outcome, null)
        } catch (error: CancellationException) {
            finishLegacyWorker(owningJob, token, request, null, error)
            throw error
        } catch (error: Exception) {
            finishLegacyWorker(
                owningJob,
                token,
                request,
                RenderPublicationOutcome.Failed(token, error),
                error,
            )
        } catch (error: Throwable) {
            finishLegacyWorker(owningJob, token, request, null, error)
            throw error
        }
    }

    private fun finishLegacyWorker(
        owningJob: Job,
        token: LibraryRenderToken,
        request: LegacyRenderRequest,
        outcome: RenderPublicationOutcome?,
        error: Throwable?,
    ) {
        var nextLaunch: LegacyWorkerLaunch? = null
        var retryToStart: Job? = null
        var recordError: Exception? = null
        synchronized(renderLock) {
            if (
                legacyPhysicalJob !== owningJob ||
                legacyPhysicalToken != token ||
                legacyPhysicalRequest !== request
            ) {
                return
            }
            legacyPhysicalJob = null
            legacyPhysicalToken = null
            legacyPhysicalRequest = null
            request.completion.complete(Unit)

            val independentlyFailed = error is Exception &&
                error !is CanonicalCollectionStopped &&
                activeRenderToken == token &&
                token.inputRevision == filterInputRevision.get() &&
                renderModeEligible(token.mode, _state.value) &&
                viewModelScope.coroutineContext[Job]?.isActive == true
            if (independentlyFailed) {
                activeRenderToken = null
                _state.update { current ->
                    current.copy(
                        isLoading = false,
                        canonicalPublicFailure = (token.mode as LibraryRenderMode.Legacy).failure,
                    )
                }
                recordError = error as Exception
                replacePendingLegacyLocked(
                    LegacyRenderRequest(
                        failure = (token.mode as LibraryRenderMode.Legacy).failure,
                        filter = captureFilterRequestLocked(token.paginationPage),
                    ),
                )
                retryToStart = scheduleLegacyRetryLocked()
            } else if (outcome is RenderPublicationOutcome.Published) {
                legacyRetryDelayMs = MIN_LEGACY_RETRY_DELAY_MS
                legacyRetryDeadlineElapsedMs = 0L
            }

            if (retryToStart == null && legacyRetryJob?.isCompleted != false) {
                val pending = pendingLegacyRequest
                pendingLegacyRequest = null
                if (pending != null) nextLaunch = startLegacyWorkerLocked(pending)
            }
        }
        recordError?.let(::recordRenderFailure)
        retryToStart?.start()
        nextLaunch?.job?.start()
    }

    private fun scheduleLegacyRetryLocked(): Job? {
        if (legacyRetryJob?.isCompleted == false) return null
        val retryDelayMs = legacyRetryDelayMs
        legacyRetryDelayMs = (legacyRetryDelayMs * 2).coerceAtMost(MAX_LEGACY_RETRY_DELAY_MS)
        legacyRetryDeadlineElapsedMs = SystemClock.elapsedRealtime() + retryDelayMs
        lateinit var retry: Job
        retry = viewModelScope.launch(
            context = canonicalDispatcher,
            start = CoroutineStart.LAZY,
        ) {
            delay(retryDelayMs)
            var launch: LegacyWorkerLaunch? = null
            synchronized(renderLock) {
                if (legacyRetryJob !== retry) return@synchronized
                legacyRetryJob = null
                legacyRetryDeadlineElapsedMs = 0L
                val pending = pendingLegacyRequest
                pendingLegacyRequest = null
                if (pending != null) launch = startLegacyWorkerLocked(pending)
            }
            launch?.job?.start()
        }
        legacyRetryJob = retry
        return retry
    }

    private fun replacePendingLegacyLocked(request: LegacyRenderRequest) {
        pendingLegacyRequest?.completion?.complete(Unit)
        pendingLegacyRequest = request
    }

    private fun retirePendingLegacyLocked() {
        pendingLegacyRequest?.completion?.complete(Unit)
        pendingLegacyRequest = null
    }

    private fun installPendingTokenLocked(filter: FilterRenderRequest) {
        activeRenderToken = newRenderTokenLocked(LibraryRenderMode.PendingInput, filter)
        latestPublishedToken = null
    }

    private fun launchCanonicalRenderFromEmission(
        snapshot: List<CanonicalLibraryCard>,
        collectorEpoch: Long,
    ): CanonicalRenderHandle {
        val handle: CanonicalRenderHandle
        synchronized(renderLock) {
            val request = captureFilterRequestLocked(paginationCurrentPage)
            handle = launchCanonicalRenderLocked(snapshot, collectorEpoch, request)
                ?: supersededCanonicalHandleLocked(collectorEpoch, request)
        }
        handle.previousJob?.cancel()
        handle.job.start()
        return handle
    }

    private fun launchCanonicalRenderLocked(
        snapshot: List<CanonicalLibraryCard>,
        collectorEpoch: Long,
        request: FilterRenderRequest,
    ): CanonicalRenderHandle? {
        if (
            !isFilterRequestCurrentLocked(request) ||
            !isCollectorActiveLocked(collectorEpoch) ||
            !canonicalModeEligible(request.state)
        ) {
            return null
        }
        val token = newRenderTokenLocked(LibraryRenderMode.Canonical(collectorEpoch), request)
        val outcome = CompletableDeferred<RenderPublicationOutcome>()
        val previousJob = renderJob
        activeRenderToken = token
        latestPublishedToken = null
        canonicalSnapshotEpoch = collectorEpoch
        latestCanonicalCards = snapshot
        canonicalCollectionFailure = null
        retirePendingLegacyLocked()
        val job = viewModelScope.launch(
            context = canonicalDispatcher,
            start = CoroutineStart.LAZY,
        ) {
            try {
                outcome.complete(publishCanonicalCards(token, snapshot))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                outcome.complete(RenderPublicationOutcome.Failed(token, error))
            }
        }
        renderJob = job
        job.invokeOnCompletion { error ->
            if (!outcome.isCompleted) {
                if (error == null || error is CancellationException) {
                    outcome.complete(RenderPublicationOutcome.Superseded(token))
                } else if (error is Exception) {
                    outcome.complete(RenderPublicationOutcome.Failed(token, error))
                } else {
                    outcome.completeExceptionally(error)
                }
            }
        }
        return CanonicalRenderHandle(job, outcome, previousJob)
    }

    private fun supersededCanonicalHandleLocked(
        collectorEpoch: Long,
        request: FilterRenderRequest,
    ): CanonicalRenderHandle {
        val token = LibraryRenderToken(
            generation = renderGeneration.get(),
            inputRevision = request.inputRevision,
            mode = LibraryRenderMode.Canonical(collectorEpoch),
            state = request.state,
            paginationPage = request.paginationPage,
        )
        return CanonicalRenderHandle(
            job = CompletableDeferred(Unit),
            outcome = CompletableDeferred<RenderPublicationOutcome>(RenderPublicationOutcome.Superseded(token)),
            previousJob = null,
        )
    }

    private fun observeDirectCanonicalOutcome(handle: CanonicalRenderHandle) {
        viewModelScope.launch(canonicalDispatcher) {
            when (val outcome = handle.outcome.await()) {
                is RenderPublicationOutcome.Failed -> if (acceptCanonicalRenderFailure(outcome)) {
                    val collectorEpoch = (outcome.token.mode as LibraryRenderMode.Canonical).collectorEpoch
                    scheduleCanonicalCollectorRestart(collectorEpoch, CanonicalPublicFailure.ASSEMBLY_FAILED)
                }
                is RenderPublicationOutcome.Published,
                is RenderPublicationOutcome.Superseded,
                -> Unit
            }
        }
    }

    private fun acceptCanonicalRenderFailure(outcome: RenderPublicationOutcome.Failed): Boolean {
        val request: FilterRenderRequest
        val collectorEpoch: Long
        synchronized(renderLock) {
            val mode = outcome.token.mode as? LibraryRenderMode.Canonical ?: return false
            if (!claimActiveTokenLocked(outcome.token)) return false
            collectorEpoch = mode.collectorEpoch
            canonicalSnapshotEpoch = collectorEpoch
            canonicalCollectionHealthy = false
            canonicalCollectionFailure = CanonicalPublicFailure.ASSEMBLY_FAILED
            request = FilterRenderRequest(
                outcome.token.inputRevision,
                outcome.token.state,
                outcome.token.paginationPage,
            )
        }
        recordRenderFailure(outcome.error)
        requestLegacyRender(CanonicalPublicFailure.ASSEMBLY_FAILED, request)
        return true
    }

    private data class CanonicalRenderHandle(
        val job: Job,
        val outcome: CompletableDeferred<RenderPublicationOutcome>,
        val previousJob: Job?,
    )

    private data class LegacyWorkerLaunch(val job: Job)

    private fun newRenderTokenLocked(
        mode: LibraryRenderMode,
        request: FilterRenderRequest,
    ): LibraryRenderToken = LibraryRenderToken(
        generation = renderGeneration.incrementAndGet(),
        inputRevision = request.inputRevision,
        mode = mode,
        state = request.state,
        paginationPage = request.paginationPage,
    )

    private fun captureFilterRequestLocked(paginationPage: Int): FilterRenderRequest = FilterRenderRequest(
        inputRevision = filterInputRevision.get(),
        state = _state.value,
        paginationPage = paginationPage,
    )

    private fun isFilterRequestCurrentLocked(request: FilterRenderRequest): Boolean =
        request.inputRevision == filterInputRevision.get()

    private fun claimActiveTokenLocked(token: LibraryRenderToken): Boolean {
        if (
            activeRenderToken != token ||
            token.inputRevision != filterInputRevision.get() ||
            !isLegacyOwnerActiveLocked(token) ||
            !renderModeEligible(token.mode, _state.value)
        ) {
            return false
        }
        activeRenderToken = null
        renderJob = null
        latestPublishedToken = token
        return true
    }

    private fun retireActiveCollectorTokenLocked(collectorEpoch: Long) {
        val mode = activeRenderToken?.mode
        if (
            (mode is LibraryRenderMode.Canonical && mode.collectorEpoch == collectorEpoch) ||
            (mode is LibraryRenderMode.WaitingCanonical && mode.collectorEpoch == collectorEpoch)
        ) {
            activeRenderToken = null
            renderJob = null
        }
    }

    private fun isLegacyOwnerActiveLocked(token: LibraryRenderToken): Boolean =
        token.mode !is LibraryRenderMode.Legacy ||
            (legacyPhysicalToken == token && legacyPhysicalJob?.isActive == true)

    private fun isTokenActive(token: LibraryRenderToken): Boolean = synchronized(renderLock) {
        activeRenderToken == token &&
            token.inputRevision == filterInputRevision.get() &&
            isLegacyOwnerActiveLocked(token) &&
            renderModeEligible(token.mode, _state.value)
    }

    private fun isTokenCurrentForSideEffect(token: LibraryRenderToken): Boolean = synchronized(renderLock) {
        latestPublishedToken == token &&
            token.inputRevision == filterInputRevision.get() &&
            renderModeEligible(token.mode, _state.value)
    }

    private fun legacyModeEligible(failure: CanonicalPublicFailure?, state: LibraryState): Boolean = when (failure) {
        null -> !canonicalPublicLibraryGate.isEnabled()
        CanonicalPublicFailure.MISSING_PROJECTION_PREREQUISITE ->
            canonicalPublicLibraryGate.isEnabled() && !canonicalProjectionReadiness.isReady.value
        CanonicalPublicFailure.UNSUPPORTED_LEGACY_CONTEXT ->
            canonicalPublicLibraryGate.isEnabled() &&
                canonicalProjectionReadiness.isReady.value &&
                canonicalUnsupportedFailure(state) != null
        CanonicalPublicFailure.ASSEMBLY_FAILED,
        CanonicalPublicFailure.INVALID_CARD_STATE,
        -> canonicalModeEligible(state) && canonicalCollectionFailure == failure
    }

    private fun renderModeEligible(mode: LibraryRenderMode, state: LibraryState): Boolean = when (mode) {
        is LibraryRenderMode.Legacy -> legacyModeEligible(mode.failure, state)
        is LibraryRenderMode.Canonical ->
            canonicalModeEligible(state) && isCollectorActiveLocked(mode.collectorEpoch)
        is LibraryRenderMode.WaitingCanonical ->
            canonicalModeEligible(state) &&
                isCollectorActiveLocked(mode.collectorEpoch) &&
                canonicalSnapshotEpoch == mode.collectorEpoch &&
                latestCanonicalCards == null &&
                canonicalCollectionFailure == null
        LibraryRenderMode.PendingInput -> true
    }

    private fun guardedSideEffect(
        token: LibraryRenderToken,
        effect: () -> Unit,
    ): Boolean = synchronized(renderLock) {
        if (
            activeRenderToken != token ||
            token.inputRevision != filterInputRevision.get() ||
            !isLegacyOwnerActiveLocked(token) ||
            !renderModeEligible(token.mode, _state.value)
        ) {
            false
        } else {
            effect()
            true
        }
    }

    private fun guardedPublishedStateUpdate(
        token: LibraryRenderToken,
        transform: (LibraryState) -> LibraryState,
    ): Boolean = synchronized(renderLock) {
        if (
            latestPublishedToken != token ||
            token.inputRevision != filterInputRevision.get() ||
            !renderModeEligible(token.mode, _state.value)
        ) {
            false
        } else {
            _state.update(transform)
            true
        }
    }

    private fun completedRenderJob(): Job = CompletableDeferred(Unit)

    private fun supersedeRenderForPendingInput(
        paginationPageLocked: () -> Int = { paginationCurrentPage },
        updateInputLocked: () -> Unit = {},
        transformState: (LibraryState) -> LibraryState = { it },
    ): FilterRenderRequest = requireNotNull(
        supersedeRenderForPendingInputInternal(
            expectedInputRevision = null,
            expectedPublishedToken = null,
            paginationPageLocked = paginationPageLocked,
            updateInputLocked = updateInputLocked,
            transformState = transformState,
        ),
    )

    private fun supersedeRenderIfInputCurrent(
        expectedInputRevision: Long,
        paginationPageLocked: () -> Int = { paginationCurrentPage },
        updateInputLocked: () -> Unit = {},
        transformState: (LibraryState) -> LibraryState = { it },
    ): FilterRenderRequest? = supersedeRenderForPendingInputInternal(
        expectedInputRevision = expectedInputRevision,
        expectedPublishedToken = null,
        paginationPageLocked = paginationPageLocked,
        updateInputLocked = updateInputLocked,
        transformState = transformState,
    )

    private fun supersedeRenderForPublishedToken(
        token: LibraryRenderToken,
    ): FilterRenderRequest? = supersedeRenderForPendingInputInternal(
        expectedInputRevision = token.inputRevision,
        expectedPublishedToken = token,
        paginationPageLocked = { paginationCurrentPage },
        updateInputLocked = {},
        transformState = { it },
    )

    private fun supersedeRenderForPendingInputInternal(
        expectedInputRevision: Long?,
        expectedPublishedToken: LibraryRenderToken?,
        paginationPageLocked: () -> Int,
        updateInputLocked: () -> Unit,
        transformState: (LibraryState) -> LibraryState,
    ): FilterRenderRequest? {
        val previousRender: Job?
        val request: FilterRenderRequest
        synchronized(renderLock) {
            if (
                (expectedInputRevision != null && filterInputRevision.get() != expectedInputRevision) ||
                (expectedPublishedToken != null && latestPublishedToken != expectedPublishedToken)
            ) {
                return null
            }
            val paginationPage = paginationPageLocked()
            val revision = filterInputRevision.incrementAndGet()
            previousRender = renderJob
            renderJob = null
            latestPublishedToken = null
            retirePendingLegacyLocked()
            updateInputLocked()
            _state.update { current -> transformState(current).copy(isLoading = false) }
            request = FilterRenderRequest(
                inputRevision = revision,
                state = _state.value,
                paginationPage = paginationPage,
            )
            activeRenderToken = newRenderTokenLocked(LibraryRenderMode.PendingInput, request)
        }
        previousRender?.cancel()
        return request
    }

    private fun recordRenderFailure(error: Throwable) {
        FeatureDiagnostics.record(
            area = DiagnosticArea.LIBRARY_FILTER,
            name = DiagnosticEventName.LIBRARY_FILTER,
            outcome = DiagnosticOutcome.FAILED,
            attributes = mapOf(DiagnosticAttribute.ERROR_TYPE to error.javaClass.simpleName),
        )
    }

    private suspend fun publishCanonicalCards(
        token: LibraryRenderToken,
        snapshot: List<CanonicalLibraryCard>,
    ): RenderPublicationOutcome {
        val diagnosticStartedAt = SystemClock.elapsedRealtime()
        val currentState = token.state
        FeatureDiagnostics.record(
            area = DiagnosticArea.LIBRARY_FILTER,
            name = DiagnosticEventName.LIBRARY_FILTER,
            outcome = DiagnosticOutcome.STARTED,
            attributes = mapOf(
                DiagnosticAttribute.FILTER_GROUPS to diagnosticFilterGroups(currentState),
            ),
        )
        if (!isTokenActive(token)) return RenderPublicationOutcome.Superseded(token)
        val page = CanonicalLibraryFilter.project(
            cards = snapshot,
            state = currentState,
            paginationPage = token.paginationPage,
            pageSize = PrefManager.itemsPerPage,
            promotion = canonicalPromotionItem(),
            showRecommendations = PrefManager.showRecommendations,
            compatibility = { card ->
                CanonicalCompatibilityLookup.resolve(
                    card = card,
                    cachedStatus = { name ->
                        GameCompatibilityCache.getCached(name)?.let(::compatibilityStatusFor)
                    },
                )
            },
        )
        var retryToCancel: Job? = null
        val committed = synchronized(renderLock) {
            if (!claimActiveTokenLocked(token)) {
                false
            } else {
                paginationCurrentPage = page.paginationPage
                lastPageInCurrentFilter = page.lastPage
                if (isFirstLoad) isFirstLoad = false
                canonicalCollectionHealthy = true
                canonicalSnapshotEpoch = (token.mode as LibraryRenderMode.Canonical).collectorEpoch
                canonicalCollectionFailure = null
                latestCanonicalCards = snapshot
                canonicalRetryDelayMs = MIN_CANONICAL_RETRY_DELAY_MS
                canonicalRetryDeadlineElapsedMs = 0L
                retryToCancel = canonicalRetryJob
                canonicalRetryJob = null
                _state.update { state ->
                    state.copy(
                        cards = page.cards,
                        currentPaginationPage = page.paginationPage + 1,
                        lastPaginationPage = page.lastPage + 1,
                        totalAppsInFilter = page.totalCount,
                        isLoading = false,
                        canonicalPublicFailure = null,
                        allCount = page.allCount,
                        steamCount = if (currentState.showSteamInLibrary) {
                            page.sourceCounts.getValue(GameSource.STEAM)
                        } else {
                            0
                        },
                        gogCount = if (currentState.showGOGInLibrary) {
                            page.sourceCounts.getValue(GameSource.GOG)
                        } else {
                            0
                        },
                        epicCount = if (currentState.showEpicInLibrary) {
                            page.sourceCounts.getValue(GameSource.EPIC)
                        } else {
                            0
                        },
                        amazonCount = if (currentState.showAmazonInLibrary) {
                            page.sourceCounts.getValue(GameSource.AMAZON)
                        } else {
                            0
                        },
                        localCount = if (currentState.showCustomGamesInLibrary) {
                            page.sourceCounts.getValue(GameSource.CUSTOM_GAME)
                        } else {
                            0
                        },
                        steamCollectionCounts = page.steamCollectionCounts,
                    )
                }
                true
            }
        }
        retryToCancel?.cancel()
        if (!committed) return RenderPublicationOutcome.Superseded(token)

        fetchCompatibilityForPage(page.compatibilityRequestNames, token)
        FeatureDiagnostics.record(
            area = DiagnosticArea.LIBRARY_FILTER,
            name = DiagnosticEventName.LIBRARY_FILTER,
            outcome = DiagnosticOutcome.SUCCEEDED,
            durationMs = SystemClock.elapsedRealtime() - diagnosticStartedAt,
            attributes = mapOf(
                DiagnosticAttribute.RESULT_COUNT to page.totalCount.toString(),
                DiagnosticAttribute.STEAM_COUNT to page.sourceCounts.getValue(GameSource.STEAM).toString(),
                DiagnosticAttribute.GOG_COUNT to page.sourceCounts.getValue(GameSource.GOG).toString(),
                DiagnosticAttribute.EPIC_COUNT to page.sourceCounts.getValue(GameSource.EPIC).toString(),
                DiagnosticAttribute.AMAZON_COUNT to page.sourceCounts.getValue(GameSource.AMAZON).toString(),
                DiagnosticAttribute.CUSTOM_COUNT to page.sourceCounts.getValue(GameSource.CUSTOM_GAME).toString(),
            ),
        )
        return RenderPublicationOutcome.Published(token)
    }

    private fun canonicalPromotionItem(): LibraryItem? {
        val featured = cachedFeatured
        if (featured != null) {
            return LibraryItem(
                index = -1,
                appId = "FEATURED_${featured.campaignId}",
                name = featured.title,
                heroImageUrl = featured.heroImageUrl,
                headerImageUrl = featured.heroImageUrl,
                capsuleImageUrl = featured.capsuleImageUrl ?: featured.heroImageUrl,
                iconHash = featured.iconUrl ?: featured.capsuleImageUrl ?: featured.heroImageUrl,
                isRecommended = true,
                isFeatured = true,
                recommendedGameId = featured.campaignId,
                recSource = "hero",
                gameSource = GameSource.STEAM,
            )
        }
        val rec = cachedRecommendation ?: return null
        return LibraryItem(
            index = -1,
            appId = "RECOMMENDED_${rec.id}",
            name = rec.name,
            heroImageUrl = rec.heroImageUrl,
            capsuleImageUrl = rec.capsuleImageUrl,
            iconHash = rec.iconUrl ?: rec.capsuleImageUrl,
            isRecommended = true,
            recommendedGameId = rec.id,
            recSource = "hero",
            gameSource = GameSource.STEAM,
        )
    }

    private fun immutableCanonicalCards(
        cards: List<CanonicalLibraryCard>,
    ): List<CanonicalLibraryCard> = if (cards.isEmpty()) {
        emptyList()
    } else {
        Collections.unmodifiableList(ArrayList(cards))
    }

    private suspend fun runLegacyRender(token: LibraryRenderToken): RenderPublicationOutcome {
        val paginationPage = token.paginationPage
        val currentState = token.state
        Timber.tag("LibraryViewModel").d("onFilterApps - appList.size: ${appList.size}, isFirstLoad: $isFirstLoad")
        val diagnosticStartedAt = SystemClock.elapsedRealtime()
        FeatureDiagnostics.record(
            area = DiagnosticArea.LIBRARY_FILTER,
            name = DiagnosticEventName.LIBRARY_FILTER,
            outcome = DiagnosticOutcome.STARTED,
            attributes = mapOf(
                DiagnosticAttribute.FILTER_GROUPS to diagnosticFilterGroups(currentState),
            ),
        )
        if (!isTokenActive(token)) return RenderPublicationOutcome.Superseded(token)

            val currentFilter = AppFilter.getAppType(currentState.appInfoSortType)

            // Fetch download directory apps once on IO thread and cache as a HashSet for O(1) lookups
            val downloadDirectoryApps = DownloadService.getDownloadDirectoryApps() + SteamService.getImportedAppDirs()
            val downloadDirectorySet = downloadDirectoryApps.toHashSet()

            fun passesCompatibleFilter(gameName: String): Boolean {
                if (!currentState.appInfoSortType.contains(AppFilter.COMPATIBLE)) {
                    return true
                }
                val cached = GameCompatibilityCache.getCached(gameName) ?: return true
                val status = compatibilityStatusFor(cached)
                return status == GameCompatibilityStatus.COMPATIBLE || status == GameCompatibilityStatus.GPU_COMPATIBLE
            }

            val steamOwnerTypeFiltered: List<SteamApp> = appList
                .asSequence()
                .filter { item ->
                    SteamService.familyMembers.ifEmpty {
                        // Handle the case where userSteamId might be null
                        SteamService.userSteamId?.let { steamId ->
                            listOf(steamId.accountID.toInt())
                        } ?: emptyList()
                    }.let { owners ->
                        if (owners.isEmpty()) {
                            true // no owner info ⇒ don’t filter the item out
                        } else {
                            owners.any { item.ownerAccountId.contains(it) }
                        }
                    }
                }
                .filter { item ->
                    currentFilter.any { item.type == it }
                }
                .filter { item ->
                    if (currentState.appInfoSortType.contains(AppFilter.SHARED)) {
                        true
                    } else {
                        item.ownerAccountId.contains(PrefManager.steamUserAccountId) || PrefManager.steamUserAccountId == 0
                    }
                }
                .filter { item ->
                    if (currentState.searchQuery.isNotEmpty()) {
                        matches(item.name, currentState.searchQuery)
                    } else {
                        true
                    }
                }
                .filter { item ->
                    val installedOnly = currentState.currentTab.installedOnly ||
                        currentState.appInfoSortType.contains(AppFilter.INSTALLED)
                    if (installedOnly) {
                        downloadDirectorySet.contains(SteamService.getAppDirName(item))
                    } else {
                        true
                    }
                }
                .toList()

            // Per-collection counts: computed from the owner/type/search-filtered set (independent of the
            // current collection selection) so each collection shows how many games it would contribute.
            val steamCollectionCounts: Map<String, Int> = steamCollections?.associate { collection ->
                collection.id to steamOwnerTypeFiltered.count { it.id in collection.appIds }
            } ?: emptyMap()

            // Apply the Steam collection filter — union/OR, fail-open (see SteamCollectionFilter).
            // Resolve the allowed app-id set once for the whole pass instead of per app.
            val allowedSteamAppIds = SteamCollectionFilter.allowedAppIds(
                selectedIds = currentState.selectedSteamCollectionIds,
                collections = steamCollections,
            )
            val steamFilteredBeforeCompatibility: List<SteamApp> =
                (
                    if (allowedSteamAppIds == null) {
                        steamOwnerTypeFiltered
                    } else {
                        steamOwnerTypeFiltered.filter { it.id in allowedSteamAppIds }
                    }
                )

            // Filter Steam apps first (no pagination yet)
            // Note: Don't sort individual lists - we'll sort the combined list for consistent ordering
            val filteredSteamApps: List<SteamApp> = steamFilteredBeforeCompatibility
                .asSequence()
                .filter { item -> passesCompatibleFilter(item.name) }
                .filter { item -> passesStatsFilters(currentState, GameSource.STEAM, item.name) }
                .sortedWith(
                    compareByDescending<SteamApp> {
                        downloadDirectorySet.contains(SteamService.getAppDirName(it))
                    }.thenBy { it.name.lowercase() },
                )
                .toList()

            // Map Steam apps to UI items
            data class LibraryEntry(val item: LibraryItem, val isInstalled: Boolean, val lastPlayed: Long = 0L)

            fun lastPlayedFor(appId: String): Long = playHistoryByAppId[appId] ?: 0L

            val licensedDepotMap = SteamService.buildLicensedDepotMap(filteredSteamApps)

            // Added this to avoid duplicate from custom imported steam game
            val steamEntriesAppIds = mutableSetOf<String>()

            val steamEntries: List<LibraryEntry> = filteredSteamApps.map { item ->
                val isInstalled = downloadDirectorySet.contains(SteamService.getAppDirName(item))
                val installedBranch = if (isInstalled) {
                    SteamService.getInstalledApp(item.id)?.branch ?: "public"
                } else {
                    "public"
                }
                // base-game size: ownedDlc=emptyMap excludes DLC depots
                val licensedDepots = licensedDepotMap[item.id]
                val resolved = SteamService.resolveDownloadableDepots(item.depots, "", emptyMap(), licensedDepots)
                val totalSizeBytes = resolved.values.sumOf { depot ->
                    depot.manifests[installedBranch]?.size ?: depot.manifests.values.firstOrNull()?.size ?: 0L
                }

                // Move appId here
                val appId = "${GameSource.STEAM.name}_${item.id}"
                steamEntriesAppIds.add(appId)

                LibraryEntry(
                    item = LibraryItem(
                        index = 0, // temporary, will be re-indexed after combining and paginating
                        appId = appId,
                        name = item.name,
                        iconHash = item.clientIconHash,
                        capsuleImageUrl = item.getCapsuleUrl(),
                        headerImageUrl = item.headerUrl,
                        heroImageUrl = item.getHeroUrl(),
                        isShared = (PrefManager.steamUserAccountId != 0 && !item.ownerAccountId.contains(PrefManager.steamUserAccountId)),
                        sizeBytes = totalSizeBytes,
                    ),
                    isInstalled = isInstalled,
                    lastPlayed = lastPlayedFor(appId),
                )
            }

            // Scan Custom Games roots and create UI items (filtered by search query inside scanner)
            // Only include custom games if GAME filter is selected
            val customGameItems = if (currentState.appInfoSortType.contains(AppFilter.GAME)) {
                CustomGameScanner.scanAsLibraryItems(
                    query = currentState.searchQuery,
                )
            } else {
                emptyList()
            }
            val customEntries = customGameItems
                .filter { !steamEntriesAppIds.contains(it.appId) } // Filter out imported steam appId
                .filter { passesStatsFilters(currentState, it.gameSource, it.name) }
                .map { LibraryEntry(it, true, lastPlayed = lastPlayedFor(it.appId)) }

            // Filter GOG games
            val filteredGOGGames = gogGameList
                .asSequence()
                .filter { game ->
                    if (currentState.searchQuery.isNotEmpty()) {
                        matches(game.title, currentState.searchQuery)
                    } else {
                        true
                    }
                }
                .filter { game ->
                    val installedOnly = currentState.currentTab.installedOnly ||
                        currentState.appInfoSortType.contains(AppFilter.INSTALLED)
                    if (installedOnly) {
                        game.isInstalled
                    } else {
                        true
                    }
                }
                .toList()

            val gogEntries = filteredGOGGames
                .filter { passesCompatibleFilter(it.title) }
                .filter { passesStatsFilters(currentState, GameSource.GOG, it.title) }
                .map { game ->
                    val appId = "${GameSource.GOG.name}_${game.id}"
                    LibraryEntry(
                        item = LibraryItem(
                            index = 0,
                            appId = appId,
                            name = game.title,
                            iconHash = game.iconUrl.ifEmpty { game.imageUrl },
                            capsuleImageUrl = game.verticalCoverUrl.ifEmpty { game.iconUrl.ifEmpty { game.imageUrl } },
                            headerImageUrl = game.imageUrl.ifEmpty { game.iconUrl },
                            heroImageUrl = game.imageUrl.ifEmpty { game.iconUrl },
                            isShared = false,
                            gameSource = GameSource.GOG,
                        ),
                        isInstalled = game.isInstalled,
                        lastPlayed = lastPlayedFor(appId),
                    )
                }

            // Filter Epic games
            val filteredEpicGames = epicGameList
                .asSequence()
                .filter { game ->
                    if (currentState.searchQuery.isNotEmpty()) {
                        matches(game.title, currentState.searchQuery)
                    } else {
                        true
                    }
                }
                .filter { game ->
                    val installedOnly = currentState.currentTab.installedOnly ||
                        currentState.appInfoSortType.contains(AppFilter.INSTALLED)
                    if (installedOnly) {
                        game.isInstalled
                    } else {
                        true
                    }
                }
                .toList()

            val epicEntries = filteredEpicGames
                .filter { passesCompatibleFilter(it.title) }
                .filter { passesStatsFilters(currentState, GameSource.EPIC, it.title) }
                .map { game ->
                    val appId = "${GameSource.EPIC.name}_${game.id}"
                    LibraryEntry(
                        item = LibraryItem(
                            index = 0,
                            appId = appId,
                            name = game.title,
                            iconHash = game.artSquare.ifEmpty { game.artCover },
                            capsuleImageUrl = game.artCover.ifEmpty { game.artSquare },
                            headerImageUrl = game.artPortrait.ifEmpty { game.artSquare.ifEmpty { game.artCover } },
                            heroImageUrl = game.artPortrait.ifEmpty { game.artSquare.ifEmpty { game.artCover } },
                            isShared = false,
                            gameSource = GameSource.EPIC,
                        ),
                        isInstalled = game.isInstalled,
                        lastPlayed = lastPlayedFor(appId),
                    )
                }

            // Amazon games
            val filteredAmazonGames = amazonGameList
                .asSequence()
                .filter { game ->
                    if (currentState.searchQuery.isNotEmpty()) {
                        matches(game.title, currentState.searchQuery)
                    } else {
                        true
                    }
                }
                .filter { game ->
                    val installedOnly = currentState.currentTab.installedOnly ||
                        currentState.appInfoSortType.contains(AppFilter.INSTALLED)
                    if (installedOnly) {
                        game.isInstalled
                    } else {
                        true
                    }
                }
                .toList()

            val amazonEntries = filteredAmazonGames
                .filter { passesCompatibleFilter(it.title) }
                .filter { passesStatsFilters(currentState, GameSource.AMAZON, it.title) }
                .map { game ->
                    val layoutHero = AmazonArtwork.layoutHeroFromProductJson(game.productJson)
                        .ifEmpty { game.heroUrl.ifEmpty { game.artUrl } }
                    val appId = "${GameSource.AMAZON.name}_${game.appId}"
                    LibraryEntry(
                        item = LibraryItem(
                            index = 0,
                            appId = appId,
                            name = game.title,
                            iconHash = game.artUrl,
                            capsuleImageUrl = game.artUrl,
                            headerImageUrl = layoutHero,
                            heroImageUrl = layoutHero.ifEmpty { game.artUrl },
                            gridHeroImageScale = AmazonArtwork.GRID_HERO_ZOOM_SCALE,
                            isShared = false,
                            gameSource = GameSource.AMAZON,
                        ),
                        isInstalled = game.isInstalled,
                        lastPlayed = lastPlayedFor(appId),
                    )
                }

            // Calculate installed counts
            val gogInstalledCount = filteredGOGGames.count { it.isInstalled }
            val epicInstalledCount = filteredEpicGames.count { it.isInstalled }
            val amazonInstalledCount = filteredAmazonGames.count { it.isInstalled }
            // Save game counts for skeleton loaders (only when not searching, to get accurate counts)
            // This needs to happen before filtering by source, so we save the total counts
            if (currentState.searchQuery.isEmpty() && isTokenActive(token)) {
                guardedSideEffect(token) { PrefManager.customGamesCount = customGameItems.size }
                guardedSideEffect(token) { PrefManager.steamGamesCount = steamFilteredBeforeCompatibility.size }
                guardedSideEffect(token) { PrefManager.gogGamesCount = filteredGOGGames.size }
                guardedSideEffect(token) { PrefManager.gogInstalledGamesCount = gogInstalledCount }
                guardedSideEffect(token) { PrefManager.epicGamesCount = filteredEpicGames.size }
                guardedSideEffect(token) { PrefManager.epicInstalledGamesCount = epicInstalledCount }
                guardedSideEffect(token) { PrefManager.amazonInstalledGamesCount = amazonInstalledCount }
                Timber.tag("LibraryViewModel").d("Saved counts - Custom: ${customGameItems.size}, Steam: ${steamFilteredBeforeCompatibility.size}, GOG: ${filteredGOGGames.size}, GOG installed: $gogInstalledCount, Epic: ${filteredEpicGames.size}, Epic installed: $epicInstalledCount, Amazon installed: $amazonInstalledCount")
            }

            // Compute effective source filters based on current tab
            // ALL tab uses user preferences, other tabs override with their presets
            // Use captured currentState (not _state.value) to avoid TOCTOU race
            val currentTab = currentState.currentTab
            val includeSteam = if (currentTab == app.gamenative.ui.enums.LibraryTab.ALL) {
                currentState.showSteamInLibrary
            } else {
                currentTab.showSteam
            }
            val includeOpen = if (currentTab == app.gamenative.ui.enums.LibraryTab.ALL) {
                currentState.showCustomGamesInLibrary
            } else {
                currentTab.showCustom
            }

            val includeGOG = (if (currentTab == app.gamenative.ui.enums.LibraryTab.ALL) {
                currentState.showGOGInLibrary
            } else {
                currentTab.showGoG
            }) && GOGService.hasStoredCredentials(context)

            val includeEpic = (if (currentTab == app.gamenative.ui.enums.LibraryTab.ALL) {
                currentState.showEpicInLibrary
            } else {
                currentTab.showEpic
            }) && EpicService.hasStoredCredentials(context)

            val includeAmazon = (if (currentTab == app.gamenative.ui.enums.LibraryTab.ALL) {
                currentState.showAmazonInLibrary
            } else {
                currentTab.showAmazon
            }) && AmazonService.hasStoredCredentials(context)

            // Combine both lists and apply sort option
            val sortComparator: Comparator<LibraryEntry> = when (currentState.currentSortOption) {
                SortOption.INSTALLED_FIRST -> compareBy<LibraryEntry> { entry ->
                    if (entry.isInstalled) 0 else 1
                }.thenBy { it.item.name.lowercase() }

                SortOption.NAME_ASC -> compareBy { it.item.name.lowercase() }

                SortOption.NAME_DESC -> compareByDescending { it.item.name.lowercase() }

                SortOption.RECENTLY_PLAYED -> LibrarySortUtils.recentlyPlayedComparator(
                    name = { it.item.name },
                    isInstalled = { it.isInstalled },
                    lastPlayed = { it.lastPlayed },
                )

                SortOption.SIZE_SMALLEST -> compareBy<LibraryEntry> { it.item.sizeBytes }
                    .thenBy { it.item.name.lowercase() }

                SortOption.SIZE_LARGEST -> compareByDescending<LibraryEntry> { it.item.sizeBytes }
                    .thenBy { it.item.name.lowercase() }

                SortOption.FPS_HIGH -> compareByDescending<LibraryEntry> {
                    currentState.statsFor(it.item)?.fps ?: -1
                }.thenBy { it.item.name.lowercase() }

                SortOption.RUNS_HIGH -> compareByDescending<LibraryEntry> {
                    currentState.statsFor(it.item)?.runsGpu ?: -1
                }.thenBy { it.item.name.lowercase() }

                SortOption.REVIEWS_HIGH -> compareByDescending<LibraryEntry> {
                    currentState.statsFor(it.item)?.reviewsDevice ?: -1
                }.thenBy { it.item.name.lowercase() }

                SortOption.REVIEWS_GPU_HIGH -> compareByDescending<LibraryEntry> {
                    currentState.statsFor(it.item)?.reviewsGpu ?: -1
                }.thenBy { it.item.name.lowercase() }
            }

            // A Steam collection can only contain Steam apps, so when one is selected the non-Steam
            // sources can't match it — keep them out of the combined list (and their tab counts).
            val steamCollectionSelected = allowedSteamAppIds != null

            val combined = buildList {
                if (includeSteam) addAll(steamEntries)
                if (includeOpen && !steamCollectionSelected) addAll(customEntries)
                if (includeGOG && !steamCollectionSelected) addAll(gogEntries)
                if (includeEpic && !steamCollectionSelected) addAll(epicEntries)
                if (includeAmazon && !steamCollectionSelected) addAll(amazonEntries)
            }.sortedWith(sortComparator).mapIndexed { idx, entry ->
                entry.item.copy(index = idx, isInstalled = entry.isInstalled)
            }

            // Total count for the current filter
            val totalFound = combined.size

            // Determine how many pages and slice the list for incremental loading
            val pageSize = PrefManager.itemsPerPage
            val lastPage = if (totalFound == 0) 0 else (totalFound - 1) / pageSize
            val safePage = paginationPage
            // Calculate how many items to show: (pagesLoaded * pageSize)
            val endIndex = min((safePage + 1) * pageSize, totalFound)
            var pagedList = combined.take(endIndex)

            // Prepend the hero (featured > recommendation) as first item on ALL tab when
            // enabled and not searching.
            val featured = cachedFeatured
            val rec = cachedRecommendation
            if (PrefManager.showRecommendations
                && currentTab == LibraryTab.ALL
                && currentState.searchQuery.isEmpty()
            ) {
                val heroItem = when {
                    featured != null -> LibraryItem(
                        index = -1,
                        appId = "FEATURED_${featured.campaignId}",
                        name = featured.title,
                        heroImageUrl = featured.heroImageUrl,
                        headerImageUrl = featured.heroImageUrl,
                        capsuleImageUrl = featured.capsuleImageUrl ?: featured.heroImageUrl,
                        iconHash = featured.iconUrl ?: featured.capsuleImageUrl ?: featured.heroImageUrl,
                        isRecommended = true,
                        isFeatured = true,
                        recommendedGameId = featured.campaignId,
                        recSource = "hero",
                        gameSource = GameSource.STEAM,
                    )
                    rec != null -> LibraryItem(
                        index = -1,
                        appId = "RECOMMENDED_${rec.id}",
                        name = rec.name,
                        heroImageUrl = rec.heroImageUrl,
                        capsuleImageUrl = rec.capsuleImageUrl,
                        iconHash = rec.iconUrl ?: rec.capsuleImageUrl,
                        isRecommended = true,
                        recommendedGameId = rec.id,
                        recSource = "hero",
                        gameSource = GameSource.STEAM,
                    )
                    else -> null
                }
                if (heroItem != null) {
                    pagedList = listOf(heroItem) + pagedList.map { it.copy(index = it.index + 1) }
                }
            }

            Timber.tag("LibraryViewModel").d("Filtered list size (with Custom Games): $totalFound")

            val cards = pagedList.map { item ->
                val compatibility = currentState.compatibilityMap[item.name]
                val stats = currentState.statsFor(item)
                if (item.isRecommended || item.isFeatured) {
                    LibraryCard.fromPromotion(item, compatibility, stats)
                } else {
                    LibraryCard.fromSource(item, compatibility, stats)
                }
            }
            val hasGog = GOGService.hasStoredCredentials(context)
            val hasEpic = EpicService.hasStoredCredentials(context)
            val hasAmazon = AmazonService.hasStoredCredentials(context)
            var retryToCancel: Job? = null
            val published = synchronized(renderLock) {
                if (!claimActiveTokenLocked(token)) {
                    false
                } else {
                    paginationCurrentPage = safePage
                    lastPageInCurrentFilter = lastPage
                    if (isFirstLoad) isFirstLoad = false
                    legacyRetryDelayMs = MIN_LEGACY_RETRY_DELAY_MS
                    legacyRetryDeadlineElapsedMs = 0L
                    retryToCancel = legacyRetryJob
                    legacyRetryJob = null
                    _state.update {
                        it.copy(
                            cards = cards,
                            currentPaginationPage = safePage + 1,
                            lastPaginationPage = lastPage + 1,
                            totalAppsInFilter = totalFound,
                            isLoading = false,
                            allCount = (if (currentState.showSteamInLibrary) steamEntries.size else 0) +
                                (if (currentState.showCustomGamesInLibrary) customEntries.size else 0) +
                                (if (currentState.showGOGInLibrary && hasGog) gogEntries.size else 0) +
                                (if (currentState.showEpicInLibrary && hasEpic) epicEntries.size else 0) +
                                (if (currentState.showAmazonInLibrary && hasAmazon) amazonEntries.size else 0),
                            steamCount = if (currentState.showSteamInLibrary) steamEntries.size else 0,
                            gogCount = if (currentState.showGOGInLibrary && hasGog) gogEntries.size else 0,
                            epicCount = if (currentState.showEpicInLibrary && hasEpic) epicEntries.size else 0,
                            amazonCount = if (currentState.showAmazonInLibrary && hasAmazon) amazonEntries.size else 0,
                            localCount = if (currentState.showCustomGamesInLibrary) customEntries.size else 0,
                            steamCollectionCounts = steamCollectionCounts,
                        )
                    }
                    true
                }
            }
            retryToCancel?.cancel()
            if (!published) return RenderPublicationOutcome.Superseded(token)

            fetchCompatibilityForPage(cards.map { it.name }, token)
            FeatureDiagnostics.record(
                area = DiagnosticArea.LIBRARY_FILTER,
                name = DiagnosticEventName.LIBRARY_FILTER,
                outcome = DiagnosticOutcome.SUCCEEDED,
                durationMs = SystemClock.elapsedRealtime() - diagnosticStartedAt,
                attributes = mapOf(
                    DiagnosticAttribute.RESULT_COUNT to totalFound.toString(),
                    DiagnosticAttribute.STEAM_COUNT to steamEntries.size.toString(),
                    DiagnosticAttribute.GOG_COUNT to gogEntries.size.toString(),
                    DiagnosticAttribute.EPIC_COUNT to epicEntries.size.toString(),
                    DiagnosticAttribute.AMAZON_COUNT to amazonEntries.size.toString(),
                    DiagnosticAttribute.CUSTOM_COUNT to customEntries.size.toString(),
                ),
            )
            return RenderPublicationOutcome.Published(token)
    }

    /**
     * Compares the game name against the search query using an exact match
     * and then again using a normalized form with diacritics removed.
     */
    private fun matches(gameName: String, searchQuery:String): Boolean {
        return gameName.contains(searchQuery, ignoreCase = true) || gameName.unaccent().contains(searchQuery, ignoreCase = true)
    }

    /**
     * Fetches compatibility information for games in paginated batches.
     * Checks cache first, then fetches uncached games in batches of 50.
     */
    private fun fetchCompatibilityForPage(
        gameNames: List<String>,
        token: LibraryRenderToken,
    ) {
        if (gameNames.isEmpty() || !isTokenCurrentForSideEffect(token)) {
            Timber.tag("LibraryViewModel").d("fetchCompatibilityForPage: No game names provided")
            return
        }

        Timber.tag("LibraryViewModel").d("fetchCompatibilityForPage: Fetching compatibility for ${gameNames.size} games, GPU: $gpuName")

        // Don't make API calls if GPU name is unknown
        if (gpuName == "Unknown GPU") {
            Timber.tag("LibraryViewModel").w("Skipping compatibility fetch - GPU name is unknown")
            return
        }

        viewModelScope.launch(canonicalDispatcher) {
            try {
                if (!isTokenCurrentForSideEffect(token)) return@launch
                // Separate cached and uncached games
                val uncachedGames = mutableListOf<String>()
                val cachedResults = mutableMapOf<String, GameCompatibilityService.GameCompatibilityResponse>()

                for (gameName in gameNames) {
                    val cached = GameCompatibilityCache.getCached(gameName)
                    if (cached != null) {
                        cachedResults[gameName] = cached
                        Timber.tag("LibraryViewModel").d("Using cached result for: $gameName")
                    } else {
                        uncachedGames.add(gameName)
                    }
                }

                Timber.tag("LibraryViewModel").d("Cached: ${cachedResults.size}, Uncached: ${uncachedGames.size}")

                // Update state with cached results immediately (for instant UI update)
                if (cachedResults.isNotEmpty()) {
                    updateCompatibilityState(cachedResults, token)
                }

                // Only fetch if there are uncached games
                if (uncachedGames.isEmpty()) {
                    Timber.tag("LibraryViewModel").d("All games in page are cached, skipping API call")
                    return@launch
                }

                // Fetch uncached games in batches of 25
                val batchSize = 25
                val fetchedResults = mutableMapOf<String, GameCompatibilityService.GameCompatibilityResponse>()

                for (i in uncachedGames.indices step batchSize) {
                    if (!isTokenCurrentForSideEffect(token)) return@launch
                    val batch = uncachedGames.subList(i, min(i + batchSize, uncachedGames.size))
                    Timber.tag("LibraryViewModel").d("Fetching batch ${i / batchSize + 1} with ${batch.size} games")
                    val batchResults = GameCompatibilityService.fetchCompatibility(batch, gpuName)

                    if (batchResults != null) {
                        Timber.tag("LibraryViewModel").d("Received ${batchResults.size} results from API")
                        // Cache all results using batch caching
                        GameCompatibilityCache.cacheAll(batchResults)
                        fetchedResults.putAll(batchResults)
                    } else {
                        Timber.tag("LibraryViewModel").w("API returned null for batch")
                    }
                }

                // Update state with newly fetched results
                if (fetchedResults.isNotEmpty()) {
                    updateCompatibilityState(fetchedResults, token)
                    // Re-apply list filtering once new compatibility data is available.
                    if (token.state.appInfoSortType.contains(AppFilter.COMPATIBLE)) {
                        supersedeRenderForPublishedToken(token)?.let(::onFilterApps)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (e: Exception) {
                Timber.tag("LibraryViewModel").e(e, "Error fetching compatibility data: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * Updates the state with compatibility results.
     */
    private fun updateCompatibilityState(
        results: Map<String, GameCompatibilityService.GameCompatibilityResponse>,
        token: LibraryRenderToken,
    ) {
        val compatibilityMap = results.mapValues { (gameName, response) ->
            compatibilityStatusFor(response)
        }

        // Update state with compatibility map (merge with existing)
        guardedPublishedStateUpdate(token) { currentState ->
            val mergedMap = currentState.compatibilityMap.toMutableMap()
            mergedMap.putAll(compatibilityMap)
            val updatedCards = currentState.cards.map { card ->
                card.copy(
                    compatibilityStatus = mergedMap[card.name] ?: card.compatibilityStatus,
                )
            }
            Timber.tag("LibraryViewModel").d("Updated state with ${compatibilityMap.size} compatibility entries, total: ${mergedMap.size}")
            currentState.copy(
                cards = updatedCards,
                compatibilityMap = mergedMap,
            )
        }
    }

    private fun compatibilityStatusFor(
        response: GameCompatibilityService.GameCompatibilityResponse,
    ): GameCompatibilityStatus {
        return when {
            response.isNotWorking -> GameCompatibilityStatus.NOT_COMPATIBLE
            !response.hasBeenTried -> GameCompatibilityStatus.UNKNOWN
            response.gpuPlayableCount > 0 -> GameCompatibilityStatus.GPU_COMPATIBLE
            response.totalPlayableCount > 0 -> GameCompatibilityStatus.COMPATIBLE
            else -> GameCompatibilityStatus.UNKNOWN
        }
    }
}
