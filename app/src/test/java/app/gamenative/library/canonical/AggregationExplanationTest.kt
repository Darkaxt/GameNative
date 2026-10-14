package app.gamenative.library.canonical

import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.canonical.*
import app.gamenative.library.canonical.artwork.*
import app.gamenative.library.canonical.runtime.OwnedCopyRuntime
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeResult
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import app.gamenative.library.metadata.MetadataClock
import io.mockk.*
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AggregationExplanationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val cards = listOf(card(1), card(2))
    private val references = cards.map { ArtworkReference(GameSource.GOG, "https://images.gog.com/fixture-${it.copies.single().key.stableSourceId}.jpg") }
    private val images = references.indices.map { i -> ArtworkFeatures("${i + 1}".repeat(64), 128, 128,
        FloatArray(48) { (it % 6) / 6f }, ByteArray(24 * 32) { it.toByte() }) }
    private val observations = mutableListOf<ArtworkTraceObservation>()
    private val sink = mockk<CanonicalLibraryDiagnosticSink>(relaxed = true).also {
        every { it.artworkState(any(), any()) } answers { observations += secondArg<ArtworkTraceObservation>() }
    }
    private val cache by lazy { ArtworkFeatureCache(temporary.newFolder(), MetadataClock { 100 }) }
    private fun owner() = CanonicalFamilyArtworkCorroborator(
        object : ArtworkFeatureSource {
            override suspend fun original(reference: ArtworkReference) = images[references.indexOf(reference)]
        }, cache, mockk(relaxed = true), { key -> OwnedCopyRuntimeResult.Available(runtime(cards.single { it.copies.single().key == key })) }, sink,
    )

    @Test fun warmOriginalBindingsPrecedeCachedComparisonReplay() = runTest {
        references.zip(images).forEach { (ref, image) -> cache.put(ref, image); cache.markIndexed(image.contentHash) }
        cache.putComparison(images[0].contentHash, images[1].contentHash, true)
        cache.flush()
        assertEquals(1, owner().project(cards).size)
        val replay = observations.indexOfFirst { it.stage == ArtworkTraceStage.CACHE_HIT && it.pair != null }
        assertTrue(replay >= 0)
        references.zip(images).forEach { (reference, image) ->
            assertTrue("Warm public original/content binding must precede cached pair replay", observations.take(replay).any {
                it.reference == reference && it.content == image.contentHash.take(16)
            })
        }
    }

    @Test fun actualPublicationStatesWhetherTitleAndArtworkEdgesWereAdmitted() = runTest {
        owner().project(cards)
        val publication = observations.first { it.stage == ArtworkTraceStage.FAMILY_PUBLISHED }
        val accessor = publication.javaClass.methods.singleOrNull { it.name == "getGroupingReason" }
        assertNotNull("Family publication must retain actual title/artwork cause, not size alone", accessor)
        assertEquals("TITLE", accessor!!.invoke(publication).toString())
    }

    @Test fun perEntryReadOnlyReportIsAvailableFromTheActualRepository() {
        assertTrue("Entry action needs a read-only repository report rather than collecting the mutating artwork flow",
            CanonicalLibraryRepository::class.java.methods.any { it.name == "explainAggregation" })
    }

    private fun card(index: Long): CanonicalLibraryCard {
        val id = CanonicalGameId.parse(UUID(0, index).toString())
        val key = OwnedCopyKey(AccountScope("2".repeat(64)), GameSource.GOG, "$index")
        val copy = OwnedCopySummary(key, GameSource.GOG, "DREDGE", null, null, null, false, false, false, false,
            false, null, null, setOf(OwnedCopyOperation.INSTALL), null, true, MatchMethod.EXACT_METADATA,
            MatchConfidence.REVIEW_REQUIRED, MatchDecisionSource.AUTOMATIC, null, CURRENT_RESOLVER_VERSION, 100)
        return CanonicalLibraryCard(CanonicalCardKey.Grouped(id), id, "DREDGE", CanonicalAppType.GAME,
            "", "", "", "", 1f, setOf("DREDGE"), setOf(GameSource.GOG), listOf(copy), null, emptySet(), false)
    }

    private fun runtime(card: CanonicalLibraryCard): OwnedCopyRuntime {
        val copy = card.copies.single()
        return OwnedCopyRuntime(copy.key, SourceOwnedCopyReference.Gog(copy.key, copy.key.stableSourceId),
            LibraryItem(appId = "GOG_${copy.key.stableSourceId}", name = "DREDGE", gameSource = GameSource.GOG),
            "DREDGE", emptySet(), "", null, CanonicalAppType.GAME, emptySet(), emptySet(), emptySet(),
            "", "", "", "", 1f, null, null, null, false, false, false, false, false, null, null,
            setOf(OwnedCopyOperation.INSTALL), originalArtworkUrl = references[cards.indexOf(card)].url)
    }
}
