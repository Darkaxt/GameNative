package app.gamenative.library.canonical.runtime

import app.gamenative.data.EpicGame
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalNormalization
import app.gamenative.data.canonical.EpicStableSourceId
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.db.dao.CompletedOwnedCopySnapshot
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.db.dao.OwnedCopyLedgerDao
import app.gamenative.library.canonical.AccountLifecycleState
import app.gamenative.library.canonical.AccountScopeProvider
import app.gamenative.library.canonical.CanonicalDiagnosticSink
import app.gamenative.library.canonical.CanonicalLibraryDiagnosticSink
import app.gamenative.library.canonical.NoOpCanonicalLibraryDiagnosticSink
import app.gamenative.library.canonical.recordSafely
import app.gamenative.library.canonical.CopyUnavailableReason
import app.gamenative.library.canonical.source.EpicOwnedCopySourceAdapter
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import app.gamenative.library.canonical.source.preferredEpicRows
import app.gamenative.library.canonical.source.sourceQualifiedKeys
import app.gamenative.service.epic.EpicService
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

@Singleton
class EpicOwnedCopyRuntimeGateway private constructor(
    private val ioDispatcher: CoroutineDispatcher,
    private val readSnapshot: suspend () -> RuntimeDownloadSnapshot<Int>,
) {
    @Inject
    constructor(
        @CanonicalIoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(
        ioDispatcher,
        {
            RuntimeDownloadSnapshot(
                activeIds = EpicService.getActiveDownloads()
                    .filterValues { it.isActive() }
                    .keys,
                partialIds = EpicService.getPartialDownloads().toSet(),
            )
        },
    )

    internal constructor(
        ioDispatcher: CoroutineDispatcher,
        readSnapshot: () -> RuntimeDownloadSnapshot<Int>,
        @Suppress("UNUSED_PARAMETER") marker: Unit,
    ) : this(ioDispatcher, readSnapshot)

    internal suspend fun snapshot(): RuntimeDownloadSnapshot<Int> =
        withContext(ioDispatcher) { readSnapshot() }
}

@Singleton
class EpicOwnedCopyRuntimeState @Inject constructor(
    private val gateway: EpicOwnedCopyRuntimeGateway,
) {
    internal suspend fun read(games: List<EpicGame>): Map<Int, OwnedCopyVolatileState> {
        if (games.isEmpty()) return emptyMap()
        val downloads = gateway.snapshot()
        return games.associate { game ->
            val downloading = game.id in downloads.activeIds
            game.id to OwnedCopyVolatileState(
                installPath = game.installPath.takeIf(String::isNotBlank),
                installedSizeBytes = game.installSize.positiveOrNull(),
                branchOrVersion = game.version.takeIf(String::isNotBlank),
                isInstalled = game.isInstalled,
                isDownloading = downloading,
                hasPartialDownload = downloading || game.id in downloads.partialIds,
                updateAvailable = false,
                isShared = false,
                playtimeMinutes = game.playTime.positiveOrNull(),
            )
        }
    }
}

@Singleton
class EpicOwnedCopyRuntimeAdapter @Inject constructor(
    private val epicGameDao: EpicGameDao,
    private val accountScopeProvider: AccountScopeProvider,
    private val ownedCopyLedgerDao: OwnedCopyLedgerDao,
    private val accountLifecycleState: AccountLifecycleState,
    private val sourceAdapter: EpicOwnedCopySourceAdapter,
    private val playHistoryDao: LibraryPlayHistoryDao,
    private val runtimeState: EpicOwnedCopyRuntimeState,
    private val diagnostics: CanonicalDiagnosticSink? = null,
    private val libraryDiagnostics: CanonicalLibraryDiagnosticSink =
        NoOpCanonicalLibraryDiagnosticSink,
) : OwnedCopyRuntimeAdapter {
    override val source: GameSource = GameSource.EPIC

    override fun invalidations(): Flow<Unit> = sourceAdapter.invalidations()

    override suspend fun resolve(key: OwnedCopyKey): OwnedCopyRuntimeResult {
        if (key.source != source) return OwnedCopyRuntimeResult.Hidden
        var ownershipProved = false
        var provedScope: AccountScope? = null
        var provedGeneration: Long? = null
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
            if (!isOwned(key, accountScope, generation)) return OwnedCopyRuntimeResult.Hidden
            ownershipProved = true
            val reference = sourceAdapter.resolve(key) as? SourceOwnedCopyReference.Epic
            if (reference == null) {
                val excluded = providerRow(key)?.let(::isExcluded) == true
                if (excluded) return OwnedCopyRuntimeResult.Hidden
                return unavailableIfFresh(
                    key,
                    accountScope,
                    generation,
                    CopyUnavailableReason.SOURCE_ROW_CHANGED,
                )
            }
            val game = epicGameDao.getById(reference.localRowId)
            if (game == null || !reference.matches(game)) {
                return unavailableIfFresh(
                    key,
                    accountScope,
                    generation,
                    CopyUnavailableReason.SOURCE_ROW_CHANGED,
                )
            }
            if (isExcluded(game)) return OwnedCopyRuntimeResult.Hidden
            val sourceState = runtimeState.read(listOf(game))[game.id]
                ?: return unavailableIfFresh(
                    key,
                    accountScope,
                    generation,
                    CopyUnavailableReason.SOURCE_ROW_CHANGED,
                )
            val localLastPlayed = playHistoryDao.pointLastPlayed(
                sourceAppId(source, game.id),
                source,
                diagnostics,
            )
            if (!hasFreshProof(key, accountScope, generation)) {
                return OwnedCopyRuntimeResult.Hidden
            }
            return available(key, reference, game, sourceState, localLastPlayed)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            reportRuntimeReadFailed(error::class)
            val scope = provedScope
            val generation = provedGeneration
            return if (ownershipProved && scope != null && generation != null) {
                unavailableIfFresh(
                    key,
                    scope,
                    generation,
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
        var ownedIds: Set<String> = emptySet()
        var completedFinalLedger: CompletedOwnedCopySnapshot? = null
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
            ownedIds = ledger.stableSourceIds.toSet()
            if (
                keys.none {
                    it.source == source && it.accountScope == accountScope &&
                        it.stableSourceId in ownedIds
                }
            ) {
                return keys.hiddenResults()
            }
            val rows = epicGameDao.getAllForCanonicalProjection()
            val rowsByStableId = preferredEpicRows(rows)
            val requestedRows = keys.asSequence()
                .filter { it.source == source && it.accountScope == accountScope }
                .filter { it.stableSourceId in ownedIds }
                .mapNotNull { rowsByStableId[it.stableSourceId] }
                .filterNot(::isExcluded)
                .distinctBy(EpicGame::id)
                .toList()
            val states = runtimeState.read(requestedRows)
            val history = playHistoryDao.batchLastPlayed(source, diagnostics)
            val finalLedger = finalLedger(accountScope, generation) ?: return keys.hiddenResults()
            completedFinalLedger = finalLedger
            val finalOwnedIds = finalLedger.stableSourceIds.toSet()
            if (!isCurrent(accountScope, generation)) return keys.hiddenResults()
            keys.associateWith { key ->
                val game = rowsByStableId[key.stableSourceId]
                val sourceState = game?.let { states[it.id] }
                when {
                    key.source != source || key.accountScope != accountScope ->
                        OwnedCopyRuntimeResult.Hidden
                    key.stableSourceId !in ownedIds || key.stableSourceId !in finalOwnedIds ->
                        OwnedCopyRuntimeResult.Hidden
                    game?.let(::isExcluded) == true -> OwnedCopyRuntimeResult.Hidden
                    game == null || sourceState == null ->
                        unavailable(key, CopyUnavailableReason.SOURCE_ROW_CHANGED)
                    else -> available(
                        key = key,
                        reference = SourceOwnedCopyReference.Epic(
                            key = key,
                            localRowId = game.id,
                            namespace = game.namespace,
                            catalogId = game.catalogId,
                        ),
                        game = game,
                        sourceState = sourceState,
                        localLastPlayed = history[sourceAppId(source, game.id)],
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            reportRuntimeReadFailed(error::class)
            val scope = provedScope ?: return keys.hiddenResults()
            val currentGeneration = generation ?: return keys.hiddenResults()
            val finalLedger = completedFinalLedger
                ?: finalLedger(scope, currentGeneration)
                ?: return keys.hiddenResults()
            if (!isCurrent(scope, currentGeneration)) return keys.hiddenResults()
            val finalOwnedIds = finalLedger.stableSourceIds.toSet()
            keys.associateWith { key ->
                if (
                    key.source == source && key.accountScope == scope &&
                    key.stableSourceId in ownedIds && key.stableSourceId in finalOwnedIds
                ) {
                    unavailable(key, CopyUnavailableReason.SOURCE_READ_FAILED, error)
                } else {
                    OwnedCopyRuntimeResult.Hidden
                }
            }
        }
    }

    private fun reportRuntimeReadFailed(errorClass: KClass<out Throwable>) {
        libraryDiagnostics.recordSafely {
            runtimeReadFailed(source, errorClass)
        }
    }

    private suspend fun providerRow(key: OwnedCopyKey): EpicGame? {
        val identity = try {
            EpicStableSourceId.decode(key.stableSourceId)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return epicGameDao.getByProviderIdentity(identity.first, identity.second)
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

    private suspend fun hasFreshProof(
        key: OwnedCopyKey,
        accountScope: AccountScope,
        generation: Long,
    ): Boolean = currentAccountProof {
        accountScopeProvider.current(source) == accountScope &&
            accountLifecycleState.generation(source) == generation &&
            accountLifecycleState.readyGeneration(source) == generation &&
            isOwned(key, accountScope, generation)
    }

    private suspend fun unavailableIfFresh(
        key: OwnedCopyKey,
        accountScope: AccountScope,
        generation: Long,
        reason: CopyUnavailableReason,
        error: Exception? = null,
    ): OwnedCopyRuntimeResult = if (hasFreshProof(key, accountScope, generation)) {
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

    private suspend fun isCurrent(accountScope: AccountScope, generation: Long): Boolean =
        currentAccountProof {
            accountScopeProvider.current(source) == accountScope &&
                accountLifecycleState.generation(source) == generation &&
                accountLifecycleState.readyGeneration(source) == generation
        }

    private fun available(
        key: OwnedCopyKey,
        reference: SourceOwnedCopyReference.Epic,
        game: EpicGame,
        sourceState: OwnedCopyVolatileState,
        localLastPlayed: Long?,
    ): OwnedCopyRuntimeResult.Available {
        val item = LibraryItem(
            appId = sourceAppId(source, game.id),
            name = game.title,
            iconHash = game.artSquare.ifEmpty { game.artCover },
            capsuleImageUrl = game.artCover.ifEmpty { game.artSquare },
            headerImageUrl = game.artPortrait.ifEmpty { game.artSquare.ifEmpty { game.artCover } },
            heroImageUrl = game.artPortrait.ifEmpty { game.artSquare.ifEmpty { game.artCover } },
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
                appType = CanonicalNormalization.appType(game.type),
                genreKeys = sourceQualifiedKeys("epic", game.genres),
                tagIds = emptySet(),
                featureKeys = emptySet(),
                iconUrl = game.artSquare.ifEmpty { game.artCover },
                capsuleImageUrl = game.artCover.ifEmpty { game.artSquare },
                headerImageUrl = game.artPortrait.ifEmpty { game.artSquare.ifEmpty { game.artCover } },
                heroImageUrl = game.artPortrait.ifEmpty { game.artSquare.ifEmpty { game.artCover } },
                gridHeroImageScale = 1f,
                installPath = sourceState.installPath,
                installedSizeBytes = sourceState.installedSizeBytes,
                branchOrVersion = sourceState.branchOrVersion,
                isInstalled = sourceState.isInstalled,
                isDownloading = sourceState.isDownloading,
                hasPartialDownload = sourceState.hasPartialDownload,
                updateAvailable = false,
                isShared = false,
                lastPlayedEpochMs = latestPositiveTimestamp(game.lastPlayed, localLastPlayed),
                playtimeMinutes = sourceState.playtimeMinutes,
                capabilities = capabilities(source, libraryItemPresent = true, sourceState),
            ),
        )
    }

    private fun SourceOwnedCopyReference.Epic.matches(game: EpicGame): Boolean =
        localRowId == game.id && namespace == game.namespace && catalogId == game.catalogId &&
            key.stableSourceId == EpicStableSourceId.encode(game.namespace, game.catalogId)

    private fun isExcluded(game: EpicGame): Boolean = game.isDLC ||
        game.namespace == "ue" || game.namespace == UNREAL_ENGINE_NAMESPACE

    private companion object {
        const val UNREAL_ENGINE_NAMESPACE = "89efe5924d3d467c839449ab6ab52e7f"
    }
}
