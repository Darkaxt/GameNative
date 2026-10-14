package app.gamenative.library.canonical

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtworkGroupingTraceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val cards = listOf(card(1), card(2))
    private val observations = mutableListOf<Pair<ArtworkGameTrace, ArtworkTraceObservation>>()
    private val diagnostics = object : CanonicalLibraryDiagnosticSink by NoOpCanonicalLibraryDiagnosticSink {
        override fun artworkState(game: ArtworkGameTrace, observation: ArtworkTraceObservation) {
            observations += game to observation
        }
    }
    private val matcher = mockk<ArtworkFeatureMatcher>().also {
        every { it.compare(any(), any()) } returns ArtworkMatch(true, 30, 28, 0.93, 0.4, 10, 1.0)
    }
    private fun features(reference: ArtworkReference) = ArtworkFeatures(
        ArtworkFeatures.hash(reference.url.toByteArray()), 64, 64, FloatArray(48) { 0.5f }, ByteArray(24 * 32),
    )
    private fun source(action: suspend (ArtworkReference) -> ArtworkFeatures?) = object : ArtworkFeatureSource {
        override suspend fun original(reference: ArtworkReference) = action(reference)
    }
    private fun owner(
        cache: ArtworkFeatureCache,
        source: ArtworkFeatureSource = source { features(it) },
        fixtureCards: List<CanonicalLibraryCard> = cards,
        originalUrls: (CanonicalLibraryCard) -> List<String> = { listOf(url(it.copies.single().key.stableSourceId)) },
    ) = CanonicalFamilyArtworkCorroborator(source, cache, matcher, { key ->
        val card = fixtureCards.single { it.copies.single().key == key }
        OwnedCopyRuntimeResult.Available(runtime(card, originalUrls(card)))
    }, diagnostics)

    @Test fun realOwnerBindsOriginalsComparisonAndPublishedFamilyThenReusesCachedEvidence() = runTest {
        val cache = ArtworkFeatureCache(temporary.newFolder(), MetadataClock { 100L })
        val family = owner(cache).project(cards).single()
        assertEquals(cards.flatMap { it.copies }.toSet(), family.copies.toSet())
        verify(exactly = 1) { matcher.compare(any(), any()) }
        for (card in cards) {
            val states = observations.filter { it.first.publicTitle == card.copies.single().nativeTitle }.map { it.second }
            assertTrue(states.any { it.stage == ArtworkTraceStage.QUEUED })
            assertTrue(states.any { it.stage == ArtworkTraceStage.ORIGINALS_ADMITTED && it.referenceCount == 1 })
            assertTrue(states.any { it.stage == ArtworkTraceStage.FEATURES_READY && it.reference?.url == url(card.copies.single().key.stableSourceId) && it.content?.length == 16 })
            assertTrue(states.any { it.stage == ArtworkTraceStage.COMPARED && it.pair != null && it.comparisonCount == 1 && it.acceptedCount == 1 })
            assertTrue(states.any { it.stage == ArtworkTraceStage.FAMILY_PUBLISHED && it.familySize == 2 })
            assertEquals(ArtworkTraceStage.FINISHED, states.last().stage)
        }
        observations.clear()
        assertEquals(1, owner(cache).project(cards).size)
        verify(exactly = 1) { matcher.compare(any(), any()) }
        assertTrue(observations.any { it.second.stage == ArtworkTraceStage.CACHE_HIT && it.second.pair != null })
    }

    @Test fun unavailableOriginalAndCancellationAreNotSuccessfulComparisons() = runTest {
        val cache = ArtworkFeatureCache(temporary.newFolder(), MetadataClock { 100L })
        assertEquals(cards, owner(cache, source { null }).project(cards))
        assertEquals(2, observations.count { it.second.stage == ArtworkTraceStage.ORIGINAL_UNAVAILABLE })
        assertFalse(observations.any { it.second.stage == ArtworkTraceStage.COMPARED })
        observations.clear()
        assertTrue(runCatching { owner(cache, source { throw CancellationException() }).project(cards) }.exceptionOrNull() is CancellationException)
        assertEquals(2, observations.count { it.second.stage == ArtworkTraceStage.CANCELLED })
        assertFalse(observations.any { it.second.stage == ArtworkTraceStage.FAILED })
    }

    @Test fun typedTraceExportsPublicNativeTitleButNotCustomTitleAccountKeysOrPaths() {
        val field = FeatureDiagnostics::class.java.getDeclaredField("store").apply { isAccessible = true }
        val previous = field.get(null)
        try {
            field.set(null, DiagnosticLogStore(temporary.newFolder()))
            FeatureDiagnostics.clear()
            val card = cards.first().copy(displayName = "Private custom label QZX")
            val game = requireNotNull(ArtworkGameTrace.from(card, 1, 0))
            val facade = FeatureCanonicalLibraryDiagnostics()
            facade.artworkState(game, ArtworkTraceObservation.of(ArtworkTraceStage.ORIGINAL_UNAVAILABLE,
                ArtworkReference(GameSource.GOG, url("1")), referenceCount = Int.MAX_VALUE))
            facade.artworkState(game, ArtworkTraceObservation.of(ArtworkTraceStage.FINISHED))
            facade.artworkState(game, ArtworkTraceObservation.of(ArtworkTraceStage.FAMILY_PUBLISHED, familySize = 1))
            val states = FeatureDiagnostics.currentGameStates()
            val report = DiagnosticReportBuilder.build(DiagnosticReportHeader("test", "test", "test", "test"), emptyList(), states)
            assertTrue(report.contains("DREDGE 1"))
            assertTrue(report.contains("ORIGINAL_UNAVAILABLE"))
            val summary = states.single { it.attributes["public_url"] == null && it.attributes["confidence"] == null }
            assertEquals("FINISHED", summary.attributes["stage"])
            assertEquals("1000000", summary.attributes["reference_count"])
            assertTrue(states.any { it.attributes["public_url"] == url("1") && it.attributes["stage"] == "ORIGINAL_UNAVAILABLE" })
            assertFalse(report.contains("Private custom label QZX"))
            assertFalse(report.contains("2".repeat(64)))
            assertFalse(report.contains(card.canonicalId.value))
            assertNull(ArtworkGameTrace.from(card.copy(copies = card.copies.map { it.copy(source = GameSource.CUSTOM_GAME) }), 1, 1))
            assertTrue(runCatching { ArtworkTraceObservation.of(ArtworkTraceStage.ORIGINAL_FETCHING,
                ArtworkReference(GameSource.GOG, "https://user:secret@private.example/image.png")) }.exceptionOrNull() is IllegalArgumentException)
        } finally { FeatureDiagnostics.clear(); field.set(null, previous) }
    }

    @Test fun generationStartNativeReviewStateIsVisibleWithoutANewCatalogAttempt() {
        val field = FeatureDiagnostics::class.java.getDeclaredField("store").apply { isAccessible = true }
        val previous = field.get(null)
        try {
            field.set(null, DiagnosticLogStore(temporary.newFolder()))
            FeatureDiagnostics.clear()
            val card = cards.first().copy(copies = cards.first().copies.map {
                it.copy(matchMethod = MatchMethod.EXACT_METADATA, confidence = MatchConfidence.REVIEW_REQUIRED)
            })
            val game = requireNotNull(ArtworkGameTrace.from(card, 1, 0))
            FeatureCanonicalLibraryDiagnostics().artworkState(game, ArtworkTraceObservation.of(ArtworkTraceStage.QUEUED))
            val attributes = FeatureDiagnostics.currentGameStates().single().attributes
            assertEquals(mapOf("initial_match_method" to "EXACT_METADATA", "initial_match_confidence" to "REVIEW_REQUIRED"),
                attributes.filterKeys { it.startsWith("initial_match_") })
        } finally { FeatureDiagnostics.clear(); field.set(null, previous) }
    }

    @Test fun perGameExportDoesNotAttachAvailableOriginalEvidenceToLaterUnavailableOriginal() = runTest {
        val originalA = ArtworkReference(GameSource.GOG, url("available-a"))
        val unavailableB = ArtworkReference(GameSource.GOG, url("unavailable-b"))
        val other = ArtworkReference(GameSource.GOG, url("2"))
        val imageA = features(originalA)
        val pair = ArtworkPairDigest.from(imageA, features(other)).value
        val cache = ArtworkFeatureCache(temporary.newFolder(), MetadataClock { 100L })
        val family = owner(cache, source { if (it == unavailableB) null else features(it) },
            originalUrls = { if (it == cards.first()) listOf(originalA.url, unavailableB.url) else listOf(other.url) })
            .project(cards).single()
        assertEquals(cards.flatMap { it.copies }.toSet(), family.copies.toSet())
        verify(exactly = 1) { matcher.compare(any(), any()) }
        val firstGame = observations.filter { it.first.publicTitle == "DREDGE 1" }.map { it.second }
        assertTrue(firstGame.any { it.reference == originalA && it.content == imageA.contentHash.take(16) })
        assertTrue(firstGame.any { it.reference == unavailableB && it.stage == ArtworkTraceStage.ORIGINAL_UNAVAILABLE })
        assertTrue(firstGame.any { it.pair?.value == pair })

        withTraceExport { states, report ->
            val game = states.filter { it.attributes["public_title"] == "DREDGE 1" }
            assertTrue("Available original must retain its own content evidence", game.any {
                it.attributes["public_url"] == originalA.url && it.attributes["public_content_id"] == imageA.contentHash.take(16)
            })
            assertTrue("Unavailable original must retain its own explanation", game.any {
                it.attributes["public_url"] == unavailableB.url && it.attributes["reason"] == "ORIGINAL_UNAVAILABLE"
            })
            assertFalse("Unavailable original cannot inherit the available original's content or pair", game.any {
                it.attributes["public_url"] == unavailableB.url &&
                    (it.attributes["public_content_id"] == imageA.contentHash.take(16) || it.attributes["confidence"] == "pair=$pair")
            })
            assertTrue(game.any { it.attributes["confidence"] == "pair=$pair" })
            assertTrue(report.contains(originalA.url))
            assertTrue(report.contains(unavailableB.url))
        }
    }

    @Test fun approvedLongOriginalUrlsWithSameDisplayPrefixExportDistinctPublicUrlIds() = runTest {
        val prefix = "https://images.gog.com/" + "a".repeat(140)
        val originalUrls = listOf("$prefix-original-a.jpg", "$prefix-original-b.jpg")
        assertEquals(originalUrls[0].take(120), originalUrls[1].take(120))
        val cache = ArtworkFeatureCache(temporary.newFolder(), MetadataClock { 100L })
        assertEquals(1, owner(cache, originalUrls = { listOf(originalUrls[cards.indexOf(it)]) }).project(cards).size)
        verify(exactly = 1) { matcher.compare(any(), any()) }

        withTraceExport { states, report ->
            val ids = cards.mapIndexed { index, card ->
                val originals = states.filter {
                    it.attributes["public_title"] == card.copies.single().nativeTitle &&
                        it.attributes["public_url"] == originalUrls[index]
                }
                assertTrue("Complete public original URL must remain visible", originals.isNotEmpty())
                assertTrue(report.contains(originalUrls[index]))
                val ids = originals.mapNotNull { it.attributes["public_url_id"] }.toSet()
                assertEquals("Each complete original requires its own public URL digest", 1, ids.size)
                ids.single().also {
                    assertTrue(it.isNotBlank())
                    assertNotEquals("[redacted]", it)
                    assertTrue(report.contains(it))
                }
            }
            assertNotEquals("Different approved URLs sharing the display prefix cannot share a URL identity", ids[0], ids[1])
        }
    }

    @Test fun acceptedThenRejectedComparisonsRetainIndividualResultsAfterDiagnosticFlood() = runTest {
        val fixtureCards = listOf(card(1), card(2), card(3))
        val images = fixtureCards.map { features(ArtworkReference(GameSource.GOG, url(it.copies.single().key.stableSourceId))) }
        val acceptedPair = ArtworkPairDigest.from(images[0], images[1]).value
        val rejectedPairA = ArtworkPairDigest.from(images[0], images[2]).value
        val rejectedPairB = ArtworkPairDigest.from(images[1], images[2]).value
        every { matcher.compare(any(), any()) } answers {
            val accepted = ArtworkPairDigest.from(firstArg<ArtworkFeatures>(), secondArg<ArtworkFeatures>()).value == acceptedPair
            ArtworkMatch(accepted, 30, if (accepted) 28 else 0, if (accepted) 0.93 else 0.0, 0.4, 10, 1.0)
        }
        val cache = ArtworkFeatureCache(temporary.newFolder(), MetadataClock { 100L })
        val families = owner(cache, fixtureCards = fixtureCards).project(fixtureCards)
        assertEquals(setOf(setOf("1", "2"), setOf("3")),
            families.map { family -> family.copies.map { it.key.stableSourceId }.toSet() }.toSet())
        verify(exactly = 3) { matcher.compare(any(), any()) }
        val secondGame = observations.filter { it.first.publicTitle == "DREDGE 2" && it.second.stage == ArtworkTraceStage.COMPARED }.map { it.second }
        assertEquals(listOf(acceptedPair, rejectedPairB), secondGame.map { it.pair?.value })
        assertEquals(listOf(1, 1), secondGame.map { it.acceptedCount })

        withTraceExport(flood = true) { states, report ->
            assertTrue("Accepted comparison needs an explicit result, not just cumulative accepted_count", states.any {
                it.attributes["match_result"] == "ACCEPTED"
            })
            assertTrue("A later rejected comparison needs its own explicit result", states.any {
                it.attributes["match_result"] == "REJECTED"
            })
            val expected = mapOf(
                "DREDGE 1" to mapOf(acceptedPair to "ACCEPTED", rejectedPairA to "REJECTED"),
                "DREDGE 2" to mapOf(acceptedPair to "ACCEPTED", rejectedPairB to "REJECTED"),
                "DREDGE 3" to mapOf(rejectedPairA to "REJECTED", rejectedPairB to "REJECTED"),
            )
            expected.forEach { (title, pairs) -> pairs.forEach { (pair, result) ->
                assertTrue("$title must retain $pair as $result after history rotation", states.any {
                    it.attributes["public_title"] == title && it.attributes["confidence"] == "pair=$pair" &&
                        it.attributes["match_result"] == result
                })
                assertTrue(report.contains(pair))
            } }
        }
    }

    @Test fun recreatedCacheReplaysIndexedRejectedPairWithoutAnotherMatcherInvocation() = runTest {
        every { matcher.compare(any(), any()) } returns ArtworkMatch(false, 30, 0, 0.0, 0.0, 0, 1.0)
        val directory = temporary.newFolder()
        val cache = ArtworkFeatureCache(directory, MetadataClock { 100L })
        assertEquals(cards, owner(cache).project(cards))
        val images = cards.map { features(ArtworkReference(GameSource.GOG, url(it.copies.single().key.stableSourceId))) }
        val pair = ArtworkPairDigest.from(images[0], images[1]).value
        assertEquals(false, cache.comparison(images[0].contentHash, images[1].contentHash))
        assertTrue(images.all { cache.isIndexed(it.contentHash) })
        assertTrue(cache.flush())
        observations.clear()

        val recreated = ArtworkFeatureCache(directory, MetadataClock { 100L })
        assertEquals(cards, owner(recreated, source { recreated.original(it) }).project(cards))
        verify(exactly = 1) { matcher.compare(any(), any()) }
        withTraceExport { states, report ->
            cards.forEach { card ->
                assertTrue("Indexed cached rejection must be explained for ${card.copies.single().nativeTitle}", states.any {
                    it.attributes["public_title"] == card.copies.single().nativeTitle &&
                        it.attributes["confidence"] == "pair=$pair" && it.attributes["match_result"] == "REJECTED"
                })
            }
            assertTrue(report.contains(pair))
            assertTrue(report.contains("REJECTED"))
        }
    }

    @Test fun changedOriginalUrlReplaysAcceptedSupportToOwnerDiscoveredAfterComparison() = runTest {
        val originalA = ArtworkReference(GameSource.GOG, url("1"))
        val oldOriginalB = ArtworkReference(GameSource.GOG, url("old-b"))
        val newOriginalB = ArtworkReference(GameSource.GOG, url("new-b"))
        val imageB = features(oldOriginalB)
        val pair = ArtworkPairDigest.from(features(originalA), imageB).value
        val cache = ArtworkFeatureCache(temporary.newFolder(), MetadataClock { 100L })
        assertTrue(cache.put(oldOriginalB, imageB))
        cache.markIndexed(imageB.contentHash)
        assertTrue(cache.flush())
        assertNull(cache.contentHash(newOriginalB))
        val acquired = mutableListOf<ArtworkReference>()
        val family = owner(cache, source {
            acquired += it
            if (it == newOriginalB) imageB else features(it)
        }, originalUrls = { if (it == cards.first()) listOf(originalA.url) else listOf(newOriginalB.url) })
            .project(cards).single()
        assertEquals(listOf(originalA, newOriginalB), acquired)
        assertEquals(cards.flatMap { it.copies }.toSet(), family.copies.toSet())
        verify(exactly = 1) { matcher.compare(any(), any()) }
        val firstComparison = observations.indexOfFirst {
            it.first.publicTitle == "DREDGE 1" && it.second.stage == ArtworkTraceStage.COMPARED && it.second.pair?.value == pair
        }
        val currentBFetching = observations.indexOfFirst {
            it.first.publicTitle == "DREDGE 2" && it.second.stage == ArtworkTraceStage.ORIGINAL_FETCHING && it.second.reference == newOriginalB
        }
        assertTrue("Game A must compare against the cached old-URL bytes before game B is acquired",
            firstComparison >= 0 && currentBFetching > firstComparison)
        assertTrue(observations.any { it.first.publicTitle == "DREDGE 2" && it.second.stage == ArtworkTraceStage.FAMILY_PUBLISHED && it.second.familySize == 2 })
        assertTrue("Newly discovered game B must receive the accepted pair supporting its published family", observations.any {
            it.first.publicTitle == "DREDGE 2" && it.second.pair?.value == pair
        })
        withTraceExport { states, report ->
            cards.forEach { card ->
                assertTrue("Every grouped game must export its supporting accepted pair", states.any {
                    it.attributes["public_title"] == card.copies.single().nativeTitle &&
                        it.attributes["confidence"] == "pair=$pair" && it.attributes["match_result"] == "ACCEPTED"
                })
            }
            assertTrue(report.contains(pair))
        }
    }

    private fun withTraceExport(flood: Boolean = false, assertions: (List<DiagnosticEvent>, String) -> Unit) {
        val field = FeatureDiagnostics::class.java.getDeclaredField("store").apply { isAccessible = true }
        val previous = field.get(null)
        try {
            field.set(null, DiagnosticLogStore(temporary.newFolder(), maxFileBytes = 1_024L, maxFiles = 1))
            FeatureDiagnostics.clear()
            val facade = FeatureCanonicalLibraryDiagnostics()
            observations.forEach { (game, observation) -> facade.artworkState(game, observation) }
            if (flood) {
                repeat(256) {
                    FeatureDiagnostics.record(DiagnosticArea.APP, DiagnosticEventName.APP_STARTED, DiagnosticOutcome.SUCCEEDED)
                }
                assertFalse("Flood must rotate the original trace out of chronological history", FeatureDiagnostics.recent(1_000).any {
                    it.attributes["operation"] == "ARTWORK_TRACE"
                })
            }
            val states = FeatureDiagnostics.currentGameStates().filter { it.attributes["operation"] == "ARTWORK_TRACE" }
            val report = DiagnosticReportBuilder.build(DiagnosticReportHeader("test", "test", "test", "test"), emptyList(), states)
            assertions(states, report)
        } finally { FeatureDiagnostics.clear(); field.set(null, previous) }
    }

    private fun url(id: String) = "https://images.gog.com/fixture-$id.jpg"
    private fun runtime(card: CanonicalLibraryCard, originalUrls: List<String> = listOf(url(card.copies.single().key.stableSourceId))): OwnedCopyRuntime {
        val copy = card.copies.single()
        return OwnedCopyRuntime(copy.key, SourceOwnedCopyReference.Gog(copy.key, copy.key.stableSourceId),
            LibraryItem(appId = "GOG_${copy.key.stableSourceId}", name = copy.nativeTitle, gameSource = GameSource.GOG),
            copy.nativeTitle, emptySet(), "", null, CanonicalAppType.GAME, emptySet(), emptySet(), emptySet(),
            "", "", "", "", 1f, null, null, null, false, false, false, false, false, null, null,
            setOf(OwnedCopyOperation.INSTALL), originalArtworkUrl = originalUrls.firstOrNull()).let { runtime ->
                if (originalUrls.size <= 1) runtime
                else runtime.copy(headerImageUrl = originalUrls[0], heroImageUrl = originalUrls[1], originalArtworkUrl = null)
            }
    }
    private fun card(index: Long): CanonicalLibraryCard {
        val id = CanonicalGameId.parse(UUID(0, index).toString())
        val key = OwnedCopyKey(AccountScope("2".repeat(64)), GameSource.GOG, "$index")
        val summary = OwnedCopySummary(key, GameSource.GOG, "DREDGE $index", null, null, null,
            false, false, false, false, false, null, null, setOf(OwnedCopyOperation.INSTALL), null, true,
            MatchMethod.UNMATCHED, MatchConfidence.UNMATCHED, MatchDecisionSource.AUTOMATIC, null, CURRENT_RESOLVER_VERSION, 100)
        return CanonicalLibraryCard(CanonicalCardKey.Grouped(id), id, "Public title $index", CanonicalAppType.GAME,
            "", "", "", "", 1f, emptySet(), setOf(GameSource.GOG), listOf(summary), null, emptySet(), false)
    }
}
