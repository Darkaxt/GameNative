package app.gamenative.library.canonical

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameEntity
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.ClassificationState
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.data.canonical.StoreMatchEntity
import app.gamenative.db.PluviaDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class DurableSteamRejectionTest {
    @get:Rule val files = TemporaryFolder()
    private lateinit var db: PluviaDatabase
    private lateinit var repository: RoomCanonicalMutationRepository
    private val key = OwnedCopyKey(AccountScope("3".repeat(64)), GameSource.GOG, "1")
    private val canonicalId = "00000000-0000-0000-0000-000000000001"

    @Before
    fun setUp() = openDatabase()

    @After
    fun tearDown() = db.close()

    @Test
    fun `multiple rejected candidates survive database and repository recreation`() = runBlocking {
        seed()
        repository.rejectSteamCandidate(key, 99, 100)
        repository.rejectSteamCandidate(key, 42, 200)
        db.close()
        openDatabase()

        assertEquals(listOf(42, 99), rejections(key))
        assertEquals(MatchConfidence.REJECTED, current().confidence)
        assertEquals(42, current().candidateSteamAppId)
    }

    @Test
    fun `guarded rejection records the exact copy alongside its decision`() = runBlocking {
        seed(candidate = 99)
        assertEquals(CanonicalGuardedMutationResult.APPLIED, repository.reject(expected(), 99, 100))
        assertEquals(listOf(99), rejections(key))
        assertEquals(100L, db.openHelper.writableDatabase.query("SELECT rejected_at FROM rejected_steam_candidate").use {
            assertTrue(it.moveToFirst())
            it.getLong(0)
        })
    }

    @Test
    fun `stale rejection cannot add durable history`() = runBlocking {
        seed(candidate = 99)
        val stale = expected()
        db.storeMatchDao().upsert(current().copy(matchedAt = 80))
        assertEquals(CanonicalGuardedMutationResult.EXPECTED_STATE_CHANGED, repository.reject(stale, 99, 100))
        assertTrue(rejections(key).isEmpty())
        assertEquals(80L, current().matchedAt)
    }

    @Test
    fun `explicit reset clears only its exact copy rejections and invalidates its attempt`() = runBlocking {
        seed(candidate = 99)
        seedRejection(key, 99)
        seedRejection(key, 42)
        val otherAccount = key.copy(accountScope = AccountScope("4".repeat(64)))
        val otherSource = key.copy(source = GameSource.STEAM)
        val otherCopy = key.copy(stableSourceId = "2")
        for (other in listOf(otherAccount, otherSource, otherCopy)) seedRejection(other, 99)
        seedAttempt()

        assertEquals(CanonicalGuardedMutationResult.APPLIED, repository.reset(expected(), 100))
        assertTrue(rejections(key).isEmpty())
        for (other in listOf(otherAccount, otherSource, otherCopy)) assertEquals(listOf(99), rejections(other))
        assertEquals(0, attemptCount())
        assertEquals(MatchMethod.UNMATCHED, current().matchMethod)
    }

    @Test
    fun `legacy explicit reset has the same durable clearing semantics`() = runBlocking {
        seed()
        seedRejection(key, 99)
        seedAttempt()
        repository.resetDecision(key, 100)
        assertTrue(rejections(key).isEmpty())
        assertEquals(0, attemptCount())
    }

    @Test
    fun `stale reset preserves rejections and completed attempt`() = runBlocking {
        seed(candidate = 99)
        seedRejection(key, 99)
        seedAttempt()
        val stale = expected()
        db.storeMatchDao().upsert(current().copy(matchedAt = 80))
        assertEquals(CanonicalGuardedMutationResult.EXPECTED_STATE_CHANGED, repository.reset(stale, 100))
        assertEquals(listOf(99), rejections(key))
        assertEquals(1, attemptCount())
    }

    @Test
    fun `history insertion failure rolls back Steam identity and rejection decision`() = runBlocking {
        seed(candidate = 99, steamIdentity = 99)
        val before = current()
        val canonical = db.canonicalGameDao().get(canonicalId)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_rejection BEFORE INSERT ON rejected_steam_candidate BEGIN SELECT RAISE(ABORT, 'synthetic history failure'); END",
        )

        val failure = runCatching { repository.reject(expected(), 99, 100) }.exceptionOrNull()
        assertNotNull("Rejection history must participate in the guarded transaction", failure)
        assertEquals(before, current())
        assertEquals(canonical, db.canonicalGameDao().get(canonicalId))
        assertTrue(rejections(key).isEmpty())
    }

    @Test
    fun `unmerge retains a durable rejection of the detached Steam identity`() = runBlocking {
        seed(candidate = 99, steamIdentity = 99)
        val selected = current().copy(confidence = MatchConfidence.HIGH)
        db.storeMatchDao().upsert(selected)
        db.storeMatchDao().upsert(selected.copy(stableSourceId = "2"))
        val projection = app.gamenative.library.canonical.source.OwnedCopyProjection(
            key, "Public Game", "studio", 2020, CanonicalAppType.GAME,
        )
        assertEquals(CanonicalGuardedMutationResult.APPLIED,
            repository.guardedUnmergeCopy(key, projection, canonicalId, 100))
        db.close()
        openDatabase()
        assertEquals(listOf(99), rejections(key))
        assertTrue(current().canonicalId != canonicalId)
    }

    @Test
    fun `older sibling rejection prevents manual confirmation from merging that sibling`() = runBlocking {
        seed()
        val other = key.copy(stableSourceId = "2")
        db.storeMatchDao().upsert(current().copy(stableSourceId = other.stableSourceId))
        repository.rejectSteamCandidate(key, 99, 100)
        repository.rejectSteamCandidate(key, 42, 200)
        val confirmedId = repository.confirmSteamMatch(other, 99, 300)
        assertTrue("The sibling rejected 99 even though its latest rejection was 42", confirmedId != canonicalId)
        assertEquals(null, db.canonicalGameDao().get(canonicalId)?.steamAppId)
        assertEquals(listOf(42, 99), rejections(key))
    }

    private fun openDatabase() {
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getDatabasePath(name: String): File = File(files.root, name)
        }
        db = Room.databaseBuilder(context, PluviaDatabase::class.java, "history.db").allowMainThreadQueries().build()
        repository = RoomCanonicalMutationRepository(db) { CanonicalGameId.random() }
    }

    private suspend fun seed(candidate: Int? = null, steamIdentity: Int? = null) {
        db.canonicalGameDao().insert(
            CanonicalGameEntity(canonicalId, steamIdentity, "Public Game", "public game", GameSource.GOG,
                CanonicalAppType.GAME, 2020, "studio", ClassificationState.UNCLASSIFIED, null, 1, 1),
        )
        db.storeMatchDao().upsert(
            StoreMatchEntity(key.accountScope.value, key.source, key.stableSourceId, canonicalId, candidate,
                if (candidate == null) MatchMethod.UNMATCHED else MatchMethod.STEAM_CATALOG,
                if (candidate == null) MatchConfidence.UNMATCHED else MatchConfidence.REVIEW_REQUIRED,
                MatchDecisionSource.AUTOMATIC, CURRENT_RESOLVER_VERSION, 1, true,
                "Public Game", "public game", "studio", 2020, CanonicalAppType.GAME),
        )
    }

    private suspend fun current(): StoreMatchEntity = requireNotNull(db.storeMatchDao().get(key.accountScope.value, key.source, key.stableSourceId))

    private suspend fun expected(): ExpectedMatchState = current().let {
        ExpectedMatchState(key, it.canonicalId, it.matchMethod, it.confidence, it.decisionSource,
            it.candidateSteamAppId, it.resolverVersion, it.matchedAt)
    }

    private fun seedRejection(copy: OwnedCopyKey, appId: Int) = db.openHelper.writableDatabase.execSQL(
        "INSERT INTO rejected_steam_candidate VALUES (?, ?, ?, ?, ?)",
        arrayOf(copy.accountScope.value, copy.source.name, copy.stableSourceId, appId, 1L),
    )

    private fun rejections(copy: OwnedCopyKey): List<Int> = db.openHelper.writableDatabase.query(
        "SELECT steam_app_id FROM rejected_steam_candidate WHERE account_scope = ? AND source = ? AND stable_source_id = ? ORDER BY steam_app_id",
        arrayOf(copy.accountScope.value, copy.source.name, copy.stableSourceId),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getInt(0)) } }

    private fun seedAttempt() = db.openHelper.writableDatabase.execSQL(
        "INSERT INTO steam_catalog_resolution_attempt VALUES (?, 'public-evidence-hash', ?, 'UNMATCHED', 1)",
        arrayOf(canonicalId, CURRENT_RESOLVER_VERSION),
    )

    private fun attemptCount(): Int = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM steam_catalog_resolution_attempt").use {
        assertTrue(it.moveToFirst())
        it.getInt(0)
    }
}
