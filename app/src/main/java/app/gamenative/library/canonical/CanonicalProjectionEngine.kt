package app.gamenative.library.canonical

import androidx.room.withTransaction
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.CanonicalGameEntity
import app.gamenative.data.canonical.CanonicalGameFeatureCrossRef
import app.gamenative.data.canonical.CanonicalGameGenreCrossRef
import app.gamenative.data.canonical.CanonicalGameTagCrossRef
import app.gamenative.data.canonical.ClassificationState
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.data.canonical.StoreMatchEntity
import app.gamenative.db.PluviaDatabase
import app.gamenative.library.canonical.source.OwnedCopyProjection
import app.gamenative.library.canonical.source.SnapshotCompleteness
import app.gamenative.library.canonical.source.SnapshotReason
import app.gamenative.library.canonical.source.SourceProjectionBatch
import javax.inject.Inject
import javax.inject.Singleton

data class MatchBucket(
    val method: MatchMethod,
    val confidence: MatchConfidence,
)

data class CanonicalProjectionResult(
    val sourceCounts: Map<GameSource, Int>,
    val canonicalCount: Int,
    val copyCount: Int,
    val matchCounts: Map<MatchBucket, Int>,
    val unavailableSources: Map<GameSource, SnapshotReason>,
) {
    init {
        require(sourceCounts.values.all { count -> count >= 0 })
        require(matchCounts.values.all { count -> count >= 0 })
        require(copyCount == sourceCounts.values.sum())
        require(copyCount == matchCounts.values.sum())
        require(canonicalCount in 0..copyCount)
        require(unavailableSources.keys.all { source -> sourceCounts[source] == 0 })
    }
}

interface CanonicalProjectionRunner {
    suspend fun rebuild(
        batches: List<SourceProjectionBatch>,
        nowEpochMs: Long,
    ): CanonicalProjectionResult
}

@Singleton
class CanonicalProjectionEngine @Inject constructor(
    private val db: PluviaDatabase,
    private val resolver: CanonicalResolver,
) : CanonicalProjectionRunner {
    private val canonicalGameDao = db.canonicalGameDao()
    private val storeMatchDao = db.storeMatchDao()
    private val facetDao = db.canonicalFacetDao()

    override suspend fun rebuild(
        batches: List<SourceProjectionBatch>,
        nowEpochMs: Long,
    ): CanonicalProjectionResult {
        val snapshotBatches = snapshotInput(batches)
        validateInput(snapshotBatches)
        val orderedBatches = snapshotBatches.sortedBy { batch -> sourceRank(batch.source) }
        val sourceCounts = linkedMapOf<GameSource, Int>()
        val unavailableSources = linkedMapOf<GameSource, SnapshotReason>()
        orderedBatches.forEach { batch ->
            sourceCounts[batch.source] = batch.copies.size
            if (batch.completeness == SnapshotCompleteness.UNAVAILABLE) {
                unavailableSources[batch.source] = requireNotNull(batch.reason)
            }
        }

        val projectionStates = linkedMapOf<String, CanonicalProjectionState>()
        val matchCounts = linkedMapOf<MatchBucket, Int>()
        val completenessBySource = orderedBatches.associate { batch ->
            batch.source to batch.completeness
        }

        db.withTransaction {
            orderedBatches
                .filter { batch -> batch.completeness == SnapshotCompleteness.COMPLETE }
                .forEach { batch ->
                    storeMatchDao.markAbsentForCompleteSnapshot(
                        accountScope = requireNotNull(batch.accountScope).value,
                        source = batch.source,
                    )
                }

            orderedBatches
                .filterNot { batch -> batch.completeness == SnapshotCompleteness.UNAVAILABLE }
                .flatMap { batch -> batch.copies }
                .sortedWith(
                    compareBy<OwnedCopyProjection>(
                        { copy -> sourceRank(copy.key.source) },
                        { copy -> copy.key.stableSourceId },
                        { copy -> copy.key.accountScope.value },
                    ),
                )
                .forEach { copy ->
                    val storedMatch = storeMatchDao.get(
                        accountScope = copy.key.accountScope.value,
                        source = copy.key.source,
                        stableSourceId = copy.key.stableSourceId,
                    )
                    val resolution = resolver.resolve(copy, nowEpochMs)
                    validateResolution(copy, resolution)
                    val match = preserveUserDecision(copy, storedMatch, resolution)
                    val state = projectionStates[match.canonicalId] ?: CanonicalProjectionState(
                        original = canonicalGameDao.get(match.canonicalId),
                    ).also { newState ->
                        projectionStates[match.canonicalId] = newState
                    }
                    persistCanonical(resolution)
                    storeMatchDao.upsert(match.copy(isPresent = true))

                    matchCounts.increment(MatchBucket(match.matchMethod, match.confidence))
                    state.copies += copy
                }

            projectionStates.forEach { (canonicalId, state) ->
                val facetsChanged = projectWinningFacets(
                    canonicalId = canonicalId,
                    projectedCopies = state.copies,
                    completenessBySource = completenessBySource,
                    nowEpochMs = nowEpochMs,
                )
                restoreNoOpTimestamp(
                    canonicalId = canonicalId,
                    original = state.original,
                    facetsChanged = facetsChanged,
                )
            }
        }

        return CanonicalProjectionResult(
            sourceCounts = sourceCounts.toMap(),
            canonicalCount = projectionStates.size,
            copyCount = sourceCounts.values.sum(),
            matchCounts = matchCounts.toMap(),
            unavailableSources = unavailableSources.toMap(),
        )
    }

    private fun snapshotInput(batches: List<SourceProjectionBatch>): List<SourceProjectionBatch> =
        batches.map { batch ->
            batch.copy(
                copies = batch.copies.map { copy ->
                    copy.copy(
                        genreKeys = copy.genreKeys.toSet(),
                        tagIds = copy.tagIds.toSet(),
                        featureKeys = copy.featureKeys.toSet(),
                    )
                },
            )
        }

    private fun validateInput(batches: List<SourceProjectionBatch>) {
        require(batches.map(SourceProjectionBatch::source).distinct().size == batches.size) {
            "Projection input must contain at most one batch per source"
        }

        val keys = mutableSetOf<OwnedCopyKey>()
        batches.forEach { batch ->
            when (batch.completeness) {
                SnapshotCompleteness.COMPLETE -> {
                    require(batch.accountScope != null) {
                        "Complete source snapshot is missing its account scope"
                    }
                }

                SnapshotCompleteness.PARTIAL -> {
                    require(batch.accountScope != null) {
                        "Partial source snapshot is missing its account scope"
                    }
                    require(batch.reason != null) {
                        "Partial source snapshot is missing its reason"
                    }
                }

                SnapshotCompleteness.UNAVAILABLE -> {
                    require(batch.copies.isEmpty()) {
                        "Unavailable source snapshot must not contain copies"
                    }
                    require(batch.reason != null) {
                        "Unavailable source snapshot is missing its reason"
                    }
                }
            }

            batch.copies.forEach { copy ->
                require(copy.key.source == batch.source) {
                    "Owned copy source does not match its source batch"
                }
                require(copy.key.accountScope == batch.accountScope) {
                    "Owned copy account scope does not match its source batch"
                }
                require(keys.add(copy.key)) {
                    "Projection input contains a duplicate owned copy key"
                }
            }
        }
    }

    private fun validateResolution(
        copy: OwnedCopyProjection,
        resolution: CanonicalResolution,
    ) {
        val match = resolution.match
        require(match.accountScope == copy.key.accountScope.value) {
            "Resolved match account scope does not match its owned copy"
        }
        require(match.source == copy.key.source) {
            "Resolved match source does not match its owned copy"
        }
        require(match.stableSourceId == copy.key.stableSourceId) {
            "Resolved match source ID does not match its owned copy"
        }
    }

    private fun preserveUserDecision(
        copy: OwnedCopyProjection,
        storedMatch: StoreMatchEntity?,
        resolution: CanonicalResolution,
    ): StoreMatchEntity {
        val userDecision = storedMatch?.takeIf { match ->
            copy.key.source != GameSource.STEAM &&
                match.decisionSource == MatchDecisionSource.USER
        } ?: return resolution.match

        require(resolution.canonical.canonicalId == userDecision.canonicalId) {
            "Resolver attempted to replace a stored user decision"
        }
        return resolution.match.copy(
            canonicalId = userDecision.canonicalId,
            candidateSteamAppId = userDecision.candidateSteamAppId,
            matchMethod = userDecision.matchMethod,
            confidence = userDecision.confidence,
            decisionSource = userDecision.decisionSource,
            resolverVersion = userDecision.resolverVersion,
            matchedAt = userDecision.matchedAt,
        )
    }

    private suspend fun persistCanonical(resolution: CanonicalResolution) {
        if (resolution.createdCanonical) {
            canonicalGameDao.insert(resolution.canonical)
            return
        }

        val stored = requireNotNull(canonicalGameDao.get(resolution.canonical.canonicalId)) {
            "Resolved match references a missing canonical game"
        }
        if (stored != resolution.canonical) {
            canonicalGameDao.update(resolution.canonical)
        }
    }

    private suspend fun projectWinningFacets(
        canonicalId: String,
        projectedCopies: List<OwnedCopyProjection>,
        completenessBySource: Map<GameSource, SnapshotCompleteness>,
        nowEpochMs: Long,
    ): Boolean {
        val canonical = requireNotNull(canonicalGameDao.get(canonicalId))
        val winningCopies = projectedCopies.filter { copy ->
            copy.key.source == canonical.primaryMetadataSource
        }
        val existingGenres = facetDao.getGenres(canonicalId).map { it.genreKey }.toSet()
        val existingTags = facetDao.getTags(canonicalId).map { it.tagId }.toSet()
        val existingFeatures = facetDao.getFeatures(canonicalId).map { it.featureKey }.toSet()
        val preserveUnobserved =
            completenessBySource[canonical.primaryMetadataSource] == SnapshotCompleteness.PARTIAL
        val genreKeys = desiredFacets(
            existing = existingGenres,
            observed = winningCopies.flatMap { it.genreKeys }.toSet(),
            preserveUnobserved = preserveUnobserved,
            hasWinningCopies = winningCopies.isNotEmpty(),
        )
        val tagIds = desiredFacets(
            existing = existingTags,
            observed = winningCopies.flatMap { it.tagIds }.toSet(),
            preserveUnobserved = preserveUnobserved,
            hasWinningCopies = winningCopies.isNotEmpty(),
        )
        val featureKeys = desiredFacets(
            existing = existingFeatures,
            observed = winningCopies.flatMap { it.featureKeys }.toSet(),
            preserveUnobserved = preserveUnobserved,
            hasWinningCopies = winningCopies.isNotEmpty(),
        )

        val genresChanged = replaceGenres(canonicalId, existingGenres, genreKeys)
        val tagsChanged = replaceTags(canonicalId, existingTags, tagIds)
        val featuresChanged = replaceFeatures(canonicalId, existingFeatures, featureKeys)
        val facetsChanged = genresChanged || tagsChanged || featuresChanged
        val classificationState = classificationState(
            hasGenres = genreKeys.isNotEmpty(),
            hasTagsOrFeatures = tagIds.isNotEmpty() || featureKeys.isNotEmpty(),
        )
        if (facetsChanged || canonical.classificationState != classificationState) {
            canonicalGameDao.update(
                canonical.copy(
                    classificationState = classificationState,
                    updatedAt = nowEpochMs,
                ),
            )
        }
        return facetsChanged
    }

    private fun <T> desiredFacets(
        existing: Set<T>,
        observed: Set<T>,
        preserveUnobserved: Boolean,
        hasWinningCopies: Boolean,
    ): Set<T> = when {
        !hasWinningCopies -> existing
        preserveUnobserved -> existing + observed
        else -> observed
    }

    private suspend fun restoreNoOpTimestamp(
        canonicalId: String,
        original: CanonicalGameEntity?,
        facetsChanged: Boolean,
    ) {
        if (original == null || facetsChanged) return
        val current = requireNotNull(canonicalGameDao.get(canonicalId))
        val restored = current.copy(updatedAt = original.updatedAt)
        if (current.updatedAt != original.updatedAt && restored == original) {
            canonicalGameDao.update(restored)
        }
    }

    private suspend fun replaceGenres(
        canonicalId: String,
        existing: Set<String>,
        desired: Set<String>,
    ): Boolean {
        if (existing == desired) return false
        facetDao.deleteGenres(canonicalId)
        facetDao.upsertGenres(
            desired.sorted().map { genreKey ->
                CanonicalGameGenreCrossRef(canonicalId, genreKey)
            },
        )
        return true
    }

    private suspend fun replaceTags(
        canonicalId: String,
        existing: Set<Int>,
        desired: Set<Int>,
    ): Boolean {
        if (existing == desired) return false
        facetDao.deleteTags(canonicalId)
        facetDao.upsertTags(
            desired.sorted().map { tagId ->
                CanonicalGameTagCrossRef(canonicalId, tagId)
            },
        )
        return true
    }

    private suspend fun replaceFeatures(
        canonicalId: String,
        existing: Set<String>,
        desired: Set<String>,
    ): Boolean {
        if (existing == desired) return false
        facetDao.deleteFeatures(canonicalId)
        facetDao.upsertFeatures(
            desired.sorted().map { featureKey ->
                CanonicalGameFeatureCrossRef(canonicalId, featureKey)
            },
        )
        return true
    }

    private fun classificationState(
        hasGenres: Boolean,
        hasTagsOrFeatures: Boolean,
    ): ClassificationState = when {
        hasGenres && hasTagsOrFeatures -> ClassificationState.CLASSIFIED
        hasGenres || hasTagsOrFeatures -> ClassificationState.PARTIALLY_CLASSIFIED
        else -> ClassificationState.UNCLASSIFIED
    }

    private fun MutableMap<MatchBucket, Int>.increment(bucket: MatchBucket) {
        this[bucket] = getOrDefault(bucket, 0) + 1
    }

    private data class CanonicalProjectionState(
        val original: CanonicalGameEntity?,
        val copies: MutableList<OwnedCopyProjection> = mutableListOf(),
    )

    private fun sourceRank(source: GameSource): Int = when (source) {
        GameSource.STEAM -> 0
        GameSource.GOG -> 1
        GameSource.EPIC -> 2
        GameSource.AMAZON -> 3
        GameSource.CUSTOM_GAME -> 4
    }
}
