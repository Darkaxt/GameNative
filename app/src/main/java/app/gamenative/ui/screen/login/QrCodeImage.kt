package app.gamenative.ui.screen.login

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import app.gamenative.ui.theme.PluviaTheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val QR_QUIET_ZONE_MODULES = 4
internal const val QR_MODULE_COLOR = -0x1000000
internal const val QR_BACKGROUND_COLOR = -0x1

internal data class QrCodePixels(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
)

/**
 * Displays a QR code for [content] at the desired [size].
 *
 * The QR code will render in the background before displaying. If this takes any amount of time, a circular progress
 * indicator will display until the QR code is rendered.
 * Source: https://gist.github.com/ryanholden8/6e921a4dc2a40bd40b3b5a15aaff4705
 */
@Composable
fun QrCodeImage(
    modifier: Modifier = Modifier,
    content: String,
    size: Dp,
) {
    val qrBitmap = rememberQrBitmap(content = content, size = size)

    Crossfade(
        modifier = Modifier,
        targetState = qrBitmap,
    ) { bitmap ->
        Box(
            modifier = modifier
                .size(size)
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                val bitmapPainter = remember(bitmap) {
                    BitmapPainter(
                        image = bitmap.asImageBitmap(),
                        filterQuality = FilterQuality.None,
                    )
                }
                Image(
                    painter = bitmapPainter,
                    contentDescription = null,
                    contentScale = ContentScale.None,
                    modifier = Modifier.size(size),
                )
            } else {
                CircularProgressIndicator(modifier = Modifier.size(92.dp))
            }
        }
    }
}

@Composable
private fun rememberQrBitmap(content: String, size: Dp): Bitmap? {
    val density = LocalDensity.current
    val sizePx = with(density) { size.roundToPx() }

    var bitmap by remember(content, sizePx) {
        mutableStateOf<Bitmap?>(null)
    }

    LaunchedEffect(content, sizePx) {
        bitmap = withContext(Dispatchers.Default) {
            try {
                val qrCode = generateQrCodePixels(content, sizePx)
                createBitmap(qrCode.width, qrCode.height).apply {
                    setPixels(qrCode.pixels, 0, qrCode.width, 0, 0, qrCode.width, qrCode.height)
                }
            } catch (_: WriterException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    return bitmap
}

internal fun generateQrCodePixels(content: String, sizePx: Int): QrCodePixels {
    require(content.isNotEmpty()) { "QR content must not be empty" }
    require(sizePx > 0) { "QR size must be positive" }

    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        sizePx,
        sizePx,
        mapOf(EncodeHintType.MARGIN to QR_QUIET_ZONE_MODULES),
    )
    val pixels = IntArray(matrix.width * matrix.height)

    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            pixels[y * matrix.width + x] = if (matrix[x, y]) QR_MODULE_COLOR else QR_BACKGROUND_COLOR
        }
    }

    return QrCodePixels(
        width = matrix.width,
        height = matrix.height,
        pixels = pixels,
    )
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_QrCodeImage() {
    PluviaTheme {
        Surface {
            QrCodeImage(Modifier, "Hello World", 256.dp)
        }
    }
}
