package app.gamenative.library.canonical

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.CanonicalIdGenerator
import app.gamenative.data.canonical.ClassificationState
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.data.canonical.StoreMatchEntity
import app.gamenative.db.PluviaDatabase
import app.gamenative.library.canonical.source.OwnedCopyProjection
import app.gamenative.library.canonical.source.SnapshotCompleteness
import app.gamenative.library.canonical.source.SourceProjectionBatch
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CanonicalProjectionScaleTest {

    private lateinit var db: PluviaDatabase
    private lateinit var engine: CanonicalProjectionEngine
    private lateinit var lifecycleState: AccountLifecycleState

    private val steamScope = AccountScope("1".repeat(64))
    private val gogScope = AccountScope("2".repeat(64))

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val idGenerator = SequentialCanonicalIdGenerator()
        lifecycleState = InMemoryAccountLifecycleState()
        engine = CanonicalProjectionEngine(
            db = db,
            resolver = CanonicalGameResolver(
                canonicalGameDao = db.canonicalGameDao(),
                storeMatchDao = db.storeMatchDao(),
                trustedSteamMappingProviders = emptySet(),
                idGenerator = idGenerator,
            ),
            accountLifecycleState = lifecycleState,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `fifteen hundred copies converge to nine hundred stable canonical games`() = runBlocking {
        val steamCopies = (100_000..100_899).map(::steamCopy)
        val gogCopies = (100_000..100_599).map(::gogCopy)
        val batches = listOf(
            batch(GameSource.GOG, gogScope, gogCopies),
            batch(GameSource.STEAM, steamScope, steamCopies),
        )

        val firstResult = engine.rebuild(batches, nowEpochMs = 1_000)
        val firstCanonicals = db.canonicalGameDao().getAll()
        val firstMatches = db.storeMatchDao().getAll()
        val firstKeyToCanonical = keyToCanonical(firstMatches)
        val firstMatchBuckets = matchBuckets(firstMatches)

        assertEquals(1_500, firstMatches.count { it.isPresent })
        assertEquals(900, firstCanonicals.size)
        assertEquals(900, firstResult.canonicalCount)
        assertEquals(1_500, firstResult.copyCount)
        assertEquals(
            mapOf(
                MatchBucket(MatchMethod.DIRECT_STEAM, MatchConfidence.VERIFIED) to 900,
                MatchBucket(MatchMethod.EXACT_METADATA, MatchConfidence.HIGH) to 600,
            ),
            firstMatchBuckets,
        )
        assertTrue(firstCanonicals.all { it.classificationState == ClassificationState.CLASSIFIED })
        (100_000..100_599).forEach { appId ->
            assertEquals(
                firstKeyToCanonical.getValue(steamKey(appId)),
                firstKeyToCanonical.getValue(gogKey(appId)),
            )
        }
        assertSteamFacets(firstKeyToCanonical.getValue(steamKey(100_000)))

        val secondResult = engine.rebuild(batches, nowEpochMs = 2_000)
        val secondCanonicals = db.canonicalGameDao().getAll()
        val secondMatches = db.storeMatchDao().getAll()

        assertEquals(firstResult, secondResult)
        assertEquals(firstCanonicals, secondCanonicals)
        assertEquals(firstMatches, secondMatches)
        assertEquals(firstKeyToCanonical, keyToCanonical(secondMatches))
        assertEquals(firstMatchBuckets, matchBuckets(secondMatches))
        assertSteamFacets(firstKeyToCanonical.getValue(steamKey(100_000)))
    }

    private suspend fun assertSteamFacets(canonicalId: String) {
        assertEquals(
            listOf("steam:action"),
            db.canonicalFacetDao().getGenres(canonicalId).map { it.genreKey },
        )
        assertEquals(
            listOf(19),
            db.canonicalFacetDao().getTags(canonicalId).map { it.tagId },
        )
        assertEquals(
            listOf("steam:controller"),
            db.canonicalFacetDao().getFeatures(canonicalId).map { it.featureKey },
        )
    }

    private fun steamCopy(appId: Int): OwnedCopyProjection = OwnedCopyProjection(
        key = steamKey(appId),
        displayName = title(appId),
        developer = developer(appId),
        releaseYear = 2020,
        appType = CanonicalAppType.GAME,
        directSteamAppId = appId,
        genreKeys = setOf("steam:action"),
        tagIds = setOf(19),
        featureKeys = setOf("steam:controller"),
    )

    private fun gogCopy(appId: Int): OwnedCopyProjection = OwnedCopyProjection(
        key = gogKey(appId),
        displayName = title(appId),
        developer = developer(appId),
        releaseYear = 2020,
        appType = CanonicalAppType.GAME,
    )

    private fun steamKey(appId: Int): OwnedCopyKey =
        OwnedCopyKey(steamScope, GameSource.STEAM, appId.toString())

    private fun gogKey(appId: Int): OwnedCopyKey =
        OwnedCopyKey(gogScope, GameSource.GOG, "gog-$appId")

    private fun title(appId: Int): String = "Scale Game $appId"

    private fun developer(appId: Int): String = "Scale Studio $appId"

    private fun batch(
        source: GameSource,
        accountScope: AccountScope,
        copies: List<OwnedCopyProjection>,
    ): SourceProjectionBatch = SourceProjectionBatch(
        source = source,
        accountScope = accountScope,
        lifecycleGeneration = lifecycleState.generation(source),
        completeness = SnapshotCompleteness.COMPLETE,
        copies = copies,
    )

    private fun keyToCanonical(matches: List<StoreMatchEntity>): Map<OwnedCopyKey, String> =
        matches.associate { match ->
            OwnedCopyKey(
                accountScope = AccountScope.parse(match.accountScope),
                source = match.source,
                stableSourceId = match.stableSourceId,
            ) to match.canonicalId
        }

    private fun matchBuckets(matches: List<StoreMatchEntity>): Map<MatchBucket, Int> =
        matches.groupingBy { match ->
            MatchBucket(match.matchMethod, match.confidence)
        }.eachCount()

    private class SequentialCanonicalIdGenerator(
        start: Long = 1_000,
    ) : CanonicalIdGenerator {
        private var next = start

        override fun generate(): CanonicalGameId = CanonicalGameId(
            UUID(0, next++).toString(),
        )
    }
}
