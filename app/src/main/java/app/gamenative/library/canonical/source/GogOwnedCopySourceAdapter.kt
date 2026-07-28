package app.gamenative.library.canonical.source

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.CanonicalNormalization
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.db.dao.OwnedCopyLedgerDao
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
) : OwnedCopySourceAdapter {
    override val source: GameSource = GameSource.GOG

    override fun invalidations(): Flow<Unit> = merge(
        gogGameDao.getAll().map { Unit },
        ownedCopyLedgerDao.observeSourceHeaders(source).map { Unit },
        AccountScopeInvalidations.forSource(source),
    )

    override suspend fun snapshot(): SourceProjectionBatch {
        val accountScope = try {
            accountScopeProvider.current(source)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return sourceReadFailed(source, null, error)
        } ?: return missingAccountScope(source)
        val accountGeneration = AccountScopeInvalidations.generation(source)

        return try {
            val ledger = ownedCopyLedgerDao.getCompletedSnapshot(accountScope.value, source)
                ?: return presenceLedgerNotReady(source, accountScope)
            val rowsById = if (ledger.stableSourceIds.isEmpty()) {
                emptyMap()
            } else {
                gogGameDao.getAllAsList()
                    .asSequence()
                    .filter { it.id.isNotBlank() }
                    .associateBy { it.id }
            }
            var missingRow = false
            val copies = ledger.stableSourceIds.mapNotNull { stableSourceId ->
                val game = rowsById[stableSourceId]
                if (game == null) {
                    missingRow = true
                    return@mapNotNull null
                }
                OwnedCopyProjection(
                    key = OwnedCopyKey(accountScope, source, stableSourceId),
                    displayName = game.title,
                    developer = game.developer,
                    releaseYear = CanonicalNormalization.releaseYear(game.releaseDate),
                    appType = CanonicalNormalization.appType(game.type),
                    genreKeys = sourceQualifiedKeys("gog", game.genres),
                )
            }
            if (
                accountScopeProvider.current(source) != accountScope ||
                AccountScopeInvalidations.generation(source) != accountGeneration
            ) {
                accountScopeChanged(source)
            } else {
                sourceBatch(
                    source = source,
                    accountScope = accountScope,
                    copies = copies,
                    partialReason = SnapshotReason.MISSING_MATERIALIZED_ROW.takeIf { missingRow },
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            sourceReadFailed(source, accountScope, error)
        }
    }

    override suspend fun resolve(key: OwnedCopyKey): SourceOwnedCopyReference? {
        if (key.source != source) return null
        val currentScope = accountScopeProvider.current(source) ?: return null
        val accountGeneration = AccountScopeInvalidations.generation(source)
        if (key.accountScope != currentScope) return null
        if (!ownedCopyLedgerDao.isPresent(currentScope.value, source, key.stableSourceId)) return null
        val game = gogGameDao.getById(key.stableSourceId)?.takeUnless { it.exclude } ?: return null
        if (
            accountScopeProvider.current(source) != currentScope ||
            AccountScopeInvalidations.generation(source) != accountGeneration
        ) {
            return null
        }
        return SourceOwnedCopyReference.Gog(key, game.id)
    }
}
