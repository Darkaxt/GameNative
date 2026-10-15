package app.gamenative.diagnostics

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.*
import app.gamenative.library.canonical.*
import app.gamenative.library.canonical.artwork.*
import java.util.UUID
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [29])
class FocusedDiagnosticExportTest {
    @Test
    fun focusedOriginalMetricsAndPublicationSurviveAttributedPairFlood() {
        val context = startCapture()
        try {
            trace(DiagnosticOutcome.STARTED, "ORIGINAL_FETCHING", mapOf(
                DiagnosticAttribute.PUBLIC_URL_ID to URL_ID,
                DiagnosticAttribute.PUBLIC_URL to URL,
            ))
            record(DiagnosticOutcome.STARTED, mapOf(
                DiagnosticAttribute.OPERATION to "ARTWORK_ORIGINAL",
                DiagnosticAttribute.PUBLIC_URL_ID to URL_ID,
                DiagnosticAttribute.PUBLIC_URL to URL,
                DiagnosticAttribute.STAGE to "FETCHING",
            ))
            record(DiagnosticOutcome.SUCCEEDED, mapOf(
                DiagnosticAttribute.OPERATION to "ARTWORK_ORIGINAL",
                DiagnosticAttribute.PUBLIC_URL_ID to URL_ID,
                DiagnosticAttribute.PUBLIC_CONTENT_ID to CONTENT,
                DiagnosticAttribute.STAGE to "FEATURES_READY",
            ))
            record(DiagnosticOutcome.SUCCEEDED, mapOf(
                DiagnosticAttribute.MATCH_METHOD to "ORB_V1",
                DiagnosticAttribute.PUBLIC_CONTENT_ID to "$CONTENT/$OTHER_CONTENT",
                DiagnosticAttribute.CONFIDENCE to "inliers=90;area=416",
            ))
            trace(DiagnosticOutcome.SUCCEEDED, "COMPARED", mapOf(
                DiagnosticAttribute.CONFIDENCE to "pair=$CONTENT/$OTHER_CONTENT",
                DiagnosticAttribute.MATCH_RESULT to "ACCEPTED",
            ))
            trace(DiagnosticOutcome.SUCCEEDED, "FAMILY_PUBLISHED", mapOf(
                DiagnosticAttribute.FAMILY_SIZE to "2",
                DiagnosticAttribute.REASON to "token=synthetic-secret",
            ))
            repeat(5_000) { index ->
                record(DiagnosticOutcome.CACHE_HIT, mapOf(
                    DiagnosticAttribute.OPERATION to "ARTWORK_TRACE",
                    DiagnosticAttribute.PUBLIC_TITLE to "Other Game $index",
                    DiagnosticAttribute.CORRELATION_ID to "2-$index",
                    DiagnosticAttribute.CONFIDENCE to "pair=1111111111111111/2222222222222222",
                    DiagnosticAttribute.MATCH_RESULT to "REJECTED",
                ))
            }
            val focused = focusedEvents(DiagnosticReportBuilder.build(context))
            assertEquals("The focused chain must be independent of global snapshot eviction", 6, focused.size)
            assertTrue(focused.any { it.attributes["stage"] == "FETCHING" })
            assertTrue(focused.any { it.attributes["confidence"] == "inliers=90;area=416" })
            assertTrue(focused.any { it.attributes["family_size"] == "2" })
            assertFalse(focused.toString().contains("Other Game"))
            assertFalse(focused.toString().contains("synthetic-secret"))
        } finally { FeatureDiagnostics.clear() }
    }

    @Test
    fun warmTypedBindingsAdmitTitleFreeOriginalMetricsAndActualGroupingCause() {
        val context = startCapture()
        try {
            val source = GameSource.GOG
            val key = OwnedCopyKey(AccountScope("2".repeat(64)), source, "1")
            val id = CanonicalGameId.parse(UUID(0, 1).toString())
            val copy = OwnedCopySummary(key, source, "DREDGE", null, null, null,
                false, false, false, false, false, null, null, emptySet(), null, true,
                MatchMethod.UNMATCHED, MatchConfidence.UNMATCHED,
                MatchDecisionSource.AUTOMATIC, null,
                CURRENT_RESOLVER_VERSION, 100)
            val card = CanonicalLibraryCard(
                CanonicalCardKey.Grouped(id), id, "DREDGE",
                CanonicalAppType.GAME, "", "", "", "", 1f, emptySet(), setOf(source),
                listOf(copy), null, emptySet(), false)
            val game = requireNotNull(ArtworkGameTrace.from(card, 1, 0))
            val facade = FeatureCanonicalLibraryDiagnostics()
            val refs = listOf("a", "b").map { ArtworkReference(source, "https://images.gog.com/warm-$it.jpg") }
            val images = refs.indices.map { index -> ArtworkFeatures(
                "${index + 1}".repeat(64), 128, 128, FloatArray(48) { .5f }, ByteArray(24 * 32)) }
            refs.zip(images).forEach { (ref, image) ->
                facade.artworkState(game, ArtworkTraceObservation.of(
                    ArtworkTraceStage.ORIGINAL_CACHE_BOUND, ref, contentHash = image.contentHash))
            }
            facade.artworkCompared(ArtworkPairDigest.from(images[0], images[1]), ArtworkMatch(
                false, 30, 25, .8, .02, 3, 1.0, reason = ArtworkMatchReason.LOW_COVERAGE), 1L)
            facade.artworkState(game, ArtworkTraceObservation.of(
                ArtworkTraceStage.FAMILY_PUBLISHED, familySize = 2,
                groupingReason = FamilyGroupingReason.TITLE))
            val events = focusedEvents(DiagnosticReportBuilder.buildFocused(context))
            assertEquals(2, events.count { it.attributes["stage"] == "ORIGINAL_CACHE_BOUND" })
            assertTrue(events.any { it.attributes["reason"] == "LOW_COVERAGE" && it.attributes["public_title"] == null })
            assertTrue(events.any { it.attributes["grouping_reason"] == "TITLE" && it.attributes["family_size"] == "2" })
            assertFalse(events.toString().contains(key.accountScope.value))
        } finally { FeatureDiagnostics.clear() }
    }

    @Test
    fun completeFocusedHistoryIsNotTheGlobalThousandEventTail() {
        val context = startCapture()
        try {
            repeat(1_500) { index ->
                trace(DiagnosticOutcome.CACHE_HIT, "CACHE_HIT", mapOf(
                    DiagnosticAttribute.COMPARISON_COUNT to index.toString(),
                ))
            }
            val focused = focusedEvents(DiagnosticReportBuilder.build(context, eventLimit = 1))
            assertEquals("Export every retained focused event, not only the global tail", 1_500, focused.size)
            assertEquals("0", focused.first().attributes["comparison_count"])
            assertEquals("1499", focused.last().attributes["comparison_count"])
        } finally { FeatureDiagnostics.clear() }
    }

    @Test
    fun settingsFacadeStopsWithoutErasingAndFocusedExportExcludesUnrelatedHistory() {
        val context = startCapture()
        try {
            trace(DiagnosticOutcome.STARTED, "QUEUED")
            record(DiagnosticOutcome.SUCCEEDED, mapOf(DiagnosticAttribute.PUBLIC_TITLE to "Other Game"))
            assertTrue(FeatureDiagnostics.stopFocusedCapture())
            trace(DiagnosticOutcome.SUCCEEDED, "FINISHED")
            val report = DiagnosticReportBuilder.buildFocused(context)
            assertEquals(1, focusedEvents(report).size)
            assertFalse(report.contains("Other Game"))
            assertTrue(FeatureDiagnostics.clear())
            assertTrue(focusedEvents(DiagnosticReportBuilder.buildFocused(context)).isEmpty())
        } finally { FeatureDiagnostics.clear() }
    }

    @Test
    fun rawFilterConfigurationIsNotExported() {
        val context = startCapture()
        try {
            assertTrue(FeatureDiagnostics.startFocusedCapture("private-filter-input"))
            record(DiagnosticOutcome.SUCCEEDED, mapOf(DiagnosticAttribute.PUBLIC_TITLE to "Other Game"))
            val report = DiagnosticReportBuilder.buildFocused(context)
            assertFalse(report.contains("private-filter-input"))
            assertFalse(report.contains("Other Game"))
        } finally { FeatureDiagnostics.clear() }
    }

    private fun startCapture(): Application {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("focused-diagnostics", Context.MODE_PRIVATE).edit()
            .putString("title", "dredge")
            .putBoolean("enabled", true)
            .commit()
        FeatureDiagnostics.initialize(context)
        assertTrue(FeatureDiagnostics.startFocusedCapture("dredge"))
        FeatureDiagnostics.clear()
        return context
    }

    private fun trace(outcome: DiagnosticOutcome, stage: String,
        attributes: Map<DiagnosticAttribute, String> = emptyMap()) = record(outcome, mapOf(
            DiagnosticAttribute.OPERATION to "ARTWORK_TRACE",
            DiagnosticAttribute.PUBLIC_TITLE to "DREDGE",
            DiagnosticAttribute.SOURCE to "GOG",
            DiagnosticAttribute.CORRELATION_ID to "1-42",
            DiagnosticAttribute.STAGE to stage,
        ) + attributes)

    private fun record(outcome: DiagnosticOutcome, attributes: Map<DiagnosticAttribute, String>) =
        FeatureDiagnostics.record(DiagnosticArea.MATCHING, DiagnosticEventName.GAME_RESOLUTION,
            outcome, attributes = attributes)

    private fun focusedEvents(report: String): List<DiagnosticEvent> = report
        .substringAfter("Focused diagnostic capture", "")
        .lineSequence().filter { it.startsWith("{") }
        .map { Json.decodeFromString<DiagnosticEvent>(it) }.toList()

    private companion object {
        const val URL_ID = "aaaaaaaaaaaaaaaa"
        const val CONTENT = "bbbbbbbbbbbbbbbb"
        const val OTHER_CONTENT = "cccccccccccccccc"
        const val URL = "https://images.gog-statics.com/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.jpg"
    }
}
