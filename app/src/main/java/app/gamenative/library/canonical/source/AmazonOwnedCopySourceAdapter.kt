package app.gamenative.library.canonical.source

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalNormalization
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.db.dao.AmazonGameDao
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
class AmazonOwnedCopySourceAdapter @Inject constructor(
    private val amazonGameDao: AmazonGameDao,
    private val accountScopeProvider: AccountScopeProvider,
    private val ownedCopyLedgerDao: OwnedCopyLedgerDao,
) : OwnedCopySourceAdapter {
    override val source: GameSource = GameSource.AMAZON

    override fun invalidations(): Flow<Unit> = merge(
        amazonGameDao.getAll().map { Unit },
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
                amazonGameDao.getAllAsList()
                    .asSequence()
                    .filter { it.productId.isNotBlank() }
                    .associateBy { it.productId }
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
                    appType = CanonicalAppType.GAME,
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
        val presence = ownedCopyLedgerDao.getPresence(
            currentScope.value,
            source,
            key.stableSourceId,
        ) ?: return null
        val entitlementId = presence.resolvedSourceId ?: return null
        val game = amazonGameDao.getByProductId(key.stableSourceId) ?: return null
        if (
            accountScopeProvider.current(source) != currentScope ||
            AccountScopeInvalidations.generation(source) != accountGeneration
        ) {
            return null
        }
        return SourceOwnedCopyReference.Amazon(
            key = key,
            localRowId = game.appId,
            productId = game.productId,
            entitlementId = entitlementId,
        )
    }
}
