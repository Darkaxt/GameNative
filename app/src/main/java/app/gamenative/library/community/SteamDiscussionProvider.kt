package app.gamenative.library.community

import app.gamenative.library.metadata.SteamHttpRetryExecutor
import app.gamenative.library.metadata.SteamRateLimitExhaustedException
import app.gamenative.utils.Net
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Singleton
class SteamDiscussionProvider internal constructor(
    private val client: OkHttpClient,
    private val endpoint: HttpUrl,
    private val allowedHosts: Set<String>,
    private val requireHttps: Boolean,
    private val allowedPorts: Set<Int>,
    private val retryExecutor: SteamHttpRetryExecutor = SteamHttpRetryExecutor(),
) : SteamDiscussionSource {
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
        allowedHosts = setOf(STEAM_COMMUNITY_HOST),
        requireHttps = true,
        allowedPorts = setOf(443),
    )

    override suspend fun fetchListing(
        steamAppId: Int,
        route: String?,
    ): SteamDiscussionListing {
        val initialRoute = route ?: "/app/$steamAppId/discussions/"
        val validatedRoute = validateRoute(steamAppId, initialRoute, RouteKind.LISTING)
            ?: throw SteamDiscussionsUnavailable()
        return parseListing(execute(steamAppId, validatedRoute), steamAppId)
    }

    override suspend fun fetchThread(
        steamAppId: Int,
        route: String,
    ): SteamDiscussionThread {
        val validatedRoute = validateRoute(steamAppId, route, RouteKind.THREAD)
            ?: throw SteamDiscussionsUnavailable()
        return parseThread(execute(steamAppId, validatedRoute), steamAppId, validatedRoute)
    }

    private suspend fun execute(steamAppId: Int, route: String): String {
        if (steamAppId <= 0 || !isAllowedEndpoint(endpoint)) throw SteamDiscussionsUnavailable()
        var request = requestFor(endpoint.resolve(route) ?: throw SteamDiscussionsUnavailable())
        repeat(MAX_NETWORK_HOPS) {
            if (!isAllowedNetworkUrl(request.url) || validateUrlAppId(request.url, steamAppId) == null) {
                throw SteamDiscussionsUnavailable()
            }
            val response = try {
                retryExecutor.execute { client.newCall(request).awaitDiscussionResponse() }
            } catch (_: SteamRateLimitExhaustedException) {
                throw SteamDiscussionsUnavailable()
            }
            if (!isAllowedNetworkUrl(response.request.url)) {
                response.close()
                throw SteamDiscussionsUnavailable()
            }
            if (response.code in REDIRECT_CODES) {
                val next = response.header("Location")?.let(response.request.url::resolve)
                response.close()
                if (
                    next == null ||
                    !isAllowedNetworkUrl(next) ||
                    validateUrlAppId(next, steamAppId) == null
                ) {
                    throw SteamDiscussionsUnavailable()
                }
                request = requestFor(next)
            } else {
                response.use { finalResponse ->
                    if (!finalResponse.isSuccessful || !finalResponse.hasHtmlContentType()) {
                        throw SteamDiscussionsUnavailable()
                    }
                    return finalResponse.readBoundedBody()
                }
            }
        }
        throw SteamDiscussionsUnavailable()
    }

    private fun parseListing(body: String, steamAppId: Int): SteamDiscussionListing {
        val document = try {
            Jsoup.parse(body, endpoint.toString())
        } catch (_: IllegalArgumentException) {
            throw SteamDiscussionsUnavailable()
        }
        ensureSupportedRepresentation(document)
        val topics = document.select(".forum_topic").take(MAX_ITEMS)
        val explicitEmpty = document.selectFirst(
            ".forum_no_topics, .forum_topic_none, [data-forum-empty=true]",
        )
        if (topics.isEmpty() && explicitEmpty == null) throw SteamDiscussionsUnavailable()
        val threads = topics
            .asSequence()
            .mapNotNull { topic ->
                val title = safeText(topic.selectFirst(".forum_topic_name"))
                    ?.take(MAX_TITLE_LENGTH)
                    ?: return@mapNotNull null
                val href = topic.selectFirst("a.forum_topic_overlay")?.attr("href")
                    ?: return@mapNotNull null
                val route = validateRoute(steamAppId, href, RouteKind.THREAD)
                    ?: return@mapNotNull null
                SteamDiscussionSummary(
                    title = title,
                    replyCount = parseCount(safeText(topic.selectFirst(".forum_topic_reply_count"))),
                    activityLabel = safeText(topic.selectFirst(".forum_topic_lastpost"))
                        ?.take(MAX_ACTIVITY_LENGTH),
                    route = route,
                    viewCount = parseCount(safeText(topic.selectFirst(".forum_topic_view_count"))),
                )
            }
            .take(MAX_ITEMS)
            .toList()
        if (topics.isNotEmpty() && threads.isEmpty()) throw SteamDiscussionsUnavailable()
        return SteamDiscussionListing(
            threads = threads,
            nextRoute = nextRoute(document.select("a[rel=next], a.pagebtn"), steamAppId, RouteKind.LISTING),
        )
    }

    private fun parseThread(
        body: String,
        steamAppId: Int,
        route: String,
    ): SteamDiscussionThread {
        val document = try {
            Jsoup.parse(body, endpoint.toString())
        } catch (_: IllegalArgumentException) {
            throw SteamDiscussionsUnavailable()
        }
        ensureSupportedRepresentation(document)
        val postContainers = document.select(".forum_op, .forum_post, .commentthread_comment")
        val explicitEmpty = document.selectFirst(
            ".forum_thread_empty, .forum_posts_empty, [data-forum-thread-empty=true]",
        )
        if (postContainers.isEmpty() && explicitEmpty == null) throw SteamDiscussionsUnavailable()
        val title = safeText(document.selectFirst(".topic, .forum_topic_name, h1"))
            ?.take(MAX_TITLE_LENGTH)
            ?: "Steam discussion"
        val posts = document.select(
            ".forum_op > .content, .forum_post_text, .forum_post_body, .commentthread_comment_text",
        )
            .asSequence()
            .mapNotNull(::safeText)
            .map { text -> SteamDiscussionPost(text.take(MAX_POST_LENGTH)) }
            .take(MAX_ITEMS)
            .toList()
        if (postContainers.isNotEmpty() && posts.isEmpty()) throw SteamDiscussionsUnavailable()
        return SteamDiscussionThread(
            title = title,
            posts = posts,
            route = route.substringBefore('?'),
            nextRoute = nextRoute(document.select("a[rel=next], a.pagebtn"), steamAppId, RouteKind.THREAD),
        )
    }

    private fun ensureSupportedRepresentation(document: Document) {
        if (
            document.selectFirst(
                ".error_ctn, .community_home_error, #error_box, [data-steam-error]",
            ) != null
        ) {
            throw SteamDiscussionsUnavailable()
        }
        val clientRenderedShell = document.selectFirst("#application_root") != null &&
            document.selectFirst("script[src*='/javascript/applications/community/main.js']") != null
        val serverForumContent = document.selectFirst(
            ".forum_topic, .forum_op, .forum_post, .commentthread_comment",
        ) != null
        if (clientRenderedShell && !serverForumContent) throw SteamDiscussionsUnavailable()
    }

    private fun nextRoute(
        links: Iterable<Element>,
        steamAppId: Int,
        kind: RouteKind,
    ): String? = links.firstNotNullOfOrNull { link ->
        val isNext = link.attr("rel").equals("next", ignoreCase = true) ||
            link.text().trim().equals("next", ignoreCase = true) ||
            link.text().trim() in setOf(">", "›", "»")
        if (isNext) validateRoute(steamAppId, link.attr("href"), kind) else null
    }

    private fun validateRoute(
        steamAppId: Int,
        value: String,
        kind: RouteKind,
    ): String? {
        if (steamAppId <= 0 || value.isBlank() || value.length > MAX_ROUTE_LENGTH) return null
        val url = endpoint.resolve(value) ?: return null
        if (!isAllowedNetworkUrl(url) || validateUrlAppId(url, steamAppId) != kind) return null
        return buildString {
            append(url.encodedPath)
            url.encodedQuery?.let { query -> append('?').append(query) }
        }
    }

    private fun validateUrlAppId(url: HttpUrl, steamAppId: Int): RouteKind? {
        val prefix = "/app/$steamAppId/discussions/"
        if (!url.encodedPath.startsWith(prefix)) return null
        val suffix = url.encodedPath.removePrefix(prefix)
        val segments = suffix.trimEnd('/').split('/').filter(String::isNotBlank)
        val kind = when {
            segments.isEmpty() -> RouteKind.LISTING
            segments.size == 1 && segments.single().all(Char::isDigit) -> RouteKind.LISTING
            segments.size == 2 && segments.all { segment -> segment.all(Char::isDigit) } -> RouteKind.THREAD
            else -> return null
        }
        if (!validPaginationQuery(url, kind)) return null
        return kind
    }

    private fun validPaginationQuery(url: HttpUrl, kind: RouteKind): Boolean {
        if (url.query == null) return true
        val paginationKey = when (kind) {
            RouteKind.LISTING -> "fp"
            RouteKind.THREAD -> "ctp"
        }
        if (url.queryParameterNames != setOf(paginationKey)) return false
        return url.queryParameterValues(paginationKey).singleOrNull()
            ?.toIntOrNull()
            ?.let { page -> page in 1..MAX_PAGE }
            ?: false
    }

    private fun isAllowedEndpoint(url: HttpUrl): Boolean =
        isAllowedNetworkUrl(url) && url.encodedPath == "/" && url.query == null

    private fun isAllowedNetworkUrl(url: HttpUrl): Boolean =
        (!requireHttps || url.isHttps) &&
            url.host in allowedHosts &&
            url.port in allowedPorts &&
            url.username.isEmpty() &&
            url.password.isEmpty() &&
            url.fragment == null

    private fun requestFor(url: HttpUrl): Request = Request.Builder()
        .url(url)
        .header("Cache-Control", "no-store")
        .get()
        .build()

    private fun Response.hasHtmlContentType(): Boolean {
        val contentType = body.contentType() ?: return false
        return contentType.type == "text" && contentType.subtype == "html"
    }

    private fun Response.readBoundedBody(): String {
        if (body.contentLength() > MAX_RESPONSE_BYTES) throw SteamDiscussionsUnavailable()
        val source = body.source()
        source.request(MAX_RESPONSE_BYTES + 1L)
        if (source.buffer.size > MAX_RESPONSE_BYTES) throw SteamDiscussionsUnavailable()
        return source.readUtf8()
    }

    private fun safeText(element: Element?): String? {
        val copy = element?.clone() ?: return null
        copy.select(
            "script, style, iframe, img, video, audio, blockquote, a, " +
                ".bb_quote, .forum_author_link, [data-miniprofile]",
        ).remove()
        return copy.text()
            .replace(WHITESPACE, " ")
            .trim()
            .takeIf(String::isNotBlank)
    }

    private fun parseCount(value: String?): Int? = value
        ?.filter(Char::isDigit)
        ?.takeIf(String::isNotEmpty)
        ?.toLongOrNull()
        ?.coerceAtMost(Int.MAX_VALUE.toLong())
        ?.toInt()

    private enum class RouteKind { LISTING, THREAD }

    private companion object {
        const val DEFAULT_ENDPOINT = "https://steamcommunity.com/"
        const val STEAM_COMMUNITY_HOST = "steamcommunity.com"
        const val MAX_NETWORK_HOPS = 4
        const val MAX_ITEMS = 50
        const val MAX_TITLE_LENGTH = 512
        const val MAX_ACTIVITY_LENGTH = 256
        const val MAX_POST_LENGTH = 32 * 1024
        const val MAX_ROUTE_LENGTH = 1024
        const val MAX_PAGE = 100
        const val MAX_RESPONSE_BYTES = 1024L * 1024L
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val WHITESPACE = Regex("\\s+")
    }
}

private class SteamDiscussionsUnavailable : IOException("Steam discussions unavailable")

private suspend fun Call.awaitDiscussionResponse(): Response = suspendCancellableCoroutine { continuation ->
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
