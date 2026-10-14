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

    internal suspend fun grouping(reference: ArtworkReference): EncodedArtwork? = fetchBytes(
        policy.groupingUrl(reference.source, reference.url, reference.steamAppId), reference,
    ) { policy.groupingUrl(reference.source, it, reference.steamAppId) }

    internal data class EncodedArtwork(val bytes: ByteArray, val mimeType: String)

    private suspend fun fetch(initial: HttpUrl?, validate: (String) -> HttpUrl?): ArtworkFingerprint? =
        withTimeoutOrNull(20_000) {
            val encoded = fetchBytes(initial, validate = validate) ?: return@withTimeoutOrNull null
            withContext(Dispatchers.Default) {
                ensureActive()
                val result = try { ArtworkImageDecoder.decode(encoded.bytes, encoded.mimeType) }
                    catch (_: IllegalArgumentException) { null }
                ensureActive()
                result
            }
        }

    private suspend fun fetchBytes(initial: HttpUrl?, reference: ArtworkReference? = null, validate: (String) -> HttpUrl?): EncodedArtwork? {
        val firstUrl = initial ?: return null
        fun record(stage: ArtworkAcquisitionStage, status: Int? = null) {
            reference?.let { recordArtworkAcquisition(it, stage, httpStatus = status) }
        }
        record(ArtworkAcquisitionStage.FETCHING)
        var finished = false
        val encoded = try { withTimeoutOrNull(20_000) {
            var url = firstUrl
            repeat(3) { hop ->
                when (val result = download(url, validate)) {
                    is ImageResponse.Redirect -> {
                        val next = if (hop < 2) validate(result.url.toString()) else null
                        if (next == null) {
                            finished = true
                            record(ArtworkAcquisitionStage.REDIRECT_REJECTED)
                            return@withTimeoutOrNull null
                        }
                        url = next
                    }
                    is ImageResponse.Bytes -> {
                        finished = true
                        record(ArtworkAcquisitionStage.BYTES_READY, 200)
                        return@withTimeoutOrNull EncodedArtwork(result.bytes, result.mimeType)
                    }
                    is ImageResponse.Unavailable -> {
                        finished = true
                        record(result.reason, result.status)
                        return@withTimeoutOrNull null
                    }
                }
            }
            null
        } } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            record(ArtworkAcquisitionStage.TIMEOUT)
            throw error
        } catch (error: kotlinx.coroutines.CancellationException) {
            record(ArtworkAcquisitionStage.CANCELLED)
            throw error
        }
        if (!finished) record(ArtworkAcquisitionStage.TIMEOUT)
        return encoded
    }

    private suspend fun download(url: HttpUrl, validate: (String) -> HttpUrl?): ImageResponse =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(url).get().build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resume(ImageResponse.Unavailable(ArtworkAcquisitionStage.TRANSPORT_UNAVAILABLE))
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
                                return@use ImageResponse.Unavailable()
                            }
                            if (response.code in REDIRECT_CODES) {
                                val redirect = response.header("Location")?.let(effectiveUrl::resolve)
                                    ?: return@use ImageResponse.Unavailable()
                                return@use ImageResponse.Redirect(redirect)
                            }
                            if (response.code != 200) return@use ImageResponse.Unavailable(ArtworkAcquisitionStage.HTTP_UNAVAILABLE, response.code)
                            val body = response.body ?: return@use ImageResponse.Unavailable()
                            val mimeType = body.contentType()?.let { "${it.type}/${it.subtype}" }
                                ?: return@use ImageResponse.Unavailable()
                            if (mimeType !in IMAGE_TYPES || body.contentLength() > ArtworkImageDecoder.MAX_ENCODED_BYTES) {
                                return@use ImageResponse.Unavailable()
                            }
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8_192)
                            val stream = body.byteStream()
                            while (continuation.isActive) {
                                val remaining = ArtworkImageDecoder.MAX_ENCODED_BYTES - output.size()
                                val count = stream.read(buffer, 0, minOf(buffer.size, remaining + 1))
                                if (count == -1) return@use ImageResponse.Bytes(output.toByteArray(), mimeType)
                                if (count > remaining) return@use ImageResponse.Unavailable()
                                output.write(buffer, 0, count)
                            }
                            ImageResponse.Unavailable()
                        } catch (_: IOException) {
                            ImageResponse.Unavailable()
                        }
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }

    private sealed interface ImageResponse {
        data class Redirect(val url: HttpUrl) : ImageResponse
        data class Bytes(val bytes: ByteArray, val mimeType: String) : ImageResponse
        data class Unavailable(val reason: ArtworkAcquisitionStage = ArtworkAcquisitionStage.INVALID_RESPONSE, val status: Int? = null) : ImageResponse
    }

    companion object {
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/webp")
    }
}
