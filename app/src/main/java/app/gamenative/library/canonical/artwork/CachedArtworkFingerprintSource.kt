package app.gamenative.library.canonical.artwork

import app.gamenative.data.GameSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

@Singleton
class CachedArtworkFingerprintSource internal constructor(
    private val cache: ArtworkFingerprintCache,
    private val provider: ArtworkFingerprintSource,
) : ArtworkFingerprintSource {
    @Inject
    constructor(cache: ArtworkFingerprintCache, provider: ArtworkImageProvider) : this(
        cache, provider as ArtworkFingerprintSource,
    )

    override suspend fun original(source: GameSource, raw: String, steamAppId: Int?): ArtworkFingerprint? {
        val url = ArtworkUrlPolicy.Default.originalUrl(source, raw, steamAppId) ?: return null
        return fetch(source, url.toString(), steamAppId) { provider.original(source, url.toString(), steamAppId) }
    }

    override suspend fun candidate(steamAppId: Int, raw: String): ArtworkFingerprint? {
        val url = ArtworkUrlPolicy.Default.candidateUrl(steamAppId, raw) ?: return null
        return fetch(GameSource.STEAM, url.toString(), steamAppId) { provider.candidate(steamAppId, url.toString()) }
    }

    private suspend fun fetch(
        source: GameSource, raw: String, steamAppId: Int?, download: suspend () -> ArtworkFingerprint?,
    ): ArtworkFingerprint? {
        coroutineContext.ensureActive()
        val cached = withContext(Dispatchers.IO) { cache.get(source, raw, steamAppId) }
        if (cached != null) return cached
        val fresh = download()?.takeIf { it.isValid() } ?: return null
        coroutineContext.ensureActive()
        withContext(Dispatchers.IO) { cache.put(source, raw, steamAppId, fresh) }
        return fresh
    }
}
