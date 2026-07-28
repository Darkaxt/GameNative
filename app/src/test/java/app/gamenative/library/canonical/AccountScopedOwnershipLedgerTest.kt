package app.gamenative.library.canonical

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.db.PluviaDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private val scopeA = AccountScope.parse("a".repeat(64))
    private val scopeB = AccountScope.parse("b".repeat(64))

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun materializationFailureLeavesPriorLedgerAndSanitizesFailure() = runTest {
        val dao = database.ownedCopyLedgerDao()
        dao.replaceCompletedSnapshot(scopeA.value, GameSource.GOG, listOf("old"), 1L)
        val ledger = AccountScopedOwnershipLedger(fixedScopes(scopeA), dao)

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
        dao.replaceCompletedSnapshot(scopeA.value, GameSource.EPIC, listOf("old"), 1L)
        val scopes = SequenceScopeProvider(listOf(scopeA, scopeB))
        val ledger = AccountScopedOwnershipLedger(scopes, dao)

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
        dao.replaceCompletedSnapshot(scopeA.value, GameSource.GOG, listOf("old"), 1L)
        val ledger = AccountScopedOwnershipLedger(fixedScopes(scopeA), dao)

        val result = ledger.runCompleteSnapshot(GameSource.GOG) {
            AccountScopeInvalidations.notifyChanged(GameSource.GOG)
            AccountScopeInvalidations.notifyChanged(GameSource.GOG)
            MaterializedOwnedCopySnapshot(value = 1, stableSourceIds = listOf("wrong-account"))
        }

        assertTrue(result.isFailure)
        assertEquals(OwnedCopySyncFailure.ACCOUNT_SCOPE_CHANGED.name, result.exceptionOrNull()?.message)
        assertEquals(listOf("old"), dao.getCompletedStableSourceIds(scopeA.value, GameSource.GOG))
    }

    @Test
    fun verifiedEmptyTraversalCommitsCompletedEmptySnapshot() = runTest {
        val dao = database.ownedCopyLedgerDao()
        val ledger = AccountScopedOwnershipLedger(fixedScopes(scopeA), dao)

        val result = ledger.runCompleteSnapshot(GameSource.AMAZON) {
            MaterializedOwnedCopySnapshot(value = 0, stableSourceIds = emptyList())
        }

        assertEquals(0, result.getOrThrow())
        assertTrue(dao.getCompletedSnapshot(scopeA.value, GameSource.AMAZON)?.stableSourceIds?.isEmpty() == true)
    }

    private fun fixedScopes(scope: AccountScope): AccountScopeProvider = object : AccountScopeProvider {
        override suspend fun current(source: GameSource): AccountScope = scope
    }

    private class SequenceScopeProvider(scopes: List<AccountScope?>) : AccountScopeProvider {
        private val values = ArrayDeque(scopes)
        override suspend fun current(source: GameSource): AccountScope? = values.removeFirstOrNull()
    }
}
