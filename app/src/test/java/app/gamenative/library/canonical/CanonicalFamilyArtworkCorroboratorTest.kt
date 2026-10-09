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
import app.gamenative.library.canonical.artwork.ArtworkFingerprint
import app.gamenative.library.canonical.artwork.ArtworkFingerprintSource
import app.gamenative.library.canonical.artwork.ArtworkFixtures
import app.gamenative.library.canonical.runtime.OwnedCopyRuntime
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeResult
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class CanonicalFamilyArtworkCorroboratorTest {
    @get:Rule val timeout = Timeout.seconds(10)
    private val cards = listOf(card(1, "Fixture Game"), card(2, "Fixture Game Deluxe"))
    private val runtimes = cards.associate { it.copies.single().key to available(it) }.toMutableMap()
    private val reads = mutableListOf<OwnedCopyKey>()
    private val images = Images()
    private var lookupAction: suspend (OwnedCopyKey) -> OwnedCopyRuntimeResult = { runtimes.getValue(it) }

    @Test
    fun resizedIndependentNativeOriginalsGroupWithoutCatalogOrOwnershipReassignment() = runTest {
        val family = project(producer()).single()
        assertEquals(cards.flatMap { it.copies }.toSet(), family.copies.toSet())
        assertEquals(mapOf(cards[0].canonicalId to 42, cards[1].canonicalId to 43), family.memberSteamAppIds)
        assertEquals(cards.associate { it.copies.single().key to it.canonicalId }, family.copyCanonicalIds)
        assertEquals(listOf(originalUrl(1), originalUrl(2)), images.requests.map { it.url })
        assertTrue(images.requests.all { it.source == GameSource.GOG && it.nativeAppId == null })
        assertTrue(reads.groupingBy { it }.eachCount().values.all { it >= 2 })
        assertEquals(0, images.candidateRequests)
    }

    @Test
    fun distinctOriginalArtworkLeavesTheEditionsSeparate() = runTest {
        images.action = { request -> fingerprint(distinct = request.url == originalUrl(2)) }
        assertEquals(2, project(producer()).size)
    }

    @Test
    fun absentNativeOriginalNeverUsesDisplayedOrCanonicalSteamArtwork() = runTest {
        replace(2) { it.copy(originalArtworkUrl = null) }
        assertEquals(2, project(producer()).size)
        assertTrue(images.requests.none { it.url.contains("steamstatic") })
        assertEquals(0, images.candidateRequests)
    }

    @Test
    fun gogSteamFallbackAndCredentialUrlsCannotBecomeOriginalEvidence() = runTest {
        val owner = producer()
        for (url in listOf("https://shared.akamai.steamstatic.com/steam/apps/42/library_600x900.jpg",
            "https://user:secret@images.gog.com/private.jpg")) {
            images.requests.clear()
            replace(2) { it.copy(originalArtworkUrl = url) }
            assertEquals(2, project(owner).size)
            assertTrue(images.requests.none { it.url == url })
        }
    }

    @Test
    fun steamOriginalMustBindToTheOwnedNativeAppIdNotRepresentativeCatalogMetadata() = runTest {
        val native = card(1, "Fixture Game", GameSource.STEAM, "101")
        val key = native.copies.single().key
        runtimes[key] = OwnedCopyRuntimeResult.Available(runtime(native).copy(
            reference = SourceOwnedCopyReference.Steam(key, 101),
            originalArtworkUrl = "https://shared.akamai.steamstatic.com/steam/apps/101/library_600x900.jpg",
        ))
        assertEquals(1, project(producer(), listOf(native, cards[1])).size)
        assertTrue(images.requests.any { it.source == GameSource.STEAM && it.nativeAppId == 101 })
        images.requests.clear()
        val current = (runtimes.getValue(key) as OwnedCopyRuntimeResult.Available).copy
        runtimes[key] = OwnedCopyRuntimeResult.Available(current.copy(
            originalArtworkUrl = "https://shared.akamai.steamstatic.com/steam/apps/42/library_600x900.jpg",
        ))
        assertEquals(2, project(producer(), listOf(native, cards[1])).size)
        assertTrue(images.requests.none { it.source == GameSource.STEAM })
    }

    @Test
    fun wrongRuntimeKeyOrReferenceCannotSupplyMemberArtwork() = runTest {
        val owner = producer()
        val expected = runtime(cards[1])
        val foreign = cards[0].copies.single().key
        for (changed in listOf(expected.copy(key = foreign),
            expected.copy(reference = SourceOwnedCopyReference.Gog(foreign, "1")),
            expected.copy(reference = SourceOwnedCopyReference.Gog(expected.key, "999")))) {
            runtimes[expected.key] = OwnedCopyRuntimeResult.Available(changed)
            images.requests.clear()
            assertEquals(2, project(owner).size)
            assertTrue(images.requests.none { it.url == originalUrl(2) })
        }
    }

    @Test
    fun changedNativeTitleTypeOrLegacyBridgeRejectsStaleMemberEvidence() = runTest {
        val owner = producer()
        val expected = runtime(cards[1])
        for (changed in listOf(expected.copy(nativeTitle = "Other Game"),
            expected.copy(appType = CanonicalAppType.DLC),
            expected.copy(libraryItem = expected.libraryItem!!.copy(appId = "GOG_999")))) {
            runtimes[expected.key] = OwnedCopyRuntimeResult.Available(changed)
            images.requests.clear()
            assertEquals(2, project(owner).size)
            assertTrue(images.requests.none { it.url == originalUrl(2) })
        }
    }

    @Test
    fun unrelatedTitlesAndNonGameMembersDoNotFetchArtwork() = runTest {
        val owner = producer()
        for (other in listOf(cards[1].copy(displayName = "Fixture Game 2"),
            cards[1].copy(displayName = "Deluxe Fixture Game"),
            cards[1].copy(appType = CanonicalAppType.DLC))) {
            assertEquals(2, project(owner, listOf(cards[0], other)).size)
        }
        assertTrue(reads.isEmpty())
        assertTrue(images.requests.isEmpty())
    }

    @Test
    fun moreThanTenMembersDoNotPublishAnArbitrarilyTruncatedFamily() = runTest {
        val input = (1L..11L).map { card(it, if (it == 1L) "Fixture Game" else "Fixture Game Deluxe") }
        assertEquals(11, project(producer(), input).size)
        assertTrue(reads.isEmpty())
        assertTrue(images.requests.isEmpty())
    }

    @Test
    fun excessiveCopiesWithinOneMemberDoNotCauseUnboundedNativeLookups() = runTest {
        val excess = cards[0].copy(copies = (1L..11L).map { card(it, "Fixture Game").copies.single() })
        assertEquals(2, project(producer(), listOf(excess, cards[1])).size)
        assertTrue(reads.isEmpty())
        assertTrue(images.requests.isEmpty())
    }

    @Test
    fun missingOneOfThreeOriginalsCannotPublishAPartialSubset() = runTest {
        val third = card(3, "Fixture Game Ultimate")
        runtimes[third.copies.single().key] = available(third)
        images.action = { request -> if (request.url == originalUrl(3)) null else fingerprint() }
        assertEquals(3, project(producer(), cards + third).size)
    }

    @Test
    fun knownNativeDeveloperContradictionPreventsArtworkOnlyGrouping() = runTest {
        replace(1) { it.copy(developerKey = "Fixture Studio") }
        replace(2) { it.copy(developerKey = "Other Studio") }
        assertEquals(2, project(producer()).size)
    }

    @Test
    fun retiredMemberAfterFetchingDiscardsTheEntireFamilyEvidence() = runTest {
        images.action = { request ->
            if (request.url == originalUrl(2)) runtimes[cards[0].copies.single().key] = OwnedCopyRuntimeResult.Hidden
            fingerprint()
        }
        assertEquals(2, project(producer()).size)
        assertTrue(reads.size >= 3)
    }

    @Test
    fun changedOriginalReferenceTitleOrDeveloperAfterFetchingDiscardsEvidence() = runTest {
        val owner = producer()
        val original = runtime(cards[0])
        for (changed in listOf(original.copy(originalArtworkUrl = "https://images.gog.com/changed.jpg"),
            original.copy(reference = SourceOwnedCopyReference.Gog(original.key, "999")),
            original.copy(nativeTitle = "Changed Game"), original.copy(developerKey = "Changed Studio"))) {
            runtimes[original.key] = OwnedCopyRuntimeResult.Available(original)
            images.action = { request ->
                if (request.url == originalUrl(2)) runtimes[original.key] = OwnedCopyRuntimeResult.Available(changed)
                fingerprint()
            }
            assertEquals(2, project(owner).size)
        }
    }

    @Test
    fun optionalTransportFailureDoesNotFailTheLibraryOrPublishPartialEvidence() = runTest {
        images.action = { request ->
            if (request.url == originalUrl(2)) throw IOException("synthetic image transport failure")
            fingerprint()
        }
        assertEquals(cards, project(producer()))
    }

    @Test
    fun programmingFailureIsNotSilentlyReportedAsMissingArtwork() = runTest {
        val owner = producer()
        images.action = { throw IllegalStateException("synthetic programming failure") }
        assertTrue(runCatching { project(owner) }.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun owningCancellationPropagatesWithoutPublishingAFamily() = runTest {
        val owner = producer()
        images.action = { throw CancellationException("synthetic owning cancellation") }
        assertTrue(runCatching { project(owner) }.exceptionOrNull() is CancellationException)
    }

    @Test
    fun wholeOptionalPhaseDeadlineIncludesImageFetchingAndCleansUp() = runTest {
        val owner = producer()
        var cleaned = false
        images.action = { try { awaitCancellation() } finally { cleaned = true } }
        assertEquals(cards, project(owner))
        assertEquals(20_000L, testScheduler.currentTime)
        assertTrue(cleaned)
    }

    @Test
    fun wholeOptionalPhaseDeadlineIncludesNativeLookupAndCleansUp() = runTest {
        val owner = producer()
        var cleaned = false
        lookupAction = { try { awaitCancellation() } finally { cleaned = true } }
        assertEquals(cards, project(owner))
        assertEquals(20_000L, testScheduler.currentTime)
        assertTrue(cleaned)
        assertTrue(images.requests.isEmpty())
    }

    @Test
    fun duplicateMemberOrCopyIdentityCannotBeUsedForIndependentCorroboration() = runTest {
        val owner = producer()
        for (malformed in listOf(cards[1].copy(canonicalId = cards[0].canonicalId,
            key = cards[0].key), cards[1].copy(copies = cards[0].copies))) {
            assertEquals(2, project(owner, listOf(cards[0], malformed)).size)
        }
        assertTrue(reads.isEmpty())
        assertTrue(images.requests.isEmpty())
    }

    private fun producer(): Any {
        val type = runCatching { Class.forName("app.gamenative.library.canonical.CanonicalFamilyArtworkCorroborator") }.getOrNull()
        assertTrue("Missing bounded independent native family-artwork producer", type != null)
        val lookup: suspend (OwnedCopyKey) -> OwnedCopyRuntimeResult = { key -> reads += key; lookupAction(key) }
        return type!!.getConstructor(ArtworkFingerprintSource::class.java, Function2::class.java).newInstance(images, lookup)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun project(owner: Any, input: List<CanonicalLibraryCard> = cards): List<CanonicalLibraryCard> =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            val method = owner.javaClass.methods.single { it.name == "project" && it.parameterCount == 2 }
            try { method.invoke(owner, input, continuation) } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        }

    private fun replace(index: Int, change: (OwnedCopyRuntime) -> OwnedCopyRuntime) {
        val key = cards[index - 1].copies.single().key
        val copy = (runtimes.getValue(key) as OwnedCopyRuntimeResult.Available).copy
        runtimes[key] = OwnedCopyRuntimeResult.Available(change(copy))
    }

    private fun originalUrl(index: Long) = "https://images.gog.com/fixture-$index.jpg"
    private fun originalUrl(index: Int) = originalUrl(index.toLong())
    private fun fingerprint(distinct: Boolean = false, width: Int = 192, height: Int = 288) =
        requireNotNull(ArtworkFingerprint.fromArgb(width, height, ArtworkFixtures.cover(width, height, distinct)))
    private fun available(card: CanonicalLibraryCard): OwnedCopyRuntimeResult = OwnedCopyRuntimeResult.Available(runtime(card))

    private fun runtime(card: CanonicalLibraryCard): OwnedCopyRuntime {
        val copy = card.copies.single()
        return OwnedCopyRuntime(
            key = copy.key, reference = SourceOwnedCopyReference.Gog(copy.key, copy.key.stableSourceId),
            libraryItem = LibraryItem(appId = "${copy.source.name}_${copy.key.stableSourceId}", name = copy.nativeTitle, gameSource = copy.source),
            nativeTitle = copy.nativeTitle, aliases = emptySet(), developerKey = "", releaseYear = null,
            appType = CanonicalAppType.GAME, genreKeys = emptySet(), tagIds = emptySet(), featureKeys = emptySet(),
            iconUrl = "", capsuleImageUrl = card.capsuleImageUrl, headerImageUrl = "", heroImageUrl = "", gridHeroImageScale = 1f,
            installPath = null, installedSizeBytes = null, branchOrVersion = null, isInstalled = false,
            isDownloading = false, hasPartialDownload = false, updateAvailable = false, isShared = false,
            lastPlayedEpochMs = null, playtimeMinutes = null, capabilities = setOf(OwnedCopyOperation.INSTALL),
            originalArtworkUrl = originalUrl(UUID.fromString(card.canonicalId.value).leastSignificantBits),
        )
    }

    private fun card(index: Long, title: String, source: GameSource = GameSource.GOG, stableId: String = "$index"): CanonicalLibraryCard {
        val id = CanonicalGameId.parse(UUID(0, index).toString())
        val key = OwnedCopyKey(AccountScope("2".repeat(64)), source, stableId)
        val summary = OwnedCopySummary(
            key = key, source = source, nativeTitle = title, installPath = null, installedSizeBytes = null,
            branchOrVersion = null, isInstalled = false, isDownloading = false, hasPartialDownload = false,
            updateAvailable = false, isShared = false, lastPlayedEpochMs = null, playtimeMinutes = null,
            capabilities = setOf(OwnedCopyOperation.INSTALL), unavailableReason = null, canSeparateMatch = true,
            matchMethod = MatchMethod.STEAM_CATALOG, confidence = MatchConfidence.HIGH,
            decisionSource = MatchDecisionSource.AUTOMATIC, decisionCandidateSteamAppId = 41 + index.toInt(),
            decisionResolverVersion = CURRENT_RESOLVER_VERSION, decisionRevision = 100,
        )
        return CanonicalLibraryCard(
            key = CanonicalCardKey.Grouped(id), canonicalId = id, displayName = title, appType = CanonicalAppType.GAME,
            iconUrl = "", capsuleImageUrl = "https://shared.akamai.steamstatic.com/steam/apps/999/library_600x900.jpg",
            headerImageUrl = "", heroImageUrl = "", gridHeroImageScale = 1f, aliases = setOf(title),
            ownedSources = setOf(source), copies = listOf(summary), preferredCopy = null,
            steamCollectionAppIds = if (source == GameSource.STEAM) setOf(stableId.toInt()) else emptySet(),
            isShared = false, steamAppId = 41 + index.toInt(),
        )
    }

    private data class OriginalRequest(val source: GameSource, val url: String, val nativeAppId: Int?)
    private inner class Images : ArtworkFingerprintSource {
        val requests = mutableListOf<OriginalRequest>()
        var candidateRequests = 0
        var action: suspend (OriginalRequest) -> ArtworkFingerprint? = { request ->
            if (request.url == originalUrl(2)) fingerprint(width = 128, height = 192) else fingerprint()
        }
        override suspend fun original(source: GameSource, raw: String, steamAppId: Int?): ArtworkFingerprint? {
            val request = OriginalRequest(source, raw, steamAppId)
            requests += request
            return action(request)
        }
        override suspend fun candidate(steamAppId: Int, raw: String): ArtworkFingerprint? {
            candidateRequests++
            throw AssertionError("Family evidence must never acquire a canonical Steam candidate image")
        }
    }
}
