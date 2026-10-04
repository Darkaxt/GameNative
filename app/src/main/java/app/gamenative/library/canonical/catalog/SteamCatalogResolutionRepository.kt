package app.gamenative.library.canonical.catalog

import androidx.room.withTransaction
import app.gamenative.db.PluviaDatabase
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.data.canonical.SteamCatalogResolutionAttemptEntity
import app.gamenative.data.canonical.SteamCatalogResolutionStatus
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
import app.gamenative.library.metadata.PcGamingWikiCurrentAvailabilityEvidence
import app.gamenative.library.metadata.PcGamingWikiCurrentAvailabilityRequest
import app.gamenative.library.metadata.PcGamingWikiCurrentAvailabilityResult
import app.gamenative.library.metadata.PcGamingWikiCurrentAvailabilitySource
import app.gamenative.library.metadata.SteamCatalogRecord
import app.gamenative.library.metadata.SteamCatalogRecordSource
import app.gamenative.library.metadata.SteamRateLimitExhaustedException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

enum class EpicPresentationOutcome {
    NOT_APPLICABLE,
    EPIC_CMS_PERSISTED,
    EPIC_CMS_UNAVAILABLE,
}

sealed interface SteamResolutionItemResult {
    data object AutoAccepted : SteamResolutionItemResult
    data object ReviewRequired : SteamResolutionItemResult

    data class CompleteNoPlausibleSteamMatch(
        val epicPresentation: EpicPresentationOutcome = EpicPresentationOutcome.NOT_APPLICABLE,
        val pcGamingWikiEvidence: PcGamingWikiCurrentAvailabilityEvidence? = null,
    ) : SteamResolutionItemResult

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
    private val pcGamingWikiSource: PcGamingWikiCurrentAvailabilitySource,
    private val epicCatalogSource: EpicCmsCatalogSource,
    private val epicFallbackWriter: EpicCatalogFallbackWriter,
    private val localeProvider: MetadataLocaleProvider,
    private val diagnostics: SteamCatalogResolutionDiagnosticSink,
    private val acceptedIdentityEnrichment: SteamAcceptedIdentityEnrichmentSink,
    private val clock: MetadataClock,
    private val db: PluviaDatabase,
    private val resumeScheduler: SteamCatalogResumeScheduler,
) {
    private val resolutionDao = db.steamCatalogResolutionDao()
    private val scanMutex = Mutex()
    private val progressMutex = Mutex()
    private val mutableProgress = MutableStateFlow(SteamResolutionProgress())
    private val mutableIsScanning = MutableStateFlow(false)
    private val mutableKeyRequired = MutableStateFlow(false)
    private val candidateLists = ConcurrentHashMap<OwnedCopyKey, List<SteamCatalogCandidate>>()
    private val candidateRecords = ConcurrentHashMap<Int, ValidatedSteamCatalogRecord>()

    val progress: StateFlow<SteamResolutionProgress> = mutableProgress.asStateFlow()
    val isScanning: StateFlow<Boolean> = mutableIsScanning.asStateFlow()
    val keyRequired: StateFlow<Boolean> = mutableKeyRequired.asStateFlow()

    suspend fun scanAutomatically(): SteamResolutionProgress = runAutomaticScan(force = false)

    suspend fun retryAutomatically(): SteamResolutionProgress {
        searchSource.requestImmediateRetry()
        return runAutomaticScan(force = true)
    }

    internal suspend fun resumeAutomatically(): Boolean {
        runAutomaticScan(force = false, scheduleResume = false)
        return eligibleMatches(force = false).isEmpty()
    }

    private suspend fun runAutomaticScan(
        force: Boolean,
        scheduleResume: Boolean = true,
    ): SteamResolutionProgress = scanMutex.withLock {
        val matches = eligibleMatches(force)
        if (!scheduleResume && matches.isEmpty()) return@withLock mutableProgress.value
        mutableKeyRequired.value = false
        mutableIsScanning.value = true
        try {
            mutableProgress.value = SteamResolutionProgress(total = matches.size)
            if (scheduleResume && matches.isNotEmpty()) {
                try {
                    resumeScheduler.enqueue()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    matches.forEach { match ->
                        updateProgress(match.source, ItemResolution(
                            SteamResolutionItemResult.ProviderUnavailable, RESUME_SCHEDULING_FAILED,
                        ))
                    }
                    return@withLock mutableProgress.value
                }
            }
            val attempts = matches.mapNotNull { prepareAttempt(it, force) }
            mutableProgress.value = SteamResolutionProgress(total = attempts.size)
            if (attempts.isNotEmpty()) {
                resolveSerially(attempts)
            }
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
    ): CanonicalGuardedMutationResult = if (candidateLists[expected.key].orEmpty().any { it.steamAppId == steamAppId }) {
        decisionWriter.rejectValidatedCandidate(expected, steamAppId, clock.nowEpochMs())
    } else {
        decisionWriter.reject(expected, steamAppId, clock.nowEpochMs())
    }

    suspend fun resetDecision(
        expected: ExpectedMatchState,
    ): CanonicalGuardedMutationResult = decisionWriter.reset(
        expected = expected,
        nowEpochMs = clock.nowEpochMs(),
    )

    private suspend fun eligibleMatches(force: Boolean): List<StoreMatchEntity> = storeMatchDao
        .getPresentWithoutSteamIdentity(GameSource.STEAM)
        .filter { match -> match.ownedCopyKeyOrNull() != null }
        .groupBy(StoreMatchEntity::canonicalId)
        .toSortedMap()
        .values
        .filterNot { matches ->
            matches.any { match -> match.decisionSource == MatchDecisionSource.USER } ||
                (!force && resolutionDao.getAttempt(strongestEvidence(matches).canonicalId).let { attempt ->
                    if (attempt == null) {
                        matches.any { match ->
                            match.matchMethod == MatchMethod.STEAM_CATALOG &&
                                match.confidence == MatchConfidence.REVIEW_REQUIRED &&
                                match.resolverVersion >= CURRENT_RESOLVER_VERSION
                        }
                    } else {
                        attempt.evidenceHash == evidenceHash(strongestEvidence(matches), localeProvider.current()) &&
                            attempt.resolverVersion == CURRENT_RESOLVER_VERSION &&
                            attempt.status !in setOf(SteamCatalogResolutionStatus.PENDING, SteamCatalogResolutionStatus.FAILED)
                    }
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

    private suspend fun resolveSerially(attempts: List<ResolutionAttempt>) {
        attempts.forEachIndexed { index, attempt ->
            resolveAndRecord(attempt)
            if (index + 1 < attempts.size) delay(AUTOMATIC_ITEM_INTERVAL_MS)
        }
    }

    private suspend fun resolveAndRecord(attempt: ResolutionAttempt) {
        val resolution = try {
            resolve(attempt)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            publishAttempt(attempt, SteamCatalogResolutionStatus.FAILED) { CanonicalGuardedMutationResult.APPLIED }
            ItemResolution(
                result = SteamResolutionItemResult.ProviderUnavailable,
                errorType = error.diagnosticCategory(),
            )
        }
        updateProgress(attempt.match.source, resolution)
    }

    private suspend fun resolve(attempt: ResolutionAttempt): ItemResolution {
        val match = attempt.match
        val expected = match.expectedState()
        val locale = attempt.locale
        val evidence = match.sourceEvidence()
        val fetched = fetchCandidates(match.evidenceDisplayName, locale)
        val rejected = resolutionDao.getRejectedSteamAppIds(match.accountScope, match.source, match.stableSourceId).toSet()
        val candidates = candidatePolicy.rankCandidates(evidence, fetched.candidates.filterNot { it.steamAppId in rejected })
        if (fetched.incomplete) {
            val selected = candidates.firstOrNull()
                ?: throw SteamCatalogCandidateFetchException()
            return publishAttempt(attempt, SteamCatalogResolutionStatus.FAILED, candidates) {
                decisionWriter.recordCandidate(
                    expected = expected,
                    steamAppId = selected.steamAppId,
                    resolverVersion = CURRENT_RESOLVER_VERSION,
                    nowEpochMs = clock.nowEpochMs(),
                )
            }.asItemResolution(SteamResolutionItemResult.ReviewRequired).copy(
                errorType = requireNotNull(fetched.incompleteReason),
            )
        }
        return when (val decision = candidatePolicy.evaluate(evidence, candidates)) {
            is CatalogDecision.AutoAccept -> {
                val selected = candidates.first { it.steamAppId == decision.steamAppId }
                val mutation = publishAttempt(attempt, SteamCatalogResolutionStatus.AUTO_ACCEPTED, candidates) {
                    decisionWriter.acceptAutomatic(
                        expected = expected,
                        steamAppId = selected.steamAppId,
                        candidateAppType = selected.appType,
                        resolverVersion = CURRENT_RESOLVER_VERSION,
                        nowEpochMs = clock.nowEpochMs(),
                    )
                }
                if (mutation == CanonicalGuardedMutationResult.APPLIED) {
                    enrichAcceptedIdentity(selected.steamAppId)
                }
                mutation.asItemResolution(SteamResolutionItemResult.AutoAccepted)
            }

            is CatalogDecision.ReviewRequired -> {
                publishAttempt(attempt, SteamCatalogResolutionStatus.REVIEW_REQUIRED, candidates) {
                    decisionWriter.recordCandidate(
                        expected = expected,
                        steamAppId = decision.steamAppIds.first(),
                        resolverVersion = CURRENT_RESOLVER_VERSION,
                        nowEpochMs = clock.nowEpochMs(),
                    )
                }.asItemResolution(SteamResolutionItemResult.ReviewRequired)
            }

            CatalogDecision.NoPlausibleCandidate -> {
                if (
                    match.source == GameSource.EPIC &&
                    match.evidenceAppType == CanonicalAppType.GAME
                ) {
                    val decisionEvidence = fetchPcGamingWikiEvidence(match)
                    val epicRecord = try {
                        fetchEpicFallback(match, locale)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        return publishAttempt(attempt, SteamCatalogResolutionStatus.UNMATCHED, candidates) {
                            decisionWriter.recordUnmatched(
                                expected = expected,
                                resolverVersion = CURRENT_RESOLVER_VERSION,
                                nowEpochMs = clock.nowEpochMs(),
                            )
                        }.asItemResolution(
                            SteamResolutionItemResult.CompleteNoPlausibleSteamMatch(
                                epicPresentation = EpicPresentationOutcome.EPIC_CMS_UNAVAILABLE,
                                pcGamingWikiEvidence = decisionEvidence,
                            ),
                        ).copy(errorType = error.diagnosticCategory())
                    }
                    publishAttempt(attempt, SteamCatalogResolutionStatus.UNMATCHED, candidates) {
                        epicFallbackWriter.recordEpicFallback(
                            expected = expected,
                            resolverVersion = CURRENT_RESOLVER_VERSION,
                            nowEpochMs = clock.nowEpochMs(),
                            locale = locale,
                            record = epicRecord,
                            decisionEvidence = decisionEvidence,
                        )
                    }.asItemResolution(
                        SteamResolutionItemResult.CompleteNoPlausibleSteamMatch(
                            epicPresentation = EpicPresentationOutcome.EPIC_CMS_PERSISTED,
                            pcGamingWikiEvidence = decisionEvidence,
                        ),
                    )
                } else {
                    publishAttempt(attempt, SteamCatalogResolutionStatus.UNMATCHED, candidates) {
                        decisionWriter.recordUnmatched(
                            expected = expected,
                            resolverVersion = CURRENT_RESOLVER_VERSION,
                            nowEpochMs = clock.nowEpochMs(),
                        )
                    }.asItemResolution(
                        SteamResolutionItemResult.CompleteNoPlausibleSteamMatch(),
                    )
                }
            }
        }
    }

    private suspend fun currentAutomaticEvidenceMatches(match: StoreMatchEntity): Boolean {
        val canonical = db.canonicalGameDao().get(match.canonicalId) ?: return false
        if (canonical.steamAppId != null) return false
        val present = storeMatchDao.getByCanonicalId(match.canonicalId).filter {
            it.isPresent && it.source != GameSource.STEAM && it.ownedCopyKeyOrNull() != null
        }
        return present.isNotEmpty() && present.none { it.decisionSource == MatchDecisionSource.USER } &&
            strongestEvidence(present) == match
    }

    private suspend fun prepareAttempt(match: StoreMatchEntity, force: Boolean): ResolutionAttempt? = db.withTransaction {
        if (!currentAutomaticEvidenceMatches(match)) return@withTransaction null
        val locale = localeProvider.current()
        val hash = evidenceHash(match, locale)
        val previous = resolutionDao.getAttempt(match.canonicalId)
        if (!force && previous?.isCompleteFor(hash) == true) return@withTransaction null
        val attemptedAt = maxOf(clock.nowEpochMs(), previous?.attemptedAt?.let { Math.addExact(it, 1L) } ?: 0L)
        val entity = SteamCatalogResolutionAttemptEntity(
            match.canonicalId, hash, CURRENT_RESOLVER_VERSION, SteamCatalogResolutionStatus.PENDING, attemptedAt,
        )
        resolutionDao.upsertAttempt(entity)
        ResolutionAttempt(match, entity, locale)
    }

    private suspend fun publishAttempt(
        attempt: ResolutionAttempt,
        status: SteamCatalogResolutionStatus,
        candidates: List<SteamCatalogCandidate> = emptyList(),
        mutation: suspend () -> CanonicalGuardedMutationResult,
    ): CanonicalGuardedMutationResult {
        val result = db.withTransaction {
            val match = attempt.match
            if (resolutionDao.getAttempt(match.canonicalId) != attempt.entity) {
                return@withTransaction CanonicalGuardedMutationResult.EXPECTED_STATE_CHANGED
            }
            if (!currentAutomaticEvidenceMatches(match) ||
                evidenceHash(match, localeProvider.current()) != attempt.entity.evidenceHash
            ) {
                resolutionDao.deleteAttempt(match.canonicalId)
                return@withTransaction CanonicalGuardedMutationResult.EXPECTED_STATE_CHANGED
            }
            // Only local guarded mutations run here; all provider work finishes before this transaction.
            val applied = mutation()
            if (applied == CanonicalGuardedMutationResult.APPLIED) {
                check(resolutionDao.completeAttempt(
                    attempt.entity.canonicalId, attempt.entity.evidenceHash, attempt.entity.resolverVersion,
                    attempt.entity.attemptedAt, status,
                ) == 1)
            } else {
                resolutionDao.deleteAttempt(match.canonicalId)
            }
            applied
        }
        if (result == CanonicalGuardedMutationResult.APPLIED && status != SteamCatalogResolutionStatus.FAILED) {
            candidateLists[attempt.match.expectedState().key] = candidates.take(MAX_VISIBLE_CANDIDATES)
        } else if (result == CanonicalGuardedMutationResult.APPLIED && candidates.isNotEmpty()) {
            candidateLists[attempt.match.expectedState().key] = candidates.take(MAX_VISIBLE_CANDIDATES)
        }
        return result
    }

    private fun SteamCatalogResolutionAttemptEntity.isCompleteFor(hash: String): Boolean =
        evidenceHash == hash && resolverVersion == CURRENT_RESOLVER_VERSION &&
            status != SteamCatalogResolutionStatus.PENDING && status != SteamCatalogResolutionStatus.FAILED

    private fun evidenceHash(match: StoreMatchEntity, locale: MetadataLocale): String {
        val publicEvidence = listOf(
            match.source.name, match.evidenceDisplayName, match.evidenceDeveloperKey,
            match.evidenceReleaseYear?.toString().orEmpty(), match.evidenceAppType.name,
            locale.normalizedLocale, locale.normalizedCountry,
        ).joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(publicEvidence.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private data class ResolutionAttempt(
        val match: StoreMatchEntity,
        val entity: SteamCatalogResolutionAttemptEntity,
        val locale: MetadataLocale,
    )

    private suspend fun fetchPcGamingWikiEvidence(
        match: StoreMatchEntity,
    ): PcGamingWikiCurrentAvailabilityEvidence? {
        val result = try {
            pcGamingWikiSource.check(
                PcGamingWikiCurrentAvailabilityRequest(
                    sourceTitle = match.evidenceDisplayName,
                    sourceReleaseYear = match.evidenceReleaseYear,
                    sourceDeveloper = match.evidenceDeveloperKey.takeIf(String::isNotBlank),
                    sourcePublisher = null,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            PcGamingWikiCurrentAvailabilityResult.Unavailable
        }
        return (result as? PcGamingWikiCurrentAvailabilityResult.Confirmed)?.evidence
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
                resolution.result is SteamResolutionItemResult.CompleteNoPlausibleSteamMatch
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
        key = checkNotNull(ownedCopyKeyOrNull()) { "Malformed persisted owned-copy identity" },
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
        const val RESUME_SCHEDULING_FAILED = "RESUME_SCHEDULING_FAILED"
        const val UNEXPECTED_FAILURE = "UNEXPECTED_FAILURE"
    }
}
