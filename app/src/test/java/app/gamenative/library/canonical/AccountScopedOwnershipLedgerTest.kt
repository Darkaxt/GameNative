package app.gamenative.library.canonical

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.db.PluviaDatabase
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class AccountScopedOwnershipLedgerTest {
    private lateinit var database: PluviaDatabase
    private lateinit var lifecycleState: InMemoryAccountLifecycleState
    private val scopeA = AccountScope.parse("a".repeat(64))
    private val scopeB = AccountScope.parse("b".repeat(64))

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        lifecycleState = InMemoryAccountLifecycleState()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun materializationFailureLeavesPriorLedgerAndSanitizesFailure() = runTest {
        val dao = database.ownedCopyLedgerDao()
        dao.replaceCompletedSnapshot(scopeA.value, GameSource.GOG, listOf("old"), 1L, 0L)
        val ledger = AccountScopedOwnershipLedger(fixedScopes(scopeA), dao, lifecycleState)

        val result: Result<Int> = ledger.runCompleteSnapshot(GameSource.GOG) {
            error("private account id, title, path, URL, token, payload, and user text")
        }

        assertTrue(result.isFailure)
        assertEquals(OwnedCopySyncFailure.MATERIALIZATION_FAILED.name, result.exceptionOrNull()?.message)
        assertFalse(result.exceptionOrNull().toString().contains("private account"))
        assertEquals(listOf("old"), dao.getCompletedStableSourceIds(scopeA.value, GameSource.GOG))
    }

    @Test
    fun accountSwitchBeforeCommitLeavesPriorLedger() = runTest {
        val dao = database.ownedCopyLedgerDao()
        dao.replaceCompletedSnapshot(scopeA.value, GameSource.EPIC, listOf("old"), 1L, 0L)
        val scopes = SequenceScopeProvider(listOf(scopeA, scopeB))
        val ledger = AccountScopedOwnershipLedger(scopes, dao, lifecycleState)

        val result = ledger.runCompleteSnapshot(GameSource.EPIC) {
            MaterializedOwnedCopySnapshot(value = 3, stableSourceIds = listOf("new"))
        }

        assertTrue(result.isFailure)
        assertEquals(OwnedCopySyncFailure.ACCOUNT_SCOPE_CHANGED.name, result.exceptionOrNull()?.message)
        assertEquals(listOf("old"), dao.getCompletedStableSourceIds(scopeA.value, GameSource.EPIC))
        assertEquals(emptyList<String>(), dao.getCompletedStableSourceIds(scopeB.value, GameSource.EPIC))
    }

    @Test
    fun accountLifecycleAbaChangeBeforeCommitLeavesPriorLedger() = runTest {
        val dao = database.ownedCopyLedgerDao()
        dao.replaceCompletedSnapshot(scopeA.value, GameSource.GOG, listOf("old"), 1L, 0L)
        val ledger = AccountScopedOwnershipLedger(fixedScopes(scopeA), dao, lifecycleState)

        val result = ledger.runCompleteSnapshot(GameSource.GOG) {
            lifecycleState.advanceGeneration(GameSource.GOG)
            lifecycleState.advanceGeneration(GameSource.GOG)
            MaterializedOwnedCopySnapshot(value = 1, stableSourceIds = listOf("wrong-account"))
        }

        assertTrue(result.isFailure)
        assertEquals(OwnedCopySyncFailure.ACCOUNT_SCOPE_CHANGED.name, result.exceptionOrNull()?.message)
        assertEquals(listOf("old"), dao.getCompletedStableSourceIds(scopeA.value, GameSource.GOG))
    }

    @Test
    fun completedAccountALedgerIsNotReusableAfterAccountBAndReturnToA() = runTest {
        val dao = database.ownedCopyLedgerDao()
        val scopes = MutableScopeProvider(scopeA)
        val ledger = AccountScopedOwnershipLedger(scopes, dao, lifecycleState)

        assertEquals(
            1,
            ledger.runCompleteSnapshot(GameSource.GOG) {
                MaterializedOwnedCopySnapshot(value = 1, stableSourceIds = listOf("owned-a"))
            }.getOrThrow(),
        )
        assertEquals(
            0L,
            dao.getCompletedSnapshotForLifecycle(scopeA.value, GameSource.GOG, 0L)
                ?.lifecycleGeneration,
        )

        lifecycleState.advanceGeneration(GameSource.GOG)
        scopes.value = scopeB
        ledger.runCompleteSnapshot(GameSource.GOG) {
            MaterializedOwnedCopySnapshot(value = 1, stableSourceIds = listOf("owned-b"))
        }.getOrThrow()

        lifecycleState.advanceGeneration(GameSource.GOG)
        scopes.value = scopeA

        assertNull(dao.getCompletedSnapshotForLifecycle(scopeA.value, GameSource.GOG, 2L))
        assertEquals(
            listOf("owned-a"),
            dao.getCompletedSnapshot(scopeA.value, GameSource.GOG)?.stableSourceIds,
        )
    }

    @Test
    fun verifiedEmptyTraversalCommitsCompletedEmptySnapshot() = runTest {
        val dao = database.ownedCopyLedgerDao()
        val ledger = AccountScopedOwnershipLedger(fixedScopes(scopeA), dao, lifecycleState)

        val result = ledger.runCompleteSnapshot(GameSource.AMAZON) {
            MaterializedOwnedCopySnapshot(value = 0, stableSourceIds = emptyList())
        }

        assertEquals(0, result.getOrThrow())
        assertTrue(dao.getCompletedSnapshot(scopeA.value, GameSource.AMAZON)?.stableSourceIds?.isEmpty() == true)
    }

    @Test
    fun completedNonSteamLedgersPublishExactLifecycleReadiness() = runTest {
        val ledger = AccountScopedOwnershipLedger(
            fixedScopes(scopeA),
            database.ownedCopyLedgerDao(),
            lifecycleState,
        )

        listOf(GameSource.GOG, GameSource.EPIC, GameSource.AMAZON).forEach { source ->
            val generation = lifecycleState.advanceGeneration(source)
            assertNull(lifecycleState.readyGeneration(source))

            val result = ledger.runCompleteSnapshot(source) {
                MaterializedOwnedCopySnapshot(
                    value = source,
                    stableSourceIds = listOf(source.name.lowercase()),
                )
            }

            assertEquals(source, result.getOrThrow())
            assertEquals(generation, lifecycleState.readyGeneration(source))
        }
    }

    @Test
    fun completedNonSteamLedgersPublishReadinessInvalidation() = runTest {
        val productionLifecycle = InMemoryAccountLifecycleState()
        AccountScopeInvalidations.install(productionLifecycle)
        try {
            val invalidation = async(start = CoroutineStart.UNDISPATCHED) {
                AccountScopeInvalidations.forSource(GameSource.GOG).first()
            }
            val ledger = AccountScopedOwnershipLedger(
                fixedScopes(scopeA),
                database.ownedCopyLedgerDao(),
                AccountScopeInvalidations,
            )

            ledger.runCompleteSnapshot(GameSource.GOG) {
                MaterializedOwnedCopySnapshot(value = Unit, stableSourceIds = listOf("owned"))
            }.getOrThrow()

            assertEquals(Unit, invalidation.await())
        } finally {
            AccountScopeInvalidations.install(InMemoryAccountLifecycleState())
        }
    }

    @Test
    fun readinessRaceRejectsCommittedSnapshotAndStaleGenerationCannotBecomeReady() = runTest {
        val delegate = InMemoryAccountLifecycleState()
        val racingLifecycle = object : AccountLifecycleState by delegate {
            override fun markReady(source: GameSource, expectedGeneration: Long): Boolean {
                delegate.advanceGeneration(source)
                return delegate.markReady(source, expectedGeneration)
            }
        }
        val ledger = AccountScopedOwnershipLedger(
            fixedScopes(scopeA),
            database.ownedCopyLedgerDao(),
            racingLifecycle,
        )

        val result = ledger.runCompleteSnapshot(GameSource.AMAZON) {
            MaterializedOwnedCopySnapshot(value = 1, stableSourceIds = listOf("owned"))
        }

        assertTrue(result.isFailure)
        assertEquals(OwnedCopySyncFailure.ACCOUNT_SCOPE_CHANGED.name, result.exceptionOrNull()?.message)
        assertNull(delegate.readyGeneration(GameSource.AMAZON))
        assertEquals(1L, delegate.generation(GameSource.AMAZON))
    }

    @Test
    fun steamLedgerDoesNotPublishLicenseReadiness() = runTest {
        val ledger = AccountScopedOwnershipLedger(
            fixedScopes(scopeA),
            database.ownedCopyLedgerDao(),
            lifecycleState,
        )

        ledger.runCompleteSnapshot(GameSource.STEAM) {
            MaterializedOwnedCopySnapshot(value = Unit, stableSourceIds = listOf("42"))
        }.getOrThrow()

        assertNull(lifecycleState.readyGeneration(GameSource.STEAM))
    }

    private fun fixedScopes(scope: AccountScope): AccountScopeProvider = object : AccountScopeProvider {
        override suspend fun current(source: GameSource): AccountScope = scope
    }

    private class MutableScopeProvider(
        var value: AccountScope,
    ) : AccountScopeProvider {
        override suspend fun current(source: GameSource): AccountScope = value
    }

    private class SequenceScopeProvider(scopes: List<AccountScope?>) : AccountScopeProvider {
        private val values = ArrayDeque(scopes)
        override suspend fun current(source: GameSource): AccountScope? = values.removeFirstOrNull()
    }
}
