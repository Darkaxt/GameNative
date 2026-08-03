package app.gamenative.ui.screen.library.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

@Composable
internal fun SteamMediaGallery(
    media: List<GameMediaItem>,
    fallbackImageUrl: String?,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val safeMedia = remember(media) {
        media.filter { !it.videoUrl.isNullOrBlank() || !it.imageUrl.isNullOrBlank() }
    }
    val pagerState = rememberPagerState(pageCount = { safeMedia.size.coerceAtLeast(1) })
    val scope = rememberCoroutineScope()
    var fullscreenPage by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = modifier.testTag("steam-media-gallery"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black, RoundedCornerShape(12.dp)),
        ) {
            GameMediaPager(
                media = safeMedia,
                fallbackImageUrl = fallbackImageUrl,
                contentDescription = contentDescription,
                pagerState = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("steam-media-gallery-viewport"),
                loadingPolicy = GameMediaLoadingPolicy.STEAM_MEDIA,
            )
            val currentItem = safeMedia.getOrNull(pagerState.currentPage)
            if (currentItem != null && currentItem.videoUrl.isNullOrBlank()) {
                IconButton(
                    onClick = { fullscreenPage = pagerState.currentPage },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(8.dp))
                        .testTag("steam-media-fullscreen"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Fullscreen,
                        contentDescription = "Open screenshot fullscreen",
                        tint = Color.White,
                    )
                }
            }
        }

        if (safeMedia.size > 1) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .testTag("steam-media-thumbnails"),
                contentPadding = PaddingValues(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(safeMedia) { index, item ->
                    val selected = index == pagerState.currentPage
                    Surface(
                        modifier = Modifier
                            .size(width = 120.dp, height = 68.dp)
                            .clickable {
                                scope.launch { pagerState.animateScrollToPage(index) }
                            }
                            .testTag("steam-media-thumbnail:$index"),
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Black,
                        border = BorderStroke(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        ),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            SteamMediaImage(
                                imageUrl = item.imageUrl ?: fallbackImageUrl,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                            if (!item.videoUrl.isNullOrBlank()) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = "Play trailer",
                                    tint = Color.White,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(Color.Black.copy(alpha = 0.58f), RoundedCornerShape(16.dp)),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fullscreenPage?.let { initialPage ->
        SteamMediaFullscreenDialog(
            media = safeMedia,
            initialPage = initialPage,
            contentDescription = contentDescription,
            onDismiss = { fullscreenPage = null },
        )
    }
}

@Composable
private fun SteamMediaFullscreenDialog(
    media: List<GameMediaItem>,
    initialPage: Int,
    contentDescription: String,
    onDismiss: () -> Unit,
) {
    var page by remember(initialPage) { mutableIntStateOf(initialPage) }
    var scale by remember(page) { mutableFloatStateOf(1f) }
    var translation by remember(page) { mutableStateOf(Offset.Zero) }
    val item = media[page]

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag("steam-media-fullscreen-dialog"),
            contentAlignment = Alignment.Center,
        ) {
            SteamMediaImage(
                imageUrl = item.imageUrl,
                contentDescription = contentDescription,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = translation.x,
                        translationY = translation.y,
                    )
                    .pointerInput(page) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            translation = if (scale == 1f) Offset.Zero else translation + pan
                        }
                    },
                contentScale = ContentScale.Fit,
            )

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(8.dp)),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close fullscreen", tint = Color.White)
            }
            if (page > 0) {
                IconButton(
                    onClick = { page -= 1 },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(8.dp)),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.NavigateBefore,
                        contentDescription = "Previous screenshot",
                        tint = Color.White,
                    )
                }
            }
            if (page < media.lastIndex) {
                IconButton(
                    onClick = { page += 1 },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(8.dp)),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.NavigateNext,
                        contentDescription = "Next screenshot",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}
