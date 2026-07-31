package app.gamenative.utils

import app.gamenative.PrefManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Persistent cache for game compatibility responses with 7-day TTL.
 * Uses lazy expiration - checks expiration on access, not on load (optimizes performance).
 */
object GameCompatibilityCache {
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L // 6 hours

    private val cacheLock = Any()
    private val inMemoryCache = mutableMapOf<String, GameCompatibilityService.GameCompatibilityResponse>()
    private val timestamps = mutableMapOf<String, Long>()
    private var cacheLoaded = false
    private var cacheGeneration = 0L

    @Serializable
    data class CachedCompatibilityResponse(
        val response: GameCompatibilityResponseData,
        val timestamp: Long
    )

    @Serializable
    data class GameCompatibilityResponseData(
        val gameName: String,
        val totalPlayableCount: Int,
        val gpuPlayableCount: Int,
        val avgRating: Float,
        val hasBeenTried: Boolean,
        val isNotWorking: Boolean
    )

    /**
     * Converts GameCompatibilityService.GameCompatibilityResponse to serializable format
     */
    private fun GameCompatibilityService.GameCompatibilityResponse.toData(): GameCompatibilityResponseData {
        return GameCompatibilityResponseData(
            gameName = this.gameName,
            totalPlayableCount = this.totalPlayableCount,
            gpuPlayableCount = this.gpuPlayableCount,
            avgRating = this.avgRating,
            hasBeenTried = this.hasBeenTried,
            isNotWorking = this.isNotWorking
        )
    }

    /**
     * Converts serializable format back to GameCompatibilityService.GameCompatibilityResponse
     */
    private fun GameCompatibilityResponseData.toResponse(): GameCompatibilityService.GameCompatibilityResponse {
        return GameCompatibilityService.GameCompatibilityResponse(
            gameName = this.gameName,
            totalPlayableCount = this.totalPlayableCount,
            gpuPlayableCount = this.gpuPlayableCount,
            avgRating = this.avgRating,
            hasBeenTried = this.hasBeenTried,
            isNotWorking = this.isNotWorking
        )
    }

    /**
     * Loads cache from persistent storage into memory.
     * Only parses JSON, no expiration filtering (lazy expiration).
     */
    private fun loadCacheLocked() {
        if (cacheLoaded) return

        try {
            val cacheJson = PrefManager.gameCompatibilityCache
            if (cacheJson.isEmpty() || cacheJson == "{}") {
                cacheLoaded = true
                return
            }

            val cacheMap = Json.decodeFromString<Map<String, CachedCompatibilityResponse>>(cacheJson)

            // Load all entries into memory (no expiration check here - lazy expiration)
            // Store both response and timestamp for expiration checking
            cacheMap.forEach { (gameName, cached) ->
                inMemoryCache[gameName] = cached.response.toResponse()
                timestamps[gameName] = cached.timestamp
            }

            Timber.tag("GameCompatibilityCache").d("Loaded ${inMemoryCache.size} cached entries from persistent storage")
            cacheLoaded = true
        } catch (e: Exception) {
            Timber.tag("GameCompatibilityCache").e(e, "Failed to load cache from persistent storage")
            cacheLoaded = true // Mark as loaded to avoid retrying
        }
    }

    /**
     * Saves cache to persistent storage.
     */
    private fun saveCacheLocked() {
        try {
            val now = System.currentTimeMillis()
            val cacheMap = inMemoryCache.mapValues { (gameName, response) ->
                val timestamp = timestamps[gameName] ?: now
                CachedCompatibilityResponse(response.toData(), timestamp)
            }
            val cacheJson = Json.encodeToString(cacheMap)
            PrefManager.gameCompatibilityCache = cacheJson
            Timber.tag("GameCompatibilityCache").d("Saved ${cacheMap.size} entries to persistent storage")
        } catch (e: Exception) {
            Timber.tag("GameCompatibilityCache").e(e, "Failed to save cache to persistent storage")
        }
    }

    /** Captures the authority generation for a compatibility network request. */
    fun captureGeneration(): Long = synchronized(cacheLock) { cacheGeneration }

    /**
     * Gets cached compatibility response for a game, if available and not expired.
     * Uses lazy expiration - checks expiration on access.
     */
    fun getCached(gameName: String): GameCompatibilityService.GameCompatibilityResponse? = synchronized(cacheLock) {
        loadCacheLocked()

        val cached = inMemoryCache[gameName] ?: return@synchronized null
        val timestamp = timestamps[gameName] ?: return@synchronized null

        // Lazy expiration check - only check when accessing
        val now = System.currentTimeMillis()
        if (now - timestamp >= CACHE_TTL_MS) {
            // Expired - remove from cache
            inMemoryCache.remove(gameName)
            timestamps.remove(gameName)
            Timber.tag("GameCompatibilityCache").d("Removed expired cache entry for: $gameName")
            return@synchronized null
        }

        cached
    }

    /**
     * Caches a compatibility response for a game.
     */
    fun cache(gameName: String, response: GameCompatibilityService.GameCompatibilityResponse) {
        cacheAll(mapOf(gameName to response))
        Timber.tag("GameCompatibilityCache").d("Cached compatibility for: $gameName")
    }

    /**
     * Caches multiple compatibility responses at once.
     */
    fun cacheAll(responses: Map<String, GameCompatibilityService.GameCompatibilityResponse>) {
        val generation = captureGeneration()
        cacheAllIfCurrent(generation, responses)
    }

    /** Commits responses only while the authority captured before network I/O is still current. */
    fun cacheAllIfCurrent(
        capturedGeneration: Long,
        responses: Map<String, GameCompatibilityService.GameCompatibilityResponse>,
    ): Boolean = synchronized(cacheLock) {
        if (capturedGeneration != cacheGeneration) return@synchronized false
        loadCacheLocked()
        val now = System.currentTimeMillis()
        inMemoryCache.putAll(responses)
        responses.keys.forEach { gameName ->
            timestamps[gameName] = now
        }
        saveCacheLocked()
        Timber.tag("GameCompatibilityCache").d("Cached ${responses.size} compatibility entries")
        true
    }

    /**
     * Checks if a game's compatibility is cached and not expired.
     */
    fun isCached(gameName: String): Boolean = getCached(gameName) != null

    /**
     * Clears the entire cache (both memory and persistent storage).
     */
    fun clear() {
        synchronized(cacheLock) {
            cacheGeneration += 1L
            inMemoryCache.clear()
            timestamps.clear()
            cacheLoaded = true
            PrefManager.gameCompatibilityCache = "{}"
            Timber.tag("GameCompatibilityCache").d("Cache cleared")
        }
    }

    /**
     * Gets the current cache size.
     */
    fun size(): Int = synchronized(cacheLock) {
        loadCacheLocked()
        inMemoryCache.size
    }
}
