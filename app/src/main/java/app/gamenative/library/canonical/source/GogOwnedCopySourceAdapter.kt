package app.gamenative.library.canonical.source

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.CanonicalNormalization
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.db.dao.OwnedCopyLedgerDao
import app.gamenative.library.canonical.AccountLifecycleState
import app.gamenative.library.canonical.AccountScopeInvalidations
import app.gamenative.library.canonical.AccountScopeProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

@Singleton
class GogOwnedCopySourceAdapter @Inject constructor(
    private val gogGameDao: GOGGameDao,
    private val accountScopeProvider: AccountScopeProvider,
    private val ownedCopyLedgerDao: OwnedCopyLedgerDao,
    private val accountLifecycleState: AccountLifecycleState = AccountScopeInvalidations,
) : OwnedCopySourceAdapter {
    override val source: GameSource = GameSource.GOG

    override fun invalidations(): Flow<Unit> = merge(
        gogGameDao.getAll().map { Unit },
        ownedCopyLedgerDao.observeSourceHeaders(source).map { Unit },
        AccountScopeInvalidations.forSource(source),
    )

    override suspend fun snapshot(): SourceProjectionBatch {
        val accountGeneration = accountLifecycleState.generation(source)
        val accountScope = try {
            accountScopeProvider.current(source)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return sourceReadFailed(source, null, error, accountGeneration)
        } ?: return missingAccountScope(source, accountGeneration)

        return try {
            val ledger = ownedCopyLedgerDao.getCompletedSnapshotForLifecycle(
                accountScope = accountScope.value,
                source = source,
                lifecycleGeneration = accountGeneration,
            ) ?: return presenceLedgerNotReady(source, accountScope, accountGeneration)
            val rowsById = if (ledger.stableSourceIds.isEmpty()) {
                emptyMap()
            } else {
                gogGameDao.getAllAsList()
                    .asSequence()
                    .filter { it.id.isNotBlank() }
                    .associateBy { it.id }
            }
            var malformedId = false
            var missingRow = false
            val copies = ledger.stableSourceIds.mapNotNull { stableSourceId ->
                val key = OwnedCopyKey.createOrNull(accountScope, source, stableSourceId)
                if (key == null) {
                    malformedId = true
                    return@mapNotNull null
                }
                val game = rowsById[stableSourceId]
                if (game == null) {
                    missingRow = true
                    return@mapNotNull null
                }
                OwnedCopyProjection(
                    key = key,
                    displayName = game.title,
                    developer = game.developer,
                    releaseYear = CanonicalNormalization.releaseYear(game.releaseDate),
                    appType = CanonicalNormalization.appType(game.type),
                    genreKeys = sourceQualifiedKeys("gog", game.genres),
                )
            }
            if (
                !accountScopeProvider.isAccountScopeUnchanged(
                    source,
                    accountScope,
                    accountGeneration,
                    accountLifecycleState,
                )
            ) {
                accountScopeChanged(source, accountGeneration)
            } else {
                sourceBatch(
                    source = source,
                    accountScope = accountScope,
                    copies = copies,
                    partialReason = when {
                        malformedId -> SnapshotReason.MALFORMED_SOURCE_ID
                        missingRow -> SnapshotReason.MISSING_MATERIALIZED_ROW
                        else -> null
                    },
                    lifecycleGeneration = accountGeneration,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            sourceReadFailed(source, accountScope, error, accountGeneration)
        }
    }

    override suspend fun resolve(key: OwnedCopyKey): SourceOwnedCopyReference? {
        if (key.source != source) return null
        val currentScope = accountScopeProvider.current(source) ?: return null
        val accountGeneration = accountLifecycleState.generation(source)
        if (key.accountScope != currentScope) return null
        if (
            !ownedCopyLedgerDao.isPresentForLifecycle(
                accountScope = currentScope.value,
                source = source,
                stableSourceId = key.stableSourceId,
                lifecycleGeneration = accountGeneration,
            )
        ) {
            return null
        }
        val game = gogGameDao.getById(key.stableSourceId)?.takeUnless { it.exclude } ?: return null
        if (
            !accountScopeProvider.isAccountScopeUnchanged(
                source,
                currentScope,
                accountGeneration,
                accountLifecycleState,
            )
        ) {
            return null
        }
        return SourceOwnedCopyReference.Gog(key, game.id)
    }
}
