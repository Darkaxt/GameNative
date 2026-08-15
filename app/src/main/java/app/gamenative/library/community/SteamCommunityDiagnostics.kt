package app.gamenative.library.community

import app.gamenative.diagnostics.DiagnosticArea
import app.gamenative.diagnostics.DiagnosticAttribute
import app.gamenative.diagnostics.DiagnosticEventName
import app.gamenative.diagnostics.DiagnosticOutcome
import app.gamenative.diagnostics.FeatureDiagnostics
import javax.inject.Inject
import javax.inject.Singleton

internal enum class SteamCommunityPageOperation {
    REVIEWS,
    DISCUSSION_LISTING,
    DISCUSSION_THREAD,
}

internal enum class SteamCommunityFailureReason {
    INVALID_REQUEST,
    NETWORK_UNAVAILABLE,
    RATE_LIMITED,
    HTTP_STATUS,
    REDIRECT_REJECTED,
    CONTENT_TYPE,
    BODY_LIMIT,
    MALFORMED_REPRESENTATION,
    CLIENT_RENDERED_SHELL,
    UNSUPPORTED_REPRESENTATION,
}

internal val SteamCommunityFailureReason.diagnosticOutcome: DiagnosticOutcome
    get() = when (this) {
        SteamCommunityFailureReason.NETWORK_UNAVAILABLE,
        SteamCommunityFailureReason.RATE_LIMITED,
        -> DiagnosticOutcome.UNAVAILABLE
        else -> DiagnosticOutcome.FAILED
    }

internal data class SteamCommunityPageDiagnostic(
    val operation: SteamCommunityPageOperation,
    val outcome: DiagnosticOutcome,
    val durationMs: Long,
    val httpStatus: Int? = null,
    val attemptCount: Int = 0,
    val itemCount: Int = 0,
    val skippedItemCount: Int = 0,
    val blankItemCount: Int = 0,
    val duplicateItemCount: Int = 0,
    val failureReason: SteamCommunityFailureReason? = null,
) {
    init {
        require(durationMs in 0..MAX_DURATION_MS)
        require(httpStatus == null || httpStatus in 100..599)
        require(attemptCount in 0..MAX_ATTEMPTS)
        require(itemCount in 0..MAX_COUNT)
        require(skippedItemCount in 0..MAX_COUNT)
        require(blankItemCount in 0..MAX_COUNT)
        require(duplicateItemCount in 0..MAX_COUNT)
        require((outcome == DiagnosticOutcome.SUCCEEDED) == (failureReason == null))
    }

    private companion object {
        const val MAX_DURATION_MS = 300_000L
        const val MAX_ATTEMPTS = 16
        const val MAX_COUNT = 1_000_000
    }
}

internal fun interface SteamCommunityDiagnosticSink {
    fun record(event: SteamCommunityPageDiagnostic)
}

internal object NoOpSteamCommunityDiagnostics : SteamCommunityDiagnosticSink {
    override fun record(event: SteamCommunityPageDiagnostic) = Unit
}

@Singleton
internal class FeatureSteamCommunityDiagnostics @Inject constructor() :
    SteamCommunityDiagnosticSink {
    override fun record(event: SteamCommunityPageDiagnostic) {
        runCatching {
            FeatureDiagnostics.record(
                area = when (event.operation) {
                    SteamCommunityPageOperation.REVIEWS -> DiagnosticArea.REVIEWS
                    SteamCommunityPageOperation.DISCUSSION_LISTING,
                    SteamCommunityPageOperation.DISCUSSION_THREAD,
                    -> DiagnosticArea.DISCUSSIONS
                },
                name = when (event.operation) {
                    SteamCommunityPageOperation.REVIEWS -> DiagnosticEventName.REVIEW_PAGE
                    SteamCommunityPageOperation.DISCUSSION_LISTING,
                    SteamCommunityPageOperation.DISCUSSION_THREAD,
                    -> DiagnosticEventName.DISCUSSION_PAGE
                },
                outcome = event.outcome,
                durationMs = event.durationMs,
                attributes = buildMap {
                    put(DiagnosticAttribute.OPERATION, event.operation.name)
                    put(DiagnosticAttribute.ATTEMPT_COUNT, event.attemptCount.toString())
                    put(DiagnosticAttribute.ITEM_COUNT, event.itemCount.toString())
                    put(DiagnosticAttribute.SKIPPED_ITEM_COUNT, event.skippedItemCount.toString())
                    put(DiagnosticAttribute.BLANK_ITEM_COUNT, event.blankItemCount.toString())
                    put(DiagnosticAttribute.DUPLICATE_ITEM_COUNT, event.duplicateItemCount.toString())
                    event.httpStatus?.let {
                        put(DiagnosticAttribute.HTTP_STATUS, it.toString())
                    }
                    event.failureReason?.let {
                        put(DiagnosticAttribute.REASON, it.name)
                    }
                },
            )
        }
    }
}
