package app.gamenative.ui.model

import android.app.Application
import android.net.Uri
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.library.canonical.CanonicalCardKey
import app.gamenative.library.canonical.CanonicalLibraryCard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.ContinuationInterceptor

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class AggregationDiagnosticViewModelTest {
    private lateinit var scheduler: TestCoroutineScheduler
    private val destination = Uri.parse("content://documents/report.txt")

    @Before fun setUp() {
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler, "main"))
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun loadingRunsOnIoAndExportsTheExactCompletedStringWithoutRecomputing() = runTest(scheduler) {
        val io = StandardTestDispatcher(scheduler, "io")
        val report = "Aggregation report\nPublic title: DREDGE\nUnicode: é\n"
        val completed = CompletableDeferred<String>()
        var loads = 0
        val writes = mutableListOf<Pair<Uri, String>>()
        val model = AggregationDiagnosticViewModel(
            explain = { loads++; assertEquals(io, currentCoroutineContext()[ContinuationInterceptor]); completed.await() },
            write = { uri, text -> assertEquals(io, currentCoroutineContext()[ContinuationInterceptor]); writes += uri to text },
            ioDispatcher = io,
        )
        model.open(card())
        assertEquals(AggregationDiagnosticState.Loading, model.state.value)
        assertNull(model.prepareExport())
        runCurrent()
        completed.complete(report)
        runCurrent()
        assertEquals(report, (model.state.value as AggregationDiagnosticState.Content).report)
        val request = model.prepareExport()!!
        assertNull(model.prepareExport())
        model.completeExport(request, destination)
        runCurrent()
        assertEquals(listOf(destination to report), writes)
        assertEquals(1, loads)
        assertEquals(AggregationExportStatus.SUCCEEDED, (model.state.value as AggregationDiagnosticState.Content).exportStatus)
        model.close()
    }

    @Test fun closeCancelsLoadingWithoutPublishingFailure() = runTest(scheduler) {
        val cancelled = CompletableDeferred<Unit>()
        val model = AggregationDiagnosticViewModel(
            explain = { try { awaitCancellation() } finally { cancelled.complete(Unit) } },
            write = { _, _ -> }, ioDispatcher = StandardTestDispatcher(scheduler),
        )
        model.open(card())
        runCurrent()
        model.close()
        runCurrent()
        assertTrue(cancelled.isCompleted)
        assertEquals(AggregationDiagnosticState.Closed, model.state.value)
    }

    @Test fun changedSelectionAndLateNonCooperativeCompletionCannotReviveOldReport() = runTest(scheduler) {
        val old = CompletableDeferred<String>()
        val model = AggregationDiagnosticViewModel(
            explain = { selected -> if (selected == card()) withContext(NonCancellable) { old.await() } else "New report" },
            write = { _, _ -> }, ioDispatcher = StandardTestDispatcher(scheduler),
        )
        model.open(card())
        runCurrent()
        model.bindSelection(card("22222222-2222-2222-2222-222222222222"))
        assertEquals(AggregationDiagnosticState.Closed, model.state.value)
        model.retry()
        runCurrent()
        old.complete("Old report")
        runCurrent()
        assertEquals("New report", (model.state.value as AggregationDiagnosticState.Content).report)
        model.close()
    }

    @Test fun failuresAreFixedStatesAndRetryUsesCurrentSelection() = runTest(scheduler) {
        var fail = true
        val model = AggregationDiagnosticViewModel(
            explain = { if (fail) error("private path or account must not surface") else "Safe report" },
            write = { _, _ -> error("private export path must not surface") },
            ioDispatcher = StandardTestDispatcher(scheduler),
        )
        model.open(card())
        runCurrent()
        assertEquals(AggregationDiagnosticState.Failed, model.state.value)
        assertNull(model.prepareExport())
        fail = false
        model.retry()
        runCurrent()
        model.completeExport(model.prepareExport()!!, destination)
        runCurrent()
        val content = model.state.value as AggregationDiagnosticState.Content
        assertEquals("Safe report", content.report)
        assertEquals(AggregationExportStatus.FAILED, content.exportStatus)
        model.close()
    }

    @Test fun pickerCancellationAndStalePickerResultsNeverWrite() = runTest(scheduler) {
        val writes = mutableListOf<String>()
        val model = AggregationDiagnosticViewModel(
            explain = { "Safe report" }, write = { _, text -> writes += text },
            ioDispatcher = StandardTestDispatcher(scheduler),
        )
        model.open(card())
        runCurrent()
        val cancelled = model.prepareExport()!!
        model.completeExport(cancelled, null)
        assertEquals(AggregationExportStatus.IDLE, (model.state.value as AggregationDiagnosticState.Content).exportStatus)
        val next = model.prepareExport()!!
        model.completeExport(cancelled, destination)
        assertEquals(AggregationExportStatus.CHOOSING, (model.state.value as AggregationDiagnosticState.Content).exportStatus)
        model.close()
        model.open(card())
        runCurrent()
        model.completeExport(next, destination)
        runCurrent()
        assertTrue(writes.isEmpty())
        model.close()
    }

    @Test fun closeCancelsExportAndLateCompletionDoesNotReplaceNewReport() = runTest(scheduler) {
        val cancelled = CompletableDeferred<Unit>()
        val model = AggregationDiagnosticViewModel(
            explain = { "Safe report" },
            write = { _, _ -> try { awaitCancellation() } finally { cancelled.complete(Unit) } },
            ioDispatcher = StandardTestDispatcher(scheduler),
        )
        model.open(card())
        runCurrent()
        model.completeExport(model.prepareExport()!!, destination)
        runCurrent()
        model.close()
        runCurrent()
        assertTrue(cancelled.isCompleted)
        assertEquals(AggregationDiagnosticState.Closed, model.state.value)
    }

    @Test fun productionTextWriterPreservesUtf8BytesAndClosesDocumentStream() {
        var closed = false
        val output = object : java.io.ByteArrayOutputStream() {
            override fun close() { closed = true; super.close() }
        }
        val report = "Exact completed report\nUnicode: é Ω\n"
        writeAggregationReport(output, report)
        assertTrue(report.toByteArray(Charsets.UTF_8).contentEquals(output.toByteArray()))
        assertTrue(closed)
        try {
            writeAggregationReport(null, report)
            org.junit.Assert.fail("Missing document output must fail")
        } catch (_: java.io.IOException) { }
    }

    @Test fun pickerLaunchFailureIsFixedAndCanBeRetried() = runTest(scheduler) {
        val model = AggregationDiagnosticViewModel(explain = { "Safe report" }, write = { _, _ -> },
            ioDispatcher = StandardTestDispatcher(scheduler))
        model.open(card())
        runCurrent()
        val failed = model.prepareExport()!!
        model.exportLaunchFailed(failed)
        assertEquals(AggregationExportStatus.FAILED, (model.state.value as AggregationDiagnosticState.Content).exportStatus)
        val next = model.prepareExport()!!
        model.exportLaunchFailed(failed)
        assertEquals(AggregationExportStatus.CHOOSING, (model.state.value as AggregationDiagnosticState.Content).exportStatus)
        model.completeExport(next, null)
        model.close()
    }

    @Test fun lateNonCooperativeExportCannotPublishSuccessIntoAnotherSession() = runTest(scheduler) {
        val completed = CompletableDeferred<Unit>()
        val model = AggregationDiagnosticViewModel(explain = { "Safe report" },
            write = { _, _ -> withContext(NonCancellable) { completed.await() } },
            ioDispatcher = StandardTestDispatcher(scheduler))
        model.open(card())
        runCurrent()
        model.completeExport(model.prepareExport()!!, destination)
        runCurrent()
        model.close()
        model.open(card())
        runCurrent()
        completed.complete(Unit)
        runCurrent()
        assertEquals(AggregationExportStatus.IDLE, (model.state.value as AggregationDiagnosticState.Content).exportStatus)
        model.close()
    }

    private fun card(uuid: String = "11111111-1111-1111-1111-111111111111"): CanonicalLibraryCard {
        val id = CanonicalGameId.parse(uuid)
        return CanonicalLibraryCard(
            key = CanonicalCardKey.Grouped(id), canonicalId = id, displayName = "Synthetic game",
            appType = CanonicalAppType.GAME, iconUrl = "", capsuleImageUrl = "", headerImageUrl = "", heroImageUrl = "",
            gridHeroImageScale = 1f, aliases = emptySet(), ownedSources = setOf(GameSource.GOG), copies = emptyList(),
            preferredCopy = null, steamCollectionAppIds = emptySet(), isShared = false,
        )
    }
}
