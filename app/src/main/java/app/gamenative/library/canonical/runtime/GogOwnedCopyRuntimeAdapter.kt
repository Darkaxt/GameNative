package app.gamenative.library.canonical.runtime

import app.gamenative.data.GOGGame
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalNormalization
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.db.dao.OwnedCopyLedgerDao
import app.gamenative.library.canonical.AccountLifecycleState
import app.gamenative.library.canonical.AccountScopeProvider
import app.gamenative.library.canonical.CopyUnavailableReason
import app.gamenative.library.canonical.source.GogOwnedCopySourceAdapter
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import app.gamenative.library.canonical.source.sourceQualifiedKeys
import app.gamenative.service.gog.GOGService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

@Singleton
class GogOwnedCopyRuntimeState @Inject constructor() {
    internal suspend fun read(games: List<GOGGame>): Map<String, OwnedCopyVolatileState> {
        if (games.isEmpty()) return emptyMap()
        val activeDownloads = GOGService.getActiveDownloads()
        val partialDownloads = GOGService.getPartialDownloads().toSet()
        return games.associate { game ->
            val downloading = activeDownloads[game.id]?.isActive() == true
            game.id to OwnedCopyVolatileState(
                installPath = game.installPath.takeIf(String::isNotBlank),
                installedSizeBytes = game.installSize.positiveOrNull(),
                branchOrVersion = null,
                isInstalled = game.isInstalled,
                isDownloading = downloading,
                hasPartialDownload = downloading || game.id in partialDownloads,
                updateAvailable = false,
                isShared = false,
                playtimeMinutes = game.playTime.positiveOrNull(),
            )
        }
    }
}

@Singleton
class GogOwnedCopyRuntimeAdapter @Inject constructor(
    private val gogGameDao: GOGGameDao,
    private val accountScopeProvider: AccountScopeProvider,
    private val ownedCopyLedgerDao: OwnedCopyLedgerDao,
    private val accountLifecycleState: AccountLifecycleState,
    private val sourceAdapter: GogOwnedCopySourceAdapter,
    private val playHistoryDao: LibraryPlayHistoryDao,
    private val runtimeState: GogOwnedCopyRuntimeState,
) : OwnedCopyRuntimeAdapter {
    override val source: GameSource = GameSource.GOG

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
            if (!isOwned(key, accountScope, generation)) return OwnedCopyRuntimeResult.Hidden
            ownershipProved = true
            val reference = sourceAdapter.resolve(key) as? SourceOwnedCopyReference.Gog
            if (reference == null || reference.gameId != key.stableSourceId) {
                return if (isCurrentAndOwned(key, accountScope, generation)) {
                    unavailable(key, CopyUnavailableReason.SOURCE_ROW_CHANGED)
                } else {
                    OwnedCopyRuntimeResult.Hidden
                }
            }
            val game = gogGameDao.getById(reference.gameId)?.takeUnless(GOGGame::exclude)
                ?: return unavailable(key, CopyUnavailableReason.SOURCE_ROW_CHANGED)
            val sourceState = runtimeState.read(listOf(game))[game.id]
                ?: return unavailable(key, CopyUnavailableReason.SOURCE_ROW_CHANGED)
            playHistoryDao.pointLastPlayed(sourceAppId(source, game.id))
            if (!isCurrentAndOwned(key, accountScope, generation)) {
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
        var accountScope: AccountScope? = null
        var ownedIds: Set<String> = emptySet()
        return try {
            val generation = accountLifecycleState.generation(source)
            accountScope = accountScopeProvider.current(source)
                ?: return keys.hiddenResults()
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
            val rows = gogGameDao.getAllAsList()
            val rowsById = rows.associateBy(GOGGame::id)
            val requestedRows = keys.asSequence()
                .filter { it.source == source && it.accountScope == accountScope }
                .filter { it.stableSourceId in ownedIds }
                .mapNotNull { rowsById[it.stableSourceId] }
                .distinctBy(GOGGame::id)
                .toList()
            val states = runtimeState.read(requestedRows)
            playHistoryDao.batchLastPlayed()
            if (!isCurrent(accountScope, generation)) return keys.hiddenResults()
            keys.associateWith { key ->
                val game = rowsById[key.stableSourceId]
                val sourceState = states[key.stableSourceId]
                when {
                    key.source != source || key.accountScope != accountScope ->
                        OwnedCopyRuntimeResult.Hidden
                    key.stableSourceId !in ownedIds -> OwnedCopyRuntimeResult.Hidden
                    game == null || sourceState == null ->
                        unavailable(key, CopyUnavailableReason.SOURCE_ROW_CHANGED)
                    else -> available(
                        key,
                        SourceOwnedCopyReference.Gog(key, game.id),
                        game,
                        sourceState,
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            keys.associateWith { key ->
                if (
                    key.source == source && key.accountScope == accountScope &&
                    key.stableSourceId in ownedIds
                ) {
                    unavailable(key, CopyUnavailableReason.SOURCE_READ_FAILED, error)
                } else {
                    OwnedCopyRuntimeResult.Hidden
                }
            }
        }
    }

    private suspend fun isOwned(
        key: OwnedCopyKey,
        accountScope: AccountScope,
        generation: Long,
    ): Boolean = ownedCopyLedgerDao.isPresentForLifecycle(
        accountScope = accountScope.value,
        source = source,
        stableSourceId = key.stableSourceId,
        lifecycleGeneration = generation,
    )

    private suspend fun isCurrentAndOwned(
        key: OwnedCopyKey,
        accountScope: AccountScope,
        generation: Long,
    ): Boolean = isCurrent(accountScope, generation) && isOwned(key, accountScope, generation)

    private suspend fun isCurrent(accountScope: AccountScope, generation: Long): Boolean =
        currentAccountProof {
            accountScopeProvider.current(source) == accountScope &&
                accountLifecycleState.generation(source) == generation &&
                accountLifecycleState.readyGeneration(source) == generation
        }

    private fun available(
        key: OwnedCopyKey,
        reference: SourceOwnedCopyReference.Gog,
        game: GOGGame,
        sourceState: OwnedCopyVolatileState,
    ): OwnedCopyRuntimeResult.Available {
        val bridgeId = game.id.toIntOrNull()?.takeIf { id -> id > 0 && id.toString() == game.id }
        val item = bridgeId?.let { id ->
            LibraryItem(
                appId = sourceAppId(source, id),
                name = game.title,
                iconHash = game.iconUrl.ifEmpty { game.imageUrl },
                capsuleImageUrl = game.verticalCoverUrl.ifEmpty {
                    game.iconUrl.ifEmpty { game.imageUrl }
                },
                headerImageUrl = game.imageUrl.ifEmpty { game.iconUrl },
                heroImageUrl = game.imageUrl.ifEmpty { game.iconUrl },
                isShared = false,
                gameSource = source,
                sizeBytes = sourceState.installedSizeBytes ?: 0L,
                isInstalled = sourceState.isInstalled,
            )
        }
        return OwnedCopyRuntimeResult.Available(
            OwnedCopyRuntime(
                key = key,
                reference = reference,
                libraryItem = item,
                nativeTitle = game.title,
                aliases = emptySet(),
                developerKey = CanonicalNormalization.developerKey(game.developer),
                releaseYear = CanonicalNormalization.releaseYear(game.releaseDate),
                appType = CanonicalNormalization.appType(game.type),
                genreKeys = sourceQualifiedKeys("gog", game.genres),
                tagIds = emptySet(),
                featureKeys = emptySet(),
                iconUrl = game.iconUrl.ifEmpty { game.imageUrl },
                capsuleImageUrl = game.verticalCoverUrl.ifEmpty {
                    game.iconUrl.ifEmpty { game.imageUrl }
                },
                headerImageUrl = game.imageUrl.ifEmpty { game.iconUrl },
                heroImageUrl = game.imageUrl.ifEmpty { game.iconUrl },
                gridHeroImageScale = 1f,
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
                capabilities = capabilities(source, item != null, sourceState),
            ),
        )
    }
}
