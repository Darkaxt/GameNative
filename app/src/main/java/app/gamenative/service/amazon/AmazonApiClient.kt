package app.gamenative.service.amazon

import app.gamenative.data.AmazonGame
import app.gamenative.utils.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.net.URL
import java.security.MessageDigest

/** Low-level client for Amazon Gaming distribution APIs. */
object AmazonApiClient {

    private const val ENTITLEMENTS_URL =
        "https://gaming.amazon.com/api/distribution/entitlements"

    private const val DISTRIBUTION_URL =
        "https://gaming.amazon.com/api/distribution/v2/public"

    private const val GET_ENTITLEMENTS_TARGET =
        "com.amazon.animusdistributionservice.entitlement.AnimusEntitlementsService.GetEntitlements"

    private const val GET_GAME_DOWNLOAD_TARGET =
        "com.amazon.animusdistributionservice.external.AnimusDistributionService.GetGameDownload"

    private const val GET_LIVE_VERSION_IDS_TARGET =
        "com.amazon.animusdistributionservice.external.AnimusDistributionService.GetLiveVersionIds"


    /** Result of a `GetGameDownload` call. */
    data class GameDownloadSpec(
        val downloadUrl: String,
        val versionId: String,
    )

    // ── Public API ───────────────────────────────────────────────────────────

    data class EntitlementsPage(
        val games: List<AmazonGame>,
        val nextToken: String?,
    )

    internal fun parseEntitlementsPage(
        responseJson: JSONObject,
        seenNextTokens: Set<String>,
    ): Result<EntitlementsPage> = runCatching {
        val entitlements = responseJson.optJSONArray("entitlements")
            ?: error("MALFORMED_AMAZON_PAGE")
        val games = buildList {
            for (index in 0 until entitlements.length()) {
                val entitlement = entitlements.optJSONObject(index)
                    ?: error("MALFORMED_AMAZON_PAGE")
                add(parseEntitlement(entitlement) ?: error("MALFORMED_AMAZON_PAGE"))
            }
        }
        val nextToken = if (!responseJson.has("nextToken") || responseJson.isNull("nextToken")) {
            null
        } else {
            val rawToken = responseJson.get("nextToken")
            require(rawToken is String && rawToken.isNotBlank() && rawToken !in seenNextTokens) {
                "MALFORMED_AMAZON_PAGE"
            }
            rawToken
        }
        EntitlementsPage(games = games, nextToken = nextToken)
    }

    /** Fetch all owned-game entitlements for the authenticated user. */
    suspend fun getEntitlements(
        bearerToken: String,
        deviceSerial: String,
    ): Result<List<AmazonGame>> = withContext(Dispatchers.IO) {
        val games = mutableMapOf<String, AmazonGame>()
        val hardwareHash = sha256Upper(deviceSerial)
        val seenNextTokens = mutableSetOf<String>()
        var nextToken: String? = null

        do {
            val requestBody = buildGetEntitlementsRequestBody(nextToken, hardwareHash)
            val responseJson = postJson(
                url = ENTITLEMENTS_URL,
                target = GET_ENTITLEMENTS_TARGET,
                bearerToken = bearerToken,
                body = requestBody,
            ) ?: return@withContext Result.failure(IllegalStateException("AMAZON_PAGE_FAILED"))
            val page = parseEntitlementsPage(responseJson, seenNextTokens).getOrElse {
                return@withContext Result.failure(IllegalStateException("MALFORMED_AMAZON_PAGE"))
            }
            for (game in page.games) {
                if (games.putIfAbsent(game.productId, game) != null) {
                    return@withContext Result.failure(
                        IllegalStateException("DUPLICATE_AMAZON_PRODUCT"),
                    )
                }
            }
            nextToken = page.nextToken
            if (nextToken != null) seenNextTokens += nextToken
        } while (nextToken != null)

        Result.success(games.values.toList())
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun buildGetEntitlementsRequestBody(nextToken: String?, hardwareHash: String): JSONObject =
        JSONObject().apply {
            put("Operation", "GetEntitlements")
            put("clientId", "Sonic")
            put("syncPoint", JSONObject.NULL)
            put("nextToken", if (nextToken != null) nextToken else JSONObject.NULL)
            put("maxResults", 50)
            put("productIdFilter", JSONObject.NULL)
            put("keyId", AmazonConstants.GAMING_KEY_ID)
            put("hardwareHash", hardwareHash)
        }

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    private fun postJson(
        url: String,
        target: String,
        bearerToken: String,
        body: JSONObject,
    ): JSONObject? {
        return try {
            val requestBody = body.toString().toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .header("X-Amz-Target", target)
                .header("x-amzn-token", bearerToken)
                .header("User-Agent", AmazonConstants.GAMING_USER_AGENT)
                .header("Content-Type", "application/json")
                .header("Content-Encoding", "amz-1.0")
                .build()

            Net.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.e("[Amazon] Request failed: HTTP ${response.code}")
                    return null
                }

                val responseText = response.body?.string() ?: return null
                JSONObject(responseText)
            }
        } catch (e: Exception) {
            Timber.e("[Amazon] Request failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Parse one entitlement JSON object into an [AmazonGame]. */
    private fun parseEntitlement(entitlement: JSONObject): AmazonGame? {
        val product = entitlement.optJSONObject("product") ?: return null
        val productId = product.opt("id") as? String ?: return null
        if (productId.isBlank() || productId != productId.trim()) return null
        val title = product.optString("title", "")
        val purchasedDate = entitlement.optString("purchasedDate", "")

        // Top-level entitlement UUID  — needed for GetGameDownload, NOT the product ID
        val entitlementId = entitlement.opt("id") as? String ?: return null
        if (entitlementId.isBlank() || entitlementId != entitlementId.trim()) return null

        // productDetail sits between product and details:
        // product -> productDetail -> details
        //                         -> iconUrl  (box art lives here, NOT inside details)
        val productDetail = product.optJSONObject("productDetail")
        val details = productDetail?.optJSONObject("details")

        val developer = details?.optString("developer", "") ?: ""
        val publisher = details?.optString("publisher", "") ?: ""
        val releaseDate = details?.optString("releaseDate", "") ?: ""
        val downloadSize = details?.optLong("fileSize", 0L) ?: 0L

        val artUrl = resolveArtUrl(productDetail, details)
        val heroUrl = AmazonArtwork.resolveAppHeroUrl(details)
        val productSku = product.optString("sku", "")

        return AmazonGame(
            // appId = 0 (auto-generated by Room when inserting)
            productId = productId,
            entitlementId = entitlementId,
            title = title,
            artUrl = artUrl,
            heroUrl = heroUrl,
            purchasedDate = purchasedDate,
            developer = developer,
            publisher = publisher,
            releaseDate = releaseDate,
            downloadSize = downloadSize,
            productSku = productSku,
            productJson = product.toString(),
        )
    }

    /** Resolve primary artwork URL. */
    private fun resolveArtUrl(productDetail: JSONObject?, details: JSONObject?): String {
        // Primary: iconUrl lives directly on productDetail, NOT inside details
        val iconUrl = productDetail?.optString("iconUrl", "") ?: ""
        if (iconUrl.isNotEmpty()) return iconUrl

        // Fallback: transparent logo PNG inside details
        val logoUrl = details?.optString("logoUrl", "") ?: ""
        if (logoUrl.isNotEmpty()) return logoUrl

        return ""
    }

    /** SHA-256 of [input], hex-encoded in uppercase. */
    private fun sha256Upper(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }.uppercase()
    }

    // ── Download API ─────────────────────────────────────────────────────────────────────────────

    /** Fetch the download manifest spec for a game. */
    suspend fun fetchGameDownload(
        entitlementId: String,
        bearerToken: String,
    ): GameDownloadSpec? = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("entitlementId", entitlementId)
            put("Operation", "GetGameDownload")
        }

        Timber.tag("Amazon").d("fetchGameDownload: entitlementId=$entitlementId")

        val response = postJson(
            url = DISTRIBUTION_URL,
            target = GET_GAME_DOWNLOAD_TARGET,
            bearerToken = bearerToken,
            body = body,
        ) ?: return@withContext null

        val downloadUrl = response.optString("downloadUrl", "").ifEmpty {
            Timber.e("[Amazon] GetGameDownload: missing downloadUrl in response: ${response.toString().take(500)}")
            return@withContext null
        }
        val versionId = response.optString("versionId", "")
        Timber.i("[Amazon] GetGameDownload: versionId=$versionId url=$downloadUrl")
        GameDownloadSpec(downloadUrl = downloadUrl, versionId = versionId)
    }

    // ── Live version checking ───────────────────────────────────────────────────────

    /** Fetch live version IDs for product IDs. */
    suspend fun fetchLiveVersionIds(
        adgProductIds: List<String>,
        bearerToken: String,
    ): Map<String, String>? = withContext(Dispatchers.IO) {
        if (adgProductIds.isEmpty()) return@withContext emptyMap()

        val idsArray = org.json.JSONArray(adgProductIds)
        val body = JSONObject().apply {
            put("adgProductIds", idsArray)
            put("Operation", "GetLiveVersionIds")
        }

        Timber.tag("Amazon").d("fetchLiveVersionIds: ${adgProductIds.size} product(s)")

        val response = postJson(
            url = DISTRIBUTION_URL,
            target = GET_LIVE_VERSION_IDS_TARGET,
            bearerToken = bearerToken,
            body = body,
        ) ?: return@withContext null

        // Response shape: { "adgProductIdToVersionIdMap": { "productId1": "versionId1", ... } }
        val versions = response.optJSONObject("adgProductIdToVersionIdMap")
        if (versions == null) {
            Timber.tag("Amazon").w("GetLiveVersionIds: no 'adgProductIdToVersionIdMap' in response: ${response.toString().take(500)}")
            return@withContext null
        }

        val result = mutableMapOf<String, String>()
        for (key in versions.keys()) {
            result[key] = versions.optString(key, "")
        }
        Timber.tag("Amazon").i("GetLiveVersionIds: ${result.size} version(s) returned")
        result
    }

    /** Check whether a game has an update available. */
    suspend fun isUpdateAvailable(
        productId: String,
        storedVersionId: String,
        bearerToken: String,
    ): Boolean? = withContext(Dispatchers.IO) {
        val liveVersions = fetchLiveVersionIds(listOf(productId), bearerToken)
            ?: return@withContext null
        val liveVersion = liveVersions[productId]
        if (liveVersion.isNullOrEmpty()) {
            Timber.tag("Amazon").w("isUpdateAvailable: no live version returned for $productId")
            return@withContext null
        }
        val updateAvailable = liveVersion != storedVersionId
        Timber.tag("Amazon").i(
            "isUpdateAvailable: productId=$productId stored=$storedVersionId live=$liveVersion update=$updateAvailable"
        )
        updateAvailable
    }

    // ── Download size pre-fetch ──────────────────────────────────────────────────────────────

    /** Fetch total download size by downloading and parsing the manifest. */
    suspend fun fetchDownloadSize(
        entitlementId: String,
        bearerToken: String,
    ): Long? = withContext(Dispatchers.IO) {
        Timber.tag("Amazon").d("fetchDownloadSize: entitlementId=$entitlementId")

        val spec = fetchGameDownload(entitlementId, bearerToken) ?: run {
            Timber.tag("Amazon").w("fetchDownloadSize: failed to get download spec")
            return@withContext null
        }

        val manifestUrl = appendPath(spec.downloadUrl, "manifest.proto")
        Timber.tag("Amazon").d("fetchDownloadSize: manifest URL = $manifestUrl")

        val manifestBytes = try {
            val request = Request.Builder()
                .url(manifestUrl)
                .get()
                .build()

            Net.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag("Amazon").e("fetchDownloadSize: HTTP ${response.code} fetching manifest")
                    return@withContext null
                }
                response.body?.bytes()
            }
        } catch (e: Exception) {
            Timber.tag("Amazon").e(e, "fetchDownloadSize: failed to fetch manifest.proto")
            return@withContext null
        }

        if (manifestBytes == null) {
            Timber.tag("Amazon").e("fetchDownloadSize: empty manifest response")
            return@withContext null
        }

        try {
            val manifest = AmazonManifest.parse(manifestBytes)
            Timber.tag("Amazon").i("fetchDownloadSize: totalInstallSize = ${manifest.totalInstallSize}")
            manifest.totalInstallSize
        } catch (e: Exception) {
            Timber.tag("Amazon").e(e, "fetchDownloadSize: failed to parse manifest")
            null
        }
    }

    // ── SDK / Launcher channel ──────────────────────────────────────────────────────────────

    /** Fetch the download spec for the launcher/SDK channel. */
    suspend fun fetchSdkDownload(
        bearerToken: String,
    ): GameDownloadSpec? = withContext(Dispatchers.IO) {
        val url = "$DISTRIBUTION_URL/download/channel/${AmazonConstants.LAUNCHER_CHANNEL_ID}"
        Timber.tag("Amazon").d("fetchSdkDownload: GET $url")

        try {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("x-amzn-token", bearerToken)
                .header("User-Agent", AmazonConstants.GAMING_USER_AGENT)
                .build()

            Net.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "(no body)"
                    Timber.tag("Amazon").e("fetchSdkDownload: HTTP ${response.code}: $errorBody")
                    return@withContext null
                }

                val responseText = response.body?.string() ?: return@withContext null
                val json = JSONObject(responseText)

                val downloadUrl = json.optString("downloadUrl", "").ifEmpty {
                    Timber.tag("Amazon").e("fetchSdkDownload: missing downloadUrl")
                    return@withContext null
                }
                val versionId = json.optString("versionId", "")
                Timber.tag("Amazon").i("fetchSdkDownload: versionId=$versionId url=${downloadUrl.take(80)}…")
                GameDownloadSpec(downloadUrl = downloadUrl, versionId = versionId)
            }
        } catch (e: Exception) {
            Timber.tag("Amazon").e(e, "fetchSdkDownload failed")
            null
        }
    }

    /** Append [segment] to the path portion of [baseUrl], before any query string. */
    internal fun appendPath(baseUrl: String, segment: String): String {
        val qIdx = baseUrl.indexOf('?')
        return if (qIdx == -1) {
            "$baseUrl/$segment"
        } else {
            val path = baseUrl.substring(0, qIdx)
            val query = baseUrl.substring(qIdx)
            "$path/$segment$query"
        }
    }
}
