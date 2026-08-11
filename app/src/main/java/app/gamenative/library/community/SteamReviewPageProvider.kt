package app.gamenative.library.community

import app.gamenative.library.metadata.SteamHttpRetryExecutor
import app.gamenative.library.metadata.SteamRateLimitExhaustedException
import app.gamenative.utils.Net
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

@Singleton
class SteamReviewPageProvider internal constructor(
    private val client: OkHttpClient,
    private val endpoint: HttpUrl,
    private val allowedHosts: Set<String>,
    private val requireHttps: Boolean,
    private val allowedPorts: Set<Int>,
    private val retryExecutor: SteamHttpRetryExecutor = SteamHttpRetryExecutor(),
) : SteamReviewPageSource {
    @Inject
    constructor() : this(
        client = Net.http.newBuilder()
            .cache(null)
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(20, TimeUnit.SECONDS)
            .build(),
        endpoint = DEFAULT_ENDPOINT.toHttpUrl(),
        allowedHosts = setOf(STEAM_STORE_HOST),
        requireHttps = true,
        allowedPorts = setOf(443),
    )

    override suspend fun fetch(
        steamAppId: Int,
        query: SteamReviewQuery,
        cursor: String?,
    ): SteamReviewPage {
        if (
            steamAppId <= 0 ||
            !isAllowedEndpoint(endpoint) ||
            cursor != null && (cursor.isBlank() || cursor.length > MAX_CURSOR_LENGTH)
        ) {
            throw SteamReviewsUnavailable()
        }
        val requestUrl = endpoint.newBuilder()
            .addPathSegment(steamAppId.toString())
            .addQueryParameter("json", "1")
            .addQueryParameter("filter", query.sort.parameter)
            .addQueryParameter("language", query.language.parameter)
            .addQueryParameter("review_type", query.polarity.parameter)
            .addQueryParameter("purchase_type", query.purchaseType.parameter)
            .addQueryParameter("num_per_page", MAX_REVIEWS.toString())
            .apply { cursor?.let { addQueryParameter("cursor", it) } }
            .build()
        if (!isAllowedNetworkUrl(requestUrl)) throw SteamReviewsUnavailable()
        val request = Request.Builder()
            .url(requestUrl)
            .header("Cache-Control", "no-store")
            .get()
            .build()
        val response = try {
            retryExecutor.execute { client.newCall(request).awaitSteamReviewResponse() }
        } catch (_: SteamRateLimitExhaustedException) {
            throw SteamReviewsUnavailable()
        }
        response.use {
            if (
                !it.isSuccessful ||
                !it.hasJsonContentType() ||
                !isAllowedNetworkUrl(it.request.url) ||
                it.code in REDIRECT_CODES
            ) {
                throw SteamReviewsUnavailable()
            }
            return parse(it.readBoundedBody())
        }
    }

    private fun parse(body: String): SteamReviewPage = try {
        val root = JSON.parseToJsonElement(body).jsonObject
        if (root["success"]?.jsonPrimitive?.intOrNull != 1) throw SteamReviewsUnavailable()
        val reviews = root["reviews"]?.jsonArray.orEmpty()
            .take(MAX_REVIEWS)
            .mapNotNull { parseReview(it.jsonObject) }
        val nextCursor = root.string("cursor")
            ?.takeIf { it.isNotBlank() && it.length <= MAX_CURSOR_LENGTH }
        SteamReviewPage(reviews = reviews, nextCursor = nextCursor)
    } catch (_: SerializationException) {
        throw SteamReviewsUnavailable()
    } catch (_: IllegalArgumentException) {
        throw SteamReviewsUnavailable()
    }

    private fun parseReview(value: JsonObject): SteamReviewCard? {
        val recommendationId = value.string("recommendationid")
            ?.takeIf(String::isNotBlank)
            ?.takeIf { it.length <= MAX_RECOMMENDATION_ID_LENGTH }
            ?: return null
        val text = value.string("review")
            ?.takeIf(String::isNotBlank)
            ?.take(MAX_REVIEW_TEXT_LENGTH)
            ?: return null
        val author = value["author"] as? JsonObject
        return SteamReviewCard(
            recommended = value.boolean("voted_up"),
            text = text,
            playtimeMinutes = author?.int("playtime_forever")?.takeIf { it >= 0 },
            helpfulVotes = value.int("votes_up").coerceAtLeast(0),
            funnyVotes = value.int("votes_funny").coerceAtLeast(0),
            commentCount = value.int("comment_count").coerceAtLeast(0),
            postedAtEpochSeconds = value.long("timestamp_created").coerceAtLeast(0),
            updatedAtEpochSeconds = value.long("timestamp_updated").coerceAtLeast(0),
            receivedForFree = value.boolean("received_for_free"),
            earlyAccess = value.boolean("written_during_early_access"),
            developerResponse = value.string("developer_response")
                ?.takeIf(String::isNotBlank)
                ?.take(MAX_DEVELOPER_RESPONSE_LENGTH),
            recommendationId = recommendationId,
        )
    }

    private fun Response.hasJsonContentType(): Boolean {
        val contentType = body.contentType() ?: return false
        return contentType.type == "application" &&
            (contentType.subtype == "json" || contentType.subtype.endsWith("+json"))
    }

    private fun Response.readBoundedBody(): String {
        if (body.contentLength() > MAX_RESPONSE_BYTES) throw SteamReviewsUnavailable()
        val source = body.source()
        source.request(MAX_RESPONSE_BYTES + 1L)
        if (source.buffer.size > MAX_RESPONSE_BYTES) throw SteamReviewsUnavailable()
        return source.readUtf8()
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

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

    private fun JsonObject.int(name: String): Int = this[name]?.jsonPrimitive?.intOrNull ?: 0
    private fun JsonObject.long(name: String): Long = this[name]?.jsonPrimitive?.longOrNull ?: 0L
    private fun JsonObject.boolean(name: String): Boolean =
        this[name]?.jsonPrimitive?.booleanOrNull ?: false

    private val SteamReviewSort.parameter: String
        get() = when (this) {
            SteamReviewSort.HELPFUL -> "all"
            SteamReviewSort.RECENT -> "recent"
        }

    private val SteamReviewPolarity.parameter: String
        get() = name.lowercase()

    private val SteamReviewLanguage.parameter: String
        get() = when (this) {
            SteamReviewLanguage.APP_LANGUAGE -> "english"
            SteamReviewLanguage.ALL -> "all"
        }

    private val SteamReviewPurchaseType.parameter: String
        get() = name.lowercase()

    private companion object {
        const val DEFAULT_ENDPOINT = "https://store.steampowered.com/appreviews"
        const val STEAM_STORE_HOST = "store.steampowered.com"
        const val APP_REVIEWS_PATH = "/appreviews"
        const val MAX_REVIEWS = 20
        const val MAX_CURSOR_LENGTH = 512
        const val MAX_RECOMMENDATION_ID_LENGTH = 128
        const val MAX_REVIEW_TEXT_LENGTH = 16 * 1024
        const val MAX_DEVELOPER_RESPONSE_LENGTH = 8 * 1024
        const val MAX_RESPONSE_BYTES = 1024L * 1024L
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

private class SteamReviewsUnavailable : IOException("Steam reviews unavailable")

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
