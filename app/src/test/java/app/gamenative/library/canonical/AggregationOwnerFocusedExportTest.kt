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
import io.mockk.verify
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** EX3 / FC-LIVE-1: real owner -> typed facade -> real focused capture -> serialized export.
 * Synthetic features and a controlled matcher verify ownership only, NOT JNI/ORB calibration.
 */
class AggregationOwnerFocusedExportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val cards = listOf(card(1), card(2))
    private val references = cards.map {
        ArtworkReference(GameSource.GOG, "https://images.gog.com/fixture-${it.copies.single().key.stableSourceId}.jpg")
    }
    private val images = references.indices.map { index ->
        ArtworkFeatures("${index + 1}".repeat(64), 128, 128,
            FloatArray(48) { (it % 6) / 6f }, ByteArray(24 * 32) { it.toByte() })
    }
    private val pair get() = ArtworkPairDigest.from(images[0], images[1]).value

    @Test fun warmOwnerBindsBothOriginalsBeforeCachedReplayAndExportsActualSupportWithoutInventedMetrics() = runTest {
        withFocusedCapture {
            val directory = temporary.newFolder()
            val seed = ArtworkFeatureCache(directory, MetadataClock { 100L })
            references.zip(images).forEach { (reference, image) ->
                assertTrue(image.isValid())
                assertTrue(seed.put(reference, image))
                seed.markIndexed(image.contentHash)
            }
            seed.putComparison(images[0].contentHash, images[1].contentHash, true)
            assertTrue(seed.flush())
            // Recreate from persisted URL bindings, accepted pair and index, not in-memory seed state.
            val cache = ArtworkFeatureCache(directory, MetadataClock { 100L })
            assertEquals(true, cache.comparison(images[0].contentHash, images[1].contentHash))
            assertTrue(images.all { cache.isIndexed(it.contentHash) })
            val matcher = mockk<ArtworkFeatureMatcher>()
            val source = object : ArtworkFeatureSource {
                override suspend fun original(reference: ArtworkReference) = cache.original(reference)
            }
            val family = owner(cache, source, matcher).project(cards).single()
            assertEquals(cards.flatMap { it.copies }.toSet(), family.copies.toSet())
            verify(exactly = 0) { matcher.compare(any(), any()) }

            val (events, report) = focusedExport()
            val replay = events.indexOfFirst {
                it.attributes["stage"] == "CACHE_HIT" && it.attributes["confidence"] == "pair=$pair"
            }
            assertTrue("Actual cached verdict must reach focused export", replay >= 0)
            references.zip(images).forEach { (reference, image) ->
                val binding = events.take(replay).single {
                    it.attributes["stage"] == "ORIGINAL_CACHE_BOUND" && it.attributes["public_url"] == reference.url
                }
                assertOriginalBinding(binding, reference, image)
                assertTrue(events.any {
                    it.attributes["correlation_id"] == binding.attributes["correlation_id"] &&
                        it.attributes["stage"] == "CACHE_HIT" && it.attributes["confidence"] == "pair=$pair" &&
                        it.attributes["match_result"] == "ACCEPTED" && it.outcome == DiagnosticOutcome.CACHE_HIT
                })
            }
            assertActualSupport(events)
            assertFalse("Cached booleans cannot manufacture historical ORB measurements", events.any {
                it.name == DiagnosticEventName.MATCH_RESOLUTION || it.attributes["match_method"] == "ORB_V1" ||
                    it.attributes["confidence"]?.startsWith("inliers=") == true
            })
            assertPrivateIdentitiesAbsent(report)
        }
    }

    @Test fun coldOwnerOriginalBindingsAdmitItsTitleFreeFreshMetricsAndActualPublicationIntoFocusedExport() = runTest {
        withFocusedCapture {
            val cache = ArtworkFeatureCache(temporary.newFolder(), MetadataClock { 100L })
            assertTrue(cache.featureHashes().isEmpty())
            assertTrue(cache.comparisonResults().isEmpty())
            val acquired = mutableListOf<ArtworkReference>()
            val source = object : ArtworkFeatureSource {
                override suspend fun original(reference: ArtworkReference): ArtworkFeatures {
                    acquired += reference
                    return images[references.indexOf(reference)].also { assertTrue(it.isValid()) }
                }
            }
            val matcher = mockk<ArtworkFeatureMatcher>()
            every { matcher.compare(any(), any()) } returns ArtworkMatch(
                true, 30, 28, 0.875, 0.5, 10, 1.0, reason = ArtworkMatchReason.ACCEPTED)
            val family = owner(cache, source, matcher).project(cards).single()
            assertEquals(references, acquired)
            assertEquals(cards.flatMap { it.copies }.toSet(), family.copies.toSet())
            verify(exactly = 1) { matcher.compare(images[1], images[0]) }

            val (events, report) = focusedExport()
            val metrics = events.single { it.name == DiagnosticEventName.MATCH_RESOLUTION }
            assertNull("Actual matcher diagnostics are title-free; ownership must admit them", metrics.attributes["public_title"])
            assertEquals("ORB_V1", metrics.attributes["match_method"])
            assertEquals(pair, metrics.attributes["public_content_id"])
            assertEquals("ACCEPTED", metrics.attributes["reason"])
            assertEquals("30", metrics.attributes["result_count"])
            assertEquals("inliers=28;ratio=875;area=500;cells=10;error=1000", metrics.attributes["confidence"])
            assertEquals(DiagnosticOutcome.SUCCEEDED, metrics.outcome)
            val metricsIndex = events.indexOf(metrics)
            references.zip(images).forEach { (reference, image) ->
                val binding = events.take(metricsIndex).single {
                    it.attributes["stage"] == "FEATURES_READY" && it.attributes["public_url"] == reference.url
                }
                assertOriginalBinding(binding, reference, image)
                assertTrue(events.any {
                    it.attributes["correlation_id"] == binding.attributes["correlation_id"] &&
                        it.attributes["stage"] == "COMPARED" && it.attributes["confidence"] == "pair=$pair" &&
                        it.attributes["match_result"] == "ACCEPTED"
                })
            }
            assertActualSupport(events)
            assertPrivateIdentitiesAbsent(report)
        }
    }

    private fun owner(cache: ArtworkFeatureCache, source: ArtworkFeatureSource, matcher: ArtworkFeatureMatcher) =
        CanonicalFamilyArtworkCorroborator(source, cache, matcher, { key ->
            OwnedCopyRuntimeResult.Available(runtime(cards.single { it.copies.single().key == key }))
        }, FeatureCanonicalLibraryDiagnostics())

    private fun assertOriginalBinding(event: DiagnosticEvent, reference: ArtworkReference, image: ArtworkFeatures) {
        assertEquals("DREDGE", event.attributes["public_title"])
        assertEquals(reference.url, event.attributes["public_url"])
        assertEquals(ArtworkFeatures.hash(reference.url.toByteArray()).take(16), event.attributes["public_url_id"])
        assertEquals(image.contentHash.take(16), event.attributes["public_content_id"])
        assertEquals("GOG", event.attributes["source"])
        assertEquals("GOG", event.attributes["provider"])
    }

    private fun assertActualSupport(events: List<DiagnosticEvent>) {
        val owners = events.filter { it.attributes["stage"] == "QUEUED" }.map { it.attributes["correlation_id"] }.toSet()
        assertEquals("Both actual owners must be captured", 2, owners.size)
        assertFalse(owners.contains(null))
        owners.forEach { id ->
            assertTrue("Actual publication must retain both admitted title and artwork support", events.any {
                it.attributes["correlation_id"] == id && it.attributes["public_title"] == "DREDGE" &&
                    it.attributes["family_size"] == "2" && it.attributes["grouping_reason"] == "TITLE_AND_ARTWORK"
            })
        }
    }

    private fun focusedExport(): Pair<List<DiagnosticEvent>, String> {
        val focused = FeatureDiagnostics.focusedCaptureReport()
        assertTrue(focused.state.available)
        assertTrue(focused.state.enabled)
        assertFalse(focused.state.full)
        assertEquals(0, focused.state.writeFailures)
        // Focused-only builder overload needs neither an Android Context nor a fabricated trace.
        val report = DiagnosticReportBuilder.build(
            DiagnosticReportHeader("test", "test", "test", "test"), emptyList(), focused = focused)
        val exported = report.substringAfter("Focused diagnostic capture").lineSequence()
            .filter { it.startsWith("{") }.map { Json.decodeFromString<DiagnosticEvent>(it) }.toList()
        assertEquals("Assertions must inspect the actual complete serialized focused history", focused.events, exported)
        assertTrue(exported.isNotEmpty())
        return exported to report
    }

    private fun assertPrivateIdentitiesAbsent(report: String) {
        val forbidden = cards.flatMap { listOf(it.canonicalId.value, it.copies.single().key.accountScope.value,
            it.copies.single().key.toString()) } + listOf(PRIVATE_PATH, PRIVATE_ALIAS, temporary.root.name)
        forbidden.forEach { assertFalse("Private identity/path must not enter focused export: $it", report.contains(it)) }
    }

    private suspend fun withFocusedCapture(assertions: suspend () -> Unit) {
        val preferences = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { preferences.getString("title", "") } returns ""
        every { preferences.getBoolean(any(), false) } returns false
        every { preferences.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.commit() } returns true
        val capture = FocusedDiagnosticCapture(temporary.newFolder(), preferences)
        val store = DiagnosticLogStore(temporary.newFolder())
        val storeField = FeatureDiagnostics::class.java.getDeclaredField("store").apply { isAccessible = true }
        val focusedField = FeatureDiagnostics::class.java.getDeclaredField("focusedCapture").apply { isAccessible = true }
        val statesField = FeatureDiagnostics::class.java.getDeclaredField("gameStates").apply { isAccessible = true }
        val previousStore = storeField.get(null)
        val previousFocused = focusedField.get(null)
        @Suppress("UNCHECKED_CAST")
        val states = statesField.get(null) as MutableMap<String, DiagnosticEvent>
        val previousStates = states.toMap()
        try {
            storeField.set(null, store)
            focusedField.set(null, capture)
            assertTrue(FeatureDiagnostics.clear())
            assertTrue(FeatureDiagnostics.startFocusedCapture("dredge"))
            assertions()
        } finally {
            try { FeatureDiagnostics.clear() } finally {
                storeField.set(null, previousStore)
                focusedField.set(null, previousFocused)
                states.clear()
                states.putAll(previousStates)
            }
        }
    }

    private fun card(index: Long): CanonicalLibraryCard {
        val id = CanonicalGameId.parse(UUID(0, index).toString())
        val key = OwnedCopyKey(AccountScope("2".repeat(64)), GameSource.GOG, "$index")
        val copy = OwnedCopySummary(key, GameSource.GOG, "DREDGE", PRIVATE_PATH, null, null,
            false, false, false, false, false, null, null, setOf(OwnedCopyOperation.INSTALL), null, true,
            MatchMethod.EXACT_METADATA, MatchConfidence.REVIEW_REQUIRED, MatchDecisionSource.AUTOMATIC,
            null, CURRENT_RESOLVER_VERSION, 100)
        return CanonicalLibraryCard(CanonicalCardKey.Grouped(id), id, "DREDGE", CanonicalAppType.GAME,
            "", "", "", "", 1f, setOf("DREDGE", PRIVATE_ALIAS), setOf(GameSource.GOG), listOf(copy), null, emptySet(), false)
    }

    private fun runtime(card: CanonicalLibraryCard): OwnedCopyRuntime {
        val copy = card.copies.single()
        return OwnedCopyRuntime(copy.key, SourceOwnedCopyReference.Gog(copy.key, copy.key.stableSourceId),
            LibraryItem(appId = "GOG_${copy.key.stableSourceId}", name = "DREDGE", gameSource = GameSource.GOG),
            "DREDGE", setOf(PRIVATE_ALIAS), "", null, CanonicalAppType.GAME, emptySet(), emptySet(), emptySet(),
            "", "", "", "", 1f, PRIVATE_PATH, null, null, false, false, false, false, false, null, null,
            setOf(OwnedCopyOperation.INSTALL), originalArtworkUrl = references[cards.indexOf(card)].url)
    }

    private companion object {
        const val PRIVATE_PATH = "/data/user/0/app.gamenative/private-owner-fixture/install"
        const val PRIVATE_ALIAS = "Private custom label QZX"
    }
}
