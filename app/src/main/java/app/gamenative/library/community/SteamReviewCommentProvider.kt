package app.gamenative.library.community

import app.gamenative.library.metadata.SteamHttpRetryExecutor
import app.gamenative.utils.Net
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

@Singleton
class SteamReviewCommentProvider internal constructor(
    client: OkHttpClient,
    private val reviewEndpoint: HttpUrl,
    private val commentEndpoint: HttpUrl,
    private val allowedReviewHosts: Set<String>,
    private val allowedCommentHosts: Set<String>,
    private val requireHttps: Boolean,
    private val allowedPorts: Set<Int>,
    private val retryExecutor: SteamHttpRetryExecutor = SteamHttpRetryExecutor(),
) : SteamReviewCommentSource {
    private val client = client.newBuilder()
        .cache(null)
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    @Inject
    constructor() : this(
        Net.http,
        "https://store.steampowered.com/appreviews".toHttpUrl(),
        "https://steamcommunity.com/".toHttpUrl(),
        setOf("store.steampowered.com"), setOf("steamcommunity.com"), true, setOf(443),
    )

    override suspend fun fetch(
        steamAppId: Int,
        query: SteamReviewQuery,
        reviewCursor: String?,
        recommendationId: String,
        start: Int,
    ): SteamReviewCommentPage = withContext(Dispatchers.Default) {
        try {
            if (steamAppId <= 0 || !positiveId(recommendationId) ||
                start !in 0..MAX_START || start % PAGE_SIZE != 0 ||
                reviewCursor != null && (reviewCursor.isBlank() || reviewCursor.length > MAX_CURSOR) ||
                !allowed(reviewEndpoint, allowedReviewHosts) || reviewEndpoint.encodedPath != "/appreviews" ||
                reviewEndpoint.query != null || !allowed(commentEndpoint, allowedCommentHosts) ||
                commentEndpoint.encodedPath != "/" || commentEndpoint.query != null
            ) throw SteamReviewCommentsUnavailable()

            val reviewUrl = reviewEndpoint.newBuilder()
                .addPathSegment(steamAppId.toString())
                .addQueryParameter("json", "1")
                .addQueryParameter("filter", if (query.sort == SteamReviewSort.RECENT) "recent" else "all")
                .addQueryParameter("language", if (query.language == SteamReviewLanguage.ALL) "all" else "english")
                .addQueryParameter("review_type", query.polarity.name.lowercase())
                .addQueryParameter("purchase_type", query.purchaseType.name.lowercase())
                .addQueryParameter("num_per_page", PAGE_SIZE.toString())
                .apply { reviewCursor?.let { addQueryParameter("cursor", it) } }
                .build()
            val root = readJson(reviewUrl, allowedReviewHosts)
            if (root.number("success") != 1) throw SteamReviewCommentsUnavailable()
            val reviews = root["reviews"] as? JsonArray ?: throw SteamReviewCommentsUnavailable()
            if (reviews.size > PAGE_SIZE) throw SteamReviewCommentsUnavailable()
            val selected = reviews.mapNotNull { it as? JsonObject }
                .filter { it.string("recommendationid") == recommendationId }
                .singleOrNull() ?: throw SteamReviewCommentsUnavailable()
            val author = (selected["author"] as? JsonObject)?.string("steamid")
                ?.takeIf { it.length == 17 && it.all { char -> char in '0'..'9' } }
                ?.takeIf { value -> value.toLongOrNull()?.let { it in STEAM_USER_BASE..STEAM_USER_MAX } == true }
                ?: throw SteamReviewCommentsUnavailable()
            val commentUrl = commentEndpoint.newBuilder()
                .addPathSegments("comment/Recommendation/render")
                .addPathSegment(author)
                .addPathSegment(steamAppId.toString())
                .addPathSegment("")
                .addQueryParameter("start", start.toString())
                .addQueryParameter("count", PAGE_SIZE.toString())
                .build()
            parseComments(readJson(commentUrl, allowedCommentHosts), start)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw SteamReviewCommentsUnavailable()
        }
    }

    private suspend fun readJson(url: HttpUrl, hosts: Set<String>): JsonObject {
        if (!allowed(url, hosts)) throw SteamReviewCommentsUnavailable()
        val request = Request.Builder().url(url)
            .header("Cache-Control", "no-store")
            .header("User-Agent", "GameNative/1.0 python-requests-compatible")
            .get().build()
        val response = retryExecutor.execute {
            client.newCall(request).awaitBufferedCommunityResponse(
                maxBodyBytes = MAX_BODY,
                canReadBody = { it.request.url == url && it.isSuccessful && it.hasJsonContentType() },
                bodyLimitFailure = { SteamReviewCommentsUnavailable() },
            )
        }
        return response.use {
            if (it.request.url != url || !it.isSuccessful || !it.hasJsonContentType()) {
                throw SteamReviewCommentsUnavailable()
            }
            JSON.parseToJsonElement(it.body.string()) as? JsonObject ?: throw SteamReviewCommentsUnavailable()
        }
    }

    private fun parseComments(root: JsonObject, start: Int): SteamReviewCommentPage {
        val success = root["success"] as? JsonPrimitive
        if (success == null || success.isString || success.booleanOrNull != true) {
            throw SteamReviewCommentsUnavailable()
        }
        val total = root.number("total_count")?.takeIf { it >= 0 } ?: throw SteamReviewCommentsUnavailable()
        val html = root.string("comments_html") ?: throw SteamReviewCommentsUnavailable()
        val document = Jsoup.parseBodyFragment(html)
        val rows = document.select(".commentthread_comment")
        if (rows.size > PAGE_SIZE || rows.isEmpty() && start < total || rows.isNotEmpty() && total == 0) {
            throw SteamReviewCommentsUnavailable()
        }
        val comments = rows.map { row ->
            val id = row.attr("data-commentid").ifEmpty { row.id().removePrefix("comment_") }
            if (!positiveId(id)) throw SteamReviewCommentsUnavailable()
            val text = row.selectFirst(".commentthread_comment_text")?.clone()
                ?: throw SteamReviewCommentsUnavailable()
            text.select("script, style, iframe, img, video, audio, object, embed, .commentthread_author_link, [data-miniprofile]").remove()
            val plain = text.text().replace(WHITESPACE, " ").trim().take(MAX_TEXT)
            if (plain.isEmpty()) throw SteamReviewCommentsUnavailable()
            SteamReviewComment(id, plain)
        }
        if (comments.distinctBy { it.commentId }.size != comments.size) throw SteamReviewCommentsUnavailable()
        return SteamReviewCommentPage(
            comments,
            (start + PAGE_SIZE).takeIf { it < total && it <= MAX_START },
        )
    }

    private fun allowed(url: HttpUrl, hosts: Set<String>) =
        (!requireHttps || url.isHttps) && url.host in hosts && url.port in allowedPorts &&
            url.username.isEmpty() && url.password.isEmpty() && url.fragment == null

    private fun Response.hasJsonContentType(): Boolean = body.contentType()?.let {
        it.type == "application" && (it.subtype == "json" || it.subtype.endsWith("+json"))
    } == true

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.number(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

    private fun positiveId(value: String) = value.length in 1..20 && value.all { it in '0'..'9' } &&
        value.toULongOrNull()?.let { it > 0uL && it.toString() == value } == true

    private companion object {
        const val PAGE_SIZE = 20
        const val MAX_START = 80
        const val MAX_CURSOR = 512
        const val MAX_BODY = 1024L * 1024L
        const val MAX_TEXT = 32 * 1024
        const val STEAM_USER_BASE = 76561197960265728L
        const val STEAM_USER_MAX = STEAM_USER_BASE + 4294967295L
        val JSON = Json { ignoreUnknownKeys = true }
        val WHITESPACE = Regex("\\s+")
    }
}
