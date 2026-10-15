package app.gamenative.library.canonical.artwork

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.junit.rules.TimeoutRule

/** Original assets through production decoding and extraction; host JNI is not Android-device parity. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkFeatureMatcherTest {
    @get:Rule val timeout = TimeoutRule.seconds(30)
    private val matcher = ArtworkFeatureMatcher()

    @Test fun realDredgeStorefrontBackgroundsMatchDespiteDifferentLayoutAndPoorPhash() {
        val images = listOf("dredge-gog.jpg", "dredge-epic.jpg", "dredge-steam.jpg").map(ArtworkFeatureFixtures::artwork)
        for (left in images.indices) for (right in left + 1 until images.size) {
            assertTrue("DREDGE original storefront pair $left/$right", compare(images[left], images[right]))
        }
    }

    @Test fun resolutionBrightnessCompressionAndOffCentreCropPreserveBackgroundEvidence() {
        val source = image("dredge-epic.jpg")
        val cropped = Bitmap.createBitmap(source, source.width / 8, source.height / 10, source.width * 3 / 4, source.height * 4 / 5)
        val resized = Bitmap.createScaledBitmap(cropped, 480, 288, true)
        try {
            val pixels = IntArray(resized.width * resized.height)
            resized.getPixels(pixels, 0, resized.width, 0, 0, resized.width, resized.height)
            pixels.indices.forEach { index ->
                val pixel = pixels[index]
                val r = ((pixel ushr 16 and 255) * 0.72 + 18).toInt().coerceIn(0, 255)
                val g = ((pixel ushr 8 and 255) * 0.72 + 18).toInt().coerceIn(0, 255)
                val b = ((pixel and 255) * 0.72 + 18).toInt().coerceIn(0, 255)
                pixels[index] = -0x1000000 or (r shl 16) or (g shl 8) or b
            }
            resized.setPixels(pixels, 0, resized.width, 0, 0, resized.width, resized.height)
            val encoded = ByteArrayOutputStream().apply { assertTrue(resized.compress(Bitmap.CompressFormat.JPEG, 80, this)) }.toByteArray()
            val decoded = requireNotNull(ArtworkImageDecoder.featurePixels(encoded, "image/jpeg"))
            val transformed = requireNotNull(matcher.extract(decoded.width, decoded.height, decoded.argb, ArtworkFeatures.hash(encoded)))
            assertTrue(compare(ArtworkFeatureFixtures.artwork("dredge-epic.jpg"), transformed))
        } finally { source.recycle(); cropped.recycle(); resized.recycle() }
    }

    @Test fun unrelatedStorefrontArtworkNeverGroupsBecauseOfPaletteOrTypography() {
        val names = listOf("dredge-steam.jpg", "portal2.jpg", "dome-keeper.jpg", "loop-hero.jpg", "dave-the-diver.jpg")
        val images = names.map(ArtworkFeatureFixtures::artwork)
        for (left in images.indices) for (right in left + 1 until images.size) {
            assertFalse("Unrelated real artwork ${names[left]}/${names[right]}", compare(images[left], images[right]))
        }
    }

    @Test fun aSharedLocalLogoDoesNotSupplyDistributedBackgroundEvidence() {
        val originals = listOf(image("portal2.jpg"), image("dome-keeper.jpg"))
        val images = originals.map { Bitmap.createScaledBitmap(it, 768, 432, true) }
        val logo = image("dredge-steam.jpg")
        try {
            images.forEach { target -> Canvas(target).drawBitmap(logo, Rect(108, 32, 360, 98), Rect(250, 165, 518, 235), Paint(Paint.FILTER_BITMAP_FLAG)) }
            assertFalse("A high ratio of local logo inliers is not shared background artwork", compare(features(images[0]), features(images[1])))
        } finally { (originals + images + logo).forEach(Bitmap::recycle) }
    }

    private fun image(name: String): Bitmap {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/artwork/$name")).use { it.readBytes() }
        val pixels = requireNotNull(ArtworkImageDecoder.featurePixels(bytes, "image/jpeg"))
        return Bitmap.createBitmap(pixels.argb, pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
    }

    private fun features(image: Bitmap): ArtworkFeatures {
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        val bytes = ByteBuffer.allocate(pixels.size * 4).apply { asIntBuffer().put(pixels) }.array()
        return requireNotNull(matcher.extract(image.width, image.height, pixels, ArtworkFeatures.hash(bytes)))
    }

    private fun compare(left: ArtworkFeatures, right: ArtworkFeatures): Boolean {
        val started = System.nanoTime()
        val result = matcher.compare(left, right)
        assertTrue("Native host matching must complete", result.completed)
        assertTrue("A completed real matcher verdict must carry its owning reason",
            result.reason != ArtworkMatchReason.REASON_NOT_RETAINED && result.reason != ArtworkMatchReason.NATIVE_UNAVAILABLE)
        assertTrue("Acceptance reason agrees with the verdict", (result.reason == ArtworkMatchReason.ACCEPTED) == result.accepted)
        println("Artwork ORB ${left.count}/${right.count}: $result; ${(System.nanoTime() - started) / 1_000_000}ms")
        return result.accepted
    }
}
