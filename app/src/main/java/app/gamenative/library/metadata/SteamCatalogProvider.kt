package app.gamenative.library.metadata

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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

interface SteamCatalogDataSource {
    suspend fun fetch(
        trustedSteamAppId: Int,
        locale: MetadataLocale,
    ): CanonicalGameMetadata?
}

class SteamCatalogException internal constructor() : IOException("Steam catalog unavailable")

class SteamCatalogProvider internal constructor(
    private val client: OkHttpClient,
    private val apiEndpoint: HttpUrl,
    private val urlPolicy: SteamUrlPolicy,
    private val clock: MetadataClock,
) : SteamCatalogDataSource {
    @Inject
    constructor() : this(
        client = Net.http.newBuilder().followRedirects(false).followSslRedirects(false).build(),
        apiEndpoint = DEFAULT_ENDPOINT.toHttpUrl(),
        urlPolicy = SteamUrlPolicy(),
        clock = MetadataClock(System::currentTimeMillis),
    )

    override suspend fun fetch(
        trustedSteamAppId: Int,
        locale: MetadataLocale,
    ): CanonicalGameMetadata? {
        require(trustedSteamAppId > 0) { "Trusted Steam identity is invalid" }
        if (!urlPolicy.isAllowedApiRequest(apiEndpoint)) throw SteamCatalogException()
        return try {
            fetchInternal(trustedSteamAppId, locale)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SteamCatalogException) {
            throw error
        } catch (_: Exception) {
            throw SteamCatalogException()
        }
    }

    private suspend fun fetchInternal(
        trustedSteamAppId: Int,
        locale: MetadataLocale,
    ): CanonicalGameMetadata? {
        val requestUrl = apiEndpoint.newBuilder()
            .setQueryParameter("appids", trustedSteamAppId.toString())
            .setQueryParameter("l", locale.steamLanguage)
            .setQueryParameter("cc", locale.normalizedCountry)
            .build()
        val body = executeValidated(Request.Builder().url(requestUrl).get().build())
        return parse(body, trustedSteamAppId)
    }

    private suspend fun executeValidated(initialRequest: Request): String {
        var request = initialRequest
        repeat(MAX_NETWORK_HOPS) {
            if (!urlPolicy.isAllowedNetworkUrl(request.url)) throw SteamCatalogException()
            val response = client.newCall(request).awaitResponse()
            if (!urlPolicy.isAllowedNetworkUrl(response.request.url)) {
                response.close()
                throw SteamCatalogException()
            }
            if (response.code in REDIRECT_CODES) {
                val next = response.header("Location")?.let(response.request.url::resolve)
                response.close()
                if (next == null || !urlPolicy.isAllowedNetworkUrl(next)) {
                    throw SteamCatalogException()
                }
                request = Request.Builder().url(next).get().build()
            } else {
                response.use { finalResponse ->
                    if (!finalResponse.isSuccessful) throw SteamCatalogException()
                    return finalResponse.body.string()
                }
            }
        }
        throw SteamCatalogException()
    }

    private fun parse(body: String, trustedSteamAppId: Int): CanonicalGameMetadata? {
        val root = JSON.parseToJsonElement(body).objectOrNull() ?: throw SteamCatalogException()
        if (root.size != 1) throw SteamCatalogException()
        val envelope = root[trustedSteamAppId.toString()].objectOrNull()
            ?: throw SteamCatalogException()
        if (envelope["success"].booleanOrNull() != true) return null
        val data = envelope["data"].objectOrNull() ?: return null
        val title = sanitizeSteamText(data["name"].stringOrNull()) ?: return null

        return CanonicalGameMetadata(
            title = title,
            shortDescription = sanitizeSteamText(data["short_description"].stringOrNull()),
            about = sanitizeSteamText(data["about_the_game"].stringOrNull()),
            headerImageUrl = data["header_image"].safeMediaUrl(),
            screenshots = data["screenshots"].arrayOrNull()
                .orEmpty()
                .mapNotNull { screenshot ->
                    screenshot.objectOrNull()?.get("path_full").safeMediaUrl()
                }
                .distinct(),
            movies = parseMovies(data["movies"]),
            developers = parseTextList(data["developers"]),
            publishers = parseTextList(data["publishers"]),
            releaseDate = sanitizeSteamText(
                data["release_date"].objectOrNull()?.get("date").stringOrNull(),
            ),
            platforms = parsePlatforms(data["platforms"]),
            languages = parseLanguages(data["supported_languages"].stringOrNull()),
            requirements = parseRequirements(data["pc_requirements"]),
            genres = parseGenres(data["genres"]),
            features = parseFeatures(data["categories"]),
            achievementCount = data["achievements"].objectOrNull()
                ?.get("total")
                .nonNegativeIntOrNull(),
            dlcCount = data["dlc"].arrayOrNull()?.size,
            fetchedAtEpochMs = clock.nowEpochMs(),
        ).sanitizedForPersistence()
    }

    private fun parseMovies(value: JsonElement?): List<GameMovie> = value.arrayOrNull()
        .orEmpty()
        .mapNotNull { element ->
            val movie = element.objectOrNull() ?: return@mapNotNull null
            val streamUrl = sequenceOf("webm", "mp4")
                .mapNotNull { format ->
                    movie[format].objectOrNull()?.get("max").safeMediaUrl()
                }
                .firstOrNull()
                ?: return@mapNotNull null
            GameMovie(
                name = sanitizeSteamText(movie["name"].stringOrNull()),
                previewImageUrl = movie["thumbnail"].safeMediaUrl(),
                streamUrl = streamUrl,
            )
        }
        .distinctBy(GameMovie::streamUrl)

    private fun parseTextList(value: JsonElement?): List<String> = value.arrayOrNull()
        .orEmpty()
        .mapNotNull { sanitizeSteamText(it.stringOrNull()) }
        .distinct()

    private fun parsePlatforms(value: JsonElement?): Set<GamePlatform> {
        val platforms = value.objectOrNull() ?: return emptySet()
        return buildSet {
            if (platforms["windows"].booleanOrNull() == true) add(GamePlatform.WINDOWS)
            if (platforms["mac"].booleanOrNull() == true) add(GamePlatform.MACOS)
            if (platforms["linux"].booleanOrNull() == true) add(GamePlatform.LINUX)
        }
    }

    private fun parseLanguages(raw: String?): List<String> {
        val separated = raw?.replace(BREAK_TAG, ",") ?: return emptyList()
        return sanitizeSteamText(separated)
            ?.split(',')
            .orEmpty()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('*') }
            .distinct()
    }

    private fun parseRequirements(value: JsonElement?): GameRequirements? {
        val requirements = value.objectOrNull() ?: return null
        val result = GameRequirements(
            minimum = sanitizeSteamText(requirements["minimum"].stringOrNull()),
            recommended = sanitizeSteamText(requirements["recommended"].stringOrNull()),
        )
        return result.takeIf { it.minimum != null || it.recommended != null }
    }

    private fun parseGenres(value: JsonElement?): List<MetadataFacet> = value.arrayOrNull()
        .orEmpty()
        .mapNotNull { element ->
            val genre = element.objectOrNull() ?: return@mapNotNull null
            val id = genre["id"].positiveIntOrNull() ?: return@mapNotNull null
            val label = sanitizeSteamText(genre["description"].stringOrNull())
                ?: return@mapNotNull null
            MetadataFacet(id = id, label = label)
        }
        .distinctBy(MetadataFacet::id)

    private fun parseFeatures(value: JsonElement?): List<MetadataFacet> = value.arrayOrNull()
        .orEmpty()
        .mapNotNull { element ->
            val feature = element.objectOrNull() ?: return@mapNotNull null
            val label = sanitizeSteamText(feature["description"].stringOrNull())
                ?: return@mapNotNull null
            MetadataFacet(
                id = feature["id"].nonNegativeIntOrNull(),
                label = label,
            )
        }
        .distinctBy { it.id to it.label }

    private fun JsonElement?.safeMediaUrl(): String? {
        val raw = stringOrNull() ?: return null
        val parsed = raw.toHttpUrlOrNull() ?: return null
        return parsed.toString().takeIf { urlPolicy.isAllowedMediaUrl(parsed) }
    }

    private companion object {
        const val DEFAULT_ENDPOINT = "https://store.steampowered.com/api/appdetails"
        const val MAX_NETWORK_HOPS = 4
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val BREAK_TAG = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

private fun JsonElement?.objectOrNull(): JsonObject? = this as? JsonObject
private fun JsonElement?.arrayOrNull(): JsonArray? = this as? JsonArray
private fun JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
private fun JsonElement?.booleanOrNull(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
private fun JsonElement?.positiveIntOrNull(): Int? =
    (this as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.takeIf { it > 0 }
private fun JsonElement?.nonNegativeIntOrNull(): Int? =
    (this as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
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
