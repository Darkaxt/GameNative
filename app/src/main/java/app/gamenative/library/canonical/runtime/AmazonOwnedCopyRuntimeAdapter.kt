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
import app.gamenative.library.canonical.AccountScopeInvalidations
import app.gamenative.library.canonical.AccountScopeProvider
import app.gamenative.library.canonical.CanonicalDiagnosticSink
import app.gamenative.library.canonical.CanonicalLibraryDiagnosticSink
import app.gamenative.library.canonical.NoOpCanonicalLibraryDiagnosticSink
import app.gamenative.library.canonical.recordSafely
import app.gamenative.library.canonical.CopyUnavailableReason
import app.gamenative.library.canonical.source.AmazonOwnedCopySourceAdapter
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import app.gamenative.library.canonical.source.preferredAmazonRows
import app.gamenative.service.amazon.AmazonArtwork
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.amazon.AmazonUpdateVersionRequest
import app.gamenative.service.amazon.AmazonUpdateVersionResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
class AmazonOwnedCopyRuntimeGateway @Inject constructor(
    @ApplicationContext private val context: Context,
    @CanonicalIoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val accountScopeProvider: AccountScopeProvider,
    private val accountLifecycleState: AccountLifecycleState,
) {
    internal suspend fun activeDownloadProductIds(): Set<String> = withContext(ioDispatcher) {
        AmazonService.getActiveDownloads()
            .filterValues { it.isActive() }
            .keys
    }

    internal suspend fun partialDownloadProductIds(): Set<String> = withContext(ioDispatcher) {
        AmazonService.getPartialDownloads(context).toSet()
    }

    internal suspend fun refreshUpdates(
        expectedOwner: UpdateObservationOwner,
        requests: List<UpdateObservationRequest<String, String>>,
    ): Map<String, UpdateRefreshOutcome> = withContext(ioDispatcher) {
        AmazonService.getUpdatePendingBatch(
            requests = requests.map { request ->
                AmazonUpdateVersionRequest(
                    productId = request.key,
                    storedVersionId = request.fingerprint,
                )
            },
            expectedOwnerIsCurrent = {
                accountScopeProvider.current(GameSource.AMAZON) == expectedOwner.accountScope &&
                    accountLifecycleState.generation(GameSource.AMAZON) == expectedOwner.generation &&
                    accountLifecycleState.readyGeneration(GameSource.AMAZON) == expectedOwner.generation
            },
        ).mapValues { (_, result) ->
            when (result) {
                is AmazonUpdateVersionResult.Failed ->
                    UpdateRefreshOutcome.Failed(result.errorClass)
                is AmazonUpdateVersionResult.Observed ->
                    UpdateRefreshOutcome.Observed(result.updateAvailable)
            }
        }
    }
}

class AmazonObservedUpdateState private constructor(
    private val gateway: AmazonOwnedCopyRuntimeGateway,
    scope: CoroutineScope,
    nowMonotonicMs: () -> Long,
    isOwnerCurrent: suspend (UpdateObservationOwner) -> Boolean,
    retirements: Flow<UpdateObservationLifecycle>,
    diagnostics: CanonicalDiagnosticSink?,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) {
    @Inject
    constructor(
        gateway: AmazonOwnedCopyRuntimeGateway,
        accountScopeProvider: AccountScopeProvider,
        accountLifecycleState: AccountLifecycleState,
        diagnostics: CanonicalDiagnosticSink,
        @CanonicalIoDispatcher dispatcher: CoroutineDispatcher,
    ) : this(
        gateway = gateway,
        scope = CoroutineScope(SupervisorJob() + dispatcher),
        nowMonotonicMs = { System.nanoTime() / 1_000_000L },
        isOwnerCurrent = { owner ->
            accountScopeProvider.current(GameSource.AMAZON) == owner.accountScope &&
                accountLifecycleState.generation(GameSource.AMAZON) == owner.generation &&
                accountLifecycleState.readyGeneration(GameSource.AMAZON) == owner.generation
        },
        retirements = AccountScopeInvalidations.forSource(GameSource.AMAZON).map {
            UpdateObservationLifecycle(
                accountScope = accountScopeProvider.current(GameSource.AMAZON),
                generation = accountLifecycleState.generation(GameSource.AMAZON),
            )
        },
        diagnostics = diagnostics,
        marker = Unit,
    )

    internal constructor(
        gateway: AmazonOwnedCopyRuntimeGateway,
        scope: CoroutineScope,
        nowMonotonicMs: () -> Long,
    ) : this(gateway, scope, nowMonotonicMs, { true }, emptyFlow(), null, Unit)

    internal constructor(
        gateway: AmazonOwnedCopyRuntimeGateway,
        scope: CoroutineScope,
        nowMonotonicMs: () -> Long,
        diagnostics: CanonicalDiagnosticSink,
    ) : this(gateway, scope, nowMonotonicMs, { true }, emptyFlow(), diagnostics, Unit)

    internal constructor(
        gateway: AmazonOwnedCopyRuntimeGateway,
        scope: CoroutineScope,
        nowMonotonicMs: () -> Long,
        isOwnerCurrent: suspend (UpdateObservationOwner) -> Boolean,
    ) : this(gateway, scope, nowMonotonicMs, isOwnerCurrent, emptyFlow(), null, Unit)

    internal constructor(
        gateway: AmazonOwnedCopyRuntimeGateway,
        scope: CoroutineScope,
        nowMonotonicMs: () -> Long,
        isOwnerCurrent: suspend (UpdateObservationOwner) -> Boolean,
        retirements: Flow<UpdateObservationLifecycle>,
    ) : this(gateway, scope, nowMonotonicMs, isOwnerCurrent, retirements, null, Unit)

    private val store = ObservedUpdateStateStore<String, String>(
        scope = scope,
        nowMonotonicMs = nowMonotonicMs,
        ttlMs = UPDATE_TTL_MS,
        retryDelayMs = UPDATE_RETRY_MS,
        maxEntries = MAX_UPDATE_ENTRIES,
        maxRefreshBatch = MAX_UPDATE_REFRESH_BATCH,
        refreshTimeoutMs = UPDATE_REFRESH_TIMEOUT_MS,
        isOwnerCurrent = isOwnerCurrent,
        onRefreshFailure = { errorClass ->
            diagnostics?.updateObservationFailed(GameSource.AMAZON, errorClass)
        },
        ownerAwareRefresh = { owner, requests -> gateway.refreshUpdates(owner, requests) },
    )

    init {
        scope.launch {
            retirements.collect { lifecycle ->
                store.transitionLifecycle(lifecycle.accountScope, lifecycle.generation)
            }
        }
    }

    internal fun invalidations(): Flow<Unit> = store.invalidations()

    internal fun snapshot(
        owner: UpdateObservationOwner,
        games: List<AmazonGame>,
        coverage: UpdateSnapshotCoverage = UpdateSnapshotCoverage.POINT,
    ): Map<String, UpdateObservation> = store.snapshot(
        owner,
        games.associate { game -> game.productId to game.versionId },
        coverage,
    )

    private companion object {
        const val UPDATE_TTL_MS = 5 * 60_000L
        const val UPDATE_RETRY_MS = 15_000L
        const val UPDATE_REFRESH_TIMEOUT_MS = 30_000L
        const val MAX_UPDATE_ENTRIES = 2_048
        const val MAX_UPDATE_REFRESH_BATCH = 32
    }
}

internal data class AmazonRuntimeBatchResult(
    val states: Map<Int, OwnedCopyVolatileState>,
    val failures: Map<Int, kotlin.reflect.KClass<out Throwable>>,
) : Map<Int, OwnedCopyVolatileState> by states

@Singleton
class AmazonOwnedCopyRuntimeState @Inject constructor(
    private val gateway: AmazonOwnedCopyRuntimeGateway,
    private val observedUpdates: AmazonObservedUpdateState = AmazonObservedUpdateState(
        gateway = gateway,
        scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
        nowMonotonicMs = { System.nanoTime() / 1_000_000L },
    ),
) {

    internal suspend fun readPoint(
        game: AmazonGame,
        accountScope: AccountScope,
        generation: Long,
    ): OwnedCopyVolatileState {
        val activeDownloads = gateway.activeDownloadProductIds()
        val partialDownloads = gateway.partialDownloadProductIds() + activeDownloads
        val observation = if (game.isInstalled) {
            observedUpdates.snapshot(
                UpdateObservationOwner(accountScope, generation),
                listOf(game),
                UpdateSnapshotCoverage.POINT,
            ).getValue(game.productId)
        } else {
            UpdateObservation.CURRENT
        }
        return stateFor(game, activeDownloads, partialDownloads, observation)
    }

    internal suspend fun readBatch(
        games: List<AmazonGame>,
        accountScope: AccountScope,
        generation: Long,
    ): AmazonRuntimeBatchResult {
        if (games.isEmpty()) {
            observedUpdates.snapshot(
                UpdateObservationOwner(accountScope, generation),
                emptyList(),
                UpdateSnapshotCoverage.COMPLETE,
            )
            return AmazonRuntimeBatchResult(emptyMap(), emptyMap())
        }
        val activeDownloads = gateway.activeDownloadProductIds()
        val partialDownloads = gateway.partialDownloadProductIds() + activeDownloads
        val observations = observedUpdates.snapshot(
            UpdateObservationOwner(accountScope, generation),
            games.filter(AmazonGame::isInstalled),
            UpdateSnapshotCoverage.COMPLETE,
        )
        val states = mutableMapOf<Int, OwnedCopyVolatileState>()
        val failures = mutableMapOf<Int, kotlin.reflect.KClass<out Throwable>>()
        games.forEach { game ->
            try {
                states[game.appId] = stateFor(
                    game,
                    activeDownloads,
                    partialDownloads,
                    observations[game.productId] ?: UpdateObservation.CURRENT,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failures[game.appId] = error::class
            }
        }
        return AmazonRuntimeBatchResult(states, failures)
    }

    internal fun updateInvalidations(): Flow<Unit> = observedUpdates.invalidations()

    private fun stateFor(
        game: AmazonGame,
        activeDownloads: Set<String>,
        partialDownloads: Set<String>,
        updateObservation: UpdateObservation,
    ): OwnedCopyVolatileState = OwnedCopyVolatileState(
        installPath = game.installPath.takeIf(String::isNotBlank),
        installedSizeBytes = game.installSize.positiveOrNull(),
        branchOrVersion = game.versionId.takeIf(String::isNotBlank),
        isInstalled = game.isInstalled,
        isDownloading = game.productId in activeDownloads,
        hasPartialDownload = game.productId in partialDownloads,
        updateAvailable = game.isInstalled &&
            updateObservation == UpdateObservation.UPDATE_AVAILABLE,
        updateObservation = if (game.isInstalled) {
            updateObservation
        } else {
            UpdateObservation.CURRENT
        },
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
    private val diagnostics: CanonicalDiagnosticSink? = null,
    private val libraryDiagnostics: CanonicalLibraryDiagnosticSink =
        NoOpCanonicalLibraryDiagnosticSink,
) : OwnedCopyRuntimeAdapter {
    override val source: GameSource = GameSource.AMAZON

    override fun invalidations(): Flow<Unit> = merge(
        sourceAdapter.invalidations(),
        runtimeState.updateInvalidations(),
    )

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
            val localLastPlayed = playHistoryDao.pointLastPlayed(
                sourceAppId(source, game.appId),
                source,
                diagnostics,
            )
            if (!hasFreshProof(key, accountScope, generation, entitlementId)) {
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
        var provedScope: AccountScope? = null
        var generation: Long? = null
        var initialLedger: CompletedOwnedCopySnapshot? = null
        var completedFinalLedger: CompletedOwnedCopySnapshot? = null
        var runtimeReadFailureReported = false
        fun reportRuntimeReadFailedOnce(errorClass: KClass<out Throwable>) {
            if (!runtimeReadFailureReported) {
                reportRuntimeReadFailed(errorClass)
                runtimeReadFailureReported = true
            }
        }
        return try {
            generation = accountLifecycleState.generation(source)
            val accountScope = accountScopeProvider.current(source)
                ?: return keys.hiddenResults()
            provedScope = accountScope
            if (accountLifecycleState.readyGeneration(source) != generation) {
                return keys.hiddenResults()
            }
            if (keys.none { it.source == source && it.accountScope == accountScope }) {
                runtimeState.readBatch(emptyList(), accountScope, generation)
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
                runtimeState.readBatch(emptyList(), accountScope, generation)
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
            val stateBatch = runtimeState.readBatch(requestedRows, accountScope, generation)
            stateBatch.failures.entries.minByOrNull { it.key }?.value
                ?.let(::reportRuntimeReadFailedOnce)
            val history = playHistoryDao.batchLastPlayed(source, diagnostics)
            val finalLedger = finalLedger(accountScope, generation) ?: return keys.hiddenResults()
            completedFinalLedger = finalLedger
            if (!isCurrent(accountScope, generation)) return keys.hiddenResults()
            keys.associateWith { key ->
                val entitlementId = ledger.resolvedSourceIds[key.stableSourceId]
                val game = rowsByProduct[key.stableSourceId]
                val sourceState = game?.let { stateBatch.states[it.appId] }
                val stateFailure = game?.let { stateBatch.failures[it.appId] }
                when {
                    key.source != source || key.accountScope != accountScope ->
                        OwnedCopyRuntimeResult.Hidden
                    !referenceIsCurrent(key.stableSourceId, ledger, finalLedger) ->
                        OwnedCopyRuntimeResult.Hidden
                    stateFailure != null -> unavailable(
                        key,
                        CopyUnavailableReason.SOURCE_READ_FAILED,
                        stateFailure,
                    )
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
            reportRuntimeReadFailedOnce(error::class)
            val scope = provedScope ?: return keys.hiddenResults()
            val currentGeneration = generation ?: return keys.hiddenResults()
            val ledger = initialLedger ?: return keys.hiddenResults()
            val finalLedger = completedFinalLedger
                ?: finalLedger(scope, currentGeneration)
                ?: return keys.hiddenResults()
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

    private fun reportRuntimeReadFailed(errorClass: KClass<out Throwable>) {
        libraryDiagnostics.recordSafely {
            runtimeReadFailed(source, errorClass)
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
