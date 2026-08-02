package app.gamenative.ui.screen.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage

data class GameMediaItem(
    val imageUrl: String? = null,
    val videoUrl: String? = null,
)

@Composable
internal fun GameMediaPager(
    media: List<GameMediaItem>,
    fallbackImageUrl: String?,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val safeMedia = remember(media) {
        media.filter { !it.videoUrl.isNullOrBlank() || !it.imageUrl.isNullOrBlank() }
    }
    val pagerState = rememberPagerState(pageCount = { safeMedia.size.coerceAtLeast(1) })

    Box(modifier = modifier.testTag("game-media-pager")) {
        if (safeMedia.isEmpty()) {
            VideoHero(
                videoUrl = null,
                fallbackImageUrl = fallbackImageUrl.orEmpty(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val item = safeMedia[page]
                if (!item.videoUrl.isNullOrBlank()) {
                    VideoHero(
                        videoUrl = item.videoUrl,
                        fallbackImageUrl = (item.imageUrl ?: fallbackImageUrl).orEmpty(),
                        contentDescription = contentDescription,
                        active = page == pagerState.currentPage,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    CoilImage(
                        imageModel = { item.imageUrl },
                        imageOptions = ImageOptions(
                            contentDescription = contentDescription,
                            contentScale = ContentScale.Crop,
                        ),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        if (safeMedia.size > 1) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "${pagerState.currentPage + 1}/${safeMedia.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
    }
}
