package app.gamenative.library.canonical.runtime

import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.canonical.EpicStableSourceId
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.data.canonical.StableSourceIdValidation
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.library.canonical.CanonicalDiagnosticSink
import app.gamenative.library.canonical.CopyUnavailableReason
import app.gamenative.library.canonical.OwnedCopyOperation
import app.gamenative.library.canonical.PlayHistoryOrigin
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
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

    suspend fun resolve(key: OwnedCopyKey): OwnedCopyRuntimeResult {
        if (!key.hasValidProviderIdentity()) return OwnedCopyRuntimeResult.Hidden
        return bySource.getValue(key.source).resolve(key).also { result ->
            result.requireIdentity(key)
        }
    }

    suspend fun resolveAll(
        source: GameSource,
        keys: Set<OwnedCopyKey>,
    ): Map<OwnedCopyKey, OwnedCopyRuntimeResult> {
        require(keys.all { it.source == source })
        val validKeys = keys.filterTo(mutableSetOf(), OwnedCopyKey::hasValidProviderIdentity)
        if (validKeys.isEmpty()) return keys.hiddenResults()
        val validResults = bySource.getValue(source).resolveAll(validKeys).also { results ->
            check(results.keys == validKeys) { "Runtime adapter returned an incomplete key set" }
            results.forEach { (key, result) -> result.requireIdentity(key) }
        }
        return keys.associateWith { key ->
            validResults[key] ?: OwnedCopyRuntimeResult.Hidden
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

private fun OwnedCopyKey.hasValidProviderIdentity(): Boolean = when (source) {
    GameSource.GOG,
    GameSource.AMAZON,
    -> StableSourceIdValidation.isValid(source, stableSourceId)
    else -> true
}

internal fun OwnedCopyRuntimeResult.requireIdentity(requestedKey: OwnedCopyKey) {
    when (this) {
        is OwnedCopyRuntimeResult.Available -> copy.requireIdentity(requestedKey)
        is OwnedCopyRuntimeResult.Unavailable ->
            check(key == requestedKey) { "Unavailable runtime key differs from requested key" }
        OwnedCopyRuntimeResult.Hidden -> Unit
    }
}

internal fun OwnedCopyRuntime.requireIdentity(requestedKey: OwnedCopyKey) {
    check(key == requestedKey) { "Runtime result copy key differs from requested key" }
    requireExactRuntimeIdentity(
        requestedKey = requestedKey,
        reference = reference,
        libraryItem = libraryItem,
    )
}

internal fun requireExactRuntimeIdentity(
    requestedKey: OwnedCopyKey,
    reference: SourceOwnedCopyReference,
    libraryItem: LibraryItem?,
) {
    check(reference.key == requestedKey) {
        "Runtime result reference key differs from requested key"
    }
    val expectedLibraryItemId = when (reference) {
        is SourceOwnedCopyReference.Steam -> {
            check(requestedKey.source == GameSource.STEAM) {
                "Runtime reference source differs from requested source"
            }
            val appId = requestedKey.stableSourceId.exactPositiveIntOrNull()
            check(appId != null && reference.appId == appId) {
                "Runtime reference identity differs from requested identity"
            }
            sourceAppId(GameSource.STEAM, reference.appId)
        }
        is SourceOwnedCopyReference.Gog -> {
            check(requestedKey.source == GameSource.GOG) {
                "Runtime reference source differs from requested source"
            }
            check(reference.gameId == requestedKey.stableSourceId) {
                "Runtime reference identity differs from requested identity"
            }
            requestedKey.stableSourceId.exactPositiveIntOrNull()?.let {
                sourceAppId(GameSource.GOG, reference.gameId)
            }
        }
        is SourceOwnedCopyReference.Epic -> {
            check(requestedKey.source == GameSource.EPIC) {
                "Runtime reference source differs from requested source"
            }
            check(
                reference.namespace.isNotBlank() &&
                    reference.catalogId.isNotBlank() &&
                    EpicStableSourceId.encode(
                        reference.namespace,
                        reference.catalogId,
                    ) == requestedKey.stableSourceId,
            ) {
                "Runtime reference identity differs from requested identity"
            }
            sourceAppId(GameSource.EPIC, reference.localRowId)
        }
        is SourceOwnedCopyReference.Amazon -> {
            check(requestedKey.source == GameSource.AMAZON) {
                "Runtime reference source differs from requested source"
            }
            check(
                reference.productId == requestedKey.stableSourceId &&
                    reference.entitlementId.isNotBlank(),
            ) {
                "Runtime reference identity differs from requested identity"
            }
            sourceAppId(GameSource.AMAZON, reference.localRowId)
        }
        is SourceOwnedCopyReference.Custom -> {
            check(requestedKey.source == GameSource.CUSTOM_GAME) {
                "Runtime reference source differs from requested source"
            }
            val appId = requestedKey.stableSourceId.exactPositiveIntOrNull()
            check(appId != null && reference.appId == appId) {
                "Runtime reference identity differs from requested identity"
            }
            sourceAppId(GameSource.CUSTOM_GAME, reference.appId)
        }
    }

    if (libraryItem == null) {
        check(requestedKey.source == GameSource.GOG && expectedLibraryItemId == null) {
            "Runtime executable identity is missing"
        }
        return
    }
    check(libraryItem.gameSource == requestedKey.source) {
        "Runtime executable source differs from requested source"
    }
    check(libraryItem.appId == expectedLibraryItemId) {
        "Runtime executable identity differs from requested identity"
    }
}

private fun String.exactPositiveIntOrNull(): Int? =
    toIntOrNull()?.takeIf { value -> value > 0 && value.toString() == this }

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
