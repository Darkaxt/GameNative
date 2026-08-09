package app.gamenative.ui.screen.library.components

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.gamenative.R
import app.gamenative.library.metadata.SteamMediaRedirectInterceptor
import app.gamenative.utils.Net

@OptIn(UnstableApi::class)
@Composable
internal fun SteamVideoHero(
    videoUrl: String,
    fallbackImageUrl: String?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    muted: Boolean = true,
    onMutedChange: (Boolean) -> Unit = {},
) {
    if (!active) {
        SteamMediaImage(
            imageUrl = fallbackImageUrl,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
        return
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showFallback by remember(videoUrl) { mutableStateOf(true) }
    val mediaClient = remember {
        Net.http.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(SteamMediaRedirectInterceptor())
            .build()
    }
    val mediaSourceFactory = remember(mediaClient) {
        DefaultMediaSourceFactory(OkHttpDataSource.Factory(mediaClient))
    }
    val exoPlayer = remember(videoUrl, mediaSourceFactory) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(videoUrl))
                repeatMode = Player.REPEAT_MODE_ALL
                volume = trailerVolume(muted)
                playWhenReady = true
                prepare()
            }
    }

    LaunchedEffect(exoPlayer, muted) {
        exoPlayer.volume = trailerVolume(muted)
    }

    DisposableEffect(exoPlayer, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                showFallback = false
            }

            override fun onPlayerError(error: PlaybackException) {
                showFallback = true
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> exoPlayer.pause()
                Lifecycle.Event.ON_RESUME -> exoPlayer.play()
                else -> Unit
            }
        }
        exoPlayer.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            exoPlayer.removeListener(listener)
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .testTag("steam-media-video"),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { playerContext ->
                PlayerView(playerContext).apply {
                    player = exoPlayer
                    useController = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (showFallback) {
            SteamMediaImage(
                imageUrl = fallbackImageUrl,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
        IconButton(
            onClick = {
                val nextMuted = !muted
                exoPlayer.volume = trailerVolume(nextMuted)
                onMutedChange(nextMuted)
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(8.dp))
                .testTag("steam-media-audio-toggle"),
        ) {
            Icon(
                imageVector = if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                contentDescription = stringResource(
                    if (muted) R.string.canonical_trailer_unmute else R.string.canonical_trailer_mute,
                ),
                tint = Color.White,
            )
        }
    }
}

internal fun trailerVolume(muted: Boolean): Float = if (muted) 0f else 1f
