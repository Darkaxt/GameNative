package app.gamenative.ui.screen.library

import androidx.compose.ui.unit.dp
import app.gamenative.ui.screen.library.components.trailerVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalDetailMediaPolicyTest {
    @Test
    fun `constrained overview keeps active media and carousel within available height`() {
        val width = constrainedMediaGalleryWidth(
            availableWidth = 700.dp,
            availableHeight = 450.dp,
            hasCarousel = true,
        )
        val galleryHeight = width * 9f / 16f + 84.dp

        assertTrue(width < 668.dp)
        assertTrue(galleryHeight <= 418.dp)
    }

    @Test
    fun `spacious overview keeps the existing media width cap`() {
        assertEquals(
            960.dp,
            constrainedMediaGalleryWidth(
                availableWidth = 1_200.dp,
                availableHeight = 900.dp,
                hasCarousel = true,
            ),
        )
    }

    @Test
    fun `trailers start muted and unmute without changing system volume`() {
        assertEquals(0f, trailerVolume(muted = true))
        assertEquals(1f, trailerVolume(muted = false))
    }
}
