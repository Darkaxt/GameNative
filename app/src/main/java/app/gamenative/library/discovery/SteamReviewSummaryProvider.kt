package app.gamenative.library.discovery

import app.gamenative.utils.Net
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

fun interface SteamReviewSummarySource {
    suspend fun fetch(steamAppId: Int): SteamReviewSummary
}

data class SteamReviewSummary(val totalReviews: Int)

@Singleton
class SteamReviewSummaryProvider internal constructor(
    private val client: OkHttpClient,
    private val endpoint: HttpUrl,
    private val allowedHosts: Set<String>,
    private val requireHttps: Boolean,
    private val allowedPorts: Set<Int>,
) : SteamReviewSummarySource {
    @Inject
    constructor() : this(
        client = Net.http.newBuilder().followRedirects(false).followSslRedirects(false).build(),
        endpoint = DEFAULT_ENDPOINT.toHttpUrl(),
        allowedHosts = setOf(STEAM_STORE_HOST),
        requireHttps = true,
        allowedPorts = setOf(443),
    )

    override suspend fun fetch(steamAppId: Int): SteamReviewSummary {
        if (steamAppId <= 0 || !isAllowedEndpoint(endpoint)) throw SteamReviewSummaryUnavailable()
        val requestUrl = endpoint.newBuilder()
            .addPathSegment(steamAppId.toString())
            .addQueryParameter("json", "1")
            .addQueryParameter("filter", "summary")
            .addQueryParameter("language", "all")
            .addQueryParameter("purchase_type", "all")
            .addQueryParameter("num_per_page", "0")
            .build()
        val body = executeValidated(Request.Builder().url(requestUrl).get().build())
        return parse(body)
    }

    private suspend fun executeValidated(initialRequest: Request): String {
        var request = initialRequest
        repeat(MAX_NETWORK_HOPS) {
            if (!isAllowedNetworkUrl(request.url)) throw SteamReviewSummaryUnavailable()
            val response = client.newCall(request).awaitSteamReviewResponse()
            if (!isAllowedNetworkUrl(response.request.url)) {
                response.close()
                throw SteamReviewSummaryUnavailable()
            }
            if (response.code in REDIRECT_CODES) {
                val next = response.header("Location")?.let(response.request.url::resolve)
                response.close()
                if (next == null || !isAllowedNetworkUrl(next)) throw SteamReviewSummaryUnavailable()
                request = Request.Builder().url(next).get().build()
            } else {
                response.use { finalResponse ->
                    if (!finalResponse.isSuccessful) throw SteamReviewSummaryUnavailable()
                    return finalResponse.readBoundedBody()
                }
            }
        }
        throw SteamReviewSummaryUnavailable()
    }

    private fun Response.readBoundedBody(): String {
        val responseBody = body
        if (responseBody.contentLength() > MAX_RESPONSE_BYTES) throw SteamReviewSummaryUnavailable()
        val source = responseBody.source()
        source.request(MAX_RESPONSE_BYTES + 1L)
        if (source.buffer.size > MAX_RESPONSE_BYTES) throw SteamReviewSummaryUnavailable()
        return source.readUtf8()
    }

    private fun parse(body: String): SteamReviewSummary {
        val response = try {
            JSON.decodeFromString<SteamReviewSummaryResponse>(body)
        } catch (_: SerializationException) {
            throw SteamReviewSummaryUnavailable()
        }
        val total = response.querySummary
            ?.totalReviews
            ?.takeUnless(JsonPrimitive::isString)
            ?.longOrNull
            ?.takeIf { it in 0..Int.MAX_VALUE.toLong() }
            ?.toInt()
            ?: throw SteamReviewSummaryUnavailable()
        return SteamReviewSummary(total)
    }

    private fun isAllowedEndpoint(url: HttpUrl): Boolean =
        isAllowedNetworkUrl(url) && url.encodedPath == APP_REVIEWS_PATH && url.query == null

    private fun isAllowedNetworkUrl(url: HttpUrl): Boolean =
        (!requireHttps || url.isHttps) &&
            url.host in allowedHosts &&
            url.port in allowedPorts &&
            url.username.isEmpty() &&
            url.password.isEmpty() &&
            url.fragment == null

    private companion object {
        const val DEFAULT_ENDPOINT = "https://store.steampowered.com/appreviews"
        const val STEAM_STORE_HOST = "store.steampowered.com"
        const val APP_REVIEWS_PATH = "/appreviews"
        const val MAX_NETWORK_HOPS = 4
        const val MAX_RESPONSE_BYTES = 64L * 1024L
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class SteamReviewSummaryResponse(
    @SerialName("query_summary") val querySummary: SteamReviewQuerySummary? = null,
)

@Serializable
private data class SteamReviewQuerySummary(
    @SerialName("total_reviews") val totalReviews: JsonPrimitive? = null,
)

private class SteamReviewSummaryUnavailable : IOException("Steam review summary unavailable")

private suspend fun Call.awaitSteamReviewResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
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
