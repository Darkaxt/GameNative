package app.gamenative.library.canonical.runtime

import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.library.canonical.AccountScopeProvider
import app.gamenative.library.canonical.CopyUnavailableReason
import app.gamenative.library.canonical.source.CustomOwnedCopySourceAdapter
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import app.gamenative.utils.GameMetadataManager
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class CustomOwnedCopyRuntimeRow(
    val appId: Int,
    val nativeTitle: String,
    val installPath: String,
    val installedSizeBytes: Long?,
    val iconUrl: String,
    val capsuleImageUrl: String,
    val headerImageUrl: String,
    val heroImageUrl: String,
)

@Singleton
class CustomOwnedCopyRuntimeScanner @Inject constructor() {
    internal fun scan(appIds: Set<Int>): Map<Int, CustomOwnedCopyRuntimeRow> {
        if (appIds.isEmpty()) return emptyMap()
        val candidates = PrefManager.customGameManualFolders.mapNotNull { path ->
            try {
                val folder = File(path).takeIf(File::isDirectory) ?: return@mapNotNull null
                val appId = GameMetadataManager.getAppIdReadOnly(folder)
                    ?.takeIf { it in appIds }
                    ?: return@mapNotNull null
                Candidate(appId, folder, folder.listFiles().orEmpty().filter(File::isFile))
            } catch (_: Exception) {
                null
            }
        }
        val counts = candidates.groupingBy(Candidate::appId).eachCount()
        return candidates.mapNotNull { candidate ->
            if (counts[candidate.appId] != 1) return@mapNotNull null
            val hero = candidate.artwork(listOf("coverh", "cover"))
            candidate.appId to CustomOwnedCopyRuntimeRow(
                appId = candidate.appId,
                nativeTitle = candidate.folder.name,
                installPath = candidate.folder.path,
                installedSizeBytes = null,
                iconUrl = candidate.icon().asFileUrl(),
                capsuleImageUrl = candidate.artwork(listOf("coverv", "cover")).asFileUrl(),
                headerImageUrl = hero.asFileUrl(),
                heroImageUrl = hero.asFileUrl(),
            )
        }.toMap()
    }

    private fun Candidate.icon(): String? = files.asSequence()
        .filter { file ->
            file.nameWithoutExtension.startsWith("steamgriddb_logo", ignoreCase = true) ||
                file.nameWithoutExtension.equals("icon", ignoreCase = true)
        }
        .filter { it.extension.lowercase() in ICON_EXTENSIONS }
        .sortedWith(compareBy<File>({ ICON_EXTENSIONS.indexOf(it.extension.lowercase()) }, File::getName))
        .firstOrNull()
        ?.path

    private fun Candidate.artwork(baseNames: List<String>): String? {
        for (baseName in baseNames) {
            val match = files.asSequence()
                .filter { it.nameWithoutExtension.equals(baseName, ignoreCase = true) }
                .filter { it.extension.lowercase() in ARTWORK_EXTENSIONS }
                .minWithOrNull(
                    compareBy<File>(
                        { ARTWORK_EXTENSIONS.indexOf(it.extension.lowercase()) },
                        File::getName,
                    ),
                )
            if (match != null) return match.path
        }
        return null
    }

    private fun String?.asFileUrl(): String = when {
        isNullOrBlank() -> ""
        startsWith("file://") -> this
        else -> "file://$this"
    }

    private data class Candidate(
        val appId: Int,
        val folder: File,
        val files: List<File>,
    )

    private companion object {
        val ICON_EXTENSIONS = listOf("png", "jpg", "jpeg", "webp", "ico")
        val ARTWORK_EXTENSIONS = listOf("png", "jpg", "jpeg", "webp")
    }
}

@Singleton
class CustomOwnedCopyRuntimeState @Inject constructor(
    private val scanner: CustomOwnedCopyRuntimeScanner,
) {
    suspend fun read(appIds: Set<Int>): Map<Int, CustomOwnedCopyRuntimeRow> =
        withContext(Dispatchers.IO) {
            scanner.scan(appIds)
        }
}

@Singleton
class CustomOwnedCopyRuntimeAdapter @Inject constructor(
    private val accountScopeProvider: AccountScopeProvider,
    private val sourceAdapter: CustomOwnedCopySourceAdapter,
    private val playHistoryDao: LibraryPlayHistoryDao,
    private val runtimeState: CustomOwnedCopyRuntimeState,
) : OwnedCopyRuntimeAdapter {
    override val source: GameSource = GameSource.CUSTOM_GAME

    override fun invalidations(): Flow<Unit> = sourceAdapter.invalidations()

    override suspend fun resolve(key: OwnedCopyKey): OwnedCopyRuntimeResult {
        if (key.source != source) return OwnedCopyRuntimeResult.Hidden
        var rowProved = false
        var provedScope: app.gamenative.data.canonical.AccountScope? = null
        var provedAppId: Int? = null
        try {
            val accountScope = accountScopeProvider.current(source)
                ?: return OwnedCopyRuntimeResult.Hidden
            provedScope = accountScope
            if (key.accountScope != accountScope) return OwnedCopyRuntimeResult.Hidden
            val appId = key.customAppIdOrNull() ?: return OwnedCopyRuntimeResult.Hidden
            provedAppId = appId
            val row = runtimeState.read(setOf(appId))[appId]
                ?: return OwnedCopyRuntimeResult.Hidden
            rowProved = true
            val reference = SourceOwnedCopyReference.Custom(key, appId)
            val lastPlayed = playHistoryDao.pointLastPlayed(sourceAppId(source, appId))
            if (!hasFreshPointProof(appId, accountScope)) {
                return OwnedCopyRuntimeResult.Hidden
            }
            return available(key, reference, row, lastPlayed)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val scope = provedScope
            val appId = provedAppId
            return if (rowProved && scope != null && appId != null) {
                unavailableIfFresh(
                    key,
                    appId,
                    scope,
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
        var provedIds: Set<Int> = emptySet()
        var provedScope: app.gamenative.data.canonical.AccountScope? = null
        var requestedIds: Set<Int> = emptySet()
        return try {
            val accountScope = accountScopeProvider.current(source)
                ?: return keys.hiddenResults()
            provedScope = accountScope
            if (keys.none { it.source == source && it.accountScope == accountScope }) {
                return keys.hiddenResults()
            }
            requestedIds = keys.asSequence()
                .filter { it.source == source && it.accountScope == accountScope }
                .mapNotNull { key -> key.customAppIdOrNull() }
                .toSet()
            val rows = runtimeState.read(requestedIds)
            provedIds = rows.keys
            val history = playHistoryDao.batchLastPlayed()
            val finalRows = finalRows(requestedIds) ?: return keys.hiddenResults()
            if (!currentAccountProof { accountScopeProvider.current(source) == accountScope }) {
                return keys.hiddenResults()
            }
            keys.associateWith { key ->
                val appId = key.customAppIdOrNull()
                val row = appId?.let(rows::get)
                when {
                    key.source != source || key.accountScope != accountScope ->
                        OwnedCopyRuntimeResult.Hidden
                    appId == null || appId !in provedIds || appId !in finalRows ->
                        OwnedCopyRuntimeResult.Hidden
                    row == null -> OwnedCopyRuntimeResult.Hidden
                    else -> available(
                        key = key,
                        reference = SourceOwnedCopyReference.Custom(key, appId),
                        row = row,
                        lastPlayed = history[sourceAppId(source, appId)],
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val scope = provedScope ?: return keys.hiddenResults()
            val finalRows = finalRows(requestedIds) ?: return keys.hiddenResults()
            if (!currentAccountProof { accountScopeProvider.current(source) == scope }) {
                return keys.hiddenResults()
            }
            keys.associateWith { key ->
                val appId = key.customAppIdOrNull()
                if (
                    key.source == source && key.accountScope == scope &&
                    appId in provedIds && appId in finalRows
                ) {
                    unavailable(key, CopyUnavailableReason.SOURCE_READ_FAILED, error)
                } else {
                    OwnedCopyRuntimeResult.Hidden
                }
            }
        }
    }

    private suspend fun hasFreshPointProof(
        appId: Int,
        accountScope: app.gamenative.data.canonical.AccountScope,
    ): Boolean = currentAccountProof {
        accountScopeProvider.current(source) == accountScope &&
            runtimeState.read(setOf(appId)).containsKey(appId)
    }

    private suspend fun unavailableIfFresh(
        key: OwnedCopyKey,
        appId: Int,
        accountScope: app.gamenative.data.canonical.AccountScope,
        reason: CopyUnavailableReason,
        error: Exception,
    ): OwnedCopyRuntimeResult = if (hasFreshPointProof(appId, accountScope)) {
        unavailable(key, reason, error)
    } else {
        OwnedCopyRuntimeResult.Hidden
    }

    private suspend fun finalRows(appIds: Set<Int>): Set<Int>? = try {
        runtimeState.read(appIds).keys
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun available(
        key: OwnedCopyKey,
        reference: SourceOwnedCopyReference.Custom,
        row: CustomOwnedCopyRuntimeRow,
        lastPlayed: Long?,
    ): OwnedCopyRuntimeResult.Available {
        val sourceState = OwnedCopyVolatileState(
            installPath = row.installPath,
            installedSizeBytes = row.installedSizeBytes,
            isInstalled = true,
        )
        val item = LibraryItem(
            appId = sourceAppId(source, row.appId),
            name = row.nativeTitle,
            capsuleImageUrl = row.capsuleImageUrl,
            headerImageUrl = row.headerImageUrl,
            heroImageUrl = row.heroImageUrl,
            isShared = false,
            gameSource = source,
            sizeBytes = row.installedSizeBytes ?: 0L,
            isInstalled = true,
        )
        return OwnedCopyRuntimeResult.Available(
            OwnedCopyRuntime(
                key = key,
                reference = reference,
                libraryItem = item,
                nativeTitle = row.nativeTitle,
                aliases = emptySet(),
                developerKey = "",
                releaseYear = null,
                appType = CanonicalAppType.GAME,
                genreKeys = emptySet(),
                tagIds = emptySet(),
                featureKeys = emptySet(),
                iconUrl = row.iconUrl,
                capsuleImageUrl = row.capsuleImageUrl,
                headerImageUrl = row.headerImageUrl,
                heroImageUrl = row.heroImageUrl,
                gridHeroImageScale = 1f,
                installPath = row.installPath,
                installedSizeBytes = row.installedSizeBytes,
                branchOrVersion = null,
                isInstalled = true,
                isDownloading = false,
                hasPartialDownload = false,
                updateAvailable = false,
                isShared = false,
                lastPlayedEpochMs = lastPlayed,
                playtimeMinutes = null,
                capabilities = capabilities(source, libraryItemPresent = true, sourceState),
            ),
        )
    }

    private fun OwnedCopyKey.customAppIdOrNull(): Int? = stableSourceId.toIntOrNull()
        ?.takeIf { it > 0 && it.toString() == stableSourceId }
}
