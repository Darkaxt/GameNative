package app.gamenative.library.discovery

import app.gamenative.data.canonical.SteamTagDictionaryEntity
import app.gamenative.db.dao.CanonicalFacetDao
import app.gamenative.library.metadata.MetadataClock
import app.gamenative.library.metadata.MetadataLocale
import app.gamenative.library.metadata.sanitizeSteamText
import app.gamenative.utils.Net
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

sealed interface SteamTagDictionaryRefreshResult {
    data class Updated(val tagIds: Set<Int>) : SteamTagDictionaryRefreshResult
    data object Failed : SteamTagDictionaryRefreshResult
}

@Singleton
class SteamTagDictionaryProvider internal constructor(
    private val client: OkHttpClient,
    private val dictionaryEndpoint: HttpUrl,
    private val facetDao: CanonicalFacetDao,
    private val allowedHosts: Set<String>,
    private val requireHttps: Boolean,
    private val allowedPorts: Set<Int>,
    private val clock: MetadataClock,
) {
    @Inject
    constructor(
        facetDao: CanonicalFacetDao,
        clock: MetadataClock,
    ) : this(
        client = Net.http.newBuilder().followRedirects(false).followSslRedirects(false).build(),
        dictionaryEndpoint = DEFAULT_ENDPOINT.toHttpUrl(),
        facetDao = facetDao,
        allowedHosts = setOf(STEAM_STORE_HOST),
        requireHttps = true,
        allowedPorts = setOf(443),
        clock = clock,
    )

    suspend fun refresh(locale: MetadataLocale): SteamTagDictionaryRefreshResult {
        if (!isAllowedEndpoint(dictionaryEndpoint)) return SteamTagDictionaryRefreshResult.Failed
        return try {
            val requestUrl = dictionaryEndpoint.newBuilder()
                .addPathSegment(locale.steamLanguage)
                .build()
            val body = executeValidated(Request.Builder().url(requestUrl).get().build())
            val entities = parse(body, locale.normalizedLocale, clock.nowEpochMs())
            if (entities.isEmpty()) return SteamTagDictionaryRefreshResult.Failed
            facetDao.upsertSteamTags(entities)
            SteamTagDictionaryRefreshResult.Updated(immutableTagIds(entities.map { it.tagId }))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            SteamTagDictionaryRefreshResult.Failed
        }
    }

    private suspend fun executeValidated(initialRequest: Request): String {
        var request = initialRequest
        repeat(MAX_NETWORK_HOPS) {
            if (!isAllowedNetworkUrl(request.url)) throw SteamTagDictionaryUnavailable()
            val response = client.newCall(request).awaitResponse()
            if (!isAllowedNetworkUrl(response.request.url)) {
                response.close()
                throw SteamTagDictionaryUnavailable()
            }
            if (response.code in REDIRECT_CODES) {
                val next = response.header("Location")?.let(response.request.url::resolve)
                response.close()
                if (next == null || !isAllowedNetworkUrl(next)) {
                    throw SteamTagDictionaryUnavailable()
                }
                request = Request.Builder().url(next).get().build()
            } else {
                response.use { finalResponse ->
                    if (!finalResponse.isSuccessful) throw SteamTagDictionaryUnavailable()
                    return finalResponse.body.string()
                }
            }
        }
        throw SteamTagDictionaryUnavailable()
    }

    private fun parse(
        body: String,
        locale: String,
        fetchedAt: Long,
    ): List<SteamTagDictionaryEntity> {
        val values = JSON.parseToJsonElement(body) as? JsonArray
            ?: throw SteamTagDictionaryUnavailable()
        return values.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val tagId = (entry["tagid"] as? JsonPrimitive)
                ?.takeUnless(JsonPrimitive::isString)
                ?.intOrNull
                ?.takeIf { it > 0 }
                ?: return@mapNotNull null
            val rawLabel = (entry["name"] as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.contentOrNull
            val label = sanitizeSteamText(rawLabel)
                ?.take(MAX_TAG_LABEL_LENGTH)
                ?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            SteamTagDictionaryEntity(tagId, locale, label, fetchedAt)
        }.distinctBy(SteamTagDictionaryEntity::tagId)
    }

    private fun isAllowedEndpoint(url: HttpUrl): Boolean =
        isAllowedNetworkUrl(url) &&
            url.encodedPath == POPULAR_TAGS_PATH &&
            url.query == null

    private fun isAllowedNetworkUrl(url: HttpUrl): Boolean =
        (!requireHttps || url.isHttps) &&
            url.host in allowedHosts &&
            url.port in allowedPorts &&
            url.username.isEmpty() &&
            url.password.isEmpty() &&
            url.fragment == null

    private companion object {
        const val DEFAULT_ENDPOINT = "https://store.steampowered.com/tagdata/populartags"
        const val STEAM_STORE_HOST = "store.steampowered.com"
        const val POPULAR_TAGS_PATH = "/tagdata/populartags"
        const val MAX_TAG_LABEL_LENGTH = 80
        const val MAX_NETWORK_HOPS = 4
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

private class SteamTagDictionaryUnavailable : IOException("Steam tag dictionary unavailable")

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
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
