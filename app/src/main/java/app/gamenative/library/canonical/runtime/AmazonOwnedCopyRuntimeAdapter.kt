package app.gamenative.library.canonical.runtime

import android.content.Context
import app.gamenative.data.AmazonGame
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalNormalization
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.db.dao.OwnedCopyLedgerDao
import app.gamenative.library.canonical.AccountLifecycleState
import app.gamenative.library.canonical.AccountScopeProvider
import app.gamenative.library.canonical.CopyUnavailableReason
import app.gamenative.library.canonical.source.AmazonOwnedCopySourceAdapter
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import app.gamenative.service.amazon.AmazonArtwork
import app.gamenative.service.amazon.AmazonService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

@Singleton
class AmazonOwnedCopyRuntimeState @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    internal suspend fun read(games: List<AmazonGame>): Map<Int, OwnedCopyVolatileState> {
        if (games.isEmpty()) return emptyMap()
        val activeDownloads = AmazonService.getActiveDownloads()
        val partialDownloads = AmazonService.getPartialDownloads(context).toSet()
        return games.associate { game ->
            val downloading = activeDownloads[game.productId]?.isActive() == true
            game.appId to OwnedCopyVolatileState(
                installPath = game.installPath.takeIf(String::isNotBlank),
                installedSizeBytes = game.installSize.positiveOrNull(),
                branchOrVersion = game.versionId.takeIf(String::isNotBlank),
                isInstalled = game.isInstalled,
                isDownloading = downloading,
                hasPartialDownload = downloading || game.productId in partialDownloads,
                updateAvailable = game.isInstalled && AmazonService.isUpdatePendingByAppId(game.appId),
                isShared = false,
                playtimeMinutes = game.playTimeMinutes.positiveOrNull(),
            )
        }
    }
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
        try {
            val generation = accountLifecycleState.generation(source)
            val accountScope = accountScopeProvider.current(source)
                ?: return OwnedCopyRuntimeResult.Hidden
            if (key.accountScope != accountScope) return OwnedCopyRuntimeResult.Hidden
            if (accountLifecycleState.readyGeneration(source) != generation) {
                return OwnedCopyRuntimeResult.Hidden
            }
            val entitlementId = currentEntitlement(key, accountScope, generation)
                ?: return OwnedCopyRuntimeResult.Hidden
            ownershipProved = true
            val reference = sourceAdapter.resolve(key) as? SourceOwnedCopyReference.Amazon
            if (
                reference == null || reference.productId != key.stableSourceId ||
                reference.entitlementId != entitlementId
            ) {
                return if (isCurrentReference(key, accountScope, generation, entitlementId)) {
                    unavailable(key, CopyUnavailableReason.SOURCE_ROW_CHANGED)
                } else {
                    OwnedCopyRuntimeResult.Hidden
                }
            }
            val game = amazonGameDao.getByAppId(reference.localRowId)
            if (game == null || game.appId != reference.localRowId || game.productId != reference.productId) {
                return unavailable(key, CopyUnavailableReason.SOURCE_ROW_CHANGED)
            }
            val sourceState = runtimeState.read(listOf(game))[game.appId]
                ?: return unavailable(key, CopyUnavailableReason.SOURCE_ROW_CHANGED)
            playHistoryDao.pointLastPlayed(sourceAppId(source, game.appId))
            if (!isCurrentReference(key, accountScope, generation, entitlementId)) {
                return OwnedCopyRuntimeResult.Hidden
            }
            return available(key, reference, game, sourceState)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return if (ownershipProved) {
                unavailable(key, CopyUnavailableReason.SOURCE_READ_FAILED, error)
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
        var ownedIds: Set<String> = emptySet()
        return try {
            val generation = accountLifecycleState.generation(source)
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
            ownedIds = ledger.stableSourceIds.toSet()
            if (
                keys.none {
                    it.source == source && it.accountScope == accountScope &&
                        it.stableSourceId in ownedIds
                }
            ) {
                return keys.hiddenResults()
            }
            val rows = amazonGameDao.getAllAsList()
            val rowsByProduct = rows.associateBy(AmazonGame::productId)
            val requestedRows = keys.asSequence()
                .filter { it.source == source && it.accountScope == accountScope }
                .filter { it.stableSourceId in ownedIds }
                .mapNotNull { rowsByProduct[it.stableSourceId] }
                .distinctBy(AmazonGame::appId)
                .toList()
            val states = runtimeState.read(requestedRows)
            playHistoryDao.batchLastPlayed()
            if (!isCurrent(accountScope, generation)) return keys.hiddenResults()
            keys.associateWith { key ->
                val entitlementId = ledger.resolvedSourceIds[key.stableSourceId]
                val game = rowsByProduct[key.stableSourceId]
                val sourceState = game?.let { states[it.appId] }
                when {
                    key.source != source || key.accountScope != accountScope ->
                        OwnedCopyRuntimeResult.Hidden
                    key.stableSourceId !in ownedIds -> OwnedCopyRuntimeResult.Hidden
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
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            keys.associateWith { key ->
                if (
                    key.source == source && key.accountScope == provedScope &&
                    key.stableSourceId in ownedIds
                ) {
                    unavailable(key, CopyUnavailableReason.SOURCE_READ_FAILED, error)
                } else {
                    OwnedCopyRuntimeResult.Hidden
                }
            }
        }
    }

    private suspend fun currentEntitlement(
        key: OwnedCopyKey,
        accountScope: AccountScope,
        generation: Long,
    ): String? = ownedCopyLedgerDao.getPresenceForLifecycle(
        accountScope = accountScope.value,
        source = source,
        stableSourceId = key.stableSourceId,
        lifecycleGeneration = generation,
    )?.resolvedSourceId?.takeIf(String::isNotBlank)

    private suspend fun isCurrentReference(
        key: OwnedCopyKey,
        accountScope: AccountScope,
        generation: Long,
        entitlementId: String,
    ): Boolean = isCurrent(accountScope, generation) &&
        currentEntitlement(key, accountScope, generation) == entitlementId

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
                lastPlayedEpochMs = game.lastPlayed.positiveOrNull(),
                playtimeMinutes = sourceState.playtimeMinutes,
                capabilities = capabilities(source, libraryItemPresent = true, sourceState),
            ),
        )
    }
}
