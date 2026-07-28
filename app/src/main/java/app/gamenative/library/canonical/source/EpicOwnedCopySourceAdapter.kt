package app.gamenative.library.canonical.source

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.CanonicalNormalization
import app.gamenative.data.canonical.EpicStableSourceId
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.db.dao.EpicGameDao
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
class EpicOwnedCopySourceAdapter @Inject constructor(
    private val epicGameDao: EpicGameDao,
    private val accountScopeProvider: AccountScopeProvider,
    private val ownedCopyLedgerDao: OwnedCopyLedgerDao,
) : OwnedCopySourceAdapter {
    override val source: GameSource = GameSource.EPIC

    override fun invalidations(): Flow<Unit> = merge(
        epicGameDao.getAll().map { Unit },
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
        val (namespace, catalogId) = try {
            EpicStableSourceId.decode(key.stableSourceId)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (!ownedCopyLedgerDao.isPresent(currentScope.value, source, key.stableSourceId)) return null
        val game = epicGameDao.getByProviderIdentity(namespace, catalogId)
            ?.takeIf(::isVisibleInAllLibrary)
            ?: return null
        if (
            accountScopeProvider.current(source) != currentScope ||
            AccountScopeInvalidations.generation(source) != accountGeneration
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

    private fun isVisibleInAllLibrary(game: app.gamenative.data.EpicGame): Boolean =
        !game.isDLC && game.namespace != "ue" && game.namespace != UNREAL_ENGINE_NAMESPACE

    private companion object {
        const val UNREAL_ENGINE_NAMESPACE = "89efe5924d3d467c839449ab6ab52e7f"
    }
}
