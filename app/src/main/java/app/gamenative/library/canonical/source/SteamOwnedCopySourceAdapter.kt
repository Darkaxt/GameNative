package app.gamenative.library.canonical.source

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.CanonicalNormalization
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.db.dao.SteamAppDao
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
class SteamOwnedCopySourceAdapter @Inject constructor(
    private val steamAppDao: SteamAppDao,
    private val accountScopeProvider: AccountScopeProvider,
    private val accountLifecycleState: AccountLifecycleState = AccountScopeInvalidations,
) : OwnedCopySourceAdapter {
    override val source: GameSource = GameSource.STEAM

    override fun invalidations(): Flow<Unit> = merge(
        steamAppDao._observeOwnedAppCount().map { Unit },
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
        val accountGeneration = accountLifecycleState.generation(source)
        if (accountLifecycleState.readyGeneration(source) != accountGeneration) {
            return presenceLedgerNotReady(source, accountScope)
        }

        return try {
            var partialReason: SnapshotReason? = null
            val copies = steamAppDao._getAllOwnedAppsPaged()
                .mapNotNull { app ->
                    if (app.id <= 0) {
                        partialReason = SnapshotReason.MALFORMED_SOURCE_ID
                        return@mapNotNull null
                    }
                    val stableSourceId = app.id.toString()
                    OwnedCopyProjection(
                        key = OwnedCopyKey(accountScope, source, stableSourceId),
                        displayName = app.name,
                        developer = app.developer,
                        releaseYear = CanonicalNormalization.releaseYear(app.releaseDate),
                        appType = CanonicalNormalization.appType(app.type),
                        directSteamAppId = app.id,
                        genreKeys = app.genreIds
                            .asSequence()
                            .filter { it > 0 }
                            .map { "steam:$it" }
                            .toSortedSet(),
                        tagIds = app.storeTagIds.filter { it > 0 }.toSortedSet(),
                        featureKeys = app.categoryIds
                            .asSequence()
                            .filter { it > 0 }
                            .map { "steam:$it" }
                            .toSortedSet(),
                    )
                }.sortedBy { it.key.stableSourceId }

            if (
                !accountScopeProvider.isAccountScopeUnchanged(
                    source,
                    accountScope,
                    accountGeneration,
                    accountLifecycleState,
                )
            ) {
                accountScopeChanged(source)
            } else {
                sourceBatch(source, accountScope, copies, partialReason)
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
        val accountGeneration = accountLifecycleState.generation(source)
        if (key.accountScope != currentScope) return null
        if (accountLifecycleState.readyGeneration(source) != accountGeneration) return null
        val appId = key.stableSourceId.toIntOrNull()
            ?.takeIf { it > 0 && it.toString() == key.stableSourceId }
            ?: return null
        steamAppDao.findOwnedApp(appId) ?: return null
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
        return SourceOwnedCopyReference.Steam(key, appId)
    }
}
