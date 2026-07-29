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
import app.gamenative.library.canonical.AccountScopeProvider
import app.gamenative.library.canonical.CopyUnavailableReason
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import app.gamenative.library.canonical.source.SteamOwnedCopySourceAdapter
import app.gamenative.service.SteamService
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

@Singleton
class SteamOwnedCopyRuntimeGateway @Inject constructor() {
    internal suspend fun activeDownloadIds(): Set<Int> =
        SteamService.getActiveDownloads()
            .filterValues { it.isActive() }
            .keys

    internal suspend fun partialDownloadIds(): Set<Int> =
        SteamService.getPartialDownloads().toSet()

    internal suspend fun installedApps(): Map<Int, AppInfo> =
        SteamService.getAllInstalledApps().orEmpty().associateBy(AppInfo::id)

    internal suspend fun licensedDepotIds(apps: List<SteamApp>): Map<Int, Set<Int>> =
        SteamService.buildLicensedDepotMap(apps)

    internal suspend fun installPaths(
        apps: List<SteamApp>,
        installedApps: Map<Int, AppInfo>,
        partialDownloadIds: Set<Int>,
    ): Map<Int, String> {
        val foldersByName = SteamService.allInstallPaths
            .asSequence()
            .flatMap { root ->
                File(root).listFiles()
                    .orEmpty()
                    .asSequence()
                    .filter(File::isDirectory)
            }
            .groupBy(File::getName)
        return apps.mapNotNull { app ->
            val installed = installedApps[app.id]
            if (installed == null && app.id !in partialDownloadIds) return@mapNotNull null
            val path = installed?.customInstallPath?.takeIf(String::isNotBlank)
                ?: sequenceOf(SteamService.getAppDirName(app), app.name)
                    .filter(String::isNotBlank)
                    .mapNotNull { foldersByName[it]?.firstOrNull()?.path }
                    .firstOrNull()
                ?: Paths.get(
                    if (PrefManager.useExternalStorage) {
                        SteamService.externalAppInstallPath
                    } else {
                        SteamService.internalAppInstallPath
                    },
                    SteamService.getAppDirName(app),
                ).toString()
            app.id to path
        }.toMap()
    }

    internal suspend fun updatePending(appId: Int, branch: String): Boolean =
        SteamService.isUpdatePending(appId, branch)
}

@Singleton
class SteamOwnedCopyRuntimeState @Inject constructor(
    private val gateway: SteamOwnedCopyRuntimeGateway,
) {
    private val updateCache = ConcurrentHashMap<SteamUpdateCacheKey, Boolean>()

    internal suspend fun readPoint(
        app: SteamApp,
        accountScope: AccountScope,
        generation: Long,
    ): OwnedCopyVolatileState {
        val inputs = readInputs(listOf(app))
        val installed = inputs.installedApps[app.id]?.isDownloaded == true
        val branch = inputs.installedApps[app.id]?.branch?.takeIf { installed }
        val updateAvailable = installed && gateway.updatePending(app.id, branch ?: "public")
        updateCache[SteamUpdateCacheKey(accountScope, generation, app.id)] = updateAvailable
        return stateFor(app, inputs, updateAvailable)
    }

    internal suspend fun readBatch(
        apps: List<SteamApp>,
        accountScope: AccountScope,
        generation: Long,
    ): Map<Int, OwnedCopyVolatileState> {
        if (apps.isEmpty()) return emptyMap()
        val inputs = readInputs(apps)
        val updateSnapshot = updateCache.toMap()
        return apps.mapNotNull { app ->
            try {
                val cacheKey = SteamUpdateCacheKey(accountScope, generation, app.id)
                app.id to stateFor(app, inputs, updateSnapshot[cacheKey] == true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }.toMap()
    }

    private suspend fun readInputs(apps: List<SteamApp>): SteamRuntimeInputs {
        val activeDownloadIds = gateway.activeDownloadIds()
        val partialDownloadIds = gateway.partialDownloadIds() + activeDownloadIds
        val installedApps = gateway.installedApps()
        return SteamRuntimeInputs(
            activeDownloadIds = activeDownloadIds,
            partialDownloadIds = partialDownloadIds,
            installedApps = installedApps,
            licensedDepotIds = gateway.licensedDepotIds(apps),
            installPaths = gateway.installPaths(apps, installedApps, partialDownloadIds),
        )
    }

    private fun stateFor(
        app: SteamApp,
        inputs: SteamRuntimeInputs,
        updateAvailable: Boolean,
    ): OwnedCopyVolatileState {
        val appInfo = inputs.installedApps[app.id]
        val installed = appInfo?.isDownloaded == true
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
            installPath = inputs.installPaths[app.id],
            installedSizeBytes = appInfo?.recoveredInstallSizeBytes?.positiveOrNull() ?: resolvedSize,
            branchOrVersion = branch,
            isInstalled = installed,
            isDownloading = app.id in inputs.activeDownloadIds,
            hasPartialDownload = app.id in inputs.partialDownloadIds,
            updateAvailable = updateAvailable,
            isShared = PrefManager.steamUserAccountId != 0 &&
                !app.ownerAccountId.contains(PrefManager.steamUserAccountId),
            playtimeMinutes = null,
        )
    }

    private data class SteamUpdateCacheKey(
        val accountScope: AccountScope,
        val generation: Long,
        val appId: Int,
    )

    private data class SteamRuntimeInputs(
        val activeDownloadIds: Set<Int>,
        val partialDownloadIds: Set<Int>,
        val installedApps: Map<Int, AppInfo>,
        val licensedDepotIds: Map<Int, Set<Int>>,
        val installPaths: Map<Int, String>,
    )
}

@Singleton
class SteamOwnedCopyRuntimeAdapter @Inject constructor(
    private val steamAppDao: SteamAppDao,
    private val accountScopeProvider: AccountScopeProvider,
    private val accountLifecycleState: AccountLifecycleState,
    private val sourceAdapter: SteamOwnedCopySourceAdapter,
    private val playHistoryDao: LibraryPlayHistoryDao,
    private val runtimeState: SteamOwnedCopyRuntimeState,
) : OwnedCopyRuntimeAdapter {
    override val source: GameSource = GameSource.STEAM

    override fun invalidations(): Flow<Unit> = sourceAdapter.invalidations()

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
            val lastPlayed = playHistoryDao.pointLastPlayed(sourceAppId(source, reference.appId))
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
            val states = runtimeState.readBatch(requestedRows, accountScope, generation)
            val history = playHistoryDao.batchLastPlayed()
            val finalOwnedIds = finalOwnedIds() ?: return keys.hiddenResults()
            if (!isCurrent(accountScope, generation)) return keys.hiddenResults()
            keys.associateWith { key ->
                val appId = key.steamAppIdOrNull()
                val app = appId?.let(rowsById::get)
                val sourceState = appId?.let(states::get)
                when {
                    key.source != source || key.accountScope != accountScope ->
                        OwnedCopyRuntimeResult.Hidden
                    appId == null || appId !in provedIds || appId !in finalOwnedIds ->
                        OwnedCopyRuntimeResult.Hidden
                    app == null -> OwnedCopyRuntimeResult.Hidden
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
            val finalOwnedIds = finalOwnedIds() ?: return keys.hiddenResults()
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
