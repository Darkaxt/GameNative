package app.gamenative.library.canonical.runtime

import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.library.canonical.AccountScopeProvider
import app.gamenative.library.canonical.CopyUnavailableReason
import app.gamenative.library.canonical.source.CustomOwnedCopySourceAdapter
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import app.gamenative.utils.CustomGameScanner
import app.gamenative.utils.FileUtils
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

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
class CustomOwnedCopyRuntimeState @Inject constructor() {
    suspend fun read(appIds: Set<Int>): Map<Int, CustomOwnedCopyRuntimeRow> = appIds.mapNotNull { appId ->
        val folderPath = CustomGameScanner.findCustomGameById(appId) ?: return@mapNotNull null
        val folder = File(folderPath).takeIf { it.isDirectory } ?: return@mapNotNull null
        val sourceAppId = sourceAppId(GameSource.CUSTOM_GAME, appId)
        val heroUrl = CustomGameScanner.findHeroCoverForCustomGame(sourceAppId).asFileUrl()
        appId to CustomOwnedCopyRuntimeRow(
            appId = appId,
            nativeTitle = folder.name,
            installPath = folder.path,
            installedSizeBytes = FileUtils.calculateDirectorySize(folder).positiveOrNull(),
            iconUrl = CustomGameScanner.findIconFileForCustomGame(sourceAppId).asFileUrl(),
            capsuleImageUrl = CustomGameScanner.findCapsuleCoverForCustomGame(sourceAppId).asFileUrl(),
            headerImageUrl = heroUrl,
            heroImageUrl = heroUrl,
        )
    }.toMap()

    private fun String?.asFileUrl(): String = when {
        isNullOrBlank() -> ""
        startsWith("file://") -> this
        else -> "file://$this"
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
        try {
            val accountScope = accountScopeProvider.current(source)
                ?: return OwnedCopyRuntimeResult.Hidden
            if (key.accountScope != accountScope) return OwnedCopyRuntimeResult.Hidden
            val appId = key.customAppIdOrNull() ?: return OwnedCopyRuntimeResult.Hidden
            val reference = sourceAdapter.resolve(key) as? SourceOwnedCopyReference.Custom
                ?: return OwnedCopyRuntimeResult.Hidden
            if (reference.appId != appId) return OwnedCopyRuntimeResult.Hidden
            rowProved = true
            val row = runtimeState.read(setOf(appId))[appId]
                ?: return OwnedCopyRuntimeResult.Hidden
            val lastPlayed = playHistoryDao.pointLastPlayed(sourceAppId(source, appId))
            if (!currentAccountProof { accountScopeProvider.current(source) == accountScope }) {
                return OwnedCopyRuntimeResult.Hidden
            }
            if (sourceAdapter.resolve(key) != reference) return OwnedCopyRuntimeResult.Hidden
            return available(key, reference, row, lastPlayed)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return if (rowProved) {
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
        var provedIds: Set<Int> = emptySet()
        var provedScope: app.gamenative.data.canonical.AccountScope? = null
        return try {
            val accountScope = accountScopeProvider.current(source)
                ?: return keys.hiddenResults()
            provedScope = accountScope
            if (keys.none { it.source == source && it.accountScope == accountScope }) {
                return keys.hiddenResults()
            }
            val requestedIds = keys.asSequence()
                .filter { it.source == source && it.accountScope == accountScope }
                .mapNotNull { key -> key.customAppIdOrNull() }
                .toSet()
            val rows = runtimeState.read(requestedIds)
            provedIds = rows.keys
            val history = playHistoryDao.batchLastPlayed()
            if (!currentAccountProof { accountScopeProvider.current(source) == accountScope }) {
                return keys.hiddenResults()
            }
            keys.associateWith { key ->
                val appId = key.customAppIdOrNull()
                val row = appId?.let(rows::get)
                when {
                    key.source != source || key.accountScope != accountScope ->
                        OwnedCopyRuntimeResult.Hidden
                    appId == null || row == null -> OwnedCopyRuntimeResult.Hidden
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
        } catch (error: Throwable) {
            keys.associateWith { key ->
                val appId = key.customAppIdOrNull()
                if (
                    key.source == source && key.accountScope == provedScope &&
                    appId in provedIds
                ) {
                    unavailable(key, CopyUnavailableReason.SOURCE_READ_FAILED, error)
                } else {
                    OwnedCopyRuntimeResult.Hidden
                }
            }
        }
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
