package app.gamenative.library.metadata

import app.gamenative.utils.Net
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal class SteamMediaException : IOException("Steam media unavailable")

internal class SteamMediaDataSource(
    baseClient: OkHttpClient,
    private val urlPolicy: MediaUrlPolicy,
    private val maxRedirects: Int = DEFAULT_MAX_REDIRECTS,
) {
    private val client = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    init {
        require(maxRedirects >= 0) { "Redirect bound must not be negative" }
    }

    constructor() : this(
        baseClient = Net.http,
        urlPolicy = SteamUrlPolicy(),
    )

    internal constructor(provider: MetadataProvider) : this(
        baseClient = Net.http,
        urlPolicy = provider.mediaUrlPolicy(),
    )

    suspend fun open(rawUrl: String): Response = try {
        openValidated(rawUrl)
    } catch (error: CancellationException) {
        throw error
    } catch (error: SteamMediaException) {
        throw error
    } catch (_: Exception) {
        throw SteamMediaException()
    }

    private suspend fun openValidated(rawUrl: String): Response {
        var currentUrl = rawUrl.toHttpUrlOrNull() ?: throw SteamMediaException()
        val visited = mutableSetOf(currentUrl)
        var redirectCount = 0

        while (true) {
            if (!urlPolicy.isAllowedMediaUrl(currentUrl)) throw SteamMediaException()
            val request = Request.Builder().url(currentUrl).get().build()
            val response = client.newCall(request).awaitSteamMediaResponse()
            val responseUrl = response.request.url
            if (responseUrl != currentUrl || !urlPolicy.isAllowedMediaUrl(responseUrl)) {
                response.close()
                throw SteamMediaException()
            }

            if (response.code in REDIRECT_CODES) {
                val nextUrl = response.header("Location")?.toHttpUrlOrNull()
                response.close()
                if (
                    nextUrl == null ||
                    !urlPolicy.isAllowedMediaUrl(nextUrl) ||
                    redirectCount >= maxRedirects ||
                    !visited.add(nextUrl)
                ) {
                    throw SteamMediaException()
                }
                redirectCount += 1
                currentUrl = nextUrl
                continue
            }

            if (!response.isSuccessful) {
                response.close()
                throw SteamMediaException()
            }
            return response
        }
    }

    private companion object {
        const val DEFAULT_MAX_REDIRECTS = 3
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

private suspend fun Call.awaitSteamMediaResponse(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(error))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.success(response))
                    } else {
                        response.close()
                    }
                }
            },
        )
    }
