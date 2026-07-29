package app.gamenative.library.canonical

import app.gamenative.data.GameSource
import app.gamenative.diagnostics.DiagnosticArea
import app.gamenative.diagnostics.DiagnosticAttribute
import app.gamenative.diagnostics.DiagnosticEventName
import app.gamenative.diagnostics.DiagnosticOutcome
import app.gamenative.diagnostics.FeatureDiagnostics
import app.gamenative.library.canonical.source.SnapshotCompleteness
import app.gamenative.library.canonical.source.SnapshotReason
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

enum class PlayHistoryOrigin {
    POINT,
    BATCH,
    FLOW,
}

interface CanonicalDiagnosticSink {
    fun indexStarted()

    fun sourceSnapshot(
        source: GameSource,
        completeness: SnapshotCompleteness,
        copyCount: Int,
        reason: SnapshotReason?,
        errorClass: KClass<out Throwable>?,
    )

    fun invalidationFailed(source: GameSource, errorClass: KClass<out Throwable>)

    fun playHistoryFailed(
        source: GameSource?,
        origin: PlayHistoryOrigin,
        errorClass: KClass<out Throwable>,
    )

    fun matchBucket(bucket: MatchBucket, count: Int)

    fun indexSucceeded(result: CanonicalProjectionResult, durationMs: Long)

    fun indexFailed(errorClass: KClass<out Throwable>, durationMs: Long)

    fun indexSkipped(reason: SnapshotReason)
}

sealed interface CanonicalDiagnosticEvent {
    data object IndexStarted : CanonicalDiagnosticEvent

    data class SourceSnapshot(
        val source: GameSource,
        val completeness: SnapshotCompleteness,
        val copyCount: Int,
        val reason: SnapshotReason?,
        val errorClass: KClass<out Throwable>?,
    ) : CanonicalDiagnosticEvent

    data class InvalidationFailed(
        val source: GameSource,
        val errorClass: KClass<out Throwable>,
    ) : CanonicalDiagnosticEvent

    data class PlayHistoryFailed(
        val source: GameSource?,
        val origin: PlayHistoryOrigin,
        val errorClass: KClass<out Throwable>,
    ) : CanonicalDiagnosticEvent

    data class MatchResolution(
        val bucket: MatchBucket,
        val count: Int,
    ) : CanonicalDiagnosticEvent

    data class IndexSucceeded(
        val result: CanonicalProjectionResult,
        val durationMs: Long,
    ) : CanonicalDiagnosticEvent

    data class IndexFailed(
        val errorClass: KClass<out Throwable>,
        val durationMs: Long,
    ) : CanonicalDiagnosticEvent

    data class IndexSkipped(val reason: SnapshotReason) : CanonicalDiagnosticEvent
}

fun interface CanonicalEventRecorder {
    fun record(event: CanonicalDiagnosticEvent)
}

@Singleton
class FeatureCanonicalEventRecorder @Inject constructor() : CanonicalEventRecorder {
    override fun record(event: CanonicalDiagnosticEvent) {
        val featureEvent = event.toFeatureEvent()
        FeatureDiagnostics.record(
            area = featureEvent.area,
            name = featureEvent.name,
            outcome = featureEvent.outcome,
            durationMs = featureEvent.durationMs,
            attributes = featureEvent.attributes,
        )
    }
}

@Singleton
class CanonicalDiagnostics @Inject constructor(
    private val recorder: CanonicalEventRecorder,
) : CanonicalDiagnosticSink {
    override fun indexStarted() {
        recorder.record(CanonicalDiagnosticEvent.IndexStarted)
    }

    override fun sourceSnapshot(
        source: GameSource,
        completeness: SnapshotCompleteness,
        copyCount: Int,
        reason: SnapshotReason?,
        errorClass: KClass<out Throwable>?,
    ) {
        recorder.record(
            CanonicalDiagnosticEvent.SourceSnapshot(
                source = source,
                completeness = completeness,
                copyCount = copyCount,
                reason = reason,
                errorClass = errorClass,
            ),
        )
    }

    override fun invalidationFailed(
        source: GameSource,
        errorClass: KClass<out Throwable>,
    ) {
        recorder.record(CanonicalDiagnosticEvent.InvalidationFailed(source, errorClass))
    }

    override fun playHistoryFailed(
        source: GameSource?,
        origin: PlayHistoryOrigin,
        errorClass: KClass<out Throwable>,
    ) {
        recorder.record(CanonicalDiagnosticEvent.PlayHistoryFailed(source, origin, errorClass))
    }

    override fun matchBucket(bucket: MatchBucket, count: Int) {
        recorder.record(CanonicalDiagnosticEvent.MatchResolution(bucket, count))
    }

    override fun indexSucceeded(
        result: CanonicalProjectionResult,
        durationMs: Long,
    ) {
        recorder.record(CanonicalDiagnosticEvent.IndexSucceeded(result, durationMs))
    }

    override fun indexFailed(
        errorClass: KClass<out Throwable>,
        durationMs: Long,
    ) {
        recorder.record(CanonicalDiagnosticEvent.IndexFailed(errorClass, durationMs))
    }

    override fun indexSkipped(reason: SnapshotReason) {
        recorder.record(CanonicalDiagnosticEvent.IndexSkipped(reason))
    }
}

internal data class CanonicalFeatureEvent(
    val area: DiagnosticArea,
    val name: DiagnosticEventName,
    val outcome: DiagnosticOutcome,
    val durationMs: Long?,
    val attributes: Map<DiagnosticAttribute, String>,
)

internal fun CanonicalDiagnosticEvent.toFeatureEvent(): CanonicalFeatureEvent = when (this) {
    CanonicalDiagnosticEvent.IndexStarted -> canonicalIndexEvent(
        outcome = DiagnosticOutcome.STARTED,
    )

    is CanonicalDiagnosticEvent.SourceSnapshot -> {
        val snapshotAttributes = linkedMapOf(
            DiagnosticAttribute.SOURCE to source.name,
            DiagnosticAttribute.RESULT_COUNT to copyCount.coerceAtLeast(0).toString(),
        )
        reason?.let { snapshotAttributes[DiagnosticAttribute.REASON] = it.name }
        errorClass?.let {
            snapshotAttributes[DiagnosticAttribute.ERROR_TYPE] = it.diagnosticName()
        }
        canonicalIndexEvent(
            outcome = when (completeness) {
                SnapshotCompleteness.COMPLETE -> DiagnosticOutcome.SUCCEEDED
                SnapshotCompleteness.PARTIAL -> DiagnosticOutcome.STALE
                SnapshotCompleteness.UNAVAILABLE -> DiagnosticOutcome.UNAVAILABLE
            },
            attributes = snapshotAttributes,
        )
    }

    is CanonicalDiagnosticEvent.InvalidationFailed -> canonicalIndexEvent(
        outcome = DiagnosticOutcome.FAILED,
        attributes = mapOf(
            DiagnosticAttribute.SOURCE to source.name,
            DiagnosticAttribute.REASON to SnapshotReason.SOURCE_READ_FAILED.name,
            DiagnosticAttribute.ERROR_TYPE to errorClass.diagnosticName(),
        ),
    )

    is CanonicalDiagnosticEvent.PlayHistoryFailed -> canonicalIndexEvent(
        outcome = DiagnosticOutcome.FAILED,
        attributes = buildMap {
            source?.let { put(DiagnosticAttribute.SOURCE, it.name) }
            put(DiagnosticAttribute.OPERATION, "PLAY_HISTORY")
            put(DiagnosticAttribute.REASON, origin.name)
            put(DiagnosticAttribute.ERROR_TYPE, errorClass.diagnosticName())
        },
    )

    is CanonicalDiagnosticEvent.MatchResolution -> CanonicalFeatureEvent(
        area = DiagnosticArea.MATCHING,
        name = DiagnosticEventName.MATCH_RESOLUTION,
        outcome = DiagnosticOutcome.SUCCEEDED,
        durationMs = null,
        attributes = mapOf(
            DiagnosticAttribute.MATCH_METHOD to bucket.method.name,
            DiagnosticAttribute.CONFIDENCE to bucket.confidence.name,
            DiagnosticAttribute.RESULT_COUNT to count.coerceAtLeast(0).toString(),
        ),
    )

    is CanonicalDiagnosticEvent.IndexSucceeded -> canonicalIndexEvent(
        outcome = DiagnosticOutcome.SUCCEEDED,
        durationMs = durationMs.coerceAtLeast(0),
        attributes = mapOf(
            DiagnosticAttribute.STEAM_COUNT to result.sourceCount(GameSource.STEAM),
            DiagnosticAttribute.GOG_COUNT to result.sourceCount(GameSource.GOG),
            DiagnosticAttribute.EPIC_COUNT to result.sourceCount(GameSource.EPIC),
            DiagnosticAttribute.AMAZON_COUNT to result.sourceCount(GameSource.AMAZON),
            DiagnosticAttribute.CUSTOM_COUNT to result.sourceCount(GameSource.CUSTOM_GAME),
            DiagnosticAttribute.CANONICAL_COUNT to result.canonicalCount.toString(),
            DiagnosticAttribute.COPY_COUNT to result.copyCount.toString(),
        ),
    )

    is CanonicalDiagnosticEvent.IndexFailed -> canonicalIndexEvent(
        outcome = DiagnosticOutcome.FAILED,
        durationMs = durationMs.coerceAtLeast(0),
        attributes = mapOf(DiagnosticAttribute.ERROR_TYPE to errorClass.diagnosticName()),
    )

    is CanonicalDiagnosticEvent.IndexSkipped -> canonicalIndexEvent(
        outcome = DiagnosticOutcome.SKIPPED,
        attributes = mapOf(DiagnosticAttribute.REASON to reason.name),
    )
}

private fun canonicalIndexEvent(
    outcome: DiagnosticOutcome,
    durationMs: Long? = null,
    attributes: Map<DiagnosticAttribute, String> = emptyMap(),
): CanonicalFeatureEvent = CanonicalFeatureEvent(
    area = DiagnosticArea.CANONICAL_INDEX,
    name = DiagnosticEventName.CANONICAL_INDEX_BUILD,
    outcome = outcome,
    durationMs = durationMs,
    attributes = attributes,
)

private fun CanonicalProjectionResult.sourceCount(source: GameSource): String =
    sourceCounts.getOrDefault(source, 0).toString()

private fun KClass<out Throwable>.diagnosticName(): String =
    simpleName?.takeIf(String::isNotBlank) ?: "Exception"
