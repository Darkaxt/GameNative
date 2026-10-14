package app.gamenative.library.canonical.catalog

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GOGGame
import app.gamenative.data.GameSource
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
    private val aliasTitle = "Playdead's Fixture Game"
    private val originalUrl = "https://images.gog.com/fixture-cover.jpg"
    private val images = Images()
    private val recordRequests = mutableListOf<Int>()
    private var hits = listOf(SteamStoreSearchHit(42, title, null))
    private var complete = true
    private var missingRecord: Int? = null
    private var developer = ""
    private var recordsDeveloper: String? = null
    private var recordsAppType = CanonicalAppType.GAME
    private var recordsYear: Int? = null
    private var recordAction: suspend () -> Unit = {}
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
    fun exactCatalogDefaultsAcceptWithoutArtworkOrCreatingSteamOwnership() = runRepositoryTest {
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
        assertNoArtworkWork()
    }

    @Test
    fun normalizedSameFullEditionNameAcceptsWithoutPartyYearOrArtwork() = runRepositoryTest {
        hits = listOf(SteamStoreSearchHit(42, "FIXTURE GAME - Definitive Edition", null))
        seed(sourceTitle = "${title}™ – Definitive Edition")
        val progress = repository().scanAutomatically()
        assertEquals(1, progress.autoAccepted)
        assertEquals(0, progress.needsReview)
        assertEquals(42, db.canonicalGameDao().get(canonicalId)?.steamAppId)
        assertNoArtworkWork()
    }

    @Test
    fun unavailableArtworkDoesNotBlockSameFullNameAutomaticDefault() = runRepositoryTest {
        seed()
        images.originalAction = { throw IOException("synthetic unavailable image") }
        val progress = repository().scanAutomatically()
        assertEquals(1, progress.autoAccepted)
        assertEquals(0, progress.needsReview)
        assertEquals(0, progress.failed)
        assertEquals(42, db.canonicalGameDao().get(canonicalId)?.steamAppId)
        assertEquals(SteamCatalogResolutionStatus.AUTO_ACCEPTED, db.steamCatalogResolutionDao().getAttempt(canonicalId)?.status)
        assertNoArtworkWork()
    }

    @Test
    fun incompleteSearchStillAcceptsValidatedSameFullNameDefault() = runRepositoryTest {
        seed()
        complete = false
        val progress = repository().scanAutomatically()
        assertEquals(1, progress.autoAccepted)
        assertEquals(0, progress.needsReview)
        assertEquals(0, progress.failed)
        assertEquals(42, db.canonicalGameDao().get(canonicalId)?.steamAppId)
        assertEquals(SteamCatalogResolutionStatus.AUTO_ACCEPTED, db.steamCatalogResolutionDao().getAttempt(canonicalId)?.status)
        assertNoArtworkWork()
    }

    @Test
    fun missingOtherDetailsStillAcceptsValidatedSameFullNameDefault() = runRepositoryTest {
        seed()
        hits += SteamStoreSearchHit(43, title, null)
        missingRecord = 43
        val progress = repository().scanAutomatically()
        assertEquals(1, progress.autoAccepted)
        assertEquals(0, progress.needsReview)
        assertEquals(0, progress.failed)
        assertEquals(42, db.canonicalGameDao().get(canonicalId)?.steamAppId)
        assertEquals(SteamCatalogResolutionStatus.AUTO_ACCEPTED, db.steamCatalogResolutionDao().getAttempt(canonicalId)?.status)
        assertNoArtworkWork()
    }

    @Test
    fun incompleteNonexactSearchNeverUsesArtworkToAutoAccept() = runRepositoryTest {
        seed(sourceTitle = aliasTitle)
        complete = false
        val progress = repository().scanAutomatically()
        assertEquals(1, progress.needsReview)
        assertEquals(0, progress.autoAccepted)
        assertNull(db.canonicalGameDao().get(canonicalId)?.steamAppId)
        assertEquals(SteamCatalogResolutionStatus.FAILED, db.steamCatalogResolutionDao().getAttempt(canonicalId)?.status)
        assertNoArtworkWork()
    }

    @Test
    fun missingNonexactDetailsNeverUsesPartialArtworkToAutoAccept() = runRepositoryTest {
        seed(sourceTitle = aliasTitle)
        hits += SteamStoreSearchHit(43, title, null)
        missingRecord = 43
        val progress = repository().scanAutomatically()
        assertEquals(1, progress.needsReview)
        assertEquals(0, progress.autoAccepted)
        assertNull(db.canonicalGameDao().get(canonicalId)?.steamAppId)
        assertEquals(SteamCatalogResolutionStatus.FAILED, db.steamCatalogResolutionDao().getAttempt(canonicalId)?.status)
        assertNoArtworkWork()
    }

    @Test
    fun nonexactAliasWithoutCorroborationRemainsReviewAndDoesNotFetchArtwork() = runRepositoryTest {
        seed(sourceTitle = aliasTitle)
        val progress = repository().scanAutomatically()
        assertEquals(1, progress.needsReview)
        assertEquals(0, progress.autoAccepted)
        assertEquals(0, progress.failed)
        assertNull(db.canonicalGameDao().get(canonicalId)?.steamAppId)
        assertEquals(42, currentMatch().candidateSteamAppId)
        assertEquals(SteamCatalogResolutionStatus.REVIEW_REQUIRED, db.steamCatalogResolutionDao().getAttempt(canonicalId)?.status)
        assertNoArtworkWork()
    }

    @Test
    fun corroboratedNonexactAliasStillUsesLegacyPolicyWithoutArtwork() = runRepositoryTest {
        developer = "Fixture Studio"
        recordsDeveloper = developer
        recordsYear = 2020
        seed(sourceTitle = aliasTitle, sourceYear = 2020)
        val progress = repository().scanAutomatically()
        assertEquals(1, progress.autoAccepted)
        assertEquals(42, db.canonicalGameDao().get(canonicalId)?.steamAppId)
        assertNoArtworkWork()
    }

    @Test
    fun editionMismatchCannotUseOriginalArtworkToBecomeSameNameDefault() = runRepositoryTest {
        seed(sourceTitle = "$title Definitive Edition")
        val progress = repository().scanAutomatically()
        assertEquals(1, progress.needsReview)
        assertEquals(0, progress.autoAccepted)
        assertNull(db.canonicalGameDao().get(canonicalId)?.steamAppId)
        assertEquals(SteamCatalogResolutionStatus.REVIEW_REQUIRED, db.steamCatalogResolutionDao().getAttempt(canonicalId)?.status)
        assertNoArtworkWork()
    }

    @Test
    fun conflictingPartyOnNonexactAliasStillRequiresReviewDespiteCloseYear() = runRepositoryTest {
        developer = "Fixture Studio"
        recordsDeveloper = "Other Studio"
        recordsYear = 2020
        seed(sourceTitle = aliasTitle, sourceYear = 2020)
        val progress = repository().scanAutomatically()
        assertEquals(1, progress.needsReview)
        assertEquals(0, progress.autoAccepted)
        assertNull(db.canonicalGameDao().get(canonicalId)?.steamAppId)
        assertEquals(SteamCatalogResolutionStatus.REVIEW_REQUIRED, db.steamCatalogResolutionDao().getAttempt(canonicalId)?.status)
        assertNoArtworkWork()
    }

    @Test
    fun unknownSourceTypeCannotUseSameTitleAutomaticGameDefault() = runRepositoryTest {
        seed(sourceAppType = CanonicalAppType.UNKNOWN)
        val progress = repository().scanAutomatically()
        assertEquals(1, progress.needsReview)
        assertEquals(0, progress.autoAccepted)
        assertNull(db.canonicalGameDao().get(canonicalId)?.steamAppId)
        assertEquals(SteamCatalogResolutionStatus.REVIEW_REQUIRED, db.steamCatalogResolutionDao().getAttempt(canonicalId)?.status)
        assertNoArtworkWork()
    }

    @Test
    fun sameTitleDlcCannotUseOriginalArtworkOrBecomeAutomaticGameDefault() = runRepositoryTest {
        recordsAppType = CanonicalAppType.DLC
        seed()
        val progress = repository().scanAutomatically()
        assertEquals(1, progress.unmatched)
        assertEquals(0, progress.autoAccepted)
        assertNull(db.canonicalGameDao().get(canonicalId)?.steamAppId)
        assertEquals(SteamCatalogResolutionStatus.UNMATCHED, db.steamCatalogResolutionDao().getAttempt(canonicalId)?.status)
        assertNoArtworkWork()
    }

    @Test
    fun stickyUserDecisionSkipsAllAutomaticCatalogAndArtworkWork() = runRepositoryTest {
        seed()
        db.storeMatchDao().upsert(currentMatch().copy(decisionSource = MatchDecisionSource.USER))
        assertEquals(0, repository().scanAutomatically().total)
        assertTrue(recordRequests.isEmpty())
        assertNoArtworkWork()
    }

    // Exact defaults bypass image I/O, so exercise publication races at the remaining provider boundary.
    @Test
    fun userDecisionDuringDetailsFetchingCannotBeOverwrittenBySameNameDefault() = runRepositoryTest {
        seed()
        recordAction = {
            db.storeMatchDao().upsert(currentMatch().copy(decisionSource = MatchDecisionSource.USER, matchedAt = 200))
        }
        val progress = repository().scanAutomatically()
        assertEquals(listOf(42), recordRequests)
        assertEquals(0, progress.autoAccepted)
        assertEquals(MatchDecisionSource.USER, currentMatch().decisionSource)
        assertNull(db.canonicalGameDao().get(canonicalId)?.steamAppId)
        assertNull(db.steamCatalogResolutionDao().getAttempt(canonicalId))
        assertNoArtworkWork()
    }

    @Test
    fun retiringSourceDuringDetailsFetchingPreventsSameNamePublication() = runRepositoryTest {
        seed()
        recordAction = { db.storeMatchDao().upsert(currentMatch().copy(isPresent = false)) }
        val progress = repository().scanAutomatically()
        assertEquals(listOf(42), recordRequests)
        assertEquals(0, progress.autoAccepted)
        assertEquals(0, progress.needsReview)
        assertNull(db.canonicalGameDao().get(canonicalId)?.steamAppId)
        assertNull(db.steamCatalogResolutionDao().getAttempt(canonicalId))
        assertNoArtworkWork()
    }

    @Test
    fun changedOriginalUrlDuringDetailsFetchingInvalidatesSameNamePublication() = runRepositoryTest {
        developer = "Fixture Studio"
        recordsDeveloper = developer
        seed()
        recordAction = { changeOriginalUrl("https://images.gog.com/changed-cover.jpg") }
        val progress = repository().scanAutomatically()
        assertEquals(listOf(42), recordRequests)
        assertEquals(0, progress.autoAccepted)
        assertNull(db.canonicalGameDao().get(canonicalId)?.steamAppId)
        assertNull(db.steamCatalogResolutionDao().getAttempt(canonicalId))
        assertNoArtworkWork()
    }

    @Test
    fun newlyAvailableOriginalArtworkReconsidersNonexactReviewWithoutAcceptingIt() = runRepositoryTest {
        seed(url = "", sourceTitle = aliasTitle)
        assertEquals(1, repository().scanAutomatically().needsReview)
        val before = requireNotNull(db.steamCatalogResolutionDao().getAttempt(canonicalId)).evidenceHash
        val requests = recordRequests.size
        changeOriginalUrl(originalUrl)
        assertEquals(1, repository().scanAutomatically().needsReview)
        val after = requireNotNull(db.steamCatalogResolutionDao().getAttempt(canonicalId)).evidenceHash
        assertTrue(recordRequests.size > requests)
        assertNotEquals(before, after)
        assertFalse(after.contains(originalUrl))
        assertFalse(after.contains(key.accountScope.value))
        assertNull(db.canonicalGameDao().get(canonicalId)?.steamAppId)
        assertNoArtworkWork()
    }

    @Test
    fun changedPublicArtworkUrlReconsidersCompletedNonexactReview() = runRepositoryTest {
        seed(sourceTitle = aliasTitle)
        assertEquals(1, repository().scanAutomatically().needsReview)
        val before = requireNotNull(db.steamCatalogResolutionDao().getAttempt(canonicalId)).evidenceHash
        val requests = recordRequests.size
        changeOriginalUrl("https://images.gog.com/new-cover.jpg")
        val progress = repository().scanAutomatically()
        assertEquals(1, progress.needsReview)
        assertEquals(0, progress.autoAccepted)
        assertTrue(recordRequests.size > requests)
        assertNotEquals(before, db.steamCatalogResolutionDao().getAttempt(canonicalId)?.evidenceHash)
        assertNoArtworkWork()
    }

    @Test
    fun unchangedCompletedNonexactReviewDoesNotRefetchOnRepositoryRecreation() = runRepositoryTest {
        seed(sourceTitle = aliasTitle)
        assertEquals(1, repository().scanAutomatically().needsReview)
        val requests = recordRequests.size
        assertEquals(0, repository().scanAutomatically().total)
        assertEquals(requests, recordRequests.size)
        assertNoArtworkWork()
    }

    @Test
    fun versionSixReviewGetsOneSameNameDefaultReconsideration() = runRepositoryTest {
        seed()
        db.storeMatchDao().upsert(currentMatch().copy(
            matchMethod = MatchMethod.STEAM_CATALOG, confidence = MatchConfidence.REVIEW_REQUIRED,
            resolverVersion = 6, candidateSteamAppId = 42,
        ))
        assertEquals(1, repository().scanAutomatically().autoAccepted)
        assertTrue(CURRENT_RESOLVER_VERSION > 6)
        assertEquals(0, repository().scanAutomatically().total)
        assertNoArtworkWork()
    }

    @Test
    fun cancellationDuringDetailsLeavesPendingLeaseAndStopsScanning() = runRepositoryTest {
        seed()
        recordAction = { throw CancellationException("synthetic owning cancellation") }
        val repository = repository()
        val failure = runCatching { repository.scanAutomatically() }.exceptionOrNull()
        assertTrue("Owning cancellation must propagate", failure is CancellationException)
        assertFalse(repository.isScanning.value)
        assertEquals(SteamCatalogResolutionStatus.PENDING, db.steamCatalogResolutionDao().getAttempt(canonicalId)?.status)
        assertNull(db.canonicalGameDao().get(canonicalId)?.steamAppId)
        assertNoArtworkWork()
    }

    @Test
    fun sameNameGamesWithoutSourceYearOrPopularityUseStableAppIdWithoutArtwork() = runRepositoryTest {
        seed()
        hits = listOf(SteamStoreSearchHit(43, title, null), SteamStoreSearchHit(42, title, null))
        val progress = repository().scanAutomatically()
        assertEquals(0, progress.needsReview)
        assertEquals(1, progress.autoAccepted)
        assertEquals(42, db.canonicalGameDao().get(canonicalId)?.steamAppId)
        assertNoArtworkWork()
    }

    @Test
    fun appDetailsBudgetValidatesAtMostFiveOfTenSameNameHitsAndAcceptsDefault() = runRepositoryTest {
        seed()
        hits = (42..51).map { SteamStoreSearchHit(it, title, null) }
        val repository = repository()
        val progress = repository.scanAutomatically()
        assertEquals(5, recordRequests.size)
        assertEquals(5, repository.candidatesFor(key).size)
        assertEquals(1, progress.autoAccepted)
        assertEquals(42, db.canonicalGameDao().get(canonicalId)?.steamAppId)
        assertEquals(SteamCatalogResolutionStatus.AUTO_ACCEPTED, db.steamCatalogResolutionDao().getAttempt(canonicalId)?.status)
        assertNoArtworkWork()
    }

    @Test
    fun truncatedAcquisitionStillAcceptsValidatedSameFullNameDefault() = runRepositoryTest {
        developer = "Fixture Studio"
        recordsDeveloper = developer
        seed()
        hits = (42..56).map { SteamStoreSearchHit(it, if (it == 42) title else "Other Game $it", null) }
        val progress = repository().scanAutomatically()
        assertEquals(5, recordRequests.size)
        assertEquals(1, progress.autoAccepted)
        assertEquals(0, progress.needsReview)
        assertEquals(42, db.canonicalGameDao().get(canonicalId)?.steamAppId)
        assertNoArtworkWork()
    }

    @Test
    fun truncatedNonexactAcquisitionCannotAutoAcceptOtherwiseCorroboratedAlias() = runRepositoryTest {
        developer = "Fixture Studio"
        recordsDeveloper = developer
        recordsYear = 2020
        seed(sourceTitle = aliasTitle, sourceYear = 2020)
        hits = (42..56).map { SteamStoreSearchHit(it, if (it == 42) title else "Other Game $it", null) }
        val progress = repository().scanAutomatically()
        assertEquals(5, recordRequests.size)
        assertEquals(0, progress.autoAccepted)
        assertEquals(1, progress.needsReview)
        assertNull(db.canonicalGameDao().get(canonicalId)?.steamAppId)
        assertEquals(SteamCatalogResolutionStatus.FAILED, db.steamCatalogResolutionDao().getAttempt(canonicalId)?.status)
        assertNoArtworkWork()
    }

    @Test
    fun lateExactSearchHitRemainsWithinValidationBudgetAndAcceptsDefault() = runRepositoryTest {
        seed()
        hits = (42..56).map { SteamStoreSearchHit(it, if (it == 56) title else "Other Game $it", null) }
        val repository = repository()
        val progress = repository.scanAutomatically()
        assertEquals(5, recordRequests.size)
        assertTrue("Title ordering must preserve a credible late hit", 56 in recordRequests)
        assertEquals(56, repository.candidatesFor(key).first().steamAppId)
        assertEquals(0, progress.needsReview)
        assertEquals(1, progress.autoAccepted)
        assertEquals(56, db.canonicalGameDao().get(canonicalId)?.steamAppId)
        assertNoArtworkWork()
    }

    private fun assertNoArtworkWork() {
        assertEquals(0, lookupCount)
        assertTrue(images.originals.isEmpty())
        assertTrue(images.candidates.isEmpty())
    }

    private fun runRepositoryTest(body: suspend TestScope.() -> Unit) = runTest {
        // Real Room workers must finish before a real deadline, not a virtual timer's automatic jump.
        withContext(Dispatchers.Default) { body(this@runTest) }
    }

    private suspend fun seed(
        url: String = originalUrl,
        sourceTitle: String = title,
        sourceYear: Int? = null,
        sourceAppType: CanonicalAppType = CanonicalAppType.GAME,
    ) {
        val titleKey = SteamCatalogNormalization.titleKey(sourceTitle)
        db.canonicalGameDao().insert(CanonicalGameEntity(
            canonicalId = canonicalId, steamAppId = null, displayName = sourceTitle, matchTitleKey = titleKey,
            primaryMetadataSource = GameSource.GOG, appType = sourceAppType, releaseYear = sourceYear,
            developerKey = developer, classificationState = ClassificationState.UNCLASSIFIED,
            steamReviewCount = null, createdAt = 1, updatedAt = 1,
        ))
        db.storeMatchDao().upsert(StoreMatchEntity(
            accountScope = key.accountScope.value, source = key.source, stableSourceId = key.stableSourceId,
            canonicalId = canonicalId, candidateSteamAppId = null, matchMethod = MatchMethod.UNMATCHED,
            confidence = MatchConfidence.UNMATCHED, decisionSource = MatchDecisionSource.AUTOMATIC,
            resolverVersion = CURRENT_RESOLVER_VERSION, matchedAt = 100, isPresent = true,
            evidenceDisplayName = sourceTitle, evidenceTitleKey = titleKey, evidenceDeveloperKey = developer,
            evidenceReleaseYear = sourceYear, evidenceAppType = sourceAppType,
        ))
        db.gogGameDao().insert(GOGGame(
            id = "42", title = sourceTitle, developer = developer, verticalCoverUrl = url,
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
            recordAction()
            if (appId == missingRecord) null else record(appId, hits.first { it.steamAppId == appId }.title)
        }
        val producer = SteamCatalogArtworkCorroborator(images) { requested ->
            assertFalse("Runtime/image lookup must stay outside Room transactions", db.inTransaction())
            assertEquals(key, requested)
            lookupCount++
            throw AssertionError("Unexpected catalog artwork runtime lookup")
        }
        return SteamCatalogResolutionRepository(
            storeMatchDao = db.storeMatchDao(), searchSource = search, recordSource = records,
            candidatePolicy = SteamCatalogCandidatePolicy(), decisionWriter = writer,
            pcGamingWikiSource = PcGamingWikiCurrentAvailabilitySource { PcGamingWikiCurrentAvailabilityResult.NotConfirmed },
            epicCatalogSource = EpicCmsCatalogSource { throw AssertionError("GOG must not fetch Epic fallback") },
            epicFallbackWriter = writer,
            localeProvider = MetadataLocaleProvider { MetadataLocale("en-US", "US") },
            diagnostics = SteamCatalogResolutionDiagnosticSink { events += it },
            acceptedIdentityEnrichment = SteamAcceptedIdentityEnrichmentSink { _, _, _ -> SteamAcceptedIdentityEnrichmentResult.Enriched },
            clock = MetadataClock { 1_000L }, db = db, resumeScheduler = SteamCatalogResumeScheduler {},
            artworkCorroborator = producer,
        )
    }

    private fun record(appId: Int, name: String) = SteamCatalogRecord(
        steamAppId = appId, appType = recordsAppType, releaseYear = recordsYear,
        metadata = CanonicalGameMetadata(
            title = name, shortDescription = null, about = null, headerImageUrl = null,
            screenshots = emptyList(), movies = emptyList(), developers = listOfNotNull(recordsDeveloper),
            publishers = emptyList(), releaseDate = null, platforms = setOf(GamePlatform.WINDOWS),
            languages = emptyList(), requirements = null, features = emptyList(), achievementCount = null,
            dlcCount = null, fetchedAtEpochMs = 1_000,
        ),
    )

    private inner class Images : ArtworkFingerprintSource {
        val originals = mutableListOf<String>()
        val candidates = mutableListOf<Int>()
        var originalAction: suspend () -> ArtworkFingerprint? = { throw AssertionError("Unexpected original image fetch") }

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
            throw AssertionError("Unexpected candidate image fetch")
        }
    }
}
