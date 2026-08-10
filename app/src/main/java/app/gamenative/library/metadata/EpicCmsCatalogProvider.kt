package app.gamenative.library.metadata

import app.gamenative.data.canonical.CanonicalNormalization
import app.gamenative.data.canonical.EpicStableSourceId
import app.gamenative.utils.Net
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody

internal data class EpicCmsCatalogRequest(
    val stableSourceId: String,
    val sourceTitle: String,
    val locale: MetadataLocale,
    val productSlug: String? = null,
)

data class EpicCmsCatalogRecord(
    val stableSourceId: String,
    val namespace: String,
    val catalogId: String,
    val slug: String,
    val offerId: String,
    val storeUrl: String,
    val metadata: CanonicalGameMetadata,
)

internal fun interface EpicCmsCatalogSource {
    suspend fun fetch(request: EpicCmsCatalogRequest): EpicCmsCatalogRecord?
}

internal class EpicCmsCatalogException(message: String = "Epic CMS catalog unavailable") :
    IOException(message)

@Singleton
internal class EpicCmsCatalogProvider internal constructor(
    private val client: OkHttpClient,
    private val apiEndpoint: HttpUrl,
    private val urlPolicy: EpicUrlPolicy,
    private val clock: MetadataClock,
    private val retryExecutor: SteamHttpRetryExecutor,
) : EpicCmsCatalogSource {
    @Inject
    constructor() : this(
        client = Net.http.newBuilder().followRedirects(false).followSslRedirects(false).build(),
        apiEndpoint = DEFAULT_ENDPOINT.toHttpUrl(),
        urlPolicy = EpicUrlPolicy(),
        clock = MetadataClock(System::currentTimeMillis),
        retryExecutor = SteamHttpRetryExecutor(),
    )

    override suspend fun fetch(request: EpicCmsCatalogRequest): EpicCmsCatalogRecord? {
        val (namespace, catalogId) = try {
            EpicStableSourceId.decode(request.stableSourceId)
        } catch (_: IllegalArgumentException) {
            throw EpicCmsCatalogException("Epic stable source identity is invalid")
        }
        val slug = request.productSlug?.validatedProductSlug()
            ?: deriveProductSlug(request.sourceTitle)
        val locale = request.locale.normalizedLocale
        val endpoint = apiEndpoint.newBuilder()
            .addPathSegment(locale)
            .addPathSegment("content")
            .addPathSegment("products")
            .addPathSegment(slug)
            .build()
        if (!urlPolicy.isAllowedCmsRequest(endpoint)) {
            throw EpicCmsCatalogException("Epic CMS endpoint is invalid")
        }
        return try {
            val body = execute(endpoint) ?: return null
            parseRecord(
                body = body,
                request = request,
                namespace = namespace,
                catalogId = catalogId,
                locale = locale,
                slug = slug,
            )
        } catch (error: Exception) {
            when (error) {
                is CancellationException,
                is SteamRateLimitExhaustedException,
                is EpicCmsCatalogException -> throw error

                else -> throw EpicCmsCatalogException()
            }
        }
    }

    private suspend fun execute(endpoint: HttpUrl): String? {
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .get()
            .build()
        var finalCall: Call? = null
        val response = retryExecutor.execute {
            client.newCall(request).also { finalCall = it }.awaitSteamResponse()
        }
        val call = requireNotNull(finalCall)
        return response.use { finalResponse ->
            if (
                finalResponse.request.url != endpoint ||
                !urlPolicy.isAllowedCmsRequest(finalResponse.request.url)
            ) {
                throw EpicCmsCatalogException("Epic CMS response endpoint is invalid")
            }
            if (finalResponse.code in REDIRECT_CODES) {
                throw EpicCmsCatalogException("Epic CMS redirects are not accepted")
            }
            if (finalResponse.code == 404) return@use null
            if (!finalResponse.isSuccessful) throw EpicCmsCatalogException()

            val type = finalResponse.body.contentType()
            if (type == null || type.type != "application" || type.subtype != "json") {
                throw EpicCmsCatalogException("Epic CMS response is not JSON")
            }
            finalResponse.readBodyCancellable(call)
        }
    }

    private suspend fun Response.readBodyCancellable(call: Call): String =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            client.dispatcher.executorService.execute {
                try {
                    val body = body.readEpicCmsBoundedUtf8(MAX_RESPONSE_BYTES)
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.success(body))
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(error))
                    }
                }
            }
        }

    private fun parseRecord(
        body: String,
        request: EpicCmsCatalogRequest,
        namespace: String,
        catalogId: String,
        locale: String,
        slug: String,
    ): EpicCmsCatalogRecord {
        val root = JSON.parseToJsonElement(body).requiredObject("root")
        val pages = root["pages"].requiredArray("pages")
        if (pages.size > MAX_PAGES) throw EpicCmsCatalogException("Epic CMS pages exceed their bound")
        val productPages = pages.map { it.requiredObject("page") }
            .filter { it["type"].stringOrNull() == "productHome" }
        if (productPages.size != 1) {
            throw EpicCmsCatalogException("Epic CMS must contain exactly one product page")
        }
        val page = productPages.single()
        if (root["namespace"].stringOrNull() != namespace || page["namespace"].stringOrNull() != namespace) {
            throw EpicCmsCatalogException("Epic CMS namespace conflicts with source identity")
        }
        if (root["_slug"].stringOrNull() != slug) {
            throw EpicCmsCatalogException("Epic CMS slug conflicts with the request")
        }
        validateOptionalLocale(root["_locale"], locale)
        validateOptionalLocale(page["_locale"], locale)

        val expectedTitle = CanonicalNormalization.titleKey(request.sourceTitle)
        val rootTitle = root["productName"].requiredText("root title", MAX_TITLE_CHARS)
        val pageTitle = page["productName"].requiredText("page title", MAX_TITLE_CHARS)
        if (
            expectedTitle.isEmpty() ||
            CanonicalNormalization.titleKey(rootTitle) != expectedTitle ||
            CanonicalNormalization.titleKey(pageTitle) != expectedTitle
        ) {
            throw EpicCmsCatalogException("Epic CMS title conflicts with source identity")
        }

        val offer = page["offer"].requiredObject("offer")
        val offerId = offer["id"].stringOrNull()
        if (
            offer["hasOffer"].booleanOrNull() != true ||
            offer["namespace"].stringOrNull() != namespace ||
            offerId == null ||
            !OFFER_ID.matches(offerId)
        ) {
            throw EpicCmsCatalogException("Epic CMS offer identity is invalid")
        }
        validateCatalogItem(page["item"], namespace, catalogId)

        val data = page["data"].requiredObject("data")
        val about = data["about"].optionalObject("about")
        val hero = data["hero"].optionalObject("hero")
        val media = parseMedia(data["carousel"], locale)
        val requirementsData = data["requirements"].optionalObject("requirements")
        val systems = requirementsData["systems"]?.requiredArray("systems").orEmpty()
        if (systems.size > MAX_SYSTEMS) {
            throw EpicCmsCatalogException("Epic CMS systems exceed their bound")
        }
        val requirements = parseRequirements(systems)
        val metadata = CanonicalGameMetadata(
            title = pageTitle,
            shortDescription = about["shortDescription"].optionalText("short description", MAX_TEXT_CHARS),
            about = about["description"].optionalText("description", MAX_TEXT_CHARS),
            headerImageUrl = hero["backgroundImageUrl"].optionalMediaUrl("hero image"),
            screenshots = media.screenshots,
            movies = media.movies,
            developers = listOfNotNull(about["developerAttribution"].optionalText("developer", MAX_TITLE_CHARS)),
            publishers = listOfNotNull(about["publisherAttribution"].optionalText("publisher", MAX_TITLE_CHARS)),
            releaseDate = parseReleaseDate(data["meta"]),
            platforms = parsePlatforms(systems),
            languages = parseLanguages(requirementsData["languages"]),
            requirements = requirements,
            genres = emptyList(),
            features = emptyList(),
            achievementCount = null,
            dlcCount = null,
            fetchedAtEpochMs = clock.nowEpochMs(),
        )
        return EpicCmsCatalogRecord(
            stableSourceId = request.stableSourceId,
            namespace = namespace,
            catalogId = catalogId,
            slug = slug,
            offerId = offerId,
            storeUrl = "https://store.epicgames.com/$locale/p/$slug",
            metadata = metadata,
        )
    }

    private fun validateCatalogItem(value: JsonElement?, namespace: String, catalogId: String) {
        if (value == null || value is JsonNull) return
        val item = value.requiredObject("item")
        val itemNamespace = item.optionalIdentityText("namespace")
        if (itemNamespace.isNotEmpty() && itemNamespace != namespace) {
            throw EpicCmsCatalogException("Epic CMS item namespace conflicts with source identity")
        }
        val cmsCatalogId = item.optionalIdentityText("catalogId")
        if (cmsCatalogId.isNotEmpty() && cmsCatalogId != catalogId) {
            throw EpicCmsCatalogException("Epic CMS catalog identity conflicts with source identity")
        }
    }

    private fun JsonObject.optionalIdentityText(field: String): String {
        val value = this[field] ?: return ""
        return value.stringOrNull()
            ?: throw EpicCmsCatalogException("Epic CMS item $field must be text")
    }

    private fun parseMedia(value: JsonElement?, locale: String): ParsedMedia {
        val items = value.optionalObject("carousel")["items"]?.requiredArray("carousel items").orEmpty()
        if (items.size > MAX_CAROUSEL_ITEMS) {
            throw EpicCmsCatalogException("Epic CMS carousel exceeds its bound")
        }
        val screenshots = mutableListOf<String>()
        val movies = mutableListOf<GameMovie>()
        for (rawItem in items) {
            val item = rawItem.requiredObject("carousel item")
            val screenshot = item["image"].optionalObject("carousel image")["src"]
                .optionalMediaUrl("carousel image")
            if (screenshot != null && screenshot !in screenshots && screenshots.size < MAX_SCREENSHOTS) {
                screenshots += screenshot
            }

            val movie = parseMovie(item["video"].optionalObject("carousel video")["recipes"], locale)
            if (
                movie != null &&
                movies.none { it.streamUrl == movie.streamUrl } &&
                movies.size < MAX_MOVIES
            ) {
                movies += movie
            }
        }
        return ParsedMedia(screenshots, movies)
    }

    private fun parseMovie(value: JsonElement?, locale: String): GameMovie? {
        val recipesText = value.stringOrNull() ?: return null
        if (recipesText.length > MAX_RECIPE_CHARS) {
            throw EpicCmsCatalogException("Epic CMS movie recipes exceed their bound")
        }
        val root = JSON.parseToJsonElement(recipesText).requiredObject("movie recipes")
        val localeKeys = buildList {
            if (locale in root) add(locale)
            addAll(root.keys.filter { it != locale }.sorted())
        }
        var hls: String? = null
        var dash: String? = null
        var poster: String? = null
        for (key in localeKeys) {
            val recipes = root[key].requiredArray("movie locale recipes")
            if (recipes.size > MAX_RECIPE_ITEMS) {
                throw EpicCmsCatalogException("Epic CMS movie recipes exceed their bound")
            }
            for (rawRecipe in recipes) {
                val outputs = rawRecipe.requiredObject("movie recipe")["outputs"].requiredArray("movie outputs")
                if (outputs.size > MAX_RECIPE_ITEMS) {
                    throw EpicCmsCatalogException("Epic CMS movie outputs exceed their bound")
                }
                for (rawOutput in outputs) {
                    val output = rawOutput.requiredObject("movie output")
                    val outputKey = output["key"].stringOrNull()
                    val contentType = output["contentType"].stringOrNull()?.lowercase(Locale.ROOT)
                    when {
                        outputKey == "manifest" && contentType == "application/x-mpegurl" && hls == null ->
                            hls = output["url"].requiredMediaUrl("movie HLS")
                        outputKey == "manifest" && contentType == "application/dash+xml" && dash == null ->
                            dash = output["url"].requiredMediaUrl("movie DASH")
                        outputKey == "thumbnail" && contentType?.startsWith("image/") == true && poster == null ->
                            poster = output["url"].requiredMediaUrl("movie poster")
                    }
                }
            }
        }
        val stream = hls ?: dash ?: return null
        return GameMovie(name = null, previewImageUrl = poster, streamUrl = stream)
    }

    private fun JsonObject.normalizedSystemType(): String? =
        this["systemType"].stringOrNull()?.trim()?.lowercase(Locale.ROOT)

    private fun parseRequirements(systems: List<JsonElement>): GameRequirements? {
        val minimum = mutableListOf<String>()
        val recommended = mutableListOf<String>()
        for (rawSystem in systems) {
            val system = rawSystem.requiredObject("system")
            if (system.normalizedSystemType() != "windows") continue

            val details = system["details"]?.requiredArray("requirement details").orEmpty()
            if (details.size > MAX_REQUIREMENT_DETAILS) {
                throw EpicCmsCatalogException("Epic CMS requirement details exceed their bound")
            }
            for (rawDetail in details) {
                val detail = rawDetail.requiredObject("requirement detail")
                val title = detail["title"].requiredText("requirement title", MAX_TITLE_CHARS)
                detail["minimum"].optionalText("minimum requirement", MAX_REQUIREMENT_CHARS)
                    ?.let { minimum += "$title: $it" }
                detail["recommended"].optionalText("recommended requirement", MAX_REQUIREMENT_CHARS)
                    ?.let { recommended += "$title: $it" }
            }
        }
        return GameRequirements(
            minimum = minimum.joinToString("\n").takeIf(String::isNotEmpty),
            recommended = recommended.joinToString("\n").takeIf(String::isNotEmpty),
        ).takeIf { it.minimum != null || it.recommended != null }
    }

    private fun parsePlatforms(systems: List<JsonElement>): Set<GamePlatform> = buildSet {
        for (rawSystem in systems) {
            when (rawSystem.requiredObject("system").normalizedSystemType()) {
                "windows" -> add(GamePlatform.WINDOWS)
                "mac", "macos", "mac os" -> add(GamePlatform.MACOS)
                "linux" -> add(GamePlatform.LINUX)
            }
        }
    }

    private fun parseLanguages(value: JsonElement?): List<String> {
        val values = value?.requiredArray("languages").orEmpty()
        if (values.size > MAX_LANGUAGES) throw EpicCmsCatalogException("Epic CMS languages exceed their bound")
        val languages = linkedSetOf<String>()
        for (rawValue in values) {
            val text = rawValue.requiredText("language entry", MAX_LANGUAGE_ENTRY_CHARS)
            for (section in text.split('|')) {
                val separator = section.indexOf(':')
                if (separator <= 0) continue

                val kind = section.substring(0, separator).trim().lowercase(Locale.ROOT)
                if (kind != "audio" && kind != "text") continue

                for (language in section.substring(separator + 1).split(',')) {
                    val name = language.trim()
                    if (name.isNotEmpty()) languages += name
                }
            }
        }
        if (languages.size > MAX_LANGUAGES) throw EpicCmsCatalogException("Epic CMS parsed languages exceed their bound")
        return languages.toList()
    }

    private fun parseReleaseDate(value: JsonElement?): String? {
        val raw = value.optionalObject("meta")["customReleaseDate"].stringOrNull()?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (raw.length > MAX_RELEASE_LABEL_CHARS) {
            throw EpicCmsCatalogException("Epic CMS release label exceeds its bound")
        }
        for (formatter in RELEASE_FORMATS) {
            try {
                return LocalDate.parse(raw, formatter).toString()
            } catch (_: DateTimeParseException) {
            }
        }
        return null
    }

    private fun JsonElement?.optionalMediaUrl(label: String): String? {
        if (this == null) return null
        val raw = stringOrNull() ?: throw EpicCmsCatalogException("Epic CMS $label media URL is invalid")
        if (raw.isBlank()) return null
        return raw.validatedMediaUrl(label)
    }

    private fun JsonElement?.requiredMediaUrl(label: String): String =
        optionalMediaUrl(label) ?: throw EpicCmsCatalogException("Epic CMS $label media URL is missing")

    private fun String.validatedMediaUrl(label: String): String {
        if (length > MAX_MEDIA_URL_CHARS) throw EpicCmsCatalogException("Epic CMS $label media URL is invalid")
        val parsed = toHttpUrlOrNull()
            ?: throw EpicCmsCatalogException("Epic CMS $label media URL is invalid")
        if (!urlPolicy.isAllowedMediaUrl(parsed)) {
            throw EpicCmsCatalogException("Epic CMS $label media URL is not validated Epic media")
        }
        return parsed.toString()
    }

    private data class ParsedMedia(
        val screenshots: List<String>,
        val movies: List<GameMovie>,
    )

    private companion object {
        const val DEFAULT_ENDPOINT = "https://store-content.ak.epicgames.com/api/"
        const val MAX_RESPONSE_BYTES = 1L * 1024L * 1024L
        const val MAX_PAGES = 100
        const val MAX_CAROUSEL_ITEMS = 100
        const val MAX_SCREENSHOTS = 20
        const val MAX_MOVIES = 10
        const val MAX_RECIPE_ITEMS = 50
        const val MAX_SYSTEMS = 20
        const val MAX_REQUIREMENT_DETAILS = 50
        const val MAX_LANGUAGES = 64
        const val MAX_TITLE_CHARS = 512
        const val MAX_TEXT_CHARS = 50_000
        const val MAX_REQUIREMENT_CHARS = 5_000
        const val MAX_LANGUAGE_ENTRY_CHARS = 10_000
        const val MAX_RECIPE_CHARS = 300_000
        const val MAX_RELEASE_LABEL_CHARS = 128
        const val MAX_MEDIA_URL_CHARS = 2_048
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val OFFER_ID = Regex("[0-9a-f]{32}")
        val JSON = Json { ignoreUnknownKeys = true }
        val RELEASE_FORMATS = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.US).withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.US).withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.US).withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d MMM uuuu", Locale.US).withResolverStyle(ResolverStyle.STRICT),
        )
    }
}

private fun String.validatedProductSlug(): String {
    if (length > 160 || !EpicUrlPolicy.PRODUCT_SLUG.matches(this)) {
        throw EpicCmsCatalogException("Epic product slug is invalid")
    }
    return this
}

private fun deriveProductSlug(title: String): String = CanonicalNormalization.titleKey(title)
    .split(' ')
    .filter { token -> token.isNotEmpty() && token.all { it.code < 128 && it.isLetterOrDigit() } }
    .joinToString("-")
    .takeIf(String::isNotEmpty)
    ?.validatedProductSlug()
    ?: throw EpicCmsCatalogException("Epic source title cannot produce a product slug")

private fun JsonElement?.requiredObject(label: String): JsonObject =
    this as? JsonObject ?: throw EpicCmsCatalogException("Epic CMS $label must be an object")

private fun JsonElement?.optionalObject(label: String): JsonObject = when (this) {
    null -> JsonObject(emptyMap())
    else -> requiredObject(label)
}

private fun JsonElement?.requiredArray(label: String): JsonArray =
    this as? JsonArray ?: throw EpicCmsCatalogException("Epic CMS $label must be a list")

private fun JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

private fun JsonElement?.booleanOrNull(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

private fun JsonElement?.requiredText(label: String, maxChars: Int): String =
    optionalText(label, maxChars)
        ?: throw EpicCmsCatalogException("Epic CMS $label must be nonblank")

private fun JsonElement?.optionalText(label: String, maxChars: Int): String? {
    if (this == null) return null
    val raw = stringOrNull() ?: throw EpicCmsCatalogException("Epic CMS $label must be text")
    if (raw.length > maxChars) throw EpicCmsCatalogException("Epic CMS $label exceeds its bound")
    return sanitizeSteamText(raw)
}

private fun validateOptionalLocale(value: JsonElement?, expected: String) {
    if (value != null && value.stringOrNull() != expected) {
        throw EpicCmsCatalogException("Epic CMS locale conflicts with the request")
    }
}

private fun ResponseBody.readEpicCmsBoundedUtf8(maxBytes: Long): String {
    if (contentLength() > maxBytes) throw EpicCmsCatalogException("Epic CMS body exceeds its bound")
    val source = source()
    if (source.request(maxBytes + 1L)) throw EpicCmsCatalogException("Epic CMS body exceeds its bound")
    return source.readUtf8()
}
