package app.gamenative.library.canonical.runtime

import android.content.Context
import app.gamenative.data.AmazonGame
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalNormalization
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.data.canonical.OwnedCopyPresenceEntity
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.CompletedOwnedCopySnapshot
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.db.dao.OwnedCopyLedgerDao
import app.gamenative.library.canonical.AccountLifecycleState
import app.gamenative.library.canonical.AccountScopeProvider
import app.gamenative.library.canonical.CopyUnavailableReason
import app.gamenative.library.canonical.source.AmazonOwnedCopySourceAdapter
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import app.gamenative.library.canonical.source.preferredAmazonRows
import app.gamenative.service.amazon.AmazonArtwork
import app.gamenative.service.amazon.AmazonService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

@Singleton
class AmazonOwnedCopyRuntimeGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    internal suspend fun activeDownloadProductIds(): Set<String> =
        AmazonService.getActiveDownloads()
            .filterValues { it.isActive() }
            .keys

    internal suspend fun partialDownloadProductIds(): Set<String> =
        AmazonService.getPartialDownloads(context).toSet()

    internal suspend fun updatePending(productId: String): Boolean =
        AmazonService.isUpdatePending(productId)
}

@Singleton
class AmazonOwnedCopyRuntimeState @Inject constructor(
    private val gateway: AmazonOwnedCopyRuntimeGateway,
) {
    private val updateCache = ConcurrentHashMap<AmazonUpdateCacheKey, Boolean>()

    internal suspend fun readPoint(
        game: AmazonGame,
        accountScope: AccountScope,
        generation: Long,
    ): OwnedCopyVolatileState {
        val activeDownloads = gateway.activeDownloadProductIds()
        val partialDownloads = gateway.partialDownloadProductIds() + activeDownloads
        val updateAvailable = game.isInstalled && gateway.updatePending(game.productId)
        updateCache[AmazonUpdateCacheKey(accountScope, generation, game.productId)] = updateAvailable
        return stateFor(game, activeDownloads, partialDownloads, updateAvailable)
    }

    internal suspend fun readBatch(
        games: List<AmazonGame>,
        accountScope: AccountScope,
        generation: Long,
    ): Map<Int, OwnedCopyVolatileState> {
        if (games.isEmpty()) return emptyMap()
        val activeDownloads = gateway.activeDownloadProductIds()
        val partialDownloads = gateway.partialDownloadProductIds() + activeDownloads
        val updateSnapshot = updateCache.toMap()
        return games.mapNotNull { game ->
            try {
                val cacheKey = AmazonUpdateCacheKey(accountScope, generation, game.productId)
                game.appId to stateFor(
                    game,
                    activeDownloads,
                    partialDownloads,
                    updateSnapshot[cacheKey] == true,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }.toMap()
    }

    private data class AmazonUpdateCacheKey(
        val accountScope: AccountScope,
        val generation: Long,
        val productId: String,
    )

    private fun stateFor(
        game: AmazonGame,
        activeDownloads: Set<String>,
        partialDownloads: Set<String>,
        updateAvailable: Boolean,
    ): OwnedCopyVolatileState = OwnedCopyVolatileState(
        installPath = game.installPath.takeIf(String::isNotBlank),
        installedSizeBytes = game.installSize.positiveOrNull(),
        branchOrVersion = game.versionId.takeIf(String::isNotBlank),
        isInstalled = game.isInstalled,
        isDownloading = game.productId in activeDownloads,
        hasPartialDownload = game.productId in partialDownloads,
        updateAvailable = updateAvailable,
        isShared = false,
        playtimeMinutes = game.playTimeMinutes.positiveOrNull(),
    )
}

@Singleton
class AmazonOwnedCopyRuntimeAdapter @Inject constructor(
    private val amazonGameDao: AmazonGameDao,
    private val accountScopeProvider: AccountScopeProvider,
    private val ownedCopyLedgerDao: OwnedCopyLedgerDao,
    private val accountLifecycleState: AccountLifecycleState,
    private val sourceAdapter: AmazonOwnedCopySourceAdapter,
    private val playHistoryDao: LibraryPlayHistoryDao,
    private val runtimeState: AmazonOwnedCopyRuntimeState,
) : OwnedCopyRuntimeAdapter {
    override val source: GameSource = GameSource.AMAZON

    override fun invalidations(): Flow<Unit> = sourceAdapter.invalidations()

    override suspend fun resolve(key: OwnedCopyKey): OwnedCopyRuntimeResult {
        if (key.source != source) return OwnedCopyRuntimeResult.Hidden
        var ownershipProved = false
        var provedScope: AccountScope? = null
        var provedGeneration: Long? = null
        var provedResolvedSourceId: String? = null
        try {
            val generation = accountLifecycleState.generation(source)
            val accountScope = accountScopeProvider.current(source)
                ?: return OwnedCopyRuntimeResult.Hidden
            provedScope = accountScope
            provedGeneration = generation
            if (key.accountScope != accountScope) return OwnedCopyRuntimeResult.Hidden
            if (accountLifecycleState.readyGeneration(source) != generation) {
                return OwnedCopyRuntimeResult.Hidden
            }
            val presence = currentPresence(key, accountScope, generation)
                ?: return OwnedCopyRuntimeResult.Hidden
            ownershipProved = true
            provedResolvedSourceId = presence.resolvedSourceId
            val entitlementId = presence.resolvedSourceId?.takeIf(String::isNotBlank)
                ?: return unavailableIfFresh(
                    key,
                    accountScope,
                    generation,
                    presence.resolvedSourceId,
                    CopyUnavailableReason.SOURCE_ROW_CHANGED,
                )
            val reference = sourceAdapter.resolve(key) as? SourceOwnedCopyReference.Amazon
            if (
                reference == null || reference.productId != key.stableSourceId ||
                reference.entitlementId != entitlementId
            ) {
                return unavailableIfFresh(
                    key,
                    accountScope,
                    generation,
                    entitlementId,
                    CopyUnavailableReason.SOURCE_ROW_CHANGED,
                )
            }
            val game = amazonGameDao.getByAppId(reference.localRowId)
            if (game == null || game.appId != reference.localRowId || game.productId != reference.productId) {
                return unavailableIfFresh(
                    key,
                    accountScope,
                    generation,
                    entitlementId,
                    CopyUnavailableReason.SOURCE_ROW_CHANGED,
                )
            }
            val sourceState = runtimeState.readPoint(game, accountScope, generation)
            val localLastPlayed = playHistoryDao.pointLastPlayed(sourceAppId(source, game.appId))
            if (!hasFreshProof(key, accountScope, generation, entitlementId)) {
                return OwnedCopyRuntimeResult.Hidden
            }
            return available(key, reference, game, sourceState, localLastPlayed)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val scope = provedScope
            val generation = provedGeneration
            return if (ownershipProved && scope != null && generation != null) {
                unavailableIfFresh(
                    key,
                    scope,
                    generation,
                    provedResolvedSourceId,
                    CopyUnavailableReason.SOURCE_READ_FAILED,
                    error,
                )
            } else {
                OwnedCopyRuntimeResult.Hidden
            }
        }
    }

    override suspend fun resolveAll(
        keys: Set<OwnedCopyKey>,
    ): Map<OwnedCopyKey, OwnedCopyRuntimeResult> {
        if (keys.isEmpty()) return emptyMap()
        var provedScope: AccountScope? = null
        var generation: Long? = null
        var initialLedger: CompletedOwnedCopySnapshot? = null
        return try {
            generation = accountLifecycleState.generation(source)
            val accountScope = accountScopeProvider.current(source)
                ?: return keys.hiddenResults()
            provedScope = accountScope
            if (accountLifecycleState.readyGeneration(source) != generation) {
                return keys.hiddenResults()
            }
            if (keys.none { it.source == source && it.accountScope == accountScope }) {
                return keys.hiddenResults()
            }
            val ledger = ownedCopyLedgerDao.getCompletedSnapshotForLifecycle(
                accountScope = accountScope.value,
                source = source,
                lifecycleGeneration = generation,
            ) ?: return keys.hiddenResults()
            initialLedger = ledger
            val ownedIds = ledger.stableSourceIds.toSet()
            if (
                keys.none {
                    it.source == source && it.accountScope == accountScope &&
                        it.stableSourceId in ownedIds
                }
            ) {
                return keys.hiddenResults()
            }
            val rows = amazonGameDao.getAllAsList()
            val rowsByProduct = preferredAmazonRows(rows)
            val requestedRows = keys.asSequence()
                .filter { it.source == source && it.accountScope == accountScope }
                .filter { it.stableSourceId in ownedIds }
                .mapNotNull { rowsByProduct[it.stableSourceId] }
                .distinctBy(AmazonGame::appId)
                .toList()
            val states = runtimeState.readBatch(requestedRows, accountScope, generation)
            val history = playHistoryDao.batchLastPlayed()
            val finalLedger = finalLedger(accountScope, generation) ?: return keys.hiddenResults()
            if (!isCurrent(accountScope, generation)) return keys.hiddenResults()
            keys.associateWith { key ->
                val entitlementId = ledger.resolvedSourceIds[key.stableSourceId]
                val game = rowsByProduct[key.stableSourceId]
                val sourceState = game?.let { states[it.appId] }
                when {
                    key.source != source || key.accountScope != accountScope ->
                        OwnedCopyRuntimeResult.Hidden
                    !referenceIsCurrent(key.stableSourceId, ledger, finalLedger) ->
                        OwnedCopyRuntimeResult.Hidden
                    entitlementId.isNullOrBlank() || game == null || sourceState == null ->
                        unavailable(key, CopyUnavailableReason.SOURCE_ROW_CHANGED)
                    else -> available(
                        key = key,
                        reference = SourceOwnedCopyReference.Amazon(
                            key = key,
                            localRowId = game.appId,
                            productId = game.productId,
                            entitlementId = entitlementId,
                        ),
                        game = game,
                        sourceState = sourceState,
                        localLastPlayed = history[sourceAppId(source, game.appId)],
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val scope = provedScope ?: return keys.hiddenResults()
            val currentGeneration = generation ?: return keys.hiddenResults()
            val ledger = initialLedger ?: return keys.hiddenResults()
            val finalLedger = finalLedger(scope, currentGeneration) ?: return keys.hiddenResults()
            if (!isCurrent(scope, currentGeneration)) return keys.hiddenResults()
            keys.associateWith { key ->
                if (
                    key.source == source && key.accountScope == scope &&
                    referenceIsCurrent(key.stableSourceId, ledger, finalLedger)
                ) {
                    unavailable(key, CopyUnavailableReason.SOURCE_READ_FAILED, error)
                } else {
                    OwnedCopyRuntimeResult.Hidden
                }
            }
        }
    }

    private suspend fun currentPresence(
        key: OwnedCopyKey,
        accountScope: AccountScope,
        generation: Long,
    ): OwnedCopyPresenceEntity? = ownedCopyLedgerDao.getPresenceForLifecycle(
        accountScope = accountScope.value,
        source = source,
        stableSourceId = key.stableSourceId,
        lifecycleGeneration = generation,
    )

    private suspend fun hasFreshProof(
        key: OwnedCopyKey,
        accountScope: AccountScope,
        generation: Long,
        resolvedSourceId: String?,
    ): Boolean = currentAccountProof {
        accountScopeProvider.current(source) == accountScope &&
            accountLifecycleState.generation(source) == generation &&
            accountLifecycleState.readyGeneration(source) == generation &&
            currentPresence(key, accountScope, generation)
                ?.let { it.resolvedSourceId == resolvedSourceId } == true
    }

    private suspend fun unavailableIfFresh(
        key: OwnedCopyKey,
        accountScope: AccountScope,
        generation: Long,
        resolvedSourceId: String?,
        reason: CopyUnavailableReason,
        error: Exception? = null,
    ): OwnedCopyRuntimeResult = if (
        hasFreshProof(key, accountScope, generation, resolvedSourceId)
    ) {
        unavailable(key, reason, error)
    } else {
        OwnedCopyRuntimeResult.Hidden
    }

    private suspend fun finalLedger(
        accountScope: AccountScope,
        generation: Long,
    ): CompletedOwnedCopySnapshot? = try {
        ownedCopyLedgerDao.getCompletedSnapshotForLifecycle(
            accountScope = accountScope.value,
            source = source,
            lifecycleGeneration = generation,
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun referenceIsCurrent(
        stableSourceId: String,
        initial: CompletedOwnedCopySnapshot,
        final: CompletedOwnedCopySnapshot,
    ): Boolean = stableSourceId in initial.stableSourceIds &&
        stableSourceId in final.stableSourceIds &&
        initial.resolvedSourceIds[stableSourceId] == final.resolvedSourceIds[stableSourceId]

    private suspend fun isCurrent(accountScope: AccountScope, generation: Long): Boolean =
        currentAccountProof {
            accountScopeProvider.current(source) == accountScope &&
                accountLifecycleState.generation(source) == generation &&
                accountLifecycleState.readyGeneration(source) == generation
        }

    private fun available(
        key: OwnedCopyKey,
        reference: SourceOwnedCopyReference.Amazon,
        game: AmazonGame,
        sourceState: OwnedCopyVolatileState,
        localLastPlayed: Long?,
    ): OwnedCopyRuntimeResult.Available {
        val layoutHero = AmazonArtwork.layoutHeroFromProductJson(game.productJson)
            .ifEmpty { game.heroUrl.ifEmpty { game.artUrl } }
        val item = LibraryItem(
            appId = sourceAppId(source, game.appId),
            name = game.title,
            iconHash = game.artUrl,
            capsuleImageUrl = game.artUrl,
            headerImageUrl = layoutHero,
            heroImageUrl = layoutHero.ifEmpty { game.artUrl },
            gridHeroImageScale = AmazonArtwork.GRID_HERO_ZOOM_SCALE,
            isShared = false,
            gameSource = source,
            sizeBytes = sourceState.installedSizeBytes ?: 0L,
            isInstalled = sourceState.isInstalled,
        )
        return OwnedCopyRuntimeResult.Available(
            OwnedCopyRuntime(
                key = key,
                reference = reference,
                libraryItem = item,
                nativeTitle = game.title,
                aliases = emptySet(),
                developerKey = CanonicalNormalization.developerKey(game.developer),
                releaseYear = CanonicalNormalization.releaseYear(game.releaseDate),
                appType = CanonicalAppType.GAME,
                genreKeys = emptySet(),
                tagIds = emptySet(),
                featureKeys = emptySet(),
                iconUrl = game.artUrl,
                capsuleImageUrl = game.artUrl,
                headerImageUrl = layoutHero,
                heroImageUrl = layoutHero.ifEmpty { game.artUrl },
                gridHeroImageScale = AmazonArtwork.GRID_HERO_ZOOM_SCALE,
                installPath = sourceState.installPath,
                installedSizeBytes = sourceState.installedSizeBytes,
                branchOrVersion = sourceState.branchOrVersion,
                isInstalled = sourceState.isInstalled,
                isDownloading = sourceState.isDownloading,
                hasPartialDownload = sourceState.hasPartialDownload,
                updateAvailable = sourceState.updateAvailable,
                isShared = false,
                lastPlayedEpochMs = latestPositiveTimestamp(game.lastPlayed, localLastPlayed),
                playtimeMinutes = sourceState.playtimeMinutes,
                capabilities = capabilities(source, libraryItemPresent = true, sourceState),
            ),
        )
    }
}
