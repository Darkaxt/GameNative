package app.gamenative.library.canonical.artwork

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Image similarity only; this value carries no catalog or ownership authority. */
data class ArtworkFingerprint(
    val width: Int,
    val height: Int,
    val bits: Long,
    val algorithmVersion: Int = ALGORITHM_VERSION,
) {
    fun corroborates(other: ArtworkFingerprint): Boolean {
        if (!isValid() || !other.isValid()) return false
        val aspect = width.toDouble() / height
        val otherAspect = other.width.toDouble() / other.height
        if (max(aspect, otherAspect) / min(aspect, otherAspect) > 1.10) return false
        return java.lang.Long.bitCount(bits xor other.bits) <= MAX_DISTANCE
    }

    internal fun isValid(): Boolean = algorithmVersion == ALGORITHM_VERSION &&
        width >= MIN_SIDE && height >= MIN_SIDE &&
        width.toLong() * height.toLong() <= MAX_PIXELS && bits >= 0L

    companion object {
        const val ALGORITHM_VERSION = 1
        const val MAX_DISTANCE = 6
        const val MAX_PIXELS = 4_194_304
        private const val SAMPLE_SIDE = 32
        private const val HASH_SIDE = 8
        private const val MIN_SIDE = 64
        private val cosine = Array(HASH_SIDE) { frequency ->
            DoubleArray(SAMPLE_SIDE) { position ->
                cos(Math.PI * (2 * position + 1) * frequency / (2 * SAMPLE_SIDE))
            }
        }

        @JvmStatic
        fun fromArgb(width: Int, height: Int, pixels: IntArray): ArtworkFingerprint? {
            val pixelCount = width.toLong() * height.toLong()
            if (width < MIN_SIDE || height < MIN_SIDE || pixelCount > MAX_PIXELS || pixelCount != pixels.size.toLong()) {
                return null
            }
            val samples = DoubleArray(SAMPLE_SIDE * SAMPLE_SIDE)
            val counts = IntArray(samples.size)
            var opaque = 0L
            for (y in 0 until height) {
                val sampleRow = y * SAMPLE_SIDE / height * SAMPLE_SIDE
                for (x in 0 until width) {
                    val pixel = pixels[y * width + x]
                    if (pixel ushr 24 < 224) continue
                    opaque++
                    val sample = sampleRow + x * SAMPLE_SIDE / width
                    samples[sample] += 0.2126 * ((pixel ushr 16) and 255) +
                        0.7152 * ((pixel ushr 8) and 255) + 0.0722 * (pixel and 255)
                    counts[sample]++
                }
            }
            if (opaque * 100 < pixelCount * 95 || counts.any { it == 0 }) return null
            samples.indices.forEach { samples[it] /= counts[it] }
            val mean = samples.average()
            val variance = samples.sumOf { (it - mean) * (it - mean) } / samples.size
            if (variance < 64.0) return null

            val coefficients = DoubleArray(HASH_SIDE * HASH_SIDE - 1)
            var index = 0
            for (v in 0 until HASH_SIDE) {
                for (u in 0 until HASH_SIDE) {
                    if (u == 0 && v == 0) continue
                    var coefficient = 0.0
                    for (y in 0 until SAMPLE_SIDE) {
                        for (x in 0 until SAMPLE_SIDE) {
                            coefficient += samples[y * SAMPLE_SIDE + x] * cosine[u][x] * cosine[v][y]
                        }
                    }
                    coefficients[index++] = coefficient *
                        (if (u == 0) 1 / sqrt(2.0) else 1.0) *
                        (if (v == 0) 1 / sqrt(2.0) else 1.0)
                }
            }
            val median = coefficients.sorted()[coefficients.size / 2]
            var bits = 0L
            coefficients.forEachIndexed { bit, coefficient ->
                if (coefficient > median) bits = bits or (1L shl bit)
            }
            return ArtworkFingerprint(width, height, bits)
        }
    }
}
