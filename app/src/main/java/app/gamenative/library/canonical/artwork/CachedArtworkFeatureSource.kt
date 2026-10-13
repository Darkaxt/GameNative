package app.gamenative.library.canonical.artwork

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class CachedArtworkFeatureSource @Inject constructor(
    private val images: ArtworkImageProvider,
    private val cache: ArtworkFeatureCache,
    private val matcher: ArtworkFeatureMatcher,
) : ArtworkFeatureSource {
    private val acquisition = Mutex()

    override suspend fun original(reference: ArtworkReference): ArtworkFeatures? = acquisition.withLock {
        withContext(Dispatchers.IO) {
            if (ArtworkUrlPolicy.Default.groupingUrl(reference.source, reference.url, reference.steamAppId) == null) return@withContext null
            cache.original(reference)?.let { return@withContext it }
            val encoded = images.grouping(reference)
                ?: return@withContext cache.original(reference, freshOnly = false)
            if (!ArtworkImageDecoder.hasSupportedSignature(encoded.bytes, encoded.mimeType)) {
                cache.invalidate(reference)
                cache.flush()
                return@withContext null
            }
            val content = ArtworkFeatures.hash(encoded.bytes)
            val features = cache.feature(content) ?: withContext(Dispatchers.Default) {
                ensureActive()
                val pixels = ArtworkImageDecoder.featurePixels(encoded.bytes, encoded.mimeType) ?: return@withContext null
                matcher.extract(pixels.width, pixels.height, pixels.argb, content).also { ensureActive() }
            } ?: run {
                cache.invalidate(reference)
                cache.flush()
                return@withContext null
            }
            ensureActive()
            cache.put(reference, features)
            features
        }
    }
}
