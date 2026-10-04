package app.gamenative.library.metadata

import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalNormalization
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
import okhttp3.ResponseBody

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
    private val retryExecutor: SteamHttpRetryExecutor,
) : SteamCatalogDataSource, SteamCatalogRecordSource {
    @Inject
    constructor() : this(
        client = Net.http.newBuilder().followRedirects(false).followSslRedirects(false).build(),
        apiEndpoint = DEFAULT_ENDPOINT.toHttpUrl(),
        urlPolicy = SteamUrlPolicy(),
        clock = MetadataClock(System::currentTimeMillis),
        retryExecutor = SteamHttpRetryExecutor(),
    )

    override suspend fun fetch(
        trustedSteamAppId: Int,
        locale: MetadataLocale,
    ): CanonicalGameMetadata? = fetchRecord(trustedSteamAppId, locale)?.metadata

    override suspend fun fetchRecord(
        trustedSteamAppId: Int,
        locale: MetadataLocale,
    ): SteamCatalogRecord? {
        require(trustedSteamAppId > 0) { "Trusted Steam identity is invalid" }
        if (!urlPolicy.isAllowedApiRequest(apiEndpoint)) throw SteamCatalogException()
        return try {
            fetchRecordInternal(trustedSteamAppId, locale)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SteamRateLimitExhaustedException) {
            throw error
        } catch (error: SteamCatalogException) {
            throw error
        } catch (_: Exception) {
            throw SteamCatalogException()
        }
    }

    private suspend fun fetchRecordInternal(
        trustedSteamAppId: Int,
        locale: MetadataLocale,
    ): SteamCatalogRecord? {
        val requestUrl = apiEndpoint.newBuilder()
            .setQueryParameter("appids", trustedSteamAppId.toString())
            .setQueryParameter("l", locale.steamLanguage)
            .setQueryParameter("cc", locale.normalizedCountry)
            .build()
        val body = executeValidated(Request.Builder().url(requestUrl).get().build())
        return parseRecord(body, trustedSteamAppId, locale)
    }

    private suspend fun executeValidated(initialRequest: Request): String {
        var request = initialRequest
        repeat(MAX_NETWORK_HOPS) {
            if (!urlPolicy.isAllowedApiRequest(request.url)) throw SteamCatalogException()
            val response = retryExecutor.execute { client.newCall(request).awaitSteamResponse() }
            if (!urlPolicy.isAllowedApiRequest(response.request.url)) {
                response.close()
                throw SteamCatalogException()
            }
            if (response.code in REDIRECT_CODES) {
                val next = response.header("Location")?.let(response.request.url::resolve)
                response.close()
                if (next == null || !urlPolicy.isAllowedApiRequest(next)) {
                    throw SteamCatalogException()
                }
                request = Request.Builder().url(next).get().build()
            } else {
                response.use { finalResponse ->
                    if (!finalResponse.isSuccessful) throw SteamCatalogException()
                    return finalResponse.body.readAppDetailsBoundedUtf8(MAX_RESPONSE_BYTES)
                }
            }
        }
        throw SteamCatalogException()
    }

    private fun parseRecord(body: String, trustedSteamAppId: Int, locale: MetadataLocale): SteamCatalogRecord? {
        val root = JSON.parseToJsonElement(body).objectOrNull() ?: throw SteamCatalogException()
        if (root.size != 1) throw SteamCatalogException()
        val envelope = root[trustedSteamAppId.toString()].objectOrNull()
            ?: throw SteamCatalogException()
        if (envelope["success"].booleanOrNull() != true) return null
        val data = envelope["data"].objectOrNull() ?: return null
        val title = sanitizeSteamText(data["name"].stringOrNull()) ?: return null

        val releaseDate = sanitizeSteamText(
            data["release_date"].objectOrNull()?.get("date").stringOrNull(),
        )
        val metadata = CanonicalGameMetadata(
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
            releaseDate = releaseDate,
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
            languageSupport = parseLanguageSupport(data["supported_languages"].stringOrNull()),
            storePrice = parseStorePrice(data["price_overview"], locale),
            storePackages = parseStorePackages(data["package_groups"]),
            contentRatings = parseContentRatings(data),
            storeLinks = GameStoreLinks(
                website = safeMetadataExternalLink(data["website"].stringOrNull()),
                support = safeMetadataExternalLink(data["support_info"].objectOrNull()?.get("url").stringOrNull()),
                manual = safeMetadataExternalLink(data["manual"].stringOrNull()),
            ),
            isFree = data["is_free"].booleanOrNull(),
        ).sanitizedForPersistence()
        return SteamCatalogRecord(
            steamAppId = trustedSteamAppId,
            appType = parseAppType(data["type"].stringOrNull()),
            releaseYear = parseReleaseYear(releaseDate),
            metadata = metadata,
        )
    }

    private fun parseAppType(rawType: String?): CanonicalAppType = when (rawType) {
        "game" -> CanonicalAppType.GAME
        "application" -> CanonicalAppType.APPLICATION
        "tool" -> CanonicalAppType.TOOL
        "demo" -> CanonicalAppType.DEMO
        "dlc" -> CanonicalAppType.DLC
        "music" -> CanonicalAppType.SOUNDTRACK
        else -> CanonicalAppType.UNKNOWN
    }

    private fun parseReleaseYear(releaseDate: String?): Int? {
        val years = RELEASE_YEAR.findAll(releaseDate.orEmpty())
            .map(MatchResult::value)
            .mapNotNull(CanonicalNormalization::releaseYear)
            .distinct()
            .toList()
        return years.singleOrNull()
    }

    private fun parseMovies(value: JsonElement?): List<GameMovie> = value.arrayOrNull()
        .orEmpty()
        .mapNotNull { element ->
            val movie = element.objectOrNull() ?: return@mapNotNull null
            val streamUrl = movie["hls_h264"].safeMediaUrl()
                ?: sequenceOf("webm", "mp4")
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

    private fun parseLanguages(raw: String?): List<String> = parseLanguageSupport(raw).map(GameLanguageSupport::name)

    private fun parseLanguageSupport(raw: String?): List<GameLanguageSupport> {
        val separated = sanitizeSteamText(raw?.replace(BREAK_TAG, ",")) ?: return emptyList()
        val hasAudioLegend = separated.contains("languages with full audio support", ignoreCase = true)
        return separated.split(',').take(65).mapNotNull { entry ->
            val text = entry.trim()
            if (text.isEmpty() || text.startsWith('*')) return@mapNotNull null
            val name = text.trimEnd('*').trim().take(80).takeIf(String::isNotBlank) ?: return@mapNotNull null
            GameLanguageSupport(name = name, fullAudioSupported = if (hasAudioLegend) text.endsWith('*') else null)
        }.distinctBy(GameLanguageSupport::name).take(64)
    }

    private fun parseStorePrice(value: JsonElement?, locale: MetadataLocale): GameStorePrice? {
        val price = value.objectOrNull() ?: return null
        val currency = price["currency"].stringOrNull()?.takeIf { it.matches(Regex("[A-Z]{3}")) } ?: return null
        val initial = price["initial"].nonNegativeIntOrNull() ?: return null
        val final = price["final"].nonNegativeIntOrNull()?.takeIf { it <= initial } ?: return null
        val discount = price["discount_percent"].nonNegativeIntOrNull()?.takeIf { it <= 100 } ?: return null
        return GameStorePrice(currency, locale.normalizedCountry, initial, final, discount)
    }

    private fun parseStorePackages(value: JsonElement?): List<GameStorePackage> = value.arrayOrNull().orEmpty()
        .take(16).flatMap { it.objectOrNull()?.get("subs").arrayOrNull().orEmpty().take(32) }
        .mapNotNull { element ->
            val sub = element.objectOrNull() ?: return@mapNotNull null
            val id = sub["packageid"].positiveIntOrNull() ?: return@mapNotNull null
            val label = sanitizeSteamText(sub["option_text"].stringOrNull())?.take(256) ?: return@mapNotNull null
            GameStorePackage(id, label, sub["price_in_cents_with_discount"].nonNegativeIntOrNull())
        }.distinctBy(GameStorePackage::packageId).take(32)

    private fun parseContentRatings(data: JsonObject): GameContentRatings {
        val descriptors = data["content_descriptors"].objectOrNull()
        return GameContentRatings(
            requiredAge = data["required_age"].positiveIntOrNull()?.takeIf { it <= 120 },
            criticScore = data["metacritic"].objectOrNull()?.get("score").nonNegativeIntOrNull()?.takeIf { it <= 100 },
            descriptorIds = descriptors?.get("ids").arrayOrNull().orEmpty().mapNotNull { it.positiveIntOrNull() }.distinct().take(32),
            notes = sanitizeSteamText(descriptors?.get("notes").stringOrNull())?.take(8_192),
        )
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
        const val MAX_RESPONSE_BYTES = 4L * 1024L * 1024L
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val BREAK_TAG = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
        val RELEASE_YEAR = Regex("(?<!\\d)\\d{4}(?!\\d)")
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

private fun ResponseBody.readAppDetailsBoundedUtf8(maxBytes: Long): String {
    if (contentLength() > maxBytes) throw SteamCatalogException()
    val source = source()
    if (source.request(maxBytes + 1L)) throw SteamCatalogException()
    return source.readUtf8()
}

internal suspend fun Call.awaitSteamResponse(): Response = suspendCancellableCoroutine { continuation ->
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
