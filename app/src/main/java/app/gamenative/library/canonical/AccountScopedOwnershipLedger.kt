package app.gamenative.library.canonical

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.db.dao.OwnedCopyLedgerDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

data class MaterializedOwnedCopySnapshot<T>(
    val value: T,
    val stableSourceIds: Collection<String>,
    val resolvedSourceIds: Map<String, String> = emptyMap(),
)

enum class OwnedCopySyncFailure {
    ACCOUNT_SCOPE_UNAVAILABLE,
    ACCOUNT_SCOPE_CHANGED,
    MATERIALIZATION_FAILED,
    LEDGER_COMMIT_FAILED,
}

private class OwnedCopySyncException(failure: OwnedCopySyncFailure) :
    IllegalStateException(failure.name)

@Singleton
class AccountScopedOwnershipLedger @Inject constructor(
    private val accountScopeProvider: AccountScopeProvider,
    private val ownedCopyLedgerDao: OwnedCopyLedgerDao,
) {
    suspend fun <T> runCompleteSnapshot(
        source: GameSource,
        materialize: suspend () -> MaterializedOwnedCopySnapshot<T>,
    ): Result<T> {
        val capturedScope = currentScopeOrNull(source)
            ?: return failure(OwnedCopySyncFailure.ACCOUNT_SCOPE_UNAVAILABLE)
        val capturedGeneration = AccountScopeInvalidations.generation(source)

        val snapshot = try {
            materialize()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return failure(OwnedCopySyncFailure.MATERIALIZATION_FAILED)
        }

        val currentScope = currentScopeOrNull(source)
        if (
            currentScope != capturedScope ||
            AccountScopeInvalidations.generation(source) != capturedGeneration
        ) {
            return failure(OwnedCopySyncFailure.ACCOUNT_SCOPE_CHANGED)
        }

        return try {
            ownedCopyLedgerDao.replaceCompletedSnapshot(
                accountScope = capturedScope.value,
                source = source,
                stableSourceIds = snapshot.stableSourceIds,
                completedAt = System.currentTimeMillis(),
                resolvedSourceIds = snapshot.resolvedSourceIds,
            )
            Result.success(snapshot.value)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failure(OwnedCopySyncFailure.LEDGER_COMMIT_FAILED)
        }
    }

    private suspend fun currentScopeOrNull(source: GameSource): AccountScope? = try {
        accountScopeProvider.current(source)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun <T> failure(reason: OwnedCopySyncFailure): Result<T> =
        Result.failure(OwnedCopySyncException(reason))
}
