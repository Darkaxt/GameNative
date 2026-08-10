package app.gamenative.ui.screen.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
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
import app.gamenative.library.metadata.MetadataProvider
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage

data class GameMediaItem(
    val imageUrl: String? = null,
    val videoUrl: String? = null,
)

internal enum class GameMediaLoadingPolicy {
    DEFAULT,
    STEAM_MEDIA,
}

@Composable
internal fun GameMediaPager(
    media: List<GameMediaItem>,
    fallbackImageUrl: String?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    loadingPolicy: GameMediaLoadingPolicy = GameMediaLoadingPolicy.DEFAULT,
    mediaProvider: MetadataProvider = MetadataProvider.STEAM_APPDETAILS,
    steamVideoMuted: Boolean = true,
    onSteamVideoMutedChange: (Boolean) -> Unit = {},
) {
    val safeMedia = rememberSafeMedia(media, loadingPolicy)
    val pagerState = rememberPagerState(pageCount = { safeMedia.size.coerceAtLeast(1) })
    GameMediaPager(
        media = safeMedia,
        fallbackImageUrl = fallbackImageUrl,
        contentDescription = contentDescription,
        pagerState = pagerState,
        modifier = modifier,
        loadingPolicy = loadingPolicy,
        mediaProvider = mediaProvider,
        steamVideoMuted = steamVideoMuted,
        onSteamVideoMutedChange = onSteamVideoMutedChange,
    )
}

@Composable
internal fun GameMediaPager(
    media: List<GameMediaItem>,
    fallbackImageUrl: String?,
    contentDescription: String,
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    loadingPolicy: GameMediaLoadingPolicy = GameMediaLoadingPolicy.DEFAULT,
    mediaProvider: MetadataProvider = MetadataProvider.STEAM_APPDETAILS,
    steamVideoMuted: Boolean = true,
    onSteamVideoMutedChange: (Boolean) -> Unit = {},
) {
    val safeMedia = rememberSafeMedia(media, loadingPolicy)

    Box(modifier = modifier.testTag("game-media-pager")) {
        if (safeMedia.isEmpty()) {
            MediaFallback(
                fallbackImageUrl = fallbackImageUrl,
                contentDescription = contentDescription,
                loadingPolicy = loadingPolicy,
                mediaProvider = mediaProvider,
            )
        } else {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val item = safeMedia[page]
                when {
                    loadingPolicy == GameMediaLoadingPolicy.STEAM_MEDIA && !item.videoUrl.isNullOrBlank() -> {
                        SteamVideoHero(
                            videoUrl = item.videoUrl,
                            fallbackImageUrl = item.imageUrl ?: fallbackImageUrl,
                            contentDescription = contentDescription,
                            active = page == pagerState.currentPage,
                            muted = steamVideoMuted,
                            provider = mediaProvider,
                            onMutedChange = onSteamVideoMutedChange,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    loadingPolicy == GameMediaLoadingPolicy.STEAM_MEDIA -> SteamMediaImage(
                        imageUrl = item.imageUrl,
                        contentDescription = contentDescription,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        provider = mediaProvider,
                    )
                    !item.videoUrl.isNullOrBlank() -> VideoHero(
                        videoUrl = item.videoUrl,
                        fallbackImageUrl = (item.imageUrl ?: fallbackImageUrl).orEmpty(),
                        contentDescription = contentDescription,
                        active = page == pagerState.currentPage,
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> CoilImage(
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

@Composable
private fun rememberSafeMedia(
    media: List<GameMediaItem>,
    loadingPolicy: GameMediaLoadingPolicy,
): List<GameMediaItem> = remember(media, loadingPolicy) {
    media.filter { item ->
        when (loadingPolicy) {
            GameMediaLoadingPolicy.DEFAULT,
            GameMediaLoadingPolicy.STEAM_MEDIA,
            -> !item.videoUrl.isNullOrBlank() || !item.imageUrl.isNullOrBlank()
        }
    }
}

@Composable
private fun MediaFallback(
    fallbackImageUrl: String?,
    contentDescription: String,
    loadingPolicy: GameMediaLoadingPolicy,
    mediaProvider: MetadataProvider,
) {
    when (loadingPolicy) {
        GameMediaLoadingPolicy.DEFAULT -> VideoHero(
            videoUrl = null,
            fallbackImageUrl = fallbackImageUrl.orEmpty(),
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
        )
        GameMediaLoadingPolicy.STEAM_MEDIA -> SteamMediaImage(
            imageUrl = fallbackImageUrl,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            provider = mediaProvider,
        )
    }
}
