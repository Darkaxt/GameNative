package app.gamenative.ui.screen.library.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import app.gamenative.library.metadata.MetadataProvider
import app.gamenative.library.metadata.SteamMediaDataSource
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage

@Composable
internal fun SteamMediaImage(
    imageUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    provider: MetadataProvider = MetadataProvider.STEAM_APPDETAILS,
) {
    val safeUrl = imageUrl?.takeIf(String::isNotBlank) ?: return
    val imageLoader = rememberSteamMediaImageLoader(provider)
    val model = remember(safeUrl) { SteamMediaImageModel(safeUrl) }
    CoilImage(
        imageModel = { model },
        imageLoader = { imageLoader },
        imageOptions = ImageOptions(
            contentDescription = contentDescription,
            contentScale = contentScale,
        ),
        modifier = modifier.testTag("steam-media-image"),
    )
}

@Composable
private fun rememberSteamMediaImageLoader(
    provider: MetadataProvider,
): ImageLoader {
    val context = LocalContext.current.applicationContext
    val imageLoader = remember(context, provider) {
        val dataSource = SteamMediaDataSource(provider = provider)
        ImageLoader.Builder(context)
            .components {
                add(SteamMediaFetcher.Factory(dataSource))
            }
            .build()
    }
    DisposableEffect(imageLoader) {
        onDispose { imageLoader.shutdown() }
    }
    return imageLoader
}

private class SteamMediaImageModel(
    val rawUrl: String,
) {
    override fun toString(): String = "SteamMediaImage"
}

private class SteamMediaFetcher(
    private val model: SteamMediaImageModel,
    private val options: Options,
    private val dataSource: SteamMediaDataSource,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val response = dataSource.open(model.rawUrl)
        return try {
            SourceResult(
                source = ImageSource(response.body.source(), options.context),
                mimeType = response.body.contentType()?.toString(),
                dataSource = DataSource.NETWORK,
            )
        } catch (error: Exception) {
            response.close()
            throw error
        }
    }

    class Factory(
        private val dataSource: SteamMediaDataSource,
    ) : Fetcher.Factory<SteamMediaImageModel> {
        override fun create(
            data: SteamMediaImageModel,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = SteamMediaFetcher(data, options, dataSource)
    }
}
