package app.gamenative.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.OwnedCopySyncEntity
import app.gamenative.db.PluviaDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class OwnedCopyLedgerDaoTest {
    private lateinit var database: PluviaDatabase
    private lateinit var dao: OwnedCopyLedgerDao
    private val scopeA = AccountScope.parse("a".repeat(64))
    private val scopeB = AccountScope.parse("b".repeat(64))

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.ownedCopyLedgerDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun replacementStoresSortedUniqueIdsAndCompletedHeader() = runTest {
        dao.replaceCompletedSnapshot(
            accountScope = scopeA.value,
            source = GameSource.GOG,
            stableSourceIds = listOf("z", "a", "z"),
            completedAt = 17L,
        )

        val snapshot = dao.getCompletedSnapshot(scopeA.value, GameSource.GOG)

        assertNotNull(snapshot)
        assertEquals(17L, snapshot?.completedAt)
        assertEquals(listOf("a", "z"), snapshot?.stableSourceIds)
        assertTrue(dao.isPresent(scopeA.value, GameSource.GOG, "a"))
        assertFalse(dao.isPresent(scopeA.value, GameSource.GOG, "missing"))
    }

    @Test
    fun malformedIdsRejectReplacementWithoutChangingPriorSnapshot() = runTest {
        dao.replaceCompletedSnapshot(scopeA.value, GameSource.GOG, listOf("old"), 1L)

        val result = runCatching {
            dao.replaceCompletedSnapshot(
                accountScope = scopeA.value,
                source = GameSource.GOG,
                stableSourceIds = listOf(" valid ", ""),
                completedAt = 2L,
            )
        }

        assertTrue(result.isFailure)
        assertEquals(listOf("old"), dao.getCompletedStableSourceIds(scopeA.value, GameSource.GOG))
    }

    @Test
    fun resolvedSourceIdIsStoredWithItsAccountScopedPresence() = runTest {
        dao.replaceCompletedSnapshot(
            accountScope = scopeA.value,
            source = GameSource.AMAZON,
            stableSourceIds = listOf("product"),
            completedAt = 1L,
            resolvedSourceIds = mapOf("product" to "entitlement-a"),
        )
        dao.replaceCompletedSnapshot(
            accountScope = scopeB.value,
            source = GameSource.AMAZON,
            stableSourceIds = listOf("product"),
            completedAt = 2L,
            resolvedSourceIds = mapOf("product" to "entitlement-b"),
        )

        assertEquals(
            "entitlement-a",
            dao.getPresence(scopeA.value, GameSource.AMAZON, "product")?.resolvedSourceId,
        )
        assertEquals(
            "entitlement-b",
            dao.getPresence(scopeB.value, GameSource.AMAZON, "product")?.resolvedSourceId,
        )
    }

    @Test
    fun completeEmptyReplacementRetainsHeader() = runTest {
        dao.replaceCompletedSnapshot(scopeA.value, GameSource.EPIC, listOf("old"), 1L)

        dao.replaceCompletedSnapshot(scopeA.value, GameSource.EPIC, emptyList(), 2L)

        val snapshot = dao.getCompletedSnapshot(scopeA.value, GameSource.EPIC)
        assertNotNull(snapshot)
        assertEquals(2L, snapshot?.completedAt)
        assertTrue(snapshot?.stableSourceIds?.isEmpty() == true)
    }

    @Test
    fun replacementDoesNotChangeAnotherSourceForTheSameAccount() = runTest {
        dao.replaceCompletedSnapshot(scopeA.value, GameSource.GOG, listOf("gog"), 1L)
        dao.replaceCompletedSnapshot(scopeA.value, GameSource.EPIC, listOf("epic"), 2L)

        dao.replaceCompletedSnapshot(scopeA.value, GameSource.GOG, emptyList(), 3L)

        assertTrue(dao.getCompletedStableSourceIds(scopeA.value, GameSource.GOG).isEmpty())
        assertEquals(
            listOf("epic"),
            dao.getCompletedStableSourceIds(scopeA.value, GameSource.EPIC),
        )
    }

    @Test
    fun sameStableIdIsIndependentAcrossAccountScopes() = runTest {
        dao.replaceCompletedSnapshot(scopeA.value, GameSource.AMAZON, listOf("shared"), 1L)
        dao.replaceCompletedSnapshot(scopeB.value, GameSource.AMAZON, listOf("shared"), 2L)

        assertTrue(dao.isPresent(scopeA.value, GameSource.AMAZON, "shared"))
        assertTrue(dao.isPresent(scopeB.value, GameSource.AMAZON, "shared"))
        assertEquals(1L, dao.getCompletedHeader(scopeA.value, GameSource.AMAZON)?.completedAt)
        assertEquals(2L, dao.getCompletedHeader(scopeB.value, GameSource.AMAZON)?.completedAt)
    }

    @Test
    fun failedReplacementPreservesPreviousSnapshot() = runTest {
        dao.replaceCompletedSnapshot(scopeA.value, GameSource.GOG, listOf("old"), 1L)
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_owned_copy_presence
            BEFORE INSERT ON owned_copy_presence
            WHEN NEW.stable_source_id = 'fail'
            BEGIN
                SELECT RAISE(ABORT, 'injected failure');
            END
            """.trimIndent(),
        )

        val result = runCatching {
            dao.replaceCompletedSnapshot(scopeA.value, GameSource.GOG, listOf("fail"), 2L)
        }

        assertTrue(result.isFailure)
        val snapshot = dao.getCompletedSnapshot(scopeA.value, GameSource.GOG)
        assertEquals(1L, snapshot?.completedAt)
        assertEquals(listOf("old"), snapshot?.stableSourceIds)
    }

    @Test
    fun sourceInvalidationEmitsAfterReplacement() = runTest {
        val initial = CompletableDeferred<List<OwnedCopySyncEntity>>()
        val emissions = async(start = CoroutineStart.UNDISPATCHED) {
            dao.observeSourceHeaders(GameSource.GOG)
                .onEach { headers ->
                    if (!initial.isCompleted) initial.complete(headers)
                }
                .take(2)
                .toList()
        }
        assertTrue(initial.await().isEmpty())

        dao.replaceCompletedSnapshot(scopeA.value, GameSource.GOG, listOf("owned"), 1L)

        assertEquals(
            listOf(scopeA.value),
            emissions.await().last().map { it.accountScope },
        )
        assertNull(dao.getCompletedSnapshot(scopeB.value, GameSource.GOG))
    }
}
