package app.gamenative.library.canonical.catalog

import app.gamenative.library.metadata.MetadataLocale
import app.gamenative.library.metadata.SteamHttpRetryExecutor
import app.gamenative.library.metadata.SteamRateLimitExhaustedException
import app.gamenative.library.metadata.SteamUrlPolicy
import app.gamenative.library.metadata.awaitSteamResponse
import app.gamenative.library.metadata.sanitizeSteamText
import app.gamenative.utils.Net
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody

fun interface SteamCatalogSearchSource {
    suspend fun search(
        query: String,
        locale: MetadataLocale,
    ): List<SteamStoreSearchHit>

    fun requestImmediateRetry() = Unit
}

data class SteamStoreSearchHit(
    val steamAppId: Int,
    val title: String,
    val headerImageUrl: String?,
)

class SteamCatalogSearchException internal constructor() :
    IOException("Steam catalog search unavailable")

class SteamStoreSearchProvider internal constructor(
    private val client: OkHttpClient,
    private val searchEndpoint: HttpUrl,
    private val urlPolicy: SteamUrlPolicy,
    private val retryExecutor: SteamHttpRetryExecutor,
) : SteamCatalogSearchSource {
    @Inject
    constructor() : this(
        client = Net.http.newBuilder().followRedirects(false).followSslRedirects(false).build(),
        searchEndpoint = DEFAULT_ENDPOINT.toHttpUrl(),
        urlPolicy = SteamUrlPolicy(),
        retryExecutor = SteamHttpRetryExecutor(),
    )

    override suspend fun search(
        query: String,
        locale: MetadataLocale,
    ): List<SteamStoreSearchHit> {
        val trimmedQuery = query.trim()
        require(trimmedQuery.isNotEmpty()) { "Steam catalog query is blank" }
        require(trimmedQuery.codePointCount(0, trimmedQuery.length) <= MAX_QUERY_CODE_POINTS) {
            "Steam catalog query is too long"
        }
        if (!urlPolicy.isAllowedStoreSearchRequest(searchEndpoint)) {
            throw SteamCatalogSearchException()
        }
        return try {
            val requestUrl = searchEndpoint.newBuilder()
                .setQueryParameter("term", trimmedQuery)
                .setQueryParameter("l", locale.steamLanguage)
                .setQueryParameter("cc", locale.normalizedCountry)
                .build()
            parseHits(executeValidated(Request.Builder().url(requestUrl).get().build()))
        } catch (error: CancellationException) {
            throw error
        } catch (error: SteamRateLimitExhaustedException) {
            throw error
        } catch (error: SteamCatalogSearchException) {
            throw error
        } catch (_: Exception) {
            throw SteamCatalogSearchException()
        }
    }

    private suspend fun executeValidated(initialRequest: Request): String {
        var request = initialRequest
        repeat(MAX_NETWORK_HOPS) {
            if (!urlPolicy.isAllowedStoreSearchRequest(request.url)) {
                throw SteamCatalogSearchException()
            }
            val response = retryExecutor.execute {
                client.newCall(request).awaitSteamResponse()
            }
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
                request = Request.Builder().url(next).get().build()
            } else {
                response.use { finalResponse ->
                    if (!finalResponse.isSuccessful) throw SteamCatalogSearchException()
                    return finalResponse.body.readStoreSearchBoundedUtf8(MAX_RESPONSE_BYTES)
                }
            }
        }
        throw SteamCatalogSearchException()
    }

    private fun parseHits(body: String): List<SteamStoreSearchHit> {
        val root = JSON.parseToJsonElement(body) as? JsonObject
            ?: throw SteamCatalogSearchException()
        val items = root["items"] as? JsonArray
            ?: if ((root["total"] as? JsonPrimitive)?.contentOrNull == "0") {
                return emptyList()
            } else {
                throw SteamCatalogSearchException()
            }
        return items.asSequence()
            .mapNotNull { it as? JsonObject }
            .filter { item -> (item["type"] as? JsonPrimitive)?.contentOrNull == "app" }
            .mapNotNull { item ->
                val appId = (item["id"] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 }
                    ?: return@mapNotNull null
                val title = sanitizeSteamText(
                    (item["name"] as? JsonPrimitive)?.contentOrNull,
                ) ?: return@mapNotNull null
                val image = (item["tiny_image"] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.toHttpUrlOrNull()
                    ?.takeIf(urlPolicy::isAllowedMediaUrl)
                    ?.toString()
                SteamStoreSearchHit(
                    steamAppId = appId,
                    title = title,
                    headerImageUrl = image,
                )
            }
            .distinctBy(SteamStoreSearchHit::steamAppId)
            .take(MAX_RESULTS)
            .toList()
    }

    private companion object {
        const val DEFAULT_ENDPOINT = "https://store.steampowered.com/api/storesearch/"
        const val MAX_QUERY_CODE_POINTS = 256
        const val MAX_RESULTS = 10
        const val MAX_NETWORK_HOPS = 4
        const val MAX_RESPONSE_BYTES = 1024L * 1024L
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

private fun ResponseBody.readStoreSearchBoundedUtf8(maxBytes: Long): String {
    if (contentLength() > maxBytes) throw SteamCatalogSearchException()
    val source = source()
    if (source.request(maxBytes + 1L)) throw SteamCatalogSearchException()
    return source.readUtf8()
}
