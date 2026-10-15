package app.gamenative.library.canonical

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.*
import app.gamenative.library.canonical.artwork.*
import app.gamenative.library.metadata.MetadataClock
import io.mockk.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout

class AggregationDiagnosticReportTest {
    @get:Rule val temporary = TemporaryFolder()
    @get:Rule val timeout = Timeout.seconds(30)
    private val cards = listOf(card(1), card(2))
    private val cache by lazy { ArtworkFeatureCache(temporary.newFolder("cache"), MetadataClock { 100 }) }
    private val matcher = mockk<ArtworkFeatureMatcher>().also {
        every { it.compare(any(), any()) } returns ArtworkMatch(false, matches = 30, inliers = 25,
            inlierRatio = .8, coverage = .02, occupiedCells = 3, reason = ArtworkMatchReason.LOW_COVERAGE)
    }
    private val inspected = mutableListOf<ArtworkReference>()
    private var inspection: suspend (ArtworkReference) -> ArtworkOriginalInspection = { ref ->
        inspected += ref
        ArtworkOriginalInspection(ref, feature(ref), ArtworkAcquisitionStage.FEATURES_READY, 200)
    }
    private suspend fun simulate(input: List<CanonicalLibraryCard> = cards, budgetMs: Long = 60_000,
        validate: suspend (OwnedCopySummary) -> Boolean = { true }) = AggregationArtworkSimulator.run(
        input, input.first().copies.map { it.key }.toSet(), cache, matcher, validate, inspection, budgetMs)
    private fun report(input: List<CanonicalLibraryCard>, result: AggregationArtworkSimulation) =
        AggregationDiagnosticReport.build(input, CanonicalGameFamilyProjection.project(input, result.cachedEdges).first(), emptyList(), result)

    @Test fun cachedArtworkSupportAndFreshRejectionRemainSeparateAndReadOnly() = runTest {
        seed(cards)
        val refs = cards.map { it.copies.single().originalArtwork.single() }
        cache.putComparison(feature(refs[0]).contentHash, feature(refs[1]).contentHash, true); cache.flush()
        val before = files()
        val result = simulate()
        val text = report(cards, result)
        assertTrue(text.contains("Admitted support: TITLE_AND_ARTWORK"))
        assertTrue(text.contains("Historical cached verdict: ACCEPTED"))
        assertTrue(text.contains("Current replay: REJECTED — LOW_COVERAGE"))
        assertTrue(text.contains("Isolated current replay support: TITLE"))
        assertTrue(text.contains("original rejection reason and ORB metrics were not retained"))
        assertEquals(before, files())
        assertEquals(true, cache.comparison(feature(refs[0]).contentHash, feature(refs[1]).contentHash))
    }

    @Test fun freshStrongArtworkSupportsDifferentFullTitlesInsideTheCachedFamilyWithoutChangingCache() = runTest {
        val input = listOf(cards[0], cards[1].copy(displayName = "Different edition name"))
        seed(input)
        val refs = input.map { it.copies.single().originalArtwork.single() }
        cache.putComparison(feature(refs[0]).contentHash, feature(refs[1]).contentHash, true); cache.flush()
        val before = files()
        every { matcher.compare(any(), any()) } returns ArtworkMatch(true, 100, 90, .9, .3, 9, 1.0)
        val result = simulate(input)
        val text = report(input, result)
        assertTrue(text.contains("Admitted support: ARTWORK"))
        assertTrue(text.contains("Isolated current replay support: ARTWORK"))
        assertTrue(text.contains("Current replay: ACCEPTED"))
        assertTrue(text.contains("full titles differ"))
        assertEquals(before, files())
    }

    @Test fun unavailableNativeMatcherIsNotCachedOrLabeledAsRejected() = runTest {
        every { matcher.compare(any(), any()) } returns ArtworkMatch(false, completed = false)
        val result = simulate()
        assertTrue(report(cards, result).contains("UNAVAILABLE — NATIVE_UNAVAILABLE"))
        assertTrue(cache.comparisonResults().isEmpty())
        assertTrue(result.replayEdges.isEmpty())
    }

    @Test fun httpUnavailableOriginalIsNotAFailedVisualComparison() = runTest {
        inspection = { ref -> ArtworkOriginalInspection(ref, null, ArtworkAcquisitionStage.HTTP_UNAVAILABLE, 404) }
        val text = report(cards, simulate())
        assertTrue(text.contains("HTTP_UNAVAILABLE (HTTP 404)"))
        assertTrue(text.contains("not a rejected visual match"))
        verify(exactly = 0) { matcher.compare(any(), any()) }
    }

    @Test fun changedContentDoesNotAttachOldBooleanVerdictToNewPixels() = runTest {
        seed(cards)
        val refs = cards.map { it.copies.single().originalArtwork.single() }
        cache.putComparison(feature(refs[0]).contentHash, feature(refs[1]).contentHash, false); cache.flush()
        inspection = { ref -> ArtworkOriginalInspection(ref, feature(ref, "changed"), ArtworkAcquisitionStage.FEATURES_READY) }
        assertTrue(report(cards, simulate()).contains("CONTENT CHANGED"))
    }

    @Test fun manualExclusionSkipsAcquisitionAndTitleAdmission() = runTest {
        val input = listOf(cards[0], cards[1].copy(familyGroupingSuppressed = true))
        val text = report(input, simulate(input))
        assertTrue(text.contains("SKIPPED — manual/native/identity safeguard"))
        assertTrue(inspected.none { it == input[1].copies.single().originalArtwork.single() })
        verify(exactly = 0) { matcher.compare(any(), any()) }
    }

    @Test fun cachedMixedTransitiveGraphUsesTheActualProjectionAndExactOriginals() = runTest {
        val third = card(3).copy(displayName = "Other public name")
        val input = cards + third
        seed(input)
        val a = input[1].copies.single().originalArtwork.single()
        val b = third.copies.single().originalArtwork.single()
        cache.putComparison(feature(a).contentHash, feature(b).contentHash, true); cache.flush()
        val result = simulate(input)
        assertEquals(3, result.focused.size)
        assertEquals(3, CanonicalGameFamilyProjection.project(input, result.cachedEdges).single().copies.size)
        assertTrue(report(input, result).contains("Admitted support: TITLE_AND_ARTWORK"))
        assertEquals(input.flatMap { it.copies }.toSet(), CanonicalGameFamilyProjection.project(input, result.cachedEdges).single().copies.toSet())
    }

    @Test fun freshReplayBudgetReportsIncompleteRatherThanInventingFailure() = runTest {
        inspection = { awaitCancellation() }
        val result = simulate(budgetMs = 30)
        assertTrue(result.budgetExhausted)
        assertTrue(report(cards, result).contains("INCOMPLETE REPLAY"))
        assertTrue(result.pairs.all { it.replay == null })
    }

    @Test fun owningCancellationPropagatesWithoutWrites() = runTest {
        val entered = CompletableDeferred<Unit>()
        inspection = { entered.complete(Unit); awaitCancellation() }
        val task = async { simulate() }
        entered.await(); task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertTrue(cache.comparisonResults().isEmpty())
    }

    @Test fun nativeRevalidationMarksStaleInsteadOfPublishingAReplayCause() = runTest {
        var reads = 0
        val result = simulate(validate = { ++reads <= cards.size })
        assertTrue(result.stale)
        assertTrue(report(cards, result).contains("STALE"))
    }

    @Test fun sharedSingleOriginalRetainsItsSelfPairCachedVerdictAndFreshArtworkSupport() = runTest {
        val shared = cards[0].copies.single().originalArtwork
        val input = listOf(cards[0], cards[1].copy(displayName = "Different full title",
            copies = cards[1].copies.map { it.copy(originalArtwork = shared) }))
        seed(input)
        val image = feature(shared.single())
        cache.putComparison(image.contentHash, image.contentHash, true); cache.flush()
        val before = files()
        every { matcher.compare(any(), any()) } returns ArtworkMatch(true, 100, 90, .9, .3, 9, 1.0)
        val result = simulate(input)
        assertEquals("Shared original must replay the production multi-owner self-pair", 1, result.pairs.size)
        val pair = result.pairs.single()
        assertEquals(pair.left, pair.right)
        assertEquals(true, pair.cachedAccepted)
        assertTrue(pair.replay!!.accepted)
        assertTrue(report(input, result).contains("Isolated current replay support: ARTWORK"))
        verify(exactly = 1) { matcher.compare(any(), any()) }
        assertEquals(before, files())
    }

    @Test fun duplicateGlobalMembershipFailsClosedWithoutAcquisitionOrMatching() = runTest {
        val result = simulate(cards + cards[0])
        assertTrue(result.stale)
        assertTrue(inspected.isEmpty())
        verify(exactly = 0) { matcher.compare(any(), any()) }
    }

    @Test fun reportBoundsAreExplicitAndPrivateIdentityPathsCredentialsNeverEscape() = runTest {
        val input = (1L..30L).map { card(it, originals = 2) }
        val result = simulate(input)
        assertEquals(24, result.focused.size)
        assertEquals(32, result.originals.size)
        assertEquals(128, result.pairs.size)
        val text = report(input, result)
        assertTrue(text.contains("Omitted: 6 entries, 16 originals"))
        assertTrue(text.contains("INCOMPLETE REPLAY"))
        assertFalse(text.contains("2".repeat(64)))
        assertFalse(text.contains(input[0].canonicalId.value))
        assertFalse(text.contains("/storage/emulated/0/private"))
        for ((unsafeTitle, normalizedPrivateValue) in listOf(
            "DREDGE token=synthetic-secret" to "synthetic secret",
            "C:\\Users\\person\\private" to "c users person private",
            "/storage/emulated/0/private" to "storage emulated 0 private",
        )) {
            val poisoned = input[0].copy(displayName = unsafeTitle, copies = input[0].copies.map {
                it.copy(nativeTitle = "C:\\Users\\person\\private", installPath = "/storage/emulated/0/private")
            })
            val poisonedResult = result.copy(focused = listOf(poisoned))
            val protected = AggregationDiagnosticReport.build(listOf(poisoned), poisoned, emptyList(), poisonedResult)
            assertFalse(protected.contains("synthetic-secret")); assertFalse(protected.contains("C:\\Users"))
            assertFalse("Normalization must not launder private values into an exported title key", protected.contains(normalizedPrivateValue))
            assertTrue(protected.contains("Full title key: [redacted]"))
        }
    }

    private fun seed(input: List<CanonicalLibraryCard>) {
        input.flatMap { it.copies.flatMap { copy -> copy.originalArtwork } }.forEach { cache.put(it, feature(it)) }
        cache.flush()
    }
    private fun files(): Map<String, List<Byte>> = temporary.root.walkTopDown().filter(File::isFile)
        .associate { it.relativeTo(temporary.root).path to it.readBytes().toList() }
    private fun feature(ref: ArtworkReference, suffix: String = "") = ArtworkFeatures(
        ArtworkFeatures.hash((ref.url + suffix).toByteArray()), 128, 128, FloatArray(48) { (it % 6) / 6f }, ByteArray(24 * 32) { it.toByte() })
    private fun card(index: Long, originals: Int = 1): CanonicalLibraryCard {
        val id = CanonicalGameId.parse(UUID(0, index).toString())
        val key = OwnedCopyKey(AccountScope("2".repeat(64)), GameSource.GOG, "$index")
        val copy = OwnedCopySummary(key, key.source, "DREDGE", "/storage/emulated/0/private", null, null, false, false,
            false, false, false, null, null, emptySet(), null, true, MatchMethod.EXACT_METADATA,
            MatchConfidence.REVIEW_REQUIRED, MatchDecisionSource.AUTOMATIC, null, CURRENT_RESOLVER_VERSION, 100,
            originalArtwork = (1..originals).map { ArtworkReference(GameSource.GOG, "https://images.gog.com/public-$index-$it.jpg") })
        return CanonicalLibraryCard(CanonicalCardKey.Grouped(id), id, "DREDGE", CanonicalAppType.GAME,
            "", "", "", "", 1f, setOf("DREDGE"), setOf(GameSource.GOG), listOf(copy), null, emptySet(), false)
    }
}
