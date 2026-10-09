package app.gamenative.library.canonical.artwork

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.junit.rules.TimeoutRule

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [29])
class ArtworkCompressionTest {
    @get:Rule val timeout = TimeoutRule.seconds(30)

    @Test
    fun compressedOriginalCoverCorroboratesAtTheSameComposition() {
        val width = 384
        val height = 576
        val pixels = ArtworkFixtures.cover(width, height)
        val original = ArtworkFixtures.fromArgb(width, height, pixels)
        assertNotNull(original)
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream()
        try {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 35, bytes))
        } finally {
            bitmap.recycle()
        }
        val encoded = bytes.toByteArray()
        assertTrue("Fixture must use actual JPEG encoding", encoded.size > 2 &&
            (encoded[0].toInt() and 255) == 255 && (encoded[1].toInt() and 255) == 216)
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(encoded, 0, encoded.size))
        val compressedPixels = IntArray(decoded.width * decoded.height)
        val compressed = try {
            decoded.getPixels(compressedPixels, 0, decoded.width, 0, 0, decoded.width, decoded.height)
            ArtworkFixtures.fromArgb(decoded.width, decoded.height, compressedPixels)
        } finally {
            decoded.recycle()
        }
        assertNotNull(compressed)
        assertTrue(ArtworkFixtures.corroborates(requireNotNull(original), requireNotNull(compressed)))
    }
}
