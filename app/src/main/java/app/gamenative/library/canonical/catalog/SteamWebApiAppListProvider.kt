package app.gamenative.library.canonical.catalog

import app.gamenative.library.metadata.SteamUrlPolicy
import app.gamenative.library.metadata.sanitizeSteamText
import app.gamenative.utils.Net
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody

internal data class SteamAppListEntry(
    val steamAppId: Int,
    val title: String,
    val lastModifiedEpochSeconds: Long,
)

internal fun interface SteamAppListRemoteSource {
    suspend fun fetchAll(apiKey: String): List<SteamAppListEntry>
}

internal class SteamWebApiAppListProvider internal constructor(
    private val client: OkHttpClient,
    private val endpoint: HttpUrl,
    private val urlPolicy: SteamUrlPolicy,
    private val maxResponseBytes: Long,
) : SteamAppListRemoteSource {
    @Inject
    constructor() : this(
        client = Net.http.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .build(),
        endpoint = DEFAULT_ENDPOINT.toHttpUrl(),
        urlPolicy = SteamUrlPolicy(),
        maxResponseBytes = MAX_RESPONSE_BYTES,
    )

    init {
        require(maxResponseBytes in 1..MAX_RESPONSE_BYTES)
    }

    override suspend fun fetchAll(apiKey: String): List<SteamAppListEntry> {
        require(API_KEY_PATTERN.matches(apiKey)) { "Steam Web API key is invalid" }
        return try {
            fetchAllInternal(apiKey)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SteamCatalogSearchException) {
            throw error
        } catch (_: Exception) {
            throw SteamCatalogSearchException()
        }
    }

    private suspend fun fetchAllInternal(apiKey: String): List<SteamAppListEntry> {
        val entries = ArrayList<SteamAppListEntry>()
        var lastAppId: Int? = null
        repeat(MAX_PAGES) {
            val url = endpoint.newBuilder()
                .query(null)
                .addQueryParameter("include_games", "true")
                .addQueryParameter("include_dlc", "false")
                .addQueryParameter("include_software", "false")
                .addQueryParameter("include_videos", "false")
                .addQueryParameter("include_hardware", "false")
                .addQueryParameter("max_results", PAGE_SIZE.toString())
                .apply { lastAppId?.let { addQueryParameter("last_appid", it.toString()) } }
                .build()
            val request = Request.Builder()
                .url(url)
                .header(API_KEY_HEADER, apiKey)
                .get()
                .build()
            val page = parsePage(executeValidated(request), lastAppId)
            if (entries.size + page.entries.size > MAX_TOTAL_ENTRIES) {
                throw SteamCatalogSearchException()
            }
            entries += page.entries
            if (!page.haveMoreResults) {
                return entries.distinctBy(SteamAppListEntry::steamAppId)
            }
            lastAppId = page.lastAppId
        }
        throw SteamCatalogSearchException()
    }

    private suspend fun executeValidated(request: Request): String {
        if (!urlPolicy.isAllowedWebApiAppListRequest(request.url)) {
            throw SteamCatalogSearchException()
        }
        val response = client.newCall(request).awaitAppListResponse()
        response.use {
            if (!urlPolicy.isAllowedWebApiAppListRequest(it.request.url) || !it.isSuccessful) {
                throw SteamCatalogSearchException()
            }
            return it.body.readAppListBoundedUtf8(maxResponseBytes)
        }
    }

    private fun parsePage(body: String, previousLastAppId: Int?): ParsedPage {
        val root = JSON.parseToJsonElement(body) as? JsonObject
            ?: throw SteamCatalogSearchException()
        val response = root["response"] as? JsonObject
            ?: throw SteamCatalogSearchException()
        val apps = response["apps"] as? JsonArray
            ?: throw SteamCatalogSearchException()
        if (apps.size > PAGE_SIZE) throw SteamCatalogSearchException()
        val parsedApps = apps.map(::parseApp)
        if (parsedApps.zipWithNext().any { (left, right) -> left.appId >= right.appId }) {
            throw SteamCatalogSearchException()
        }
        previousLastAppId?.let { previous ->
            if (parsedApps.firstOrNull()?.appId?.let { it <= previous } == true) {
                throw SteamCatalogSearchException()
            }
        }
        val entries = parsedApps.mapNotNull(ParsedApp::entry)
        val haveMore = response["have_more_results"].booleanValue()
            ?: throw SteamCatalogSearchException()
        val lastAppId = response["last_appid"].intValue()
        if (haveMore && (
                parsedApps.isEmpty() ||
                    lastAppId == null ||
                    lastAppId != parsedApps.last().appId ||
                    previousLastAppId?.let { lastAppId <= it } == true
                )
        ) {
            throw SteamCatalogSearchException()
        }
        return ParsedPage(entries, haveMore, lastAppId)
    }

    private fun parseApp(element: JsonElement): ParsedApp {
        val app = element as? JsonObject ?: throw SteamCatalogSearchException()
        val appId = app["appid"].intValue()?.takeIf { it > 0 }
            ?: throw SteamCatalogSearchException()
        val title = sanitizeSteamText(app["name"].stringValue())
            ?.takeIf { it.codePointCount(0, it.length) <= MAX_TITLE_CODE_POINTS }
            ?: return ParsedApp(appId, null)
        val lastModified = app["last_modified"].longValue()?.takeIf { it >= 0L } ?: 0L
        return ParsedApp(appId, SteamAppListEntry(appId, title, lastModified))
    }

    private data class ParsedApp(
        val appId: Int,
        val entry: SteamAppListEntry?,
    )

    private data class ParsedPage(
        val entries: List<SteamAppListEntry>,
        val haveMoreResults: Boolean,
        val lastAppId: Int?,
    )

    private companion object {
        const val DEFAULT_ENDPOINT = "https://api.steampowered.com/IStoreService/GetAppList/v1/"
        const val API_KEY_HEADER = "x-webapi-key"
        const val PAGE_SIZE = 50_000
        const val MAX_PAGES = 10
        const val MAX_TOTAL_ENTRIES = 500_000
        const val MAX_TITLE_CODE_POINTS = 500
        const val MAX_RESPONSE_BYTES = 16L * 1024L * 1024L
        val API_KEY_PATTERN = Regex("[0-9A-Fa-f]{32}")
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

private fun JsonElement?.stringValue(): String? =
    (this as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

private fun JsonElement?.intValue(): Int? = (this as? JsonPrimitive)?.intOrNull

private fun JsonElement?.longValue(): Long? = (this as? JsonPrimitive)?.longOrNull

private fun JsonElement?.booleanValue(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

private fun ResponseBody.readAppListBoundedUtf8(maxBytes: Long): String {
    if (contentLength() > maxBytes) throw SteamCatalogSearchException()
    val source = source()
    if (source.request(maxBytes + 1L)) throw SteamCatalogSearchException()
    return source.readUtf8()
}

private suspend fun Call.awaitAppListResponse(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, error: java.io.IOException) {
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
