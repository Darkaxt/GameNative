package app.gamenative.library.canonical.artwork

import app.gamenative.data.GameSource
import app.gamenative.utils.Net
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

@Singleton
class ArtworkImageProvider internal constructor(client: OkHttpClient, private val policy: ArtworkUrlPolicy) : ArtworkFingerprintSource {
    private val client = client.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    @Inject
    constructor() : this(Net.http, ArtworkUrlPolicy.Default)

    override suspend fun original(source: GameSource, raw: String, steamAppId: Int?): ArtworkFingerprint? =
        fetch(policy.originalUrl(source, raw, steamAppId)) { policy.originalUrl(source, it, steamAppId) }

    override suspend fun candidate(steamAppId: Int, raw: String): ArtworkFingerprint? =
        fetch(policy.candidateUrl(steamAppId, raw)) { policy.candidateUrl(steamAppId, it) }

    private suspend fun fetch(initial: HttpUrl?, validate: (String) -> HttpUrl?): ArtworkFingerprint? {
        val firstUrl = initial ?: return null
        return withTimeoutOrNull(20_000) {
            var url = firstUrl
            repeat(3) { hop ->
                when (val result = download(url, validate)) {
                    is ImageResponse.Redirect -> {
                        if (hop == 2) return@withTimeoutOrNull null
                        url = validate(result.url.toString()) ?: return@withTimeoutOrNull null
                    }
                    is ImageResponse.Bytes -> return@withTimeoutOrNull withContext(Dispatchers.Default) {
                        ensureActive()
                        val fingerprint = try {
                            ArtworkImageDecoder.decode(result.bytes, result.mimeType)
                        } catch (_: IllegalArgumentException) {
                            null
                        }
                        ensureActive()
                        fingerprint
                    }
                    ImageResponse.Unavailable -> return@withTimeoutOrNull null
                }
            }
            null
        }
    }

    private suspend fun download(url: HttpUrl, validate: (String) -> HttpUrl?): ImageResponse =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(url).get().build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resume(ImageResponse.Unavailable)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = response.use {
                        try {
                            val effectiveUrl = response.networkResponse?.request?.url
                                ?: response.cacheResponse?.request?.url ?: response.request.url
                            if (!continuation.isActive || listOfNotNull(
                                    response.request.url, response.networkResponse?.request?.url,
                                    response.cacheResponse?.request?.url,
                                ).any { validate(it.toString()) == null }
                            ) {
                                return@use ImageResponse.Unavailable
                            }
                            if (response.code in REDIRECT_CODES) {
                                val redirect = response.header("Location")?.let(effectiveUrl::resolve)
                                    ?: return@use ImageResponse.Unavailable
                                return@use ImageResponse.Redirect(redirect)
                            }
                            if (response.code != 200) return@use ImageResponse.Unavailable
                            val body = response.body ?: return@use ImageResponse.Unavailable
                            val mimeType = body.contentType()?.let { "${it.type}/${it.subtype}" }
                                ?: return@use ImageResponse.Unavailable
                            if (mimeType !in IMAGE_TYPES || body.contentLength() > ArtworkImageDecoder.MAX_ENCODED_BYTES) {
                                return@use ImageResponse.Unavailable
                            }
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8_192)
                            val stream = body.byteStream()
                            while (continuation.isActive) {
                                val remaining = ArtworkImageDecoder.MAX_ENCODED_BYTES - output.size()
                                val count = stream.read(buffer, 0, minOf(buffer.size, remaining + 1))
                                if (count == -1) return@use ImageResponse.Bytes(output.toByteArray(), mimeType)
                                if (count > remaining) return@use ImageResponse.Unavailable
                                output.write(buffer, 0, count)
                            }
                            ImageResponse.Unavailable
                        } catch (_: IOException) {
                            ImageResponse.Unavailable
                        }
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }

    private sealed interface ImageResponse {
        data class Redirect(val url: HttpUrl) : ImageResponse
        data class Bytes(val bytes: ByteArray, val mimeType: String) : ImageResponse
        data object Unavailable : ImageResponse
    }

    companion object {
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/webp")
    }
}
