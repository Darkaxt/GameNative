package app.gamenative.library.canonical.runtime

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.library.canonical.CanonicalDiagnosticSink
import app.gamenative.library.canonical.CopyUnavailableReason
import app.gamenative.library.canonical.OwnedCopyOperation
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.retryWhen

interface OwnedCopyRuntimeAdapter {
    val source: GameSource

    fun invalidations(): Flow<Unit>

    suspend fun resolve(key: OwnedCopyKey): OwnedCopyRuntimeResult

    suspend fun resolveAll(
        keys: Set<OwnedCopyKey>,
    ): Map<OwnedCopyKey, OwnedCopyRuntimeResult>
}

@Singleton
class OwnedCopyRuntimeRegistry @Inject constructor(
    adapters: Set<@JvmSuppressWildcards OwnedCopyRuntimeAdapter>,
    private val playHistoryDao: LibraryPlayHistoryDao,
    private val diagnostics: CanonicalDiagnosticSink,
) {
    private val volatileInvalidations = MutableSharedFlow<GameSource>(extraBufferCapacity = 1)
    private val bySource = adapters.associateBy { it.source }.also { map ->
        check(map.size == adapters.size) { "Duplicate owned-copy runtime adapter" }
        check(map.keys == GameSource.entries.toSet()) { "Missing owned-copy runtime adapter" }
    }

    fun notifyVolatileStateChanged(source: GameSource) {
        volatileInvalidations.tryEmit(source)
    }

    fun invalidations(): Flow<Unit> = merge(
        playHistoryDao.getAll().map { Unit },
        volatileInvalidations.map { Unit },
        *RUNTIME_SOURCE_ORDER.map { source ->
            bySource.getValue(source).invalidations().retryWhen { error, attempt ->
                if (error is CancellationException) return@retryWhen false
                diagnostics.invalidationFailed(source, error::class)
                delay((1_000L shl attempt.coerceAtMost(6).toInt()).coerceAtMost(60_000L))
                true
            }
        }.toTypedArray(),
    )

    suspend fun resolve(key: OwnedCopyKey): OwnedCopyRuntimeResult =
        bySource.getValue(key.source).resolve(key)

    suspend fun resolveAll(
        source: GameSource,
        keys: Set<OwnedCopyKey>,
    ): Map<OwnedCopyKey, OwnedCopyRuntimeResult> {
        require(keys.all { it.source == source })
        return bySource.getValue(source).resolveAll(keys).also { results ->
            check(results.keys == keys) { "Runtime adapter returned an incomplete key set" }
        }
    }

    private companion object {
        val RUNTIME_SOURCE_ORDER = listOf(
            GameSource.STEAM,
            GameSource.GOG,
            GameSource.EPIC,
            GameSource.AMAZON,
            GameSource.CUSTOM_GAME,
        )
    }
}

internal data class OwnedCopyVolatileState(
    val installPath: String? = null,
    val installedSizeBytes: Long? = null,
    val branchOrVersion: String? = null,
    val isInstalled: Boolean = false,
    val isDownloading: Boolean = false,
    val hasPartialDownload: Boolean = false,
    val updateAvailable: Boolean = false,
    val isShared: Boolean = false,
    val playtimeMinutes: Long? = null,
)

internal fun capabilities(
    source: GameSource,
    libraryItemPresent: Boolean,
    state: OwnedCopyVolatileState,
): Set<OwnedCopyOperation> {
    if (!libraryItemPresent) return emptySet()
    val supportsInstall = source != GameSource.CUSTOM_GAME
    val supportsPlay = true
    val supportsUpdate = source == GameSource.STEAM || source == GameSource.AMAZON
    val supportsUninstall = source != GameSource.CUSTOM_GAME
    return buildSet {
        add(OwnedCopyOperation.OPEN_SOURCE_DETAILS)
        if (supportsInstall && !state.isInstalled) add(OwnedCopyOperation.INSTALL)
        if (supportsPlay && (state.isInstalled || source == GameSource.CUSTOM_GAME)) {
            add(OwnedCopyOperation.PLAY)
        }
        if (state.isDownloading || state.hasPartialDownload) {
            add(OwnedCopyOperation.PAUSE_RESUME_DOWNLOAD)
            add(OwnedCopyOperation.CANCEL_DOWNLOAD)
        }
        if (supportsUpdate && state.isInstalled && state.updateAvailable) {
            add(OwnedCopyOperation.UPDATE)
        }
        if (supportsUninstall && (state.isInstalled || state.hasPartialDownload)) {
            add(OwnedCopyOperation.UNINSTALL)
        }
        if (source == GameSource.STEAM) {
            add(OwnedCopyOperation.EXPORT_SAVES)
            add(OwnedCopyOperation.IMPORT_SAVES)
        }
    }
}

internal fun unavailable(
    key: OwnedCopyKey,
    reason: CopyUnavailableReason,
    error: Throwable? = null,
): OwnedCopyRuntimeResult.Unavailable = OwnedCopyRuntimeResult.Unavailable(
    key = key,
    reason = reason,
    errorClass = error?.let { it::class },
)

internal suspend fun LibraryPlayHistoryDao.pointLastPlayed(appId: String): Long? =
    get(appId)?.lastPlayed?.takeIf { it > 0L }

internal suspend fun LibraryPlayHistoryDao.batchLastPlayed(): Map<String, Long> =
    getAll().first().asSequence()
        .filter { it.lastPlayed > 0L }
        .associate { it.appId to it.lastPlayed }

internal fun sourceAppId(source: GameSource, id: Any): String = "${source.name}_$id"

internal fun Set<OwnedCopyKey>.hiddenResults(): Map<OwnedCopyKey, OwnedCopyRuntimeResult> =
    associateWith { OwnedCopyRuntimeResult.Hidden }

internal suspend inline fun currentAccountProof(
    crossinline check: suspend () -> Boolean,
): Boolean = try {
    check()
} catch (error: CancellationException) {
    throw error
} catch (_: Throwable) {
    false
}

internal fun Long.positiveOrNull(): Long? = takeIf { it > 0L }
