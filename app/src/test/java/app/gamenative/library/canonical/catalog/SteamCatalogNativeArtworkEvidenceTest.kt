package app.gamenative.library.canonical.catalog

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.AmazonGame
import app.gamenative.data.EpicGame
import app.gamenative.data.GOGGame
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameEntity
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.CanonicalIdGenerator
import app.gamenative.data.canonical.ClassificationState
import app.gamenative.data.canonical.EpicStableSourceId
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.data.canonical.SteamCatalogResolutionStatus
import app.gamenative.data.canonical.StoreMatchEntity
import app.gamenative.db.PluviaDatabase
import app.gamenative.library.canonical.CURRENT_RESOLVER_VERSION
import app.gamenative.library.canonical.RoomCanonicalMutationRepository
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
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.junit.rules.TimeoutRule

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = ResolutionWorkerTestApplication::class)
class SteamCatalogNativeArtworkEvidenceTest {
    @get:Rule val timeout = TimeoutRule.seconds(30)
    private lateinit var db: PluviaDatabase
    private lateinit var writer: RoomCanonicalMutationRepository
    private val canonicalId = UUID(0, 1).toString()
    private val title = "Fixture Game"
    private val epicId = EpicStableSourceId.encode("fixture-namespace", "fixture-catalog")
    private val amazonId = "amzn1.adg.product.${UUID(0, 1)}"
    private var recordRequests = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PluviaDatabase::class.java)
            .allowMainThreadQueries().build()
        writer = RoomCanonicalMutationRepository(db, CanonicalIdGenerator { CanonicalGameId.random() })
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun exactEpicCoverChangeReconsidersReviewAfterRepositoryRecreation() = runNativeTest {
        seed(GameSource.EPIC, epicId)
        putEpic(cover = "https://cdn1.epicgames.com/initial-cover.jpg")
        val before = reviewHash()
        putEpic(cover = "https://cdn1.epicgames.com/changed-cover.jpg")
        assertNotEquals(before, reviewHash())
        assertEquals(2, recordRequests)
        assertNoCatalogAcceptance()
    }

    @Test
    fun sameCatalogInAnotherEpicNamespaceCannotChangeEvidence() = runNativeTest {
        seed(GameSource.EPIC, epicId)
        putEpic(cover = "https://cdn1.epicgames.com/exact-cover.jpg")
        putEpic(id = 2, namespace = "other-namespace", cover = "https://cdn1.epicgames.com/other-cover.jpg")
        val before = reviewHash()
        putEpic(id = 2, namespace = "other-namespace", cover = "https://cdn1.epicgames.com/changed-other.jpg")
        assertSkippedWithHash(before)
    }

    @Test
    fun epicSquareAndPortraitCannotReplaceMissingOriginalCover() = runNativeTest {
        seed(GameSource.EPIC, epicId)
        putEpic(cover = "", square = "https://cdn1.epicgames.com/square.jpg")
        val before = reviewHash()
        putEpic(cover = "", square = "https://cdn1.epicgames.com/changed-square.jpg",
            portrait = "https://cdn1.epicgames.com/portrait.jpg")
        assertSkippedWithHash(before)
    }

    @Test
    fun exactAmazonProductArtChangeReconsidersReviewAfterRepositoryRecreation() = runNativeTest {
        seed(GameSource.AMAZON, amazonId)
        putAmazon(art = "https://m.media-amazon.com/initial-cover.jpg")
        val before = reviewHash()
        putAmazon(art = "https://m.media-amazon.com/changed-cover.jpg")
        assertNotEquals(before, reviewHash())
        assertEquals(2, recordRequests)
        assertNoCatalogAcceptance()
    }

    @Test
    fun amazonOtherProductHeroAndEntitlementAreNotOriginalArtworkEvidence() = runNativeTest {
        seed(GameSource.AMAZON, amazonId)
        putAmazon(art = "https://m.media-amazon.com/exact-cover.jpg")
        val before = reviewHash()
        putAmazon(appId = 2, productId = "amzn1.adg.product.${UUID(0, 2)}",
            art = "https://m.media-amazon.com/other-cover.jpg")
        putAmazon(art = "https://m.media-amazon.com/exact-cover.jpg",
            hero = "https://m.media-amazon.com/changed-hero.jpg", entitlement = "synthetic-private-entitlement")
        assertSkippedWithHash(before)
    }

    @Test
    fun credentialBearingNativeArtworkCannotChangePublicEvidence() = runNativeTest {
        seed(GameSource.GOG, "42")
        db.gogGameDao().insert(GOGGame(id = "42", title = title,
            verticalCoverUrl = "https://synthetic-user:synthetic-secret@images.gog.com/cover.jpg"))
        val before = reviewHash()
        db.gogGameDao().insert(GOGGame(id = "42", title = title,
            verticalCoverUrl = "file:///synthetic-private-install/cover.jpg"))
        assertSkippedWithHash(before)
        assertFalse(before.contains("synthetic"))
    }

    private fun runNativeTest(body: suspend () -> Unit) = runTest {
        withContext(Dispatchers.Default) { body() }
    }

    private suspend fun putEpic(
        id: Int = 1,
        namespace: String = "fixture-namespace",
        cover: String,
        square: String = "",
        portrait: String = "",
    ) {
        db.epicGameDao().insert(EpicGame(id = id, namespace = namespace, catalogId = "fixture-catalog",
            appName = "fixture-app-$id", title = title, artCover = cover, artSquare = square, artPortrait = portrait))
    }

    private suspend fun putAmazon(
        appId: Int = 1,
        productId: String = amazonId,
        art: String,
        hero: String = "",
        entitlement: String = "",
    ) {
        db.amazonGameDao().insertAll(listOf(AmazonGame(appId = appId, productId = productId, title = title,
            artUrl = art, heroUrl = hero, entitlementId = entitlement)))
    }

    private suspend fun seed(source: GameSource, stableSourceId: String) {
        val key = OwnedCopyKey(AccountScope("2".repeat(64)), source, stableSourceId)
        db.canonicalGameDao().insert(CanonicalGameEntity(
            canonicalId = canonicalId, steamAppId = null, displayName = title, matchTitleKey = "fixture game",
            primaryMetadataSource = source, appType = CanonicalAppType.GAME, releaseYear = null,
            developerKey = "", classificationState = ClassificationState.UNCLASSIFIED,
            steamReviewCount = null, createdAt = 1, updatedAt = 1,
        ))
        db.storeMatchDao().upsert(StoreMatchEntity(
            accountScope = key.accountScope.value, source = source, stableSourceId = stableSourceId,
            canonicalId = canonicalId, candidateSteamAppId = null, matchMethod = MatchMethod.UNMATCHED,
            confidence = MatchConfidence.UNMATCHED, decisionSource = MatchDecisionSource.AUTOMATIC,
            resolverVersion = CURRENT_RESOLVER_VERSION, matchedAt = 100, isPresent = true,
            evidenceDisplayName = title, evidenceTitleKey = "fixture game", evidenceDeveloperKey = "",
            evidenceReleaseYear = null, evidenceAppType = CanonicalAppType.GAME,
        ))
    }

    private suspend fun reviewHash(): String {
        assertEquals(1, repository().scanAutomatically().needsReview)
        val attempt = requireNotNull(db.steamCatalogResolutionDao().getAttempt(canonicalId))
        assertEquals(SteamCatalogResolutionStatus.REVIEW_REQUIRED, attempt.status)
        assertEquals(64, attempt.evidenceHash.length)
        assertFalse(attempt.evidenceHash.contains("https://"))
        assertFalse(attempt.evidenceHash.contains("2".repeat(64)))
        return attempt.evidenceHash
    }

    private suspend fun assertSkippedWithHash(before: String) {
        assertEquals(0, repository().scanAutomatically().total)
        assertEquals(before, db.steamCatalogResolutionDao().getAttempt(canonicalId)?.evidenceHash)
        assertEquals(1, recordRequests)
        assertNoCatalogAcceptance()
    }

    private suspend fun assertNoCatalogAcceptance() {
        assertNull(db.canonicalGameDao().get(canonicalId)?.steamAppId)
        assertEquals(1, db.storeMatchDao().getAll().size)
        assertFalse(db.storeMatchDao().getAll().any { it.source == GameSource.STEAM })
    }

    private fun repository(): SteamCatalogResolutionRepository = SteamCatalogResolutionRepository(
        storeMatchDao = db.storeMatchDao(),
        searchSource = SteamCatalogSearchSource { _, _ -> listOf(SteamStoreSearchHit(42, title, null)) },
        recordSource = SteamCatalogRecordSource { appId, _ ->
            assertFalse("Public providers stay outside Room transactions", db.inTransaction())
            recordRequests++
            SteamCatalogRecord(steamAppId = appId, appType = CanonicalAppType.GAME, releaseYear = null,
                metadata = CanonicalGameMetadata(
                    title = title, shortDescription = null, about = null, headerImageUrl = null,
                    screenshots = emptyList(), movies = emptyList(), developers = emptyList(), publishers = emptyList(),
                    releaseDate = null, platforms = setOf(GamePlatform.WINDOWS), languages = emptyList(),
                    requirements = null, features = emptyList(), achievementCount = null, dlcCount = null,
                    fetchedAtEpochMs = 1_000,
                ))
        },
        candidatePolicy = SteamCatalogCandidatePolicy(),
        decisionWriter = writer,
        pcGamingWikiSource = PcGamingWikiCurrentAvailabilitySource { PcGamingWikiCurrentAvailabilityResult.NotConfirmed },
        epicCatalogSource = EpicCmsCatalogSource { throw AssertionError("Review must not fetch source fallback") },
        epicFallbackWriter = writer,
        localeProvider = MetadataLocaleProvider { MetadataLocale("en-US", "US") },
        diagnostics = SteamCatalogResolutionDiagnosticSink {},
        acceptedIdentityEnrichment = SteamAcceptedIdentityEnrichmentSink { _, _, _ ->
            throw AssertionError("URI freshness alone must not establish catalog identity")
        },
        clock = MetadataClock { 1_000L },
        db = db,
        resumeScheduler = SteamCatalogResumeScheduler {},
    )
}
