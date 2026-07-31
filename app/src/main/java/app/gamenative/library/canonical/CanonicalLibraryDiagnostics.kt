package app.gamenative.library.canonical

import app.gamenative.data.GameSource
import app.gamenative.diagnostics.DiagnosticArea
import app.gamenative.diagnostics.DiagnosticAttribute
import app.gamenative.diagnostics.DiagnosticEventName
import app.gamenative.diagnostics.DiagnosticOutcome
import app.gamenative.diagnostics.FeatureDiagnostics
import app.gamenative.library.canonical.action.ActionFailureReason
import app.gamenative.library.canonical.action.ActionSelectionPolicy
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass
import kotlinx.coroutines.CancellationException

interface CanonicalLibraryDiagnosticSink {
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
