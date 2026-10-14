package app.gamenative.diagnostics

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [29])
class FocusedDiagnosticCaptureTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun disabledByDefaultAndPublicTitleFilterIsCaseInsensitiveSubstring() {
        val capture = capture()
        capture.record(event("DREDGE"))
        assertTrue(capture.report().events.isEmpty())
        assertTrue(capture.start(" dredge "))
        capture.record(event("DREDGE: The Pale Reach"))
        capture.record(event("Other game"))
        assertEquals(listOf("DREDGE: The Pale Reach"), capture.report().events.map { it.attributes["public_title"] })
    }

    @Test
    fun stopAndRecreationPreserveHistoryButDoNotResumeStoppedCapture() {
        val capture = capture()
        assertTrue(capture.start("DREDGE"))
        capture.record(event("DREDGE"))
        assertTrue(capture.stop())
        capture.record(event("DREDGE"))
        val recreated = capture()
        assertFalse(recreated.state().enabled)
        recreated.record(event("DREDGE"))
        assertEquals(1, recreated.report().events.size)
    }

    @Test
    fun enabledConfigurationAndChronologicalEvidenceSurviveRecreation() {
        val capture = capture()
        assertTrue(capture.start("DREDGE"))
        capture.record(event("DREDGE", "first"))
        val recreated = capture()
        assertTrue(recreated.state().enabled)
        recreated.record(event("DREDGE", "second"))
        assertEquals(listOf("first", "second"), recreated.report().events.map { it.attributes["stage"] })
    }

    @Test
    fun newCaptureAndClearResetHistoryAndAssociations() {
        val capture = capture()
        capture.start("DREDGE")
        capture.record(event("DREDGE"))
        assertTrue(capture.start("Pilgrims"))
        capture.record(event("DREDGE"))
        capture.record(event("Pilgrims"))
        assertEquals(1, capture.report().events.size)
        assertTrue(capture.clear())
        assertTrue(capture.report().events.isEmpty())
        assertTrue(capture.state().enabled)
    }

    @Test
    fun fullCapturePausesWithoutRotatingAwayItsBeginningAndRemainsFullAfterRestart() {
        val capture = capture(maxBytes = 1_024)
        capture.start("DREDGE")
        repeat(100) { capture.record(event("DREDGE", "step-$it")) }
        assertTrue(capture.state().full)
        val retained = capture.report().events
        assertEquals("step-0", retained.first().attributes["stage"])
        assertTrue(retained.size in 1..99)
        assertTrue(File(temporary.root, "feature-events.0.jsonl").length() <= 1_024)
        val recreated = capture(maxBytes = 1_024)
        assertTrue(recreated.state().full)
        recreated.record(event("DREDGE", "later"))
        assertEquals(retained, recreated.report().events)
    }

    @Test
    fun onlyOwnedContentAssociatesAnonymousMetricsWithoutTransitiveCaptureOfOtherGames() {
        val capture = capture()
        capture.start("DREDGE")
        capture.record(event("DREDGE").copy(attributes = mapOf(
            "public_title" to "DREDGE", "public_content_id" to "aaaaaaaaaaaaaaaa",
        )))
        capture.record(event("Other game").copy(attributes = mapOf(
            "public_title" to "Other game", "public_content_id" to "aaaaaaaaaaaaaaaa",
        )))
        capture.record(event("").copy(attributes = mapOf(
            "public_content_id" to "aaaaaaaaaaaaaaaa/bbbbbbbbbbbbbbbb",
        )))
        capture.record(event("").copy(attributes = mapOf(
            "public_content_id" to "bbbbbbbbbbbbbbbb/cccccccccccccccc",
        )))
        assertEquals(2, capture.report().events.size)
    }

    @Test
    fun unsafeFilterIsRejectedAndLoadedEventsAreSanitizedAgain() {
        val capture = capture()
        assertTrue(capture.start("DREDGE"))
        assertFalse(capture.start("token=synthetic-secret"))
        val unsafe = event("DREDGE").copy(attributes = mapOf(
            "public_title" to "DREDGE", "account_id" to "private-account",
            "reason" to "password=synthetic-secret",
        ))
        File(temporary.root, "feature-events.0.jsonl").appendText(Json.encodeToString(unsafe) + "\n")
        val report = capture.report()
        assertFalse(report.events.toString().contains("synthetic-secret"))
        assertFalse(report.events.toString().contains("private-account"))
        assertEquals("[redacted]", report.events.single().attributes["reason"])
    }

    @Test
    fun corruptRecordIsReportedAsIncompleteInsteadOfSilentlyDisappearing() {
        val capture = capture()
        capture.start("DREDGE")
        capture.record(event("DREDGE"))
        File(temporary.root, "feature-events.0.jsonl").appendText("{truncated\n")
        val report = capture.report()
        assertEquals(1, report.events.size)
        assertTrue(report.state.writeFailures > 0)
    }

    @Test
    fun persistenceFailureIsReportedWithoutThrowingIntoObservedFeature() {
        val capture = capture()
        assertTrue(capture.start("DREDGE"))
        assertTrue(File(temporary.root, "feature-events.0.jsonl").mkdir())
        capture.record(event("DREDGE"))
        assertTrue(capture.state().writeFailures > 0)
        assertTrue(capture.report().events.isEmpty())
    }

    private fun capture(maxBytes: Long = 8L * 1024L * 1024L): FocusedDiagnosticCapture {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val preferences = context.getSharedPreferences(temporary.root.name, Context.MODE_PRIVATE)
        return FocusedDiagnosticCapture(temporary.root, preferences, maxBytes)
    }

    private fun event(title: String, stage: String = "QUEUED") = DiagnosticEvent(
        timestampEpochMs = 1, sessionId = "synthetic", area = DiagnosticArea.MATCHING,
        name = DiagnosticEventName.GAME_RESOLUTION, outcome = DiagnosticOutcome.STARTED,
        attributes = mapOf("public_title" to title, "stage" to stage),
    )
}
