package app.gamenative.library.canonical

import app.gamenative.data.GameSource
import app.gamenative.diagnostics.DiagnosticArea
import app.gamenative.diagnostics.DiagnosticAttribute
import app.gamenative.diagnostics.DiagnosticEventName
import app.gamenative.diagnostics.DiagnosticOutcome
import app.gamenative.diagnostics.FeatureDiagnostics
import app.gamenative.library.canonical.action.ActionFailureReason
import app.gamenative.library.canonical.action.ActionSelectionPolicy
import app.gamenative.library.canonical.artwork.ArtworkFeatures
import app.gamenative.library.canonical.artwork.ArtworkMatch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass
import kotlinx.coroutines.CancellationException

enum class ArtworkEvidenceFailure {
    NATIVE_COPY_UNAVAILABLE,
    ORIGINAL_UNAVAILABLE,
    STALE_NATIVE_COPY,
    NATIVE_MATCHER_UNAVAILABLE,
}

class ArtworkPairDigest private constructor(val value: String) {
    companion object {
        fun from(left: ArtworkFeatures, right: ArtworkFeatures): ArtworkPairDigest = fromHashes(left.contentHash, right.contentHash)

        internal fun fromHashes(left: String, right: String): ArtworkPairDigest {
            require(ArtworkFeatures.HASH.matches(left) && ArtworkFeatures.HASH.matches(right))
            val hashes = listOf(left, right).sorted()
            return ArtworkPairDigest("${hashes[0].take(16)}/${hashes[1].take(16)}")
        }
    }
}

interface CanonicalLibraryDiagnosticSink {
    fun artworkState(game: ArtworkGameTrace, observation: ArtworkTraceObservation) = Unit

    fun artworkScheduler(snapshot: ArtworkSchedulerSnapshot) = Unit

    fun artworkCompared(images: ArtworkPairDigest, result: ArtworkMatch, elapsedMs: Long) = Unit

    fun artworkUnavailable(source: GameSource, reason: ArtworkEvidenceFailure) = Unit

    fun cardsProjected(
        resultCount: Int,
        canonicalCount: Int,
        copyCount: Int,
        elapsedMs: Long,
    )

    fun runtimeReadFailed(
        source: GameSource,
        errorClass: KClass<out Throwable>,
    )

    fun legacyFallback(
        reason: CanonicalPublicFailure,
        errorClass: KClass<out Throwable>? = null,
    )

    fun routeSelected(
        source: GameSource,
        operation: OwnedCopyOperation,
        policy: ActionSelectionPolicy,
        capableCount: Int,
    )

    fun chooserRequired(operation: OwnedCopyOperation, capableCount: Int)

    fun routeFailed(
        source: GameSource?,
        operation: OwnedCopyOperation,
        reason: ActionFailureReason,
        errorClass: KClass<out Throwable>? = null,
    )

    fun revalidationFailed(
        source: GameSource,
        operation: OwnedCopyOperation,
        reason: ActionFailureReason,
    )

    fun routeSucceeded(source: GameSource, operation: OwnedCopyOperation)
}

internal object NoOpCanonicalLibraryDiagnosticSink : CanonicalLibraryDiagnosticSink {
    override fun cardsProjected(resultCount: Int, canonicalCount: Int, copyCount: Int, elapsedMs: Long) = Unit

    override fun runtimeReadFailed(source: GameSource, errorClass: KClass<out Throwable>) = Unit

    override fun legacyFallback(reason: CanonicalPublicFailure, errorClass: KClass<out Throwable>?) = Unit

    override fun routeSelected(
        source: GameSource,
        operation: OwnedCopyOperation,
        policy: ActionSelectionPolicy,
        capableCount: Int,
    ) = Unit

    override fun chooserRequired(operation: OwnedCopyOperation, capableCount: Int) = Unit

    override fun routeFailed(
        source: GameSource?,
        operation: OwnedCopyOperation,
        reason: ActionFailureReason,
        errorClass: KClass<out Throwable>?,
    ) = Unit

    override fun revalidationFailed(
        source: GameSource,
        operation: OwnedCopyOperation,
        reason: ActionFailureReason,
    ) = Unit

    override fun routeSucceeded(source: GameSource, operation: OwnedCopyOperation) = Unit
}

@Singleton
class FeatureCanonicalLibraryDiagnostics @Inject constructor() : CanonicalLibraryDiagnosticSink {
    override fun artworkScheduler(snapshot: ArtworkSchedulerSnapshot) {
        if (!FeatureDiagnostics.focusedCaptureState().enabled) return
        FeatureDiagnostics.recordArtworkScheduler(snapshot.elapsedMs.boundedDuration(), mapOf(
            DiagnosticAttribute.OPERATION to "ARTWORK_SCHEDULER",
            DiagnosticAttribute.CORRELATION_ID to snapshot.generation.toString(),
            DiagnosticAttribute.STAGE to snapshot.stage.name,
            DiagnosticAttribute.PREPARATION_PHASE to snapshot.preparation.name,
            DiagnosticAttribute.PRODUCER_PHASE to snapshot.producer.name,
            DiagnosticAttribute.CONSUMER_PHASE to snapshot.consumer.name,
            DiagnosticAttribute.PRODUCER_RANK to snapshot.producerRank.boundedCount(),
            DiagnosticAttribute.CONSUMER_RANK to snapshot.consumerRank.boundedCount(),
            DiagnosticAttribute.REFERENCE_COUNT to snapshot.total.boundedCount(),
            DiagnosticAttribute.ENQUEUED_COUNT to snapshot.enqueued.boundedCount(),
            DiagnosticAttribute.DEQUEUED_COUNT to snapshot.dequeued.boundedCount(),
            DiagnosticAttribute.COMPLETED_COUNT to snapshot.completed.boundedCount(),
            DiagnosticAttribute.QUEUE_SIZE to snapshot.queueSize.boundedCount(),
            DiagnosticAttribute.QUEUE_HIGH_WATER to snapshot.queueHighWater.boundedCount(),
            DiagnosticAttribute.INDEX_PROGRESS to snapshot.indexProgress.boundedCount(),
            DiagnosticAttribute.INDEX_TOTAL to snapshot.indexTotal.boundedCount(),
            DiagnosticAttribute.PREPARATION_ELAPSED_MS to snapshot.preparationElapsedMs.boundedDuration().toString(),
            DiagnosticAttribute.PRODUCER_ELAPSED_MS to snapshot.producerElapsedMs.boundedDuration().toString(),
            DiagnosticAttribute.CONSUMER_ELAPSED_MS to snapshot.consumerElapsedMs.boundedDuration().toString(),
        ))
    }

    override fun artworkState(game: ArtworkGameTrace, observation: ArtworkTraceObservation) {
        if (observation.stage == ArtworkTraceStage.ORIGINAL_SCHEDULED && !FeatureDiagnostics.focusedCaptureState().enabled) return
        val attributes = buildMap {
            put(DiagnosticAttribute.OPERATION, "ARTWORK_TRACE")
            put(DiagnosticAttribute.PUBLIC_TITLE, game.publicTitle)
            put(DiagnosticAttribute.SOURCE, game.source.name)
            put(DiagnosticAttribute.CORRELATION_ID, game.correlationId)
            put(DiagnosticAttribute.INITIAL_MATCH_METHOD, game.initialMatchMethod.name)
            put(DiagnosticAttribute.INITIAL_MATCH_CONFIDENCE, game.initialMatchConfidence.name)
            if (observation.stage != ArtworkTraceStage.FAMILY_PUBLISHED) {
                put(DiagnosticAttribute.STAGE, observation.stage.name)
                put(DiagnosticAttribute.REASON, observation.stage.name)
            }
            if (observation.stage in setOf(ArtworkTraceStage.NATIVE_UNAVAILABLE, ArtworkTraceStage.STALE_NATIVE_EVIDENCE,
                    ArtworkTraceStage.ORIGINAL_UNAVAILABLE, ArtworkTraceStage.NATIVE_MATCHER_UNAVAILABLE, ArtworkTraceStage.FAILED)) {
                put(DiagnosticAttribute.ERROR_TYPE, observation.stage.name)
            }
            observation.reference?.let {
                put(DiagnosticAttribute.PUBLIC_URL, it.url)
                put(DiagnosticAttribute.PUBLIC_URL_ID, ArtworkFeatures.hash(it.url.toByteArray()).take(16))
                put(DiagnosticAttribute.PROVIDER, it.source.name)
            }
            observation.content?.let { put(DiagnosticAttribute.PUBLIC_CONTENT_ID, it) }
            observation.pair?.let { put(DiagnosticAttribute.CONFIDENCE, "pair=${it.value}") }
            observation.matchResult?.let { put(DiagnosticAttribute.MATCH_RESULT, it.name) }
            observation.referenceCount?.let { put(DiagnosticAttribute.REFERENCE_COUNT, it.boundedCount()) }
            observation.candidateCount?.let { put(DiagnosticAttribute.CANDIDATE_COUNT, it.boundedCount()) }
            observation.comparisonCount?.let { put(DiagnosticAttribute.COMPARISON_COUNT, it.boundedCount()) }
            observation.acceptedCount?.let { put(DiagnosticAttribute.ACCEPTED_COUNT, it.boundedCount()) }
            observation.familySize?.let { put(DiagnosticAttribute.FAMILY_SIZE, it.boundedCount()) }
            observation.groupingReason?.let { put(DiagnosticAttribute.GROUPING_REASON, it.name) }
            observation.acquisitionRank?.let { put(DiagnosticAttribute.ACQUISITION_RANK, it.boundedCount()) }
            observation.scheduleCacheState?.let { put(DiagnosticAttribute.CACHE_STATE, it.name) }
        }
        if (observation.stage == ArtworkTraceStage.ORIGINAL_SCHEDULED) {
            FeatureDiagnostics.recordArtworkSchedule(attributes)
            return
        }
        record(
            area = DiagnosticArea.MATCHING,
            name = DiagnosticEventName.GAME_RESOLUTION,
            outcome = when (observation.stage) {
                ArtworkTraceStage.QUEUED, ArtworkTraceStage.NATIVE_VALIDATING, ArtworkTraceStage.ORIGINAL_FETCHING -> DiagnosticOutcome.STARTED
                ArtworkTraceStage.INELIGIBLE, ArtworkTraceStage.INVALID_MEMBERSHIP, ArtworkTraceStage.CANCELLED -> DiagnosticOutcome.SKIPPED
                ArtworkTraceStage.NATIVE_UNAVAILABLE, ArtworkTraceStage.STALE_NATIVE_EVIDENCE,
                ArtworkTraceStage.NO_ADMITTED_ORIGINAL, ArtworkTraceStage.ORIGINAL_UNAVAILABLE, ArtworkTraceStage.NATIVE_MATCHER_UNAVAILABLE -> DiagnosticOutcome.UNAVAILABLE
                ArtworkTraceStage.FAILED -> DiagnosticOutcome.FAILED
                ArtworkTraceStage.CACHE_HIT, ArtworkTraceStage.ORIGINAL_CACHE_BOUND -> DiagnosticOutcome.CACHE_HIT
                ArtworkTraceStage.COMPARED -> if (observation.matchResult == ArtworkTraceMatchResult.REJECTED)
                    DiagnosticOutcome.SKIPPED else DiagnosticOutcome.SUCCEEDED
                else -> DiagnosticOutcome.SUCCEEDED
            },
            attributes = attributes,
        )
    }

    override fun artworkCompared(images: ArtworkPairDigest, result: ArtworkMatch, elapsedMs: Long) = record(
        area = DiagnosticArea.MATCHING,
        name = DiagnosticEventName.MATCH_RESOLUTION,
        outcome = when {
            !result.completed -> DiagnosticOutcome.UNAVAILABLE
            result.accepted -> DiagnosticOutcome.SUCCEEDED
            else -> DiagnosticOutcome.SKIPPED
        },
        durationMs = elapsedMs.boundedDuration(),
        attributes = mapOf(
            DiagnosticAttribute.MATCH_METHOD to "ORB_V1",
            DiagnosticAttribute.REASON to result.reason.name,
            DiagnosticAttribute.PUBLIC_CONTENT_ID to images.value,
            DiagnosticAttribute.RESULT_COUNT to result.matches.boundedCount(),
            DiagnosticAttribute.CONFIDENCE to "inliers=${result.inliers.boundedCount()};ratio=${result.inlierRatio.thousandths()};" +
                "area=${result.coverage.thousandths()};cells=${result.occupiedCells.boundedCount()};error=${result.meanError.thousandths()}",
        ),
    )

    override fun artworkUnavailable(source: GameSource, reason: ArtworkEvidenceFailure) = record(
        area = DiagnosticArea.MATCHING,
        name = DiagnosticEventName.MATCH_RESOLUTION,
        outcome = DiagnosticOutcome.UNAVAILABLE,
        attributes = mapOf(
            DiagnosticAttribute.MATCH_METHOD to "ORB_V1",
            DiagnosticAttribute.SOURCE to source.name,
            DiagnosticAttribute.REASON to reason.name,
        ),
    )

    override fun cardsProjected(
        resultCount: Int,
        canonicalCount: Int,
        copyCount: Int,
        elapsedMs: Long,
    ) = record(
        area = DiagnosticArea.LIBRARY_FILTER,
        name = DiagnosticEventName.LIBRARY_FILTER,
        outcome = DiagnosticOutcome.SUCCEEDED,
        durationMs = elapsedMs.boundedDuration(),
        attributes = mapOf(
            DiagnosticAttribute.RESULT_COUNT to resultCount.boundedCount(),
            DiagnosticAttribute.CANONICAL_COUNT to canonicalCount.boundedCount(),
            DiagnosticAttribute.COPY_COUNT to copyCount.boundedCount(),
        ),
    )

    override fun runtimeReadFailed(
        source: GameSource,
        errorClass: KClass<out Throwable>,
    ) = record(
        area = DiagnosticArea.ACTION_ROUTING,
        name = DiagnosticEventName.GAME_RESOLUTION,
        outcome = DiagnosticOutcome.FAILED,
        attributes = mapOf(
            DiagnosticAttribute.SOURCE to source.name,
            DiagnosticAttribute.REASON to CopyUnavailableReason.SOURCE_READ_FAILED.name,
            DiagnosticAttribute.ERROR_TYPE to errorClass.diagnosticName(),
        ),
    )

    override fun legacyFallback(
        reason: CanonicalPublicFailure,
        errorClass: KClass<out Throwable>?,
    ) = record(
        area = DiagnosticArea.LIBRARY_FILTER,
        name = DiagnosticEventName.LIBRARY_FILTER,
        outcome = when (reason) {
            CanonicalPublicFailure.MISSING_PROJECTION_PREREQUISITE,
            CanonicalPublicFailure.UNSUPPORTED_LEGACY_CONTEXT,
            -> DiagnosticOutcome.SKIPPED
            CanonicalPublicFailure.ASSEMBLY_FAILED,
            CanonicalPublicFailure.INVALID_CARD_STATE,
            -> DiagnosticOutcome.FAILED
        },
        attributes = buildMap {
            put(DiagnosticAttribute.REASON, reason.name)
            errorClass?.let { put(DiagnosticAttribute.ERROR_TYPE, it.diagnosticName()) }
        },
    )

    override fun routeSelected(
        source: GameSource,
        operation: OwnedCopyOperation,
        policy: ActionSelectionPolicy,
        capableCount: Int,
    ) = record(
        outcome = DiagnosticOutcome.STARTED,
        attributes = mapOf(
            DiagnosticAttribute.SOURCE to source.name,
            DiagnosticAttribute.OPERATION to operation.name,
            DiagnosticAttribute.SELECTION_POLICY to policy.name,
            DiagnosticAttribute.RESULT_COUNT to capableCount.boundedCount(),
        ),
    )

    override fun chooserRequired(
        operation: OwnedCopyOperation,
        capableCount: Int,
    ) = record(
        outcome = DiagnosticOutcome.DEFERRED,
        attributes = mapOf(
            DiagnosticAttribute.OPERATION to operation.name,
            DiagnosticAttribute.RESULT_COUNT to capableCount.boundedCount(),
        ),
    )

    override fun routeFailed(
        source: GameSource?,
        operation: OwnedCopyOperation,
        reason: ActionFailureReason,
        errorClass: KClass<out Throwable>?,
    ) = record(
        outcome = if (reason == ActionFailureReason.PREFERENCE_WRITE_FAILED) {
            DiagnosticOutcome.FAILED
        } else {
            DiagnosticOutcome.UNAVAILABLE
        },
        attributes = buildMap {
            source?.let { put(DiagnosticAttribute.SOURCE, it.name) }
            put(DiagnosticAttribute.OPERATION, operation.name)
            put(DiagnosticAttribute.REASON, reason.name)
            errorClass?.let { put(DiagnosticAttribute.ERROR_TYPE, it.diagnosticName()) }
        },
    )

    override fun revalidationFailed(
        source: GameSource,
        operation: OwnedCopyOperation,
        reason: ActionFailureReason,
    ) = record(
        outcome = DiagnosticOutcome.UNAVAILABLE,
        attributes = mapOf(
            DiagnosticAttribute.SOURCE to source.name,
            DiagnosticAttribute.OPERATION to operation.name,
            DiagnosticAttribute.REASON to reason.name,
        ),
    )

    override fun routeSucceeded(
        source: GameSource,
        operation: OwnedCopyOperation,
    ) = record(
        outcome = DiagnosticOutcome.SUCCEEDED,
        attributes = mapOf(
            DiagnosticAttribute.SOURCE to source.name,
            DiagnosticAttribute.OPERATION to operation.name,
        ),
    )

    private fun record(
        area: DiagnosticArea = DiagnosticArea.ACTION_ROUTING,
        name: DiagnosticEventName = DiagnosticEventName.ACTION_ROUTE,
        outcome: DiagnosticOutcome,
        durationMs: Long? = null,
        attributes: Map<DiagnosticAttribute, String>,
    ) {
        try {
            FeatureDiagnostics.record(
                area = area,
                name = name,
                outcome = outcome,
                durationMs = durationMs,
                attributes = attributes,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Diagnostics are best effort and must not affect library or action behavior.
        }
    }

    private fun Double.thousandths(): String = if (isFinite()) (coerceIn(0.0, 1_000.0) * 1_000).toInt().toString() else "0"

    private fun Int.boundedCount(): String = coerceIn(0, MAX_COUNT).toString()

    private fun Long.boundedDuration(): Long = coerceIn(0L, MAX_DURATION_MS)

    private fun KClass<out Throwable>.diagnosticName(): String = simpleName ?: UNKNOWN_EXCEPTION

    private companion object {
        const val MAX_COUNT = 1_000_000
        const val MAX_DURATION_MS = 86_400_000L
        const val UNKNOWN_EXCEPTION = "UNKNOWN_EXCEPTION"
    }
}

internal inline fun CanonicalLibraryDiagnosticSink.recordSafely(
    record: CanonicalLibraryDiagnosticSink.() -> Unit,
) {
    try {
        record()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // A broken diagnostic facade must never affect the observed feature behavior.
    }
}
