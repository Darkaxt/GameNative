package app.gamenative.library.canonical.catalog

import app.gamenative.data.GameSource
import app.gamenative.diagnostics.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SteamCatalogResolutionDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun publicRateLimitedStateSurvivesFloodAndSuccessfulRetryClearsCooldownAttributes() {
        val field = FeatureDiagnostics::class.java.getDeclaredField("store").apply { isAccessible = true }
        val previous = field.get(null)
        try {
            field.set(null, DiagnosticLogStore(temporary.newFolder()))
            FeatureDiagnostics.clear()
            val facade = FeatureSteamCatalogResolutionDiagnostics()
            facade.record(SteamResolutionDiagnosticEvent(SteamResolutionItemResult.RateLimited(120_000),
                GameSource.GOG, 0, 136, 0, "RATE_LIMIT_EXHAUSTED", "DREDGE", 136))
            repeat(1_500) { FeatureDiagnostics.record(DiagnosticArea.MATCHING, DiagnosticEventName.MATCH_RESOLUTION, DiagnosticOutcome.SKIPPED) }
            val state = FeatureDiagnostics.currentGameStates().single()
            assertEquals(DiagnosticOutcome.DEFERRED, state.outcome)
            assertEquals("DREDGE", state.attributes["public_title"])
            assertEquals("0", state.attributes["result_count"])
            assertEquals("136", state.attributes["deferred_count"])
            assertEquals("120000", state.attributes["retry_after_ms"])
            assertFalse(state.toString().contains("account"))
            facade.record(SteamResolutionDiagnosticEvent(SteamResolutionItemResult.AutoAccepted,
                GameSource.GOG, 1, 136, 0, publicTitle = "DREDGE"))
            val resolved = FeatureDiagnostics.currentGameStates().single()
            assertEquals(DiagnosticOutcome.SUCCEEDED, resolved.outcome)
            assertEquals("AUTO_ACCEPTED", resolved.attributes["confidence"])
            assertFalse(resolved.attributes.containsKey("error_type"))
            assertFalse(resolved.attributes.containsKey("retry_after_ms"))
        } finally { FeatureDiagnostics.clear(); field.set(null, previous) }
    }
}
