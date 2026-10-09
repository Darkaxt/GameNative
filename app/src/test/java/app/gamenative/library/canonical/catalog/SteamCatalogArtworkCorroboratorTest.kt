package app.gamenative.library.canonical.catalog

import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.canonical.artwork.ArtworkFingerprint
import app.gamenative.library.canonical.artwork.ArtworkFingerprintSource
import app.gamenative.library.canonical.artwork.ArtworkFixtures
import app.gamenative.library.canonical.runtime.OwnedCopyRuntime
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeResult
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class SteamCatalogArtworkCorroboratorTest {
    @get:Rule val timeout = Timeout.seconds(5)
    private val key = OwnedCopyKey(AccountScope("2".repeat(64)), GameSource.GOG, "42")
    private val source = SourceCatalogEvidence("Fixture Game", null, null, CanonicalAppType.GAME)
    private val originalUrl = "https://images.gog.com/fixture-cover.jpg"
    private val portraits = "https://shared.akamai.steamstatic.com/steam/apps/"
    private val images = Images()
    private var current: OwnedCopyRuntimeResult = OwnedCopyRuntimeResult.Available(copy())
    private var reads = 0

    @Test
    fun resizedOriginalAndIndependentExactAppIdPortraitProduceCorroboration() = runTest {
        val producer = producer()
        assertEquals(setOf(42), corroborate(producer, listOf(candidate(header = originalUrl))))
        assertEquals(listOf(OriginalRequest(GameSource.GOG, originalUrl, null)), images.originalRequests)
        assertEquals(listOf(CandidateRequest(42, portraits + "42/library_600x900.jpg")), images.candidateRequests)
        assertTrue("Source identity must be revalidated after image work", reads >= 2)
    }

    @Test
    fun distinctIndependentPortraitAddsNoCorroboration() = runTest {
        images.candidates[42] = fingerprint(distinct = true)
        assertEquals(emptySet<Int>(), corroborate(producer(), listOf(candidate())))
    }

    @Test
    fun missingOriginalNeverUsesTheDisplayedSteamCover() = runTest {
        current = OwnedCopyRuntimeResult.Available(copy().copy(
            originalArtworkUrl = null, capsuleImageUrl = portraits + "42/library_600x900.jpg",
        ))
        assertEquals(emptySet<Int>(), corroborate(producer(), listOf(candidate())))
        assertTrue(images.originalRequests.isEmpty())
        assertTrue(images.candidateRequests.isEmpty())
    }

    @Test
    fun steamFallbackCannotServeAsOriginalGogArtworkEvenWhenItMatches() = runTest {
        current = OwnedCopyRuntimeResult.Available(copy().copy(
            originalArtworkUrl = portraits + "42/library_600x900.jpg",
        ))
        assertEquals(emptySet<Int>(), corroborate(producer(), listOf(candidate())))
        assertTrue(images.originalRequests.isEmpty())
    }

    @Test
    fun nonExactTitlesAndNonGameCandidatesDoNotFetchArtwork() = runTest {
        val producer = producer()
        val candidates = listOf(
            candidate(title = "Fixture Game 2"),
            candidate(id = 43, type = CanonicalAppType.DLC),
            candidate(id = 44, type = CanonicalAppType.DEMO),
            candidate(id = 45, type = CanonicalAppType.SOUNDTRACK),
        )
        assertEquals(emptySet<Int>(), corroborate(producer, candidates))
        assertTrue(images.originalRequests.isEmpty())
        assertTrue(images.candidateRequests.isEmpty())
    }

    @Test
    fun wrongOwnedCopyKeyCannotSupplyOriginalArtwork() = runTest {
        val other = key.copy(stableSourceId = "43")
        current = OwnedCopyRuntimeResult.Available(copy().copy(
            key = other, reference = SourceOwnedCopyReference.Gog(other, "43"),
        ))
        assertEquals(emptySet<Int>(), corroborate(producer(), listOf(candidate())))
        assertTrue(images.originalRequests.isEmpty())
    }

    @Test
    fun changedNativeTitleCannotCorroborateAnOldCatalogAttempt() = runTest {
        current = OwnedCopyRuntimeResult.Available(copy().copy(nativeTitle = "Other Game"))
        assertEquals(emptySet<Int>(), corroborate(producer(), listOf(candidate())))
        assertTrue(images.originalRequests.isEmpty())
    }

    @Test
    fun changedKnownSourceYearDoesNotUseAStaleRuntimeSnapshot() = runTest {
        current = OwnedCopyRuntimeResult.Available(copy().copy(releaseYear = 2020))
        assertEquals(emptySet<Int>(), corroborate(producer(), listOf(candidate()), source.copy(releaseYear = 2024)))
        assertTrue(images.originalRequests.isEmpty())
    }

    @Test
    fun partialArtworkAvailabilityDoesNotResolveIdenticalTitleAmbiguity() = runTest {
        images.candidates[43] = null
        assertEquals(emptySet<Int>(), corroborate(producer(), listOf(candidate(), candidate(id = 43))))
        assertEquals(listOf(42, 43), images.candidateRequests.map(CandidateRequest::appId))
    }

    @Test
    fun moreThanThreeExactCandidatesCannotCauseUnboundedArtworkFanOut() = runTest {
        assertEquals(emptySet<Int>(), corroborate(producer(), (42..45).map { candidate(id = it) }))
        assertTrue(images.originalRequests.isEmpty())
        assertTrue(images.candidateRequests.isEmpty())
    }

    @Test
    fun owningCancellationPropagatesInsteadOfPublishingArtworkEvidence() = runTest {
        val producer = producer()
        images.originalAction = { throw CancellationException("synthetic cancellation") }
        val failure = runCatching { corroborate(producer, listOf(candidate())) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(images.candidateRequests.isEmpty())
    }

    @Test
    fun wholeOptionalPhaseTimesOutAndCleansUpWithoutPartialEvidence() = runTest {
        val producer = producer()
        var cleaned = false
        images.originalAction = {
            try { awaitCancellation() } finally { cleaned = true }
        }
        assertEquals(emptySet<Int>(), corroborate(producer, listOf(candidate())))
        assertEquals(20_000L, testScheduler.currentTime)
        assertTrue(cleaned)
        assertTrue(images.candidateRequests.isEmpty())
    }

    @Test
    fun retiredSourceAfterCandidateFetchingDiscardsTheEvidence() = runTest {
        val producer = producer()
        images.candidateAction = {
            current = OwnedCopyRuntimeResult.Hidden
            fingerprint()
        }
        assertEquals(emptySet<Int>(), corroborate(producer, listOf(candidate())))
        assertTrue(reads >= 2)
    }

    @Test
    fun changedOriginalUrlAfterCandidateFetchingDiscardsTheEvidence() = runTest {
        val producer = producer()
        images.candidateAction = {
            current = OwnedCopyRuntimeResult.Available(copy().copy(
                originalArtworkUrl = "https://images.gog.com/changed-cover.jpg",
            ))
            fingerprint()
        }
        assertEquals(emptySet<Int>(), corroborate(producer, listOf(candidate())))
        assertTrue(reads >= 2)
    }

    @Test
    fun unavailableOriginalImageAddsNoEvidenceAndDoesNotFetchCandidates() = runTest {
        images.originalAction = { null }
        assertEquals(emptySet<Int>(), corroborate(producer(), listOf(candidate())))
        assertEquals(1, images.originalRequests.size)
        assertTrue(images.candidateRequests.isEmpty())
    }

    @Test
    fun originalTransportExceptionLeavesTheOptionalPhaseUnavailable() = runTest {
        val producer = producer()
        images.originalAction = { throw IOException("synthetic image transport failure") }
        val result = runCatching { corroborate(producer, listOf(candidate())) }.getOrNull()
        assertEquals("Optional image failure must not fail catalog resolution", emptySet<Int>(), result)
        assertTrue(images.candidateRequests.isEmpty())
    }

    @Test
    fun candidateTransportExceptionDiscardsPartialOptionalEvidence() = runTest {
        val producer = producer()
        images.candidateAction = { throw IOException("synthetic image transport failure") }
        val result = runCatching { corroborate(producer, listOf(candidate())) }.getOrNull()
        assertEquals("Optional image failure must not fail catalog resolution", emptySet<Int>(), result)
    }

    private fun producer(): Any {
        val type = runCatching {
            Class.forName("app.gamenative.library.canonical.catalog.SteamCatalogArtworkCorroborator")
        }.getOrNull()
        assertNotNull("Independent original/candidate artwork producer is required", type)
        val lookup: suspend (OwnedCopyKey) -> OwnedCopyRuntimeResult = { requested ->
            assertEquals(key, requested)
            reads++
            current
        }
        return requireNotNull(type).getConstructor(ArtworkFingerprintSource::class.java, Function2::class.java)
            .newInstance(images, lookup)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun corroborate(
        producer: Any, candidates: List<SteamCatalogCandidate>, evidence: SourceCatalogEvidence = source,
    ): Set<Int> = suspendCoroutineUninterceptedOrReturn { continuation ->
        val method = producer.javaClass.methods.single { it.name == "corroborate" && it.parameterCount == 4 }
        try {
            method.invoke(producer, key, evidence, candidates, continuation)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }

    private fun candidate(
        id: Int = 42, title: String = source.title, type: CanonicalAppType = CanonicalAppType.GAME,
        header: String? = null,
    ) = SteamCatalogCandidate(id, title, null, null, type, header)

    private fun copy() = OwnedCopyRuntime(
        key = key, reference = SourceOwnedCopyReference.Gog(key, key.stableSourceId),
        libraryItem = LibraryItem(appId = "GOG_42", name = source.title, gameSource = GameSource.GOG),
        nativeTitle = source.title, aliases = emptySet(), developerKey = "", releaseYear = null,
        appType = CanonicalAppType.GAME, genreKeys = emptySet(), tagIds = emptySet(), featureKeys = emptySet(),
        iconUrl = "", capsuleImageUrl = "", headerImageUrl = "", heroImageUrl = "", gridHeroImageScale = 1f,
        installPath = null, installedSizeBytes = null, branchOrVersion = null,
        isInstalled = false, isDownloading = false, hasPartialDownload = false, updateAvailable = false,
        isShared = false, lastPlayedEpochMs = null, playtimeMinutes = null, capabilities = emptySet(),
        originalArtworkUrl = originalUrl,
    )

    private fun fingerprint(distinct: Boolean = false) = requireNotNull(
        ArtworkFingerprint.fromArgb(192, 288, ArtworkFixtures.cover(192, 288, distinct)),
    )

    private data class OriginalRequest(val source: GameSource, val url: String, val nativeAppId: Int?)
    private data class CandidateRequest(val appId: Int, val url: String)

    private inner class Images : ArtworkFingerprintSource {
        val originalRequests = mutableListOf<OriginalRequest>()
        val candidateRequests = mutableListOf<CandidateRequest>()
        val candidates = mutableMapOf<Int, ArtworkFingerprint?>(42 to fingerprint())
        var originalAction: suspend () -> ArtworkFingerprint? = {
            ArtworkFingerprint.fromArgb(384, 576, ArtworkFixtures.cover(384, 576))
        }
        var candidateAction: suspend (Int) -> ArtworkFingerprint? = { candidates[it] }

        override suspend fun original(source: GameSource, raw: String, steamAppId: Int?): ArtworkFingerprint? {
            originalRequests += OriginalRequest(source, raw, steamAppId)
            return originalAction()
        }

        override suspend fun candidate(steamAppId: Int, raw: String): ArtworkFingerprint? {
            candidateRequests += CandidateRequest(steamAppId, raw)
            return candidateAction(steamAppId)
        }
    }
}
