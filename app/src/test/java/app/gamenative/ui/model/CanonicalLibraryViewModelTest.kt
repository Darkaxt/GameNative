package app.gamenative.ui.model

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.GameCompatibilityStatus
import app.gamenative.data.GameSource
import app.gamenative.data.GOGGame
import app.gamenative.data.HeroResponse
import app.gamenative.data.LibraryItem
import app.gamenative.data.RecommendationRepository
import app.gamenative.data.SteamApp
import app.gamenative.data.SteamCollection
import app.gamenative.data.SteamCollectionRepository
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.diagnostics.DiagnosticArea
import app.gamenative.diagnostics.DiagnosticAttribute
import app.gamenative.diagnostics.DiagnosticEventName
import app.gamenative.diagnostics.DiagnosticOutcome
import app.gamenative.diagnostics.FeatureDiagnostics
import app.gamenative.events.AndroidEvent
import app.gamenative.library.canonical.CanonicalCardKey
import app.gamenative.library.canonical.CanonicalLibraryCard
import app.gamenative.library.canonical.CanonicalGuardedMutationResult
import app.gamenative.library.canonical.CanonicalLibraryRepository
import app.gamenative.library.canonical.CanonicalMutationRepository
import app.gamenative.library.canonical.CanonicalProjectionClock
import app.gamenative.library.canonical.CanonicalProjectionReadiness
import app.gamenative.library.canonical.CanonicalPublicFailure
import app.gamenative.library.canonical.CanonicalPublicLibraryGate
import app.gamenative.library.canonical.CopyUnavailableReason
import app.gamenative.library.canonical.OwnedCopyOperation
import app.gamenative.library.canonical.OwnedCopySummary
import app.gamenative.library.canonical.PreferredCopyRepository
import app.gamenative.library.canonical.PrefManagerCanonicalPublicLibraryGate
import app.gamenative.library.canonical.action.ActionFailureReason
import app.gamenative.library.canonical.action.ActionSelectionPolicy
import app.gamenative.library.canonical.action.OwnedCopyActionGuard
import app.gamenative.library.canonical.action.OwnedCopyActionRouter
import app.gamenative.library.canonical.action.OwnedCopyRouteResult
import app.gamenative.library.canonical.runtime.OwnedCopyRuntime
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeRegistry
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeResult
import app.gamenative.library.canonical.source.OwnedCopyProjection
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import app.gamenative.ui.data.GameCardStats
import app.gamenative.ui.data.LibraryCardIdentity
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.enums.AppFilter
import app.gamenative.ui.enums.LibraryTab
import app.gamenative.ui.enums.SortOption
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.CustomGameScanner
import app.gamenative.utils.DeviceGameStatsCache
import app.gamenative.utils.DeviceGameStatsService.DeviceGameStats
import app.gamenative.utils.GameCompatibilityCache
import app.gamenative.utils.GameCompatibilityService
import app.gamenative.utils.GpuGameStatsCache
import io.mockk.clearAllMocks
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.unmockkObject
import io.mockk.verify
import java.util.EnumSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CanonicalLibraryViewModelTest {

    private lateinit var context: Context
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var dispatcher: TestDispatcher
    private lateinit var viewModelStore: ViewModelStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PrefManager.init(context)
        if (PrefManager.canonicalPublicLibraryEnabled) {
            PrefManager.canonicalPublicLibraryEnabled = false
        }
        if (!PrefManager.showSteamInLibrary) PrefManager.showSteamInLibrary = true
        if (!PrefManager.showCustomGamesInLibrary) PrefManager.showCustomGamesInLibrary = true
        if (!PrefManager.showGOGInLibrary) PrefManager.showGOGInLibrary = true
        if (!PrefManager.showEpicInLibrary) PrefManager.showEpicInLibrary = true
        if (!PrefManager.showAmazonInLibrary) PrefManager.showAmazonInLibrary = true
        val defaultFilters = filters(AppFilter.GAME)
        if (PrefManager.libraryFilter != defaultFilters) PrefManager.libraryFilter = defaultFilters
        if (PrefManager.librarySortOption != SortOption.INSTALLED_FIRST) {
            PrefManager.librarySortOption = SortOption.INSTALLED_FIRST
        }
        if (PrefManager.librarySteamCollections.isNotEmpty()) PrefManager.librarySteamCollections = emptySet()
        awaitPreference {
            !PrefManager.canonicalPublicLibraryEnabled &&
                PrefManager.showSteamInLibrary &&
                PrefManager.showCustomGamesInLibrary &&
                PrefManager.showGOGInLibrary &&
                PrefManager.showEpicInLibrary &&
                PrefManager.showAmazonInLibrary &&
                PrefManager.libraryFilter == defaultFilters &&
                PrefManager.librarySortOption == SortOption.INSTALLED_FIRST &&
                PrefManager.librarySteamCollections.isEmpty()
        }
        scheduler = TestCoroutineScheduler()
        dispatcher = StandardTestDispatcher(scheduler)
        Dispatchers.setMain(dispatcher)
        viewModelStore = ViewModelStore()
        PluviaApp.events.clearAllListeners()

        mockkObject(DownloadService)
        every { DownloadService.getDownloadDirectoryApps() } returns mutableListOf()
        mockkObject(SteamService.Companion)
        every { SteamService.getImportedAppDirs() } returns emptyList()
        every { SteamService.familyMembers } returns emptyList()
        every { SteamService.userSteamId } returns null
        mockkObject(GOGService.Companion)
        every { GOGService.hasStoredCredentials(any()) } returns false
        mockkObject(EpicService.Companion)
        every { EpicService.hasStoredCredentials(any()) } returns false
        mockkObject(AmazonService.Companion)
        every { AmazonService.hasStoredCredentials(any()) } returns false
        mockkObject(CustomGameScanner)
        every { CustomGameScanner.scanAsLibraryItems(any(), any(), any()) } returns emptyList()
        mockkObject(RecommendationRepository)
        coEvery { RecommendationRepository.getHero(any()) } returns HeroResponse()
        mockkObject(DeviceGameStatsCache)
        coEvery { DeviceGameStatsCache.clear() } just runs
        coEvery { DeviceGameStatsCache.refreshIfStale(any(), any(), any()) } returns true
        every { DeviceGameStatsCache.getAll() } returns emptyMap()
        mockkObject(GpuGameStatsCache)
        coEvery { GpuGameStatsCache.clear() } just runs
        coEvery { GpuGameStatsCache.refreshIfStale(any(), any()) } returns true
        every { GpuGameStatsCache.getAll() } returns emptyMap()
        mockkObject(GameCompatibilityCache)
        coEvery { GameCompatibilityCache.clear() } just runs
        every { GameCompatibilityCache.getCached(any()) } returns null
        every { GameCompatibilityCache.captureGeneration() } returns 0L
        coEvery { GameCompatibilityCache.cacheAllIfCurrent(any(), any()) } returns true
    }

    @After
    fun tearDown() {
        if (::viewModelStore.isInitialized) viewModelStore.clear()
        PluviaApp.events.clearAllListeners()
        clearAllMocks()
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun `public gate requires projection and public preferences independently`() {
        val gate = PrefManagerCanonicalPublicLibraryGate()

        PrefManager.canonicalProjectionEnabled = false
        PrefManager.canonicalPublicLibraryEnabled = true
        awaitPreference { !PrefManager.canonicalProjectionEnabled && PrefManager.canonicalPublicLibraryEnabled }
        assertFalse(gate.isEnabled())

        PrefManager.canonicalProjectionEnabled = true
        PrefManager.canonicalPublicLibraryEnabled = false
        awaitPreference { PrefManager.canonicalProjectionEnabled && !PrefManager.canonicalPublicLibraryEnabled }
        assertFalse(gate.isEnabled())

        PrefManager.canonicalPublicLibraryEnabled = true
        awaitPreference { PrefManager.canonicalProjectionEnabled && PrefManager.canonicalPublicLibraryEnabled }
        assertTrue(gate.isEnabled())
    }

    @Test
    fun `debug setting explains prerequisite restart and source-native recovery`() {
        assertEquals("Experimental canonical library", context.getString(R.string.settings_debug_canonical_library_title))
        assertEquals(
            "Enable + restart GameNative to test canonical cards/actions. Disable + restart GameNative to restore source-native cards/actions.",
            context.getString(R.string.settings_debug_canonical_library_subtitle),
        )
    }

    @Test
    fun `gate off keeps realistic legacy source card and never collects canonical repository`() = runTest(dispatcher) {
        PrefManager.showSteamInLibrary = false
        PrefManager.showCustomGamesInLibrary = false
        PrefManager.showGOGInLibrary = true
        PrefManager.showEpicInLibrary = false
        PrefManager.showAmazonInLibrary = false
        awaitPreference {
            !PrefManager.showSteamInLibrary &&
                !PrefManager.showCustomGamesInLibrary &&
                PrefManager.showGOGInLibrary &&
                !PrefManager.showEpicInLibrary &&
                !PrefManager.showAmazonInLibrary
        }
        every { GOGService.hasStoredCredentials(any()) } returns true
        val gogRows = MutableStateFlow(
            listOf(
                GOGGame(
                    id = "42",
                    title = "Exact Legacy",
                    isInstalled = true,
                    imageUrl = "legacy-wide",
                    iconUrl = "legacy-icon",
                    verticalCoverUrl = "legacy-cover",
                ),
            ),
        )
        val repository = mockk<CanonicalLibraryRepository>()
        every { repository.observeCards() } throws AssertionError("canonical repository must stay cold")
        val readiness = CanonicalProjectionReadiness().apply { markSucceeded() }

        viewModel(
            repository = repository,
            gateEnabled = false,
            readiness = readiness,
            gogRows = gogRows,
        )
        runCurrent()
        awaitState { it.cards.any { card -> card.name == "Exact Legacy" } }

        val card = stateSnapshot.cards.single { it.name == "Exact Legacy" }
        assertTrue(card.identity is LibraryCardIdentity.SourceCopy)
        assertEquals("GOG_42", card.sourceItemOrNull()?.appId)
        assertEquals(GameSource.GOG, card.sourceItemOrNull()?.gameSource)
        assertEquals("legacy-cover", card.capsuleImageUrl)
        assertTrue(stateSnapshot.cards.all {
            it.identity is LibraryCardIdentity.SourceCopy || it.identity is LibraryCardIdentity.Promotion
        })
        verify(exactly = 0) { repository.observeCards() }
        viewModelStore.clear()
    }

    @Test
    fun `gate on waits for readiness then wakes from readiness alone`() = runTest(dispatcher) {
        val repository = repository(MutableStateFlow(listOf(card(name = "Ready Card"))))
        val readiness = CanonicalProjectionReadiness()
        val vm = viewModel(repository, gateEnabled = true, readiness = readiness)
        runCurrent()

        assertEquals(CanonicalPublicFailure.MISSING_PROJECTION_PREREQUISITE, vm.state.value.canonicalPublicFailure)
        verify(exactly = 0) { repository.observeCards() }

        readiness.markSucceeded()
        runCurrent()

        assertEquals(listOf("Ready Card"), vm.state.value.cards.map { it.name })
        assertNull(vm.state.value.canonicalPublicFailure)
        verify(exactly = 1) { repository.observeCards() }
        viewModelStore.clear()
    }

    @Test
    fun `canonical flow failure falls back retries with capped deterministic backoff and recovers`() = runTest(dispatcher) {
        var attempts = 0
        val repository = mockk<CanonicalLibraryRepository>()
        every { repository.observeCards() } answers {
            attempts += 1
            if (attempts <= 8) {
                flow { throw IllegalStateException("private assembly failure") }
            } else {
                MutableStateFlow(listOf(card(name = "Recovered")))
            }
        }
        val readiness = CanonicalProjectionReadiness().apply { markSucceeded() }
        val vm = viewModel(repository, gateEnabled = true, readiness = readiness)
        runCurrent()

        assertEquals(1, attempts)
        assertEquals(CanonicalPublicFailure.ASSEMBLY_FAILED, vm.state.value.canonicalPublicFailure)
        val retryDelays = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L)
        retryDelays.forEachIndexed { index, retryDelay ->
            advanceTimeBy(retryDelay - 1)
            runCurrent()
            assertEquals(index + 1, attempts)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(index + 2, attempts)
        }

        assertEquals(9, attempts)
        assertEquals(listOf("Recovered"), vm.state.value.cards.map { it.name })
        assertNull(vm.state.value.canonicalPublicFailure)
        viewModelStore.clear()
    }

    @Test
    fun `authoritative canonical and legacy failure clears prior account cards and recovers`() = runTest(dispatcher) {
        val priorCard = card(name = "Prior Account Title")
        val recoveredCard = card(
            canonicalId = canonicalId(2),
            name = "Current Account Title",
            copyKeys = listOf(key(GameSource.STEAM, "current-account")),
        )
        val priorDeviceStats = mapOf(
            GameSource.STEAM to mapOf("Prior Account Native Title" to stats(1, 30, 1, 60)),
        )
        val priorGpuStats = mapOf(
            GameSource.STEAM to mapOf("Prior Account Native Title" to stats(2, 40, 2, 120)),
        )
        every { DeviceGameStatsCache.getAll() } returns priorDeviceStats
        every { GpuGameStatsCache.getAll() } returns priorGpuStats
        val emissions = MutableStateFlow(listOf(priorCard))
        val vm = viewModel(
            repository = repository(emissions),
            gateEnabled = true,
            readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
        )
        runCurrent()
        assertEquals(listOf("Prior Account Title"), vm.state.value.cards.map { it.name })
        assertEquals(1, vm.state.value.totalAppsInFilter)
        assertEquals(1, vm.state.value.allCount)
        assertEquals(priorDeviceStats, vm.state.value.deviceGameStats)
        assertEquals(priorGpuStats, vm.state.value.gpuGameStats)

        every { DownloadService.getDownloadDirectoryApps() } throws
            IllegalStateException("private fallback failure")
        emissions.value = listOf(priorCard, priorCard.copy(displayName = "Invalid Duplicate"))
        runCurrent()

        with(vm.state.value) {
            assertTrue("prior account title survived dual publication failure", cards.isEmpty())
            assertEquals(0, totalAppsInFilter)
            assertEquals(1, currentPaginationPage)
            assertEquals(1, lastPaginationPage)
            assertEquals(0, allCount)
            assertEquals(0, steamCount)
            assertEquals(0, gogCount)
            assertEquals(0, epicCount)
            assertEquals(0, amazonCount)
            assertEquals(0, localCount)
            assertTrue(steamCollectionCounts.isEmpty())
            assertTrue(compatibilityMap.isEmpty())
            assertTrue(deviceGameStats.isEmpty())
            assertTrue(gpuGameStats.isEmpty())
            assertFalse(isLoading)
            assertEquals(CanonicalPublicFailure.INVALID_CARD_STATE, canonicalPublicFailure)
        }

        every { DownloadService.getDownloadDirectoryApps() } returns mutableListOf()
        emissions.value = listOf(recoveredCard)
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(listOf("Current Account Title"), vm.state.value.cards.map { it.name })
        assertNull(vm.state.value.canonicalPublicFailure)
        assertFalse(vm.state.value.isLoading)
        viewModelStore.clear()
    }

    @Test
    fun `post-validation projection failures back off before becoming healthy`() = runTest(dispatcher) {
        val collectionAttempts = AtomicInteger(0)
        val projectionAttempts = AtomicInteger(0)
        val repository = mockk<CanonicalLibraryRepository>()
        every { repository.observeCards() } answers {
            collectionAttempts.incrementAndGet()
            MutableStateFlow(listOf(card(name = "Projection Recovery")))
        }
        every { GameCompatibilityCache.getCached(any()) } answers {
            if (projectionAttempts.incrementAndGet() <= 2) {
                throw IllegalStateException("fixed projection failure")
            }
            null
        }
        val vm = viewModel(
            repository = repository,
            gateEnabled = true,
            readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
        )
        runCurrent()

        assertEquals(1, collectionAttempts.get())
        assertEquals(CanonicalPublicFailure.ASSEMBLY_FAILED, vm.state.value.canonicalPublicFailure)
        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, collectionAttempts.get())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, collectionAttempts.get())
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(2, collectionAttempts.get())
        advanceTimeBy(1)
        runCurrent()

        assertEquals(3, collectionAttempts.get())
        assertEquals(listOf("Projection Recovery"), vm.state.value.cards.map { it.name })
        assertNull(vm.state.value.canonicalPublicFailure)
        viewModelStore.clear()
    }

    @Test
    fun `canonical parent cancellation does not restart collection`() = runTest(dispatcher) {
        val repository = mockk<CanonicalLibraryRepository>()
        every { repository.observeCards() } returns flow { awaitCancellation() }
        val readiness = CanonicalProjectionReadiness().apply { markSucceeded() }
        val vm = viewModel(repository, gateEnabled = true, readiness = readiness)
        runCurrent()

        assertNull(vm.state.value.canonicalPublicFailure)
        verify(exactly = 1) { repository.observeCards() }
        viewModelStore.clear()
        advanceTimeBy(60_000)
        runCurrent()

        verify(exactly = 1) { repository.observeCards() }
        assertNull(vm.state.value.canonicalPublicFailure)
    }

    @Test
    fun `invalid canonical variants are typed and never partially published`() = runTest(dispatcher) {
        val valid = card(name = "Valid")
        val invalidLists = listOf(
            listOf(valid, valid.copy(displayName = "Duplicate")),
            listOf(valid.copy(copies = emptyList(), ownedSources = emptySet())),
            listOf(valid.copy(ownedSources = setOf(GameSource.GOG))),
            listOf(
                valid.copy(
                    key = CanonicalCardKey.Grouped(otherCanonicalId),
                ),
            ),
            listOf(
                valid.copy(
                    key = CanonicalCardKey.Independent(gogKey),
                    copies = listOf(copy(steamKey, "Wrong independent copy")),
                    ownedSources = setOf(GameSource.STEAM),
                ),
            ),
        )
        invalidLists.forEach { cards ->
            assertEquals(CanonicalPublicFailure.INVALID_CARD_STATE, CanonicalLibraryCardValidator.failureOrNull(cards))
        }

        val emissions = MutableStateFlow(invalidLists.first())
        val vm = viewModel(
            repository = repository(emissions),
            gateEnabled = true,
            readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
        )
        runCurrent()
        assertEquals(CanonicalPublicFailure.INVALID_CARD_STATE, vm.state.value.canonicalPublicFailure)
        assertTrue(vm.state.value.cards.none { it.name == "Duplicate" })
        viewModelStore.clear()
    }

    @Test
    fun `cross-card copy trust and preferred invariants reject the whole canonical list`() = runTest(dispatcher) {
        val first = card(canonicalId = canonicalId(1), name = "First", copyKeys = listOf(steamKey))
        val crossCardDuplicate = listOf(
            first,
            card(canonicalId = canonicalId(2), name = "Second", copyKeys = listOf(steamKey)),
        )
        val groupedUntrusted = listOf(
            MatchConfidence.REVIEW_REQUIRED,
            MatchConfidence.REJECTED,
            MatchConfidence.UNMATCHED,
        ).map { confidence ->
            listOf(
                first.copy(
                    copies = first.copies.map { it.copy(confidence = confidence) },
                ),
            )
        }
        val independentTrusted = listOf(
            first.copy(key = CanonicalCardKey.Independent(steamKey)),
        )
        val independentUntrusted = listOf(
            first.copy(
                key = CanonicalCardKey.Independent(steamKey),
                copies = first.copies.map { it.copy(confidence = MatchConfidence.REVIEW_REQUIRED) },
            ),
        )
        val nonmemberPreferred = listOf(
            first.copy(preferredCopy = gogKey),
        )

        (listOf(crossCardDuplicate, independentTrusted, nonmemberPreferred) + groupedUntrusted).forEach { cards ->
            assertEquals(CanonicalPublicFailure.INVALID_CARD_STATE, CanonicalLibraryCardValidator.failureOrNull(cards))
        }
        assertNull(CanonicalLibraryCardValidator.failureOrNull(independentUntrusted))

        val vm = viewModel(
            repository = repository(MutableStateFlow(crossCardDuplicate)),
            gateEnabled = true,
            readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
        )
        runCurrent()

        assertEquals(CanonicalPublicFailure.INVALID_CARD_STATE, vm.state.value.canonicalPublicFailure)
        assertTrue(vm.state.value.cards.none { it.name == "First" || it.name == "Second" })
        viewModelStore.clear()
    }

    @Test
    fun `expired and recommended contexts use typed legacy recovery`() {
        val expired = canonicalState(filters = filters(AppFilter.GAME, AppFilter.EXPIRED))
        val recommended = canonicalState(tab = LibraryTab.RECOMMENDED)

        assertEquals(CanonicalPublicFailure.UNSUPPORTED_LEGACY_CONTEXT, canonicalUnsupportedFailure(expired))
        assertEquals(CanonicalPublicFailure.UNSUPPORTED_LEGACY_CONTEXT, canonicalUnsupportedFailure(recommended))
        assertNull(canonicalUnsupportedFailure(canonicalState()))
    }

    @Test
    fun `empty valid canonical list stays empty without fallback`() = runTest(dispatcher) {
        val vm = viewModel(
            repository = repository(MutableStateFlow(emptyList())),
            gateEnabled = true,
            readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
        )
        runCurrent()

        assertTrue(vm.state.value.cards.isEmpty())
        assertEquals(0, vm.state.value.totalAppsInFilter)
        assertNull(vm.state.value.canonicalPublicFailure)
        viewModelStore.clear()
    }

    @Test
    fun `app types are exact and unknown types are never guessed`() {
        val cards = CanonicalAppType.entries.mapIndexed { index, type ->
            card(
                canonicalId = canonicalId(index + 1),
                name = type.name,
                appType = type,
                copyKeys = listOf(key(GameSource.STEAM, (index + 10).toString())),
            )
        }

        val selected = mapOf(
            AppFilter.GAME to CanonicalAppType.GAME,
            AppFilter.APPLICATION to CanonicalAppType.APPLICATION,
            AppFilter.TOOL to CanonicalAppType.TOOL,
            AppFilter.DEMO to CanonicalAppType.DEMO,
        )
        selected.forEach { (filter, expected) ->
            val page = project(cards, canonicalState(filters = filters(filter)))
            assertEquals(listOf(expected.name), page.cards.map { it.name })
        }
        val allModeled = project(
            cards,
            canonicalState(filters = filters(AppFilter.GAME, AppFilter.APPLICATION, AppFilter.TOOL, AppFilter.DEMO)),
        )
        assertEquals(
            setOf("GAME", "APPLICATION", "TOOL", "DEMO"),
            allModeled.cards.mapTo(linkedSetOf()) { it.name },
        )
        assertTrue(allModeled.cards.none { it.name in setOf("DLC", "SOUNDTRACK", "UNKNOWN") })
    }

    @Test
    fun `search matches canonical title and safe aliases`() {
        val card = card(name = "Canonical Name", aliases = setOf("Source Alias", "Áccented Alias"))

        assertEquals(listOf("Canonical Name"), project(listOf(card), canonicalState(search = "canonical")).cards.map { it.name })
        assertEquals(listOf("Canonical Name"), project(listOf(card), canonicalState(search = "source alias")).cards.map { it.name })
        assertEquals(listOf("Canonical Name"), project(listOf(card), canonicalState(search = "Accented")).cards.map { it.name })
        assertTrue(project(listOf(card), canonicalState(search = "private missing")).cards.isEmpty())
    }

    @Test
    fun `installed and shared admission use current copy summaries only`() {
        val installed = card(
            canonicalId = canonicalId(1),
            name = "Installed",
            copyKeys = listOf(steamKey, gogKey),
            installedKeys = setOf(gogKey),
        )
        val sharedKey = steamKey.copy(stableSourceId = "20")
        val shared = card(
            canonicalId = canonicalId(2),
            name = "Shared",
            copyKeys = listOf(sharedKey),
            sharedKeys = setOf(sharedKey),
        ).copy(isShared = false)
        val lyingNonSteam = card(
            canonicalId = canonicalId(3),
            name = "Not Shared",
            copyKeys = listOf(gogKey.copy(stableSourceId = "31")),
        ).copy(isShared = true)

        assertEquals(
            listOf("Installed"),
            project(listOf(installed, shared), canonicalState(filters = filters(AppFilter.GAME, AppFilter.INSTALLED))).cards.map { it.name },
        )
        assertEquals(
            listOf("Not Shared"),
            project(listOf(shared, lyingNonSteam), canonicalState()).cards.map { it.name },
        )
        assertEquals(
            listOf("Not Shared", "Shared"),
            project(listOf(shared, lyingNonSteam), canonicalState(filters = filters(AppFilter.GAME, AppFilter.SHARED))).cards.map { it.name },
        )
        assertFalse(
            project(listOf(lyingNonSteam), canonicalState()).cards.single().isShared,
        )
    }

    @Test
    fun `mixed-source shared aggregate is excluded from cards tabs and pre-page counts`() {
        val mixedSteam = key(GameSource.STEAM, "shared-steam")
        val mixedGog = key(GameSource.GOG, "owned-gog")
        val mixed = card(
            canonicalId = canonicalId(41),
            name = "Mixed Shared",
            copyKeys = listOf(mixedSteam, mixedGog),
            sharedKeys = setOf(mixedSteam),
        )
        val ownedSteam = card(
            canonicalId = canonicalId(42),
            name = "Owned",
            copyKeys = listOf(key(GameSource.STEAM, "owned-steam")),
        )

        val all = project(listOf(mixed, ownedSteam), canonicalState())
        val steam = project(listOf(mixed, ownedSteam), canonicalState(tab = LibraryTab.STEAM))
        val gog = project(listOf(mixed, ownedSteam), canonicalState(tab = LibraryTab.GOG))

        assertEquals(listOf("Owned"), all.cards.map { it.name })
        assertEquals(listOf("Owned"), steam.cards.map { it.name })
        assertTrue(gog.cards.isEmpty())
        assertEquals(1, all.allCount)
        assertEquals(1, all.sourceCounts.getValue(GameSource.STEAM))
        assertEquals(0, all.sourceCounts.getValue(GameSource.GOG))

        val includingShared = project(
            listOf(mixed, ownedSteam),
            canonicalState(filters = filters(AppFilter.GAME, AppFilter.SHARED)),
        )
        assertEquals(listOf("Mixed Shared", "Owned"), includingShared.cards.map { it.name })
        assertTrue(includingShared.cards.single { it.name == "Mixed Shared" }.isShared)
    }

    @Test
    fun `source tabs and counts admit grouped card once while retaining all badges`() {
        val grouped = card(
            name = "Grouped",
            copyKeys = listOf(steamKey, gogKey),
        )
        val steamOnly = card(
            canonicalId = canonicalId(2),
            name = "Steam only",
            copyKeys = listOf(steamKey.copy(stableSourceId = "22")),
        )
        val all = project(listOf(grouped, steamOnly), canonicalState(), pageSize = 1)
        val steam = project(listOf(grouped, steamOnly), canonicalState(tab = LibraryTab.STEAM), pageSize = 50)
        val gog = project(listOf(grouped, steamOnly), canonicalState(tab = LibraryTab.GOG), pageSize = 50)

        assertEquals(2, all.totalCount)
        assertEquals(2, all.sourceCounts.getValue(GameSource.STEAM))
        assertEquals(1, all.sourceCounts.getValue(GameSource.GOG))
        assertEquals(2, all.allCount)
        assertEquals(listOf("Grouped", "Steam only"), steam.cards.map { it.name })
        assertEquals(listOf("Grouped"), gog.cards.map { it.name })
        assertEquals(setOf(GameSource.STEAM, GameSource.GOG), gog.cards.single().ownedSources)
    }

    @Test
    fun `All admission uses enabled source preferences without stripping ownership badges`() {
        val grouped = card(name = "Grouped", copyKeys = listOf(steamKey, gogKey))
        val disabled = canonicalState(showSteam = false, showGog = true)
        val admitted = project(listOf(grouped), disabled)
        assertEquals(listOf("Grouped"), admitted.cards.map { it.name })
        assertEquals(setOf(GameSource.STEAM, GameSource.GOG), admitted.cards.single().ownedSources)

        val noneEnabled = disabled.copy(showGOGInLibrary = false)
        assertTrue(project(listOf(grouped), noneEnabled).cards.isEmpty())
    }

    @Test
    fun `recently played uses maximum copy and size uses preferred then sole installed then unknown last`() {
        val preferred = key(GameSource.STEAM, "100")
        val secondary = key(GameSource.GOG, "101")
        val preferredSize = card(
            canonicalId = canonicalId(1),
            name = "Preferred",
            copyKeys = listOf(preferred, secondary),
            installedKeys = setOf(preferred, secondary),
            sizes = mapOf(preferred to 500L, secondary to 2_000L),
            lastPlayed = mapOf(preferred to 10L, secondary to 900L),
            preferred = preferred,
        )
        val soleInstalledKey = key(GameSource.EPIC, "102")
        val soleInstalled = card(
            canonicalId = canonicalId(2),
            name = "Sole",
            copyKeys = listOf(soleInstalledKey, key(GameSource.AMAZON, "103")),
            installedKeys = setOf(soleInstalledKey),
            sizes = mapOf(soleInstalledKey to 1_000L),
            lastPlayed = mapOf(soleInstalledKey to 800L),
        )
        val unknown = card(
            canonicalId = canonicalId(3),
            name = "Unknown",
            copyKeys = listOf(key(GameSource.CUSTOM_GAME, "104")),
        )

        val recent = project(
            listOf(soleInstalled, preferredSize),
            canonicalState(sort = SortOption.RECENTLY_PLAYED),
        )
        assertEquals(listOf("Preferred", "Sole"), recent.cards.map { it.name })

        val smallest = project(
            listOf(unknown, soleInstalled, preferredSize),
            canonicalState(sort = SortOption.SIZE_SMALLEST),
        )
        assertEquals(listOf("Preferred", "Sole", "Unknown"), smallest.cards.map { it.name })
        assertEquals(listOf(500L, 1_000L, 0L), smallest.cards.map { it.sizeBytes })

        val largest = project(
            listOf(unknown, soleInstalled, preferredSize),
            canonicalState(sort = SortOption.SIZE_LARGEST),
        )
        assertEquals(listOf("Sole", "Preferred", "Unknown"), largest.cards.map { it.name })
    }

    @Test
    fun `Steam collections match grouped cards through any Steam copy AppID`() {
        val grouped = card(
            name = "Collected",
            copyKeys = listOf(key(GameSource.STEAM, "570"), gogKey),
            steamCollectionAppIds = setOf(570),
        )
        val nonMatch = card(
            canonicalId = canonicalId(2),
            name = "Other",
            copyKeys = listOf(key(GameSource.STEAM, "730")),
            steamCollectionAppIds = setOf(730),
        )
        val collection = SteamCollection("favorites", "Favorites", setOf(570))
        val state = canonicalState(
            selectedCollections = setOf("favorites"),
            collections = listOf(collection),
        )

        val page = project(listOf(grouped, nonMatch), state)
        assertEquals(listOf("Collected"), page.cards.map { it.name })
        assertEquals(mapOf("favorites" to 1), page.steamCollectionCounts)
    }

    @Test
    fun `compatibility resolves display name first then deterministic cached aliases and requests display names only`() {
        val lookups = mutableListOf<String>()
        val card = card(
            name = "Canonical",
            aliases = linkedSetOf("z alias", "A alias", "Canonical"),
        )
        val status = CanonicalCompatibilityLookup.resolve(
            card = card,
            cachedStatus = { name ->
                lookups += name
                if (name == "A alias") GameCompatibilityStatus.COMPATIBLE else null
            },
        )
        assertEquals(GameCompatibilityStatus.COMPATIBLE, status)
        assertEquals(listOf("Canonical", "A alias"), lookups)

        val page = project(
            cards = listOf(card),
            state = canonicalState(filters = filters(AppFilter.GAME, AppFilter.COMPATIBLE)),
            compatibility = { status },
        )
        assertEquals(listOf("Canonical"), page.compatibilityRequestNames)
        assertEquals(GameCompatibilityStatus.COMPATIBLE, page.cards.single().compatibilityStatus)
    }

    @Test
    fun `cleared compatibility cache is authoritative over stale state and refetches display name`() {
        val canonical = card(
            name = "Cleared Canonical",
            aliases = linkedSetOf("Cached Alias", "Cleared Canonical"),
        )
        val staleState = canonicalState(
            filters = filters(AppFilter.GAME, AppFilter.COMPATIBLE),
        ).copy(
            compatibilityMap = mapOf(
                "Cleared Canonical" to GameCompatibilityStatus.NOT_COMPATIBLE,
                "Cached Alias" to GameCompatibilityStatus.UNKNOWN,
            ),
        )
        val cacheLookups = mutableListOf<String>()

        val status = CanonicalCompatibilityLookup.resolve(
            card = canonical,
            cachedStatus = { name -> cacheLookups += name; null },
        )
        val page = project(
            cards = listOf(canonical),
            state = staleState,
            compatibility = { status },
        )

        assertNull(status)
        assertEquals(listOf("Cleared Canonical", "Cached Alias"), cacheLookups)
        assertEquals(listOf("Cleared Canonical"), page.cards.map { it.name })
        assertEquals(listOf("Cleared Canonical"), page.compatibilityRequestNames)
    }

    @Test
    fun `stats aggregate each source native tuple component-wise and drive filters sorts and cards`() {
        val steam = key(GameSource.STEAM, "201")
        val gog = key(GameSource.GOG, "202")
        val aggregate = card(
            canonicalId = canonicalId(1),
            name = "Aggregate",
            copyKeys = listOf(steam, gog),
            nativeTitles = mapOf(steam to "Steam Native", gog to "GOG Native"),
        )
        val lower = card(
            canonicalId = canonicalId(2),
            name = "Lower",
            copyKeys = listOf(key(GameSource.EPIC, "203")),
            nativeTitles = mapOf(key(GameSource.EPIC, "203") to "Epic Native"),
        )
        val state = canonicalState(
            filters = filters(AppFilter.GAME, AppFilter.PLAYABLE, AppFilter.FIVE_STAR, AppFilter.FIVE_STAR_GPU, AppFilter.PROVEN_GPU),
            sort = SortOption.FPS_HIGH,
            deviceStats = mapOf(
                GameSource.STEAM to mapOf("Steam Native" to stats(3, 35, 2, 100)),
                GameSource.GOG to mapOf("GOG Native" to stats(7, 60, 1, 300)),
                GameSource.EPIC to mapOf("Epic Native" to stats(1, 10, 0, 10)),
            ),
            gpuStats = mapOf(
                GameSource.STEAM to mapOf("Steam Native" to stats(6, 0, 1, 0)),
                GameSource.GOG to mapOf("GOG Native" to stats(9, 0, 4, 0)),
                GameSource.EPIC to mapOf("Epic Native" to stats(1, 0, 0, 0)),
            ),
        )

        val page = project(listOf(lower, aggregate), state)
        assertEquals(listOf("Aggregate"), page.cards.map { it.name })
        assertEquals(
            GameCardStats(
                runsGpu = 9,
                reviewsDevice = 2,
                reviewsGpu = 4,
                fps = 60,
                sessionSec = 300,
            ),
            page.cards.single().gameStats,
        )
    }

    @Test
    fun `grouped card remains intact before incremental pagination and counts are pre-page`() {
        val grouped = card(
            canonicalId = canonicalId(1),
            name = "A Grouped",
            copyKeys = listOf(key(GameSource.STEAM, "301"), key(GameSource.GOG, "302")),
        )
        val second = card(
            canonicalId = canonicalId(2),
            name = "B Second",
            copyKeys = listOf(key(GameSource.STEAM, "303")),
        )
        val pageZero = project(listOf(second, grouped), canonicalState(), pageSize = 1, paginationPage = 0)
        val pageOne = project(listOf(second, grouped), canonicalState(), pageSize = 1, paginationPage = 1)

        assertEquals(2, pageZero.totalCount)
        assertEquals(2, pageZero.sourceCounts.getValue(GameSource.STEAM))
        assertEquals(1, pageZero.sourceCounts.getValue(GameSource.GOG))
        assertEquals(listOf("A Grouped"), pageZero.cards.map { it.name })
        assertEquals(2, pageZero.cards.single().ownedSources.size)
        assertEquals(listOf("A Grouped", "B Second"), pageOne.cards.map { it.name })
    }

    @Test
    fun `promotion prepend keeps unchanged All non-search recommendation rules`() {
        val promotion = LibraryItem(
            index = -1,
            appId = "RECOMMENDED_fixed",
            name = "Promotion",
            isRecommended = true,
            recommendedGameId = "fixed",
            gameSource = GameSource.STEAM,
        )
        val canonical = card(name = "Owned")

        val all = project(listOf(canonical), canonicalState(), promotion = promotion, showRecommendations = true)
        assertTrue(all.cards.first().identity is LibraryCardIdentity.Promotion)
        assertEquals(listOf(-1, 1), all.cards.map { it.index })
        val searching = project(
            listOf(canonical),
            canonicalState(search = "Owned"),
            promotion = promotion,
            showRecommendations = true,
        )
        assertTrue(searching.cards.none { it.identity is LibraryCardIdentity.Promotion })
        val sourceTab = project(
            listOf(canonical),
            canonicalState(tab = LibraryTab.STEAM),
            promotion = promotion,
            showRecommendations = true,
        )
        assertTrue(sourceTab.cards.none { it.identity is LibraryCardIdentity.Promotion })
    }

    @Test
    fun `install and custom image events invalidate by source only without private payload`() = runTest(dispatcher) {
        val registry = mockk<OwnedCopyRuntimeRegistry>(relaxed = true)
        val vm = viewModel(
            repository = repository(MutableStateFlow(emptyList())),
            gateEnabled = false,
            readiness = CanonicalProjectionReadiness(),
            runtimeRegistry = registry,
        )
        runCurrent()
        val before = vm.state.value.imageRefreshCounter

        PluviaApp.events.emit(AndroidEvent.LibraryInstallStatusChanged(8675309, GameSource.EPIC))
        PluviaApp.events.emit(AndroidEvent.CustomGameImagesFetched("private-title/id/path"))
        runCurrent()

        verify(exactly = 1) { registry.notifyVolatileStateChanged(GameSource.EPIC) }
        verify(exactly = 1) { registry.notifyVolatileStateChanged(GameSource.CUSTOM_GAME) }
        assertEquals(before + 1, vm.state.value.imageRefreshCounter)
        viewModelStore.clear()
    }

    @Test
    fun `superseded legacy failure cannot clear canonical activation`() {
        val io = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        val legacyStarted = CountDownLatch(1)
        val releaseLegacy = CountDownLatch(1)
        val blockFirstLegacy = AtomicBoolean(true)
        every { DownloadService.getDownloadDirectoryApps() } answers {
            if (blockFirstLegacy.compareAndSet(true, false)) {
                legacyStarted.countDown()
                releaseLegacy.await(10, TimeUnit.SECONDS)
                throw IllegalStateException("private stale legacy failure")
            }
            mutableListOf()
        }
        every { GOGService.hasStoredCredentials(any()) } returns true
        val readiness = CanonicalProjectionReadiness()
        val canonicalCards = MutableStateFlow(listOf(card(name = "Canonical Winner")))

        try {
            val vm = viewModel(
                repository = repository(canonicalCards),
                gateEnabled = true,
                readiness = readiness,
                gogRows = MutableStateFlow(
                    listOf(GOGGame(id = "slow", title = "Stale Legacy", isInstalled = true)),
                ),
                ioDispatcher = io,
            )
            awaitLatch(legacyStarted, "slow legacy render")

            readiness.markSucceeded()
            awaitState { state -> state.cards.map { it.name } == listOf("Canonical Winner") }

            releaseLegacy.countDown()
            assertFalse(
                "superseded legacy failure cleared or replaced canonical output",
                awaitCondition(timeoutMs = 1_500L) {
                    val names = vm.state.value.cards.map { it.name }
                    names != listOf("Canonical Winner")
                },
            )
            assertEquals(listOf("Canonical Winner"), vm.state.value.cards.map { it.name })
        } finally {
            releaseLegacy.countDown()
            viewModelStore.clear()
            io.close()
        }
    }

    @Test
    fun `rapid query tab and page requests supersede a blocked canonical render`() {
        val io = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        val sourceCards = buildList {
            add(card(canonicalId = canonicalId(60), name = "Alpha", copyKeys = listOf(key(GameSource.STEAM, "alpha"))))
            repeat(105) { index ->
                add(
                    card(
                        canonicalId = canonicalId(index + 100),
                        name = "Beta ${index.toString().padStart(2, '0')}",
                        copyKeys = listOf(key(GameSource.STEAM, "beta-$index")),
                    ),
                )
            }
        }
        val slowStarted = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)
        val blockNextLookup = AtomicBoolean(false)

        try {
            val vm = viewModel(
                repository = repository(MutableStateFlow(sourceCards)),
                gateEnabled = true,
                readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
                ioDispatcher = io,
            )
            awaitState { it.cards.size == 50 && it.cards.any { card -> card.name == "Alpha" } }
            every { GameCompatibilityCache.getCached(any()) } answers {
                if (blockNextLookup.compareAndSet(true, false)) {
                    slowStarted.countDown()
                    releaseSlow.await(10, TimeUnit.SECONDS)
                }
                null
            }

            blockNextLookup.set(true)
            vm.onTabChanged(LibraryTab.ALL)
            awaitLatch(slowStarted, "blocked canonical render")

            vm.onSearchQuery("Beta")
            scheduler.advanceTimeBy(500L)
            scheduler.runCurrent()
            vm.onTabChanged(LibraryTab.STEAM)
            awaitState { state ->
                state.currentTab == LibraryTab.STEAM &&
                    state.searchQuery == "Beta" &&
                    state.cards.size == 50 &&
                    state.cards.all { it.name.startsWith("Beta") }
            }
            vm.onPageChange(1)
            vm.onPageChange(1)
            awaitState { state ->
                state.currentPaginationPage == 3 &&
                    state.cards.size == 105 &&
                    state.cards.all { it.name.startsWith("Beta") }
            }

            releaseSlow.countDown()
            assertFalse(
                "older canonical render replaced the latest query tab or page",
                awaitCondition(timeoutMs = 1_500L) {
                    vm.state.value.currentPaginationPage != 3 ||
                        vm.state.value.cards.any { it.name == "Alpha" }
                },
            )
        } finally {
            releaseSlow.countDown()
            viewModelStore.clear()
            io.close()
        }
    }

    @Test
    fun `slow canonical render and orphan emission cannot clear unsupported recovery`() {
        val io = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        val emissions = MutableStateFlow(listOf(card(name = "Initial Canonical")))
        val slowStarted = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)
        val blockNextLookup = AtomicBoolean(false)

        try {
            val vm = viewModel(
                repository = repository(emissions),
                gateEnabled = true,
                readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
                ioDispatcher = io,
            )
            awaitState { it.cards.map { card -> card.name } == listOf("Initial Canonical") }
            every { GameCompatibilityCache.getCached(any()) } answers {
                if (blockNextLookup.compareAndSet(true, false)) {
                    slowStarted.countDown()
                    releaseSlow.await(10, TimeUnit.SECONDS)
                }
                null
            }

            blockNextLookup.set(true)
            vm.onTabChanged(LibraryTab.STEAM)
            awaitLatch(slowStarted, "slow canonical context render")
            vm.onFilterChanged(AppFilter.EXPIRED)
            awaitState { state ->
                state.canonicalPublicFailure == CanonicalPublicFailure.UNSUPPORTED_LEGACY_CONTEXT &&
                    !state.isLoading
            }

            emissions.value = listOf(card(canonicalId = canonicalId(71), name = "Orphan Canonical"))
            releaseSlow.countDown()
            assertFalse(
                "orphan canonical work cleared unsupported recovery",
                awaitCondition(timeoutMs = 1_500L) {
                    val state = vm.state.value
                    state.canonicalPublicFailure != CanonicalPublicFailure.UNSUPPORTED_LEGACY_CONTEXT ||
                        state.cards.any { it.name == "Orphan Canonical" }
                },
            )
        } finally {
            releaseSlow.countDown()
            viewModelStore.clear()
            io.close()
        }
    }

    @Test
    fun `noncooperative orphan emission cannot cancel unsupported fallback`() {
        val io = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        val validationStarted = CountDownLatch(1)
        val releaseValidation = CountDownLatch(1)
        val orphanHandled = CountDownLatch(1)
        val fallbackStarted = CountDownLatch(1)
        val releaseFallback = CountDownLatch(1)
        val blockFallback = AtomicBoolean(false)
        val orphanCards = object : AbstractList<CanonicalLibraryCard>() {
            override val size: Int = 1

            override fun get(index: Int): CanonicalLibraryCard {
                require(index == 0)
                validationStarted.countDown()
                releaseValidation.await(10, TimeUnit.SECONDS)
                return card(canonicalId = canonicalId(73), name = "Noncooperative Orphan")
            }
        }
        val emissions = object : Flow<List<CanonicalLibraryCard>> {
            override suspend fun collect(collector: FlowCollector<List<CanonicalLibraryCard>>) {
                collector.emit(listOf(card(name = "Initial Canonical")))
                collector.emit(orphanCards)
                orphanHandled.countDown()
            }
        }
        every { DownloadService.getDownloadDirectoryApps() } answers {
            if (blockFallback.get()) {
                fallbackStarted.countDown()
                releaseFallback.await(10, TimeUnit.SECONDS)
            }
            mutableListOf()
        }

        try {
            val vm = viewModel(
                repository = repository(emissions),
                gateEnabled = true,
                readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
                ioDispatcher = io,
            )
            awaitState { it.cards.map { card -> card.name } == listOf("Initial Canonical") }
            awaitLatch(validationStarted, "blocked orphan validation")

            blockFallback.set(true)
            vm.onFilterChanged(AppFilter.EXPIRED)
            awaitLatch(fallbackStarted, "blocked unsupported fallback")
            releaseValidation.countDown()
            awaitLatch(orphanHandled, "noncooperative orphan handling")
            releaseFallback.countDown()

            assertTrue(
                "orphan emission canceled unsupported recovery: ${vm.state.value}",
                awaitCondition(timeoutMs = 3_000L) {
                    val state = vm.state.value
                    state.canonicalPublicFailure == CanonicalPublicFailure.UNSUPPORTED_LEGACY_CONTEXT &&
                        !state.isLoading &&
                        state.cards.none { it.name == "Noncooperative Orphan" }
                },
            )
        } finally {
            releaseValidation.countDown()
            releaseFallback.countDown()
            viewModelStore.clear()
            io.close()
        }
    }

    @Test
    fun `delayed failed generation cannot replace newer canonical success`() {
        val executor = Executors.newFixedThreadPool(8)
        val delegate = executor.asCoroutineDispatcher()
        val holdNextDispatch = AtomicBoolean(false)
        val failedObserverHeld = CountDownLatch(1)
        val releaseFailedObserver = CountDownLatch(1)
        val gatedDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                if (holdNextDispatch.compareAndSet(true, false)) {
                    failedObserverHeld.countDown()
                    releaseFailedObserver.await(10, TimeUnit.SECONDS)
                }
                delegate.dispatch(context, block)
            }
        }
        val failNextProjection = AtomicBoolean(false)
        val emissions = MutableStateFlow(listOf(card(name = "Generation Zero")))

        try {
            val vm = viewModel(
                repository = repository(emissions),
                gateEnabled = true,
                readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
                ioDispatcher = gatedDispatcher,
            )
            awaitState { it.cards.map { card -> card.name } == listOf("Generation Zero") }
            every { GameCompatibilityCache.getCached(any()) } answers {
                if (failNextProjection.compareAndSet(true, false)) {
                    holdNextDispatch.set(true)
                    throw IllegalStateException("fixed projection failure")
                }
                null
            }

            failNextProjection.set(true)
            vm.onTabChanged(LibraryTab.STEAM)
            awaitLatch(failedObserverHeld, "delayed failed generation observer")

            emissions.value = listOf(card(canonicalId = canonicalId(74), name = "Generation One"))
            awaitState { state ->
                state.cards.map { it.name } == listOf("Generation One") &&
                    state.canonicalPublicFailure == null &&
                    !state.isLoading
            }

            releaseFailedObserver.countDown()
            assertFalse(
                "stale failed outcome replaced the newer publication",
                awaitCondition(timeoutMs = 2_000L) {
                    val state = vm.state.value
                    state.canonicalPublicFailure != null || state.cards.map { it.name } != listOf("Generation One")
                },
            )
        } finally {
            releaseFailedObserver.countDown()
            viewModelStore.clear()
            delegate.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `superseded collector render failure cannot replace newer input or start cooldown`() {
        val io = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        val firstProjectionStarted = CountDownLatch(1)
        val releaseFirstProjection = CountDownLatch(1)
        val blockFirstProjection = AtomicBoolean(true)
        val observeCalls = AtomicInteger(0)
        val emissions = MutableStateFlow(
            listOf(
                card(name = "Collector A"),
                card(
                    canonicalId = canonicalId(77),
                    name = "Collector B",
                    copyKeys = listOf(gogKey),
                ),
            ),
        )
        val repository = mockk<CanonicalLibraryRepository>()
        every { repository.observeCards() } answers {
            observeCalls.incrementAndGet()
            emissions
        }
        every { GameCompatibilityCache.getCached(any()) } answers {
            if (blockFirstProjection.compareAndSet(true, false)) {
                firstProjectionStarted.countDown()
                awaitUninterruptibly(releaseFirstProjection)
                throw IllegalStateException("fixed superseded collector projection failure")
            }
            null
        }

        try {
            val vm = viewModel(
                repository = repository,
                gateEnabled = true,
                readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
                ioDispatcher = io,
            )
            awaitLatch(firstProjectionStarted, "blocked collector render")
            vm.onTabChanged(LibraryTab.GOG)
            awaitState { state ->
                state.currentTab == LibraryTab.GOG &&
                    state.cards.map { it.name } == listOf("Collector B") &&
                    state.canonicalPublicFailure == null &&
                    !state.isLoading
            }

            releaseFirstProjection.countDown()
            assertFalse(
                "superseded collector failure replaced the newer input or published fallback",
                awaitCondition(timeoutMs = 750L) {
                    val state = vm.state.value
                    state.currentTab != LibraryTab.GOG ||
                        state.cards.map { it.name } != listOf("Collector B") ||
                        state.canonicalPublicFailure != null ||
                        state.isLoading
                },
            )
            Thread.sleep(700L)
            assertEquals("superseded collector failure started cooldown retry", 1, observeCalls.get())
        } finally {
            releaseFirstProjection.countDown()
            viewModelStore.clear()
            io.close()
        }
    }

    @Test
    fun `delayed waiting request preserves sole initial canonical snapshot`() {
        val io = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        val collectorStarted = CountDownLatch(1)
        val releaseInitialSnapshot = CountDownLatch(1)
        val initialCards = object : Flow<List<CanonicalLibraryCard>> {
            override suspend fun collect(collector: FlowCollector<List<CanonicalLibraryCard>>) {
                collectorStarted.countDown()
                releaseInitialSnapshot.await(10, TimeUnit.SECONDS)
                collector.emit(listOf(card(name = "Sole Initial Snapshot")))
                awaitCancellation()
            }
        }

        try {
            val vm = viewModel(
                repository = repository(initialCards),
                gateEnabled = true,
                readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
                ioDispatcher = io,
            )
            awaitLatch(collectorStarted, "initial canonical collector")
            val delayedState = vm.state.value

            releaseInitialSnapshot.countDown()
            awaitState { state ->
                state.cards.map { it.name } == listOf("Sole Initial Snapshot") && !state.isLoading
            }

            invokeWaitingRequest(vm, delayedState).joinOnCurrentThread()
            assertFalse(
                "delayed waiting request canceled the sole canonical publication",
                awaitCondition(timeoutMs = 1_500L) {
                    val state = vm.state.value
                    state.isLoading || state.cards.map { it.name } != listOf("Sole Initial Snapshot")
                },
            )
        } finally {
            releaseInitialSnapshot.countDown()
            viewModelStore.clear()
            io.close()
        }
    }

    @Test
    fun `older gate-blocked All callback cannot replace newer Steam input`() {
        val io = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        val gateBlocked = CountDownLatch(1)
        val releaseOldGate = CountDownLatch(1)
        val blockNextGate = AtomicBoolean(false)
        val gate = CanonicalPublicLibraryGate {
            if (blockNextGate.compareAndSet(true, false)) {
                gateBlocked.countDown()
                releaseOldGate.await(10, TimeUnit.SECONDS)
            }
            true
        }
        val steam = card(canonicalId = canonicalId(75), name = "Steam Current", copyKeys = listOf(steamKey))
        val gogOnly = card(
            canonicalId = canonicalId(76),
            name = "GOG Stale All",
            copyKeys = listOf(key(GameSource.GOG, "gog-stale")),
        )
        val oldDone = CountDownLatch(1)

        try {
            val vm = viewModel(
                repository = repository(MutableStateFlow(listOf(steam, gogOnly))),
                gateEnabled = true,
                gate = gate,
                readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
                ioDispatcher = io,
            )
            awaitState { it.cards.size == 2 }
            vm.onTabChanged(LibraryTab.STEAM)
            awaitState { it.cards.map { card -> card.name } == listOf("Steam Current") }

            blockNextGate.set(true)
            Thread {
                try {
                    vm.onTabChanged(LibraryTab.ALL)
                } finally {
                    oldDone.countDown()
                }
            }.start()
            awaitLatch(gateBlocked, "old All callback gate")

            vm.onTabChanged(LibraryTab.STEAM)
            awaitState { state ->
                state.currentTab == LibraryTab.STEAM &&
                    state.cards.map { it.name } == listOf("Steam Current")
            }
            releaseOldGate.countDown()
            awaitLatch(oldDone, "old All callback completion")

            assertFalse(
                "older All input became the newest render",
                awaitCondition(timeoutMs = 1_500L) {
                    vm.state.value.cards.any { it.name == "GOG Stale All" }
                },
            )
        } finally {
            releaseOldGate.countDown()
            viewModelStore.clear()
            io.close()
        }
    }

    @Test
    fun `unsupported lifecycle clears account-bound canonical snapshot before restart`() {
        val io = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        val observeCalls = AtomicInteger(0)
        val secondCollectorStarted = CountDownLatch(1)
        val repository = mockk<CanonicalLibraryRepository>()
        every { repository.observeCards() } answers {
            if (observeCalls.incrementAndGet() == 1) {
                MutableStateFlow(listOf(card(name = "Account A Card")))
            } else {
                flow<List<CanonicalLibraryCard>> {
                    secondCollectorStarted.countDown()
                    awaitCancellation()
                }
            }
        }

        try {
            val vm = viewModel(
                repository = repository,
                gateEnabled = true,
                readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
                ioDispatcher = io,
            )
            awaitState { it.cards.map { card -> card.name } == listOf("Account A Card") }

            vm.onTabChanged(LibraryTab.RECOMMENDED)
            awaitState { state ->
                state.canonicalPublicFailure == CanonicalPublicFailure.UNSUPPORTED_LEGACY_CONTEXT &&
                    !state.isLoading
            }
            vm.onTabChanged(LibraryTab.ALL)
            awaitLatch(secondCollectorStarted, "post-account-change collector")

            assertFalse(
                "retained account A snapshot was republished in the new collector epoch",
                awaitCondition(timeoutMs = 1_500L) {
                    vm.state.value.cards.any { it.name == "Account A Card" }
                },
            )
        } finally {
            viewModelStore.clear()
            io.close()
        }
    }

    @Test
    fun `legacy render longer than five seconds completes without timeout`() {
        val io = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        val legacyStarted = CountDownLatch(1)
        val releaseLegacy = CountDownLatch(1)
        val attempts = AtomicInteger(0)
        every { DownloadService.getDownloadDirectoryApps() } answers {
            attempts.incrementAndGet()
            legacyStarted.countDown()
            releaseLegacy.await(15, TimeUnit.SECONDS)
            mutableListOf()
        }
        every { GOGService.hasStoredCredentials(any()) } returns true

        try {
            val vm = viewModel(
                repository = repository(MutableStateFlow(emptyList())),
                gateEnabled = false,
                readiness = CanonicalProjectionReadiness(),
                gogRows = MutableStateFlow(listOf(GOGGame(id = "slow-legacy", title = "Slow Legacy Success", isInstalled = true))),
                ioDispatcher = io,
            )
            awaitLatch(legacyStarted, "long legacy computation")
            Thread.sleep(5_500L)
            assertEquals("long legacy work timed out or overlapped", 1, attempts.get())

            releaseLegacy.countDown()
            assertTrue(
                "long legacy computation did not publish",
                awaitCondition(timeoutMs = 5_000L) {
                    attempts.get() == 1 &&
                        vm.state.value.cards.map { it.name } == listOf("Slow Legacy Success") &&
                        !vm.state.value.isLoading
                },
            )
            assertEquals("long legacy work was retried after the timeout", 1, attempts.get())
        } finally {
            releaseLegacy.countDown()
            viewModelStore.clear()
            io.close()
        }
    }

    @Test
    fun `blocked legacy worker stays single-flight and runs only latest pending input`() {
        val io = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        val firstLegacyStarted = CountDownLatch(1)
        val releaseFirstLegacy = CountDownLatch(1)
        val attempts = AtomicInteger(0)
        val gateEnabled = AtomicBoolean(true)
        val gate = CanonicalPublicLibraryGate { gateEnabled.get() }
        every { DownloadService.getDownloadDirectoryApps() } returns mutableListOf()
        every { GOGService.hasStoredCredentials(any()) } returns true

        try {
            val vm = viewModel(
                repository = repository(MutableStateFlow(emptyList())),
                gateEnabled = true,
                gate = gate,
                readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
                gogRows = MutableStateFlow(listOf(GOGGame(id = "pending-gog", title = "Latest Pending GOG", isInstalled = true))),
                ioDispatcher = io,
            )
            awaitState { !it.isLoading }
            every { DownloadService.getDownloadDirectoryApps() } answers {
                attempts.incrementAndGet()
                mutableListOf()
            }
            val blockFirstCredentialCheck = AtomicBoolean(true)
            every { GOGService.hasStoredCredentials(any()) } answers {
                if (blockFirstCredentialCheck.compareAndSet(true, false)) {
                    firstLegacyStarted.countDown()
                    awaitUninterruptibly(releaseFirstLegacy)
                }
                true
            }

            gateEnabled.set(false)
            vm.onTabChanged(LibraryTab.ALL)
            awaitLatch(firstLegacyStarted, "first blocked legacy worker")
            vm.onTabChanged(LibraryTab.GOG)
            Thread.sleep(500L)
            assertEquals("superseded requests launched overlapping legacy workers", 1, attempts.get())

            releaseFirstLegacy.countDown()
            assertTrue(
                "latest pending legacy input did not run after the physical worker",
                awaitCondition(timeoutMs = 5_000L) {
                    attempts.get() == 2 &&
                        vm.state.value.currentTab == LibraryTab.GOG &&
                        vm.state.value.cards.map { it.name } == listOf("Latest Pending GOG") &&
                        !vm.state.value.isLoading
                },
            )
        } finally {
            releaseFirstLegacy.countDown()
            viewModelStore.clear()
            io.close()
        }
    }

    @Test
    fun `superseded physical legacy failure establishes cooldown before pending work`() {
        val io = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        val gateEnabled = AtomicBoolean(true)
        val gate = CanonicalPublicLibraryGate { gateEnabled.get() }
        val firstLegacyStarted = CountDownLatch(1)
        val releaseFirstLegacy = CountDownLatch(1)
        val attempts = AtomicInteger(0)
        every { GOGService.hasStoredCredentials(any()) } returns true

        try {
            val vm = viewModel(
                repository = repository(MutableStateFlow(listOf(card(name = "Canonical Before Legacy Failure")))),
                gateEnabled = true,
                gate = gate,
                readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
                gogRows = MutableStateFlow(
                    listOf(GOGGame(id = "legacy-cooldown", title = "Pending After Cooldown", isInstalled = true)),
                ),
                ioDispatcher = io,
            )
            awaitState { state ->
                state.cards.map { it.name } == listOf("Canonical Before Legacy Failure") && !state.isLoading
            }
            every { DownloadService.getDownloadDirectoryApps() } answers {
                if (attempts.incrementAndGet() == 1) {
                    firstLegacyStarted.countDown()
                    awaitUninterruptibly(releaseFirstLegacy)
                    throw IllegalStateException("private physical legacy failure")
                }
                mutableListOf()
            }

            gateEnabled.set(false)
            vm.onTabChanged(LibraryTab.ALL)
            awaitLatch(firstLegacyStarted, "physical legacy worker before supersession")
            vm.onTabChanged(LibraryTab.GOG)
            mockkObject(FeatureDiagnostics)
            every { FeatureDiagnostics.record(any(), any(), any(), any(), any()) } just runs
            releaseFirstLegacy.countDown()
            repeat(12) {
                PluviaApp.events.emit(AndroidEvent.CustomGameImagesFetched("private-noisy-payload-$it"))
            }

            Thread.sleep(700L)
            assertEquals("pending work bypassed physical failure cooldown", 1, attempts.get())
            assertTrue(
                "latest pending work did not run after physical failure cooldown",
                awaitCondition(timeoutMs = 4_000L) {
                    attempts.get() == 2 &&
                        vm.state.value.currentTab == LibraryTab.GOG &&
                        vm.state.value.cards.map { it.name } == listOf("Pending After Cooldown") &&
                        !vm.state.value.isLoading
                },
            )
            verify(exactly = 1) {
                FeatureDiagnostics.record(
                    area = DiagnosticArea.LIBRARY_FILTER,
                    name = DiagnosticEventName.LIBRARY_FILTER,
                    outcome = DiagnosticOutcome.FAILED,
                    durationMs = null,
                    attributes = mapOf(DiagnosticAttribute.ERROR_TYPE to "IllegalStateException"),
                )
            }
        } finally {
            releaseFirstLegacy.countDown()
            viewModelStore.clear()
            io.close()
        }
    }

    @Test
    fun `active initial canonical render cancellation retains safe cards and clears loading`() {
        val io = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        val renderStarted = CountDownLatch(1)
        val releaseRender = CountDownLatch(1)
        val blockCanonicalRender = AtomicBoolean(true)
        val observeCalls = AtomicInteger(0)
        val gateEnabled = AtomicBoolean(false)
        val gate = CanonicalPublicLibraryGate { gateEnabled.get() }
        val repository = mockk<CanonicalLibraryRepository>()
        every { repository.observeCards() } answers {
            observeCalls.incrementAndGet()
            MutableStateFlow(listOf(card(name = "Cancelled Canonical")))
        }
        every { GOGService.hasStoredCredentials(any()) } returns true

        try {
            val vm = viewModel(
                repository = repository,
                gateEnabled = false,
                gate = gate,
                readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
                gogRows = MutableStateFlow(
                    listOf(GOGGame(id = "render-safe", title = "Render Safe Card", isInstalled = true)),
                ),
                ioDispatcher = io,
            )
            awaitState { state ->
                state.cards.map { it.name } == listOf("Render Safe Card") && !state.isLoading
            }
            every { GameCompatibilityCache.getCached(any()) } answers {
                if (blockCanonicalRender.compareAndSet(true, false)) {
                    renderStarted.countDown()
                    awaitUninterruptibly(releaseRender)
                }
                null
            }

            gateEnabled.set(true)
            vm.onTabChanged(LibraryTab.STEAM)
            awaitLatch(renderStarted, "active initial canonical render")
            cancelJobField(vm, "renderJob")
            releaseRender.countDown()

            assertTrue(
                "cancelled active render published or left loading",
                awaitCondition(timeoutMs = 2_000L) {
                    val state = vm.state.value
                    !state.isLoading &&
                        state.canonicalPublicFailure == null &&
                        state.cards.map { it.name } == listOf("Render Safe Card")
                },
            )
            Thread.sleep(1_300L)
            assertEquals("render cancellation restarted collection", 1, observeCalls.get())
            assertEquals(listOf("Render Safe Card"), vm.state.value.cards.map { it.name })
        } finally {
            releaseRender.countDown()
            viewModelStore.clear()
            io.close()
        }
    }

    @Test
    fun `upstream canonical cancellation clears loading without fallback or retry`() {
        val io = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        val observeCalls = AtomicInteger(0)
        val cancellationReached = CountDownLatch(1)
        val gateEnabled = AtomicBoolean(false)
        val gate = CanonicalPublicLibraryGate { gateEnabled.get() }
        val repository = mockk<CanonicalLibraryRepository>()
        every { repository.observeCards() } answers {
            observeCalls.incrementAndGet()
            flow {
                cancellationReached.countDown()
                throw CancellationException("independent upstream cancellation")
            }
        }
        every { GOGService.hasStoredCredentials(any()) } returns true

        try {
            val vm = viewModel(
                repository = repository,
                gateEnabled = false,
                gate = gate,
                readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
                gogRows = MutableStateFlow(
                    listOf(GOGGame(id = "safe-before-cancel", title = "Safe Before Cancellation", isInstalled = true)),
                ),
                ioDispatcher = io,
            )
            awaitState { state ->
                state.cards.map { it.name } == listOf("Safe Before Cancellation") && !state.isLoading
            }

            gateEnabled.set(true)
            vm.onTabChanged(LibraryTab.STEAM)
            awaitLatch(cancellationReached, "upstream collector cancellation")
            assertTrue(
                "upstream cancellation left loading or published fallback",
                awaitCondition(timeoutMs = 2_000L) {
                    val state = vm.state.value
                    !state.isLoading &&
                        state.canonicalPublicFailure == null &&
                        state.cards.map { it.name } == listOf("Safe Before Cancellation")
                },
            )
            Thread.sleep(1_300L)
            assertEquals("upstream cancellation retried collection", 1, observeCalls.get())
            assertNull(vm.state.value.canonicalPublicFailure)
            assertEquals(listOf("Safe Before Cancellation"), vm.state.value.cards.map { it.name })
        } finally {
            viewModelStore.clear()
            io.close()
        }
    }

    @Test
    fun `independently cancelled legacy worker clears loading without fallback or retry`() {
        val io = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        val gateEnabled = AtomicBoolean(true)
        val gate = CanonicalPublicLibraryGate { gateEnabled.get() }
        val attempts = AtomicInteger(0)
        val cancelledWorkerStarted = CountDownLatch(1)
        val releaseCancelledWorker = CountDownLatch(1)
        every { GOGService.hasStoredCredentials(any()) } returns true

        try {
            val vm = viewModel(
                repository = repository(MutableStateFlow(listOf(card(name = "Canonical Safe Before Legacy Cancel")))),
                gateEnabled = true,
                gate = gate,
                readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
                gogRows = MutableStateFlow(listOf(GOGGame(id = "cancel-no-retry", title = "Cancelled Legacy", isInstalled = true))),
                ioDispatcher = io,
            )
            awaitState { state ->
                state.cards.map { it.name } == listOf("Canonical Safe Before Legacy Cancel") && !state.isLoading
            }
            every { DownloadService.getDownloadDirectoryApps() } answers {
                attempts.incrementAndGet()
                cancelledWorkerStarted.countDown()
                awaitUninterruptibly(releaseCancelledWorker)
                mutableListOf()
            }

            gateEnabled.set(false)
            vm.onTabChanged(LibraryTab.GOG)
            awaitLatch(cancelledWorkerStarted, "legacy worker before independent cancellation")
            cancelJobField(vm, "legacyPhysicalJob")
            releaseCancelledWorker.countDown()
            assertTrue(
                "cancelled legacy worker published or left loading",
                awaitCondition(timeoutMs = 2_000L) {
                    val state = vm.state.value
                    !state.isLoading &&
                        state.canonicalPublicFailure == null &&
                        state.cards.map { it.name } == listOf("Canonical Safe Before Legacy Cancel")
                },
            )
            Thread.sleep(1_300L)
            assertEquals("legacy cancellation started retry", 1, attempts.get())
            assertEquals(listOf("Canonical Safe Before Legacy Cancel"), vm.state.value.cards.map { it.name })
        } finally {
            releaseCancelledWorker.countDown()
            viewModelStore.clear()
            io.close()
        }
    }

    @Test
    fun `refresh completion merges fresh stats into latest filter state`() {
        val io = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val gogRows = MutableStateFlow(emptyList<GOGGame>())
        val gogCard = card(
            canonicalId = canonicalId(71),
            name = "Fresh GOG Card",
            copyKeys = listOf(gogKey),
            nativeTitles = mapOf(gogKey to "Fresh GOG Native"),
        )
        val freshDeviceStats = mapOf(
            GameSource.GOG to mapOf("Fresh GOG Native" to stats(runs = 3, fps = 77, reviews = 5, session = 88)),
        )
        val freshGpuStats = mapOf(
            GameSource.GOG to mapOf("Fresh GOG Native" to stats(runs = 9, fps = 60, reviews = 7, session = 44)),
        )

        try {
            val vm = viewModel(
                repository = repository(MutableStateFlow(listOf(gogCard))),
                gateEnabled = true,
                readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
                gogRows = gogRows,
                ioDispatcher = io,
            )
            awaitState { state -> state.cards.map { it.name } == listOf("Fresh GOG Card") && !state.isLoading }
            coEvery { SteamService.refreshOwnedGamesFromServer() } coAnswers {
                refreshStarted.complete(Unit)
                releaseRefresh.await()
                0
            }

            vm.onRefresh()
            scheduler.runCurrent()
            assertTrue("refresh did not reach blocked source work", refreshStarted.isCompleted)
            vm.onTabChanged(LibraryTab.GOG)
            vm.onSortOptionChanged(SortOption.NAME_DESC)
            vm.onSearchQuery("Fresh")
            gogRows.value = listOf(GOGGame(id = "dao-revision", title = "DAO Revision", isInstalled = true))
            scheduler.advanceTimeBy(500L)
            scheduler.runCurrent()
            awaitState { state ->
                state.currentTab == LibraryTab.GOG &&
                    state.currentSortOption == SortOption.NAME_DESC &&
                    state.searchQuery == "Fresh"
            }
            every { DeviceGameStatsCache.getAll() } returns freshDeviceStats
            every { GpuGameStatsCache.getAll() } returns freshGpuStats
            releaseRefresh.complete(Unit)
            scheduler.runCurrent()

            assertTrue(
                "refresh completion dropped stats or restored an obsolete filter state",
                awaitCondition(timeoutMs = 4_000L) {
                    val state = vm.state.value
                    !state.isRefreshing &&
                        !state.isLoading &&
                        state.currentTab == LibraryTab.GOG &&
                        state.currentSortOption == SortOption.NAME_DESC &&
                        state.searchQuery == "Fresh" &&
                        state.currentPaginationPage == 1 &&
                        state.deviceGameStats == freshDeviceStats &&
                        state.gpuGameStats == freshGpuStats &&
                        state.cards.singleOrNull()?.gameStats == GameCardStats(
                            runsGpu = 9,
                            reviewsDevice = 5,
                            reviewsGpu = 7,
                            fps = 77,
                            sessionSec = 88,
                        )
                },
            )
        } finally {
            releaseRefresh.complete(Unit)
            viewModelStore.clear()
            io.close()
        }
    }

    @Test
    fun `refresh completion preserves latest pending page reset`() {
        val io = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val pendingRenderStarted = CountDownLatch(1)
        val releasePendingRender = CountDownLatch(1)
        val blockNextProjection = AtomicBoolean(false)
        val cards = (0 until 105).map { index ->
            card(
                canonicalId = canonicalId(300 + index),
                name = "Paged ${index.toString().padStart(3, '0')}",
                copyKeys = listOf(key(GameSource.STEAM, "paged-$index")),
            )
        }

        try {
            val vm = viewModel(
                repository = repository(MutableStateFlow(cards)),
                gateEnabled = true,
                readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
                ioDispatcher = io,
            )
            awaitState { state -> state.cards.size == 50 && state.currentPaginationPage == 1 && !state.isLoading }
            vm.onPageChange(1)
            awaitState { state -> state.cards.size == 100 && state.currentPaginationPage == 2 && !state.isLoading }
            coEvery { SteamService.refreshOwnedGamesFromServer() } coAnswers {
                refreshStarted.complete(Unit)
                releaseRefresh.await()
                0
            }
            every { GameCompatibilityCache.getCached(any()) } answers {
                if (blockNextProjection.compareAndSet(true, false)) {
                    pendingRenderStarted.countDown()
                    awaitUninterruptibly(releasePendingRender)
                }
                null
            }

            vm.onRefresh()
            scheduler.runCurrent()
            assertTrue("refresh did not reach blocked source work", refreshStarted.isCompleted)
            blockNextProjection.set(true)
            vm.onSearchQuery("Paged")
            scheduler.advanceTimeBy(500L)
            scheduler.runCurrent()
            awaitLatch(pendingRenderStarted, "latest page-resetting filter render")

            releaseRefresh.complete(Unit)
            assertTrue(
                "refresh completion restored the last published page",
                awaitCondition(timeoutMs = 4_000L) {
                    scheduler.runCurrent()
                    val state = vm.state.value
                    !state.isRefreshing &&
                        !state.isLoading &&
                        state.searchQuery == "Paged" &&
                        state.currentPaginationPage == 1 &&
                        state.cards.size == 50
                },
            )
        } finally {
            releaseRefresh.complete(Unit)
            releasePendingRender.countDown()
            viewModelStore.clear()
            io.close()
        }
    }

    @Test
    fun `refresh page zero intent survives DAO emission that supersedes initial render`() {
        val io = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        val steamRows = MutableStateFlow<List<SteamApp>>(emptyList())
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val cards = (0 until 105).map { index ->
            card(
                canonicalId = canonicalId(500 + index),
                name = "Refresh Page ${index.toString().padStart(3, '0')}",
                copyKeys = listOf(key(GameSource.STEAM, "refresh-page-$index")),
            )
        }

        try {
            val vm = viewModel(
                repository = repository(MutableStateFlow(cards)),
                gateEnabled = true,
                readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
                steamRows = steamRows,
                ioDispatcher = io,
            )
            awaitState { state -> state.cards.size == 50 && state.currentPaginationPage == 1 && !state.isLoading }
            vm.onPageChange(1)
            awaitState { state -> state.cards.size == 100 && state.currentPaginationPage == 2 && !state.isLoading }
            coEvery { SteamService.refreshOwnedGamesFromServer() } coAnswers {
                refreshStarted.complete(Unit)
                releaseRefresh.await()
                0
            }

            vm.onRefresh()
            scheduler.runCurrent()
            assertTrue("refresh did not start", refreshStarted.isCompleted)
            steamRows.value = listOf(mockk(relaxed = true))
            assertTrue(
                "DAO emission inherited the page published before refresh",
                awaitCondition(timeoutMs = 4_000L) {
                    scheduler.runCurrent()
                    val state = vm.state.value
                    state.isRefreshing &&
                        !state.isLoading &&
                        state.currentPaginationPage == 1 &&
                        state.cards.size == 50
                },
            )

            releaseRefresh.complete(Unit)
            assertTrue(
                "DAO emission restored the page published before refresh",
                awaitCondition(timeoutMs = 4_000L) {
                    scheduler.runCurrent()
                    val state = vm.state.value
                    !state.isRefreshing &&
                        !state.isLoading &&
                        state.currentPaginationPage == 1 &&
                        state.cards.size == 50
                },
            )
        } finally {
            releaseRefresh.complete(Unit)
            viewModelStore.clear()
            io.close()
        }
    }

    @Test
    fun `post clear source failure rerenders compatible filter from cleared cache`() {
        val io = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        val compatibilityCleared = AtomicBoolean(false)
        val refetchQueued = AtomicBoolean(false)
        val cachedCompatibility = GameCompatibilityService.GameCompatibilityResponse(
            gameName = "Recovery Compatible",
            totalPlayableCount = 1,
            gpuPlayableCount = 0,
            avgRating = 5f,
            hasBeenTried = true,
            isNotWorking = false,
        )
        coEvery { GameCompatibilityCache.clear() } coAnswers {
            compatibilityCleared.set(true)
            Unit
        }
        every { GameCompatibilityCache.getCached(any()) } answers {
            cachedCompatibility.takeUnless { compatibilityCleared.get() }
        }
        mockkObject(GameCompatibilityService)
        coEvery { GameCompatibilityService.fetchCompatibility(any(), any()) } coAnswers {
            refetchQueued.set(true)
            null
        }
        mockkObject(SnackbarManager)
        every { SnackbarManager.show(any()) } just runs

        try {
            val vm = viewModel(
                repository = repository(MutableStateFlow(listOf(card(name = "Recovery Compatible")))),
                gateEnabled = true,
                readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
                ioDispatcher = io,
            )
            awaitState { state -> state.cards.map { it.name } == listOf("Recovery Compatible") && !state.isLoading }
            setLazyStringField(vm, "gpuName", "Test GPU")
            vm.onFilterChanged(AppFilter.COMPATIBLE)
            awaitState { state ->
                state.appInfoSortType.contains(AppFilter.COMPATIBLE) &&
                    state.cards.singleOrNull()?.compatibilityStatus == GameCompatibilityStatus.COMPATIBLE &&
                    !state.isLoading
            }
            coEvery { SteamService.refreshOwnedGamesFromServer() } throws IllegalStateException("private source failure")

            vm.onRefresh()
            scheduler.runCurrent()
            assertTrue(
                "post-clear failure retained stale compatibility or skipped refetch",
                awaitCondition(timeoutMs = 4_000L) {
                    scheduler.runCurrent()
                    val state = vm.state.value
                    !state.isRefreshing &&
                        !state.isLoading &&
                        state.cards.map { it.name } == listOf("Recovery Compatible") &&
                        state.cards.single().compatibilityStatus == null &&
                        refetchQueued.get()
                },
            )
        } finally {
            viewModelStore.clear()
            io.close()
        }
    }

    @Test
    fun `pre-refresh compatibility fetch cannot repopulate cleared cache or recovered state`() = runTest(dispatcher) {
        val oldFetchStarted = CompletableDeferred<Unit>()
        val releaseOldFetch = CompletableDeferred<Unit>()
        val oldFetchReturned = CompletableDeferred<Unit>()
        var fetchCalls = 0
        var cacheGeneration = 1L
        val committedCompatibility = mutableMapOf<String, GameCompatibilityService.GameCompatibilityResponse>()
        val gameName = "Compatibility Generation Race"
        val staleCompatibility = GameCompatibilityService.GameCompatibilityResponse(
            gameName = gameName,
            totalPlayableCount = 0,
            gpuPlayableCount = 0,
            avgRating = 1f,
            hasBeenTried = true,
            isNotWorking = true,
        )
        every { GameCompatibilityCache.getCached(any()) } answers {
            committedCompatibility[firstArg()]
        }
        every { GameCompatibilityCache.captureGeneration() } answers { cacheGeneration }
        coEvery { GameCompatibilityCache.clear() } coAnswers {
            cacheGeneration += 1L
            committedCompatibility.clear()
        }
        coEvery { GameCompatibilityCache.cacheAllIfCurrent(any(), any()) } coAnswers {
            val capturedGeneration = firstArg<Long>()
            val responses = secondArg<Map<String, GameCompatibilityService.GameCompatibilityResponse>>()
            if (capturedGeneration == cacheGeneration) {
                committedCompatibility.putAll(responses)
                true
            } else {
                false
            }
        }
        mockkObject(GameCompatibilityService)
        coEvery { GameCompatibilityService.fetchCompatibility(any(), any()) } coAnswers {
            fetchCalls += 1
            if (fetchCalls == 1) {
                oldFetchStarted.complete(Unit)
                releaseOldFetch.await()
                oldFetchReturned.complete(Unit)
                mapOf(gameName to staleCompatibility)
            } else {
                null
            }
        }
        mockkObject(SnackbarManager)
        every { SnackbarManager.show(any()) } just runs

        try {
            val emissions = MutableSharedFlow<List<CanonicalLibraryCard>>()
            val vm = viewModel(
                repository = repository(emissions),
                gateEnabled = true,
                readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
                ioDispatcher = dispatcher,
            )
            setLazyStringField(vm, "gpuName", "Test GPU")
            runCurrent()
            assertEquals(1, emissions.subscriptionCount.value)

            emissions.emit(listOf(card(name = gameName)))
            runCurrent()
            assertEquals(listOf(gameName), vm.state.value.cards.map { it.name })
            assertFalse(vm.state.value.isLoading)
            assertTrue(oldFetchStarted.isCompleted)

            vm.onFilterChanged(AppFilter.COMPATIBLE)
            runCurrent()
            with(vm.state.value) {
                assertTrue(appInfoSortType.contains(AppFilter.COMPATIBLE))
                assertEquals(listOf(gameName), cards.map { it.name })
                assertFalse(isLoading)
            }
            coEvery { DeviceGameStatsCache.clear() } throws
                IllegalStateException("private post-clear failure")

            vm.onRefresh()
            runCurrent()
            with(vm.state.value) {
                assertFalse(isRefreshing)
                assertFalse(isLoading)
                assertEquals(listOf(gameName), cards.map { it.name })
                assertNull(cards.single().compatibilityStatus)
                assertFalse(gameName in compatibilityMap)
            }
            assertEquals(2L, cacheGeneration)
            assertTrue(committedCompatibility.isEmpty())

            releaseOldFetch.complete(Unit)
            runCurrent()
            assertTrue(oldFetchReturned.isCompleted)
            assertTrue(committedCompatibility.isEmpty())
            with(vm.state.value) {
                assertEquals(listOf(gameName), cards.map { it.name })
                assertNull(cards.single().compatibilityStatus)
                assertFalse(gameName in compatibilityMap)
            }
        } finally {
            releaseOldFetch.complete(Unit)
            viewModelStore.clear()
        }
    }

    @Test
    fun `ordinary failures across refresh boundaries clear indicator without merging stats`() {
        val io = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        val freshDeviceStats = mapOf(GameSource.STEAM to mapOf("Fresh Device" to stats(1, 60, 1, 60)))
        val freshGpuStats = mapOf(GameSource.STEAM to mapOf("Fresh GPU" to stats(2, 60, 2, 60)))
        mockkObject(FeatureDiagnostics)
        every { FeatureDiagnostics.record(any(), any(), any(), any(), any()) } just runs
        mockkObject(SnackbarManager)
        every { SnackbarManager.show(any()) } just runs

        try {
            RefreshFailureBoundary.entries.forEach { boundary ->
                stubSuccessfulRefreshPipeline(emptyMap(), emptyMap())
                val vm = viewModel(
                    repository = repository(MutableStateFlow(listOf(card(name = "Safe Refresh Failure")))),
                    gateEnabled = true,
                    readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
                    ioDispatcher = io,
                )
                awaitState { state ->
                    state.cards.map { it.name } == listOf("Safe Refresh Failure") &&
                        state.deviceGameStats.isEmpty() &&
                        state.gpuGameStats.isEmpty() &&
                        !state.isLoading
                }
                setLazyStringField(vm, "gpuName", "Test GPU")
                stubSuccessfulRefreshPipeline(freshDeviceStats, freshGpuStats)
                clearMocks(FeatureDiagnostics, SnackbarManager, answers = false)
                val failure = IllegalStateException("private ${boundary.name} refresh failure")
                when (boundary) {
                    RefreshFailureBoundary.COMPATIBILITY_CLEAR ->
                        coEvery { GameCompatibilityCache.clear() } throws failure
                    RefreshFailureBoundary.DEVICE_CLEAR ->
                        coEvery { DeviceGameStatsCache.clear() } throws failure
                    RefreshFailureBoundary.GPU_CLEAR ->
                        coEvery { GpuGameStatsCache.clear() } throws failure
                    RefreshFailureBoundary.STEAM_SYNC ->
                        coEvery { SteamService.refreshOwnedGamesFromServer() } throws failure
                    RefreshFailureBoundary.GOG_CREDENTIALS ->
                        every { GOGService.hasStoredCredentials(any()) } throws failure
                    RefreshFailureBoundary.GOG_SYNC -> {
                        every { GOGService.hasStoredCredentials(any()) } returns true
                        every { GOGService.triggerLibrarySync(any()) } throws failure
                    }
                    RefreshFailureBoundary.AMAZON_CREDENTIALS ->
                        every { AmazonService.hasStoredCredentials(any()) } throws failure
                    RefreshFailureBoundary.AMAZON_SYNC -> {
                        every { AmazonService.hasStoredCredentials(any()) } returns true
                        every { AmazonService.triggerLibrarySync(any()) } throws failure
                    }
                    RefreshFailureBoundary.DEVICE_REFRESH ->
                        coEvery { DeviceGameStatsCache.refreshIfStale(any(), any(), any()) } throws failure
                    RefreshFailureBoundary.GPU_REFRESH ->
                        coEvery { GpuGameStatsCache.refreshIfStale(any(), any()) } throws failure
                    RefreshFailureBoundary.DEVICE_READ ->
                        every { DeviceGameStatsCache.getAll() } throws failure
                    RefreshFailureBoundary.GPU_READ ->
                        every { GpuGameStatsCache.getAll() } throws failure
                }

                vm.onRefresh()
                scheduler.runCurrent()
                assertTrue(
                    "$boundary left refresh active or merged partial stats",
                    awaitCondition(timeoutMs = 2_000L) {
                        scheduler.runCurrent()
                        val state = vm.state.value
                        !state.isRefreshing &&
                            state.deviceGameStats.isEmpty() &&
                            state.gpuGameStats.isEmpty() &&
                            state.cards.map { it.name } == listOf("Safe Refresh Failure")
                    },
                )
                verify(exactly = 1) {
                    FeatureDiagnostics.record(
                        area = DiagnosticArea.LIBRARY_FILTER,
                        name = DiagnosticEventName.LIBRARY_FILTER,
                        outcome = DiagnosticOutcome.FAILED,
                        durationMs = null,
                        attributes = mapOf(
                            DiagnosticAttribute.REASON to boundary.stage,
                            DiagnosticAttribute.ERROR_TYPE to "IllegalStateException",
                        ),
                    )
                }
                verify(exactly = 1) {
                    SnackbarManager.show("Library refresh failed. Check your connection and try again.")
                }
                viewModelStore.clear()
                viewModelStore = ViewModelStore()
            }
        } finally {
            viewModelStore.clear()
            io.close()
        }
    }

    @Test
    fun `unavailable stats retrieval clears refresh without merging cached values`() {
        val io = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        val freshDeviceStats = mapOf(GameSource.STEAM to mapOf("Fresh Device" to stats(1, 60, 1, 60)))
        val freshGpuStats = mapOf(GameSource.STEAM to mapOf("Fresh GPU" to stats(2, 60, 2, 60)))
        mockkObject(FeatureDiagnostics)
        every { FeatureDiagnostics.record(any(), any(), any(), any(), any()) } just runs
        mockkObject(SnackbarManager)
        every { SnackbarManager.show(any()) } just runs

        try {
            listOf("DEVICE", "GPU").forEach { unavailableCache ->
                stubSuccessfulRefreshPipeline(emptyMap(), emptyMap())
                val vm = viewModel(
                    repository = repository(MutableStateFlow(listOf(card(name = "Safe Stats Retrieval")))),
                    gateEnabled = true,
                    readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
                    ioDispatcher = io,
                )
                awaitState { state ->
                    state.cards.map { it.name } == listOf("Safe Stats Retrieval") &&
                        state.deviceGameStats.isEmpty() &&
                        state.gpuGameStats.isEmpty() &&
                        !state.isLoading
                }
                setLazyStringField(vm, "gpuName", "Test GPU")
                stubSuccessfulRefreshPipeline(freshDeviceStats, freshGpuStats)
                if (unavailableCache == "DEVICE") {
                    coEvery { DeviceGameStatsCache.refreshIfStale(any(), any(), any()) } returns false
                } else {
                    coEvery { GpuGameStatsCache.refreshIfStale(any(), any()) } returns false
                }
                clearMocks(FeatureDiagnostics, SnackbarManager, answers = false)

                vm.onRefresh()
                scheduler.runCurrent()
                assertTrue(
                    "$unavailableCache retrieval rejection merged cached stats",
                    awaitCondition(timeoutMs = 2_000L) {
                        scheduler.runCurrent()
                        val state = vm.state.value
                        !state.isRefreshing &&
                            state.deviceGameStats.isEmpty() &&
                            state.gpuGameStats.isEmpty() &&
                            state.cards.map { it.name } == listOf("Safe Stats Retrieval")
                    },
                )
                verify(exactly = 1) {
                    FeatureDiagnostics.record(
                        area = DiagnosticArea.LIBRARY_FILTER,
                        name = DiagnosticEventName.LIBRARY_FILTER,
                        outcome = DiagnosticOutcome.FAILED,
                        durationMs = null,
                        attributes = mapOf(
                            DiagnosticAttribute.REASON to "${unavailableCache}_STATS_REFRESH",
                            DiagnosticAttribute.ERROR_TYPE to "LibraryStatsRefreshUnavailable",
                        ),
                    )
                }
                verify(exactly = 1) {
                    SnackbarManager.show("Library refresh failed. Check your connection and try again.")
                }
                viewModelStore.clear()
                viewModelStore = ViewModelStore()
            }
        } finally {
            viewModelStore.clear()
            io.close()
        }
    }

    @Test
    fun `independently cancelled refresh clears indicator without diagnostics or merge`() {
        val io = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val freshDeviceStats = mapOf(GameSource.STEAM to mapOf("Cancelled Device" to stats(1, 60, 1, 60)))
        val freshGpuStats = mapOf(GameSource.STEAM to mapOf("Cancelled GPU" to stats(2, 60, 2, 60)))
        mockkObject(FeatureDiagnostics)
        every { FeatureDiagnostics.record(any(), any(), any(), any(), any()) } just runs
        mockkObject(SnackbarManager)
        every { SnackbarManager.show(any()) } just runs

        try {
            val vm = viewModel(
                repository = repository(MutableStateFlow(listOf(card(name = "Safe Before Refresh Cancel")))),
                gateEnabled = true,
                readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
                ioDispatcher = io,
            )
            awaitState { state -> state.cards.map { it.name } == listOf("Safe Before Refresh Cancel") }
            stubSuccessfulRefreshPipeline(freshDeviceStats, freshGpuStats)
            coEvery { SteamService.refreshOwnedGamesFromServer() } coAnswers {
                refreshStarted.complete(Unit)
                releaseRefresh.await()
                0
            }
            clearMocks(FeatureDiagnostics, SnackbarManager, answers = false)

            vm.onRefresh()
            scheduler.runCurrent()
            assertTrue("refresh did not start before independent cancellation", refreshStarted.isCompleted)
            cancelJobField(vm, "refreshJob")
            releaseRefresh.complete(Unit)
            assertTrue(
                "cancelled refresh left indicator active or merged stats",
                awaitCondition(timeoutMs = 2_000L) {
                    scheduler.runCurrent()
                    val state = vm.state.value
                    !state.isRefreshing &&
                        state.deviceGameStats.isEmpty() &&
                        state.gpuGameStats.isEmpty() &&
                        state.cards.map { it.name } == listOf("Safe Before Refresh Cancel")
                },
            )
            verify(exactly = 0) {
                FeatureDiagnostics.record(
                    area = any(),
                    name = any(),
                    outcome = DiagnosticOutcome.FAILED,
                    durationMs = any(),
                    attributes = any(),
                )
            }
            verify(exactly = 0) { SnackbarManager.show(any()) }
        } finally {
            releaseRefresh.complete(Unit)
            viewModelStore.clear()
            io.close()
        }
    }

    @Test
    fun `view model shutdown clears authoritative refresh indicator`() {
        val io = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()

        try {
            val vm = viewModel(
                repository = repository(MutableStateFlow(listOf(card(name = "Safe Before Shutdown")))),
                gateEnabled = true,
                readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
                ioDispatcher = io,
            )
            awaitState { state -> state.cards.map { it.name } == listOf("Safe Before Shutdown") }
            coEvery { SteamService.refreshOwnedGamesFromServer() } coAnswers {
                refreshStarted.complete(Unit)
                releaseRefresh.await()
                0
            }

            vm.onRefresh()
            scheduler.runCurrent()
            assertTrue("refresh did not start before view model shutdown", refreshStarted.isCompleted)
            viewModelStore.clear()
            releaseRefresh.complete(Unit)
            assertTrue(
                "parent cancellation left refresh indicator active",
                awaitCondition(timeoutMs = 2_000L) {
                    scheduler.runCurrent()
                    !vm.state.value.isRefreshing
                },
            )
        } finally {
            releaseRefresh.complete(Unit)
            viewModelStore.clear()
            io.close()
        }
    }

    @Test
    fun `older refresh cannot clear or overwrite a newer refresh`() {
        val io = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        val firstRefreshStarted = CompletableDeferred<Unit>()
        val firstRefreshSourceCompleted = CompletableDeferred<Unit>()
        val secondRefreshStarted = CompletableDeferred<Unit>()
        val releaseFirstRefresh = CompletableDeferred<Unit>()
        val releaseSecondRefresh = CompletableDeferred<Unit>()
        val refreshCalls = AtomicInteger(0)
        val oldDeviceStats = mapOf(GameSource.STEAM to mapOf("Epoch Native" to stats(1, 10, 1, 10)))
        val oldGpuStats = mapOf(GameSource.STEAM to mapOf("Epoch Native" to stats(2, 20, 2, 20)))
        val newDeviceStats = mapOf(GameSource.STEAM to mapOf("Epoch Native" to stats(3, 30, 3, 30)))
        val newGpuStats = mapOf(GameSource.STEAM to mapOf("Epoch Native" to stats(4, 40, 4, 40)))
        val deviceResult = AtomicReference<Map<GameSource, Map<String, DeviceGameStats>>>(emptyMap())
        val gpuResult = AtomicReference<Map<GameSource, Map<String, DeviceGameStats>>>(emptyMap())

        try {
            val vm = viewModel(
                repository = repository(MutableStateFlow(listOf(card(name = "Refresh Epoch")))),
                gateEnabled = true,
                readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
                ioDispatcher = io,
            )
            awaitState { state -> state.cards.map { it.name } == listOf("Refresh Epoch") && !state.isLoading }
            coEvery { SteamService.refreshOwnedGamesFromServer() } coAnswers {
                if (refreshCalls.incrementAndGet() == 1) {
                    firstRefreshStarted.complete(Unit)
                    releaseFirstRefresh.await()
                    firstRefreshSourceCompleted.complete(Unit)
                } else {
                    secondRefreshStarted.complete(Unit)
                    releaseSecondRefresh.await()
                }
                0
            }
            every { DeviceGameStatsCache.getAll() } answers { deviceResult.get() }
            every { GpuGameStatsCache.getAll() } answers { gpuResult.get() }

            vm.onRefresh()
            scheduler.runCurrent()
            assertTrue("first refresh did not start", firstRefreshStarted.isCompleted)
            vm.onRefresh()
            scheduler.runCurrent()
            assertTrue("second refresh did not start", secondRefreshStarted.isCompleted)

            deviceResult.set(oldDeviceStats)
            gpuResult.set(oldGpuStats)
            releaseFirstRefresh.complete(Unit)
            assertTrue(
                "older refresh source did not complete",
                awaitCondition(timeoutMs = 4_000L) {
                    scheduler.runCurrent()
                    firstRefreshSourceCompleted.isCompleted
                },
            )
            repeat(10) {
                scheduler.runCurrent()
                Thread.sleep(50L)
            }
            assertTrue("older refresh cleared the active newer refresh", vm.state.value.isRefreshing)
            assertTrue("older refresh published stale device stats", vm.state.value.deviceGameStats != oldDeviceStats)
            assertTrue("older refresh published stale GPU stats", vm.state.value.gpuGameStats != oldGpuStats)

            deviceResult.set(newDeviceStats)
            gpuResult.set(newGpuStats)
            releaseSecondRefresh.complete(Unit)
            scheduler.runCurrent()
            assertTrue(
                "newer refresh did not publish authoritative stats",
                awaitCondition(timeoutMs = 4_000L) {
                    scheduler.runCurrent()
                    val state = vm.state.value
                    !state.isRefreshing &&
                        state.deviceGameStats == newDeviceStats &&
                        state.gpuGameStats == newGpuStats
                },
            )
        } finally {
            releaseFirstRefresh.complete(Unit)
            releaseSecondRefresh.complete(Unit)
            viewModelStore.clear()
            io.close()
        }
    }

    @Test
    fun `noisy callbacks cannot bypass canonical projection retry cooldown`() {
        val io = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        val observeCalls = AtomicInteger(0)
        val emissions = MutableStateFlow(listOf(card(name = "Cooldown Canonical")))
        val repository = mockk<CanonicalLibraryRepository>()
        every { repository.observeCards() } answers {
            observeCalls.incrementAndGet()
            emissions
        }
        val failNextProjection = AtomicBoolean(false)

        try {
            val vm = viewModel(
                repository = repository,
                gateEnabled = true,
                readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
                ioDispatcher = io,
            )
            awaitState { it.cards.map { card -> card.name } == listOf("Cooldown Canonical") }
            every { GameCompatibilityCache.getCached(any()) } answers {
                if (failNextProjection.compareAndSet(true, false)) {
                    throw IllegalStateException("fixed cooldown projection failure")
                }
                null
            }

            failNextProjection.set(true)
            vm.onTabChanged(LibraryTab.STEAM)
            awaitState { it.canonicalPublicFailure == CanonicalPublicFailure.ASSEMBLY_FAILED }

            repeat(12) {
                PluviaApp.events.emit(AndroidEvent.CustomGameImagesFetched("private-noisy-payload-$it"))
            }
            Thread.sleep(700L)
            assertEquals("ordinary callbacks bypassed the projection retry deadline", 1, observeCalls.get())

            assertTrue(
                "canonical projection did not retry after its cooldown",
                awaitCondition(timeoutMs = 4_000L) {
                    observeCalls.get() == 2 &&
                        vm.state.value.cards.map { it.name } == listOf("Cooldown Canonical") &&
                        vm.state.value.canonicalPublicFailure == null
                },
            )
        } finally {
            viewModelStore.clear()
            io.close()
        }
    }

    @Test
    fun `concurrent eligible triggers create one collector and unsupported context rejects later emissions`() {
        val executor = Executors.newFixedThreadPool(16)
        val delegate = executor.asCoroutineDispatcher()
        val slowDispatch = object : CoroutineDispatcher() {
            val armed = AtomicBoolean(false)

            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                if (armed.get()) Thread.sleep(75L)
                delegate.dispatch(context, block)
            }
        }
        val gateEnabled = AtomicBoolean(false)
        val gate = CanonicalPublicLibraryGate { gateEnabled.get() }
        val readiness = CanonicalProjectionReadiness()
        val observeCalls = AtomicInteger(0)
        val emissions = MutableStateFlow<List<CanonicalLibraryCard>>(emptyList())
        val repository = mockk<CanonicalLibraryRepository>()
        every { repository.observeCards() } answers {
            observeCalls.incrementAndGet()
            emissions
        }
        val callers = 12
        val ready = CountDownLatch(callers)
        val start = CountDownLatch(1)
        val done = CountDownLatch(callers)

        try {
            val vm = viewModel(
                repository = repository,
                gateEnabled = false,
                readiness = readiness,
                gate = gate,
                ioDispatcher = slowDispatch,
            )
            readiness.markSucceeded()
            gateEnabled.set(true)
            slowDispatch.armed.set(true)
            repeat(callers) { index ->
                Thread {
                    ready.countDown()
                    start.await(5, TimeUnit.SECONDS)
                    vm.onTabChanged(if (index % 2 == 0) LibraryTab.ALL else LibraryTab.STEAM)
                    done.countDown()
                }.start()
            }
            awaitLatch(ready, "concurrent collector callers")
            start.countDown()
            awaitLatch(done, "concurrent collector triggers", timeoutMs = 10_000L)
            slowDispatch.armed.set(false)
            assertTrue("canonical repository was not collected", awaitCondition { observeCalls.get() >= 1 })
            assertEquals(1, observeCalls.get())

            vm.onTabChanged(LibraryTab.RECOMMENDED)
            awaitState { state ->
                state.canonicalPublicFailure == CanonicalPublicFailure.UNSUPPORTED_LEGACY_CONTEXT &&
                    !state.isLoading
            }
            emissions.value = listOf(card(canonicalId = canonicalId(72), name = "Unsupported Orphan"))
            assertFalse(
                "unsupported context accepted an orphan emission",
                awaitCondition(timeoutMs = 1_000L) {
                    vm.state.value.cards.any { it.name == "Unsupported Orphan" }
                },
            )
            assertEquals(CanonicalPublicFailure.UNSUPPORTED_LEGACY_CONTEXT, vm.state.value.canonicalPublicFailure)
        } finally {
            start.countDown()
            slowDispatch.armed.set(false)
            viewModelStore.clear()
            delegate.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `readiness fallback throw restores safe state and retries autonomously`() {
        val io = Executors.newFixedThreadPool(6).asCoroutineDispatcher()
        val attempts = AtomicInteger(0)

        try {
            val vm = viewModel(
                repository = repository(MutableStateFlow(emptyList())),
                gateEnabled = true,
                readiness = CanonicalProjectionReadiness(),
                ioDispatcher = io,
            )
            awaitState { !it.isLoading }
            every { DownloadService.getDownloadDirectoryApps() } answers {
                if (attempts.incrementAndGet() == 1) throw IllegalStateException("fixed legacy failure")
                mutableListOf()
            }

            vm.onTabChanged(LibraryTab.STEAM)
            assertTrue(
                "legacy fallback did not retry after throwing",
                awaitCondition(timeoutMs = 5_000L) {
                    attempts.get() >= 2 && !vm.state.value.isLoading
                },
            )
            assertEquals(CanonicalPublicFailure.MISSING_PROJECTION_PREREQUISITE, vm.state.value.canonicalPublicFailure)
        } finally {
            viewModelStore.clear()
            io.close()
        }
    }

    @Test
    fun `blocked canonical fallback cannot stall collection retry or loading forever`() {
        val io = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        val fallbackStarted = CountDownLatch(1)
        val releaseFallback = CountDownLatch(1)
        val blockFirstFallback = AtomicBoolean(true)
        val collectionAttempts = AtomicInteger(0)
        every { DownloadService.getDownloadDirectoryApps() } answers {
            if (blockFirstFallback.compareAndSet(true, false)) {
                fallbackStarted.countDown()
                releaseFallback.await(15, TimeUnit.SECONDS)
            }
            mutableListOf()
        }
        val repository = mockk<CanonicalLibraryRepository>()
        every { repository.observeCards() } answers {
            if (collectionAttempts.incrementAndGet() == 1) {
                flow { throw IllegalStateException("fixed collection failure") }
            } else {
                MutableStateFlow(listOf(card(name = "Autonomous Recovery")))
            }
        }

        try {
            val vm = viewModel(
                repository = repository,
                gateEnabled = true,
                readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
                ioDispatcher = io,
            )
            awaitLatch(fallbackStarted, "blocked legacy fallback")
            val recovered = awaitCondition(timeoutMs = 8_000L) {
                collectionAttempts.get() >= 2 &&
                    vm.state.value.cards.map { it.name } == listOf("Autonomous Recovery") &&
                    !vm.state.value.isLoading
            }
            assertTrue(
                "canonical retry waited indefinitely for blocked fallback: attempts=${collectionAttempts.get()}, state=${vm.state.value}",
                recovered,
            )
        } finally {
            releaseFallback.countDown()
            viewModelStore.clear()
            io.close()
        }
    }

    @Test
    fun `authoritative capability preference and runtime-only snapshot advances observable revision`() = runTest(dispatcher) {
        val emissions = MutableSharedFlow<List<CanonicalLibraryCard>>()
        val initial = card(
            name = "Reactive copies",
            copyKeys = listOf(steamKey, gogKey),
        )
        val updatedGog = initial.copies.single { it.key == gogKey }.copy(
            capabilities = setOf(OwnedCopyOperation.PLAY, OwnedCopyOperation.OPEN_SOURCE_DETAILS),
            branchOrVersion = "runtime-v2",
        )
        val updated = initial.copy(
            copies = initial.copies.map { copy -> if (copy.key == gogKey) updatedGog else copy },
            preferredCopy = gogKey,
        )
        val vm = viewModel(
            repository = repository(emissions),
            gateEnabled = true,
            readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
        )
        runCurrent()
        assertTrue(awaitCondition { emissions.subscriptionCount.value > 0 })

        emissions.emit(listOf(initial))
        runCurrent()
        awaitState { state -> state.cards.singleOrNull()?.name == initial.displayName && !state.isLoading }
        val initialRevision = vm.state.value.canonicalSnapshotRevision
        val initialCards = vm.state.value.cards

        emissions.emit(listOf(updated))
        assertTrue(
            awaitCondition {
                scheduler.runCurrent()
                vm.state.value.canonicalSnapshotRevision > initialRevision
            },
        )

        assertEquals(initialCards, vm.state.value.cards)
        assertEquals(gogKey, vm.canonicalCard(initial.key)?.preferredCopy)
        assertEquals(
            "runtime-v2",
            vm.canonicalCard(initial.key)?.copies?.single { it.key == gogKey }?.branchOrVersion,
        )
        assertTrue(
            OwnedCopyOperation.PLAY in requireNotNull(vm.canonicalCard(initial.key))
                .copies.single { it.key == gogKey }.capabilities,
        )
        viewModelStore.clear()
    }

    @Test
    fun `canonical selection routes the exact current card and explicit copy through owned router`() = runTest(dispatcher) {
        val grouped = card(
            name = "Exact routed card",
            copyKeys = listOf(steamKey, gogKey),
        )
        val router = mockk<OwnedCopyActionRouter>()
        val ready = OwnedCopyRouteResult.Ready(
            guard = mockk<OwnedCopyActionGuard>(),
            policy = ActionSelectionPolicy.EXPLICIT,
        )
        coEvery {
            router.route(
                card = grouped,
                operation = OwnedCopyOperation.OPEN_SOURCE_DETAILS,
                explicitKey = gogKey,
                rememberChoice = false,
            )
        } returns ready
        val vm = viewModel(
            repository = repository(MutableStateFlow(listOf(grouped))),
            gateEnabled = true,
            readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
            actionRouter = router,
        )
        runCurrent()

        assertEquals(
            ready,
            vm.routeCanonicalAction(
                key = grouped.key,
                operation = OwnedCopyOperation.OPEN_SOURCE_DETAILS,
                explicitKey = gogKey,
                rememberChoice = false,
            ),
        )
        coVerify(exactly = 1) {
            router.route(grouped, OwnedCopyOperation.OPEN_SOURCE_DETAILS, gogKey, false)
        }
        viewModelStore.clear()
    }

    @Test
    fun `canonical routing failure returns fixed unavailable result`() = runTest(dispatcher) {
        val grouped = card(
            name = "Route failure",
            copyKeys = listOf(steamKey, gogKey),
        )
        val router = mockk<OwnedCopyActionRouter>()
        coEvery { router.route(any(), any(), any(), any()) } throws
            IllegalStateException("private router detail")
        val vm = viewModel(
            repository = repository(MutableStateFlow(listOf(grouped))),
            gateEnabled = true,
            readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
            actionRouter = router,
        )
        runCurrent()

        assertEquals(
            OwnedCopyRouteResult.Unavailable(ActionFailureReason.COPY_UNAVAILABLE),
            vm.routeCanonicalAction(
                key = grouped.key,
                operation = OwnedCopyOperation.OPEN_SOURCE_DETAILS,
            ),
        )
        viewModelStore.clear()
    }

    @Test
    fun `separate copy re-resolves exact key and forwards only current runtime metadata`() = runTest(dispatcher) {
        val grouped = card(
            name = "Grouped",
            copyKeys = listOf(steamKey, epicKey),
        )
        val currentRuntime = runtime(epicKey).copy(
            nativeTitle = "Current runtime title",
            developerKey = "current-developer",
            releaseYear = 2025,
            appType = CanonicalAppType.APPLICATION,
            genreKeys = linkedSetOf("epic:current-genre"),
            tagIds = linkedSetOf(7, 9),
            featureKeys = linkedSetOf("epic:current-feature"),
        )
        val registry = mockk<OwnedCopyRuntimeRegistry>(relaxed = true)
        coEvery { registry.resolve(epicKey) } returns OwnedCopyRuntimeResult.Available(currentRuntime)
        val mutations = mockk<CanonicalMutationRepository>()
        coEvery {
            mutations.guardedUnmergeCopy(any(), any(), any(), any())
        } returns CanonicalGuardedMutationResult.APPLIED
        val clock = CanonicalProjectionClock { 456L }
        val vm = viewModel(
            repository = repository(MutableStateFlow(listOf(grouped))),
            gateEnabled = true,
            readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
            runtimeRegistry = registry,
            mutationRepository = mutations,
            projectionClock = clock,
        )
        runCurrent()

        assertEquals(
            CanonicalCopyChangeResult.SUCCESS,
            vm.separateCanonicalCopy(grouped.key, epicKey),
        )
        coVerify(exactly = 1) { registry.resolve(epicKey) }
        coVerify(exactly = 1) {
            mutations.guardedUnmergeCopy(
                key = epicKey,
                current = OwnedCopyProjection(
                    key = epicKey,
                    displayName = "Current runtime title",
                    developer = "current-developer",
                    releaseYear = 2025,
                    appType = CanonicalAppType.APPLICATION,
                    genreKeys = linkedSetOf("epic:current-genre"),
                    tagIds = linkedSetOf(7, 9),
                    featureKeys = linkedSetOf("epic:current-feature"),
                ),
                expectedCanonicalId = grouped.canonicalId.value,
                nowEpochMs = 456L,
            )
        }
        viewModelStore.clear()
    }

    @Test
    fun `legacy bridge unsupported copy can separate when fresh runtime metadata is available`() = runTest(dispatcher) {
        val unsupportedCopy = copy(
            key = epicKey,
            nativeTitle = "Unsupported bridge",
        ).copy(
            unavailableReason = CopyUnavailableReason.LEGACY_BRIDGE_UNSUPPORTED,
            canSeparateMatch = true,
        )
        val grouped = card(
            name = "Grouped unsupported bridge",
            copyKeys = listOf(steamKey, epicKey),
        ).copy(copies = listOf(copy(steamKey, "Steam copy"), unsupportedCopy))
        val registry = mockk<OwnedCopyRuntimeRegistry>(relaxed = true)
        coEvery { registry.resolve(epicKey) } returns OwnedCopyRuntimeResult.Available(
            runtime(epicKey).copy(libraryItem = null),
        )
        val mutations = mockk<CanonicalMutationRepository>()
        coEvery {
            mutations.guardedUnmergeCopy(any(), any(), any(), any())
        } returns CanonicalGuardedMutationResult.APPLIED
        val vm = viewModel(
            repository = repository(MutableStateFlow(listOf(grouped))),
            gateEnabled = true,
            readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
            runtimeRegistry = registry,
            mutationRepository = mutations,
        )
        runCurrent()

        assertEquals(
            CanonicalCopyChangeResult.SUCCESS,
            vm.separateCanonicalCopy(grouped.key, epicKey),
        )
        coVerify(exactly = 1) { registry.resolve(epicKey) }
        coVerify(exactly = 1) {
            mutations.guardedUnmergeCopy(epicKey, any(), grouped.canonicalId.value, any())
        }
        viewModelStore.clear()
    }

    @Test
    fun `separate failure leaves grouped snapshot unchanged and account or gate change blocks mutation`() = runTest(dispatcher) {
        val grouped = card(
            name = "Still grouped",
            copyKeys = listOf(steamKey, epicKey),
        )
        val gateEnabled = AtomicBoolean(true)
        val gate = CanonicalPublicLibraryGate { gateEnabled.get() }
        val registry = mockk<OwnedCopyRuntimeRegistry>(relaxed = true)
        coEvery { registry.resolve(epicKey) } returns OwnedCopyRuntimeResult.Available(runtime(epicKey))
        val mutations = mockk<CanonicalMutationRepository>()
        coEvery {
            mutations.guardedUnmergeCopy(any(), any(), any(), any())
        } throws IllegalStateException("private transaction detail")
        val vm = viewModel(
            repository = repository(MutableStateFlow(listOf(grouped))),
            gateEnabled = true,
            gate = gate,
            readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
            runtimeRegistry = registry,
            mutationRepository = mutations,
        )
        runCurrent()

        assertEquals(
            CanonicalCopyChangeResult.TRANSACTION_FAILED,
            vm.separateCanonicalCopy(grouped.key, epicKey),
        )
        assertEquals(grouped, vm.canonicalCard(grouped.key))

        gateEnabled.set(false)
        assertEquals(
            CanonicalCopyChangeResult.PUBLIC_FEATURE_DISABLED,
            vm.separateCanonicalCopy(grouped.key, epicKey),
        )
        coVerify(exactly = 1) { registry.resolve(epicKey) }
        coVerify(exactly = 1) { mutations.guardedUnmergeCopy(any(), any(), any(), any()) }
        viewModelStore.clear()
    }

    @Test
    fun `guarded separate stale-state result maps to fixed copy-state feedback`() = runTest(dispatcher) {
        val grouped = card(
            name = "Stale grouped copy",
            copyKeys = listOf(steamKey, epicKey),
        )
        val registry = mockk<OwnedCopyRuntimeRegistry>(relaxed = true)
        coEvery { registry.resolve(epicKey) } returns OwnedCopyRuntimeResult.Available(runtime(epicKey))
        val mutations = mockk<CanonicalMutationRepository>()
        coEvery {
            mutations.guardedUnmergeCopy(any(), any(), any(), any())
        } returns CanonicalGuardedMutationResult.EXPECTED_STATE_CHANGED
        val vm = viewModel(
            repository = repository(MutableStateFlow(listOf(grouped))),
            gateEnabled = true,
            readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
            runtimeRegistry = registry,
            mutationRepository = mutations,
        )
        runCurrent()

        assertEquals(
            CanonicalCopyChangeResult.COPY_STATE_CHANGED,
            vm.separateCanonicalCopy(grouped.key, epicKey),
        )
        assertEquals(grouped, vm.canonicalCard(grouped.key))
        viewModelStore.clear()
    }

    @Test
    fun `runtime resolution failure returns fixed copy-state result without mutating`() = runTest(dispatcher) {
        val grouped = card(
            name = "Runtime failure",
            copyKeys = listOf(steamKey, epicKey),
        )
        val registry = mockk<OwnedCopyRuntimeRegistry>(relaxed = true)
        coEvery { registry.resolve(epicKey) } throws IllegalStateException("private runtime detail")
        val mutations = mockk<CanonicalMutationRepository>(relaxed = true)
        val vm = viewModel(
            repository = repository(MutableStateFlow(listOf(grouped))),
            gateEnabled = true,
            readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
            runtimeRegistry = registry,
            mutationRepository = mutations,
        )
        runCurrent()

        assertEquals(
            CanonicalCopyChangeResult.COPY_STATE_CHANGED,
            vm.separateCanonicalCopy(grouped.key, epicKey),
        )
        coVerify(exactly = 0) { mutations.guardedUnmergeCopy(any(), any(), any(), any()) }
        viewModelStore.clear()
    }

    @Test
    fun `reset runtime resolution failure returns fixed copy-state result without mutating`() = runTest(dispatcher) {
        val rejectedCopy = copy(
            key = gogKey,
            nativeTitle = "Rejected copy",
            confidence = MatchConfidence.REJECTED,
        ).copy(decisionSource = MatchDecisionSource.USER)
        val independent = card(name = "Rejected", copyKeys = listOf(gogKey)).copy(
            key = CanonicalCardKey.Independent(gogKey),
            copies = listOf(rejectedCopy),
            ownedSources = setOf(GameSource.GOG),
            preferredCopy = null,
        )
        val registry = mockk<OwnedCopyRuntimeRegistry>(relaxed = true)
        coEvery { registry.resolve(gogKey) } throws IllegalStateException("private runtime detail")
        val mutations = mockk<CanonicalMutationRepository>(relaxed = true)
        val vm = viewModel(
            repository = repository(MutableStateFlow(listOf(independent))),
            gateEnabled = true,
            readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
            runtimeRegistry = registry,
            mutationRepository = mutations,
        )
        runCurrent()

        assertEquals(
            CanonicalCopyChangeResult.COPY_STATE_CHANGED,
            vm.resetCanonicalDecision(independent.key, gogKey),
        )
        coVerify(exactly = 0) { mutations.guardedResetDecision(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        viewModelStore.clear()
    }

    @Test
    fun `guarded reset stale-state result maps to fixed copy-state feedback`() = runTest(dispatcher) {
        val rejectedCopy = copy(
            key = gogKey,
            nativeTitle = "Stale rejected copy",
            confidence = MatchConfidence.REJECTED,
        ).copy(decisionSource = MatchDecisionSource.USER)
        val independent = card(name = "Stale rejection", copyKeys = listOf(gogKey)).copy(
            key = CanonicalCardKey.Independent(gogKey),
            copies = listOf(rejectedCopy),
            ownedSources = setOf(GameSource.GOG),
            preferredCopy = null,
        )
        val registry = mockk<OwnedCopyRuntimeRegistry>(relaxed = true)
        coEvery { registry.resolve(gogKey) } returns OwnedCopyRuntimeResult.Unavailable(
            key = gogKey,
            reason = CopyUnavailableReason.SOURCE_READ_FAILED,
        )
        val mutations = mockk<CanonicalMutationRepository>()
        coEvery {
            mutations.guardedResetDecision(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns CanonicalGuardedMutationResult.EXPECTED_STATE_CHANGED
        val vm = viewModel(
            repository = repository(MutableStateFlow(listOf(independent))),
            gateEnabled = true,
            readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
            runtimeRegistry = registry,
            mutationRepository = mutations,
        )
        runCurrent()

        assertEquals(
            CanonicalCopyChangeResult.COPY_STATE_CHANGED,
            vm.resetCanonicalDecision(independent.key, gogKey),
        )
        assertEquals(independent, vm.canonicalCard(independent.key))
        viewModelStore.clear()
    }

    @Test
    fun `reset accepts exact current-account unavailable key but blocks hidden entitlement`() = runTest(dispatcher) {
        val rejectedCopy = copy(
            key = gogKey,
            nativeTitle = "Rejected copy",
            confidence = MatchConfidence.REJECTED,
        ).copy(decisionSource = MatchDecisionSource.USER)
        val independent = card(name = "Rejected", copyKeys = listOf(gogKey)).copy(
            key = CanonicalCardKey.Independent(gogKey),
            copies = listOf(rejectedCopy),
            ownedSources = setOf(GameSource.GOG),
            preferredCopy = null,
        )
        val registry = mockk<OwnedCopyRuntimeRegistry>(relaxed = true)
        coEvery { registry.resolve(gogKey) } returns OwnedCopyRuntimeResult.Unavailable(
            key = gogKey,
            reason = CopyUnavailableReason.SOURCE_READ_FAILED,
        )
        val mutations = mockk<CanonicalMutationRepository>(relaxed = true)
        coEvery {
            mutations.guardedResetDecision(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns CanonicalGuardedMutationResult.APPLIED
        val vm = viewModel(
            repository = repository(MutableStateFlow(listOf(independent))),
            gateEnabled = true,
            readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
            runtimeRegistry = registry,
            mutationRepository = mutations,
            projectionClock = CanonicalProjectionClock { 789L },
        )
        runCurrent()

        assertEquals(CanonicalCopyChangeResult.SUCCESS, vm.resetCanonicalDecision(independent.key, gogKey))
        coVerify(exactly = 1) {
            mutations.guardedResetDecision(
                gogKey,
                independent.canonicalId.value,
                rejectedCopy.matchMethod,
                rejectedCopy.confidence,
                rejectedCopy.decisionSource,
                rejectedCopy.decisionCandidateSteamAppId,
                rejectedCopy.decisionResolverVersion,
                rejectedCopy.decisionRevision,
                789L,
            )
        }

        coEvery { registry.resolve(gogKey) } returns OwnedCopyRuntimeResult.Hidden
        assertEquals(
            CanonicalCopyChangeResult.COPY_STATE_CHANGED,
            vm.resetCanonicalDecision(independent.key, gogKey),
        )
        coVerify(exactly = 1) {
            mutations.guardedResetDecision(
                gogKey,
                independent.canonicalId.value,
                rejectedCopy.matchMethod,
                rejectedCopy.confidence,
                rejectedCopy.decisionSource,
                rejectedCopy.decisionCandidateSteamAppId,
                rejectedCopy.decisionResolverVersion,
                rejectedCopy.decisionRevision,
                789L,
            )
        }
        viewModelStore.clear()
    }

    @Test
    fun `automatic selection clear is gate checked and failed write keeps preferred marker`() = runTest(dispatcher) {
        val grouped = card(
            name = "Preferred grouped",
            copyKeys = listOf(steamKey, gogKey),
            preferred = gogKey,
        )
        val preferences = mockk<PreferredCopyRepository>()
        coEvery { preferences.clearPreferredCopy(grouped.canonicalId, any()) } throws
            IllegalStateException("private preference detail")
        val vm = viewModel(
            repository = repository(MutableStateFlow(listOf(grouped))),
            gateEnabled = true,
            readiness = CanonicalProjectionReadiness().apply { markSucceeded() },
            preferredCopyRepository = preferences,
            projectionClock = CanonicalProjectionClock { 321L },
        )
        runCurrent()

        assertEquals(
            CanonicalCopyChangeResult.TRANSACTION_FAILED,
            vm.useAutomaticCopySelection(grouped.key),
        )
        assertEquals(gogKey, vm.canonicalCard(grouped.key)?.preferredCopy)
        coVerify(exactly = 1) { preferences.clearPreferredCopy(grouped.canonicalId, 321L) }
        viewModelStore.clear()
    }

    private fun stubSuccessfulRefreshPipeline(
        deviceStats: Map<GameSource, Map<String, DeviceGameStats>>,
        gpuStats: Map<GameSource, Map<String, DeviceGameStats>>,
    ) {
        coEvery { GameCompatibilityCache.clear() } just runs
        coEvery { DeviceGameStatsCache.clear() } just runs
        coEvery { GpuGameStatsCache.clear() } just runs
        coEvery { SteamService.refreshOwnedGamesFromServer() } returns 0
        every { GOGService.hasStoredCredentials(any()) } returns false
        every { GOGService.triggerLibrarySync(any()) } just runs
        every { AmazonService.hasStoredCredentials(any()) } returns false
        every { AmazonService.triggerLibrarySync(any()) } just runs
        coEvery { DeviceGameStatsCache.refreshIfStale(any(), any(), any()) } returns true
        coEvery { GpuGameStatsCache.refreshIfStale(any(), any()) } returns true
        every { DeviceGameStatsCache.getAll() } returns deviceStats
        every { GpuGameStatsCache.getAll() } returns gpuStats
    }

    private enum class RefreshFailureBoundary(val stage: String) {
        COMPATIBILITY_CLEAR("COMPATIBILITY_CACHE_CLEAR"),
        DEVICE_CLEAR("DEVICE_STATS_CACHE_CLEAR"),
        GPU_CLEAR("GPU_STATS_CACHE_CLEAR"),
        STEAM_SYNC("STEAM_SYNC"),
        GOG_CREDENTIALS("GOG_SYNC"),
        GOG_SYNC("GOG_SYNC"),
        AMAZON_CREDENTIALS("AMAZON_SYNC"),
        AMAZON_SYNC("AMAZON_SYNC"),
        DEVICE_REFRESH("DEVICE_STATS_REFRESH"),
        GPU_REFRESH("GPU_STATS_REFRESH"),
        DEVICE_READ("DEVICE_STATS_READ"),
        GPU_READ("GPU_STATS_READ"),
    }

    private fun project(
        cards: List<CanonicalLibraryCard>,
        state: LibraryState,
        pageSize: Int = 50,
        paginationPage: Int = 0,
        promotion: LibraryItem? = null,
        showRecommendations: Boolean = false,
        compatibility: (CanonicalLibraryCard) -> GameCompatibilityStatus? = { null },
    ): CanonicalLibraryPage = CanonicalLibraryFilter.project(
        cards = cards,
        state = state,
        paginationPage = paginationPage,
        pageSize = pageSize,
        promotion = promotion,
        showRecommendations = showRecommendations,
        compatibility = compatibility,
    )

    private var latestVm: LibraryViewModel? = null
    private val stateSnapshot: LibraryState get() = requireNotNull(latestVm).state.value

    private fun viewModel(
        repository: CanonicalLibraryRepository,
        gateEnabled: Boolean,
        readiness: CanonicalProjectionReadiness,
        runtimeRegistry: OwnedCopyRuntimeRegistry = mockk(relaxed = true),
        steamRows: Flow<List<SteamApp>> = emptyFlow(),
        gogRows: Flow<List<GOGGame>> = emptyFlow(),
        gate: CanonicalPublicLibraryGate = CanonicalPublicLibraryGate { gateEnabled },
        ioDispatcher: CoroutineDispatcher = dispatcher,
        actionRouter: OwnedCopyActionRouter = mockk(relaxed = true),
        preferredCopyRepository: PreferredCopyRepository = mockk(relaxed = true),
        mutationRepository: CanonicalMutationRepository = mockk(relaxed = true),
        projectionClock: CanonicalProjectionClock = CanonicalProjectionClock { 1L },
    ): LibraryViewModel {
        val history = mockk<LibraryPlayHistoryDao>(relaxed = true)
        every { history.getAll() } returns emptyFlow()
        val steam = mockk<SteamAppDao>(relaxed = true)
        every { steam.getAllOwnedApps(any(), any()) } returns steamRows
        val gog = mockk<GOGGameDao>(relaxed = true)
        every { gog.getAll() } returns gogRows
        val epic = mockk<EpicGameDao>(relaxed = true)
        every { epic.getAll() } returns emptyFlow()
        val amazon = mockk<AmazonGameDao>(relaxed = true)
        every { amazon.getAll() } returns emptyFlow()
        return LibraryViewModel(
            libraryPlayHistoryDao = history,
            steamAppDao = steam,
            gogGameDao = gog,
            epicGameDao = epic,
            amazonGameDao = amazon,
            context = context,
            canonicalLibraryRepository = repository,
            canonicalPublicLibraryGate = gate,
            canonicalProjectionReadiness = readiness,
            runtimeRegistry = runtimeRegistry,
            actionRouter = actionRouter,
            preferredCopyRepository = preferredCopyRepository,
            canonicalMutationRepository = mutationRepository,
            canonicalProjectionClock = projectionClock,
            canonicalDispatcher = ioDispatcher,
        ).also { viewModel ->
            latestVm = viewModel
            viewModelStore.put("latest", viewModel)
        }
    }

    private fun repository(cards: Flow<List<CanonicalLibraryCard>>): CanonicalLibraryRepository =
        mockk<CanonicalLibraryRepository>().also { repository ->
            every { repository.observeCards() } returns cards
        }

    private fun canonicalState(
        filters: EnumSet<AppFilter> = filters(AppFilter.GAME),
        search: String = "",
        sort: SortOption = SortOption.NAME_ASC,
        tab: LibraryTab = LibraryTab.ALL,
        showSteam: Boolean = true,
        showGog: Boolean = true,
        selectedCollections: Set<String> = emptySet(),
        collections: List<SteamCollection>? = null,
        deviceStats: Map<GameSource, Map<String, DeviceGameStats>> = emptyMap(),
        gpuStats: Map<GameSource, Map<String, DeviceGameStats>> = emptyMap(),
    ): LibraryState = LibraryState(
        appInfoSortType = filters,
        searchQuery = search,
        currentSortOption = sort,
        currentTab = tab,
        showSteamInLibrary = showSteam,
        showGOGInLibrary = showGog,
        showEpicInLibrary = true,
        showAmazonInLibrary = true,
        showCustomGamesInLibrary = true,
        selectedSteamCollectionIds = selectedCollections,
        steamCollections = collections,
        deviceGameStats = deviceStats,
        gpuGameStats = gpuStats,
    )

    private fun card(
        canonicalId: CanonicalGameId = canonicalId(1),
        name: String = "Canonical",
        appType: CanonicalAppType = CanonicalAppType.GAME,
        aliases: Set<String> = setOf(name),
        copyKeys: List<OwnedCopyKey> = listOf(steamKey),
        installedKeys: Set<OwnedCopyKey> = emptySet(),
        sharedKeys: Set<OwnedCopyKey> = emptySet(),
        sizes: Map<OwnedCopyKey, Long> = emptyMap(),
        lastPlayed: Map<OwnedCopyKey, Long> = emptyMap(),
        nativeTitles: Map<OwnedCopyKey, String> = emptyMap(),
        confidences: Map<OwnedCopyKey, MatchConfidence> = emptyMap(),
        preferred: OwnedCopyKey? = null,
        steamCollectionAppIds: Set<Int> = copyKeys
            .filter { it.source == GameSource.STEAM }
            .mapNotNull { it.stableSourceId.toIntOrNull() }
            .toSet(),
    ): CanonicalLibraryCard {
        val copies = copyKeys.map { key ->
            copy(
                key = key,
                nativeTitle = nativeTitles[key] ?: "$name ${key.source.name}",
                installed = key in installedKeys,
                shared = key in sharedKeys,
                size = sizes[key],
                lastPlayed = lastPlayed[key],
                confidence = confidences[key] ?: MatchConfidence.VERIFIED,
            )
        }
        return CanonicalLibraryCard(
            key = CanonicalCardKey.Grouped(canonicalId),
            canonicalId = canonicalId,
            displayName = name,
            appType = appType,
            iconUrl = "icon",
            capsuleImageUrl = "capsule",
            headerImageUrl = "header",
            heroImageUrl = "hero",
            gridHeroImageScale = 1f,
            aliases = aliases,
            ownedSources = copies.mapTo(linkedSetOf()) { it.source },
            copies = copies,
            preferredCopy = preferred,
            steamCollectionAppIds = steamCollectionAppIds,
            isShared = copies.any { it.isShared },
        )
    }

    private fun copy(
        key: OwnedCopyKey,
        nativeTitle: String,
        installed: Boolean = false,
        shared: Boolean = false,
        size: Long? = null,
        lastPlayed: Long? = null,
        confidence: MatchConfidence = MatchConfidence.VERIFIED,
    ): OwnedCopySummary = OwnedCopySummary(
        key = key,
        source = key.source,
        nativeTitle = nativeTitle,
        installPath = if (installed) "private-install-path" else null,
        installedSizeBytes = size,
        branchOrVersion = null,
        isInstalled = installed,
        isDownloading = false,
        hasPartialDownload = false,
        updateAvailable = false,
        isShared = shared,
        lastPlayedEpochMs = lastPlayed,
        playtimeMinutes = null,
        capabilities = setOf(OwnedCopyOperation.OPEN_SOURCE_DETAILS),
        unavailableReason = null,
        canSeparateMatch = true,
        matchMethod = MatchMethod.DIRECT_STEAM,
        confidence = confidence,
        decisionSource = MatchDecisionSource.AUTOMATIC,
        decisionCandidateSteamAppId = 456,
        decisionResolverVersion = 7,
        decisionRevision = 123L,
    )

    private fun runtime(key: OwnedCopyKey): OwnedCopyRuntime {
        val reference = when (key.source) {
            GameSource.STEAM -> SourceOwnedCopyReference.Steam(key, key.stableSourceId.toIntOrNull() ?: 10)
            GameSource.GOG -> SourceOwnedCopyReference.Gog(key, key.stableSourceId)
            GameSource.EPIC -> SourceOwnedCopyReference.Epic(key, 31, "current-namespace", "current-catalog")
            GameSource.AMAZON -> SourceOwnedCopyReference.Amazon(key, 41, "current-product", "current-entitlement")
            GameSource.CUSTOM_GAME -> SourceOwnedCopyReference.Custom(key, key.stableSourceId.toIntOrNull() ?: 50)
        }
        return OwnedCopyRuntime(
            key = key,
            reference = reference,
            libraryItem = LibraryItem(
                appId = "${key.source.name}_runtime",
                name = "Runtime item",
                gameSource = key.source,
            ),
            nativeTitle = "Runtime title",
            aliases = emptySet(),
            developerKey = "runtime-developer",
            releaseYear = 2024,
            appType = CanonicalAppType.GAME,
            genreKeys = setOf("runtime:genre"),
            tagIds = setOf(1),
            featureKeys = setOf("runtime:feature"),
            iconUrl = "",
            capsuleImageUrl = "",
            headerImageUrl = "",
            heroImageUrl = "",
            gridHeroImageScale = 1f,
            installPath = "private-runtime-path",
            installedSizeBytes = 1L,
            branchOrVersion = "runtime-branch",
            isInstalled = true,
            isDownloading = false,
            hasPartialDownload = false,
            updateAvailable = false,
            isShared = false,
            lastPlayedEpochMs = null,
            playtimeMinutes = null,
            capabilities = setOf(OwnedCopyOperation.OPEN_SOURCE_DETAILS),
        )
    }

    private fun filters(vararg filters: AppFilter): EnumSet<AppFilter> =
        if (filters.isEmpty()) EnumSet.noneOf(AppFilter::class.java)
        else EnumSet.copyOf(filters.toList())

    private fun key(source: GameSource, id: String): OwnedCopyKey = OwnedCopyKey(accountScope, source, id)

    private fun canonicalId(number: Int): CanonicalGameId = CanonicalGameId.parse(
        "00000000-0000-0000-0000-${number.toString().padStart(12, '0')}",
    )

    private fun stats(runs: Int, fps: Int, reviews: Int, session: Int): DeviceGameStats =
        DeviceGameStats(runs, fps, reviews, session)

    private fun invokeWaitingRequest(vm: LibraryViewModel, state: LibraryState): Job {
        val method = LibraryViewModel::class.java.getDeclaredMethod(
            "requestWaitingCanonical",
            Int::class.javaPrimitiveType,
            LibraryState::class.java,
        )
        method.isAccessible = true
        return method.invoke(vm, 0, state) as Job
    }

    private fun setLazyStringField(vm: LibraryViewModel, name: String, value: String) {
        val delegateField = LibraryViewModel::class.java.getDeclaredField("${name}\$delegate")
        delegateField.isAccessible = true
        val delegate = delegateField.get(vm)
        val valueField = delegate.javaClass.getDeclaredField("_value")
        valueField.isAccessible = true
        valueField.set(delegate, value)
    }

    private fun cancelJobField(vm: LibraryViewModel, name: String) {
        val field = LibraryViewModel::class.java.getDeclaredField(name)
        field.isAccessible = true
        (field.get(vm) as Job).cancel()
    }

    private fun Job.joinOnCurrentThread() = runBlocking { join() }

    private fun awaitPreference(condition: () -> Boolean) {
        repeat(200) {
            if (condition()) return
            Thread.sleep(10L)
        }
        assertTrue("Preference update did not settle", condition())
    }

    private fun awaitState(condition: (LibraryState) -> Boolean) {
        assertTrue("Library state did not settle", awaitCondition { condition(stateSnapshot) })
    }

    private fun awaitCondition(
        timeoutMs: Long = 5_000L,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10L)
        }
        return condition()
    }

    private fun awaitLatch(latch: CountDownLatch, name: String, timeoutMs: Long = 5_000L) {
        assertTrue("Timed out waiting for $name", latch.await(timeoutMs, TimeUnit.MILLISECONDS))
    }

    private fun awaitUninterruptibly(latch: CountDownLatch) {
        while (latch.count > 0L) {
            try {
                latch.await(100L, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                // Model source-native work that cannot honor coroutine cancellation.
            }
        }
    }

    private companion object {
        val accountScope = AccountScope("a".repeat(64))
        val steamKey = OwnedCopyKey(accountScope, GameSource.STEAM, "10")
        val gogKey = OwnedCopyKey(accountScope, GameSource.GOG, "11")
        val epicKey = OwnedCopyKey(accountScope, GameSource.EPIC, "epic-current")
        val otherCanonicalId = CanonicalGameId.parse("99999999-9999-9999-9999-999999999999")
    }
}
