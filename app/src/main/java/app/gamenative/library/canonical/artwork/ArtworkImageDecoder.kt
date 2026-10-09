package app.gamenative.library.canonical.artwork

import android.graphics.Bitmap
import android.graphics.BitmapFactory

object ArtworkImageDecoder {
    const val MAX_ENCODED_BYTES = 2_097_152

    @JvmStatic
    fun decode(bytes: ByteArray, mimeType: String): ArtworkFingerprint? {
        if (bytes.size > MAX_ENCODED_BYTES || !hasSupportedSignature(bytes, mimeType)) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth < 64 || bounds.outHeight < 64 || bounds.outWidth > 4_096 || bounds.outHeight > 4_096 ||
            bounds.outWidth.toLong() * bounds.outHeight > ArtworkFingerprint.MAX_PIXELS
        ) return null
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > 512 || bounds.outHeight / sampleSize > 768) sampleSize *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return try {
            val pixelCount = bitmap.width.toLong() * bitmap.height
            if (pixelCount > ArtworkFingerprint.MAX_PIXELS || bitmap.width < 64 || bitmap.height < 64) return null
            val pixels = IntArray(pixelCount.toInt())
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            ArtworkFingerprint.fromArgb(bitmap.width, bitmap.height, pixels)
        } finally {
            bitmap.recycle()
        }
    }

    private fun hasSupportedSignature(bytes: ByteArray, mimeType: String): Boolean = when (mimeType) {
        "image/jpeg" -> bytes.size >= 3 && (bytes[0].toInt() and 255) == 255 &&
            (bytes[1].toInt() and 255) == 216 && (bytes[2].toInt() and 255) == 255
        "image/png" -> bytes.size >= 8 && bytes.take(8) == listOf(-119, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte)
        "image/webp" -> bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
            bytes.copyOfRange(8, 12).decodeToString() == "WEBP"
        else -> false
    }
}
