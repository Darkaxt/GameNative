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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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
    private val diagnostics: SteamCommunityDiagnosticSink = NoOpSteamCommunityDiagnostics,
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
        diagnostics = FeatureSteamCommunityDiagnostics(),
    )

    override suspend fun fetch(
        steamAppId: Int,
        query: SteamReviewQuery,
        cursor: String?,
    ): SteamReviewPage {
        val startedAt = System.nanoTime()
        val transport = TransportDiagnostic()
        return try {
            val parsed = fetchPage(steamAppId, query, cursor, transport)
            recordDiagnostic(
                SteamCommunityPageDiagnostic(
                    operation = SteamCommunityPageOperation.REVIEWS,
                    outcome = DiagnosticOutcome.SUCCEEDED,
                    durationMs = elapsedMs(startedAt),
                    httpStatus = transport.httpStatus,
                    attemptCount = transport.attemptCount,
                    itemCount = parsed.page.reviews.size,
                    skippedItemCount = parsed.skippedItemCount,
                    blankItemCount = parsed.blankItemCount,
                    duplicateItemCount = parsed.duplicateItemCount,
                ),
            )
            parsed.page
        } catch (error: CancellationException) {
            throw error
        } catch (error: SteamReviewsUnavailable) {
            recordDiagnostic(
                SteamCommunityPageDiagnostic(
                    operation = SteamCommunityPageOperation.REVIEWS,
                    outcome = error.reason.diagnosticOutcome,
                    durationMs = elapsedMs(startedAt),
                    httpStatus = transport.httpStatus,
                    attemptCount = transport.attemptCount,
                    failureReason = error.reason,
                ),
            )
            throw error
        } catch (_: IOException) {
            val failure = SteamReviewsUnavailable(
                SteamCommunityFailureReason.NETWORK_UNAVAILABLE,
            )
            recordDiagnostic(
                SteamCommunityPageDiagnostic(
                    operation = SteamCommunityPageOperation.REVIEWS,
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

    private suspend fun fetchPage(
        steamAppId: Int,
        query: SteamReviewQuery,
        cursor: String?,
        transport: TransportDiagnostic,
    ): ParsedReviewPage {
        if (
            steamAppId <= 0 ||
            !isAllowedEndpoint(endpoint) ||
            cursor != null && (cursor.isBlank() || cursor.length > MAX_CURSOR_LENGTH)
        ) {
            throw SteamReviewsUnavailable(SteamCommunityFailureReason.INVALID_REQUEST)
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
        if (!isAllowedNetworkUrl(requestUrl)) {
            throw SteamReviewsUnavailable(SteamCommunityFailureReason.INVALID_REQUEST)
        }
        val request = Request.Builder()
            .url(requestUrl)
            .header("Cache-Control", "no-store")
            .get()
            .build()
        val response = try {
            retryExecutor.execute {
                transport.attemptCount++
                client.newCall(request).awaitSteamReviewResponse().also { response ->
                    transport.httpStatus = response.code
                }
            }
        } catch (_: SteamRateLimitExhaustedException) {
            throw SteamReviewsUnavailable(SteamCommunityFailureReason.RATE_LIMITED)
        }
        response.use {
            transport.httpStatus = it.code
            if (!isAllowedNetworkUrl(it.request.url) || it.code in REDIRECT_CODES) {
                throw SteamReviewsUnavailable(SteamCommunityFailureReason.REDIRECT_REJECTED)
            }
            if (!it.isSuccessful) {
                throw SteamReviewsUnavailable(SteamCommunityFailureReason.HTTP_STATUS)
            }
            if (!it.hasJsonContentType()) {
                throw SteamReviewsUnavailable(SteamCommunityFailureReason.CONTENT_TYPE)
            }
            return parse(
                body = it.readBoundedBody(),
                pageScope = "$steamAppId:${cursor.orEmpty()}",
            )
        }
    }

    private fun parse(body: String, pageScope: String): ParsedReviewPage = try {
        val root = JSON.parseToJsonElement(body).jsonObject
        if (root["success"]?.jsonPrimitive?.intOrNull != 1) {
            throw SteamReviewsUnavailable(SteamCommunityFailureReason.MALFORMED_REPRESENTATION)
        }
        val boundedReviews = (root["reviews"] as? JsonArray)
            ?.take(MAX_REVIEWS)
            ?: throw SteamReviewsUnavailable(
                SteamCommunityFailureReason.MALFORMED_REPRESENTATION,
            )
        val reviews = boundedReviews.mapIndexedNotNull { index, value ->
            parseReview(
                value = value.jsonObject,
                fallbackIdentity = pageIdentity(pageScope, index),
            )
        }
        val nextCursor = root.string("cursor")
            ?.takeIf { it.isNotBlank() && it.length <= MAX_CURSOR_LENGTH }
        ParsedReviewPage(
            page = SteamReviewPage(reviews = reviews, nextCursor = nextCursor),
            skippedItemCount = boundedReviews.size - reviews.size,
            blankItemCount = boundedReviews.count { value ->
                (value as? JsonObject)?.string("review")?.isBlank() == true
            },
            duplicateItemCount = reviews.size - reviews.distinctBy { it.recommendationId }.size,
        )
    } catch (_: SerializationException) {
        throw SteamReviewsUnavailable(SteamCommunityFailureReason.MALFORMED_REPRESENTATION)
    } catch (_: IllegalArgumentException) {
        throw SteamReviewsUnavailable(SteamCommunityFailureReason.MALFORMED_REPRESENTATION)
    }

    private fun parseReview(
        value: JsonObject,
        fallbackIdentity: String,
    ): SteamReviewCard? {
        val recommendationId = value.string("recommendationid")
            ?.takeIf(String::isNotBlank)
            ?.takeIf { it.length <= MAX_RECOMMENDATION_ID_LENGTH }
            ?: fallbackIdentity
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
        if (body.contentLength() > MAX_RESPONSE_BYTES) {
            throw SteamReviewsUnavailable(SteamCommunityFailureReason.BODY_LIMIT)
        }
        val source = body.source()
        source.request(MAX_RESPONSE_BYTES + 1L)
        if (source.buffer.size > MAX_RESPONSE_BYTES) {
            throw SteamReviewsUnavailable(SteamCommunityFailureReason.BODY_LIMIT)
        }
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

    private fun pageIdentity(pageScope: String, index: Int): String =
        "page:${DiagnosticRedactor.correlationId("review:$pageScope:$index")}"

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

    private data class ParsedReviewPage(
        val page: SteamReviewPage,
        val skippedItemCount: Int,
        val blankItemCount: Int,
        val duplicateItemCount: Int,
    )

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
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAX_DIAGNOSTIC_DURATION_MS = 300_000L
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

private class SteamReviewsUnavailable(
    val reason: SteamCommunityFailureReason,
) : IOException("Steam reviews unavailable")

private suspend fun Call.awaitSteamReviewResponse(): Response = suspendCancellableCoroutine { continuation ->
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
