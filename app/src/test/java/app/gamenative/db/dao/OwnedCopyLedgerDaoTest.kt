package app.gamenative.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.OwnedCopyPresenceEntity
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
    private val amazonProductId =
        "amzn1.adg.product.11111111-1111-1111-1111-111111111111"

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
            stableSourceIds = listOf("2", "1", "2"),
            completedAt = 17L,
            lifecycleGeneration = 0L,
        )

        val snapshot = dao.getCompletedSnapshot(scopeA.value, GameSource.GOG)

        assertNotNull(snapshot)
        assertEquals(17L, snapshot?.completedAt)
        assertEquals(0L, snapshot?.lifecycleGeneration)
        assertEquals(listOf("1", "2"), snapshot?.stableSourceIds)
        assertTrue(dao.isPresent(scopeA.value, GameSource.GOG, "1"))
        assertFalse(dao.isPresent(scopeA.value, GameSource.GOG, "missing"))
    }

    @Test
    fun malformedIdsRejectReplacementWithoutChangingPriorSnapshot() = runTest {
        dao.replaceCompletedSnapshot(scopeA.value, GameSource.GOG, listOf("1"), 1L, 0L)

        listOf(listOf(" 2 "), listOf(""), listOf("01"), listOf("gog-2")).forEach { ids ->
            val result = runCatching {
                dao.replaceCompletedSnapshot(
                    accountScope = scopeA.value,
                    source = GameSource.GOG,
                    stableSourceIds = ids,
                    completedAt = 2L,
                    lifecycleGeneration = 0L,
                )
            }

            assertTrue(result.isFailure)
            assertEquals(listOf("1"), dao.getCompletedStableSourceIds(scopeA.value, GameSource.GOG))
        }
    }

    @Test
    fun malformedAmazonIdRejectsReplacementWithoutChangingPriorSnapshot() = runTest {
        dao.replaceCompletedSnapshot(
            accountScope = scopeA.value,
            source = GameSource.AMAZON,
            stableSourceIds = listOf(amazonProductId),
            completedAt = 1L,
            lifecycleGeneration = 0L,
            resolvedSourceIds = mapOf(amazonProductId to "entitlement-a"),
        )

        val result = runCatching {
            dao.replaceCompletedSnapshot(
                accountScope = scopeA.value,
                source = GameSource.AMAZON,
                stableSourceIds = listOf("11111111-1111-1111-1111-111111111111"),
                completedAt = 2L,
                lifecycleGeneration = 0L,
            )
        }

        assertTrue(result.isFailure)
        assertEquals(
            listOf(amazonProductId),
            dao.getCompletedStableSourceIds(scopeA.value, GameSource.AMAZON),
        )
    }

    @Test
    fun resolvedSourceIdIsStoredWithItsAccountScopedPresence() = runTest {
        dao.replaceCompletedSnapshot(
            accountScope = scopeA.value,
            source = GameSource.AMAZON,
            stableSourceIds = listOf(amazonProductId),
            completedAt = 1L,
            lifecycleGeneration = 0L,
            resolvedSourceIds = mapOf(amazonProductId to "entitlement-a"),
        )
        dao.replaceCompletedSnapshot(
            accountScope = scopeB.value,
            source = GameSource.AMAZON,
            stableSourceIds = listOf(amazonProductId),
            completedAt = 2L,
            lifecycleGeneration = 0L,
            resolvedSourceIds = mapOf(amazonProductId to "entitlement-b"),
        )

        assertEquals(
            "entitlement-a",
            dao.getPresence(scopeA.value, GameSource.AMAZON, amazonProductId)?.resolvedSourceId,
        )
        assertEquals(
            "entitlement-b",
            dao.getPresence(scopeB.value, GameSource.AMAZON, amazonProductId)?.resolvedSourceId,
        )
    }

    @Test
    fun completeEmptyReplacementRetainsHeader() = runTest {
        dao.replaceCompletedSnapshot(scopeA.value, GameSource.EPIC, listOf("old"), 1L, 0L)

        dao.replaceCompletedSnapshot(scopeA.value, GameSource.EPIC, emptyList(), 2L, 0L)

        val snapshot = dao.getCompletedSnapshot(scopeA.value, GameSource.EPIC)
        assertNotNull(snapshot)
        assertEquals(2L, snapshot?.completedAt)
        assertTrue(snapshot?.stableSourceIds?.isEmpty() == true)
    }

    @Test
    fun replacementDoesNotChangeAnotherSourceForTheSameAccount() = runTest {
        dao.replaceCompletedSnapshot(scopeA.value, GameSource.GOG, listOf("1"), 1L, 0L)
        dao.replaceCompletedSnapshot(scopeA.value, GameSource.EPIC, listOf("epic"), 2L, 0L)

        dao.replaceCompletedSnapshot(scopeA.value, GameSource.GOG, emptyList(), 3L, 0L)

        assertTrue(dao.getCompletedStableSourceIds(scopeA.value, GameSource.GOG).isEmpty())
        assertEquals(
            listOf("epic"),
            dao.getCompletedStableSourceIds(scopeA.value, GameSource.EPIC),
        )
    }

    @Test
    fun sameStableIdIsIndependentAcrossAccountScopes() = runTest {
        dao.replaceCompletedSnapshot(
            scopeA.value,
            GameSource.AMAZON,
            listOf(amazonProductId),
            1L,
            0L,
        )
        dao.replaceCompletedSnapshot(
            scopeB.value,
            GameSource.AMAZON,
            listOf(amazonProductId),
            2L,
            0L,
        )

        assertTrue(dao.isPresent(scopeA.value, GameSource.AMAZON, amazonProductId))
        assertTrue(dao.isPresent(scopeB.value, GameSource.AMAZON, amazonProductId))
        assertEquals(1L, dao.getCompletedHeader(scopeA.value, GameSource.AMAZON)?.completedAt)
        assertEquals(2L, dao.getCompletedHeader(scopeB.value, GameSource.AMAZON)?.completedAt)
    }

    @Test
    fun lifecycleReadsRequireExactHeaderGeneration() = runTest {
        dao.replaceCompletedSnapshot(
            accountScope = scopeA.value,
            source = GameSource.GOG,
            stableSourceIds = listOf("1"),
            completedAt = 1L,
            lifecycleGeneration = 2L,
        )

        assertNull(dao.getCompletedSnapshotForLifecycle(scopeA.value, GameSource.GOG, 1L))
        assertFalse(
            dao.isPresentForLifecycle(scopeA.value, GameSource.GOG, "1", 1L),
        )
        assertEquals(
            listOf("1"),
            dao.getCompletedSnapshotForLifecycle(scopeA.value, GameSource.GOG, 2L)
                ?.stableSourceIds,
        )
        assertTrue(
            dao.isPresentForLifecycle(scopeA.value, GameSource.GOG, "1", 2L),
        )
    }

    @Test
    fun migratedNegativeGenerationIsUnreadableUntilFreshSync() = runTest {
        dao.upsertCompletedHeader(
            OwnedCopySyncEntity(
                accountScope = scopeA.value,
                source = GameSource.GOG,
                completedAt = 1L,
                lifecycleGeneration = -1L,
            ),
        )
        dao.insertPresenceRows(
            listOf(
                OwnedCopyPresenceEntity(
                    accountScope = scopeA.value,
                    source = GameSource.GOG,
                    stableSourceId = "old",
                ),
            ),
        )

        assertEquals(listOf("old"), dao.getCompletedSnapshot(scopeA.value, GameSource.GOG)?.stableSourceIds)
        assertNull(dao.getCompletedSnapshotForLifecycle(scopeA.value, GameSource.GOG, 0L))
        assertFalse(dao.isPresentForLifecycle(scopeA.value, GameSource.GOG, "old", 0L))
    }

    @Test
    fun staleLifecycleWriterCannotReplaceNewerSnapshot() = runTest {
        dao.replaceCompletedSnapshot(
            accountScope = scopeA.value,
            source = GameSource.GOG,
            stableSourceIds = listOf("2"),
            completedAt = 2L,
            lifecycleGeneration = 2L,
        )

        val replaced = dao.replaceCompletedSnapshot(
            accountScope = scopeA.value,
            source = GameSource.GOG,
            stableSourceIds = listOf("1"),
            completedAt = 3L,
            lifecycleGeneration = 1L,
        )

        assertFalse(replaced)
        val snapshot = dao.getCompletedSnapshot(scopeA.value, GameSource.GOG)
        assertEquals(2L, snapshot?.lifecycleGeneration)
        assertEquals(listOf("2"), snapshot?.stableSourceIds)
    }

    @Test
    fun failedReplacementPreservesPreviousSnapshot() = runTest {
        dao.replaceCompletedSnapshot(scopeA.value, GameSource.GOG, listOf("1"), 1L, 0L)
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_owned_copy_presence
            BEFORE INSERT ON owned_copy_presence
            WHEN NEW.stable_source_id = '2'
            BEGIN
                SELECT RAISE(ABORT, 'injected failure');
            END
            """.trimIndent(),
        )

        val result = runCatching {
            dao.replaceCompletedSnapshot(scopeA.value, GameSource.GOG, listOf("2"), 2L, 0L)
        }

        assertTrue(result.isFailure)
        val snapshot = dao.getCompletedSnapshot(scopeA.value, GameSource.GOG)
        assertEquals(1L, snapshot?.completedAt)
        assertEquals(listOf("1"), snapshot?.stableSourceIds)
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

        dao.replaceCompletedSnapshot(scopeA.value, GameSource.GOG, listOf("1"), 1L, 0L)

        assertEquals(
            listOf(scopeA.value),
            emissions.await().last().map { it.accountScope },
        )
        assertNull(dao.getCompletedSnapshot(scopeB.value, GameSource.GOG))
    }
}
