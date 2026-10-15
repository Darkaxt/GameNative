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

    /** Isolated diagnostic replay: no put/invalidate/flush or production-acquisition events. */
    internal suspend fun inspectOriginal(reference: ArtworkReference): ArtworkOriginalInspection = acquisition.withLock {
        withContext(Dispatchers.IO) {
            var stage = ArtworkAcquisitionStage.INVALID_RESPONSE
            var status: Int? = null
            val encoded = images.inspectOriginal(reference) { value, code -> stage = value; status = code }
                ?: return@withContext ArtworkOriginalInspection(reference, null, stage, status)
            if (!ArtworkImageDecoder.hasSupportedSignature(encoded.bytes, encoded.mimeType)) {
                return@withContext ArtworkOriginalInspection(reference, null, ArtworkAcquisitionStage.SIGNATURE_REJECTED, status)
            }
            stage = ArtworkAcquisitionStage.EXTRACTOR_UNAVAILABLE
            val feature = withContext(Dispatchers.Default) {
                ensureActive()
                val pixels = ArtworkImageDecoder.featurePixels(encoded.bytes, encoded.mimeType) ?: run {
                    stage = ArtworkAcquisitionStage.DECODE_UNAVAILABLE
                    return@withContext null
                }
                matcher.extract(pixels.width, pixels.height, pixels.argb, ArtworkFeatures.hash(encoded.bytes))
            }
            ensureActive()
            ArtworkOriginalInspection(reference, feature,
                if (feature == null) stage else ArtworkAcquisitionStage.FEATURES_READY, status)
        }
    }

    override suspend fun original(reference: ArtworkReference): ArtworkFeatures? = acquisition.withLock {
        withContext(Dispatchers.IO) {
            if (ArtworkUrlPolicy.Default.groupingUrl(reference.source, reference.url, reference.steamAppId) == null) return@withContext null
            cache.original(reference)?.let {
                recordArtworkAcquisition(reference, ArtworkAcquisitionStage.DESCRIPTOR_CACHE_HIT, it.contentHash)
                return@withContext it
            }
            val encoded = images.grouping(reference) ?: return@withContext cache.original(reference, freshOnly = false)?.also {
                recordArtworkAcquisition(reference, ArtworkAcquisitionStage.STALE_CACHE_FALLBACK, it.contentHash)
            }
            if (!ArtworkImageDecoder.hasSupportedSignature(encoded.bytes, encoded.mimeType)) {
                recordArtworkAcquisition(reference, ArtworkAcquisitionStage.SIGNATURE_REJECTED)
                cache.invalidate(reference)
                cache.flush()
                return@withContext null
            }
            val content = ArtworkFeatures.hash(encoded.bytes)
            val features = cache.feature(content) ?: withContext(Dispatchers.Default) {
                ensureActive()
                val pixels = ArtworkImageDecoder.featurePixels(encoded.bytes, encoded.mimeType) ?: run {
                    recordArtworkAcquisition(reference, ArtworkAcquisitionStage.DECODE_UNAVAILABLE, content)
                    return@withContext null
                }
                matcher.extract(pixels.width, pixels.height, pixels.argb, content).also {
                    ensureActive()
                    if (it == null) recordArtworkAcquisition(reference, ArtworkAcquisitionStage.EXTRACTOR_UNAVAILABLE, content)
                }
            } ?: run {
                cache.invalidate(reference)
                cache.flush()
                return@withContext null
            }
            ensureActive()
            cache.put(reference, features)
            recordArtworkAcquisition(reference, ArtworkAcquisitionStage.FEATURES_READY, features.contentHash)
            features
        }
    }
}
