package app.gamenative.library.canonical.catalog

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.data.canonical.StoreMatchEntity
import app.gamenative.db.dao.StoreMatchDao
import app.gamenative.library.canonical.CURRENT_RESOLVER_VERSION
import app.gamenative.library.canonical.CanonicalGuardedMutationResult
import app.gamenative.library.canonical.EpicCatalogFallbackWriter
import app.gamenative.library.canonical.ExpectedMatchState
import app.gamenative.library.canonical.SteamCatalogDecisionWriter
import app.gamenative.library.metadata.EpicCmsCatalogException
import app.gamenative.library.metadata.EpicCmsCatalogRequest
import app.gamenative.library.metadata.EpicCmsCatalogSource
import app.gamenative.library.metadata.MetadataClock
import app.gamenative.library.metadata.MetadataLocale
import app.gamenative.library.metadata.MetadataLocaleProvider
import app.gamenative.library.metadata.SteamCatalogRecord
import app.gamenative.library.metadata.SteamCatalogRecordSource
import app.gamenative.library.metadata.SteamRateLimitExhaustedException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SteamResolutionProgress(
    val completed: Int = 0,
    val total: Int = 0,
    val failed: Int = 0,
    val autoAccepted: Int = 0,
    val needsReview: Int = 0,
    val unmatched: Int = 0,
)

sealed interface SteamResolutionItemResult {
    data object AutoAccepted : SteamResolutionItemResult
    data object ReviewRequired : SteamResolutionItemResult
    data object Unmatched : SteamResolutionItemResult
    data object ExpectedStateChanged : SteamResolutionItemResult
    data object ProviderUnavailable : SteamResolutionItemResult
}

@Singleton
class SteamCatalogResolutionRepository @Inject internal constructor(
    private val storeMatchDao: StoreMatchDao,
    private val searchSource: SteamCatalogSearchSource,
    private val recordSource: SteamCatalogRecordSource,
    private val candidatePolicy: SteamCatalogCandidatePolicy,
    private val decisionWriter: SteamCatalogDecisionWriter,
    private val epicCatalogSource: EpicCmsCatalogSource,
    private val epicFallbackWriter: EpicCatalogFallbackWriter,
    private val localeProvider: MetadataLocaleProvider,
    private val diagnostics: SteamCatalogResolutionDiagnosticSink,
    private val acceptedIdentityEnrichment: SteamAcceptedIdentityEnrichmentSink,
    private val clock: MetadataClock,
) {
    private val scanMutex = Mutex()
    private val progressMutex = Mutex()
    private val mutableProgress = MutableStateFlow(SteamResolutionProgress())
    private val mutableIsScanning = MutableStateFlow(false)
    private val mutableKeyRequired = MutableStateFlow(false)
    private val candidateLists = ConcurrentHashMap<OwnedCopyKey, List<SteamCatalogCandidate>>()
    private val candidateRecords = ConcurrentHashMap<Int, ValidatedSteamCatalogRecord>()
    private var automaticScanCompleted = false

    val progress: StateFlow<SteamResolutionProgress> = mutableProgress.asStateFlow()
    val isScanning: StateFlow<Boolean> = mutableIsScanning.asStateFlow()
    val keyRequired: StateFlow<Boolean> = mutableKeyRequired.asStateFlow()

    suspend fun scanAutomatically(): SteamResolutionProgress = runAutomaticScan(force = false)

    suspend fun retryAutomatically(): SteamResolutionProgress {
        searchSource.requestImmediateRetry()
        return runAutomaticScan(force = true)
    }

    private suspend fun runAutomaticScan(force: Boolean): SteamResolutionProgress = scanMutex.withLock {
        mutableKeyRequired.value = false
        if (!force && automaticScanCompleted) return@withLock mutableProgress.value

        mutableIsScanning.value = true
        try {
            val selectedMatches = eligibleMatches(force)
            mutableProgress.value = SteamResolutionProgress(total = selectedMatches.size)
            if (selectedMatches.isNotEmpty()) {
                resolveWithBoundedWorkers(selectedMatches)
            }
            automaticScanCompleted = true
            mutableProgress.value
        } finally {
            mutableIsScanning.value = false
        }
    }

    suspend fun searchManually(
        expected: ExpectedMatchState,
        query: String,
    ): List<SteamCatalogCandidate> {
        val locale = localeProvider.current()
        val directSteamAppId = query.trim().toIntOrNull()?.takeIf { it > 0 }
        val candidates = if (directSteamAppId != null) {
            listOfNotNull(fetchDirectCandidate(directSteamAppId, locale))
        } else {
            searchSource.requestImmediateRetry()
            fetchCandidates(query, locale).candidates.take(MAX_VISIBLE_CANDIDATES)
        }
        candidateLists[expected.key] = candidates
        return candidates
    }

    fun candidatesFor(key: OwnedCopyKey): List<SteamCatalogCandidate> =
        candidateLists[key].orEmpty()

    fun validatedRecordFor(steamAppId: Int): SteamCatalogRecord? = candidateRecords[steamAppId]?.record

    suspend fun confirmCandidate(
        expected: ExpectedMatchState,
        steamAppId: Int,
    ): CanonicalGuardedMutationResult {
        val candidate = candidateLists[expected.key]
            ?.firstOrNull { it.steamAppId == steamAppId }
            ?: return CanonicalGuardedMutationResult.EXPECTED_STATE_CHANGED
        val result = decisionWriter.confirm(
            expected = expected,
            steamAppId = steamAppId,
            candidateAppType = candidate.appType,
            nowEpochMs = clock.nowEpochMs(),
        )
        if (result == CanonicalGuardedMutationResult.APPLIED) {
            enrichAcceptedIdentity(steamAppId)
        }
        return result
    }

    suspend fun rejectCandidate(
        expected: ExpectedMatchState,
        steamAppId: Int,
    ): CanonicalGuardedMutationResult = decisionWriter.reject(
        expected = expected,
        steamAppId = steamAppId,
        nowEpochMs = clock.nowEpochMs(),
    )

    suspend fun resetDecision(
        expected: ExpectedMatchState,
    ): CanonicalGuardedMutationResult = decisionWriter.reset(
        expected = expected,
        nowEpochMs = clock.nowEpochMs(),
    )

    private suspend fun eligibleMatches(force: Boolean): List<StoreMatchEntity> = storeMatchDao
        .getPresentWithoutSteamIdentity(GameSource.STEAM)
        .groupBy(StoreMatchEntity::canonicalId)
        .toSortedMap()
        .values
        .filterNot { matches ->
            matches.any { match -> match.decisionSource == MatchDecisionSource.USER } ||
                (!force && matches.any { match ->
                    match.matchMethod == MatchMethod.STEAM_CATALOG &&
                        match.confidence == MatchConfidence.REVIEW_REQUIRED &&
                        match.resolverVersion >= CURRENT_RESOLVER_VERSION
                })
        }
        .map(::strongestEvidence)

    private fun strongestEvidence(matches: List<StoreMatchEntity>): StoreMatchEntity = matches
        .sortedWith(
            compareByDescending<StoreMatchEntity>(::evidenceScore)
                .thenBy { it.source.name }
                .thenBy(StoreMatchEntity::stableSourceId)
                .thenBy(StoreMatchEntity::accountScope),
        )
        .first()

    private fun evidenceScore(match: StoreMatchEntity): Int =
        (if (match.evidenceAppType != CanonicalAppType.UNKNOWN) 4 else 0) +
            (if (match.evidenceDeveloperKey.isNotBlank()) 2 else 0) +
            (if (match.evidenceReleaseYear != null) 1 else 0)

    private suspend fun resolveWithBoundedWorkers(matches: List<StoreMatchEntity>) = coroutineScope {
        val nextIndex = AtomicInteger(0)
        repeat(minOf(MAX_CONCURRENCY, matches.size)) {
            launch {
                while (true) {
                    val index = nextIndex.getAndIncrement()
                    if (index >= matches.size) break
                    resolveAndRecord(matches[index])
                    if (index + 1 < matches.size) {
                        delay(AUTOMATIC_ITEM_INTERVAL_MS)
                    }
                }
            }
        }
    }

    private suspend fun resolveAndRecord(match: StoreMatchEntity) {
        val resolution = try {
            resolve(match)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ItemResolution(
                result = SteamResolutionItemResult.ProviderUnavailable,
                errorType = error.diagnosticCategory(),
            )
        }
        updateProgress(match.source, resolution)
    }

    private suspend fun resolve(match: StoreMatchEntity): ItemResolution {
        val expected = match.expectedState()
        val locale = localeProvider.current()
        val evidence = match.sourceEvidence()
        val fetched = fetchCandidates(match.evidenceDisplayName, locale)
        val candidates = candidatePolicy.rankCandidates(evidence, fetched.candidates)
        candidateLists[expected.key] = candidates.take(MAX_VISIBLE_CANDIDATES)
        if (fetched.incomplete) {
            val selected = candidates.firstOrNull()
                ?: throw SteamCatalogCandidateFetchException()
            return decisionWriter.recordCandidate(
                expected = expected,
                steamAppId = selected.steamAppId,
                resolverVersion = CURRENT_RESOLVER_VERSION,
                nowEpochMs = clock.nowEpochMs(),
            ).asItemResolution(SteamResolutionItemResult.ReviewRequired).copy(
                errorType = requireNotNull(fetched.incompleteReason),
            )
        }
        return when (val decision = candidatePolicy.evaluate(evidence, candidates)) {
            is CatalogDecision.AutoAccept -> {
                val selected = candidates.first { it.steamAppId == decision.steamAppId }
                val mutation = decisionWriter.acceptAutomatic(
                    expected = expected,
                    steamAppId = selected.steamAppId,
                    candidateAppType = selected.appType,
                    resolverVersion = CURRENT_RESOLVER_VERSION,
                    nowEpochMs = clock.nowEpochMs(),
                )
                if (mutation == CanonicalGuardedMutationResult.APPLIED) {
                    enrichAcceptedIdentity(selected.steamAppId)
                }
                mutation.asItemResolution(SteamResolutionItemResult.AutoAccepted)
            }

            is CatalogDecision.ReviewRequired -> {
                decisionWriter.recordCandidate(
                    expected = expected,
                    steamAppId = decision.steamAppIds.first(),
                    resolverVersion = CURRENT_RESOLVER_VERSION,
                    nowEpochMs = clock.nowEpochMs(),
                ).asItemResolution(SteamResolutionItemResult.ReviewRequired)
            }

            CatalogDecision.Unmatched -> {
                if (
                    match.source == GameSource.EPIC &&
                    match.evidenceAppType == CanonicalAppType.GAME
                ) {
                    val epicRecord = fetchEpicFallback(match, locale)
                    epicFallbackWriter.recordEpicFallback(
                        expected = expected,
                        resolverVersion = CURRENT_RESOLVER_VERSION,
                        nowEpochMs = clock.nowEpochMs(),
                        locale = locale,
                        record = epicRecord,
                    ).asItemResolution(SteamResolutionItemResult.Unmatched)
                } else {
                    decisionWriter.recordUnmatched(
                        expected = expected,
                        resolverVersion = CURRENT_RESOLVER_VERSION,
                        nowEpochMs = clock.nowEpochMs(),
                    ).asItemResolution(SteamResolutionItemResult.Unmatched)
                }
            }
        }
    }

    private suspend fun fetchEpicFallback(
        match: StoreMatchEntity,
        locale: MetadataLocale,
    ) = try {
        epicCatalogSource.fetch(
            EpicCmsCatalogRequest(
                stableSourceId = match.stableSourceId,
                sourceTitle = match.evidenceDisplayName,
                locale = locale,
            ),
        ) ?: throw EpicCmsCatalogException()
    } catch (error: CancellationException) {
        throw error
    } catch (error: SteamRateLimitExhaustedException) {
        throw error
    } catch (error: EpicCmsCatalogException) {
        throw error
    } catch (_: Exception) {
        throw EpicCmsCatalogException()
    }

    private suspend fun fetchCandidates(
        query: String,
        locale: MetadataLocale,
    ): CandidateFetchResult {
        var failedFetches = 0
        val searchResult = fetchSearchHits(query, locale)
        val candidates = searchResult.hits
            .distinctBy(SteamStoreSearchHit::steamAppId)
            .take(MAX_VALIDATED_HITS)
            .mapNotNull { hit ->
                val record = try {
                    recordSource.fetchRecord(hit.steamAppId, locale)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: SteamRateLimitExhaustedException) {
                    throw error
                } catch (_: Exception) {
                    failedFetches++
                    return@mapNotNull null
                }
                val validated = record?.takeIf { it.steamAppId == hit.steamAppId }
                if (validated == null) {
                    failedFetches++
                    null
                } else {
                    validated.toCandidate(hit, locale)
                }
            }
        if (failedFetches > 0 && candidates.isEmpty()) {
            throw SteamCatalogCandidateFetchException()
        }
        if (!searchResult.complete && candidates.isEmpty()) {
            throw SteamCatalogSearchIncompleteException()
        }
        val incompleteReason = when {
            !searchResult.complete -> SEARCH_INCOMPLETE
            failedFetches > 0 -> CANDIDATE_DETAILS_INCOMPLETE
            else -> null
        }
        return CandidateFetchResult(
            candidates = candidates,
            incompleteReason = incompleteReason,
        )
    }

    private suspend fun fetchSearchHits(
        query: String,
        locale: MetadataLocale,
    ): SteamCatalogSearchResult {
        val hitsById = linkedMapOf<Int, SteamStoreSearchHit>()
        var successfulQueries = 0
        var partial = false
        var firstFailure: Exception? = null
        SteamCatalogNormalization.titleQueries(query)
            .take(MAX_QUERY_FAN_OUT)
            .forEach { catalogQuery ->
                val result = try {
                    searchSource.searchResult(catalogQuery, locale)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: SteamRateLimitExhaustedException) {
                    throw error
                } catch (error: Exception) {
                    partial = true
                    if (firstFailure == null) firstFailure = error
                    return@forEach
                }
                successfulQueries++
                partial = partial || !result.complete
                result.hits.forEach { hit -> hitsById.putIfAbsent(hit.steamAppId, hit) }
            }
        if (successfulQueries == 0) {
            throw firstFailure ?: SteamCatalogSearchException()
        }
        return SteamCatalogSearchResult(
            hits = hitsById.values.take(MAX_VALIDATED_HITS),
            complete = !partial,
        )
    }

    private suspend fun fetchDirectCandidate(
        steamAppId: Int,
        locale: MetadataLocale,
    ): SteamCatalogCandidate? {
        val record = recordSource.fetchRecord(steamAppId, locale)
            ?.takeIf { it.steamAppId == steamAppId }
            ?: return null
        return record.toCandidate(
            SteamStoreSearchHit(
                steamAppId = steamAppId,
                title = record.metadata.title,
                headerImageUrl = record.metadata.headerImageUrl,
            ),
            locale,
        )
    }

    private fun SteamCatalogRecord.toCandidate(
        hit: SteamStoreSearchHit,
        locale: MetadataLocale,
    ): SteamCatalogCandidate {
        candidateRecords[steamAppId] = ValidatedSteamCatalogRecord(this, locale)
        return SteamCatalogCandidate(
            steamAppId = steamAppId,
            title = metadata.title,
            developer = metadata.developers.firstOrNull(),
            releaseYear = releaseYear,
            appType = appType,
            headerImageUrl = metadata.headerImageUrl ?: hit.headerImageUrl,
            publisher = metadata.publishers.firstOrNull(),
        )
    }

    private suspend fun enrichAcceptedIdentity(steamAppId: Int) {
        val validated = candidateRecords[steamAppId] ?: return
        try {
            acceptedIdentityEnrichment.enrich(
                trustedSteamAppId = steamAppId,
                locale = validated.locale,
                record = validated.record,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The accepted identity remains valid when optional enrichment fails.
        }
    }

    private suspend fun updateProgress(
        source: GameSource,
        resolution: ItemResolution,
    ) = progressMutex.withLock {
        val previous = mutableProgress.value
        val next = previous.copy(
            completed = previous.completed + 1,
            failed = previous.failed + if (
                resolution.result == SteamResolutionItemResult.ProviderUnavailable
            ) {
                1
            } else {
                0
            },
            autoAccepted = previous.autoAccepted + if (
                resolution.result == SteamResolutionItemResult.AutoAccepted
            ) {
                1
            } else {
                0
            },
            needsReview = previous.needsReview + if (
                resolution.result == SteamResolutionItemResult.ReviewRequired
            ) {
                1
            } else {
                0
            },
            unmatched = previous.unmatched + if (
                resolution.result == SteamResolutionItemResult.Unmatched
            ) {
                1
            } else {
                0
            },
        )
        mutableProgress.value = next
        diagnostics.recordSafely(
            SteamResolutionDiagnosticEvent(
                result = resolution.result,
                source = source,
                completed = next.completed,
                total = next.total,
                failed = next.failed,
                errorType = resolution.errorType,
            ),
        )
    }

    private fun Exception.diagnosticCategory(): String = when (this) {
        is SteamRateLimitExhaustedException -> RATE_LIMIT_EXHAUSTED
        is SteamCatalogSearchIncompleteException -> SEARCH_INCOMPLETE
        is SteamCatalogSearchException -> STORE_SEARCH_UNAVAILABLE
        is SteamCatalogCandidateFetchException -> APP_DETAILS_UNAVAILABLE
        is EpicCmsCatalogException -> EPIC_CMS_UNAVAILABLE
        else -> UNEXPECTED_FAILURE
    }

    private fun CanonicalGuardedMutationResult.asItemResolution(
        appliedResult: SteamResolutionItemResult,
    ): ItemResolution = ItemResolution(
        result = if (this == CanonicalGuardedMutationResult.APPLIED) {
            appliedResult
        } else {
            SteamResolutionItemResult.ExpectedStateChanged
        },
    )

    private fun StoreMatchEntity.sourceEvidence() = SourceCatalogEvidence(
        title = evidenceDisplayName,
        developer = evidenceDeveloperKey.takeIf(String::isNotBlank),
        releaseYear = evidenceReleaseYear,
        appType = evidenceAppType,
    )

    private fun StoreMatchEntity.expectedState() = ExpectedMatchState(
        key = OwnedCopyKey(
            accountScope = AccountScope.parse(accountScope),
            source = source,
            stableSourceId = stableSourceId,
        ),
        canonicalId = canonicalId,
        matchMethod = matchMethod,
        confidence = confidence,
        decisionSource = decisionSource,
        candidateSteamAppId = candidateSteamAppId,
        resolverVersion = resolverVersion,
        decisionRevision = matchedAt,
    )

    private fun SteamCatalogResolutionDiagnosticSink.recordSafely(
        event: SteamResolutionDiagnosticEvent,
    ) {
        try {
            record(event)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Diagnostics are best effort and never affect resolution.
        }
    }

    private data class ValidatedSteamCatalogRecord(
        val record: SteamCatalogRecord,
        val locale: MetadataLocale,
    )

    private data class CandidateFetchResult(
        val candidates: List<SteamCatalogCandidate>,
        val incompleteReason: String?,
    ) {
        val incomplete: Boolean = incompleteReason != null
    }

    private data class ItemResolution(
        val result: SteamResolutionItemResult,
        val errorType: String? = null,
    )

    private class SteamCatalogCandidateFetchException :
        IllegalStateException("Steam catalog candidate details unavailable")

    private class SteamCatalogSearchIncompleteException :
        IllegalStateException("Steam catalog search incomplete")

    private companion object {
        const val MAX_CONCURRENCY = 1
        const val MAX_QUERY_FAN_OUT = 3
        const val MAX_VALIDATED_HITS = 15
        const val MAX_VISIBLE_CANDIDATES = 5
        const val AUTOMATIC_ITEM_INTERVAL_MS = 350L
        const val STORE_SEARCH_UNAVAILABLE = "STORE_SEARCH_UNAVAILABLE"
        const val APP_DETAILS_UNAVAILABLE = "APP_DETAILS_UNAVAILABLE"
        const val SEARCH_INCOMPLETE = "SEARCH_INCOMPLETE"
        const val CANDIDATE_DETAILS_INCOMPLETE = "CANDIDATE_DETAILS_INCOMPLETE"
        const val RATE_LIMIT_EXHAUSTED = "RATE_LIMIT_EXHAUSTED"
        const val EPIC_CMS_UNAVAILABLE = "EPIC_CMS_UNAVAILABLE"
        const val UNEXPECTED_FAILURE = "UNEXPECTED_FAILURE"
    }
}
