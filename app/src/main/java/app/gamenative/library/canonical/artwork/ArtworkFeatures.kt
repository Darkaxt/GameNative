package app.gamenative.library.canonical.artwork

import app.gamenative.data.GameSource
import java.security.MessageDigest

/** Public original image reference, never an entitlement or a rendered card bitmap. */
data class ArtworkReference(val source: GameSource, val url: String, val steamAppId: Int? = null)

class ArtworkFeatures(
    val contentHash: String,
    val width: Int,
    val height: Int,
    points: FloatArray,
    descriptors: ByteArray,
    val algorithmVersion: Int = ALGORITHM_VERSION,
) {
    internal val points = points.copyOf()
    internal val descriptors = descriptors.copyOf()
    val count: Int get() = points.size / 2

    fun isValid(): Boolean = algorithmVersion == ALGORITHM_VERSION && HASH.matches(contentHash) &&
        width in 64..768 && height in 64..768 && count in 24..MAX_FEATURES &&
        points.size == count * 2 && descriptors.size == count * DESCRIPTOR_BYTES &&
        points.all { it.isFinite() && it in 0f..1f }

    companion object {
        const val ALGORITHM_VERSION = 1
        const val MAX_FEATURES = 1_600
        const val DESCRIPTOR_BYTES = 32
        internal val HASH = Regex("[a-f0-9]{64}")
        internal fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}

enum class ArtworkMatchReason {
    ACCEPTED, REASON_NOT_RETAINED, INVALID_FEATURES, TOO_FEW_MATCHES, AFFINE_UNAVAILABLE,
    NONFINITE_TRANSFORM, TOO_FEW_INLIERS, LOW_INLIER_RATIO, LOW_COVERAGE, TOO_FEW_CELLS,
    NARROW_HORIZONTAL_SPAN, NARROW_VERTICAL_SPAN, IMPLAUSIBLE_SCALE, EXCESSIVE_ROTATION,
    EXCESSIVE_RESIDUAL, NATIVE_UNAVAILABLE,
}

data class ArtworkMatch(
    val accepted: Boolean,
    val matches: Int = 0,
    val inliers: Int = 0,
    val inlierRatio: Double = 0.0,
    val coverage: Double = 0.0,
    val occupiedCells: Int = 0,
    val meanError: Double = 0.0,
    val completed: Boolean = true,
    val reason: ArtworkMatchReason = if (!completed) ArtworkMatchReason.NATIVE_UNAVAILABLE
        else if (accepted) ArtworkMatchReason.ACCEPTED else ArtworkMatchReason.REASON_NOT_RETAINED,
)

interface ArtworkFeatureSource {
    suspend fun original(reference: ArtworkReference): ArtworkFeatures?
}
