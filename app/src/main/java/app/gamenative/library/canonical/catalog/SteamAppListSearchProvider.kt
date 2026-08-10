package app.gamenative.library.canonical.catalog

import android.content.Context
import app.gamenative.data.canonical.CanonicalNormalization
import app.gamenative.library.metadata.MetadataLocale
import app.gamenative.library.metadata.sanitizeSteamText
import app.gamenative.service.steam.SteamWebApiKeySource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class SteamAppListSnapshot(
    val refreshedAtEpochSeconds: Long,
    val entries: List<SteamAppListEntry>,
)

internal interface SteamAppListCache {
    suspend fun load(): SteamAppListSnapshot?

    suspend fun write(snapshot: SteamAppListSnapshot)
}

@Singleton
internal class FileSteamAppListCache @Inject constructor(
    @ApplicationContext context: Context,
) : SteamAppListCache {
    private val file = File(context.filesDir, CACHE_RELATIVE_PATH)

    override suspend fun load(): SteamAppListSnapshot? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        try {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return@withContext null
                val refreshedAt = input.readLong().takeIf { it >= 0L } ?: return@withContext null
                val count = input.readInt().takeIf { it in 1..MAX_ENTRIES }
                    ?: return@withContext null
                val entries = ArrayList<SteamAppListEntry>(count)
                repeat(count) {
                    val appId = input.readInt().takeIf { it > 0 } ?: return@withContext null
                    val lastModified = input.readLong().takeIf { it >= 0L }
                        ?: return@withContext null
                    val titleByteCount = input.readInt().takeIf { it in 1..MAX_TITLE_BYTES }
                        ?: return@withContext null
                    val titleBytes = ByteArray(titleByteCount)
                    input.readFully(titleBytes)
                    val title = sanitizeSteamText(String(titleBytes, StandardCharsets.UTF_8))
                        ?: return@withContext null
                    entries += SteamAppListEntry(appId, title, lastModified)
                }
                SteamAppListSnapshot(refreshedAt, entries)
            }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun write(snapshot: SteamAppListSnapshot) = withContext(Dispatchers.IO) {
        require(snapshot.refreshedAtEpochSeconds >= 0L)
        val entries = snapshot.entries
            .distinctBy(SteamAppListEntry::steamAppId)
            .sortedBy(SteamAppListEntry::steamAppId)
        require(entries.size in 1..MAX_ENTRIES)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            DataOutputStream(BufferedOutputStream(temporary.outputStream())).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeLong(snapshot.refreshedAtEpochSeconds)
                output.writeInt(entries.size)
                entries.forEach { entry ->
                    val titleBytes = entry.title.toByteArray(StandardCharsets.UTF_8)
                    require(titleBytes.size in 1..MAX_TITLE_BYTES)
                    output.writeInt(entry.steamAppId)
                    output.writeLong(entry.lastModifiedEpochSeconds)
                    output.writeInt(titleBytes.size)
                    output.write(titleBytes)
                }
            }
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporary.delete()
        }
        Unit
    }

    private companion object {
        const val CACHE_RELATIVE_PATH = "catalog/steam-app-list-v1.bin"
        const val MAGIC = 0x47534C31
        const val VERSION = 1
        const val MAX_ENTRIES = 500_000
        const val MAX_TITLE_BYTES = 2_000
    }
}

@Singleton
internal class SteamAppListSearchProvider internal constructor(
    private val remoteSource: SteamAppListRemoteSource,
    private val keySource: SteamWebApiKeySource,
    private val cache: SteamAppListCache,
    private val nowEpochSeconds: () -> Long,
) : SteamCatalogSearchSource {
    @Inject
    constructor(
        remoteSource: SteamAppListRemoteSource,
        keySource: SteamWebApiKeySource,
        cache: SteamAppListCache,
    ) : this(
        remoteSource = remoteSource,
        keySource = keySource,
        cache = cache,
        nowEpochSeconds = { System.currentTimeMillis() / 1_000L },
    )

    private val refreshMutex = Mutex()
    @Volatile
    private var titleIndex: Map<String, List<SteamStoreSearchHit>>? = null
    @Volatile
    private var titleIndexRefreshedAtEpochSeconds: Long? = null
    private var lastRefreshFailureEpochSeconds: Long? = null
    @Volatile
    private var immediateRetryRequested = false

    override suspend fun search(
        query: String,
        locale: MetadataLocale,
    ): List<SteamStoreSearchHit> {
        val titleKey = normalizedTitleKey(query)
        if (titleKey.isEmpty()) return emptyList()
        return ensureIndex()[titleKey].orEmpty().take(MAX_RESULTS)
    }

    override fun searchLoaded(
        query: String,
        locale: MetadataLocale,
    ): List<SteamStoreSearchHit> {
        val titleKey = normalizedTitleKey(query)
        if (titleKey.isEmpty()) return emptyList()
        val index = titleIndex ?: return emptyList()
        val refreshedAt = titleIndexRefreshedAtEpochSeconds ?: return emptyList()
        if (isStale(refreshedAt, nowEpochSeconds())) return emptyList()
        return index[titleKey].orEmpty().take(MAX_RESULTS)
    }

    override fun requestImmediateRetry() {
        immediateRetryRequested = true
    }

    private fun normalizedTitleKey(query: String): String {
        val trimmed = query.trim()
        require(trimmed.isNotEmpty()) { "Steam catalog query is blank" }
        require(trimmed.codePointCount(0, trimmed.length) <= MAX_QUERY_CODE_POINTS) {
            "Steam catalog query is too long"
        }
        return CanonicalNormalization.titleKey(trimmed)
    }

    private suspend fun ensureIndex(): Map<String, List<SteamStoreSearchHit>> {
        val now = nowEpochSeconds()
        titleIndex?.takeIf { canUseLoadedIndex(now) }?.let { return it }
        return refreshMutex.withLock {
            titleIndex?.takeIf { canUseLoadedIndex(now) }?.let { return@withLock it }
            val cached = cache.load()
            if (cached != null && !isStale(cached.refreshedAtEpochSeconds, now)) {
                return@withLock loadIndex(cached)
            }
            val canRetry = immediateRetryRequested || lastRefreshFailureEpochSeconds?.let {
                now - it >= FAILURE_BACKOFF_SECONDS
            } != false
            immediateRetryRequested = false
            val apiKey = if (canRetry) keySource.keyOrNull() else null
            if (apiKey != null) {
                try {
                    val entries = remoteSource.fetchAll(apiKey)
                    if (entries.isEmpty()) throw SteamCatalogSearchException()
                    val refreshed = SteamAppListSnapshot(now, entries)
                    try {
                        cache.write(refreshed)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // The process-local public index remains usable when disk caching fails.
                    }
                    lastRefreshFailureEpochSeconds = null
                    return@withLock loadIndex(refreshed)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    lastRefreshFailureEpochSeconds = now
                }
            }
            cached?.let(::loadIndex)
                ?: titleIndex
                ?: throw SteamCatalogSearchException()
        }
    }

    private fun canUseLoadedIndex(now: Long): Boolean {
        val refreshedAt = titleIndexRefreshedAtEpochSeconds ?: return false
        if (!isStale(refreshedAt, now)) {
            immediateRetryRequested = false
            return true
        }
        return !immediateRetryRequested && lastRefreshFailureEpochSeconds?.let {
            now - it < FAILURE_BACKOFF_SECONDS
        } == true
    }

    private fun loadIndex(
        snapshot: SteamAppListSnapshot,
    ): Map<String, List<SteamStoreSearchHit>> = snapshot.toTitleIndex().also {
        titleIndex = it
        titleIndexRefreshedAtEpochSeconds = snapshot.refreshedAtEpochSeconds
    }

    private fun isStale(refreshedAtEpochSeconds: Long, now: Long): Boolean =
        now - refreshedAtEpochSeconds >= REFRESH_INTERVAL_SECONDS

    private fun SteamAppListSnapshot.toTitleIndex(): Map<String, List<SteamStoreSearchHit>> =
        entries.asSequence()
            .distinctBy(SteamAppListEntry::steamAppId)
            .sortedBy(SteamAppListEntry::steamAppId)
            .mapNotNull { entry ->
                val key = CanonicalNormalization.titleKey(entry.title)
                key.takeIf(String::isNotEmpty)?.let {
                    it to SteamStoreSearchHit(entry.steamAppId, entry.title, null)
                }
            }
            .groupBy(keySelector = Pair<String, SteamStoreSearchHit>::first) { it.second }

    private companion object {
        const val MAX_QUERY_CODE_POINTS = 256
        const val MAX_RESULTS = 10
        const val REFRESH_INTERVAL_SECONDS = 7L * 24L * 60L * 60L
        const val FAILURE_BACKOFF_SECONDS = 15L * 60L
    }
}
