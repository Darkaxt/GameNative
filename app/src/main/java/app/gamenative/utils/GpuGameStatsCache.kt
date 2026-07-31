package app.gamenative.utils

import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.utils.DeviceGameStatsService.DeviceGameStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Persistent cache for the GPU-wide game stats blob (keyed by GPU only) with a 6-hour TTL.
 *
 * Mirrors [DeviceGameStatsCache] but sources from the gpu-game-stats endpoint. Used for the
 * "successful runs on your GPU" stat, which aggregates across all devices with the same GPU.
 */
object GpuGameStatsCache {
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L // 6 hours

    private val cacheLock = Any()
    private val mutationMutex = Mutex()
    private var inMemory: Map<GameSource, Map<String, DeviceGameStats>> = emptyMap()
    private var loadedTimestamp: Long = 0L
    private var cacheLoaded = false
    private var latestRequestGeneration = 0L

    @Serializable
    private data class CachedStats(
        val stats: Map<String, Map<String, DeviceGameStatsData>>,
        val timestamp: Long,
    )

    @Serializable
    private data class DeviceGameStatsData(
        val successfulRuns: Int,
        val medianFps: Int,
        val fiveStarReviews: Int,
        val medianSessionSec: Int,
    )

    private fun DeviceGameStats.toData() = DeviceGameStatsData(
        successfulRuns, medianFps, fiveStarReviews, medianSessionSec,
    )

    private fun DeviceGameStatsData.toStats() = DeviceGameStats(
        successfulRuns, medianFps, fiveStarReviews, medianSessionSec,
    )

    private fun loadCacheLocked() {
        if (cacheLoaded) return
        try {
            val cacheJson = PrefManager.gpuGameStatsCache
            if (cacheJson.isNotEmpty() && cacheJson != "{}") {
                val cached = Json.decodeFromString<CachedStats>(cacheJson)
                inMemory = cached.stats.mapNotNull { (platform, games) ->
                    val source = runCatching { GameSource.valueOf(platform) }.getOrNull()
                        ?: return@mapNotNull null
                    source to games.mapValues { it.value.toStats() }
                }.toMap()
                loadedTimestamp = cached.timestamp
                Timber.tag("GpuGameStatsCache").d("Loaded ${inMemory.values.sumOf { it.size }} cached game stats")
            }
        } catch (e: Exception) {
            Timber.tag("GpuGameStatsCache").e(e, "Failed to load cache from persistent storage")
        }
        cacheLoaded = true
    }

    private fun encodeCache(data: Map<GameSource, Map<String, DeviceGameStats>>, timestamp: Long): String =
        Json.encodeToString(
            CachedStats(
                stats = data.entries.associate { (source, games) ->
                    source.name to games.mapValues { it.value.toData() }
                },
                timestamp = timestamp,
            ),
        )

    /** Fetches and persists fresh stats if the cache is empty or older than the TTL. */
    suspend fun refreshIfStale(gpuName: String, modernBuild: Boolean): Boolean {
        val now = System.currentTimeMillis()
        val (requestGeneration, cacheIsFresh) = mutationMutex.withLock {
            synchronized(cacheLock) {
                loadCacheLocked()
                latestRequestGeneration += 1L
                latestRequestGeneration to
                    (loadedTimestamp != 0L && now - loadedTimestamp < CACHE_TTL_MS)
            }
        }
        if (cacheIsFresh) {
            Timber.tag("GpuGameStatsCache").d("Cache is fresh, skipping fetch")
            return true
        }

        val fetched = DeviceGameStatsService.fetchForGpu(gpuName, modernBuild) ?: return false
        val encoded = try {
            encodeCache(fetched, now)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.tag("GpuGameStatsCache").e(error, "Failed to encode cache for persistent storage")
            return false
        }

        return mutationMutex.withLock {
            if (synchronized(cacheLock) { requestGeneration != latestRequestGeneration }) {
                return@withLock false
            }
            try {
                PrefManager.writeGpuGameStatsCache(encoded)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.tag("GpuGameStatsCache").e(
                    "Failed to save cache to persistent storage: %s",
                    error.javaClass.simpleName,
                )
                return@withLock false
            }
            synchronized(cacheLock) {
                inMemory = fetched
                loadedTimestamp = now
            }
            Timber.tag("GpuGameStatsCache").d("Saved ${fetched.values.sumOf { it.size }} game stats to persistent storage")
            true
        }
    }

    /** Gets stats for a single game, if available. */
    fun getStats(source: GameSource, gameName: String): DeviceGameStats? = synchronized(cacheLock) {
        loadCacheLocked()
        inMemory[source]?.get(gameName)
    }

    /** Returns all cached stats, grouped by platform. */
    fun getAll(): Map<GameSource, Map<String, DeviceGameStats>> = synchronized(cacheLock) {
        loadCacheLocked()
        inMemory
    }

    /** Clears memory and persistence as one acknowledged, serialized mutation. */
    suspend fun clear() {
        mutationMutex.withLock {
            PrefManager.writeGpuGameStatsCache("{}")
            synchronized(cacheLock) {
                latestRequestGeneration += 1L
                inMemory = emptyMap()
                loadedTimestamp = 0L
                cacheLoaded = true
            }
            Timber.tag("GpuGameStatsCache").d("Cache cleared")
        }
    }
}
