package app.gamenative.ui.model

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameEntity
import app.gamenative.data.canonical.ClassificationState
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.data.canonical.StoreMatchEntity
import app.gamenative.db.dao.CanonicalLibraryAggregate
import app.gamenative.db.dao.CanonicalLibraryDao
import app.gamenative.library.canonical.CURRENT_RESOLVER_VERSION
import app.gamenative.library.canonical.CanonicalGuardedMutationResult
import app.gamenative.library.canonical.CanonicalProjectionReadiness
import app.gamenative.library.canonical.CanonicalPublicLibraryGate
import app.gamenative.library.canonical.ExpectedMatchState
import app.gamenative.library.canonical.catalog.SteamCatalogCandidate
import app.gamenative.library.canonical.catalog.SteamCatalogResolutionRepository
import app.gamenative.library.canonical.catalog.SteamResolutionProgress
import app.gamenative.service.steam.SteamWebApiKeyRepository
import app.gamenative.service.steam.SteamWebApiKeyStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SteamMatchViewModelTest {
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var dispatcher: TestDispatcher
    private lateinit var aggregates: MutableStateFlow<List<CanonicalLibraryAggregate>>
    private lateinit var progress: MutableStateFlow<SteamResolutionProgress>
    private lateinit var scanning: MutableStateFlow<Boolean>
    private lateinit var keyRequired: MutableStateFlow<Boolean>
    private lateinit var keyStatusChanges: MutableSharedFlow<SteamWebApiKeyStatus>
    private lateinit var keyRepository: SteamWebApiKeyRepository
    private lateinit var repository: SteamCatalogResolutionRepository
    private lateinit var readiness: CanonicalProjectionReadiness
    private var publicLibraryEnabled = true

    @Before
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        dispatcher = StandardTestDispatcher(scheduler)
        Dispatchers.setMain(dispatcher)
        aggregates = MutableStateFlow(emptyList())
        progress = MutableStateFlow(SteamResolutionProgress())
        scanning = MutableStateFlow(false)
        keyRequired = MutableStateFlow(false)
        keyStatusChanges = MutableSharedFlow(extraBufferCapacity = 1)
        keyRepository = mockk(relaxed = true)
        every { keyRepository.changes } returns keyStatusChanges
        repository = mockk(relaxed = true)
        every { repository.progress } returns progress
        every { repository.isScanning } returns scanning
        every { repository.keyRequired } returns keyRequired
        every { repository.candidatesFor(any()) } returns emptyList()
        coEvery { repository.scanAutomatically() } returns SteamResolutionProgress()
        coEvery { repository.retryAutomatically() } returns SteamResolutionProgress()
        readiness = CanonicalProjectionReadiness()
        publicLibraryEnabled = true
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun firstEligibleObservationWaitsForReadinessAndPublicLibraryThenStartsOneScan() = runTest(scheduler) {
        publicLibraryEnabled = false
        aggregates.value = listOf(aggregate(game(1), match(1, GameSource.GOG)))
        val viewModel = viewModel()
        readiness.markSucceeded()
        runCurrent()

        coVerify(exactly = 0) { repository.scanAutomatically() }

        publicLibraryEnabled = true
        aggregates.value = listOf(aggregate(game(1).copy(updatedAt = 2), match(1, GameSource.GOG)))
        runCurrent()
        aggregates.value = listOf(aggregate(game(1).copy(updatedAt = 3), match(1, GameSource.GOG)))
        runCurrent()

        coVerify(exactly = 1) { repository.scanAutomatically() }
        assertEquals(1, viewModel.state.value.coverage.eligible)
    }

    @Test
    fun keyRequiredStateIsExposedToTheResolverUi() = runTest(scheduler) {
        val viewModel = viewModel()
        runCurrent()

        keyRequired.value = true
        runCurrent()

        assertEquals(true, viewModel.state.value.keyRequired)
    }

    @Test
    fun savedOrDeletedKeyRefreshesTheReadyResolver() = runTest(scheduler) {
        aggregates.value = listOf(aggregate(game(1), match(1, GameSource.GOG)))
        readiness.markSucceeded()
        viewModel()
        runCurrent()

        keyStatusChanges.emit(SteamWebApiKeyStatus.CONFIGURED)
        runCurrent()
        keyStatusChanges.emit(SteamWebApiKeyStatus.NOT_CONFIGURED)
        runCurrent()

        coVerify(exactly = 1) { repository.retryAutomatically() }
        coVerify(exactly = 2) { repository.scanAutomatically() }
    }

    @Test
    fun keyChangeBeforeReadinessUsesTheNormalResolverGate() = runTest(scheduler) {
        viewModel()
        runCurrent()

        keyStatusChanges.emit(SteamWebApiKeyStatus.CONFIGURED)
        runCurrent()

        coVerify(exactly = 0) { repository.retryAutomatically() }
        aggregates.value = listOf(aggregate(game(1), match(1, GameSource.GOG)))
        readiness.markSucceeded()
        runCurrent()
        coVerify(exactly = 1) { repository.scanAutomatically() }
    }

    @Test
    fun coverageAndReviewTargetUseUnfilteredPresentNonSteamCanonicals() = runTest(scheduler) {
        val resolved = match(1, GameSource.GOG).copy(
            matchMethod = MatchMethod.STEAM_CATALOG,
            confidence = MatchConfidence.HIGH,
            candidateSteamAppId = 11,
        )
        val review = match(2, GameSource.EPIC).copy(
            matchMethod = MatchMethod.STEAM_CATALOG,
            confidence = MatchConfidence.REVIEW_REQUIRED,
            candidateSteamAppId = 22,
        )
        val unmatched = match(3, GameSource.AMAZON)
        val rejected = match(4, GameSource.GOG).copy(
            matchMethod = MatchMethod.MANUAL,
            confidence = MatchConfidence.REJECTED,
            decisionSource = MatchDecisionSource.USER,
            candidateSteamAppId = 44,
        )
        val directSteam = match(5, GameSource.STEAM).copy(
            matchMethod = MatchMethod.DIRECT_STEAM,
            confidence = MatchConfidence.VERIFIED,
        )
        aggregates.value = listOf(
            aggregate(game(1, steamAppId = 11), resolved),
            aggregate(game(2), review),
            aggregate(game(3), unmatched),
            aggregate(game(4), rejected),
            aggregate(game(5, steamAppId = 55), directSteam),
        )
        val viewModel = viewModel()
        runCurrent()

        assertEquals(
            SteamResolutionCoverage(
                resolved = 1,
                eligible = 4,
                needsReview = 1,
                unmatched = 1,
            ),
            viewModel.state.value.coverage,
        )

        viewModel.openReviewMatches()

        assertEquals(
            SteamMatchPickerState.Empty(review.expectedState()),
            viewModel.state.value.picker,
        )
    }

    @Test
    fun openingPickerPrefillsSourceTitleAndRetainsExactExpectedState() = runTest(scheduler) {
        val source = match(1, GameSource.GOG).copy(
            evidenceDisplayName = "Source title",
            matchMethod = MatchMethod.STEAM_CATALOG,
            confidence = MatchConfidence.REVIEW_REQUIRED,
            candidateSteamAppId = 42,
        )
        val candidate = candidate(42)
        aggregates.value = listOf(aggregate(game(1), source))
        every { repository.candidatesFor(source.key()) } returns listOf(candidate)
        val viewModel = viewModel()
        runCurrent()

        viewModel.openMatch(source.key())

        assertEquals("Source title", viewModel.query.value)
        assertEquals(
            SteamMatchPickerState.Results(
                expected = source.expectedState(),
                candidates = listOf(candidate),
                selectedSteamAppId = null,
            ),
            viewModel.state.value.picker,
        )
    }

    @Test
    fun changingQueryCancelsPriorSearchAndNumericQueryIsPassedUnchanged() = runTest(scheduler) {
        val source = match(1, GameSource.GOG)
        aggregates.value = listOf(aggregate(game(1), source))
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        coEvery { repository.searchManually(source.expectedState(), "first") } coAnswers {
            firstStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                firstCancelled.complete(Unit)
            }
        }
        coEvery { repository.searchManually(source.expectedState(), "42") } returns listOf(candidate(42))
        val viewModel = viewModel()
        runCurrent()
        viewModel.openMatch(source.key())
        viewModel.updateQuery("first")
        viewModel.search()
        runCurrent()
        firstStarted.await()

        viewModel.updateQuery("42")
        runCurrent()
        firstCancelled.await()
        viewModel.search()
        runCurrent()

        coVerify(exactly = 1) { repository.searchManually(source.expectedState(), "42") }
        val picker = viewModel.state.value.picker as SteamMatchPickerState.Results
        assertEquals(listOf(candidate(42)), picker.candidates)
    }

    @Test
    fun confirmRejectAndResetUseCapturedStateAndResetImmediatelyRetries() = runTest(scheduler) {
        val source = match(1, GameSource.GOG).copy(
            matchMethod = MatchMethod.MANUAL,
            confidence = MatchConfidence.REJECTED,
            decisionSource = MatchDecisionSource.USER,
            candidateSteamAppId = 42,
        )
        aggregates.value = listOf(aggregate(game(1, steamAppId = 42), source))
        every { repository.candidatesFor(source.key()) } returns listOf(candidate(42))
        coEvery { repository.confirmCandidate(source.expectedState(), 42) } returns
            CanonicalGuardedMutationResult.APPLIED
        coEvery { repository.rejectCandidate(source.expectedState(), 42) } returns
            CanonicalGuardedMutationResult.APPLIED
        coEvery { repository.resetDecision(source.expectedState()) } returns
            CanonicalGuardedMutationResult.APPLIED
        val viewModel = viewModel()
        runCurrent()

        viewModel.openMatch(source.key())
        viewModel.selectCandidate(42)
        viewModel.confirmSelected()
        runCurrent()
        viewModel.openMatch(source.key())
        viewModel.keepSeparate()
        runCurrent()
        viewModel.openMatch(source.key())
        viewModel.resetDecision()
        runCurrent()

        coVerify(exactly = 1) { repository.confirmCandidate(source.expectedState(), 42) }
        coVerify(exactly = 1) { repository.rejectCandidate(source.expectedState(), 42) }
        coVerify(exactly = 1) { repository.resetDecision(source.expectedState()) }
        coVerify(exactly = 1) { repository.retryAutomatically() }
        assertEquals(SteamMatchPickerState.Closed, viewModel.state.value.picker)
    }

    @Test
    fun staleMutationClosesPickerEmitsFixedEffectAndCloseClearsPrivateState() = runTest(scheduler) {
        val source = match(1, GameSource.GOG)
        aggregates.value = listOf(aggregate(game(1), source))
        every { repository.candidatesFor(source.key()) } returns listOf(candidate(42))
        coEvery { repository.confirmCandidate(source.expectedState(), 42) } returns
            CanonicalGuardedMutationResult.EXPECTED_STATE_CHANGED
        val viewModel = viewModel()
        runCurrent()
        viewModel.openMatch(source.key())
        viewModel.selectCandidate(42)
        val effect = async { viewModel.effects.first() }
        runCurrent()

        viewModel.confirmSelected()
        runCurrent()

        assertEquals(SteamMatchEffect.RefreshRequired, effect.await())
        assertEquals(SteamMatchPickerState.Closed, viewModel.state.value.picker)
        assertEquals("", viewModel.query.value)
    }

    private fun viewModel() = SteamMatchViewModel(
        resolutionRepository = repository,
        steamWebApiKeyRepository = keyRepository,
        canonicalLibraryDao = object : CanonicalLibraryDao {
            override fun observePresentGames(): Flow<List<CanonicalLibraryAggregate>> = aggregates
        },
        publicLibraryGate = CanonicalPublicLibraryGate { publicLibraryEnabled },
        projectionReadiness = readiness,
    )

    private fun aggregate(
        game: CanonicalGameEntity,
        vararg matches: StoreMatchEntity,
    ) = CanonicalLibraryAggregate(
        game = game,
        matches = matches.toList(),
        preferences = emptyList(),
    )

    private fun game(index: Long, steamAppId: Int? = null) = CanonicalGameEntity(
        canonicalId = UUID(0, index).toString(),
        steamAppId = steamAppId,
        displayName = "Game $index",
        matchTitleKey = "game $index",
        primaryMetadataSource = GameSource.GOG,
        appType = CanonicalAppType.GAME,
        releaseYear = 2020,
        developerKey = "studio",
        classificationState = ClassificationState.UNCLASSIFIED,
        steamReviewCount = null,
        createdAt = index,
        updatedAt = index,
    )

    private fun match(index: Long, source: GameSource) = StoreMatchEntity(
        accountScope = ACCOUNT_SCOPE.value,
        source = source,
        stableSourceId = "copy-$index",
        canonicalId = UUID(0, index).toString(),
        candidateSteamAppId = null,
        matchMethod = MatchMethod.UNMATCHED,
        confidence = MatchConfidence.UNMATCHED,
        decisionSource = MatchDecisionSource.AUTOMATIC,
        resolverVersion = CURRENT_RESOLVER_VERSION,
        matchedAt = index,
        isPresent = true,
        evidenceDisplayName = "Game $index",
        evidenceTitleKey = "game $index",
        evidenceDeveloperKey = "studio",
        evidenceReleaseYear = 2020,
        evidenceAppType = CanonicalAppType.GAME,
    )

    private fun StoreMatchEntity.key() = OwnedCopyKey(
        accountScope = AccountScope.parse(accountScope),
        source = source,
        stableSourceId = stableSourceId,
    )

    private fun StoreMatchEntity.expectedState() = ExpectedMatchState(
        key = key(),
        canonicalId = canonicalId,
        matchMethod = matchMethod,
        confidence = confidence,
        decisionSource = decisionSource,
        candidateSteamAppId = candidateSteamAppId,
        resolverVersion = resolverVersion,
        decisionRevision = matchedAt,
    )

    private fun candidate(steamAppId: Int) = SteamCatalogCandidate(
        steamAppId = steamAppId,
        title = "Candidate $steamAppId",
        developer = "Studio",
        releaseYear = 2020,
        appType = CanonicalAppType.GAME,
        headerImageUrl = null,
    )

    private companion object {
        val ACCOUNT_SCOPE: AccountScope = AccountScope.parse("a".repeat(64))
    }
}
