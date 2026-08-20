package app.gamenative.library.metadata

import app.gamenative.utils.Net
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody

internal data class PcGamingWikiCurrentAvailabilityRequest(
    val sourceTitle: String,
    val sourceReleaseYear: Int?,
    val sourceDeveloper: String?,
    val sourcePublisher: String?,
)

enum class PcGamingWikiAvailabilityLabel {
    PCGW_CURRENT_EGS_ACCOUNT_REQUIRED,
}

data class PcGamingWikiCurrentAvailabilityEvidence(
    val sourceRevision: Long,
    val label: PcGamingWikiAvailabilityLabel =
        PcGamingWikiAvailabilityLabel.PCGW_CURRENT_EGS_ACCOUNT_REQUIRED,
    val futureSteamAvailability: Boolean = false,
) {
    init {
        require(sourceRevision > 0L) { "PCGamingWiki source revision must be positive" }
    }
}

internal sealed interface PcGamingWikiCurrentAvailabilityResult {
    data class Confirmed(
        val evidence: PcGamingWikiCurrentAvailabilityEvidence,
    ) : PcGamingWikiCurrentAvailabilityResult

    data object NotConfirmed : PcGamingWikiCurrentAvailabilityResult
    data object Unavailable : PcGamingWikiCurrentAvailabilityResult
}

internal fun interface PcGamingWikiCurrentAvailabilitySource {
    suspend fun check(
        request: PcGamingWikiCurrentAvailabilityRequest,
    ): PcGamingWikiCurrentAvailabilityResult
}

internal class PcGamingWikiUrlPolicy internal constructor(
    private val allowedHost: String = PCGW_HOST,
    private val requireHttps: Boolean = true,
    private val allowedPort: Int = 443,
) {
    fun isAllowedApiRequest(url: HttpUrl): Boolean =
        (!requireHttps || url.isHttps) &&
            url.host == allowedHost &&
            url.port == allowedPort &&
            url.username.isEmpty() &&
            url.password.isEmpty() &&
            url.encodedPath == API_PATH &&
            url.fragment == null

    private companion object {
        const val PCGW_HOST = "www.pcgamingwiki.com"
        const val API_PATH = "/w/api.php"
    }
}

@Singleton
internal class PcGamingWikiCurrentAvailabilityProvider internal constructor(
    private val client: OkHttpClient,
    private val apiEndpoint: HttpUrl,
    private val urlPolicy: PcGamingWikiUrlPolicy,
    private val clock: MetadataClock,
    private val retryExecutor: SteamHttpRetryExecutor,
) : PcGamingWikiCurrentAvailabilitySource {
    @Inject
    constructor() : this(
        client = Net.http.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .callTimeout(20, TimeUnit.SECONDS)
            .build(),
        apiEndpoint = DEFAULT_ENDPOINT.toHttpUrl(),
        urlPolicy = PcGamingWikiUrlPolicy(),
        clock = MetadataClock(System::currentTimeMillis),
        retryExecutor = SteamHttpRetryExecutor(),
    )

    private val refreshMutex = Mutex()

    @Volatile
    private var cachedSnapshot: AvailabilitySnapshot? = null

    override suspend fun check(
        request: PcGamingWikiCurrentAvailabilityRequest,
    ): PcGamingWikiCurrentAvailabilityResult {
        if (!request.isBounded()) return PcGamingWikiCurrentAvailabilityResult.Unavailable
        val snapshot = try {
            currentSnapshot()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return PcGamingWikiCurrentAvailabilityResult.Unavailable
        }
        return snapshot.match(request)
    }

    private suspend fun currentSnapshot(): AvailabilitySnapshot {
        val now = clock.nowEpochMs()
        cachedSnapshot?.takeIf { it.isFresh(now) }?.let { return it }
        return refreshMutex.withLock {
            val refreshedAt = clock.nowEpochMs()
            cachedSnapshot?.takeIf { it.isFresh(refreshedAt) }?.let { return@withLock it }
            fetchSnapshot(refreshedAt).also { cachedSnapshot = it }
        }
    }

    private suspend fun fetchSnapshot(fetchedAtEpochMs: Long): AvailabilitySnapshot {
        if (!urlPolicy.isAllowedApiRequest(apiEndpoint)) throw PcGamingWikiAvailabilityException()
        val revision = fetchRevision()
        val rows = fetchRows()
        return AvailabilitySnapshot(
            sourceRevision = revision,
            fetchedAtEpochMs = fetchedAtEpochMs,
            rows = rows,
        )
    }

    private suspend fun fetchRevision(): Long {
        val url = apiEndpoint.newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .addQueryParameter("prop", "revisions")
            .addQueryParameter("titles", LIST_PAGE)
            .addQueryParameter("rvprop", "ids")
            .addQueryParameter("rvlimit", "1")
            .build()
        val body = executeJson(
            Request.Builder()
                .url(url)
                .safeHeaders()
                .get()
                .build(),
        )
        return parseRevision(body)
    }

    private suspend fun fetchRows(): List<AvailabilityRow> {
        val form = FormBody.Builder()
            .add("action", "cargoquery")
            .add("format", "json")
            .add("tables", CARGO_TABLES)
            .add("join_on", CARGO_JOIN)
            .add("limit", MAX_ROWS.toString())
            .add("fields", CARGO_FIELDS)
            .add("where", cargoWherePredicate())
            .build()
        val body = executeJson(
            Request.Builder()
                .url(apiEndpoint)
                .safeHeaders()
                .post(form)
                .build(),
        )
        return parseRows(body)
    }

    private suspend fun executeJson(request: Request): String {
        if (!urlPolicy.isAllowedApiRequest(request.url)) throw PcGamingWikiAvailabilityException()
        var finalCall: Call? = null
        val response = retryExecutor.execute {
            client.newCall(request).also { call -> finalCall = call }.awaitSteamResponse()
        }
        return readValidatedJson(requireNotNull(finalCall), response)
    }

    private suspend fun readValidatedJson(
        call: Call,
        response: Response,
    ): String = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            call.cancel()
        }
        try {
            client.dispatcher.executorService.execute {
                val result = runCatching {
                    response.use { finalResponse ->
                        if (
                            !urlPolicy.isAllowedApiRequest(finalResponse.request.url) ||
                            finalResponse.code in REDIRECT_CODES ||
                            !finalResponse.isSuccessful
                        ) {
                            throw PcGamingWikiAvailabilityException()
                        }
                        val body = finalResponse.body
                        val contentType = body.contentType()
                        if (contentType?.type != "application" || contentType.subtype != "json") {
                            throw PcGamingWikiAvailabilityException()
                        }
                        body.readPcGamingWikiBoundedUtf8(MAX_RESPONSE_BYTES)
                    }
                }
                if (continuation.isActive) {
                    continuation.resumeWith(result)
                }
            }
        } catch (error: Exception) {
            response.close()
            if (continuation.isActive) {
                continuation.resumeWith(Result.failure(error))
            }
        }
    }

    private fun parseRevision(body: String): Long {
        val root = JSON.parseToJsonElement(body).objectOrThrow()
        if (root["error"] != null) throw PcGamingWikiAvailabilityException()
        val pages = root["query"].objectOrThrow()["pages"].arrayOrThrow()
        if (pages.size != 1) throw PcGamingWikiAvailabilityException()
        val page = pages.single().objectOrThrow()
        if (page["title"].requiredBoundedString(MAX_PAGE_CHARS) != LIST_PAGE) {
            throw PcGamingWikiAvailabilityException()
        }
        val revisions = page["revisions"].arrayOrThrow()
        if (revisions.size != 1) throw PcGamingWikiAvailabilityException()
        return revisions.single().objectOrThrow()["revid"].positiveLongOrThrow()
    }

    private fun parseRows(body: String): List<AvailabilityRow> {
        val root = JSON.parseToJsonElement(body).objectOrThrow()
        if (root["continue"] != null || root["error"] != null) {
            throw PcGamingWikiAvailabilityException()
        }
        val rows = root["cargoquery"].arrayOrThrow()
        if (rows.size > MAX_ROWS) throw PcGamingWikiAvailabilityException()
        return rows.map { envelope ->
            val values = envelope.objectOrThrow()["title"].objectOrThrow()
            AvailabilityRow(
                title = values["Page"].requiredBoundedString(MAX_PAGE_CHARS),
                series = values["Series"].optionalBoundedString(MAX_FIELD_CHARS),
                developers = values["Developers"].optionalBoundedString(MAX_FIELD_CHARS),
                publishers = values["Publishers"].optionalBoundedString(MAX_FIELD_CHARS),
                released = values["Released"].optionalBoundedString(MAX_FIELD_CHARS),
                availableOn = values["AvailableOn"].optionalBoundedString(MAX_FIELD_CHARS),
                futureStores = values["FutureStores"].optionalBoundedString(MAX_FIELD_CHARS),
            )
        }
    }

    private fun AvailabilitySnapshot.match(
        request: PcGamingWikiCurrentAvailabilityRequest,
    ): PcGamingWikiCurrentAvailabilityResult {
        val requestTitle = normalizePcGamingWikiTitle(request.sourceTitle)
        if (requestTitle.isEmpty()) return PcGamingWikiCurrentAvailabilityResult.Unavailable
        val sourceDeveloper = normalizeCompany(request.sourceDeveloper)
        val sourcePublisher = normalizeCompany(request.sourcePublisher)
        val plausible = rows.filter { row ->
            if (normalizePcGamingWikiTitle(row.title) != requestTitle) return@filter false
            val releaseYears = row.releaseYears()
            val suppliedYear = request.sourceReleaseYear
            if (suppliedYear != null && releaseYears.isNotEmpty() && suppliedYear !in releaseYears) {
                return@filter false
            }
            val yearCorroborated = suppliedYear != null && suppliedYear in releaseYears
            val developerCorroborated = sourceDeveloper != null &&
                sourceDeveloper in row.companyKeys(row.developers)
            val publisherCorroborated = sourcePublisher != null &&
                sourcePublisher in row.companyKeys(row.publishers)
            yearCorroborated || developerCorroborated || publisherCorroborated
        }
        val matched = plausible.singleOrNull()
            ?: return PcGamingWikiCurrentAvailabilityResult.NotConfirmed
        return PcGamingWikiCurrentAvailabilityResult.Confirmed(
            PcGamingWikiCurrentAvailabilityEvidence(
                sourceRevision = sourceRevision,
                futureSteamAvailability = matched.hasFutureSteamAvailability(),
            ),
        )
    }

    private fun PcGamingWikiCurrentAvailabilityRequest.isBounded(): Boolean =
        sourceTitle.isNotBlank() &&
            sourceTitle.length <= MAX_PAGE_CHARS &&
            sourceReleaseYear?.let { it in MIN_RELEASE_YEAR..MAX_RELEASE_YEAR } != false &&
            sourceDeveloper.isBoundedOptionalField() &&
            sourcePublisher.isBoundedOptionalField()

    private fun String?.isBoundedOptionalField(): Boolean = this == null || length <= MAX_FIELD_CHARS

    private fun AvailabilitySnapshot.isFresh(nowEpochMs: Long): Boolean =
        nowEpochMs >= fetchedAtEpochMs && nowEpochMs - fetchedAtEpochMs < CACHE_TTL_MS

    private fun AvailabilityRow.releaseYears(): Set<Int> = RELEASE_YEAR.findAll(released.orEmpty())
        .mapNotNull { match -> match.value.toIntOrNull() }
        .filter { year -> year in MIN_RELEASE_YEAR..MAX_RELEASE_YEAR }
        .toSet()

    private fun AvailabilityRow.companyKeys(raw: String?): Set<String> = raw.orEmpty()
        .split(',')
        .asSequence()
        .map { company -> company.removePrefix("Company:") }
        .mapNotNull(::normalizeCompany)
        .take(MAX_COMPANIES_PER_FIELD)
        .toSet()

    private fun AvailabilityRow.hasFutureSteamAvailability(): Boolean = futureStores.orEmpty()
        .split(',', ';')
        .asSequence()
        .map(String::trim)
        .any { store -> store.equals("Steam", ignoreCase = true) }

    private fun normalizeCompany(raw: String?): String? = raw
        ?.takeIf(String::isNotBlank)
        ?.let(::normalizePlainText)
        ?.takeIf(String::isNotBlank)

    private fun normalizePcGamingWikiTitle(raw: String): String = normalizePlainText(raw)
        .split(' ')
        .asSequence()
        .filter(String::isNotEmpty)
        .take(MAX_TITLE_TOKENS)
        .map { token -> ROMAN_TITLE_TOKENS[token] ?: token }
        .joinToString(" ")

    private fun normalizePlainText(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFKD)
        .lowercase(Locale.ROOT)
        .replace(COMBINING_MARKS, "")
        .replace(NON_ALPHANUMERIC, " ")
        .trim()
        .replace(WHITESPACE, " ")

    private fun Request.Builder.safeHeaders(): Request.Builder = this
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/json")
        .removeHeader("Authorization")
        .removeHeader("Cookie")

    private fun cargoWherePredicate(): String = buildString {
        append("Availability.Available_from HOLDS 'Epic Games Store'")
        ALTERNATIVE_STORE_DRM_FIELDS.forEach { (store, drmField) ->
            append("\nAND NOT (Availability.Available_from HOLDS '")
            append(store)
            append("' AND Availability.")
            append(drmField)
            append(" HOLDS NOT 'Epic Games Launcher')")
        }
    }

    private data class AvailabilitySnapshot(
        val sourceRevision: Long,
        val fetchedAtEpochMs: Long,
        val rows: List<AvailabilityRow>,
    )

    private data class AvailabilityRow(
        val title: String,
        val series: String?,
        val developers: String?,
        val publishers: String?,
        val released: String?,
        val availableOn: String?,
        val futureStores: String?,
    )

    private companion object {
        const val DEFAULT_ENDPOINT = "https://www.pcgamingwiki.com:443/w/api.php"
        const val LIST_PAGE = "List of games exclusive to Epic Games Store"
        const val USER_AGENT = "GameNative/1.0 (https://github.com/Darkaxt/GameNative)"
        const val CARGO_TABLES = "Infobox_game,Availability"
        const val CARGO_JOIN = "Infobox_game._pageID=Availability._pageID"
        const val CARGO_FIELDS = "Infobox_game._pageName=Page," +
            "Infobox_game.Series=Series," +
            "Infobox_game.Developers=Developers," +
            "Infobox_game.Publishers=Publishers," +
            "Infobox_game.Released=Released," +
            "Infobox_game.Available_on=AvailableOn," +
            "Availability.Available_from_future=FutureStores"
        const val MAX_ROWS = 50
        const val MAX_PAGE_CHARS = 256
        const val MAX_FIELD_CHARS = 4_096
        const val MAX_TITLE_TOKENS = 48
        const val MAX_COMPANIES_PER_FIELD = 32
        const val MAX_RESPONSE_BYTES = 512L * 1_024L
        const val MIN_RELEASE_YEAR = 1970
        const val MAX_RELEASE_YEAR = 2100
        val CACHE_TTL_MS = TimeUnit.HOURS.toMillis(12)
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val RELEASE_YEAR = Regex("(?<!\\d)\\d{4}(?!\\d)")
        val COMBINING_MARKS = Regex("\\p{M}+")
        val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
        val WHITESPACE = Regex("\\s+")
        val ROMAN_TITLE_TOKENS = mapOf(
            "ii" to "2",
            "iii" to "3",
            "iv" to "4",
            "v" to "5",
            "vi" to "6",
            "vii" to "7",
            "viii" to "8",
            "ix" to "9",
            "x" to "10",
            "xi" to "11",
            "xii" to "12",
        )
        val ALTERNATIVE_STORE_DRM_FIELDS = listOf(
            "Amazon DE" to "Amazon_DE_DRM",
            "Amazon ES" to "Amazon_ES_DRM",
            "Amazon FR" to "Amazon_FR_DRM",
            "Amazon IT" to "Amazon_IT_DRM",
            "Amazon JP" to "Amazon_JP_DRM",
            "Amazon UK" to "Amazon_UK_DRM",
            "Amazon US" to "Amazon_US_DRM",
            "Battle.net" to "Battlenet_DRM",
            "Developer Website" to "Developer_website_DRM",
            "EA app" to "EA_app_DRM",
            "GamersGate" to "GamersGate_DRM",
            "Gamesplanet" to "Gamesplanet_DRM",
            "GOG.com" to "GOGcom_DRM",
            "Green Man Gaming" to "Green_Man_Gaming_DRM",
            "Humble Store" to "Humble_Store_DRM",
            "itch.io" to "itchio_DRM",
            "Mac App Store" to "Mac_App_Store_DRM",
            "Meta Store" to "Meta_Store_DRM",
            "Microsoft Store" to "Microsoft_Store_DRM",
            "Official website" to "Official_website_DRM",
            "Publisher website" to "Publisher_website_DRM",
            "Retail" to "Retail_DRM",
            "Steam" to "Steam_DRM",
            "Ubisoft Store" to "Ubisoft_Store_DRM",
            "Viveport" to "Viveport_DRM",
            "Zoom Platform" to "Zoom_Platform_DRM",
        )
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

private class PcGamingWikiAvailabilityException :
    IOException("PCGamingWiki current availability unavailable")

private fun JsonElement?.objectOrThrow(): JsonObject =
    this as? JsonObject ?: throw PcGamingWikiAvailabilityException()

private fun JsonElement?.arrayOrThrow(): JsonArray =
    this as? JsonArray ?: throw PcGamingWikiAvailabilityException()

private fun JsonElement?.positiveLongOrThrow(): Long =
    (this as? JsonPrimitive)?.longOrNull?.takeIf { it > 0L }
        ?: throw PcGamingWikiAvailabilityException()

private fun JsonElement?.requiredBoundedString(maxChars: Int): String =
    optionalBoundedString(maxChars)?.takeIf(String::isNotBlank)
        ?: throw PcGamingWikiAvailabilityException()

private fun JsonElement?.optionalBoundedString(maxChars: Int): String? {
    if (this == null || this === JsonNull) return null
    val primitive = this as? JsonPrimitive ?: throw PcGamingWikiAvailabilityException()
    if (!primitive.isString) throw PcGamingWikiAvailabilityException()
    val raw = primitive.contentOrNull ?: throw PcGamingWikiAvailabilityException()
    if (raw.length > maxChars) throw PcGamingWikiAvailabilityException()
    return raw.trim().takeIf(String::isNotEmpty)
}

private fun ResponseBody.readPcGamingWikiBoundedUtf8(maxBytes: Long): String {
    if (contentLength() > maxBytes) throw PcGamingWikiAvailabilityException()
    val source = source()
    if (source.request(maxBytes + 1L)) throw PcGamingWikiAvailabilityException()
    return source.readUtf8()
}
