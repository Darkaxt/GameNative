package app.gamenative.library.canonical.runtime

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.library.canonical.CanonicalDiagnosticSink
import app.gamenative.library.canonical.CopyUnavailableReason
import app.gamenative.library.canonical.OwnedCopyOperation
import app.gamenative.library.canonical.PlayHistoryOrigin
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
        resilientInvalidations(source = null) {
            playHistoryDao.getAll().map { Unit }
        },
        volatileInvalidations.map { Unit },
        *RUNTIME_SOURCE_ORDER.map { source ->
            resilientInvalidations(source) {
                bySource.getValue(source).invalidations()
            }
        }.toTypedArray(),
    )

    suspend fun resolve(key: OwnedCopyKey): OwnedCopyRuntimeResult =
        bySource.getValue(key.source).resolve(key).also { result ->
            result.requireIdentity(key)
        }

    suspend fun resolveAll(
        source: GameSource,
        keys: Set<OwnedCopyKey>,
    ): Map<OwnedCopyKey, OwnedCopyRuntimeResult> {
        require(keys.all { it.source == source })
        return bySource.getValue(source).resolveAll(keys).also { results ->
            check(results.keys == keys) { "Runtime adapter returned an incomplete key set" }
            results.forEach { (key, result) -> result.requireIdentity(key) }
        }
    }

    private fun resilientInvalidations(
        source: GameSource?,
        factory: () -> Flow<Unit>,
    ): Flow<Unit> = flow {
        emitAll(factory())
    }.retryWhen { error, attempt ->
        if (error is CancellationException || error !is Exception) return@retryWhen false
        if (source == null) {
            diagnostics.playHistoryFailed(null, PlayHistoryOrigin.FLOW, error::class)
        } else {
            diagnostics.invalidationFailed(source, error::class)
        }
        delay((1_000L shl attempt.coerceAtMost(6).toInt()).coerceAtMost(60_000L))
        true
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

internal data class RuntimeDownloadSnapshot<K : Any>(
    val activeIds: Set<K>,
    val partialIds: Set<K>,
)

internal data class OwnedCopyVolatileState(
    val installPath: String? = null,
    val installedSizeBytes: Long? = null,
    val branchOrVersion: String? = null,
    val isInstalled: Boolean = false,
    val isDownloading: Boolean = false,
    val hasPartialDownload: Boolean = false,
    val updateAvailable: Boolean = false,
    val updateObservation: UpdateObservation = if (updateAvailable) {
        UpdateObservation.UPDATE_AVAILABLE
    } else {
        UpdateObservation.UNKNOWN
    },
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

internal fun unavailable(
    key: OwnedCopyKey,
    reason: CopyUnavailableReason,
    errorClass: KClass<out Throwable>,
): OwnedCopyRuntimeResult.Unavailable = OwnedCopyRuntimeResult.Unavailable(
    key = key,
    reason = reason,
    errorClass = errorClass,
)

internal suspend fun LibraryPlayHistoryDao.pointLastPlayed(appId: String): Long? =
    get(appId)?.lastPlayed?.takeIf { it > 0L }

internal suspend fun LibraryPlayHistoryDao.batchLastPlayed(): Map<String, Long> =
    getAll().first().asSequence()
        .filter { it.lastPlayed > 0L }
        .associate { it.appId to it.lastPlayed }

internal suspend fun LibraryPlayHistoryDao.pointLastPlayed(
    appId: String,
    source: GameSource,
    diagnostics: CanonicalDiagnosticSink?,
): Long? = try {
    pointLastPlayed(appId)
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    diagnostics?.playHistoryFailed(source, PlayHistoryOrigin.POINT, error::class)
    null
}

internal suspend fun LibraryPlayHistoryDao.batchLastPlayed(
    source: GameSource,
    diagnostics: CanonicalDiagnosticSink?,
): Map<String, Long> = try {
    batchLastPlayed()
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    diagnostics?.playHistoryFailed(source, PlayHistoryOrigin.BATCH, error::class)
    emptyMap()
}

internal fun sourceAppId(source: GameSource, id: Any): String = "${source.name}_$id"

internal fun Set<OwnedCopyKey>.hiddenResults(): Map<OwnedCopyKey, OwnedCopyRuntimeResult> =
    associateWith { OwnedCopyRuntimeResult.Hidden }

internal fun OwnedCopyRuntimeResult.requireIdentity(requestedKey: OwnedCopyKey) {
    when (this) {
        is OwnedCopyRuntimeResult.Available -> {
            check(copy.key == requestedKey) { "Runtime result copy key differs from requested key" }
            check(copy.reference.key == requestedKey) {
                "Runtime result reference key differs from requested key"
            }
        }
        is OwnedCopyRuntimeResult.Unavailable ->
            check(key == requestedKey) { "Unavailable runtime key differs from requested key" }
        OwnedCopyRuntimeResult.Hidden -> Unit
    }
}

internal suspend inline fun currentAccountProof(
    crossinline check: suspend () -> Boolean,
): Boolean = try {
    check()
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    false
}

internal fun latestPositiveTimestamp(provider: Long?, local: Long?): Long? =
    listOfNotNull(provider?.positiveOrNull(), local?.positiveOrNull()).maxOrNull()

internal fun Long.positiveOrNull(): Long? = takeIf { it > 0L }
