package app.gamenative.library.canonical.catalog

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GOGGame
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameEntity
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.CanonicalIdGenerator
import app.gamenative.data.canonical.ClassificationState
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.data.canonical.SteamCatalogResolutionStatus
import app.gamenative.data.canonical.StoreMatchEntity
import app.gamenative.db.PluviaDatabase
import app.gamenative.library.canonical.CURRENT_RESOLVER_VERSION
import app.gamenative.library.canonical.RoomCanonicalMutationRepository
import app.gamenative.library.canonical.artwork.ArtworkFingerprint
import app.gamenative.library.canonical.artwork.ArtworkFingerprintSource
import app.gamenative.library.canonical.artwork.ArtworkFixtures
import app.gamenative.library.canonical.runtime.OwnedCopyRuntime
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeResult
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import app.gamenative.library.metadata.CanonicalGameMetadata
import app.gamenative.library.metadata.EpicCmsCatalogSource
import app.gamenative.library.metadata.GamePlatform
import app.gamenative.library.metadata.MetadataClock
import app.gamenative.library.metadata.MetadataLocale
import app.gamenative.library.metadata.MetadataLocaleProvider
import app.gamenative.library.metadata.PcGamingWikiCurrentAvailabilityResult
import app.gamenative.library.metadata.PcGamingWikiCurrentAvailabilitySource
import app.gamenative.library.metadata.SteamCatalogRecord
import app.gamenative.library.metadata.SteamCatalogRecordSource
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.junit.rules.TimeoutRule

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = ResolutionWorkerTestApplication::class)
class SteamCatalogArtworkResolutionRepositoryTest {
    @get:Rule val timeout = TimeoutRule.seconds(30)
    private lateinit var db: PluviaDatabase
    private lateinit var writer: RoomCanonicalMutationRepository
    private val key = OwnedCopyKey(AccountScope("2".repeat(64)), GameSource.GOG, "42")
    private val canonicalId = UUID(0, 1).toString()
    private val title = "Fixture Game"
    private val originalUrl = "https://images.gog.com/fixture-cover.jpg"
    private val images = Images()
    private val recordRequests = mutableListOf<Int>()
    private var hits = listOf(SteamStoreSearchHit(42, title, null))
    private var complete = true
    private var missingRecord: Int? = null
    private var developer = ""
    private var recordsDeveloper: String? = null
    private var lookupCount = 0
    private val events = mutableListOf<SteamResolutionDiagnosticEvent>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PluviaDatabase::class.java)
            .allowMainThreadQueries().build()
        writer = RoomCanonicalMutationRepository(db, CanonicalIdGenerator { CanonicalGameId.random() })
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun completeIndependentArtworkAcceptsExactCatalogWithoutCreatingSteamOwnership() = runRepositoryTest {
        seed()
        val original = requireNotNull(db.gogGameDao().getById("42"))
        val repository = repository()
        assertEquals(1, repository.scanAutomatically().autoAccepted)
        assertEquals(42, db.canonicalGameDao().get(canonicalId)?.steamAppId)
        val matches = db.storeMatchDao().getAll()
        assertEquals(1, matches.size)
        assertEquals(key, matches.single().ownedCopyKeyOrNull())
        assertEquals(title, matches.single().evidenceDisplayName)
        assertEquals(original, db.gogGameDao().getById("42"))
        assertFalse(matches.any { it.source == GameSource.STEAM })
        assertEquals(SteamCatalogResolutionStatus.AUTO_ACCEPTED, db.steamCatalogResolutionDao().getAttempt(canonicalId)?.status)
        assertEquals(listOf(42), images.candidates)
        assertTrue(lookupCount >= 2)
    }

    @Test
    fun unavailableArtworkRetainsReviewWithoutFailingTheCatalogAttempt() = runRepositoryTest {
        seed()
        images.originalAction = { throw IOException("synthetic unavailable image") }
        val progress = repository().scanAutomatically()
        assertEquals(1, progress.needsReview)
        assertEquals(0, progress.failed)
        assertNull(db.canonicalGameDao().get(canonicalId)?.steamAppId)
        assertEquals(SteamCatalogResolutionStatus.REVIEW_REQUIRED, db.steamCatalogResolutionDao().getAttempt(canonicalId)?.status)
    }

    @Test
    fun incompleteSearchNeverUsesMatchingArtworkToAutoAccept() = runRepositoryTest {
        seed()
        complete = false
        val progress = repository().scanAutomatically()
        assertEquals(1, progress.needsReview)
        assertEquals(0, progress.autoAccepted)
        assertTrue(images.originals.isEmpty())
        assertEquals(0, lookupCount)
        assertEquals(SteamCatalogResolutionStatus.FAILED, db.steamCatalogResolutionDao().getAttempt(canonicalId)?.status)
    }

    @Test
    fun missingDetailsNeverUsesPartialArtworkToAutoAccept() = runRepositoryTest {
        seed()
        hits += SteamStoreSearchHit(43, title, null)
        missingRecord = 43
        val progress = repository().scanAutomatically()
        assertEquals(1, progress.needsReview)
        assertEquals(0, progress.autoAccepted)
        assertTrue(images.originals.isEmpty())
        assertEquals(SteamCatalogResolutionStatus.FAILED, db.steamCatalogResolutionDao().getAttempt(canonicalId)?.status)
    }

    @Test
    fun stickyUserDecisionSkipsAllAutomaticCatalogAndArtworkWork() = runRepositoryTest {
        seed()
        db.storeMatchDao().upsert(currentMatch().copy(decisionSource = MatchDecisionSource.USER))
        assertEquals(0, repository().scanAutomatically().total)
        assertTrue(recordRequests.isEmpty())
        assertTrue(images.originals.isEmpty())
    }

    @Test
    fun userDecisionDuringImageFetchingCannotBeOverwritten() = runRepositoryTest {
        seed()
        images.candidateAction = {
            db.storeMatchDao().upsert(currentMatch().copy(decisionSource = MatchDecisionSource.USER, matchedAt = 200))
            fingerprint()
        }
        val progress = repository().scanAutomatically()
        assertEquals(1, images.candidates.size)
        assertEquals(0, progress.autoAccepted)
        assertEquals(MatchDecisionSource.USER, currentMatch().decisionSource)
        assertNull(db.canonicalGameDao().get(canonicalId)?.steamAppId)
        assertNull(db.steamCatalogResolutionDao().getAttempt(canonicalId))
    }

    @Test
    fun retiringSourceDuringImageFetchingPreventsPublication() = runRepositoryTest {
        seed()
        images.candidateAction = {
            db.storeMatchDao().upsert(currentMatch().copy(isPresent = false))
            fingerprint()
        }
        val progress = repository().scanAutomatically()
        assertEquals(1, images.candidates.size)
        assertEquals(0, progress.autoAccepted)
        assertEquals(0, progress.needsReview)
        assertNull(db.steamCatalogResolutionDao().getAttempt(canonicalId))
    }

    @Test
    fun changedOriginalUrlDuringFetchingInvalidatesEvenBaselineCorroboration() = runRepositoryTest {
        developer = "Fixture Studio"
        recordsDeveloper = developer
        seed()
        images.candidateAction = {
            changeOriginalUrl("https://images.gog.com/changed-cover.jpg")
            fingerprint()
        }
        val progress = repository().scanAutomatically()
        assertEquals(1, images.candidates.size)
        assertEquals(0, progress.autoAccepted)
        assertNull(db.canonicalGameDao().get(canonicalId)?.steamAppId)
        assertNull(db.steamCatalogResolutionDao().getAttempt(canonicalId))
    }

    @Test
    fun newlyAvailableOriginalArtworkReconsidersCompletedReviewAfterRepositoryRecreation() = runRepositoryTest {
        seed(url = "")
        assertEquals(1, repository().scanAutomatically().needsReview)
        val before = requireNotNull(db.steamCatalogResolutionDao().getAttempt(canonicalId)).evidenceHash
        changeOriginalUrl(originalUrl)
        assertEquals(1, repository().scanAutomatically().autoAccepted)
        val after = requireNotNull(db.steamCatalogResolutionDao().getAttempt(canonicalId)).evidenceHash
        assertNotEquals(before, after)
        assertFalse(after.contains(originalUrl))
        assertFalse(after.contains(key.accountScope.value))
    }

    @Test
    fun changedPublicArtworkUrlReconsidersCompletedUnavailableReview() = runRepositoryTest {
        seed()
        images.originalAction = { null }
        assertEquals(1, repository().scanAutomatically().needsReview)
        changeOriginalUrl("https://images.gog.com/new-cover.jpg")
        images.originalAction = { fingerprint() }
        val progress = repository().scanAutomatically()
        assertEquals("progress=$progress; virtualMs=${testScheduler.currentTime}; lookups=$lookupCount; " +
            "originals=${images.originals.size}; candidates=${images.candidates.size}; events=$events", 1, progress.autoAccepted)
    }

    @Test
    fun unchangedCompletedReviewDoesNotRefetchOnRepositoryRecreation() = runRepositoryTest {
        seed()
        images.originalAction = { null }
        assertEquals(1, repository().scanAutomatically().needsReview)
        val requests = recordRequests.size
        assertEquals(0, repository().scanAutomatically().total)
        assertEquals(requests, recordRequests.size)
    }

    @Test
    fun versionSixReviewGetsOneAutomaticArtworkAwareReconsideration() = runRepositoryTest {
        seed()
        db.storeMatchDao().upsert(currentMatch().copy(
            matchMethod = MatchMethod.STEAM_CATALOG, confidence = MatchConfidence.REVIEW_REQUIRED,
            resolverVersion = 6, candidateSteamAppId = 42,
        ))
        assertEquals(1, repository().scanAutomatically().autoAccepted)
        assertTrue(CURRENT_RESOLVER_VERSION > 6)
        assertEquals(0, repository().scanAutomatically().total)
    }

    @Test
    fun cancellationDuringArtworkLeavesPendingLeaseAndStopsScanning() = runRepositoryTest {
        seed()
        images.originalAction = { throw CancellationException("synthetic owning cancellation") }
        val repository = repository()
        val failure = runCatching { repository.scanAutomatically() }.exceptionOrNull()
        assertTrue("Owning cancellation must propagate", failure is CancellationException)
        assertFalse(repository.isScanning.value)
        assertEquals(SteamCatalogResolutionStatus.PENDING, db.steamCatalogResolutionDao().getAttempt(canonicalId)?.status)
        assertNull(db.canonicalGameDao().get(canonicalId)?.steamAppId)
    }

    @Test
    fun sharedArtworkWithoutSourceYearCannotResolveTwoExactCatalogGames() = runRepositoryTest {
        seed()
        hits += SteamStoreSearchHit(43, title, null)
        val progress = repository().scanAutomatically()
        assertEquals(1, progress.needsReview)
        assertEquals(0, progress.autoAccepted)
        assertEquals(listOf(42, 43), images.candidates)
        assertNull(db.canonicalGameDao().get(canonicalId)?.steamAppId)
    }

    @Test
    fun appDetailsBudgetValidatesAtMostFiveOfTenSearchHits() = runRepositoryTest {
        seed()
        hits = (42..51).map { SteamStoreSearchHit(it, title, null) }
        val repository = repository()
        repository.scanAutomatically()
        assertEquals(5, recordRequests.size)
        assertEquals(5, repository.candidatesFor(key).size)
        assertEquals(0, lookupCount)
        assertNull(db.canonicalGameDao().get(canonicalId)?.steamAppId)
        assertEquals(SteamCatalogResolutionStatus.FAILED, db.steamCatalogResolutionDao().getAttempt(canonicalId)?.status)
    }

    @Test
    fun truncatedHitAcquisitionCannotAutoAcceptAnOtherwiseCorroboratedCandidate() = runRepositoryTest {
        developer = "Fixture Studio"
        recordsDeveloper = developer
        seed()
        hits = (42..56).map { SteamStoreSearchHit(it, if (it == 42) title else "Other Game $it", null) }
        val progress = repository().scanAutomatically()
        assertEquals(5, recordRequests.size)
        assertEquals(0, progress.autoAccepted)
        assertEquals(1, progress.needsReview)
        assertEquals(0, lookupCount)
        assertNull(db.canonicalGameDao().get(canonicalId)?.steamAppId)
    }

    @Test
    fun lateExactSearchHitRemainsReviewableWithinTheValidationBudget() = runRepositoryTest {
        seed()
        hits = (42..56).map { SteamStoreSearchHit(it, if (it == 56) title else "Other Game $it", null) }
        val repository = repository()
        val progress = repository.scanAutomatically()
        assertEquals(5, recordRequests.size)
        assertTrue("Title ordering must preserve a credible late hit", 56 in recordRequests)
        assertEquals(56, repository.candidatesFor(key).first().steamAppId)
        assertEquals(1, progress.needsReview)
        assertEquals(0, progress.autoAccepted)
        assertEquals(0, lookupCount)
    }

    private fun runRepositoryTest(body: suspend TestScope.() -> Unit) = runTest {
        // Real Room workers must finish before a real deadline, not a virtual timer's automatic jump.
        withContext(Dispatchers.Default) { body(this@runTest) }
    }

    private suspend fun seed(url: String = originalUrl) {
        db.canonicalGameDao().insert(CanonicalGameEntity(
            canonicalId = canonicalId, steamAppId = null, displayName = title, matchTitleKey = "fixture game",
            primaryMetadataSource = GameSource.GOG, appType = CanonicalAppType.GAME, releaseYear = null,
            developerKey = developer, classificationState = ClassificationState.UNCLASSIFIED,
            steamReviewCount = null, createdAt = 1, updatedAt = 1,
        ))
        db.storeMatchDao().upsert(StoreMatchEntity(
            accountScope = key.accountScope.value, source = key.source, stableSourceId = key.stableSourceId,
            canonicalId = canonicalId, candidateSteamAppId = null, matchMethod = MatchMethod.UNMATCHED,
            confidence = MatchConfidence.UNMATCHED, decisionSource = MatchDecisionSource.AUTOMATIC,
            resolverVersion = CURRENT_RESOLVER_VERSION, matchedAt = 100, isPresent = true,
            evidenceDisplayName = title, evidenceTitleKey = "fixture game", evidenceDeveloperKey = developer,
            evidenceReleaseYear = null, evidenceAppType = CanonicalAppType.GAME,
        ))
        db.gogGameDao().insert(GOGGame(
            id = "42", title = title, developer = developer, verticalCoverUrl = url,
            isInstalled = true, installPath = "fixture-install", installSize = 1234,
        ))
    }

    private suspend fun currentMatch() = db.storeMatchDao().getAll().single()

    private suspend fun changeOriginalUrl(url: String) {
        db.gogGameDao().insert(requireNotNull(db.gogGameDao().getById("42")).copy(verticalCoverUrl = url))
    }

    private fun repository(): SteamCatalogResolutionRepository {
        val search = object : SteamCatalogSearchSource {
            override suspend fun search(query: String, locale: MetadataLocale) = hits
            override suspend fun searchResult(query: String, locale: MetadataLocale) = SteamCatalogSearchResult(hits, complete)
        }
        val records = SteamCatalogRecordSource { appId, _ ->
            assertFalse("Catalog providers must not run in Room transactions", db.inTransaction())
            recordRequests += appId
            if (appId == missingRecord) null else record(appId, hits.first { it.steamAppId == appId }.title)
        }
        val producer = SteamCatalogArtworkCorroborator(images) { requested ->
            assertFalse("Runtime/image lookup must stay outside Room transactions", db.inTransaction())
            assertEquals(key, requested)
            lookupCount++
            if (!currentMatch().isPresent) OwnedCopyRuntimeResult.Hidden else {
                val game = requireNotNull(db.gogGameDao().getById("42"))
                OwnedCopyRuntimeResult.Available(runtime(game))
            }
        }
        val arguments = arrayOf<Any>(
            db.storeMatchDao(), search, records, SteamCatalogCandidatePolicy(), writer,
            PcGamingWikiCurrentAvailabilitySource { PcGamingWikiCurrentAvailabilityResult.NotConfirmed },
            EpicCmsCatalogSource { throw AssertionError("GOG must not fetch Epic fallback") }, writer,
            MetadataLocaleProvider { MetadataLocale("en-US", "US") }, SteamCatalogResolutionDiagnosticSink { events += it },
            SteamAcceptedIdentityEnrichmentSink { _, _, _ -> SteamAcceptedIdentityEnrichmentResult.Enriched },
            MetadataClock { 1_000L }, db, SteamCatalogResumeScheduler {},
        )
        val constructors = SteamCatalogResolutionRepository::class.java.constructors
        val integrated = constructors.singleOrNull {
            it.parameterCount == 15 && it.parameterTypes.last() == SteamCatalogArtworkCorroborator::class.java
        }
        val constructor = integrated ?: constructors.single { it.parameterCount == 14 }
        return try {
            constructor.newInstance(*(if (integrated != null) arguments + producer else arguments)) as SteamCatalogResolutionRepository
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }

    private fun runtime(game: GOGGame) = OwnedCopyRuntime(
        key = key, reference = SourceOwnedCopyReference.Gog(key, "42"),
        libraryItem = LibraryItem(appId = "GOG_42", name = game.title, gameSource = GameSource.GOG),
        nativeTitle = game.title, aliases = emptySet(), developerKey = game.developer, releaseYear = null,
        appType = CanonicalAppType.GAME, genreKeys = emptySet(), tagIds = emptySet(), featureKeys = emptySet(),
        iconUrl = "", capsuleImageUrl = "", headerImageUrl = "", heroImageUrl = "", gridHeroImageScale = 1f,
        installPath = game.installPath, installedSizeBytes = game.installSize, branchOrVersion = null,
        isInstalled = game.isInstalled, isDownloading = false, hasPartialDownload = false, updateAvailable = false,
        isShared = false, lastPlayedEpochMs = null, playtimeMinutes = null, capabilities = emptySet(),
        originalArtworkUrl = game.verticalCoverUrl.takeIf(String::isNotBlank),
    )

    private fun record(appId: Int, name: String) = SteamCatalogRecord(
        steamAppId = appId, appType = CanonicalAppType.GAME, releaseYear = null,
        metadata = CanonicalGameMetadata(
            title = name, shortDescription = null, about = null, headerImageUrl = null,
            screenshots = emptyList(), movies = emptyList(), developers = listOfNotNull(recordsDeveloper),
            publishers = emptyList(), releaseDate = null, platforms = setOf(GamePlatform.WINDOWS),
            languages = emptyList(), requirements = null, features = emptyList(), achievementCount = null,
            dlcCount = null, fetchedAtEpochMs = 1_000,
        ),
    )

    private fun fingerprint() = requireNotNull(ArtworkFingerprint.fromArgb(192, 288, ArtworkFixtures.cover(192, 288)))

    private inner class Images : ArtworkFingerprintSource {
        val originals = mutableListOf<String>()
        val candidates = mutableListOf<Int>()
        var originalAction: suspend () -> ArtworkFingerprint? = { fingerprint() }
        var candidateAction: suspend () -> ArtworkFingerprint? = { fingerprint() }

        override suspend fun original(source: GameSource, raw: String, steamAppId: Int?): ArtworkFingerprint? {
            assertFalse("Image work must stay outside Room transactions", db.inTransaction())
            assertEquals(GameSource.GOG, source)
            originals += raw
            return originalAction()
        }

        override suspend fun candidate(steamAppId: Int, raw: String): ArtworkFingerprint? {
            assertFalse("Image work must stay outside Room transactions", db.inTransaction())
            assertEquals("https://shared.akamai.steamstatic.com/steam/apps/$steamAppId/library_600x900.jpg", raw)
            candidates += steamAppId
            return candidateAction()
        }
    }
}
