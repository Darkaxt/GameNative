package app.gamenative.diagnostics

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [29])
class GroupingDiagnosticExportTest {
    @Test
    fun retainedSnapshotsAreBoundedMergedSanitizedAndCleared() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        FeatureDiagnostics.initialize(context)
        FeatureDiagnostics.clear()
        try {
            repeat(8_200) { index ->
                FeatureDiagnostics.record(DiagnosticArea.MATCHING, DiagnosticEventName.GAME_RESOLUTION,
                    DiagnosticOutcome.STARTED, attributes = mapOf(
                        DiagnosticAttribute.OPERATION to "ARTWORK_TRACE",
                        DiagnosticAttribute.CORRELATION_ID to "generation1-$index",
                        DiagnosticAttribute.PUBLIC_TITLE to "Public Game $index",
                        DiagnosticAttribute.STAGE to "QUEUED",
                    ))
            }
            org.junit.Assert.assertEquals(8_192, FeatureDiagnostics.currentGameStates().size)
            org.junit.Assert.assertFalse(FeatureDiagnostics.currentGameStates().any { it.attributes["public_title"] == "Public Game 0" })
            FeatureDiagnostics.record(DiagnosticArea.MATCHING, DiagnosticEventName.GAME_RESOLUTION,
                DiagnosticOutcome.SUCCEEDED, attributes = mapOf(
                    DiagnosticAttribute.OPERATION to "ARTWORK_TRACE",
                    DiagnosticAttribute.CORRELATION_ID to "generation1-8199",
                    DiagnosticAttribute.STAGE to "FINISHED",
                    DiagnosticAttribute.REASON to "token=synthetic-secret",
                    DiagnosticAttribute.FAMILY_SIZE to "2",
                ))
            val state = FeatureDiagnostics.currentGameStates().last()
            org.junit.Assert.assertEquals("Public Game 8199", state.attributes["public_title"])
            org.junit.Assert.assertEquals("FINISHED", state.attributes["stage"])
            org.junit.Assert.assertFalse(state.toString().contains("synthetic-secret"))
            FeatureDiagnostics.clear()
            assertTrue(FeatureDiagnostics.currentGameStates().isEmpty())
        } finally { FeatureDiagnostics.clear() }
    }

    @Test
    fun publicGameStateSurvivesPairEventFloodInManualExport() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        FeatureDiagnostics.initialize(context)
        FeatureDiagnostics.clear()
        try {
            FeatureDiagnostics.record(DiagnosticArea.MATCHING, DiagnosticEventName.GAME_RESOLUTION,
                DiagnosticOutcome.STARTED, attributes = mapOf(
                    DiagnosticAttribute.PUBLIC_TITLE to "DREDGE",
                    DiagnosticAttribute.SOURCE to "GOG",
                    DiagnosticAttribute.OPERATION to "ARTWORK_TRACE",
                    DiagnosticAttribute.CORRELATION_ID to "generation1-game1",
                    DiagnosticAttribute.REASON to "QUEUED",
                ))
            repeat(1_500) {
                FeatureDiagnostics.record(DiagnosticArea.MATCHING, DiagnosticEventName.MATCH_RESOLUTION,
                    DiagnosticOutcome.SKIPPED, attributes = mapOf(DiagnosticAttribute.OPERATION to "ORB_V1"))
            }
            val report = DiagnosticReportBuilder.build(context)
            assertTrue("A queued game's trace must survive the bounded chronological tail", report.contains("DREDGE"))
            assertTrue(report.contains("QUEUED"))
        } finally {
            FeatureDiagnostics.clear()
        }
    }
}
