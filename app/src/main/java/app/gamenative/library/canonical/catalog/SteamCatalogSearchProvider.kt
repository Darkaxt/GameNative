package app.gamenative.library.canonical.catalog

import app.gamenative.library.metadata.MetadataLocale
import app.gamenative.library.metadata.SteamUrlPolicy
import app.gamenative.library.metadata.sanitizeSteamText
import app.gamenative.utils.Net
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody

fun interface SteamCatalogSearchSource {
    suspend fun search(
        query: String,
        locale: MetadataLocale,
    ): List<SteamStoreSearchHit>
}

data class SteamStoreSearchHit(
    val steamAppId: Int,
    val title: String,
    val headerImageUrl: String?,
)

class SteamCatalogSearchException internal constructor() :
    IOException("Steam catalog search unavailable")

class SteamCatalogSearchProvider internal constructor(
    private val client: OkHttpClient,
    private val endpoint: HttpUrl,
    private val urlPolicy: SteamUrlPolicy,
    private val maxResponseBytes: Long,
) : SteamCatalogSearchSource {
    init {
        require(maxResponseBytes in 1..MAX_RESPONSE_BYTES) {
            "Invalid Steam catalog response limit"
        }
    }

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

    override suspend fun search(
        query: String,
        locale: MetadataLocale,
    ): List<SteamStoreSearchHit> {
        val trimmedQuery = query.trim()
        require(trimmedQuery.isNotEmpty()) { "Steam catalog query is blank" }
        require(trimmedQuery.codePointCount() <= MAX_QUERY_CODE_POINTS) {
            "Steam catalog query is too long"
        }
        if (!urlPolicy.isAllowedStoreSearchRequest(endpoint)) {
            throw SteamCatalogSearchException()
        }

        return try {
            searchInternal(trimmedQuery, locale)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SteamCatalogSearchException) {
            throw error
        } catch (_: Exception) {
            throw SteamCatalogSearchException()
        }
    }

    private suspend fun searchInternal(
        query: String,
        locale: MetadataLocale,
    ): List<SteamStoreSearchHit> {
        val requestUrl = endpoint.newBuilder()
            .query(null)
            .addQueryParameter("term", query)
            .addQueryParameter("cc", locale.normalizedCountry)
            .addQueryParameter("l", locale.steamLanguage)
            .build()
        val request = Request.Builder()
            .url(requestUrl)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .get()
            .build()
        return parse(executeValidated(request))
    }

    private suspend fun executeValidated(initialRequest: Request): String {
        var request = initialRequest
        repeat(MAX_NETWORK_HOPS) {
            if (!urlPolicy.isAllowedStoreSearchRequest(request.url)) {
                throw SteamCatalogSearchException()
            }
            val response = client.newCall(request).awaitCatalogSearchResponse()
            if (!urlPolicy.isAllowedStoreSearchRequest(response.request.url)) {
                response.close()
                throw SteamCatalogSearchException()
            }
            if (response.code in REDIRECT_CODES) {
                val next = response.header("Location")?.let(response.request.url::resolve)
                response.close()
                if (next == null || !urlPolicy.isAllowedStoreSearchRequest(next)) {
                    throw SteamCatalogSearchException()
                }
                request = Request.Builder()
                    .url(next)
                    .cacheControl(CacheControl.FORCE_NETWORK)
                    .get()
                    .build()
            } else {
                response.use { finalResponse ->
                    if (!finalResponse.isSuccessful) throw SteamCatalogSearchException()
                    return finalResponse.body.readBoundedUtf8(maxResponseBytes)
                }
            }
        }
        throw SteamCatalogSearchException()
    }

    private fun parse(body: String): List<SteamStoreSearchHit> {
        val root = JSON.parseToJsonElement(body) as? JsonObject
            ?: throw SteamCatalogSearchException()
        return (root["items"] as? JsonArray)
            .orEmpty()
            .mapNotNull(::parseHit)
            .distinctBy(SteamStoreSearchHit::steamAppId)
            .take(MAX_RESULTS)
    }

    private fun parseHit(element: JsonElement): SteamStoreSearchHit? {
        val item = element as? JsonObject ?: return null
        if (!item["type"].stringOrNull().equals("app", ignoreCase = true)) return null
        val steamAppId = item["id"].positiveIntOrNull() ?: return null
        val title = sanitizeSteamText(item["name"].stringOrNull())
            ?.take(MAX_TITLE_LENGTH)
            ?: return null
        val headerImageUrl = item["tiny_image"].stringOrNull()
            ?.toHttpUrlOrNull()
            ?.takeIf(urlPolicy::isAllowedMediaUrl)
            ?.toString()
        return SteamStoreSearchHit(
            steamAppId = steamAppId,
            title = title,
            headerImageUrl = headerImageUrl,
        )
    }

    private companion object {
        const val DEFAULT_ENDPOINT = "https://store.steampowered.com/api/storesearch/"
        const val MAX_QUERY_CODE_POINTS = 256
        const val MAX_TITLE_LENGTH = 500
        const val MAX_RESULTS = 10
        const val MAX_NETWORK_HOPS = 4
        const val MAX_RESPONSE_BYTES = 1_000_000L
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

private fun String.codePointCount(): Int = codePointCount(0, length)

private fun JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

private fun JsonElement?.positiveIntOrNull(): Int? =
    (this as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }

private fun ResponseBody.readBoundedUtf8(maxBytes: Long): String {
    if (contentLength() > maxBytes) throw SteamCatalogSearchException()
    val source = source()
    if (source.request(maxBytes + 1L)) throw SteamCatalogSearchException()
    return source.readUtf8()
}

private suspend fun Call.awaitCatalogSearchResponse(): Response =
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
