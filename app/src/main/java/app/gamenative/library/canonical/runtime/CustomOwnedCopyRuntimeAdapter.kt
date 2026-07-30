package app.gamenative.library.canonical.runtime

import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.library.canonical.AccountScopeProvider
import app.gamenative.library.canonical.CanonicalDiagnosticSink
import app.gamenative.library.canonical.CopyUnavailableReason
import app.gamenative.library.canonical.source.CustomOwnedCopySourceAdapter
import app.gamenative.library.canonical.source.SnapshotCompleteness
import app.gamenative.library.canonical.source.SnapshotReason
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import app.gamenative.utils.GameMetadataManager
import app.gamenative.utils.ReadOnlyAppIdResult
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass
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

internal data class CustomRuntimeScanResult(
    val rows: Map<Int, CustomOwnedCopyRuntimeRow>,
    val failures: Map<Int, KClass<out Throwable>> = emptyMap(),
    val batchFailure: KClass<out Throwable>? = null,
)

@Singleton
class CustomOwnedCopyRuntimeScanner private constructor(
    private val readAppId: (File) -> ReadOnlyAppIdResult,
) {
    @Inject
    constructor() : this(GameMetadataManager::readAppIdReadOnly)

    internal constructor(
        readAppId: (File) -> ReadOnlyAppIdResult,
        @Suppress("UNUSED_PARAMETER") marker: Unit = Unit,
    ) : this(readAppId)

    private val associations = linkedMapOf<String, Int>()

    internal fun scan(appIds: Set<Int>): Map<Int, CustomOwnedCopyRuntimeRow> =
        scanTyped(appIds).rows

    internal fun scanTyped(appIds: Set<Int>): CustomRuntimeScanResult {
        if (appIds.isEmpty()) return CustomRuntimeScanResult(emptyMap())
        val failures = mutableMapOf<Int, KClass<out Throwable>>()
        var batchFailure: KClass<out Throwable>? = null
        val candidates = PrefManager.customGameManualFolders.mapNotNull { path ->
            try {
                val folder = File(path).takeIf(File::isDirectory)
                if (folder == null) {
                    removeAssociation(path)
                    return@mapNotNull null
                }
                when (val read = readAppId(folder)) {
                    is ReadOnlyAppIdResult.Present -> {
                        rememberAssociation(path, read.appId)
                        if (read.appId !in appIds) return@mapNotNull null
                        Candidate(read.appId, folder, folder.listFiles().orEmpty().filter(File::isFile))
                    }
                    ReadOnlyAppIdResult.MissingOrInvalid -> {
                        removeAssociation(path)
                        null
                    }
                    is ReadOnlyAppIdResult.ReadFailure -> {
                        val prior = association(path)
                        if (prior == null) {
                            if (batchFailure == null) batchFailure = read.errorClass
                        } else if (prior in appIds) {
                            failures[prior] = read.errorClass
                        }
                        null
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val prior = association(path)
                if (prior == null) {
                    if (batchFailure == null) batchFailure = error::class
                } else if (prior in appIds) {
                    failures[prior] = error::class
                }
                null
            }
        }
        if (batchFailure != null) {
            return CustomRuntimeScanResult(emptyMap(), batchFailure = batchFailure)
        }
        val counts = candidates.groupingBy(Candidate::appId).eachCount()
        val rows = candidates.mapNotNull { candidate ->
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
        return CustomRuntimeScanResult(rows, failures)
    }

    private fun Candidate.icon(): String? {
        val rootLogo = files.asSequence()
            .filter { file ->
                file.nameWithoutExtension.equals("steamgriddb_logo", ignoreCase = true)
            }
            .filter { it.isSupportedIcon(STEAM_GRID_LOGO_EXTENSIONS) }
            .sortedWith(iconOrder(STEAM_GRID_LOGO_EXTENSIONS))
            .firstOrNull()
        if (rootLogo != null) return rootLogo.path

        val nearbyFiles = buildList {
            addAll(files)
            folder.listFiles()
                .orEmpty()
                .asSequence()
                .filter(File::isDirectory)
                .forEach { child -> addAll(child.listFiles().orEmpty().filter(File::isFile)) }
        }
        val icons = nearbyFiles.asSequence()
            .filter { it.isSupportedIcon(NEARBY_ICON_EXTENSIONS) }
            .distinctBy(File::getAbsolutePath)
            .sortedWith(iconOrder(NEARBY_ICON_EXTENSIONS))
            .toList()
        val executables = nearbyFiles.asSequence()
            .filter { file ->
                file.extension.equals("exe", ignoreCase = true) &&
                    !file.name.startsWith("unins", ignoreCase = true)
            }
            .distinctBy(File::getAbsolutePath)
            .toList()
        val executableBase = executables.singleOrNull()?.nameWithoutExtension

        val extracted = icons.filter { icon ->
            icon.name.endsWith(".extracted.ico", ignoreCase = true)
        }
        if (extracted.isNotEmpty()) {
            return extracted.firstOrNull { icon ->
                executableBase != null && icon.nameWithoutExtension
                    .removeSuffix(".extracted")
                    .equals(executableBase, ignoreCase = true)
            }?.path ?: extracted.first().path
        }
        if (executableBase != null) {
            icons.firstOrNull { icon ->
                icon.nameWithoutExtension.equals(executableBase, ignoreCase = true)
            }?.let { return it.path }
        }
        icons.firstOrNull { icon -> icon.name.contains("icon", ignoreCase = true) }
            ?.let { return it.path }
        return icons.singleOrNull()?.path
    }

    private fun File.isSupportedIcon(extensions: List<String>): Boolean =
        isFile && extension.lowercase() in extensions

    private fun iconOrder(extensions: List<String>): Comparator<File> = compareBy(
        { extensions.indexOf(it.extension.lowercase()) },
        File::getName,
    )

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

    private fun association(path: String): Int? = synchronized(associations) {
        associations[path]
    }

    private fun rememberAssociation(path: String, appId: Int) = synchronized(associations) {
        associations.remove(path)
        associations[path] = appId
        while (associations.size > MAX_ASSOCIATIONS) {
            associations.remove(associations.keys.first())
        }
    }

    private fun removeAssociation(path: String) = synchronized(associations) {
        associations.remove(path)
        Unit
    }

    private companion object {
        const val MAX_ASSOCIATIONS = 512
        val STEAM_GRID_LOGO_EXTENSIONS = listOf("png", "jpg", "webp")
        val NEARBY_ICON_EXTENSIONS = listOf("png", "ico")
        val ARTWORK_EXTENSIONS = listOf("png", "jpg", "jpeg", "webp")
    }
}

@Singleton
class CustomOwnedCopyRuntimeState @Inject constructor(
    private val scanner: CustomOwnedCopyRuntimeScanner,
    @CanonicalIoDispatcher private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun read(appIds: Set<Int>): Map<Int, CustomOwnedCopyRuntimeRow> =
        readTyped(appIds).rows

    internal suspend fun readTyped(appIds: Set<Int>): CustomRuntimeScanResult =
        withContext(ioDispatcher) {
            scanner.scanTyped(appIds)
        }
}

@Singleton
class CustomOwnedCopyRuntimeAdapter @Inject constructor(
    private val accountScopeProvider: AccountScopeProvider,
    private val sourceAdapter: CustomOwnedCopySourceAdapter,
    private val playHistoryDao: LibraryPlayHistoryDao,
    private val runtimeState: CustomOwnedCopyRuntimeState,
    private val diagnostics: CanonicalDiagnosticSink? = null,
) : OwnedCopyRuntimeAdapter {
    override val source: GameSource = GameSource.CUSTOM_GAME

    override fun invalidations(): Flow<Unit> = sourceAdapter.invalidations()

    override suspend fun resolve(key: OwnedCopyKey): OwnedCopyRuntimeResult {
        if (key.source != source) return OwnedCopyRuntimeResult.Hidden
        var accountScope: app.gamenative.data.canonical.AccountScope? = null
        var appId: Int? = null
        var initial: CustomRuntimeScanResult? = null
        var completedFinal: CustomRuntimeScanResult? = null
        try {
            val currentScope = accountScopeProvider.current(source)
                ?: return OwnedCopyRuntimeResult.Hidden
            accountScope = currentScope
            if (key.accountScope != currentScope) return OwnedCopyRuntimeResult.Hidden
            val requestedAppId = key.customAppIdOrNull() ?: return OwnedCopyRuntimeResult.Hidden
            appId = requestedAppId
            val first = runtimeState.readTyped(setOf(requestedAppId))
            initial = first
            first.batchFailure?.let {
                reportBatchFailure(it)
                return OwnedCopyRuntimeResult.Hidden
            }
            if (requestedAppId !in first.rows && requestedAppId !in first.failures) {
                return OwnedCopyRuntimeResult.Hidden
            }
            val row = first.rows[requestedAppId]
            val lastPlayed = row?.let {
                playHistoryDao.pointLastPlayed(
                    sourceAppId(source, requestedAppId),
                    source,
                    diagnostics,
                )
            }
            val final = runtimeState.readTyped(setOf(requestedAppId))
            completedFinal = final
            final.batchFailure?.let {
                reportBatchFailure(it)
                return OwnedCopyRuntimeResult.Hidden
            }
            if (!currentAccountProof { accountScopeProvider.current(source) == currentScope }) {
                return OwnedCopyRuntimeResult.Hidden
            }
            if (requestedAppId !in final.rows && requestedAppId !in final.failures) {
                return OwnedCopyRuntimeResult.Hidden
            }
            val failure = first.failures[requestedAppId] ?: final.failures[requestedAppId]
            if (failure != null) {
                return unavailable(key, CopyUnavailableReason.SOURCE_READ_FAILED, failure)
            }
            val currentRow = row ?: return OwnedCopyRuntimeResult.Hidden
            return available(
                key,
                SourceOwnedCopyReference.Custom(key, requestedAppId),
                currentRow,
                lastPlayed,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val scope = accountScope ?: return OwnedCopyRuntimeResult.Hidden
            val requestedAppId = appId ?: return OwnedCopyRuntimeResult.Hidden
            val first = initial ?: return OwnedCopyRuntimeResult.Hidden
            val final = completedFinal
                ?: finalRead(setOf(requestedAppId))
                ?: return OwnedCopyRuntimeResult.Hidden
            if (!currentAccountProof { accountScopeProvider.current(source) == scope }) {
                return OwnedCopyRuntimeResult.Hidden
            }
            val firstKnown = requestedAppId in first.rows || requestedAppId in first.failures
            val finalKnown = requestedAppId in final.rows || requestedAppId in final.failures
            return if (firstKnown && finalKnown && final.batchFailure == null) {
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
        var accountScope: app.gamenative.data.canonical.AccountScope? = null
        var requestedIds: Set<Int> = emptySet()
        var initial: CustomRuntimeScanResult? = null
        var completedFinal: CustomRuntimeScanResult? = null
        return try {
            val currentScope = accountScopeProvider.current(source)
                ?: return keys.hiddenResults()
            accountScope = currentScope
            if (keys.none { it.source == source && it.accountScope == currentScope }) {
                return keys.hiddenResults()
            }
            requestedIds = keys.asSequence()
                .filter { it.source == source && it.accountScope == currentScope }
                .mapNotNull { key -> key.customAppIdOrNull() }
                .toSet()
            val first = runtimeState.readTyped(requestedIds)
            initial = first
            val history = if (first.batchFailure == null) {
                playHistoryDao.batchLastPlayed(source, diagnostics)
            } else {
                emptyMap()
            }
            val final = runtimeState.readTyped(requestedIds)
            completedFinal = final
            val batchFailure = first.batchFailure ?: final.batchFailure
            if (batchFailure != null) {
                reportBatchFailure(batchFailure)
                return keys.hiddenResults()
            }
            if (!currentAccountProof { accountScopeProvider.current(source) == currentScope }) {
                return keys.hiddenResults()
            }
            keys.associateWith { key ->
                val requestedAppId = key.customAppIdOrNull()
                val firstKnown = requestedAppId != null &&
                    (requestedAppId in first.rows || requestedAppId in first.failures)
                val finalKnown = requestedAppId != null &&
                    (requestedAppId in final.rows || requestedAppId in final.failures)
                val failure = requestedAppId?.let { id ->
                    first.failures[id] ?: final.failures[id]
                }
                val row = requestedAppId?.let(first.rows::get)
                when {
                    key.source != source || key.accountScope != currentScope ->
                        OwnedCopyRuntimeResult.Hidden
                    !firstKnown || !finalKnown -> OwnedCopyRuntimeResult.Hidden
                    failure != null -> unavailable(
                        key,
                        CopyUnavailableReason.SOURCE_READ_FAILED,
                        failure,
                    )
                    row == null -> OwnedCopyRuntimeResult.Hidden
                    else -> available(
                        key = key,
                        reference = SourceOwnedCopyReference.Custom(key, requestedAppId),
                        row = row,
                        lastPlayed = history[sourceAppId(source, requestedAppId)],
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val scope = accountScope ?: return keys.hiddenResults()
            val first = initial ?: return keys.hiddenResults()
            val final = completedFinal
                ?: finalRead(requestedIds)
                ?: return keys.hiddenResults()
            if (final.batchFailure != null) return keys.hiddenResults()
            if (!currentAccountProof { accountScopeProvider.current(source) == scope }) {
                return keys.hiddenResults()
            }
            keys.associateWith { key ->
                val requestedAppId = key.customAppIdOrNull()
                val firstKnown = requestedAppId != null &&
                    (requestedAppId in first.rows || requestedAppId in first.failures)
                val finalKnown = requestedAppId != null &&
                    (requestedAppId in final.rows || requestedAppId in final.failures)
                if (
                    key.source == source && key.accountScope == scope &&
                    firstKnown && finalKnown
                ) {
                    unavailable(key, CopyUnavailableReason.SOURCE_READ_FAILED, error)
                } else {
                    OwnedCopyRuntimeResult.Hidden
                }
            }
        }
    }

    private suspend fun finalRead(appIds: Set<Int>): CustomRuntimeScanResult? = try {
        runtimeState.readTyped(appIds)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun reportBatchFailure(errorClass: KClass<out Throwable>) {
        diagnostics?.sourceSnapshot(
            source = source,
            completeness = SnapshotCompleteness.PARTIAL,
            copyCount = 0,
            reason = SnapshotReason.SOURCE_READ_FAILED,
            errorClass = errorClass,
        )
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
