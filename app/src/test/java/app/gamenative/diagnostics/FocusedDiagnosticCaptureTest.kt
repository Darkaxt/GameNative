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

    @Test
    fun schedulerReportFlushesLatestThrottledSnapshotWithHonestAgesAndTimestamps() {
        var now = 0L
        val capture = capture(monotonicClockMs = { now })
        capture.start("DREDGE")
        capture.recordScheduler(scheduler(producer = "ACQUIRE"))
        now = 100L
        capture.recordScheduler(scheduler(producer = "CACHE_WRITE", rank = 2))
        now = 250L
        val latest = scheduler(producer = "QUEUE_SEND", rank = 3).copy(durationMs = 250L,
            attributes = scheduler(producer = "QUEUE_SEND", rank = 3).attributes +
                ("producer_elapsed_ms" to "80"))
        capture.recordScheduler(latest)
        assertEquals(1, persistedEvents().size)
        now = 1_250L
        val beforeReport = System.currentTimeMillis()
        val report = capture.report()
        assertEquals(2, report.events.size)
        val snapshot = report.events.last()
        assertEquals("CAPTURE_SNAPSHOT", snapshot.attributes["stage"])
        assertEquals("QUEUE_SEND", snapshot.attributes["producer_phase"])
        assertEquals("3", snapshot.attributes["producer_rank"])
        assertEquals("80", snapshot.attributes["producer_elapsed_ms"])
        assertEquals("1000", snapshot.attributes["snapshot_age_ms"])
        assertEquals(250L, snapshot.durationMs)
        assertTrue(snapshot.timestampEpochMs >= beforeReport)
        assertTrue(snapshot.timestampEpochMs >= report.events.first().timestampEpochMs)
        assertEquals(report.events, capture.report().events)
        now = 1_500L
        val later = capture.report().events
        assertEquals(3, later.size)
        assertEquals("1250", later.last().attributes["snapshot_age_ms"])
        assertEquals("80", later.last().attributes["producer_elapsed_ms"])
        assertEquals(250L, later.last().durationMs)
        assertEquals(later, capture.report().events)
    }

    @Test
    fun persistedFullQueuePhaseStartGetsAgedEvidenceOnReportAndStopWithoutSameInstantDuplicates() {
        for (stopWithoutReport in listOf(false, true)) {
            var now = 0L
            val capture = capture(monotonicClockMs = { now })
            capture.start("DREDGE")
            val phaseStart = scheduler(producer = "QUEUE_SEND", rank = 66, queueSize = 64).copy(durationMs = 0L,
                attributes = scheduler(producer = "QUEUE_SEND", rank = 66, queueSize = 64).attributes +
                    ("producer_elapsed_ms" to "0"))
            capture.recordScheduler(phaseStart)
            assertEquals(1, persistedEvents().size)
            // The original write already represents this exact monotonic instant.
            assertEquals(1, capture.report().events.size)
            now = 5_000L
            val beforeExport = System.currentTimeMillis()
            if (stopWithoutReport) assertTrue(capture.stop())
            val events = capture.report().events
            assertEquals(2, events.size)
            val snapshot = events.last()
            assertEquals("CAPTURE_SNAPSHOT", snapshot.attributes["stage"])
            assertEquals("5000", snapshot.attributes["snapshot_age_ms"])
            assertEquals("0", snapshot.attributes["producer_elapsed_ms"])
            assertEquals(0L, snapshot.durationMs)
            assertEquals(phaseStart.attributes - "stage", snapshot.attributes - "stage" - "snapshot_age_ms")
            assertTrue(snapshot.timestampEpochMs >= beforeExport)
            assertTrue(snapshot.timestampEpochMs >= events.first().timestampEpochMs)
            assertEquals(events, capture.report().events)
            assertTrue(capture.stop())
            assertEquals(events, capture.report().events)
        }
    }

    @Test
    fun persistedTerminalSnapshotsDoNotInventOngoingAgeEvidence() {
        for (stage in listOf("FINISHED", "CANCELLED", "FAILED")) {
            var now = 0L
            val capture = capture(monotonicClockMs = { now })
            capture.start("DREDGE")
            capture.recordScheduler(scheduler(stage = stage))
            val terminal = persistedEvents()
            assertEquals(1, terminal.size)
            now = 5_000L
            assertEquals(terminal, capture.report().events)
            assertTrue(capture.stop())
            assertEquals(terminal, capture.report().events)
        }
    }

    @Test
    fun schedulerStopFlushesBeforeDisablingAndCannotReplayAfterStop() {
        var now = 0L
        val capture = capture(monotonicClockMs = { now })
        capture.start("DREDGE")
        capture.recordScheduler(scheduler())
        now = 100L
        capture.recordScheduler(scheduler(producer = "CACHE_WRITE", rank = 2))
        now = 400L
        assertTrue(capture.stop())
        capture.recordScheduler(scheduler(rank = 3))
        val events = capture.report().events
        assertEquals(2, events.size)
        assertEquals("CAPTURE_SNAPSHOT", events.last().attributes["stage"])
        assertEquals("300", events.last().attributes["snapshot_age_ms"])
        assertEquals("2", events.last().attributes["producer_rank"])
        assertEquals(events, capture.report().events)
    }

    @Test
    fun schedulerProgressIsThrottledExceptGenerationTerminalAndNewFullQueueBlocks() {
        var now = 0L
        val capture = capture(monotonicClockMs = { now })
        capture.start("DREDGE")
        capture.recordScheduler(scheduler())
        now = 1_999L
        capture.recordScheduler(scheduler(rank = 2))
        assertEquals(1, persistedEvents().size)
        now = 2_000L
        capture.recordScheduler(scheduler(rank = 3))
        assertEquals(2, persistedEvents().size)
        capture.recordScheduler(scheduler(producer = "QUEUE_SEND", rank = 66, queueSize = 64))
        assertEquals(3, persistedEvents().size)
        capture.recordScheduler(scheduler(producer = "QUEUE_SEND", rank = 66, queueSize = 64))
        assertEquals(3, persistedEvents().size)
        capture.recordScheduler(scheduler(producer = "QUEUE_SEND", rank = 67, queueSize = 64))
        assertEquals(4, persistedEvents().size)
        capture.recordScheduler(scheduler(generation = "2"))
        assertEquals(5, persistedEvents().size)
        capture.recordScheduler(scheduler(generation = "2", stage = "FINISHED"))
        assertEquals(6, persistedEvents().size)
        assertEquals(DiagnosticOutcome.SUCCEEDED, capture.report().events.last().outcome)
        capture.recordScheduler(scheduler(generation = "3", stage = "CANCELLED"))
        assertEquals(DiagnosticOutcome.SKIPPED, capture.report().events.last().outcome)
        capture.recordScheduler(scheduler(generation = "4", stage = "FAILED"))
        assertEquals(DiagnosticOutcome.FAILED, capture.report().events.last().outcome)
    }

    @Test
    fun schedulerWhitelistDropsInjectedIdentitiesFailuresAndInvalidTypedValuesWithoutAssociatingGames() {
        val capture = capture()
        capture.start("DREDGE")
        val injected = scheduler().copy(attributes = scheduler().attributes + mapOf(
            "public_title" to "Unrelated game", "public_url" to "https://images.gog.com/unrelated.jpg",
            "public_url_id" to "aaaaaaaaaaaaaaaa", "public_content_id" to "bbbbbbbbbbbbbbbb",
            "canonical_id" to "canonical-private", "account_id" to "account-private",
            "reason" to "arbitrary failure string", "error_type" to "arbitrary failure string",
            "preparation_phase" to "PRIVATE TITLE", "consumer_rank" to "not-a-number",
            "index_progress" to "-1", "completed_count" to "token=private",
        ))
        capture.recordScheduler(injected)
        capture.record(event("").copy(attributes = mapOf("public_url_id" to "aaaaaaaaaaaaaaaa")))
        capture.record(event("").copy(attributes = mapOf("public_content_id" to "bbbbbbbbbbbbbbbb")))
        capture.record(event("Unrelated game"))
        val events = capture.report().events
        assertEquals(1, events.size)
        assertEquals(scheduler().attributes - "consumer_rank", events.single().attributes)
        val text = events.toString()
        listOf("Unrelated game", "unrelated.jpg", "aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", "private",
            "arbitrary failure", "PRIVATE TITLE", "not-a-number").forEach { assertFalse(text.contains(it)) }
        // Persisted scheduler evidence is re-whitelisted too, rather than becoming an identity-bearing record on export.
        File(temporary.root, "feature-events.0.jsonl").appendText(Json.encodeToString(injected) + "\n")
        assertTrue(capture.report().events.all { "public_title" !in it.attributes && "public_url_id" !in it.attributes })
    }

    @Test
    fun schedulerRejectsDisabledAndMalformedRecords() {
        val capture = capture()
        capture.recordScheduler(scheduler())
        assertTrue(capture.report().events.isEmpty())
        capture.start("DREDGE")
        capture.recordScheduler(scheduler().copy(attributes = scheduler().attributes + ("correlation_id" to "private-game")))
        capture.recordScheduler(scheduler().copy(attributes = scheduler().attributes + ("stage" to "arbitrary failure")))
        capture.recordScheduler(scheduler().copy(attributes = scheduler().attributes + ("operation" to "OTHER")))
        assertTrue(capture.report().events.isEmpty())
        capture.stop()
        capture.recordScheduler(scheduler())
        assertTrue(capture.report().events.isEmpty())
    }

    @Test
    fun schedulerClearAndNewCaptureResetPendingSnapshotThrottleAndFullQueueBoundary() {
        var now = 0L
        val capture = capture(monotonicClockMs = { now })
        capture.start("DREDGE")
        capture.recordScheduler(scheduler())
        now = 100L
        capture.recordScheduler(scheduler(rank = 2))
        assertTrue(capture.clear())
        assertTrue(capture.report().events.isEmpty())
        capture.recordScheduler(scheduler(producer = "QUEUE_SEND", rank = 66, queueSize = 64))
        assertEquals(1, persistedEvents().size)
        capture.recordScheduler(scheduler(rank = 3))
        assertTrue(capture.start("Pilgrims"))
        assertTrue(capture.report().events.isEmpty())
        capture.recordScheduler(scheduler(producer = "QUEUE_SEND", rank = 66, queueSize = 64))
        assertEquals(1, persistedEvents().size)
        assertEquals("RUNNING", capture.report().events.single().attributes["stage"])
    }

    @Test
    fun schedulerFullCaptureDoesNotRotateOrRetainPendingSnapshots() {
        var now = 0L
        val capture = capture(maxBytes = 1_024, monotonicClockMs = { now })
        capture.start("DREDGE")
        repeat(20) {
            now += 2_000L
            capture.recordScheduler(scheduler(rank = it + 1))
        }
        assertTrue(capture.state().full)
        val retained = capture.report().events
        assertTrue(retained.isNotEmpty())
        assertEquals("1", retained.first().attributes["producer_rank"])
        assertTrue(File(temporary.root, "feature-events.0.jsonl").length() <= 1_024)
        capture.recordScheduler(scheduler(generation = "2"))
        assertTrue(capture.stop())
        assertEquals(retained, capture.report().events)
        assertTrue(capture(maxBytes = 1_024).state().full)
        assertTrue(capture.clear())
        assertFalse(capture.state().full)
        assertTrue(capture.report().events.isEmpty())
    }

    @Test
    fun schedulerPersistenceAndFlushFailuresAreReportedWithoutEscapingToTheFeature() {
        val capture = capture()
        capture.start("DREDGE")
        assertTrue(File(temporary.root, "feature-events.0.jsonl").mkdir())
        capture.recordScheduler(scheduler())
        assertTrue(capture.state().writeFailures > 0)
        capture.stop()
        assertFalse(capture.state().enabled)
        assertTrue(capture.report().events.isEmpty())
        assertTrue(capture.state().writeFailures > 0)
        assertTrue(capture.clear())
        assertEquals(0, capture.state().writeFailures)
        assertTrue(capture.report().events.isEmpty())
    }

    @Test
    fun featureArtworkScheduleAndSchedulerUseOnlyEnabledFocusedCaptureWithoutRetainingGameStates() {
        val capture = capture()
        val standard = DiagnosticLogStore(temporary.newFolder("standard"))
        val captureField = FeatureDiagnostics::class.java.getDeclaredField("focusedCapture").apply { isAccessible = true }
        val storeField = FeatureDiagnostics::class.java.getDeclaredField("store").apply { isAccessible = true }
        val previousCapture = captureField.get(null)
        val previousStore = storeField.get(null)
        val previousStates = FeatureDiagnostics.currentGameStates()
        val schedulerAttributes = mapOf(DiagnosticAttribute.OPERATION to "ARTWORK_SCHEDULER",
            DiagnosticAttribute.CORRELATION_ID to "1", DiagnosticAttribute.STAGE to "RUNNING",
            DiagnosticAttribute.PRODUCER_PHASE to "ACQUIRE", DiagnosticAttribute.PRODUCER_RANK to "1")
        val schedule = mapOf(DiagnosticAttribute.OPERATION to "ARTWORK_TRACE", DiagnosticAttribute.STAGE to "ORIGINAL_SCHEDULED",
            DiagnosticAttribute.PUBLIC_TITLE to "DREDGE", DiagnosticAttribute.ACQUISITION_RANK to "70",
            DiagnosticAttribute.REFERENCE_COUNT to "70", DiagnosticAttribute.CACHE_STATE to "MISSING")
        try {
            captureField.set(null, capture)
            storeField.set(null, standard)
            FeatureDiagnostics.recordArtworkSchedule(schedule)
            FeatureDiagnostics.recordArtworkScheduler(10L, schedulerAttributes)
            assertTrue(capture.report().events.isEmpty())
            capture.start("DREDGE")
            FeatureDiagnostics.recordArtworkSchedule(schedule + (DiagnosticAttribute.PUBLIC_TITLE to "Unrelated game"))
            FeatureDiagnostics.recordArtworkSchedule(schedule)
            FeatureDiagnostics.recordArtworkScheduler(10L, schedulerAttributes + (DiagnosticAttribute.PUBLIC_TITLE to "Unrelated game"))
            val events = capture.report().events
            assertEquals(2, events.size)
            assertEquals("70", events.first().attributes["acquisition_rank"])
            assertEquals(DiagnosticOutcome.STARTED, events.first().outcome)
            assertEquals(10L, events.last().durationMs)
            assertFalse(events.last().attributes.containsKey("public_title"))
            assertTrue(standard.recent(100).isEmpty())
            assertEquals(previousStates, FeatureDiagnostics.currentGameStates())
            capture.stop()
            FeatureDiagnostics.recordArtworkSchedule(schedule)
            FeatureDiagnostics.recordArtworkScheduler(20L, schedulerAttributes)
            assertEquals(events, capture.report().events)
        } finally {
            captureField.set(null, previousCapture)
            storeField.set(null, previousStore)
        }
    }

    private fun persistedEvents(): List<DiagnosticEvent> = File(temporary.root, "feature-events.0.jsonl")
        .takeIf { it.isFile }?.readLines().orEmpty().filter { it.isNotBlank() }
        .map { Json.decodeFromString<DiagnosticEvent>(it) }

    private fun scheduler(
        generation: String = "1", stage: String = "RUNNING", producer: String = "ACQUIRE",
        rank: Int = 1, queueSize: Int = 0,
    ) = DiagnosticEvent(
        timestampEpochMs = 1, sessionId = "synthetic", area = DiagnosticArea.MATCHING,
        name = DiagnosticEventName.GAME_RESOLUTION, outcome = DiagnosticOutcome.STARTED,
        attributes = mapOf("operation" to "ARTWORK_SCHEDULER", "correlation_id" to generation,
            "stage" to stage, "producer_phase" to producer, "producer_rank" to "$rank",
            "consumer_phase" to "COMPARE", "consumer_rank" to "1", "queue_size" to "$queueSize",
            "reference_count" to "70", "enqueued_count" to "65", "dequeued_count" to "1"),
    )

    private fun capture(
        maxBytes: Long = 8L * 1024L * 1024L,
        monotonicClockMs: () -> Long = { 0L },
    ): FocusedDiagnosticCapture {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val preferences = context.getSharedPreferences(temporary.root.name, Context.MODE_PRIVATE)
        return FocusedDiagnosticCapture(temporary.root, preferences, maxBytes, monotonicClockMs)
    }

    private fun event(title: String, stage: String = "QUEUED") = DiagnosticEvent(
        timestampEpochMs = 1, sessionId = "synthetic", area = DiagnosticArea.MATCHING,
        name = DiagnosticEventName.GAME_RESOLUTION, outcome = DiagnosticOutcome.STARTED,
        attributes = mapOf("public_title" to title, "stage" to stage),
    )
}
