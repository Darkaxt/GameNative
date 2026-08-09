package app.gamenative.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.data.canonical.StoreMatchEntity
import app.gamenative.db.dao.CanonicalLibraryAggregate
import app.gamenative.db.dao.CanonicalLibraryDao
import app.gamenative.library.canonical.CanonicalGuardedMutationResult
import app.gamenative.library.canonical.CanonicalProjectionReadiness
import app.gamenative.library.canonical.CanonicalPublicLibraryGate
import app.gamenative.library.canonical.ExpectedMatchState
import app.gamenative.library.canonical.OwnedCopySummary
import app.gamenative.library.canonical.catalog.SteamCatalogCandidate
import app.gamenative.library.canonical.catalog.SteamCatalogResolutionRepository
import app.gamenative.library.canonical.catalog.SteamResolutionProgress
import app.gamenative.service.steam.SteamWebApiKeyRepository
import app.gamenative.service.steam.SteamWebApiKeyStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch


data class SteamResolutionCoverage(
    val resolved: Int = 0,
    val eligible: Int = 0,
    val needsReview: Int = 0,
    val unmatched: Int = 0,
)

sealed interface SteamMatchPickerState {
    data object Closed : SteamMatchPickerState

    data class Searching(val expected: ExpectedMatchState) : SteamMatchPickerState

    data class Results(
        val expected: ExpectedMatchState,
        val candidates: List<SteamCatalogCandidate>,
        val selectedSteamAppId: Int?,
    ) : SteamMatchPickerState

    data class Empty(val expected: ExpectedMatchState) : SteamMatchPickerState

    data class Unavailable(val expected: ExpectedMatchState) : SteamMatchPickerState
}

data class SteamMatchUiState(
    val coverage: SteamResolutionCoverage = SteamResolutionCoverage(),
    val progress: SteamResolutionProgress = SteamResolutionProgress(),
    val isScanning: Boolean = false,
    val keyRequired: Boolean = false,
    val picker: SteamMatchPickerState = SteamMatchPickerState.Closed,
)

sealed interface SteamMatchEffect {
    data object RefreshRequired : SteamMatchEffect
}

enum class SteamMatchStatus {
    AUTOMATIC,
    USER_CONFIRMED,
    NEEDS_REVIEW,
    KEPT_SEPARATE,
    UNMATCHED,
    CHECKING,
    IMMUTABLE_STEAM,
}

internal fun OwnedCopySummary.steamMatchStatus(isScanning: Boolean): SteamMatchStatus = when {
    source == GameSource.STEAM || matchMethod == MatchMethod.DIRECT_STEAM ->
        SteamMatchStatus.IMMUTABLE_STEAM
    decisionSource == MatchDecisionSource.USER && confidence == MatchConfidence.VERIFIED ->
        SteamMatchStatus.USER_CONFIRMED
    decisionSource == MatchDecisionSource.USER && confidence == MatchConfidence.REJECTED ->
        SteamMatchStatus.KEPT_SEPARATE
    confidence == MatchConfidence.REVIEW_REQUIRED -> SteamMatchStatus.NEEDS_REVIEW
    isScanning && confidence == MatchConfidence.UNMATCHED -> SteamMatchStatus.CHECKING
    confidence == MatchConfidence.UNMATCHED -> SteamMatchStatus.UNMATCHED
    else -> SteamMatchStatus.AUTOMATIC
}

@HiltViewModel
class SteamMatchViewModel @Inject constructor(
    private val resolutionRepository: SteamCatalogResolutionRepository,
    private val steamWebApiKeyRepository: SteamWebApiKeyRepository,
    private val canonicalLibraryDao: CanonicalLibraryDao,
    private val publicLibraryGate: CanonicalPublicLibraryGate,
    private val projectionReadiness: CanonicalProjectionReadiness,
) : ViewModel() {
    private val aggregates = MutableStateFlow<List<CanonicalLibraryAggregate>>(emptyList())
    private val mutableState = MutableStateFlow(SteamMatchUiState())
    private val mutableQuery = MutableStateFlow("")
    private val mutableEffects = MutableSharedFlow<SteamMatchEffect>(extraBufferCapacity = 1)
    private var searchJob: Job? = null
    private var mutationJob: Job? = null

    val state: StateFlow<SteamMatchUiState> = mutableState.asStateFlow()
    val query: StateFlow<String> = mutableQuery.asStateFlow()
    val effects: SharedFlow<SteamMatchEffect> = mutableEffects.asSharedFlow()

    init {
        viewModelScope.launch {
            canonicalLibraryDao.observePresentGames().collectLatest { current ->
                aggregates.value = current
                mutableState.value = mutableState.value.copy(coverage = current.toCoverage())
            }
        }
        viewModelScope.launch {
            resolutionRepository.progress.collectLatest { current ->
                mutableState.value = mutableState.value.copy(progress = current)
            }
        }
        viewModelScope.launch {
            resolutionRepository.isScanning.collectLatest { current ->
                mutableState.value = mutableState.value.copy(isScanning = current)
            }
        }
        viewModelScope.launch {
            resolutionRepository.keyRequired.collectLatest { current ->
                mutableState.value = mutableState.value.copy(keyRequired = current)
            }
        }
        viewModelScope.launch {
            steamWebApiKeyRepository.changes.collectLatest { status ->
                if (!resolverGateOpen(projectionReadiness.isReady.value, aggregates.value)) {
                    return@collectLatest
                }
                when (status) {
                    SteamWebApiKeyStatus.CONFIGURED -> resolutionRepository.retryAutomatically()
                    SteamWebApiKeyStatus.NOT_CONFIGURED -> resolutionRepository.scanAutomatically()
                }
            }
        }
        viewModelScope.launch {
            combine(projectionReadiness.isReady, aggregates) { ready, current ->
                resolverGateOpen(ready, current)
            }.first { it }
            resolutionRepository.scanAutomatically()
        }
    }

    fun retryAutomatically() {
        viewModelScope.launch { resolutionRepository.retryAutomatically() }
    }

    fun openReviewMatches() {
        val reviewTarget = aggregates.value
            .asSequence()
            .flatMap { aggregate ->
                aggregate.matches.asSequence().map { match -> aggregate to match }
            }
            .filter { (_, match) ->
                match.isPresent &&
                    match.source != GameSource.STEAM &&
                    match.confidence == MatchConfidence.REVIEW_REQUIRED
            }
            .sortedWith(
                compareBy<Pair<CanonicalLibraryAggregate, StoreMatchEntity>>(
                    { it.first.game.canonicalId },
                    { it.second.source.name },
                    { it.second.stableSourceId },
                ),
            )
            .firstOrNull()
            ?: return
        openMatch(reviewTarget.second.key())
    }

    fun openMatch(key: OwnedCopyKey) {
        val match = aggregates.value.asSequence()
            .flatMap { it.matches.asSequence() }
            .firstOrNull { candidate ->
                candidate.isPresent && candidate.source != GameSource.STEAM && candidate.key() == key
            }
            ?: return
        val expected = match.expectedState()
        val candidates = resolutionRepository.candidatesFor(key)
        searchJob?.cancel()
        mutationJob?.cancel()
        mutableQuery.value = match.evidenceDisplayName
        mutableState.value = mutableState.value.copy(
            picker = if (candidates.isEmpty()) {
                SteamMatchPickerState.Empty(expected)
            } else {
                SteamMatchPickerState.Results(expected, candidates, selectedSteamAppId = null)
            },
        )
    }

    fun updateQuery(value: String) {
        searchJob?.cancel()
        mutableQuery.value = value
        val expected = mutableState.value.picker.expectedOrNull() ?: return
        if (mutableState.value.picker is SteamMatchPickerState.Searching) {
            mutableState.value = mutableState.value.copy(picker = SteamMatchPickerState.Empty(expected))
        }
    }

    fun search() {
        val expected = mutableState.value.picker.expectedOrNull() ?: return
        val querySnapshot = mutableQuery.value.trim()
        searchJob?.cancel()
        if (querySnapshot.isEmpty()) {
            mutableState.value = mutableState.value.copy(picker = SteamMatchPickerState.Empty(expected))
            return
        }
        mutableState.value = mutableState.value.copy(picker = SteamMatchPickerState.Searching(expected))
        searchJob = viewModelScope.launch {
            try {
                val candidates = resolutionRepository.searchManually(expected, querySnapshot)
                if (mutableState.value.picker.expectedOrNull() != expected) return@launch
                mutableState.value = mutableState.value.copy(
                    picker = if (candidates.isEmpty()) {
                        SteamMatchPickerState.Empty(expected)
                    } else {
                        SteamMatchPickerState.Results(expected, candidates, selectedSteamAppId = null)
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (mutableState.value.picker.expectedOrNull() == expected) {
                    mutableState.value = mutableState.value.copy(
                        picker = SteamMatchPickerState.Unavailable(expected),
                    )
                }
            }
        }
    }

    fun selectCandidate(steamAppId: Int) {
        val picker = mutableState.value.picker as? SteamMatchPickerState.Results ?: return
        if (picker.candidates.none { it.steamAppId == steamAppId }) return
        mutableState.value = mutableState.value.copy(
            picker = picker.copy(selectedSteamAppId = steamAppId),
        )
    }

    fun confirmSelected() {
        val picker = mutableState.value.picker as? SteamMatchPickerState.Results ?: return
        val steamAppId = picker.selectedSteamAppId ?: return
        launchMutation {
            resolutionRepository.confirmCandidate(picker.expected, steamAppId)
        }
    }

    fun keepSeparate() {
        val picker = mutableState.value.picker
        val expected = picker.expectedOrNull() ?: return
        val steamAppId = (picker as? SteamMatchPickerState.Results)?.selectedSteamAppId
            ?: expected.candidateSteamAppId
            ?: aggregates.value.firstNotNullOfOrNull { aggregate ->
                aggregate.takeIf { it.matches.any { match -> match.key() == expected.key } }
                    ?.game
                    ?.steamAppId
            }
            ?: return
        launchMutation {
            resolutionRepository.rejectCandidate(expected, steamAppId)
        }
    }

    fun resetDecision() {
        val expected = mutableState.value.picker.expectedOrNull() ?: return
        mutationJob?.cancel()
        mutationJob = viewModelScope.launch {
            when (resolutionRepository.resetDecision(expected)) {
                CanonicalGuardedMutationResult.APPLIED -> {
                    clearPicker(cancelMutation = false)
                    resolutionRepository.retryAutomatically()
                }

                else -> closeForRefresh()
            }
        }
    }

    fun close() {
        clearPicker(cancelMutation = true)
    }

    private fun launchMutation(
        mutation: suspend () -> CanonicalGuardedMutationResult,
    ) {
        mutationJob?.cancel()
        mutationJob = viewModelScope.launch {
            when (mutation()) {
                CanonicalGuardedMutationResult.APPLIED -> clearPicker(cancelMutation = false)
                else -> closeForRefresh()
            }
        }
    }

    private fun closeForRefresh() {
        clearPicker(cancelMutation = false)
        mutableEffects.tryEmit(SteamMatchEffect.RefreshRequired)
    }

    private fun clearPicker(cancelMutation: Boolean) {
        searchJob?.cancel()
        searchJob = null
        if (cancelMutation) {
            mutationJob?.cancel()
            mutationJob = null
        }
        mutableQuery.value = ""
        mutableState.value = mutableState.value.copy(picker = SteamMatchPickerState.Closed)
    }

    private fun resolverGateOpen(
        projectionReady: Boolean,
        current: List<CanonicalLibraryAggregate>,
    ): Boolean = projectionReady && publicLibraryGate.isEnabled() && current.hasEligibleCopy()

    private fun List<CanonicalLibraryAggregate>.hasEligibleCopy(): Boolean = any { aggregate ->
        aggregate.matches.any { match -> match.isPresent && match.source != GameSource.STEAM }
    }

    private fun List<CanonicalLibraryAggregate>.toCoverage(): SteamResolutionCoverage {
        val eligible = filter { aggregate ->
            aggregate.matches.any { match -> match.isPresent && match.source != GameSource.STEAM }
        }
        return SteamResolutionCoverage(
            resolved = eligible.count { aggregate -> aggregate.game.steamAppId?.let { it > 0 } == true },
            eligible = eligible.size,
            needsReview = eligible.count { aggregate ->
                aggregate.matches.any { match ->
                    match.isPresent &&
                        match.source != GameSource.STEAM &&
                        match.confidence == MatchConfidence.REVIEW_REQUIRED
                }
            },
            unmatched = eligible.count { aggregate ->
                aggregate.game.steamAppId == null && aggregate.matches.any { match ->
                    match.isPresent &&
                        match.source != GameSource.STEAM &&
                        match.confidence == MatchConfidence.UNMATCHED
                }
            },
        )
    }

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

    private fun SteamMatchPickerState.expectedOrNull(): ExpectedMatchState? = when (this) {
        SteamMatchPickerState.Closed -> null
        is SteamMatchPickerState.Searching -> expected
        is SteamMatchPickerState.Results -> expected
        is SteamMatchPickerState.Empty -> expected
        is SteamMatchPickerState.Unavailable -> expected
    }
}
