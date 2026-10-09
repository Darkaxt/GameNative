package app.gamenative.library.canonical.artwork

import android.app.Application
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.junit.rules.TimeoutRule

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkImageDecoderTest {
    @get:Rule val timeout = TimeoutRule.seconds(30)

    @Test
    fun boundedDecodedArtworkCorroboratesItsOriginalPixels() {
        val decoded = decode(ArtworkEncodedFixtures.png(), "image/png")
        assertNotNull(decoded)
        assertTrue(ArtworkFixtures.corroborates(ArtworkFixtures.fingerprint(192, 288), requireNotNull(decoded)))
    }

    @Test
    fun nativeJpegArtworkCorroboratesAfterBoundedDecoding() {
        val bytes = ArtworkEncodedFixtures.jpeg()
        assertTrue((bytes[0].toInt() and 255) == 255 && (bytes[1].toInt() and 255) == 216)
        val decoded = decode(bytes, "image/jpeg")
        assertNotNull(decoded)
        assertTrue(ArtworkFixtures.corroborates(ArtworkFixtures.fingerprint(192, 288), requireNotNull(decoded)))
    }

    @Test
    fun nativeWebpArtworkCorroboratesWithoutAFormatFallback() {
        val bytes = ArtworkEncodedFixtures.webp()
        assertTrue(bytes.copyOfRange(0, 4).decodeToString() == "RIFF")
        assertTrue(bytes.copyOfRange(8, 12).decodeToString() == "WEBP")
        val decoded = decode(bytes, "image/webp")
        assertNotNull(decoded)
        assertTrue(ArtworkFixtures.corroborates(ArtworkFixtures.fingerprint(192, 288), requireNotNull(decoded)))
    }

    @Test
    fun malformedUnsupportedAndOversizedBodiesAreUnavailable() {
        assertNull(decode(byteArrayOf(1, 2, 3), "image/png"))
        assertNull(decode("<svg>synthetic</svg>".toByteArray(), "image/svg+xml"))
        assertNull(decode(ByteArray(2_097_153), "image/png"))
        assertNull(decode(ArtworkEncodedFixtures.png(), "text/html"))
    }

    @Test
    fun oversizedHeaderCannotAllocateAnImage() {
        val bytes = ArtworkEncodedFixtures.png().copyOf()
        ByteBuffer.wrap(bytes, 16, 8).putInt(4_096).putInt(4_096)
        val checksum = CRC32().apply { update(bytes, 12, 17) }.value.toInt()
        ByteBuffer.wrap(bytes, 29, 4).putInt(checksum)
        assertNull(decode(bytes, "image/png"))
    }

    @Test
    fun transparentPlaceholderStillFailsAfterDecoding() {
        assertNull(decode(ArtworkEncodedFixtures.png(transparent = true), "image/png"))
    }

    private fun decode(bytes: ByteArray, mimeType: String): Any? = ArtworkBoundaryFixtures.type("ArtworkImageDecoder")
        .getMethod("decode", ByteArray::class.java, String::class.java)
        .invoke(null, bytes, mimeType)
}

internal object ArtworkEncodedFixtures {
    fun png(transparent: Boolean = false): ByteArray = encode(Bitmap.CompressFormat.PNG, transparent)

    fun webp(): ByteArray = encode(Bitmap.CompressFormat.WEBP, transparent = false)

    fun jpeg(): ByteArray = encode(Bitmap.CompressFormat.JPEG, transparent = false)

    private fun encode(format: Bitmap.CompressFormat, transparent: Boolean): ByteArray {
        val width = 192
        val height = 288
        val pixels = if (transparent) IntArray(width * height) else ArtworkFixtures.cover(width, height)
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream()
        try {
            assertTrue(bitmap.compress(format, 85, bytes))
        } finally {
            bitmap.recycle()
        }
        return bytes.toByteArray()
    }
}
