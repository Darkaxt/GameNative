package app.gamenative.library.canonical

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.CanonicalGamePreferenceEntity
import app.gamenative.data.canonical.CanonicalIdGenerator
import app.gamenative.data.canonical.ClassificationState
import app.gamenative.data.canonical.GameDetailSnapshotEntity
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.db.PluviaDatabase
import app.gamenative.library.canonical.source.OwnedCopyProjection
import app.gamenative.library.canonical.source.SnapshotCompleteness
import app.gamenative.library.canonical.source.SnapshotReason
import app.gamenative.library.canonical.source.SourceProjectionBatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class CanonicalProjectionEngineTest {

    private lateinit var db: PluviaDatabase
    private lateinit var idGenerator: SequentialCanonicalIdGenerator
    private lateinit var lifecycleState: InMemoryAccountLifecycleState
    private lateinit var engine: CanonicalProjectionEngine

    private val primaryScope = AccountScope("1".repeat(64))
    private val secondaryScope = AccountScope("2".repeat(64))

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        idGenerator = SequentialCanonicalIdGenerator()
        lifecycleState = InMemoryAccountLifecycleState()
        engine = newEngine(realResolver())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `reverse ordered batches still resolve Steam first and preserve Steam facets`() = runBlocking {
        val steam = copy(
            source = GameSource.STEAM,
            stableSourceId = "10",
            displayName = "Control",
            developer = "Remedy Entertainment",
            releaseYear = 2019,
            directSteamAppId = 10,
            genreKeys = setOf("steam:action"),
            tagIds = setOf(19, 21),
            featureKeys = setOf("steam:controller"),
        )
        val gog = copy(
            source = GameSource.GOG,
            stableSourceId = "gog-10",
            displayName = "Control",
            developer = "Remedy Entertainment",
            releaseYear = 2019,
            genreKeys = setOf("gog:adventure"),
        )

        val result = engine.rebuild(
            batches = listOf(
                batch(GameSource.GOG, copies = listOf(gog)),
                batch(GameSource.STEAM, copies = listOf(steam)),
            ),
            nowEpochMs = 100,
        )

        val canonicals = db.canonicalGameDao().getAll()
        val matches = db.storeMatchDao().getAll()
        assertEquals(1, canonicals.size)
        assertEquals(2, matches.size)
        assertEquals(1, matches.map { it.canonicalId }.distinct().size)
        assertEquals(GameSource.STEAM, canonicals.single().primaryMetadataSource)
        assertEquals(ClassificationState.CLASSIFIED, canonicals.single().classificationState)
        assertEquals(
            listOf("steam:action"),
            db.canonicalFacetDao().getGenres(canonicals.single().canonicalId).map { it.genreKey },
        )
        assertEquals(
            listOf(19, 21),
            db.canonicalFacetDao().getTags(canonicals.single().canonicalId).map { it.tagId },
        )
        assertEquals(
            listOf("steam:controller"),
            db.canonicalFacetDao().getFeatures(canonicals.single().canonicalId).map { it.featureKey },
        )
        assertEquals(listOf(GameSource.STEAM, GameSource.GOG), result.sourceCounts.keys.toList())
        assertEquals(1, result.canonicalCount)
        assertEquals(2, result.copyCount)
        assertEquals(1, result.matchCounts[MatchBucket(MatchMethod.DIRECT_STEAM, MatchConfidence.VERIFIED)])
        assertEquals(1, result.matchCounts[MatchBucket(MatchMethod.EXACT_METADATA, MatchConfidence.HIGH)])
    }

    @Test
    fun `identical rebuild preserves all projected rows and timestamps`() = runBlocking {
        val batches = listOf(
            batch(
                GameSource.GOG,
                copies = listOf(
                    copy(
                        source = GameSource.GOG,
                        stableSourceId = "gog-10",
                        displayName = "Control",
                        developer = "Remedy Entertainment",
                        releaseYear = 2019,
                        genreKeys = setOf("gog:adventure"),
                    ),
                ),
            ),
            batch(
                GameSource.STEAM,
                copies = listOf(
                    copy(
                        source = GameSource.STEAM,
                        stableSourceId = "10",
                        displayName = "Control",
                        developer = "Remedy Entertainment",
                        releaseYear = 2019,
                        directSteamAppId = 10,
                        genreKeys = setOf("steam:action"),
                        tagIds = setOf(19),
                    ),
                ),
            ),
        )

        engine.rebuild(batches, nowEpochMs = 100)
        val firstCanonicals = db.canonicalGameDao().getAll()
        val firstMatches = db.storeMatchDao().getAll()
        val firstGenres = db.canonicalFacetDao().getGenres(firstCanonicals.single().canonicalId)
        val firstTags = db.canonicalFacetDao().getTags(firstCanonicals.single().canonicalId)

        engine.rebuild(batches, nowEpochMs = 200)

        assertEquals(firstCanonicals, db.canonicalGameDao().getAll())
        assertEquals(firstMatches, db.storeMatchDao().getAll())
        assertEquals(firstGenres, db.canonicalFacetDao().getGenres(firstCanonicals.single().canonicalId))
        assertEquals(firstTags, db.canonicalFacetDao().getTags(firstCanonicals.single().canonicalId))
    }

    @Test
    fun `automatic reassignment transfers dependents and removes standalone orphan`() = runBlocking {
        val gog = copy(
            source = GameSource.GOG,
            stableSourceId = "gog-10",
            displayName = "Control",
            developer = "Remedy Entertainment",
            releaseYear = 2019,
            genreKeys = setOf("gog:adventure"),
        )
        engine.rebuild(
            batches = listOf(batch(GameSource.GOG, copies = listOf(gog))),
            nowEpochMs = 100,
        )
        val standaloneId = db.canonicalGameDao().getAll().single().canonicalId
        db.canonicalPreferenceDao().upsert(
            CanonicalGamePreferenceEntity(
                canonicalId = standaloneId,
                preferredAccountScope = gog.key.accountScope.value,
                preferredSource = gog.key.source,
                preferredStableSourceId = gog.key.stableSourceId,
                titleOverride = "Preferred title",
                artworkOverrideJson = "{}",
                updatedAt = 110,
            ),
        )
        db.gameDetailSnapshotDao().upsert(
            GameDetailSnapshotEntity(
                canonicalId = standaloneId,
                locale = "en",
                country = "US",
                payloadJson = "{}",
                provenanceJson = "{}",
                fetchedAt = 120,
                sourceRevision = "revision",
            ),
        )

        val steam = copy(
            source = GameSource.STEAM,
            stableSourceId = "10",
            displayName = "Control",
            developer = "Remedy Entertainment",
            releaseYear = 2019,
            directSteamAppId = 10,
            genreKeys = setOf("steam:action"),
        )
        val result = engine.rebuild(
            batches = listOf(
                batch(GameSource.GOG, copies = listOf(gog)),
                batch(
                    source = GameSource.STEAM,
                    completeness = SnapshotCompleteness.PARTIAL,
                    copies = listOf(steam),
                    reason = SnapshotReason.MISSING_MATERIALIZED_ROW,
                ),
            ),
            nowEpochMs = 200,
        )

        val canonical = db.canonicalGameDao().getAll().single()
        assertEquals(10, canonical.steamAppId)
        assertEquals(1, result.canonicalCount)
        assertNull(db.canonicalGameDao().get(standaloneId))
        assertEquals(
            listOf(canonical.canonicalId),
            db.storeMatchDao().getAll().map { it.canonicalId }.distinct(),
        )
        assertEquals(
            listOf("gog:adventure", "steam:action"),
            db.canonicalFacetDao().getGenres(canonical.canonicalId).map { it.genreKey },
        )
        val preference = db.canonicalPreferenceDao().get(canonical.canonicalId)
        assertEquals("Preferred title", preference?.titleOverride)
        assertEquals(gog.key, preference?.preferredCopyKeyOrNull())
        val snapshot = db.gameDetailSnapshotDao().get(canonical.canonicalId, "en", "US")
        assertEquals("revision", snapshot?.sourceRevision)
        assertTrue(db.canonicalGameDao().findByTitleKey("control").all {
            it.canonicalId == canonical.canonicalId
        })
    }

    @Test
    fun `automatic reassignment preserves a canonical that still owns another copy`() = runBlocking {
        val first = copy(
            source = GameSource.GOG,
            stableSourceId = "a",
            displayName = "Original Game",
            developer = "Shared Studio",
            releaseYear = 2020,
        )
        val second = first.copy(
            key = first.key.copy(stableSourceId = "2"),
        )
        engine.rebuild(
            batches = listOf(batch(GameSource.GOG, copies = listOf(first, second))),
            nowEpochMs = 100,
        )
        val sharedCanonicalId = db.canonicalGameDao().getAll().single().canonicalId
        db.canonicalPreferenceDao().upsert(
            CanonicalGamePreferenceEntity(
                canonicalId = sharedCanonicalId,
                preferredAccountScope = second.key.accountScope.value,
                preferredSource = second.key.source,
                preferredStableSourceId = second.key.stableSourceId,
                titleOverride = "Keep with shared canonical",
                artworkOverrideJson = null,
                updatedAt = 110,
            ),
        )

        val changedFirst = first.copy(displayName = "New Game")
        val steam = copy(
            source = GameSource.STEAM,
            stableSourceId = "10",
            displayName = "New Game",
            developer = "Shared Studio",
            releaseYear = 2020,
            directSteamAppId = 10,
        )
        engine.rebuild(
            batches = listOf(
                batch(GameSource.GOG, copies = listOf(changedFirst, second)),
                batch(GameSource.STEAM, copies = listOf(steam)),
            ),
            nowEpochMs = 200,
        )

        val matches = db.storeMatchDao().getAll().associateBy { it.stableSourceId }
        val steamCanonicalId = requireNotNull(matches["10"]).canonicalId
        assertEquals(
            steamCanonicalId,
            requireNotNull(matches[first.key.stableSourceId]).canonicalId,
        )
        assertEquals(
            sharedCanonicalId,
            requireNotNull(matches[second.key.stableSourceId]).canonicalId,
        )
        assertEquals(2, db.canonicalGameDao().getAll().size)
        assertEquals(
            "Keep with shared canonical",
            db.canonicalPreferenceDao().get(sharedCanonicalId)?.titleOverride,
        )
        assertNull(db.canonicalPreferenceDao().get(steamCanonicalId))
    }

    @Test
    fun `identical rebuild with multiple primary source copies preserves updated time`() = runBlocking {
        val copies = listOf(
            copy(
                source = GameSource.GOG,
                stableSourceId = "a",
                displayName = "Control",
                developer = "Remedy Entertainment",
                releaseYear = 2019,
            ),
            copy(
                source = GameSource.GOG,
                stableSourceId = "b",
                displayName = "CONTROL",
                developer = "Remedy Entertainment",
                releaseYear = 2019,
            ),
        )
        val batches = listOf(batch(GameSource.GOG, copies = copies))

        engine.rebuild(batches, nowEpochMs = 100)
        val first = db.canonicalGameDao().getAll().single()
        assertEquals("CONTROL", first.displayName)

        engine.rebuild(batches, nowEpochMs = 200)

        assertEquals(first, db.canonicalGameDao().getAll().single())
    }

    @Test
    fun `partial snapshot retains facets contributed by an omitted matching copy`() = runBlocking {
        val first = copy(
            source = GameSource.GOG,
            stableSourceId = "a",
            displayName = "Shared Game",
            developer = "Shared Studio",
            releaseYear = 2020,
            genreKeys = setOf("gog:action"),
        )
        val second = copy(
            source = GameSource.GOG,
            stableSourceId = "b",
            displayName = "Shared Game",
            developer = "Shared Studio",
            releaseYear = 2020,
            genreKeys = setOf("gog:strategy"),
        )
        engine.rebuild(
            listOf(batch(GameSource.GOG, copies = listOf(first, second))),
            nowEpochMs = 100,
        )
        val canonicalId = db.canonicalGameDao().getAll().single().canonicalId

        engine.rebuild(
            listOf(
                batch(
                    source = GameSource.GOG,
                    completeness = SnapshotCompleteness.PARTIAL,
                    copies = listOf(first),
                    reason = SnapshotReason.MISSING_MATERIALIZED_ROW,
                ),
            ),
            nowEpochMs = 200,
        )

        assertEquals(
            listOf("gog:action", "gog:strategy"),
            db.canonicalFacetDao().getGenres(canonicalId).map { it.genreKey },
        )
    }

    @Test
    fun `complete partial unavailable and complete empty snapshots preserve presence rules`() = runBlocking {
        val first = copy(
            source = GameSource.GOG,
            stableSourceId = "first",
            displayName = "First Game",
            developer = "First Studio",
            releaseYear = 2020,
            genreKeys = setOf("gog:action"),
        )
        val second = copy(
            source = GameSource.GOG,
            stableSourceId = "second",
            displayName = "Second Game",
            developer = "Second Studio",
            releaseYear = 2021,
            genreKeys = setOf("gog:strategy"),
        )
        engine.rebuild(listOf(batch(GameSource.GOG, copies = listOf(first, second))), 100)

        engine.rebuild(
            listOf(
                batch(
                    source = GameSource.GOG,
                    completeness = SnapshotCompleteness.PARTIAL,
                    copies = listOf(first),
                    reason = SnapshotReason.MISSING_MATERIALIZED_ROW,
                ),
            ),
            200,
        )
        assertTrue(db.storeMatchDao().getAll().all { it.isPresent })

        val unavailableResult = engine.rebuild(
            listOf(
                batch(
                    source = GameSource.GOG,
                    completeness = SnapshotCompleteness.UNAVAILABLE,
                    copies = emptyList(),
                    reason = SnapshotReason.SOURCE_READ_FAILED,
                ),
            ),
            300,
        )
        assertTrue(db.storeMatchDao().getAll().all { it.isPresent })
        assertEquals(
            SnapshotReason.SOURCE_READ_FAILED,
            unavailableResult.unavailableSources[GameSource.GOG],
        )

        engine.rebuild(listOf(batch(GameSource.GOG, copies = emptyList())), 400)

        assertTrue(db.storeMatchDao().getAll().none { it.isPresent })
        val retainedCanonicals = db.canonicalGameDao().getAll()
        assertEquals(2, retainedCanonicals.size)
        assertEquals(
            2,
            retainedCanonicals.sumOf { canonical ->
                db.canonicalFacetDao().getGenres(canonical.canonicalId).size
            },
        )
    }

    @Test
    fun `account lifecycle unavailability retires cached ownership but source failure preserves it`() = runBlocking {
        val owned = copy(
            source = GameSource.GOG,
            stableSourceId = "owned",
            displayName = "Owned Game",
            developer = "Studio",
            releaseYear = 2020,
        )
        engine.rebuild(listOf(batch(GameSource.GOG, copies = listOf(owned))), 100)

        engine.rebuild(
            listOf(
                batch(
                    source = GameSource.GOG,
                    completeness = SnapshotCompleteness.UNAVAILABLE,
                    copies = emptyList(),
                    reason = SnapshotReason.SOURCE_READ_FAILED,
                ),
            ),
            200,
        )
        assertTrue(db.storeMatchDao().getAll().single().isPresent)

        engine.rebuild(
            listOf(
                batch(
                    source = GameSource.GOG,
                    completeness = SnapshotCompleteness.UNAVAILABLE,
                    copies = emptyList(),
                    reason = SnapshotReason.PRESENCE_LEDGER_NOT_READY,
                ),
            ),
            300,
        )
        assertFalse(db.storeMatchDao().getAll().single().isPresent)

        engine.rebuild(listOf(batch(GameSource.GOG, copies = listOf(owned))), 400)
        engine.rebuild(
            listOf(
                SourceProjectionBatch(
                    source = GameSource.GOG,
                    accountScope = null,
                    lifecycleGeneration = lifecycleState.generation(GameSource.GOG),
                    completeness = SnapshotCompleteness.UNAVAILABLE,
                    copies = emptyList(),
                    reason = SnapshotReason.MISSING_ACCOUNT_SCOPE,
                ),
            ),
            500,
        )
        assertFalse(db.storeMatchDao().getAll().single().isPresent)
    }

    @Test
    fun `unmatched source facets are qualified and classify their canonical`() = runBlocking {
        val gog = copy(
            source = GameSource.GOG,
            stableSourceId = "unmatched",
            displayName = "Unmatched Game",
            developer = "Unknown Studio",
            releaseYear = 2022,
            genreKeys = setOf("gog:role-playing", "gog:strategy"),
        )

        engine.rebuild(listOf(batch(GameSource.GOG, copies = listOf(gog))), 100)

        val canonical = db.canonicalGameDao().getAll().single()
        assertEquals(ClassificationState.PARTIALLY_CLASSIFIED, canonical.classificationState)
        assertEquals(
            listOf("gog:role-playing", "gog:strategy"),
            db.canonicalFacetDao().getGenres(canonical.canonicalId).map { it.genreKey },
        )
    }

    @Test
    fun `stale lifecycle batch is rejected before database mutation`() = runBlocking {
        val owned = copy(
            source = GameSource.GOG,
            stableSourceId = "owned",
            displayName = "Owned Game",
            developer = "Studio",
            releaseYear = 2020,
        )
        val staleBatch = batch(GameSource.GOG, copies = listOf(owned))
        lifecycleState.advanceGeneration(GameSource.GOG)

        val failure = runCatching {
            engine.rebuild(listOf(staleBatch), nowEpochMs = 100)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(
            "ACCOUNT_LIFECYCLE_CHANGED_DURING_PROJECTION",
            failure?.message,
        )
        assertTrue(db.canonicalGameDao().getAll().isEmpty())
        assertTrue(db.storeMatchDao().getAll().isEmpty())
    }

    @Test
    fun `account transition waits for a started projection transaction`() = runBlocking {
        val delegate = realResolver()
        val resolverStarted = CompletableDeferred<Unit>()
        val releaseResolver = CompletableDeferred<Unit>()
        val blockingResolver = object : CanonicalResolver {
            override suspend fun resolve(
                copy: OwnedCopyProjection,
                nowEpochMs: Long,
            ): CanonicalResolution {
                resolverStarted.complete(Unit)
                releaseResolver.await()
                return delegate.resolve(copy, nowEpochMs)
            }
        }
        val blockingEngine = newEngine(blockingResolver)
        val owned = copy(
            source = GameSource.GOG,
            stableSourceId = "owned",
            displayName = "Owned Game",
            developer = "Studio",
            releaseYear = 2020,
        )
        val projection = async {
            blockingEngine.rebuild(
                listOf(batch(GameSource.GOG, copies = listOf(owned))),
                nowEpochMs = 100,
            )
        }
        resolverStarted.await()
        val transitionStarted = CompletableDeferred<Unit>()
        val transition = async(Dispatchers.Default) {
            transitionStarted.complete(Unit)
            AccountLifecycleSerialization.blocking {
                lifecycleState.advanceGeneration(GameSource.GOG)
            }
        }
        transitionStarted.await()

        assertFalse(transition.isCompleted)
        assertEquals(0L, lifecycleState.generation(GameSource.GOG))
        releaseResolver.complete(Unit)

        projection.await()
        assertEquals(1L, transition.await())
        assertEquals(1L, lifecycleState.generation(GameSource.GOG))
        assertEquals(1, db.storeMatchDao().getAll().size)
    }

    @Test
    fun `later resolver failure rolls back earlier canonical writes`() = runBlocking {
        val delegate = realResolver()
        var calls = 0
        val failingResolver = object : CanonicalResolver {
            override suspend fun resolve(
                copy: OwnedCopyProjection,
                nowEpochMs: Long,
            ): CanonicalResolution {
                calls += 1
                if (calls == 2) error("resolver failed")
                return delegate.resolve(copy, nowEpochMs)
            }
        }
        val failingEngine = newEngine(failingResolver)
        val copies = listOf(
            copy(
                source = GameSource.STEAM,
                stableSourceId = "10",
                displayName = "First",
                developer = "Studio",
                releaseYear = 2020,
                directSteamAppId = 10,
                genreKeys = setOf("steam:action"),
            ),
            copy(
                source = GameSource.STEAM,
                stableSourceId = "20",
                displayName = "Second",
                developer = "Studio",
                releaseYear = 2021,
                directSteamAppId = 20,
                genreKeys = setOf("steam:strategy"),
            ),
        )

        val failure = runCatching {
            failingEngine.rebuild(listOf(batch(GameSource.STEAM, copies = copies)), 100)
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(db.canonicalGameDao().getAll().isEmpty())
        assertTrue(db.storeMatchDao().getAll().isEmpty())
    }

    @Test
    fun `same provider id across account scopes retains history with only current account present`() = runBlocking {
        val primaryCopy = copy(
            source = GameSource.GOG,
            accountScope = primaryScope,
            stableSourceId = "shared-id",
            displayName = "Shared Game",
            developer = "Shared Studio",
            releaseYear = 2020,
        )
        val secondaryCopy = primaryCopy.copy(
            key = primaryCopy.key.copy(accountScope = secondaryScope),
        )

        engine.rebuild(
            listOf(batch(GameSource.GOG, primaryScope, copies = listOf(primaryCopy))),
            100,
        )
        engine.rebuild(
            listOf(batch(GameSource.GOG, secondaryScope, copies = listOf(secondaryCopy))),
            200,
        )

        val matches = db.storeMatchDao().getAll()
        assertEquals(2, matches.size)
        assertEquals(2, matches.map { it.accountScope }.distinct().size)
        assertEquals(1, matches.map { it.canonicalId }.distinct().size)
        assertEquals(
            mapOf(primaryScope.value to false, secondaryScope.value to true),
            matches.associate { it.accountScope to it.isPresent },
        )
    }

    @Test
    fun `stored non Steam user decision remains unchanged when copy is observed`() = runBlocking {
        val gog = copy(
            source = GameSource.GOG,
            stableSourceId = "user-choice",
            displayName = "Chosen Game",
            developer = "Chosen Studio",
            releaseYear = 2020,
        )
        engine.rebuild(listOf(batch(GameSource.GOG, copies = listOf(gog))), 100)
        val automatic = db.storeMatchDao().getAll().single()
        val userDecision = automatic.copy(
            candidateSteamAppId = 99,
            confidence = MatchConfidence.REJECTED,
            decisionSource = MatchDecisionSource.USER,
            matchedAt = 150,
        )
        db.storeMatchDao().upsert(userDecision)

        engine.rebuild(listOf(batch(GameSource.GOG, copies = listOf(gog))), 200)

        val stored = db.storeMatchDao().getAll().single()
        assertEquals(userDecision, stored)
    }

    @Test
    fun `invalid input is rejected before complete snapshot can mutate presence`() = runBlocking {
        val existing = copy(
            source = GameSource.GOG,
            stableSourceId = "existing",
            displayName = "Existing Game",
            developer = "Existing Studio",
            releaseYear = 2020,
        )
        engine.rebuild(listOf(batch(GameSource.GOG, copies = listOf(existing))), 100)
        val before = db.storeMatchDao().getAll()
        val invalidInputs = listOf(
            listOf(
                batch(GameSource.GOG, copies = emptyList()),
                batch(GameSource.GOG, copies = listOf(existing)),
            ),
            listOf(
                batch(
                    GameSource.GOG,
                    copies = listOf(existing.copy(key = existing.key.copy(accountScope = secondaryScope))),
                ),
            ),
            listOf(batch(GameSource.GOG, copies = listOf(existing, existing))),
            listOf(
                SourceProjectionBatch(
                    source = GameSource.GOG,
                    accountScope = null,
                    completeness = SnapshotCompleteness.COMPLETE,
                    copies = emptyList(),
                ),
            ),
            listOf(
                batch(
                    source = GameSource.GOG,
                    completeness = SnapshotCompleteness.UNAVAILABLE,
                    copies = listOf(existing),
                    reason = SnapshotReason.SOURCE_READ_FAILED,
                ),
            ),
        )

        invalidInputs.forEach { batches ->
            val failure = runCatching { engine.rebuild(batches, 200) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals(before, db.storeMatchDao().getAll())
        }
    }

    private fun newEngine(resolver: CanonicalResolver): CanonicalProjectionEngine =
        CanonicalProjectionEngine(
            db = db,
            resolver = resolver,
            accountLifecycleState = lifecycleState,
        )

    private fun realResolver(): CanonicalResolver = CanonicalGameResolver(
        canonicalGameDao = db.canonicalGameDao(),
        storeMatchDao = db.storeMatchDao(),
        trustedSteamMappingProviders = emptySet(),
        idGenerator = idGenerator,
    )

    private fun batch(
        source: GameSource,
        accountScope: AccountScope = primaryScope,
        completeness: SnapshotCompleteness = SnapshotCompleteness.COMPLETE,
        copies: List<OwnedCopyProjection>,
        reason: SnapshotReason? = null,
    ): SourceProjectionBatch = SourceProjectionBatch(
        source = source,
        accountScope = accountScope,
        lifecycleGeneration = if (source == GameSource.CUSTOM_GAME) {
            null
        } else {
            lifecycleState.generation(source)
        },
        completeness = completeness,
        copies = copies,
        reason = reason,
    )

    private fun copy(
        source: GameSource,
        accountScope: AccountScope = primaryScope,
        stableSourceId: String,
        displayName: String,
        developer: String,
        releaseYear: Int?,
        directSteamAppId: Int? = null,
        genreKeys: Set<String> = emptySet(),
        tagIds: Set<Int> = emptySet(),
        featureKeys: Set<String> = emptySet(),
    ): OwnedCopyProjection {
        val canonicalStableSourceId = when (source) {
            GameSource.GOG -> stableSourceId.takeIf { value ->
                value.toLongOrNull()?.let { it > 0L && it.toString() == value } == true
            } ?: (stableSourceId.hashCode().toUInt().toLong() + 1L).toString()
            GameSource.AMAZON -> stableSourceId.takeIf { it.startsWith("amzn1.adg.product.") }
                ?: "amzn1.adg.product.${UUID.nameUUIDFromBytes(stableSourceId.toByteArray())}"
            else -> stableSourceId
        }
        return OwnedCopyProjection(
            key = OwnedCopyKey(
                accountScope = accountScope,
                source = source,
                stableSourceId = canonicalStableSourceId,
            ),
            displayName = displayName,
            developer = developer,
            releaseYear = releaseYear,
            appType = CanonicalAppType.GAME,
            directSteamAppId = directSteamAppId,
            genreKeys = genreKeys,
            tagIds = tagIds,
            featureKeys = featureKeys,
        )
    }

    private class SequentialCanonicalIdGenerator(
        start: Long = 1_000,
    ) : CanonicalIdGenerator {
        private var next = start

        override fun generate(): CanonicalGameId = CanonicalGameId(
            UUID(0, next++).toString(),
        )
    }
}
