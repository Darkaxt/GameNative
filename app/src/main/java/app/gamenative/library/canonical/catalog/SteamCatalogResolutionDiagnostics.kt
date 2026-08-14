package app.gamenative.library.canonical.catalog

import app.gamenative.data.GameSource
import app.gamenative.diagnostics.DiagnosticArea
import app.gamenative.diagnostics.DiagnosticAttribute
import app.gamenative.diagnostics.DiagnosticEventName
import app.gamenative.diagnostics.DiagnosticOutcome
import app.gamenative.diagnostics.FeatureDiagnostics
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

data class SteamResolutionDiagnosticEvent(
    val result: SteamResolutionItemResult,
    val source: GameSource?,
    val completed: Int,
    val total: Int,
    val failed: Int,
    val errorType: String? = null,
)

fun interface SteamCatalogResolutionDiagnosticSink {
    fun record(event: SteamResolutionDiagnosticEvent)
}

internal object NoOpSteamCatalogResolutionDiagnostics : SteamCatalogResolutionDiagnosticSink {
    override fun record(event: SteamResolutionDiagnosticEvent) = Unit
}

@Singleton
class FeatureSteamCatalogResolutionDiagnostics @Inject constructor() :
    SteamCatalogResolutionDiagnosticSink {
    override fun record(event: SteamResolutionDiagnosticEvent) {
        try {
            FeatureDiagnostics.record(
                area = DiagnosticArea.MATCHING,
                name = DiagnosticEventName.MATCH_RESOLUTION,
                outcome = event.result.diagnosticOutcome(),
                attributes = buildMap {
                    event.source?.let { put(DiagnosticAttribute.SOURCE, it.name) }
                    put(DiagnosticAttribute.CONFIDENCE, event.result.diagnosticCategory())
                    put(DiagnosticAttribute.RESULT_COUNT, event.completed.boundedCount())
                    put(DiagnosticAttribute.CANONICAL_COUNT, event.total.boundedCount())
                    event.errorType?.let { put(DiagnosticAttribute.ERROR_TYPE, it) }
                },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Diagnostics are best effort and never affect catalog resolution.
        }
    }

    private fun SteamResolutionItemResult.diagnosticOutcome(): DiagnosticOutcome = when (this) {
        SteamResolutionItemResult.AutoAccepted -> DiagnosticOutcome.SUCCEEDED
        is SteamResolutionItemResult.CompleteNoPlausibleSteamMatch -> DiagnosticOutcome.SUCCEEDED
        SteamResolutionItemResult.ReviewRequired -> DiagnosticOutcome.DEFERRED
        SteamResolutionItemResult.ExpectedStateChanged -> DiagnosticOutcome.SKIPPED
        SteamResolutionItemResult.ProviderUnavailable -> DiagnosticOutcome.UNAVAILABLE
    }

    private fun SteamResolutionItemResult.diagnosticCategory(): String = when (this) {
        SteamResolutionItemResult.AutoAccepted -> "AUTO_ACCEPTED"
        SteamResolutionItemResult.ReviewRequired -> "REVIEW_REQUIRED"
        is SteamResolutionItemResult.CompleteNoPlausibleSteamMatch -> when {
            epicPresentation == EpicPresentationOutcome.EPIC_CMS_PERSISTED &&
                pcGamingWikiEvidence != null -> "EPIC_CMS_PERSISTED_PCGW_CORROBORATED"
            epicPresentation == EpicPresentationOutcome.EPIC_CMS_PERSISTED ->
                "EPIC_CMS_PERSISTED"
            epicPresentation == EpicPresentationOutcome.EPIC_CMS_UNAVAILABLE ->
                "EPIC_CMS_UNAVAILABLE_AFTER_COMPLETE_STEAM_MISS"
            else -> "COMPLETE_NO_PLAUSIBLE_STEAM_MATCH"
        }
        SteamResolutionItemResult.ExpectedStateChanged -> "EXPECTED_STATE_CHANGED"
        SteamResolutionItemResult.ProviderUnavailable -> "PROVIDER_UNAVAILABLE"
    }

    private fun Int.boundedCount(): String = coerceIn(0, MAX_COUNT).toString()

    private companion object {
        const val MAX_COUNT = 1_000_000
    }
}
