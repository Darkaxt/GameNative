package app.gamenative.library.canonical.runtime

import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.SteamApp
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
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

@Singleton
class SteamOwnedCopyRuntimeState @Inject constructor() {
    internal suspend fun read(apps: List<SteamApp>): Map<Int, OwnedCopyVolatileState> {
        if (apps.isEmpty()) return emptyMap()
        val activeDownloads = SteamService.getActiveDownloads()
        val partialDownloads = SteamService.getPartialDownloads().toSet()
        val licensedDepots = SteamService.buildLicensedDepotMap(apps)
        return apps.associate { app ->
            val installed = SteamService.isAppInstalled(app.id)
            val appInfo = SteamService.getInstalledApp(app.id)
            val downloading = activeDownloads[app.id]?.isActive() == true
            val partial = downloading || app.id in partialDownloads
            val branch = appInfo?.branch?.takeIf { installed }
            val resolvedSize = SteamService.resolveDownloadableDepots(
                depots = app.depots,
                preferredLanguage = "",
                ownedDlc = emptyMap(),
                licensedDepotIds = licensedDepots[app.id],
            ).values.sumOf { depot ->
                depot.manifests[branch ?: "public"]?.size
                    ?: depot.manifests.values.firstOrNull()?.size
                    ?: 0L
            }.positiveOrNull()
            val path = SteamService.getAppDirPath(app.id).takeIf { installed || partial }
            val updateAvailable = installed && SteamService.isUpdatePending(
                appId = app.id,
                branch = branch ?: "public",
            )
            val shared = PrefManager.steamUserAccountId != 0 &&
                !app.ownerAccountId.contains(PrefManager.steamUserAccountId)
            app.id to OwnedCopyVolatileState(
                installPath = path,
                installedSizeBytes = appInfo?.recoveredInstallSizeBytes?.positiveOrNull() ?: resolvedSize,
                branchOrVersion = branch,
                isInstalled = installed,
                isDownloading = downloading,
                hasPartialDownload = partial,
                updateAvailable = updateAvailable,
                isShared = shared,
                playtimeMinutes = null,
            )
        }
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
) : OwnedCopyRuntimeAdapter {
    override val source: GameSource = GameSource.STEAM

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
            val reference = sourceAdapter.resolve(key) as? SourceOwnedCopyReference.Steam
                ?: return OwnedCopyRuntimeResult.Hidden
            ownershipProved = true
            val app = steamAppDao.findOwnedApp(reference.appId)
                ?: return unavailable(key, CopyUnavailableReason.SOURCE_ROW_CHANGED)
            val sourceState = runtimeState.read(listOf(app))[reference.appId]
                ?: return unavailable(key, CopyUnavailableReason.SOURCE_ROW_CHANGED)
            val lastPlayed = playHistoryDao.pointLastPlayed(sourceAppId(source, reference.appId))
            if (!isCurrent(accountScope, generation)) return OwnedCopyRuntimeResult.Hidden
            if (steamAppDao.findOwnedApp(reference.appId) == null) {
                return OwnedCopyRuntimeResult.Hidden
            }
            return available(key, reference, app, sourceState, lastPlayed)
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
        var provedScope: app.gamenative.data.canonical.AccountScope? = null
        var provedIds: Set<Int> = emptySet()
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
            val rows = steamAppDao._getAllOwnedAppsPaged()
            val rowsById = rows.associateBy(SteamApp::id)
            provedIds = rowsById.keys
            val requestedRows = keys.mapNotNull { key ->
                key.steamAppIdOrNull()
                    ?.takeIf { key.source == source && key.accountScope == accountScope }
                    ?.let(rowsById::get)
            }.distinctBy(SteamApp::id)
            val states = runtimeState.read(requestedRows)
            val history = playHistoryDao.batchLastPlayed()
            if (!isCurrent(accountScope, generation)) return keys.hiddenResults()
            keys.associateWith { key ->
                val appId = key.steamAppIdOrNull()
                val app = appId?.let(rowsById::get)
                val sourceState = appId?.let(states::get)
                when {
                    key.source != source || key.accountScope != accountScope ->
                        OwnedCopyRuntimeResult.Hidden
                    appId == null || app == null -> OwnedCopyRuntimeResult.Hidden
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
        } catch (error: Throwable) {
            keys.associateWith { key ->
                val appId = key.steamAppIdOrNull()
                if (
                    key.source == source && key.accountScope == provedScope &&
                    appId != null && appId in provedIds
                ) {
                    unavailable(key, CopyUnavailableReason.SOURCE_READ_FAILED, error)
                } else {
                    OwnedCopyRuntimeResult.Hidden
                }
            }
        }
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
