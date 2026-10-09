package app.gamenative.library.canonical.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkFingerprintTest {
    @Test(timeout = 5_000)
    fun resizedOriginalCoverCorroboratesWithoutPixelEquality() {
        val original = ArtworkFixtures.fingerprint(384, 576)
        val resized = ArtworkFixtures.fingerprint(192, 288)
        assertTrue(ArtworkFixtures.corroborates(original, resized))
        assertEquals(1, ArtworkFixtures.type().getMethod("getAlgorithmVersion").invoke(original))
    }

    @Test(timeout = 5_000)
    fun distinctCoverDoesNotCorroborate() {
        assertFalse(
            ArtworkFixtures.corroborates(
                ArtworkFixtures.fingerprint(384, 576),
                ArtworkFixtures.fingerprint(384, 576, distinct = true),
            ),
        )
    }

    @Test(timeout = 5_000)
    fun solidPlaceholderIsNotUsableArtwork() {
        assertNull(ArtworkFixtures.fromArgb(192, 288, IntArray(192 * 288) { 0xff404040.toInt() }))
    }

    @Test(timeout = 5_000)
    fun transparentAndTinyImagesAreNotUsableArtwork() {
        assertNull(ArtworkFixtures.fromArgb(192, 288, IntArray(192 * 288)))
        assertNull(ArtworkFixtures.fromArgb(32, 48, ArtworkFixtures.cover(32, 48)))
    }

    @Test(timeout = 5_000)
    fun invalidDimensionsAndPixelBudgetsFailClosed() {
        assertNull(ArtworkFixtures.fromArgb(Int.MAX_VALUE, Int.MAX_VALUE, intArrayOf(0)))
        assertNull(ArtworkFixtures.fromArgb(192, 288, intArrayOf(0)))
        assertNull(ArtworkFixtures.fromArgb(0, 288, intArrayOf(0)))
    }

    @Test(timeout = 5_000)
    fun sameBitsWithIncompatibleAspectCannotCorroborate() {
        val original = ArtworkFixtures.fingerprint(384, 576)
        assertFalse(ArtworkFixtures.corroborates(original, ArtworkFixtures.copy(original, width = 576, height = 384)))
    }

    @Test(timeout = 5_000)
    fun fingerprintsFromDifferentAlgorithmsCannotCorroborate() {
        val original = ArtworkFixtures.fingerprint(384, 576)
        assertFalse(ArtworkFixtures.corroborates(original, ArtworkFixtures.copy(original, algorithmVersion = 2)))
    }

    @Test(timeout = 5_000)
    fun distanceThresholdIsExplicitAndConservative() {
        val original = ArtworkFixtures.fingerprint(384, 576)
        val bits = ArtworkFixtures.type().getMethod("getBits").invoke(original) as Long
        assertTrue(ArtworkFixtures.corroborates(original, ArtworkFixtures.copy(original, bits = bits xor ((1L shl 6) - 1))))
        assertFalse(ArtworkFixtures.corroborates(original, ArtworkFixtures.copy(original, bits = bits xor ((1L shl 7) - 1))))
    }

    @Test(timeout = 5_000)
    fun sameBitsCannotMakeInvalidFingerprintDimensionsUsable() {
        val original = ArtworkFixtures.fingerprint(384, 576)
        assertFalse(ArtworkFixtures.corroborates(original, ArtworkFixtures.copy(original, width = 2, height = 3)))
        assertFalse(ArtworkFixtures.corroborates(original, ArtworkFixtures.copy(original, width = 3_840, height = 5_760)))
    }

    @Test(timeout = 5_000)
    fun unusedHighBitCannotBeCorroboratingEvidence() {
        val original = ArtworkFixtures.fingerprint(384, 576)
        val bits = ArtworkFixtures.type().getMethod("getBits").invoke(original) as Long
        assertFalse(ArtworkFixtures.corroborates(original, ArtworkFixtures.copy(original, bits = bits or Long.MIN_VALUE)))
    }

    @Test(timeout = 5_000)
    fun mostlyTransparentAndLowContrastPlaceholdersAreRejected() {
        val transparent = ArtworkFixtures.cover(192, 288).mapIndexed { index, pixel ->
            if (index % 10 == 0) pixel else pixel and 0x00ffffff
        }.toIntArray()
        assertNull(ArtworkFixtures.fromArgb(192, 288, transparent))
        val lowContrast = IntArray(192 * 288) { index ->
            if (index % 2 == 0) 0xff404040.toInt() else 0xff424242.toInt()
        }
        assertNull(ArtworkFixtures.fromArgb(192, 288, lowContrast))
    }
}

internal object ArtworkFixtures {
    fun type(): Class<*> {
        val type = runCatching {
            Class.forName("app.gamenative.library.canonical.artwork.ArtworkFingerprint")
        }.getOrNull()
        assertNotNull("Versioned independent-artwork fingerprint boundary is required", type)
        return requireNotNull(type)
    }

    fun fromArgb(width: Int, height: Int, pixels: IntArray): Any? = type()
        .getMethod("fromArgb", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IntArray::class.java)
        .invoke(null, width, height, pixels)

    fun fingerprint(width: Int, height: Int, distinct: Boolean = false): Any {
        val result = fromArgb(width, height, cover(width, height, distinct))
        assertNotNull("Fixture cover must be usable", result)
        return requireNotNull(result)
    }

    fun corroborates(first: Any, second: Any): Boolean =
        type().getMethod("corroborates", type()).invoke(first, second) as Boolean

    fun copy(
        original: Any,
        width: Int = type().getMethod("getWidth").invoke(original) as Int,
        height: Int = type().getMethod("getHeight").invoke(original) as Int,
        bits: Long = type().getMethod("getBits").invoke(original) as Long,
        algorithmVersion: Int = 1,
    ): Any = type().getConstructor(
        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        Long::class.javaPrimitiveType, Int::class.javaPrimitiveType,
    ).newInstance(width, height, bits, algorithmVersion)

    fun cover(width: Int, height: Int, distinct: Boolean = false): IntArray = IntArray(width * height) { index ->
        val u = (index % width).toDouble() / width
        val v = (index / width).toDouble() / height
        if (distinct) {
            when {
                u > 0.65 && v < 0.55 -> 0xffe6d246.toInt()
                u < 0.5 && v > 0.35 -> 0xff288cb4.toInt()
                else -> 0xff821928.toInt()
            }
        } else {
            when {
                u < 0.2 && v < 0.75 -> 0xffebebd2.toInt()
                u > 0.45 && v > 0.5 -> 0xffb46423.toInt()
                v > 0.8 -> 0xff146e5a.toInt()
                u > 0.3 && u < 0.8 && v > 0.15 && v < 0.3 -> 0xfff5f0e6.toInt()
                else -> 0xff232d50.toInt()
            }
        }
    }
}
