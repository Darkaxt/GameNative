package app.gamenative.library.canonical.runtime

import app.gamenative.PrefManager
import app.gamenative.data.AppInfo
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.SteamApp
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalNormalization
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.library.canonical.AccountLifecycleState
import app.gamenative.library.canonical.AccountScopeInvalidations
import app.gamenative.library.canonical.AccountScopeProvider
import app.gamenative.library.canonical.CanonicalDiagnosticSink
import app.gamenative.library.canonical.CopyUnavailableReason
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import app.gamenative.library.canonical.source.SteamOwnedCopySourceAdapter
import app.gamenative.service.SteamService
import app.gamenative.service.SteamUpdateCheckResult
import java.io.File
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

internal data class SteamUpdateRefreshRequest(
    val app: SteamApp,
    val branch: String,
    val licensedDepotIds: Set<Int>?,
    val installedDepotIds: Set<Int>,
)

internal data class SteamInstallationState(
    val path: String?,
    val isInstalled: Boolean,
    val hasPartialDownload: Boolean,
)

@Singleton
class SteamInstallationSnapshotReader @Inject constructor() {
    internal fun read(
        apps: List<SteamApp>,
        installRoots: List<String>,
        appInfos: Map<Int, AppInfo>,
        activeDownloadIds: Set<Int>,
        persistedPartialIds: Set<Int>,
        workshopPausedIds: Set<Int>,
    ): Map<Int, SteamInstallationState> {
        val directoriesByName = installRoots.asSequence()
            .flatMap { root ->
                File(root).listFiles()
                    .orEmpty()
                    .asSequence()
                    .filter(File::isDirectory)
            }
            .groupBy(File::getName)
        return apps.associate { app ->
            val names = sequenceOf(app.config.installDir, app.name)
                .filter(String::isNotBlank)
                .distinct()
                .toList()
            val imported = appInfos[app.id]
                ?.customInstallPath
                ?.takeIf(String::isNotBlank)
                ?.let(::File)
                ?.takeIf(File::isDirectory)
            val candidates = buildList {
                imported?.let(::add)
                names.forEach { name -> addAll(directoriesByName[name].orEmpty()) }
            }.distinctBy(File::getAbsolutePath)
            val completed = candidates.firstOrNull { candidate ->
                File(candidate, DOWNLOAD_COMPLETE_MARKER).isFile
            }
            val selected = completed ?: candidates.firstOrNull()
            val installed = completed != null
            val partial = app.id in activeDownloadIds ||
                app.id in persistedPartialIds ||
                app.id in workshopPausedIds ||
                (!installed && selected != null)
            app.id to SteamInstallationState(
                path = selected?.path,
                isInstalled = installed,
                hasPartialDownload = partial,
            )
        }
    }

    private companion object {
        const val DOWNLOAD_COMPLETE_MARKER = ".download_complete"
    }
}

internal data class SteamRuntimeInputs(
    val activeDownloadIds: Set<Int>,
    val appInfos: Map<Int, AppInfo>,
    val licensedDepotIds: Map<Int, Set<Int>>,
    val installations: Map<Int, SteamInstallationState>,
)

@Singleton
class SteamOwnedCopyRuntimeGateway @Inject constructor(
    private val installationReader: SteamInstallationSnapshotReader,
    @CanonicalIoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    internal suspend fun activeDownloadIds(): Set<Int> = withContext(ioDispatcher) {
        SteamService.getActiveDownloads()
            .filterValues { it.isActive() }
            .keys
    }

    internal suspend fun partialDownloadIds(): Set<Int> = withContext(ioDispatcher) {
        SteamService.getPartialDownloads().toSet()
    }

    internal suspend fun installedApps(): Map<Int, AppInfo> = withContext(ioDispatcher) {
        SteamService.getAllInstalledAppsSuspending().orEmpty().associateBy(AppInfo::id)
    }

    internal suspend fun licensedDepotIds(apps: List<SteamApp>): Map<Int, Set<Int>> =
        withContext(ioDispatcher) {
            SteamService.buildLicensedDepotMapSuspending(apps)
        }

    internal suspend fun installationSnapshot(
        apps: List<SteamApp>,
        appInfos: Map<Int, AppInfo>,
        activeDownloadIds: Set<Int>,
        persistedPartialIds: Set<Int>,
    ): Map<Int, SteamInstallationState> = withContext(ioDispatcher) {
        installationReader.read(
            apps = apps,
            installRoots = SteamService.allInstallPaths,
            appInfos = appInfos,
            activeDownloadIds = activeDownloadIds,
            persistedPartialIds = persistedPartialIds,
            workshopPausedIds = SteamService.workshopPausedApps.toSet(),
        )
    }

    internal suspend fun refreshUpdates(
        requests: List<SteamUpdateRefreshRequest>,
    ): Map<Int, UpdateRefreshOutcome> = withContext(ioDispatcher) {
        SteamService.getUpdatePendingBatch(
            apps = requests.map(SteamUpdateRefreshRequest::app),
            branches = requests.associate { it.app.id to it.branch },
            licensedDepotIds = requests.mapNotNull { request ->
                request.licensedDepotIds?.let { request.app.id to it }
            }.toMap(),
            installedDepotIds = requests.associate { request ->
                request.app.id to request.installedDepotIds
            },
        ).mapValues { (_, result) ->
            when (result) {
                is SteamUpdateCheckResult.Failed -> UpdateRefreshOutcome.Failed(result.errorClass)
                is SteamUpdateCheckResult.Observed ->
                    UpdateRefreshOutcome.Observed(result.updateAvailable)
            }
        }
    }
}

class SteamObservedUpdateState private constructor(
    private val gateway: SteamOwnedCopyRuntimeGateway,
    scope: CoroutineScope,
    nowMonotonicMs: () -> Long,
    isOwnerCurrent: suspend (UpdateObservationOwner) -> Boolean,
    retirements: Flow<UpdateObservationLifecycle>,
    diagnostics: CanonicalDiagnosticSink?,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) {
    @Inject
    constructor(
        gateway: SteamOwnedCopyRuntimeGateway,
        accountScopeProvider: AccountScopeProvider,
        accountLifecycleState: AccountLifecycleState,
        diagnostics: CanonicalDiagnosticSink,
        @CanonicalIoDispatcher dispatcher: CoroutineDispatcher,
    ) : this(
        gateway = gateway,
        scope = CoroutineScope(SupervisorJob() + dispatcher),
        nowMonotonicMs = { System.nanoTime() / 1_000_000L },
        isOwnerCurrent = { owner ->
            accountScopeProvider.current(GameSource.STEAM) == owner.accountScope &&
                accountLifecycleState.generation(GameSource.STEAM) == owner.generation &&
                accountLifecycleState.readyGeneration(GameSource.STEAM) == owner.generation
        },
        retirements = AccountScopeInvalidations.forSource(GameSource.STEAM).map {
            UpdateObservationLifecycle(
                accountScope = accountScopeProvider.current(GameSource.STEAM),
                generation = accountLifecycleState.generation(GameSource.STEAM),
            )
        },
        diagnostics = diagnostics,
        marker = Unit,
    )

    internal constructor(
        gateway: SteamOwnedCopyRuntimeGateway,
        scope: CoroutineScope,
        nowMonotonicMs: () -> Long,
    ) : this(gateway, scope, nowMonotonicMs, { true }, emptyFlow(), null, Unit)

    internal constructor(
        gateway: SteamOwnedCopyRuntimeGateway,
        scope: CoroutineScope,
        nowMonotonicMs: () -> Long,
        diagnostics: CanonicalDiagnosticSink,
    ) : this(gateway, scope, nowMonotonicMs, { true }, emptyFlow(), diagnostics, Unit)

    internal constructor(
        gateway: SteamOwnedCopyRuntimeGateway,
        scope: CoroutineScope,
        nowMonotonicMs: () -> Long,
        isOwnerCurrent: suspend (UpdateObservationOwner) -> Boolean,
    ) : this(gateway, scope, nowMonotonicMs, isOwnerCurrent, emptyFlow(), null, Unit)

    internal constructor(
        gateway: SteamOwnedCopyRuntimeGateway,
        scope: CoroutineScope,
        nowMonotonicMs: () -> Long,
        isOwnerCurrent: suspend (UpdateObservationOwner) -> Boolean,
        retirements: Flow<UpdateObservationLifecycle>,
    ) : this(gateway, scope, nowMonotonicMs, isOwnerCurrent, retirements, null, Unit)

    private val store = ObservedUpdateStateStore<Int, SteamUpdateRefreshRequest>(
        scope = scope,
        nowMonotonicMs = nowMonotonicMs,
        ttlMs = UPDATE_TTL_MS,
        retryDelayMs = UPDATE_RETRY_MS,
        maxEntries = MAX_UPDATE_ENTRIES,
        maxRefreshBatch = MAX_UPDATE_REFRESH_BATCH,
        refreshTimeoutMs = UPDATE_REFRESH_TIMEOUT_MS,
        isOwnerCurrent = isOwnerCurrent,
        onRefreshFailure = { errorClass ->
            diagnostics?.updateObservationFailed(GameSource.STEAM, errorClass)
        },
        refresh = { requests ->
            gateway.refreshUpdates(requests.map { it.fingerprint })
        },
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
        apps: List<SteamApp>,
        inputs: SteamRuntimeInputs,
        coverage: UpdateSnapshotCoverage = UpdateSnapshotCoverage.POINT,
    ): Map<Int, UpdateObservation> = store.snapshot(
        owner,
        apps.associate { app ->
            val appInfo = inputs.appInfos[app.id]
            val installed = inputs.installations[app.id]?.isInstalled == true
            app.id to SteamUpdateRefreshRequest(
                app = app,
                branch = appInfo?.branch?.takeIf { installed } ?: "public",
                licensedDepotIds = inputs.licensedDepotIds[app.id],
                installedDepotIds = appInfo
                    ?.let { it.downloadedDepots + it.dlcDepots }
                    .orEmpty()
                    .toSet(),
            )
        },
        coverage,
    )

    private companion object {
        const val UPDATE_TTL_MS = 5 * 60_000L
        const val UPDATE_RETRY_MS = 15_000L
        const val UPDATE_REFRESH_TIMEOUT_MS = 30_000L
        const val MAX_UPDATE_ENTRIES = 2_048
        const val MAX_UPDATE_REFRESH_BATCH = 100
    }
}

internal data class SteamRuntimeBatchResult(
    val states: Map<Int, OwnedCopyVolatileState>,
    val failures: Map<Int, KClass<out Throwable>>,
)

@Singleton
class SteamOwnedCopyRuntimeState @Inject constructor(
    private val gateway: SteamOwnedCopyRuntimeGateway,
    private val observedUpdates: SteamObservedUpdateState = SteamObservedUpdateState(
        gateway = gateway,
        scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
        nowMonotonicMs = { System.nanoTime() / 1_000_000L },
    ),
) {

    internal suspend fun readPoint(
        app: SteamApp,
        accountScope: AccountScope,
        generation: Long,
    ): OwnedCopyVolatileState {
        val inputs = readInputs(listOf(app))
        val observation = if (inputs.installations[app.id]?.isInstalled == true) {
            observedUpdates.snapshot(
                UpdateObservationOwner(accountScope, generation),
                listOf(app),
                inputs,
                UpdateSnapshotCoverage.POINT,
            ).getValue(app.id)
        } else {
            UpdateObservation.CURRENT
        }
        return stateFor(app, inputs, observation)
    }

    internal suspend fun readBatch(
        apps: List<SteamApp>,
        accountScope: AccountScope,
        generation: Long,
    ): SteamRuntimeBatchResult {
        if (apps.isEmpty()) return SteamRuntimeBatchResult(emptyMap(), emptyMap())
        val inputs = readInputs(apps)
        val observedApps = apps.filter { app ->
            inputs.installations[app.id]?.isInstalled == true
        }
        val observations = observedUpdates.snapshot(
            UpdateObservationOwner(accountScope, generation),
            observedApps,
            inputs,
            UpdateSnapshotCoverage.COMPLETE,
        )
        val states = mutableMapOf<Int, OwnedCopyVolatileState>()
        val failures = mutableMapOf<Int, KClass<out Throwable>>()
        apps.forEach { app ->
            try {
                states[app.id] = stateFor(
                    app,
                    inputs,
                    observations[app.id] ?: UpdateObservation.CURRENT,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failures[app.id] = error::class
            }
        }
        return SteamRuntimeBatchResult(states, failures)
    }

    internal fun updateInvalidations(): Flow<Unit> = observedUpdates.invalidations()

    private suspend fun readInputs(apps: List<SteamApp>): SteamRuntimeInputs {
        val activeDownloadIds = gateway.activeDownloadIds()
        val persistedPartialIds = gateway.partialDownloadIds()
        val appInfos = gateway.installedApps()
        return SteamRuntimeInputs(
            activeDownloadIds = activeDownloadIds,
            appInfos = appInfos,
            licensedDepotIds = gateway.licensedDepotIds(apps),
            installations = gateway.installationSnapshot(
                apps,
                appInfos,
                activeDownloadIds,
                persistedPartialIds,
            ),
        )
    }

    private fun stateFor(
        app: SteamApp,
        inputs: SteamRuntimeInputs,
        updateObservation: UpdateObservation,
    ): OwnedCopyVolatileState {
        val appInfo = inputs.appInfos[app.id]
        val installation = inputs.installations.getValue(app.id)
        val installed = installation.isInstalled
        val branch = appInfo?.branch?.takeIf { installed }
        val resolvedSize = SteamService.resolveDownloadableDepots(
            depots = app.depots,
            preferredLanguage = "",
            ownedDlc = emptyMap(),
            licensedDepotIds = inputs.licensedDepotIds[app.id],
        ).values.sumOf { depot ->
            depot.manifests[branch ?: "public"]?.size
                ?: depot.manifests.values.firstOrNull()?.size
                ?: 0L
        }.positiveOrNull()
        return OwnedCopyVolatileState(
            installPath = installation.path,
            installedSizeBytes = appInfo?.recoveredInstallSizeBytes?.positiveOrNull() ?: resolvedSize,
            branchOrVersion = branch,
            isInstalled = installed,
            isDownloading = app.id in inputs.activeDownloadIds,
            hasPartialDownload = installation.hasPartialDownload,
            updateAvailable = updateObservation == UpdateObservation.UPDATE_AVAILABLE,
            updateObservation = updateObservation,
            isShared = PrefManager.steamUserAccountId != 0 &&
                !app.ownerAccountId.contains(PrefManager.steamUserAccountId),
            playtimeMinutes = null,
        )
    }

}

@Singleton
class SteamOwnedCopyRuntimeAdapter @Inject constructor(
    private val steamAppDao: SteamAppDao,
    private val accountScopeProvider: AccountScopeProvider,
    private val accountLifecycleState: AccountLifecycleState,
    private val sourceAdapter: SteamOwnedCopySourceAdapter,
    private val playHistoryDao: LibraryPlayHistoryDao,
    private val runtimeState: SteamOwnedCopyRuntimeState,
    private val diagnostics: app.gamenative.library.canonical.CanonicalDiagnosticSink? = null,
) : OwnedCopyRuntimeAdapter {
    override val source: GameSource = GameSource.STEAM

    override fun invalidations(): Flow<Unit> = merge(
        sourceAdapter.invalidations(),
        runtimeState.updateInvalidations(),
    )

    override suspend fun resolve(key: OwnedCopyKey): OwnedCopyRuntimeResult {
        if (key.source != source) return OwnedCopyRuntimeResult.Hidden
        var ownershipProved = false
        var provedScope: app.gamenative.data.canonical.AccountScope? = null
        var provedGeneration: Long? = null
        var provedAppId: Int? = null
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
            val reference = sourceAdapter.resolve(key) as? SourceOwnedCopyReference.Steam
                ?: return OwnedCopyRuntimeResult.Hidden
            ownershipProved = true
            provedAppId = reference.appId
            val app = steamAppDao.findOwnedApp(reference.appId)
                ?: return unavailableIfFresh(
                    key,
                    reference.appId,
                    accountScope,
                    generation,
                    CopyUnavailableReason.SOURCE_ROW_CHANGED,
                )
            val sourceState = runtimeState.readPoint(app, accountScope, generation)
            val lastPlayed = playHistoryDao.pointLastPlayed(
                sourceAppId(source, reference.appId),
                source,
                diagnostics,
            )
            if (!hasFreshProof(reference.appId, accountScope, generation)) {
                return OwnedCopyRuntimeResult.Hidden
            }
            return available(key, reference, app, sourceState, lastPlayed)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val scope = provedScope
            val generation = provedGeneration
            val appId = provedAppId
            return if (ownershipProved && scope != null && generation != null && appId != null) {
                unavailableIfFresh(
                    key,
                    appId,
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
        var provedScope: app.gamenative.data.canonical.AccountScope? = null
        var generation: Long? = null
        var provedIds: Set<Int> = emptySet()
        var completedFinalOwnedIds: Set<Int>? = null
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
            val rows = steamAppDao._getAllOwnedAppsPaged()
            val rowsById = rows.associateBy(SteamApp::id)
            provedIds = rowsById.keys
            val requestedRows = keys.mapNotNull { key ->
                key.steamAppIdOrNull()
                    ?.takeIf { key.source == source && key.accountScope == accountScope }
                    ?.let(rowsById::get)
            }.distinctBy(SteamApp::id)
            val stateBatch = runtimeState.readBatch(requestedRows, accountScope, generation)
            val history = playHistoryDao.batchLastPlayed(source, diagnostics)
            val finalOwnedIds = finalOwnedIds() ?: return keys.hiddenResults()
            completedFinalOwnedIds = finalOwnedIds
            if (!isCurrent(accountScope, generation)) return keys.hiddenResults()
            keys.associateWith { key ->
                val appId = key.steamAppIdOrNull()
                val app = appId?.let(rowsById::get)
                val sourceState = appId?.let(stateBatch.states::get)
                val stateFailure = appId?.let(stateBatch.failures::get)
                when {
                    key.source != source || key.accountScope != accountScope ->
                        OwnedCopyRuntimeResult.Hidden
                    appId == null || appId !in provedIds || appId !in finalOwnedIds ->
                        OwnedCopyRuntimeResult.Hidden
                    app == null -> OwnedCopyRuntimeResult.Hidden
                    stateFailure != null -> unavailable(
                        key,
                        CopyUnavailableReason.SOURCE_READ_FAILED,
                        stateFailure,
                    )
                    sourceState == null -> unavailable(key, CopyUnavailableReason.SOURCE_ROW_CHANGED)
                    else -> available(
                        key = key,
                        reference = SourceOwnedCopyReference.Steam(key, appId),
                        app = app,
                        sourceState = sourceState,
                        lastPlayed = history[sourceAppId(source, appId)],
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val scope = provedScope ?: return keys.hiddenResults()
            val currentGeneration = generation ?: return keys.hiddenResults()
            val finalOwnedIds = completedFinalOwnedIds
                ?: finalOwnedIds()
                ?: return keys.hiddenResults()
            if (!isCurrent(scope, currentGeneration)) return keys.hiddenResults()
            keys.associateWith { key ->
                val appId = key.steamAppIdOrNull()
                if (
                    key.source == source && key.accountScope == scope &&
                    appId != null && appId in provedIds && appId in finalOwnedIds
                ) {
                    unavailable(key, CopyUnavailableReason.SOURCE_READ_FAILED, error)
                } else {
                    OwnedCopyRuntimeResult.Hidden
                }
            }
        }
    }

    private suspend fun hasFreshProof(
        appId: Int,
        accountScope: app.gamenative.data.canonical.AccountScope,
        generation: Long,
    ): Boolean = currentAccountProof {
        accountScopeProvider.current(source) == accountScope &&
            accountLifecycleState.generation(source) == generation &&
            accountLifecycleState.readyGeneration(source) == generation &&
            steamAppDao.findOwnedApp(appId) != null
    }

    private suspend fun unavailableIfFresh(
        key: OwnedCopyKey,
        appId: Int,
        accountScope: app.gamenative.data.canonical.AccountScope,
        generation: Long,
        reason: CopyUnavailableReason,
        error: Exception? = null,
    ): OwnedCopyRuntimeResult = if (hasFreshProof(appId, accountScope, generation)) {
        unavailable(key, reason, error)
    } else {
        OwnedCopyRuntimeResult.Hidden
    }

    private suspend fun finalOwnedIds(): Set<Int>? = try {
        steamAppDao._getAllOwnedAppsPaged().mapTo(mutableSetOf(), SteamApp::id)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private suspend fun isCurrent(
        accountScope: app.gamenative.data.canonical.AccountScope,
        generation: Long,
    ): Boolean = currentAccountProof {
        accountScopeProvider.current(source) == accountScope &&
            accountLifecycleState.generation(source) == generation &&
            accountLifecycleState.readyGeneration(source) == generation
    }

    private fun available(
        key: OwnedCopyKey,
        reference: SourceOwnedCopyReference.Steam,
        app: SteamApp,
        sourceState: OwnedCopyVolatileState,
        lastPlayed: Long?,
    ): OwnedCopyRuntimeResult.Available {
        val item = LibraryItem(
            appId = sourceAppId(source, reference.appId),
            name = app.name,
            iconHash = app.clientIconHash,
            capsuleImageUrl = app.getCapsuleUrl(),
            headerImageUrl = app.headerUrl,
            heroImageUrl = app.getHeroUrl(),
            isShared = sourceState.isShared,
            gameSource = source,
            sizeBytes = sourceState.installedSizeBytes ?: 0L,
            isInstalled = sourceState.isInstalled,
        )
        return OwnedCopyRuntimeResult.Available(
            OwnedCopyRuntime(
                key = key,
                reference = reference,
                libraryItem = item,
                nativeTitle = app.name,
                aliases = emptySet(),
                developerKey = CanonicalNormalization.developerKey(app.developer),
                releaseYear = CanonicalNormalization.releaseYear(app.releaseDate),
                appType = CanonicalNormalization.appType(app.type),
                genreKeys = app.genreIds.asSequence()
                    .filter { it > 0 }
                    .map { "steam:$it" }
                    .toSortedSet(),
                tagIds = app.storeTagIds.filter { it > 0 }.toSortedSet(),
                featureKeys = app.categoryIds.asSequence()
                    .filter { it > 0 }
                    .map { "steam:$it" }
                    .toSortedSet(),
                iconUrl = app.clientIconUrl,
                capsuleImageUrl = app.getCapsuleUrl(),
                headerImageUrl = app.headerUrl,
                heroImageUrl = app.getHeroUrl(),
                gridHeroImageScale = 1f,
                installPath = sourceState.installPath,
                installedSizeBytes = sourceState.installedSizeBytes,
                branchOrVersion = sourceState.branchOrVersion,
                isInstalled = sourceState.isInstalled,
                isDownloading = sourceState.isDownloading,
                hasPartialDownload = sourceState.hasPartialDownload,
                updateAvailable = sourceState.updateAvailable,
                isShared = sourceState.isShared,
                lastPlayedEpochMs = lastPlayed,
                playtimeMinutes = sourceState.playtimeMinutes,
                capabilities = capabilities(source, libraryItemPresent = true, sourceState),
            ),
        )
    }

    private fun OwnedCopyKey.steamAppIdOrNull(): Int? = stableSourceId.toIntOrNull()
        ?.takeIf { it > 0 && it.toString() == stableSourceId }
}
