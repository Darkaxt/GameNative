package app.gamenative.library.canonical.source

import app.gamenative.data.EpicGame
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.CanonicalNormalization
import app.gamenative.data.canonical.EpicStableSourceId
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.db.dao.EpicGameDao
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
class EpicOwnedCopySourceAdapter @Inject constructor(
    private val epicGameDao: EpicGameDao,
    private val accountScopeProvider: AccountScopeProvider,
    private val ownedCopyLedgerDao: OwnedCopyLedgerDao,
    private val accountLifecycleState: AccountLifecycleState = AccountScopeInvalidations,
) : OwnedCopySourceAdapter {
    override val source: GameSource = GameSource.EPIC

    override fun invalidations(): Flow<Unit> = merge(
        epicGameDao.getAll().map { Unit },
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
                epicGameDao.getAllAsList().mapNotNull { game ->
                    runCatching {
                        EpicStableSourceId.encode(game.namespace, game.catalogId) to game
                    }.getOrNull()
                }.toMap()
            }
            var missingRow = false
            val copies = ledger.stableSourceIds.mapNotNull { stableSourceId ->
                val game = rowsById[stableSourceId]
                if (game == null) {
                    missingRow = true
                    return@mapNotNull null
                }
                if (!isVisibleInAllLibrary(game)) return@mapNotNull null
                OwnedCopyProjection(
                    key = OwnedCopyKey(accountScope, source, stableSourceId),
                    displayName = game.title,
                    developer = game.developer,
                    releaseYear = CanonicalNormalization.releaseYear(game.releaseDate),
                    appType = CanonicalNormalization.appType(game.type),
                    genreKeys = sourceQualifiedKeys("epic", game.genres),
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
                    partialReason = SnapshotReason.MISSING_MATERIALIZED_ROW.takeIf { missingRow },
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
        val (namespace, catalogId) = try {
            EpicStableSourceId.decode(key.stableSourceId)
        } catch (_: IllegalArgumentException) {
            return null
        }
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
        val game = epicGameDao.getByProviderIdentity(namespace, catalogId)
            ?.takeIf(::isVisibleInAllLibrary)
            ?: return null
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
        return SourceOwnedCopyReference.Epic(
            key = key,
            localRowId = game.id,
            namespace = namespace,
            catalogId = catalogId,
        )
    }

    private fun isVisibleInAllLibrary(game: EpicGame): Boolean =
        !game.isDLC && game.namespace != "ue" && game.namespace != UNREAL_ENGINE_NAMESPACE

    private companion object {
        const val UNREAL_ENGINE_NAMESPACE = "89efe5924d3d467c839449ab6ab52e7f"
    }
}
