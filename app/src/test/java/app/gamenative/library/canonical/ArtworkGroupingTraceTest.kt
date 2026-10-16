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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
        diagnosticSink: CanonicalLibraryDiagnosticSink = diagnostics,
    ) = CanonicalFamilyArtworkCorroborator(source, cache, matcher, { key ->
        val card = fixtureCards.single { it.copies.single().key == key }
        OwnedCopyRuntimeResult.Available(runtime(card, originalUrls(card)))
    }, diagnosticSink)

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
        val currentBSupport = observations.indexOfFirst {
            it.first.publicTitle == "DREDGE 2" && it.second.pair?.value == pair &&
                it.second.matchResult == ArtworkTraceMatchResult.ACCEPTED
        }
        assertTrue("Every current URL must reach acquisition", currentBFetching >= 0)
        assertTrue("A later-bound owner must receive accepted support after the original comparison",
            firstComparison >= 0 && currentBSupport > firstComparison)
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

    @Test fun acquisitionAndWarmPublicationPrecedeHistoricalRejectedTraceDrain() = runTest {
        val fixtureCards = cards + card(3)
        val refs = fixtureCards.map { ArtworkReference(GameSource.GOG, url(it.copies.single().key.stableSourceId)) }
        val cache = ArtworkFeatureCache(temporary.newFolder(), MetadataClock { 100L })
        refs.take(2).forEach { cache.put(it, features(it)); cache.markIndexed(features(it).contentHash) }
        cache.putComparison(features(refs[0]).contentHash, features(refs[1]).contentHash, true)
        val rejected = (1..32).map { ArtworkFeatures.hash("historical-$it".toByteArray()) }
        rejected.forEach { cache.putComparison(features(refs[0]).contentHash, it, false) }
        var acquired = 0
        val owner = owner(cache, source {
            if (acquired++ == 0) {
                assertTrue("Warm accepted family must publish before acquisition", observations.any {
                    it.second.stage == ArtworkTraceStage.FAMILY_PUBLISHED && it.second.familySize == 2
                })
                assertFalse("Historical rejected diagnostic replay must not block acquisition", observations.any {
                    it.second.stage == ArtworkTraceStage.CACHE_HIT && it.second.matchResult == ArtworkTraceMatchResult.REJECTED
                })
            }
            features(it)
        }, fixtureCards = fixtureCards)
        assertEquals(1, owner.project(fixtureCards).size)
        assertEquals(3, acquired)
        val rejectedPairs = rejected.map { ArtworkPairDigest.fromHashes(features(refs[0]).contentHash, it).value }.toSet()
        assertEquals("Deferred cached outcomes must not be silently discarded", rejectedPairs,
            observations.filter { it.second.matchResult == ArtworkTraceMatchResult.REJECTED }.mapNotNull { it.second.pair?.value }.toSet())
    }

    @Test fun cacheMissingOriginalIsAcquiredBeforeAlreadyBoundOriginals() = runTest {
        val first = ArtworkReference(GameSource.GOG, url("1"))
        val missing = ArtworkReference(GameSource.GOG, url("2"))
        val cache = ArtworkFeatureCache(temporary.newFolder(), MetadataClock { 100L })
        cache.put(first, features(first))
        cache.markIndexed(features(first).contentHash)
        val acquired = mutableListOf<ArtworkReference>()
        owner(cache, source { acquired += it; features(it) }).project(cards)
        assertEquals("Missing current originals must not wait behind warm revalidation", listOf(missing, first), acquired)
    }

    @Test fun laterOriginalAcquisitionProgressesWhileHistoricalComparisonIsBlocked() = runBlocking {
        val cache = ArtworkFeatureCache(temporary.newFolder(), MetadataClock { 100L })
        val historical = ArtworkReference(GameSource.GOG, url("historical"))
        cache.put(historical, features(historical))
        val comparisonEntered = CountDownLatch(1)
        val releaseComparison = CountDownLatch(1)
        val laterAcquired = CountDownLatch(1)
        every { matcher.compare(any(), any()) } answers {
            comparisonEntered.countDown()
            assertTrue("Test must release the bounded comparison", releaseComparison.await(10, TimeUnit.SECONDS))
            ArtworkMatch(true, 30, 28, 0.93, 0.4, 10, 1.0)
        }
        val worker = launch {
            owner(cache, source {
                if (it.url == url("2")) {
                    withContext(Dispatchers.Default) {
                        assertTrue("Historical comparison must be active", comparisonEntered.await(10, TimeUnit.SECONDS))
                    }
                    laterAcquired.countDown()
                }
                features(it)
            }).project(cards)
        }
        try {
            assertTrue(withContext(Dispatchers.Default) { comparisonEntered.await(10, TimeUnit.SECONDS) })
            // A real-time bound is intentional: the native comparison occupies a Default worker.
            val acquired = withContext(Dispatchers.Default) { laterAcquired.await(2, TimeUnit.SECONDS) }
            assertTrue("Later original acquisition must not wait for another original's shortlist", acquired)
        } finally {
            releaseComparison.countDown()
            worker.cancelAndJoin()
        }
    }

    @Test fun unrelatedMembershipChurnDoesNotRestartAnUnchangedSlowAcquisition() = runTest {
        val early = card(1)
        val target = listOf(card(2), card(3))
        val extra = card(4)
        val fixture = listOf(early) + target + extra
        val cache = ArtworkFeatureCache(temporary.newFolder(), MetadataClock { 100L })
        val entered = CompletableDeferred<Unit>()
        var earlyAttempts = 0
        val acquired = mutableSetOf<ArtworkReference>()
        val targetReady = CountDownLatch(target.size)
        val upstream = MutableSharedFlow<List<CanonicalLibraryCard>>()
        val producer = owner(cache, source {
            if (it.url == url("1")) {
                earlyAttempts++
                entered.complete(Unit)
                awaitCancellation()
            }
            acquired += it
            if (target.any { card -> it.url == url(card.copies.single().key.stableSourceId) }) targetReady.countDown()
            features(it)
        }, fixtureCards = fixture)
        val collector = launch { producer.observe(upstream).collect() }
        try {
            testScheduler.runCurrent()
            upstream.emit(listOf(early) + target)
            entered.await()
            repeat(8) { index ->
                delay(5_000)
                upstream.emit(listOf(early) + target + if (index % 2 == 0) listOf(extra) else emptyList())
            }
            val ready = withContext(Dispatchers.Default) { targetReady.await(2, TimeUnit.SECONDS) }
            assertEquals("Unrelated members must not restart the stable acquisition timeout", 1, earlyAttempts)
            assertTrue("Stable target originals must progress despite unrelated generations", ready &&
                target.all { ArtworkReference(GameSource.GOG, url(it.copies.single().key.stableSourceId)) in acquired })
        } finally { collector.cancelAndJoin() }
    }

    @Test fun unchangedTailOriginalsPublishWhileEarlierNativeArtworkKeepsChanging() = runBlocking {
        withTimeout(30_000) {
            val cache = ArtworkFeatureCache(temporary.newFolder(), MetadataClock { 100L })
            val target = listOf(card(13), card(14))
            val targetRefs = target.map { ArtworkReference(GameSource.GOG, url(it.copies.single().key.stableSourceId)) }
            val targetHashes = targetRefs.map { features(it).contentHash }.toSet()
            every { matcher.compare(any(), any()) } answers {
                val accepted = setOf(firstArg<ArtworkFeatures>().contentHash, secondArg<ArtworkFeatures>().contentHash) == targetHashes
                ArtworkMatch(accepted, 30, if (accepted) 28 else 0, if (accepted) 0.93 else 0.0, 0.4, 10, 1.0)
            }
            fun snapshot(revision: Int) = (1L..12L).map { id ->
                val card = card(id)
                card.copy(copies = card.copies.map { it.copy(originalArtwork = listOf(
                    ArtworkReference(GameSource.GOG, url("$id-revision-$revision")))) })
            } + target
            var current = snapshot(0)
            val upstream = MutableSharedFlow<List<CanonicalLibraryCard>>(replay = 1)
            val requests = Channel<Pair<ArtworkReference, CompletableDeferred<Unit>>>(Channel.UNLIMITED)
            val cancelled = Channel<Unit>(Channel.UNLIMITED)
            val consumed = Channel<Long>(Channel.UNLIMITED)
            val consumedGenerations = mutableSetOf<Long>()
            val acquired = mutableSetOf<ArtworkReference>()
            val published = CompletableDeferred<Unit>()
            val sink = object : CanonicalLibraryDiagnosticSink by diagnostics {
                override fun artworkScheduler(snapshot: ArtworkSchedulerSnapshot) {
                    if (snapshot.stage == ArtworkSchedulerStage.CANCELLED) cancelled.trySend(Unit)
                    if (snapshot.completed >= 2 && consumedGenerations.add(snapshot.generation)) consumed.trySend(snapshot.generation)
                }
            }
            val producer = CanonicalFamilyArtworkCorroborator(source { ref ->
                val release = CompletableDeferred<Unit>()
                requests.send(ref to release)
                release.await()
                acquired += ref
                features(ref)
            }, cache, matcher, { key ->
                val card = current.single { it.copies.single().key == key }
                val originals = card.copies.single().originalArtwork.map { it.url }.ifEmpty { listOf(url(key.stableSourceId)) }
                OwnedCopyRuntimeResult.Available(runtime(card, originals))
            }, sink)
            val collector = launch {
                producer.observe(upstream).collect { cards ->
                    if (cards.any { family -> family.copies.map { it.key }.toSet() == target.map { it.copies.single().key }.toSet() })
                        published.complete(Unit)
                }
            }
            try {
                upstream.emit(current)
                repeat(8) { revision ->
                    repeat(2) { requests.receive().second.complete(Unit) }
                    val held = requests.receive()
                    while (consumed.receive() != revision + 1L) { /* Await this generation's consumer, not producer speed. */ }
                    current = snapshot(revision + 1)
                    upstream.emit(current)
                    cancelled.receive()
                    held.second.complete(Unit)
                }
                // Keep the next acquisition blocked: no quiet final drain may make this assertion pass.
                val held = requests.receive()
                assertTrue("Unchanged tail originals must be acquired while earlier artwork continues changing",
                    targetRefs.all { it in acquired })
                published.await()
                assertTrue("Publication must have actual accepted artwork support", cache.acceptedPairs().any {
                    setOf(it.first, it.second) == targetHashes
                })
                assertFalse("The final acquisition remains gated", held.second.isCompleted)
            } finally { collector.cancelAndJoin(); requests.cancel(); cancelled.cancel(); consumed.cancel() }
        }
    }

    @Test fun cancelledQueuedOriginalsRemainPendingEvenAfterTheirCacheBindingsBecomeWarm() = runTest {
        val cache = ArtworkFeatureCache(temporary.newFolder(), MetadataClock { 100L })
        val historical = ArtworkReference(GameSource.GOG, url("historical"))
        cache.put(historical, features(historical))
        val targets = listOf(card(13), card(14))
        val targetRefs = targets.map { ArtworkReference(GameSource.GOG, url(it.copies.single().key.stableSourceId)) }
        val targetHashes = targetRefs.map { features(it).contentHash }.toSet()
        fun snapshot(revision: Int) = (1L..12L).map { id ->
            val card = card(id)
            card.copy(copies = card.copies.map { it.copy(originalArtwork = listOf(
                ArtworkReference(GameSource.GOG, url("$id-revision-$revision")))) })
        } + targets + card(15)
        var current = snapshot(0)
        val comparisonEntered = CountDownLatch(1)
        val releaseComparison = CountDownLatch(1)
        var firstComparison = true
        every { matcher.compare(any(), any()) } answers {
            if (firstComparison) {
                firstComparison = false
                comparisonEntered.countDown()
                assertTrue("Test must release the blocked consumer", releaseComparison.await(10, TimeUnit.SECONDS))
            }
            val accepted = setOf(firstArg<ArtworkFeatures>().contentHash, secondArg<ArtworkFeatures>().contentHash) == targetHashes
            ArtworkMatch(accepted, 30, if (accepted) 28 else 0, if (accepted) 0.93 else 0.0, 0.4, 10, 1.0)
        }
        val upstream = MutableSharedFlow<List<CanonicalLibraryCard>>(replay = 1)
        val requests = Channel<Pair<ArtworkReference, CompletableDeferred<Unit>>>(Channel.UNLIMITED)
        val cancelled = CompletableDeferred<Unit>()
        val replacementRendered = CompletableDeferred<Unit>()
        val published = CompletableDeferred<Unit>()
        val sink = object : CanonicalLibraryDiagnosticSink by diagnostics {
            override fun artworkScheduler(snapshot: ArtworkSchedulerSnapshot) {
                if (snapshot.stage == ArtworkSchedulerStage.CANCELLED) cancelled.complete(Unit)
            }
        }
        val producer = CanonicalFamilyArtworkCorroborator(source { ref ->
            val release = CompletableDeferred<Unit>()
            requests.send(ref to release)
            release.await()
            features(ref)
        }, cache, matcher, { key ->
            val card = current.single { it.copies.single().key == key }
            val originals = card.copies.single().originalArtwork.map { it.url }.ifEmpty { listOf(url(key.stableSourceId)) }
            OwnedCopyRuntimeResult.Available(runtime(card, originals))
        }, sink)
        val collector = launch {
            producer.observe(upstream).collect { cards ->
                if (cards.first().copies.first().originalArtwork.any { it.url == url("1-revision-1") })
                    replacementRendered.complete(Unit)
                if (cards.any { family -> family.copies.map { it.key }.toSet() == targets.map { it.copies.single().key }.toSet() })
                    published.complete(Unit)
            }
        }
        try {
            upstream.emit(current)
            requests.receive().second.complete(Unit)
            assertTrue(withContext(Dispatchers.Default) { comparisonEntered.await(10, TimeUnit.SECONDS) })
            repeat(13) { requests.receive().second.complete(Unit) }
            val held = requests.receive() // Producer has sent both targets, while the consumer is still blocked.
            assertEquals(url("15"), held.first.url)
            assertTrue(targetRefs.all { cache.contentHash(it) != null })
            assertTrue(cache.acceptedPairs().isEmpty())
            current = snapshot(1)
            upstream.emit(current)
            replacementRendered.await()
            testScheduler.runCurrent() // Drive cancellation into cancelAndJoin before releasing the Default worker.
            releaseComparison.countDown()
            cancelled.await()
            held.second.complete(Unit)
            assertTrue("The cancelled consumer must not have saved the target pair", cache.acceptedPairs().isEmpty())
            for (target in targetRefs) {
                val request = requests.receive()
                assertEquals("Cached but unconsumed targets must precede freshly replaced missing prefixes", target, request.first)
                request.second.complete(Unit)
            }
            val next = requests.receive()
            published.await()
            assertTrue(cache.acceptedPairs().any { setOf(it.first, it.second) == targetHashes })
            assertFalse("No quiet final acquisition drain", next.second.isCompleted)
        } finally { releaseComparison.countDown(); collector.cancelAndJoin(); requests.cancel() }
    }

    @Test fun singletonFinishesWithoutAcquisitionOrInventedArtworkFailure() = runTest {
        val cache = ArtworkFeatureCache(temporary.newFolder(), MetadataClock { 100L })
        val acquired = mutableListOf<ArtworkReference>()
        val singleton = listOf(cards.last())
        assertEquals(singleton, owner(cache, source { acquired += it; features(it) }).project(singleton))
        assertTrue("A singleton has no inter-canonical visual relationship to discover", acquired.isEmpty())
        verify(exactly = 0) { matcher.compare(any(), any()) }
        assertEquals(ArtworkTraceStage.FINISHED, observations.last().second.stage)
        assertFalse(observations.any { it.second.stage in setOf(ArtworkTraceStage.INELIGIBLE,
            ArtworkTraceStage.ORIGINAL_FETCHING, ArtworkTraceStage.ORIGINAL_UNAVAILABLE, ArtworkTraceStage.COMPARED) })
    }

    @Test fun lastVisualPeerRetirementCancelsWithoutReacquiringAndRecoversWhenPeerReturns() = runBlocking {
        withTimeout(10_000) {
            val cache = ArtworkFeatureCache(temporary.newFolder(), MetadataClock { 100L })
            val entered = CompletableDeferred<Unit>()
            val cleaned = CompletableDeferred<Unit>()
            val singletonFinished = CompletableDeferred<Unit>()
            val acquired = mutableListOf<ArtworkReference>()
            var blocked = true
            val upstream = MutableSharedFlow<List<CanonicalLibraryCard>>(replay = 1)
            val emitted = Channel<List<CanonicalLibraryCard>>(Channel.UNLIMITED)
            val sink = object : CanonicalLibraryDiagnosticSink by diagnostics {
                override fun artworkState(game: ArtworkGameTrace, observation: ArtworkTraceObservation) {
                    diagnostics.artworkState(game, observation)
                    if (game.publicTitle == "DREDGE 2" && observation.stage == ArtworkTraceStage.FINISHED)
                        singletonFinished.complete(Unit)
                }
            }
            val producer = owner(cache, source {
                acquired += it
                if (it.url == url("2") && blocked) {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { cleaned.complete(Unit) }
                }
                features(it)
            }, diagnosticSink = sink)
            val collector = launch { producer.observe(upstream).collect { emitted.send(it) } }
            try {
                upstream.emit(cards)
                entered.await()
                upstream.emit(listOf(cards.last()))
                cleaned.await()
                singletonFinished.await()
                assertEquals("Retirement must not restart acquisition without a visual peer",
                    listOf(url("1"), url("2")), acquired.map { it.url })
                val retiredSnapshots = mutableListOf<List<CanonicalLibraryCard>>()
                while (true) retiredSnapshots += emitted.tryReceive().getOrNull() ?: break
                assertEquals(listOf(cards.last()), retiredSnapshots.last())
                assertFalse(retiredSnapshots.flatten().any { it.isPresentationFamily })

                blocked = false
                upstream.emit(cards)
                var family: CanonicalLibraryCard
                while (true) {
                    val snapshot = emitted.receive()
                    if (snapshot.size == 1 && snapshot.single().isPresentationFamily) {
                        family = snapshot.single()
                        break
                    }
                }
                assertEquals(cards.flatMap { it.copies }.toSet(), family.copies.toSet())
                assertEquals("The returned peer must enable a fresh acquisition", 2, acquired.count { it.url == url("2") })
                assertEquals(cards.associate { it.copies.single().key to it.canonicalId }, family.copyCanonicalIds)
            } finally { collector.cancelAndJoin(); emitted.cancel() }
        }
    }

    @Test fun cachedTraceReplayObservesOwnerCancellationBeforeNextPair() = runTest {
        val ref = ArtworkReference(GameSource.GOG, url("1"))
        val cache = ArtworkFeatureCache(temporary.newFolder(), MetadataClock { 100L })
        cache.put(ref, features(ref))
        cache.markIndexed(features(ref).contentHash)
        repeat(32) { cache.putComparison(features(ref).contentHash, ArtworkFeatures.hash("old-$it".toByteArray()), false) }
        var cachedPairs = 0
        lateinit var worker: Job
        val cancellingSink = object : CanonicalLibraryDiagnosticSink by diagnostics {
            override fun artworkState(game: ArtworkGameTrace, observation: ArtworkTraceObservation) {
                diagnostics.artworkState(game, observation)
                if (observation.stage == ArtworkTraceStage.CACHE_HIT && observation.pair != null) {
                    cachedPairs++
                    worker.cancel()
                }
            }
        }
        worker = launch { owner(cache, diagnosticSink = cancellingSink).project(cards) }
        worker.join()
        assertTrue(worker.isCancelled)
        assertEquals("Cancelled generations must not drain the remaining historical traces", 1, cachedPairs)
        assertFalse(observations.any { it.second.stage == ArtworkTraceStage.FINISHED })
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
