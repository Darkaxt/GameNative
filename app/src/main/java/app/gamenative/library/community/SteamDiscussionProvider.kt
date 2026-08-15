package app.gamenative.library.community

import app.gamenative.diagnostics.DiagnosticOutcome
import app.gamenative.diagnostics.DiagnosticRedactor
import app.gamenative.library.metadata.SteamHttpRetryExecutor
import app.gamenative.library.metadata.SteamRateLimitExhaustedException
import app.gamenative.utils.Net
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
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
import org.jsoup.nodes.TextNode

@Singleton
class SteamDiscussionProvider internal constructor(
    private val client: OkHttpClient,
    private val endpoint: HttpUrl,
    private val allowedHosts: Set<String>,
    private val requireHttps: Boolean,
    private val allowedPorts: Set<Int>,
    private val retryExecutor: SteamHttpRetryExecutor = SteamHttpRetryExecutor(),
    private val diagnostics: SteamCommunityDiagnosticSink = NoOpSteamCommunityDiagnostics,
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
        diagnostics = FeatureSteamCommunityDiagnostics(),
    )

    override suspend fun fetchListing(
        steamAppId: Int,
        route: String?,
    ): SteamDiscussionListing = fetchWithDiagnostics(
        SteamCommunityPageOperation.DISCUSSION_LISTING,
    ) { transport ->
        val initialRoute = route ?: "/app/$steamAppId/discussions/"
        val validatedRoute = validateRoute(steamAppId, initialRoute, RouteKind.LISTING)
            ?: throw SteamDiscussionsUnavailable(SteamCommunityFailureReason.INVALID_REQUEST)
        val parsed = parseListing(
            execute(steamAppId, validatedRoute, transport),
            steamAppId,
            validatedRoute,
        )
        ParsedPage(
            value = parsed.listing,
            itemCount = parsed.listing.threads.size,
            skippedItemCount = parsed.skippedItemCount,
            duplicateItemCount = parsed.duplicateItemCount,
        )
    }

    override suspend fun fetchThread(
        steamAppId: Int,
        route: String,
    ): SteamDiscussionThread = fetchWithDiagnostics(
        SteamCommunityPageOperation.DISCUSSION_THREAD,
    ) { transport ->
        val validatedRoute = validateRoute(steamAppId, route, RouteKind.THREAD)
            ?: throw SteamDiscussionsUnavailable(SteamCommunityFailureReason.INVALID_REQUEST)
        val parsed = parseThread(
            execute(steamAppId, validatedRoute, transport),
            steamAppId,
            validatedRoute,
        )
        ParsedPage(
            value = parsed.thread,
            itemCount = parsed.thread.posts.size,
            skippedItemCount = parsed.skippedItemCount,
            blankItemCount = parsed.blankItemCount,
            duplicateItemCount = parsed.duplicateItemCount,
        )
    }

    private suspend fun <T> fetchWithDiagnostics(
        operation: SteamCommunityPageOperation,
        fetchPage: suspend (TransportDiagnostic) -> ParsedPage<T>,
    ): T {
        val startedAt = System.nanoTime()
        val transport = TransportDiagnostic()
        return try {
            val parsed = fetchPage(transport)
            recordDiagnostic(
                SteamCommunityPageDiagnostic(
                    operation = operation,
                    outcome = DiagnosticOutcome.SUCCEEDED,
                    durationMs = elapsedMs(startedAt),
                    httpStatus = transport.httpStatus,
                    attemptCount = transport.attemptCount,
                    itemCount = parsed.itemCount,
                    skippedItemCount = parsed.skippedItemCount,
                    blankItemCount = parsed.blankItemCount,
                    duplicateItemCount = parsed.duplicateItemCount,
                ),
            )
            parsed.value
        } catch (error: CancellationException) {
            throw error
        } catch (error: SteamDiscussionsUnavailable) {
            recordDiagnostic(
                SteamCommunityPageDiagnostic(
                    operation = operation,
                    outcome = error.reason.diagnosticOutcome,
                    durationMs = elapsedMs(startedAt),
                    httpStatus = transport.httpStatus,
                    attemptCount = transport.attemptCount,
                    failureReason = error.reason,
                ),
            )
            throw error
        } catch (_: IOException) {
            val failure = SteamDiscussionsUnavailable(
                SteamCommunityFailureReason.NETWORK_UNAVAILABLE,
            )
            recordDiagnostic(
                SteamCommunityPageDiagnostic(
                    operation = operation,
                    outcome = DiagnosticOutcome.UNAVAILABLE,
                    durationMs = elapsedMs(startedAt),
                    httpStatus = transport.httpStatus,
                    attemptCount = transport.attemptCount,
                    failureReason = failure.reason,
                ),
            )
            throw failure
        }
    }

    private suspend fun execute(
        steamAppId: Int,
        route: String,
        transport: TransportDiagnostic,
    ): String {
        if (steamAppId <= 0 || !isAllowedEndpoint(endpoint)) {
            throw SteamDiscussionsUnavailable(SteamCommunityFailureReason.INVALID_REQUEST)
        }
        var request = requestFor(
            endpoint.resolve(route)
                ?: throw SteamDiscussionsUnavailable(SteamCommunityFailureReason.INVALID_REQUEST),
        )
        repeat(MAX_NETWORK_HOPS) {
            if (!isAllowedNetworkUrl(request.url) || validateUrlAppId(request.url, steamAppId) == null) {
                throw SteamDiscussionsUnavailable(SteamCommunityFailureReason.INVALID_REQUEST)
            }
            val response = try {
                retryExecutor.execute {
                    transport.attemptCount++
                    client.newCall(request).awaitDiscussionResponse().also { response ->
                        transport.httpStatus = response.code
                    }
                }
            } catch (_: SteamRateLimitExhaustedException) {
                throw SteamDiscussionsUnavailable(SteamCommunityFailureReason.RATE_LIMITED)
            }
            transport.httpStatus = response.code
            if (!isAllowedNetworkUrl(response.request.url)) {
                response.close()
                throw SteamDiscussionsUnavailable(SteamCommunityFailureReason.REDIRECT_REJECTED)
            }
            if (response.code in REDIRECT_CODES) {
                val next = response.header("Location")?.let(response.request.url::resolve)
                response.close()
                if (
                    next == null ||
                    !isAllowedNetworkUrl(next) ||
                    !isAllowedRedirect(request.url, next, steamAppId)
                ) {
                    throw SteamDiscussionsUnavailable(
                        SteamCommunityFailureReason.REDIRECT_REJECTED,
                    )
                }
                request = requestFor(next)
            } else {
                response.use { finalResponse ->
                    if (!finalResponse.isSuccessful) {
                        throw SteamDiscussionsUnavailable(SteamCommunityFailureReason.HTTP_STATUS)
                    }
                    if (!finalResponse.hasHtmlContentType()) {
                        throw SteamDiscussionsUnavailable(SteamCommunityFailureReason.CONTENT_TYPE)
                    }
                    return finalResponse.readBoundedBody()
                }
            }
        }
        throw SteamDiscussionsUnavailable(SteamCommunityFailureReason.REDIRECT_REJECTED)
    }

    private fun parseListing(
        body: String,
        steamAppId: Int,
        route: String,
    ): ParsedListing {
        val document = try {
            Jsoup.parse(body, endpoint.toString())
        } catch (_: IllegalArgumentException) {
            throw SteamDiscussionsUnavailable(
                SteamCommunityFailureReason.MALFORMED_REPRESENTATION,
            )
        }
        ensureSupportedRepresentation(document)
        val topics = document.select(".forum_topic").take(MAX_ITEMS)
        val explicitEmpty = document.selectFirst(
            ".forum_no_topics, .forum_topic_none, [data-forum-empty=true]",
        )
        if (topics.isEmpty() && explicitEmpty == null) {
            throw SteamDiscussionsUnavailable(
                SteamCommunityFailureReason.UNSUPPORTED_REPRESENTATION,
            )
        }
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
        if (topics.isNotEmpty() && threads.isEmpty()) {
            throw SteamDiscussionsUnavailable(
                SteamCommunityFailureReason.MALFORMED_REPRESENTATION,
            )
        }
        return ParsedListing(
            listing = SteamDiscussionListing(
                threads = threads,
                nextRoute = nextRoute(document, route, steamAppId, RouteKind.LISTING),
            ),
            skippedItemCount = topics.size - threads.size,
            duplicateItemCount = threads.size - threads.distinctBy { it.route }.size,
        )
    }

    private fun parseThread(
        body: String,
        steamAppId: Int,
        route: String,
    ): ParsedThread {
        val document = try {
            Jsoup.parse(body, endpoint.toString())
        } catch (_: IllegalArgumentException) {
            throw SteamDiscussionsUnavailable(
                SteamCommunityFailureReason.MALFORMED_REPRESENTATION,
            )
        }
        ensureSupportedRepresentation(document)
        val postContainers = document.select(".forum_op, .forum_post, .commentthread_comment")
        val explicitEmpty = document.selectFirst(
            ".forum_thread_empty, .forum_posts_empty, [data-forum-thread-empty=true]",
        )
        if (postContainers.isEmpty() && explicitEmpty == null) {
            throw SteamDiscussionsUnavailable(
                SteamCommunityFailureReason.UNSUPPORTED_REPRESENTATION,
            )
        }
        val title = safeText(document.selectFirst(".topic, .forum_topic_name, h1"))
            ?.take(MAX_TITLE_LENGTH)
            ?: "Steam discussion"
        val candidates = mutableListOf<Element>()
        if (threadPage(route) == 1) {
            document.selectFirst(
                ".forum_op > .content, .forum_op .forum_post_text, .forum_op .forum_post_body",
            )?.let(candidates::add)
        }
        listOf(
            ".forum_post > .content",
            ".forum_post_text",
            ".forum_post_body",
            ".commentthread_comment_text",
        ).forEach { selector ->
            document.select(selector).forEach { candidate ->
                val insideOpeningPost = candidate.parents().any { it.hasClass("forum_op") }
                if (!insideOpeningPost && candidates.none { it === candidate }) {
                    candidates.add(candidate)
                }
            }
        }
        if (postContainers.isNotEmpty() && candidates.isEmpty()) {
            throw SteamDiscussionsUnavailable(
                SteamCommunityFailureReason.MALFORMED_REPRESENTATION,
            )
        }
        var blankItemCount = 0
        var skippedItemCount = 0
        val posts = candidates
            .take(MAX_ITEMS)
            .mapIndexedNotNull { index, candidate ->
                val text = safePostText(candidate)
                if (text == null) {
                    if (isBlankPostContent(candidate)) {
                        blankItemCount++
                    } else {
                        skippedItemCount++
                    }
                    null
                } else {
                    SteamDiscussionPost(
                        text = text.take(MAX_POST_LENGTH),
                        postId = stablePostId(candidate) ?: pagePostIdentity(route, index),
                    )
                }
            }
        if (candidates.isNotEmpty() && posts.isEmpty()) {
            throw SteamDiscussionsUnavailable(
                SteamCommunityFailureReason.MALFORMED_REPRESENTATION,
            )
        }
        return ParsedThread(
            thread = SteamDiscussionThread(
                title = title,
                posts = posts,
                route = route.substringBefore('?'),
                nextRoute = nextRoute(document, route, steamAppId, RouteKind.THREAD),
            ),
            skippedItemCount = skippedItemCount,
            blankItemCount = blankItemCount,
            duplicateItemCount = posts.size - posts.distinctBy { it.postId }.size,
        )
    }

    private fun ensureSupportedRepresentation(document: Document) {
        if (
            document.selectFirst(
                ".error_ctn, .community_home_error, #error_box, [data-steam-error]",
            ) != null
        ) {
            throw SteamDiscussionsUnavailable(
                SteamCommunityFailureReason.UNSUPPORTED_REPRESENTATION,
            )
        }
        val clientRenderedShell = document.selectFirst("#application_root") != null &&
            document.selectFirst("script[src*='/javascript/applications/community/main.js']") != null
        val serverForumContent = document.selectFirst(
            ".forum_topic, .forum_op, .forum_post, .commentthread_comment",
        ) != null
        if (clientRenderedShell && !serverForumContent) {
            throw SteamDiscussionsUnavailable(
                SteamCommunityFailureReason.CLIENT_RENDERED_SHELL,
            )
        }
    }

    private fun threadPage(route: String): Int = endpoint.resolve(route)
        ?.queryParameter("ctp")
        ?.toIntOrNull()
        ?: 1

    private fun stablePostId(element: Element): String? {
        var current: Element? = element
        while (current != null) {
            val node = current
            listOf("data-postid" to "post:", "data-commentid" to "comment:")
                .firstNotNullOfOrNull { (attribute, prefix) ->
                    node.attr(attribute)
                        .takeIf { value -> value.isNotEmpty() && value.all { it in '0'..'9' } }
                        ?.let { value -> "$prefix$value" }
                }
                ?.let { return it }
            node.id()
                .takeIf { POST_ID.matches(it) }
                ?.let { return it }
            current = node.parent()
        }
        return null
    }

    private fun pagePostIdentity(route: String, index: Int): String =
        "page:${DiagnosticRedactor.correlationId("discussion:$route:$index")}"

    private fun nextRoute(
        document: Document,
        route: String,
        steamAppId: Int,
        kind: RouteKind,
    ): String? = nextAnchorRoute(document, route, steamAppId, kind)
        ?: nextSummaryRoute(document, route, steamAppId, kind)

    private fun nextAnchorRoute(
        document: Document,
        route: String,
        steamAppId: Int,
        kind: RouteKind,
    ): String? = document.select("a[rel=next], a.pagebtn").firstNotNullOfOrNull { link ->
        val isNext = link.attr("rel").equals("next", ignoreCase = true) ||
            link.text().trim().equals("next", ignoreCase = true) ||
            link.text().trim() in setOf(">", "›", "»")
        val candidate = if (isNext) {
            validateRoute(steamAppId, link.attr("href"), kind)
        } else {
            null
        }
        candidate?.takeIf { exactNextRoute(route, it, kind) }
    }

    private fun exactNextRoute(
        currentRoute: String,
        candidateRoute: String,
        kind: RouteKind,
    ): Boolean {
        val currentUrl = endpoint.resolve(currentRoute) ?: return false
        val candidateUrl = endpoint.resolve(candidateRoute) ?: return false
        return candidateUrl.encodedPath == currentUrl.encodedPath &&
            routePage(candidateRoute, kind) == routePage(currentRoute, kind) + 1
    }

    private fun nextSummaryRoute(
        document: Document,
        route: String,
        steamAppId: Int,
        kind: RouteKind,
    ): String? {
        val currentPage = routePage(route, kind)
        val totalPages = pagingTotalPages(document, currentPage) ?: return null
        if (currentPage >= totalPages || currentPage >= MAX_PAGE) return null
        val paginationKey = when (kind) {
            RouteKind.LISTING -> "fp"
            RouteKind.THREAD -> "ctp"
        }
        return validateRoute(
            steamAppId,
            "${route.substringBefore('?')}?$paginationKey=${currentPage + 1}",
            kind,
        )
    }

    private fun routePage(route: String, kind: RouteKind): Int {
        val key = when (kind) {
            RouteKind.LISTING -> "fp"
            RouteKind.THREAD -> "ctp"
        }
        return endpoint.resolve(route)?.queryParameter(key)?.toIntOrNull() ?: 1
    }

    private fun pagingTotalPages(document: Document, currentPage: Int): Int? {
        val values = document.selectFirst(".forum_paging_summary")
            ?.select("span")
            ?.mapNotNull { span ->
                span.text()
                    .trim()
                    .replace(",", "")
                    .takeIf { value -> value.isNotEmpty() && value.all { it in '0'..'9' } }
                    ?.toLongOrNull()
            }
            ?.take(3)
            ?: return null
        if (values.size != 3) return null
        val (start, end, total) = values
        if (start < 1L || end < start || total < end) return null
        val observedPageSize = end - start + 1L
        val inferredPageSize = if (
            currentPage > 1 && start > 1L && (start - 1L) % (currentPage - 1L) == 0L
        ) {
            (start - 1L) / (currentPage - 1L)
        } else {
            0L
        }
        val pageSize = maxOf(observedPageSize, inferredPageSize)
        if (pageSize < 1L) return null
        return ((total + pageSize - 1L) / pageSize)
            .coerceAtMost(MAX_PAGE.toLong())
            .toInt()
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

    private fun isAllowedRedirect(
        current: HttpUrl,
        next: HttpUrl,
        steamAppId: Int,
    ): Boolean {
        val currentKind = validateUrlAppId(current, steamAppId) ?: return false
        val nextKind = validateUrlAppId(next, steamAppId) ?: return false
        if (currentKind != nextKind) return false
        return currentKind != RouteKind.THREAD ||
            current.encodedPath == next.encodedPath && current.encodedQuery == next.encodedQuery
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
        .header("User-Agent", DISCUSSION_USER_AGENT)
        .header("Cache-Control", "no-store")
        .get()
        .build()

    private fun Response.hasHtmlContentType(): Boolean {
        val contentType = body.contentType() ?: return false
        return contentType.type == "text" && contentType.subtype == "html"
    }

    private fun Response.readBoundedBody(): String {
        if (body.contentLength() > MAX_RESPONSE_BYTES) {
            throw SteamDiscussionsUnavailable(SteamCommunityFailureReason.BODY_LIMIT)
        }
        val source = body.source()
        source.request(MAX_RESPONSE_BYTES + 1L)
        if (source.buffer.size > MAX_RESPONSE_BYTES) {
            throw SteamDiscussionsUnavailable(SteamCommunityFailureReason.BODY_LIMIT)
        }
        return source.readUtf8()
    }

    private fun isBlankPostContent(element: Element): Boolean {
        val copy = element.clone()
        copy.select("br").remove()
        return copy.children().isEmpty() && copy.ownText().isBlank()
    }

    private fun safePostText(element: Element): String? {
        val copy = element.clone()
        copy.select("img.emoticon[alt]").forEach { emoticon ->
            val alt = emoticon.attr("alt")
                .trim()
                .take(MAX_EMOTICON_ALT_LENGTH)
            if (alt.isEmpty()) {
                emoticon.remove()
            } else {
                emoticon.replaceWith(TextNode(" $alt "))
            }
        }
        copy.select(
            "script, style, iframe, img, video, audio, object, embed, " +
                ".forum_author_link, [data-miniprofile]",
        ).remove()
        return copy.text()
            .replace(WHITESPACE, " ")
            .trim()
            .takeIf(String::isNotBlank)
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

    private fun recordDiagnostic(event: SteamCommunityPageDiagnostic) {
        runCatching { diagnostics.record(event) }
    }

    private fun elapsedMs(startedAt: Long): Long =
        ((System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND)
            .coerceIn(0L, MAX_DIAGNOSTIC_DURATION_MS)

    private data class TransportDiagnostic(
        var attemptCount: Int = 0,
        var httpStatus: Int? = null,
    )

    private data class ParsedPage<T>(
        val value: T,
        val itemCount: Int,
        val skippedItemCount: Int = 0,
        val blankItemCount: Int = 0,
        val duplicateItemCount: Int = 0,
    )

    private data class ParsedListing(
        val listing: SteamDiscussionListing,
        val skippedItemCount: Int,
        val duplicateItemCount: Int,
    )

    private data class ParsedThread(
        val thread: SteamDiscussionThread,
        val skippedItemCount: Int,
        val blankItemCount: Int,
        val duplicateItemCount: Int,
    )

    private enum class RouteKind { LISTING, THREAD }

    private companion object {
        const val DEFAULT_ENDPOINT = "https://steamcommunity.com/"
        const val STEAM_COMMUNITY_HOST = "steamcommunity.com"
        const val DISCUSSION_USER_AGENT = "GameNative/1.0 python-requests-compatible"
        const val MAX_NETWORK_HOPS = 4
        const val MAX_ITEMS = 50
        const val MAX_TITLE_LENGTH = 512
        const val MAX_ACTIVITY_LENGTH = 256
        const val MAX_EMOTICON_ALT_LENGTH = 256
        const val MAX_POST_LENGTH = 32 * 1024
        const val MAX_ROUTE_LENGTH = 1024
        const val MAX_PAGE = 100
        const val MAX_RESPONSE_BYTES = 1024L * 1024L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAX_DIAGNOSTIC_DURATION_MS = 300_000L
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val POST_ID = Regex("(?:forum_op|forum_post|comment|commentthread_comment)[_-][0-9]+")
        val WHITESPACE = Regex("\\s+")
    }
}

private class SteamDiscussionsUnavailable(
    val reason: SteamCommunityFailureReason,
) : IOException("Steam discussions unavailable")

private suspend fun Call.awaitDiscussionResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resume(response) { _, deliveredResponse, _ ->
                        deliveredResponse.close()
                    }
                } else {
                    response.close()
                }
            }
        },
    )
}
