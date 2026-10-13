package app.gamenative.library.canonical

import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.canonical.artwork.ArtworkFeatureCache
import app.gamenative.library.canonical.artwork.ArtworkFeatureFixtures
import app.gamenative.library.canonical.artwork.ArtworkFeatureMatcher
import app.gamenative.library.canonical.artwork.ArtworkFeatureSource
import app.gamenative.library.canonical.artwork.ArtworkFeatures
import app.gamenative.library.canonical.artwork.ArtworkReference
import app.gamenative.library.canonical.runtime.OwnedCopyRuntime
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeResult
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import app.gamenative.library.metadata.MetadataClock
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Revised visual admission, with the previous exact-native, cancellation and manual-decision safeguards. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, manifest = Config.NONE, sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CanonicalFamilyArtworkCorroboratorTest {
    @get:Rule val timeout = Timeout.seconds(30)
    @get:Rule val temporary = TemporaryFolder()
    private val cards = listOf(card(1, "Harbour adventure"), card(2, "Collector bonus pack"))
    private val runtimes: MutableMap<OwnedCopyKey, OwnedCopyRuntimeResult> = cards.associate { it.copies.single().key to available(it) }.toMutableMap()
    private val reads = mutableListOf<OwnedCopyKey>()
    private val images = Images()
    private val clock = MetadataClock { 100L }
    private var lookupAction: suspend (OwnedCopyKey) -> OwnedCopyRuntimeResult = { runtimes.getValue(it) }
    private val cache by lazy { ArtworkFeatureCache(temporary.newFolder(), clock) }

    @Test fun realOriginalsGroupWithoutCatalogOwnershipOrMetadataReassignment() = runTest {
        val family = producer().project(cards).single()
        assertEquals(cards.flatMap { it.copies }.toSet(), family.copies.toSet())
        assertEquals(mapOf(cards[0].canonicalId to 42, cards[1].canonicalId to 43), family.memberSteamAppIds)
        assertEquals(cards.associate { it.copies.single().key to it.canonicalId }, family.copyCanonicalIds)
        assertEquals(listOf(originalUrl(1), originalUrl(2)), images.requests.map { it.url })
        assertTrue(reads.groupingBy { it }.eachCount().values.all { it >= 2 })
    }

    @Test fun publicGamesDbOriginalsGroupWithoutSteamIdentity() = runTest {
        replace(1) { it.copy(originalArtworkUrl = "${originalUrl(1)}?namespace=gamesdb") }
        replace(2) { it.copy(originalArtworkUrl = "${originalUrl(2)}?namespace=gamesdb") }
        val input = cards.map { it.copy(steamAppId = null) }
        assertEquals(input.flatMap { it.copies }.toSet(), producer().project(input).single().copies.toSet())
        assertTrue(images.requests.all { it.url.endsWith("?namespace=gamesdb") })
    }

    @Test fun differentArtworkDoesNotGroup() = runTest {
        images.action = { artwork(if (it.url == originalUrl(2)) "portal2.jpg" else "dredge-gog.jpg") }
        assertEquals(2, producer().project(cards).size)
    }

    @Test fun absentOriginalCannotAcquireUnvalidatedDisplayUrls() = runTest {
        replace(2) { it.copy(originalArtworkUrl = null) }
        val input = cards.map { it.copy(headerImageUrl = "https://untrusted.example/rendered-card.png") }
        assertEquals(2, producer().project(input).size)
        assertTrue(images.requests.none { it.url.contains("untrusted.example") })
    }

    @Test fun rawCatalogHeadersAlreadyChosenByTheCardGeneratorAreVisualEvidenceNotOwnedSteamCopies() = runTest {
        replace(1) { it.copy(originalArtworkUrl = null) }
        replace(2) { it.copy(originalArtworkUrl = null) }
        val input = cards.map { card -> card.copy(headerImageUrl =
            "https://shared.akamai.steamstatic.com/steam/apps/${card.steamAppId}/header.jpg") }
        val family = producer().project(input).single()
        assertEquals(setOf(GameSource.GOG), family.ownedSources)
        assertEquals(input.flatMap { it.copies }.toSet(), family.copies.toSet())
        assertTrue(images.requests.all { it.source == GameSource.STEAM })
        assertEquals(setOf(42, 43), images.requests.map { it.steamAppId }.toSet())
    }

    @Test fun wrongStoreAndCredentialUrlsCannotBecomeNativeEvidence() = runTest {
        for (url in listOf("https://shared.akamai.steamstatic.com/steam/apps/42/library_600x900.jpg",
            "https://user:secret@images.gog.com/private.jpg")) {
            images.requests.clear()
            replace(2) { it.copy(originalArtworkUrl = url) }
            assertEquals(2, producer().project(cards).size)
            assertTrue(images.requests.none { it.url == url })
        }
    }

    @Test fun nativeSteamArtworkBindsToTheOwnedIdNotTheRepresentativeCatalog() = runTest {
        val native = card(1, "Different title", GameSource.STEAM, "101")
        val key = native.copies.single().key
        runtimes[key] = OwnedCopyRuntimeResult.Available(runtime(native).copy(
            reference = SourceOwnedCopyReference.Steam(key, 101),
            originalArtworkUrl = "https://shared.akamai.steamstatic.com/steam/apps/101/header.jpg"))
        assertEquals(1, producer().project(listOf(native, cards[1])).size)
        assertTrue(images.requests.any { it.source == GameSource.STEAM && it.steamAppId == 101 })
        val current = (runtimes.getValue(key) as OwnedCopyRuntimeResult.Available).copy
        runtimes[key] = OwnedCopyRuntimeResult.Available(current.copy(
            originalArtworkUrl = "https://shared.akamai.steamstatic.com/steam/apps/42/header.jpg"))
        images.requests.clear()
        assertEquals(2, producer().project(listOf(native, cards[1])).size)
        assertTrue(images.requests.none { it.source == GameSource.STEAM })
    }

    @Test fun wrongRuntimeKeyReferenceOrLegacyBridgeCannotSupplyEvidence() = runTest {
        val expected = runtime(cards[1])
        val foreign = cards[0].copies.single().key
        for (changed in listOf(expected.copy(key = foreign),
            expected.copy(reference = SourceOwnedCopyReference.Gog(foreign, "1")),
            expected.copy(reference = SourceOwnedCopyReference.Gog(expected.key, "999")),
            expected.copy(libraryItem = expected.libraryItem!!.copy(appId = "GOG_999")))) {
            runtimes[expected.key] = OwnedCopyRuntimeResult.Available(changed)
            images.requests.clear()
            assertEquals(2, producer().project(cards).size)
            assertTrue(images.requests.none { it.url == originalUrl(2) })
        }
    }

    @Test fun titleDeveloperAndProductTypeCannotVetoDistributedArtwork() = runTest {
        replace(1) { it.copy(developerKey = "One Studio") }
        replace(2) { it.copy(nativeTitle = "Other Game", developerKey = "Other Studio", appType = CanonicalAppType.DLC) }
        for (type in CanonicalAppType.entries) {
            assertEquals(1, producer().project(listOf(cards[0], cards[1].copy(appType = type))).size)
        }
    }

    @Test fun visualFamiliesAreNotTruncatedAtTenMembers() = runTest {
        val input = (1L..12L).map { card(it, "Different product $it") }
        input.forEach { runtimes[it.copies.single().key] = available(it) }
        val family = producer().project(input).single()
        assertEquals(12, family.copies.size)
        assertEquals(input.map { it.canonicalId }.toSet(), family.memberSteamAppIds.keys)
    }

    @Test fun malformedMemberBindingsAndDuplicateKeysCannotPublishEvidence() = runTest {
        val excess = cards[0].copy(copies = (1L..11L).map { card(it, "Different product").copies.single() })
        val malformed = listOf(excess, cards[1].copy(canonicalId = cards[0].canonicalId, key = cards[0].key),
            cards[1].copy(copies = cards[0].copies))
        malformed.forEach { assertEquals(2, producer().project(listOf(cards[0], it)).size) }
        assertTrue(images.requests.isEmpty())
    }

    @Test fun missingThirdArtworkDoesNotVetoTheVerifiedPair() = runTest {
        val third = card(3, "Unrelated name")
        runtimes[third.copies.single().key] = available(third)
        images.action = { if (it.url == originalUrl(3)) null else artwork() }
        val result = producer().project(cards + third)
        assertEquals(2, result.size)
        assertEquals(2, result.single { it.isPresentationFamily }.copies.size)
    }

    @Test fun retiredMemberAfterFetchingCannotPublishStaleEdges() = runTest {
        images.action = {
            if (it.url == originalUrl(2)) runtimes[cards[0].copies.single().key] = OwnedCopyRuntimeResult.Hidden
            artwork()
        }
        assertEquals(2, producer().project(cards).size)
    }

    @Test fun changedOriginalOrNativeReferenceDiscardsEvidenceButMetadataChangesDoNot() = runTest {
        val original = runtime(cards[0])
        for ((changed, count) in listOf(original.copy(originalArtworkUrl = "https://images.gog.com/changed.jpg") to 2,
            original.copy(reference = SourceOwnedCopyReference.Gog(original.key, "999")) to 2,
            original.copy(nativeTitle = "Changed Game", developerKey = "Changed Studio") to 1)) {
            runtimes[original.key] = OwnedCopyRuntimeResult.Available(original)
            images.action = {
                if (it.url == originalUrl(2)) runtimes[original.key] = OwnedCopyRuntimeResult.Available(changed)
                artwork()
            }
            // A new evidence store prevents a warm historical edge hiding the fetch-time fence under test.
            assertEquals(count, producer(ArtworkFeatureCache(temporary.newFolder(), clock)).project(cards).size)
        }
    }

    @Test fun optionalTransportFailurePreservesCards() = runTest {
        images.action = { if (it.url == originalUrl(2)) throw IOException("synthetic transport failure") else artwork() }
        assertEquals(cards, producer().project(cards))
    }

    @Test fun programmingFailureIsNotSilentlyReportedAsMissingArtwork() = runTest {
        images.action = { throw IllegalStateException("synthetic programming failure") }
        assertTrue(runCatching { producer().project(cards) }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun owningCancellationPropagates() = runTest {
        images.action = { throw CancellationException("synthetic owning cancellation") }
        assertTrue(runCatching { producer().project(cards) }.exceptionOrNull() is CancellationException)
    }

    @Test fun perImageTimeoutContinuesWithoutDiscardingEarlierOrLaterProgress() = runTest {
        val later = (3L..4L).map { card(it, "Other product $it") }
        later.forEach { runtimes[it.copies.single().key] = available(it) }
        var cleaned = false
        images.action = {
            if (it.url == originalUrl(3)) try { awaitCancellation() } finally { cleaned = true }
            else artwork()
        }
        val updates = producer().projectBatches(cards + later).toList()
        assertTrue(updates.any { it.size == 3 })
        assertEquals(2, updates.last().size)
        assertEquals(3, updates.last().single { it.isPresentationFamily }.copies.size)
        assertEquals(20_000L, testScheduler.currentTime)
        assertTrue(cleaned)
        assertTrue(images.requests.any { it.url == originalUrl(4) })
    }

    @Test fun imageAndNativeLookupTimeoutsCleanUpAndLeaveRawCards() = runTest {
        var cleaned = 0
        images.action = { try { awaitCancellation() } finally { cleaned++ } }
        assertEquals(cards, producer().project(cards))
        assertEquals(40_000L, testScheduler.currentTime)
        assertEquals(2, cleaned)
        images.requests.clear()
        lookupAction = { try { awaitCancellation() } finally { cleaned++ } }
        assertEquals(cards, producer().project(cards))
        assertEquals(80_000L, testScheduler.currentTime)
        assertEquals(4, cleaned)
        assertTrue(images.requests.isEmpty())
    }

    @Test fun restartAndMetadataSyncReuseCompletedPairsWhileChangedArtComparesIncrementally() = runTest {
        val folder = temporary.newFolder()
        val firstMatcher = io.mockk.spyk(ArtworkFeatureMatcher())
        val first = CanonicalFamilyArtworkCorroborator(images, ArtworkFeatureCache(folder, clock), firstMatcher, lookupAction)
        assertEquals(1, first.project(cards).size)
        io.mockk.verify(exactly = 1) { firstMatcher.compare(any(), any()) }
        val restartedMatcher = io.mockk.spyk(ArtworkFeatureMatcher())
        val restarted = CanonicalFamilyArtworkCorroborator(images, ArtworkFeatureCache(folder, clock), restartedMatcher, lookupAction)
        val synced = cards.reversed().map { it.copy(displayName = "Other catalog title", appType = CanonicalAppType.DEMO) }
        assertEquals(1, restarted.project(synced).size)
        io.mockk.verify(exactly = 0) { restartedMatcher.compare(any(), any()) }
        images.action = { artwork(if (it.url == originalUrl(2)) "portal2.jpg" else "dredge-gog.jpg") }
        assertEquals(2, restarted.project(cards).size)
        io.mockk.verify(exactly = 2) { restartedMatcher.compare(any(), any()) }
    }

    @Test fun theActualFamilyOwnerReportsPairOutcomesWithoutNativeAccountOrCopyPayloads() = runTest {
        producer().project(cards)
        io.mockk.verify(exactly = 1) {
            diagnostics.artworkCompared(match { it.value == ArtworkPairDigest.from(artwork(), artwork("dredge-epic.jpg")).value },
                match { it.accepted && it.completed && it.inliers >= 24 }, any())
        }
        io.mockk.verify { diagnostics.cardsProjected(1, 2, 2, any()) }
    }

    @Test fun statEmissionsReuseThePendingWorkerAndPublishLatestCopies() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        images.action = { entered.complete(Unit); release.await(); artwork() }
        val upstream = MutableSharedFlow<List<CanonicalLibraryCard>>()
        val updates = Channel<List<CanonicalLibraryCard>>(Channel.UNLIMITED)
        val collector = launch { producer().observe(upstream).collect { updates.send(it) } }
        testScheduler.runCurrent()
        upstream.emit(cards)
        assertEquals(cards, updates.receive())
        entered.await()
        val latest = cards.map { card -> card.copy(copies = card.copies.map { it.copy(playtimeMinutes = 999, isInstalled = true) }) }
        repeat(5) { upstream.emit(latest) }
        assertEquals(latest, updates.receive())
        release.complete(Unit)
        var family = updates.receive()
        while (family.size != 1) family = updates.receive()
        assertTrue(family.single().copies.all { it.playtimeMinutes == 999L && it.isInstalled })
        assertEquals(2, images.requests.size)
        collector.cancelAndJoin()
    }

    @Test fun automaticCatalogMetadataEmissionsCannotStarveUnchangedOriginalArtwork() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        images.action = { entered.complete(Unit); release.await(); artwork() }
        val upstream = MutableSharedFlow<List<CanonicalLibraryCard>>()
        val updates = Channel<List<CanonicalLibraryCard>>(Channel.UNLIMITED)
        val initial = cards.map { it.copy(memberSteamAppIds = mapOf(it.canonicalId to it.steamAppId)) }
        val collector = launch { producer().observe(upstream).collect { updates.send(it) } }
        try {
            testScheduler.runCurrent()
            upstream.emit(initial)
            assertEquals(initial, updates.receive())
            entered.await()
            val latest = initial.map { card ->
                val id = card.steamAppId!! + 500
                card.copy(displayName = "Changed catalog", appType = CanonicalAppType.DLC, steamAppId = id,
                    memberSteamAppIds = mapOf(card.canonicalId to id), copies = card.copies.map {
                        it.copy(decisionCandidateSteamAppId = id, decisionRevision = 200, playtimeMinutes = 123)
                    })
            }
            upstream.emit(latest)
            assertEquals(latest, updates.receive())
            release.complete(Unit)
            var family = updates.receive()
            while (family.size != 1) family = updates.receive()
            assertEquals(latest.associate { it.canonicalId to it.steamAppId }, family.single().memberSteamAppIds)
            assertTrue(family.single().copies.all { it.playtimeMinutes == 123L && it.decisionRevision == 200L })
            assertEquals("Only actual image/membership/manual changes replace the worker", 2, images.requests.size)
        } finally { collector.cancelAndJoin() }
    }

    @Test fun unavailableNativeMatchingIsRetriedInsteadOfPersistingANegativeOrCompletedIndexMarker() = runTest {
        val matching = io.mockk.spyk(ArtworkFeatureMatcher())
        io.mockk.every { matching.compare(any(), any()) } returns app.gamenative.library.canonical.artwork.ArtworkMatch(false, completed = false)
        val owner = CanonicalFamilyArtworkCorroborator(images, cache, matching, lookupAction)
        assertEquals(2, owner.project(cards).size)
        assertEquals(null, cache.comparison(artwork().contentHash, artwork("dredge-epic.jpg").contentHash))
        assertTrue(!cache.isIndexed(artwork("dredge-epic.jpg").contentHash))
        io.mockk.every { matching.compare(any(), any()) } answers { callOriginal() }
        assertEquals(1, owner.project(cards).size)
        io.mockk.verify(exactly = 2) { matching.compare(any(), any()) }
    }

    @Test fun pendingManualSeparationAndChangedArtworkFenceOldWorkerOutcomes() = runTest {
        val original = runtime(cards[1])
        for (manual in listOf(true, false)) {
            runtimes[original.key] = OwnedCopyRuntimeResult.Available(original)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val upstream = Channel<List<CanonicalLibraryCard>>(Channel.UNLIMITED)
            val updates = Channel<List<CanonicalLibraryCard>>(Channel.UNLIMITED)
            val changedUrl = "https://images.gog.com/changed.jpg"
            images.action = { reference ->
                if (reference.url == originalUrl(2)) {
                    entered.complete(Unit)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await(); artwork("dredge-epic.jpg") }
                } else artwork(if (reference.url == changedUrl) "portal2.jpg" else "dredge-gog.jpg")
            }
            val collector = launch {
                try { producer(ArtworkFeatureCache(temporary.newFolder(), clock)).observe(upstream.receiveAsFlow()).collect { updates.send(it) } }
                finally { updates.close() }
            }
            try {
                upstream.send(cards)
                assertEquals(cards, updates.receive())
                entered.await()
                val changed = if (manual) cards[1].copy(familyGroupingSuppressed = true) else {
                    runtimes[original.key] = OwnedCopyRuntimeResult.Available(original.copy(originalArtworkUrl = changedUrl))
                    cards[1].copy(copies = cards[1].copies.map {
                        it.copy(originalArtwork = listOf(ArtworkReference(GameSource.GOG, changedUrl)))
                    })
                }
                val latest = listOf(cards[0], changed)
                upstream.send(latest)
                assertEquals(latest, updates.receive())
                release.complete(Unit)
                upstream.close()
                collector.join()
                assertTrue(updates.receiveAsFlow().toList().none { batch -> batch.any { it.isPresentationFamily } })
            } finally {
                release.complete(Unit)
                upstream.close()
                collector.cancelAndJoin()
            }
        }
    }

    private val diagnostics = io.mockk.mockk<CanonicalLibraryDiagnosticSink>(relaxed = true)
    private fun producer(evidence: ArtworkFeatureCache = cache) = CanonicalFamilyArtworkCorroborator(images, evidence, ArtworkFeatureMatcher(),
        lookupRuntime = { reads += it; lookupAction(it) }, diagnostics = diagnostics)
    private fun replace(index: Int, change: (OwnedCopyRuntime) -> OwnedCopyRuntime) {
        val key = cards[index - 1].copies.single().key
        runtimes[key] = OwnedCopyRuntimeResult.Available(change((runtimes.getValue(key) as OwnedCopyRuntimeResult.Available).copy))
    }
    private fun artwork(name: String = "dredge-gog.jpg") = ArtworkFeatureFixtures.artwork(name)
    private fun originalUrl(index: Long) = "https://images.gog.com/fixture-$index.jpg"
    private fun originalUrl(index: Int) = originalUrl(index.toLong())
    private fun available(card: CanonicalLibraryCard) = OwnedCopyRuntimeResult.Available(runtime(card))
    private fun runtime(card: CanonicalLibraryCard): OwnedCopyRuntime {
        val copy = card.copies.single()
        return OwnedCopyRuntime(copy.key, SourceOwnedCopyReference.Gog(copy.key, copy.key.stableSourceId),
            LibraryItem(appId = "${copy.source.name}_${copy.key.stableSourceId}", name = copy.nativeTitle, gameSource = copy.source),
            copy.nativeTitle, emptySet(), "", null, CanonicalAppType.GAME, emptySet(), emptySet(), emptySet(),
            "", "", "", "", 1f, null, null, null, false, false, false, false, false, null, null,
            setOf(OwnedCopyOperation.INSTALL), originalArtworkUrl = originalUrl(UUID.fromString(card.canonicalId.value).leastSignificantBits))
    }
    private fun card(index: Long, title: String, source: GameSource = GameSource.GOG, stableId: String = "$index"): CanonicalLibraryCard {
        val id = CanonicalGameId.parse(UUID(0, index).toString())
        val key = OwnedCopyKey(AccountScope("2".repeat(64)), source, stableId)
        val summary = OwnedCopySummary(key, source, title, null, null, null, false, false, false, false, false,
            null, null, setOf(OwnedCopyOperation.INSTALL), null, true, MatchMethod.STEAM_CATALOG, MatchConfidence.HIGH,
            MatchDecisionSource.AUTOMATIC, 41 + index.toInt(), CURRENT_RESOLVER_VERSION, 100)
        return CanonicalLibraryCard(CanonicalCardKey.Grouped(id), id, title, CanonicalAppType.GAME, "",
            "https://shared.akamai.steamstatic.com/steam/apps/999/library_600x900.jpg", "", "", 1f,
            setOf(title), setOf(source), listOf(summary), null, emptySet(), false, steamAppId = 41 + index.toInt())
    }
    private inner class Images : ArtworkFeatureSource {
        val requests = mutableListOf<ArtworkReference>()
        var action: suspend (ArtworkReference) -> ArtworkFeatures? = {
            artwork(when {
                it.url == originalUrl(2) -> "dredge-epic.jpg"
                it.url.contains("/43/") -> "dredge-steam.jpg"
                else -> "dredge-gog.jpg"
            })
        }
        override suspend fun original(reference: ArtworkReference): ArtworkFeatures? { requests += reference; return action(reference) }
    }
}
