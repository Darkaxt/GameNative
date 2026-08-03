package app.gamenative.ui.screen.login

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeImageTest {

    private val syntheticContent = "synthetic-qr-test-content"

    @Test
    fun `generated QR uses exact dimensions and canonical opaque colors`() {
        val sizePx = 256

        val qrCode = generateQrCodePixels(syntheticContent, sizePx)

        assertEquals(sizePx, qrCode.width)
        assertEquals(sizePx, qrCode.height)
        assertEquals(sizePx * sizePx, qrCode.pixels.size)
        assertTrue(qrCode.pixels.contains(OPAQUE_BLACK))
        assertTrue(qrCode.pixels.contains(OPAQUE_WHITE))
        assertTrue(
            qrCode.pixels.all { pixel ->
                pixel == OPAQUE_BLACK || pixel == OPAQUE_WHITE
            },
        )
    }

    @Test
    fun `generated QR preserves a four module quiet zone on every edge`() {
        val qrCode = generateQrCodePixels(syntheticContent, 256)
        val firstBlackY = (0 until qrCode.height).first { y ->
            (0 until qrCode.width).any { x -> qrCode[x, y] == OPAQUE_BLACK }
        }
        val lastBlackY = (qrCode.height - 1 downTo 0).first { y ->
            (0 until qrCode.width).any { x -> qrCode[x, y] == OPAQUE_BLACK }
        }
        val firstBlackX = (0 until qrCode.width).first { x ->
            (0 until qrCode.height).any { y -> qrCode[x, y] == OPAQUE_BLACK }
        }
        val lastBlackX = (qrCode.width - 1 downTo 0).first { x ->
            (0 until qrCode.height).any { y -> qrCode[x, y] == OPAQUE_BLACK }
        }
        val finderRunWidth = (firstBlackX until qrCode.width)
            .takeWhile { x -> qrCode[x, firstBlackY] == OPAQUE_BLACK }
            .size

        assertEquals(0, finderRunWidth % FINDER_PATTERN_WIDTH_MODULES)
        val moduleSizePx = finderRunWidth / FINDER_PATTERN_WIDTH_MODULES
        val quietZonePx = QR_QUIET_ZONE_MODULES * moduleSizePx
        assertTrue(firstBlackX >= quietZonePx)
        assertTrue(firstBlackY >= quietZonePx)
        assertTrue(qrCode.width - lastBlackX - 1 >= quietZonePx)
        assertTrue(qrCode.height - lastBlackY - 1 >= quietZonePx)
    }

    @Test
    fun `generated QR is deterministic and decodable`() {
        val first = generateQrCodePixels(syntheticContent, 256)
        val second = generateQrCodePixels(syntheticContent, 256)

        assertArrayEquals(first.pixels, second.pixels)

        val source = RGBLuminanceSource(first.width, first.height, first.pixels)
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)))
        assertEquals(syntheticContent, decoded.text)
    }

    private operator fun QrCodePixels.get(x: Int, y: Int): Int = pixels[y * width + x]

    private companion object {
        const val FINDER_PATTERN_WIDTH_MODULES = 7
        const val OPAQUE_BLACK = 0xFF000000.toInt()
        const val OPAQUE_WHITE = 0xFFFFFFFF.toInt()
    }
}
