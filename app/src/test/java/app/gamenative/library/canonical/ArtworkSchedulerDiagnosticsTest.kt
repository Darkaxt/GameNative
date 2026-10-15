package app.gamenative.library.canonical

import android.content.SharedPreferences
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.canonical.*
import app.gamenative.diagnostics.*
import app.gamenative.library.canonical.artwork.*
import app.gamenative.library.canonical.runtime.OwnedCopyRuntime
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeResult
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import app.gamenative.library.metadata.MetadataClock
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtworkSchedulerDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val cards = (1L..70L).map(::card)
    private val matcher = mockk<ArtworkFeatureMatcher>()

    @Test fun focusedExportDistinguishesFullQueueFromAnUnreachedLateOriginal() = runBlocking {
        var captureClock = 0L
        withCapture(clock = { captureClock }) {
            val cache = ArtworkFeatureCache(temporary.newFolder(), MetadataClock { 100L })
            val historical = ArtworkReference(GameSource.GOG, url("historical"))
            cache.put(historical, features(historical))
            val comparing = CountDownLatch(1)
            val release = CountDownLatch(1)
            val ready = CompletableDeferred<Unit>()
            every { matcher.compare(any(), any()) } answers {
                comparing.countDown()
                check(release.await(15, TimeUnit.SECONDS)) { "Test must release the native comparison" }
                ArtworkMatch(false, 0, 0, 0.0, 0.0, 0, 0.0)
            }
            val facade = FeatureCanonicalLibraryDiagnostics()
            val sink = object : CanonicalLibraryDiagnosticSink by facade {
                override fun artworkState(game: ArtworkGameTrace, observation: ArtworkTraceObservation) {
                    facade.artworkState(game, observation)
                    if (observation.stage == ArtworkTraceStage.FEATURES_READY && observation.reference?.url == url("66"))
                        ready.complete(Unit)
                }
            }
            val worker = launch {
                owner(cache, sink) { reference ->
                    if (reference.url == url("2")) withContext(Dispatchers.Default) {
                        check(comparing.await(15, TimeUnit.SECONDS)) { "Consumer must enter its comparison" }
                    }
                    features(reference)
                }.project(cards)
            }
            try {
                withTimeout(15_000) { ready.await() }
                yield()
                captureClock = 5_000L
                val events = FeatureDiagnostics.focusedCaptureReport().events
                assertTargetSchedule(events)
                val blocked = events.lastOrNull { it.attributes["operation"] == "ARTWORK_SCHEDULER" &&
                    it.attributes["producer_phase"] == "QUEUE_SEND" && it.attributes["queue_size"] == "64" }
                assertNotNull("Focused export must identify shared queue backpressure before the target's turn", blocked)
                val state = requireNotNull(blocked).attributes
                assertEquals("66", state["producer_rank"])
                assertEquals("1", state["consumer_rank"])
                assertEquals("COMPARE", state["consumer_phase"])
                assertEquals("65", state["enqueued_count"])
                assertEquals("1", state["dequeued_count"])
                assertEquals("CAPTURE_SNAPSHOT", state["stage"])
                assertEquals("5000", state["snapshot_age_ms"])
                val entry = events.first { it.attributes["operation"] == "ARTWORK_SCHEDULER" &&
                    it.attributes["producer_phase"] == "QUEUE_SEND" && it.attributes["producer_rank"] == "66" }
                assertEquals("Export must not relabel the measured phase-entry duration as fresh execution",
                    entry.attributes["producer_elapsed_ms"], state["producer_elapsed_ms"])
                assertFalse(events.any { it.attributes["public_title"] == "DREDGE" && it.attributes["stage"] == "ORIGINAL_FETCHING" })
                assertPrivateAndUnrelatedDataAbsent(events)
            } finally { release.countDown(); worker.cancelAndJoin() }
        }
    }

    @Test fun focusedExportDistinguishesEarlierAcquisitionFromQueueBackpressure() = runBlocking {
        withCapture {
            val cache = ArtworkFeatureCache(temporary.newFolder(), MetadataClock { 100L })
            val entered = CompletableDeferred<Unit>()
            val worker = launch {
                owner(cache, FeatureCanonicalLibraryDiagnostics()) {
                    entered.complete(Unit)
                    awaitCancellation()
                }.project(cards)
            }
            try {
                withTimeout(15_000) { entered.await() }
                val events = FeatureDiagnostics.focusedCaptureReport().events
                assertTargetSchedule(events)
                val state = events.lastOrNull { it.attributes["operation"] == "ARTWORK_SCHEDULER" }?.attributes
                assertNotNull("A focused capture must retain the shared acquisition frontier even for another title", state)
                assertEquals("ACQUIRE", state?.get("producer_phase"))
                assertEquals("1", state?.get("producer_rank"))
                assertEquals("0", state?.get("enqueued_count"))
                assertEquals("0", state?.get("queue_size"))
                assertPrivateAndUnrelatedDataAbsent(events)
            } finally { worker.cancelAndJoin() }
            assertTrue("Cancellation must retain the last producer frontier", FeatureDiagnostics.focusedCaptureReport().events.any {
                it.attributes["operation"] == "ARTWORK_SCHEDULER" && it.attributes["stage"] == "CANCELLED" &&
                    it.attributes["producer_phase"] == "ACQUIRE" && it.attributes["producer_rank"] == "1"
            })
        }
    }

    private fun assertTargetSchedule(events: List<DiagnosticEvent>) {
        val scheduled = events.singleOrNull { it.attributes["public_title"] == "DREDGE" && it.attributes["stage"] == "ORIGINAL_SCHEDULED" }
        assertNotNull("The late focused original must expose its real acquisition rank before fetching", scheduled)
        assertEquals("70", scheduled?.attributes?.get("acquisition_rank"))
        assertEquals("70", scheduled?.attributes?.get("reference_count"))
        assertEquals("MISSING", scheduled?.attributes?.get("cache_state"))
        assertEquals(ArtworkFeatures.hash(url("70").toByteArray()).take(16), scheduled?.attributes?.get("public_url_id"))
    }

    private fun assertPrivateAndUnrelatedDataAbsent(events: List<DiagnosticEvent>) {
        val text = events.toString()
        assertFalse(text.contains("Unrelated game"))
        assertFalse(text.contains("2".repeat(64)))
        assertFalse(text.contains(cards.first().canonicalId.value))
        assertFalse(text.contains(url("1")))
        assertTrue(events.filter { it.attributes["operation"] == "ARTWORK_SCHEDULER" }.all {
            it.attributes.keys.none { key -> key in setOf("public_title", "public_url", "public_url_id", "public_content_id") }
        })
    }

    private suspend fun withCapture(clock: () -> Long = { System.nanoTime() / 1_000_000L }, block: suspend () -> Unit) {
        val storeField = FeatureDiagnostics::class.java.getDeclaredField("store").apply { isAccessible = true }
        val captureField = FeatureDiagnostics::class.java.getDeclaredField("focusedCapture").apply { isAccessible = true }
        val previousStore = storeField.get(null)
        val previousCapture = captureField.get(null)
        val preferences = mockk<SharedPreferences>(relaxed = true)
        every { preferences.getString("title", "") } returns "DREDGE"
        every { preferences.getBoolean("enabled", false) } returns true
        every { preferences.getBoolean("full", false) } returns false
        try {
            storeField.set(null, DiagnosticLogStore(temporary.newFolder()))
            captureField.set(null, FocusedDiagnosticCapture(temporary.newFolder(), preferences, monotonicClockMs = clock))
            block()
        } finally {
            FeatureDiagnostics.clear()
            captureField.set(null, previousCapture)
            storeField.set(null, previousStore)
        }
    }

    private fun owner(cache: ArtworkFeatureCache, sink: CanonicalLibraryDiagnosticSink,
        acquire: suspend (ArtworkReference) -> ArtworkFeatures?) = CanonicalFamilyArtworkCorroborator(
        object : ArtworkFeatureSource { override suspend fun original(reference: ArtworkReference) = acquire(reference) },
        cache, matcher, { key -> OwnedCopyRuntimeResult.Available(runtime(cards.single { it.copies.single().key == key })) }, sink,
    )

    private fun features(reference: ArtworkReference) = ArtworkFeatures(ArtworkFeatures.hash(reference.url.toByteArray()),
        64, 64, FloatArray(48) { 0.5f }, ByteArray(24 * 32))
    private fun url(id: String) = "https://images.gog.com/scheduler-$id.jpg"
    private fun runtime(card: CanonicalLibraryCard): OwnedCopyRuntime {
        val copy = card.copies.single()
        return OwnedCopyRuntime(copy.key, SourceOwnedCopyReference.Gog(copy.key, copy.key.stableSourceId),
            LibraryItem(appId = "GOG_${copy.key.stableSourceId}", name = copy.nativeTitle, gameSource = GameSource.GOG),
            copy.nativeTitle, emptySet(), "", null, CanonicalAppType.GAME, emptySet(), emptySet(), emptySet(),
            "", "", "", "", 1f, null, null, null, false, false, false, false, false, null, null,
            setOf(OwnedCopyOperation.INSTALL), originalArtworkUrl = url(copy.key.stableSourceId))
    }
    private fun card(index: Long): CanonicalLibraryCard {
        val id = CanonicalGameId.parse(UUID(0, index).toString())
        val key = OwnedCopyKey(AccountScope("2".repeat(64)), GameSource.GOG, "$index")
        val summary = OwnedCopySummary(key, GameSource.GOG, if (index == 70L) "DREDGE" else "Unrelated game $index", null, null, null,
            false, false, false, false, false, null, null, setOf(OwnedCopyOperation.INSTALL), null, true,
            MatchMethod.UNMATCHED, MatchConfidence.UNMATCHED, MatchDecisionSource.AUTOMATIC, null, CURRENT_RESOLVER_VERSION, 100)
        return CanonicalLibraryCard(CanonicalCardKey.Grouped(id), id, "Public title $index", CanonicalAppType.GAME,
            "", "", "", "", 1f, emptySet(), setOf(GameSource.GOG), listOf(summary), null, emptySet(), false)
    }
}
