package app.gamenative.service.epic

import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.data.EpicGame
import app.gamenative.data.GameSource
import app.gamenative.data.LaunchInfo
import app.gamenative.data.LibraryItem
import app.gamenative.data.canonical.EpicStableSourceId
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.library.canonical.AccountScopedOwnershipLedger
import app.gamenative.library.canonical.MaterializedOwnedCopySnapshot
import app.gamenative.utils.Net
import app.gamenative.utils.sanitizeForFilename
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

private const val UNREAL_ENGINE_NAMESPACE = "89efe5924d3d467c839449ab6ab52e7f"

data class EpicLibraryPage(
    val items: List<EpicManager.ParsedLibraryItem>,
    val nextCursor: String?,
)

private fun JSONObject.requiredEpicPageString(name: String): String {
    val value = opt(name)
    require(value is String && value.isNotBlank() && value == value.trim()) {
        "MALFORMED_EPIC_PAGE"
    }
    return value
}

private fun JSONObject.optionalEpicPageString(name: String): String {
    if (!has(name) || isNull(name)) return ""
    val value = get(name)
    require(value is String) { "MALFORMED_EPIC_PAGE" }
    return value
}

internal fun parseEpicLibraryPage(
    json: JSONObject,
    seenCursors: Set<String>,
): Result<EpicLibraryPage> = runCatching {
    val records = json.optJSONArray("records") ?: error("MALFORMED_EPIC_PAGE")
    val metadata = json.optJSONObject("responseMetadata") ?: error("MALFORMED_EPIC_PAGE")
    val items = buildList {
        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index) ?: error("MALFORMED_EPIC_PAGE")
            val appName = record.requiredEpicPageString("appName")
            val namespace = record.requiredEpicPageString("namespace")
            val catalogItemId = record.requiredEpicPageString("catalogItemId")
            val sandboxType = record.optionalEpicPageString("sandboxType")
            val platformValue = record.opt("platform")
            val platforms: List<String> = when (platformValue) {
                null, JSONObject.NULL -> emptyList()
                is JSONArray -> buildList {
                    for (platformIndex in 0 until platformValue.length()) {
                        val platform = platformValue.get(platformIndex)
                        require(
                            platform is String &&
                                platform.isNotEmpty() &&
                                platform == platform.trim(),
                        ) {
                            "MALFORMED_EPIC_PAGE"
                        }
                        add(platform)
                    }
                }
                else -> error("MALFORMED_EPIC_PAGE")
            }
            val excluded = namespace == "ue" ||
                namespace == UNREAL_ENGINE_NAMESPACE ||
                sandboxType == "PRIVATE" ||
                appName == "1" ||
                (platforms.isNotEmpty() && "Win32" !in platforms && "Windows" !in platforms)
            if (!excluded) {
                add(
                    EpicManager.ParsedLibraryItem(
                        appName = appName,
                        namespace = namespace,
                        catalogItemId = catalogItemId,
                        sandboxType = sandboxType,
                        country = record.optionalEpicPageString("country"),
                    ),
                )
            }
        }
    }
    val nextCursor = if (!metadata.has("nextCursor") || metadata.isNull("nextCursor")) {
        null
    } else {
        val rawCursor = metadata.get("nextCursor")
        require(rawCursor is String && rawCursor.isNotBlank() && rawCursor !in seenCursors) {
            "MALFORMED_EPIC_CURSOR"
        }
        rawCursor
    }
    EpicLibraryPage(items = items, nextCursor = nextCursor)
}

internal fun validateEpicCatalogIdentity(
    data: JSONObject,
    expectedNamespace: String,
    expectedCatalogItemId: String,
): Result<Unit> = runCatching {
    val namespace = data.opt("namespace") as? String
    val catalogItemId = data.opt("id") as? String
    require(namespace == expectedNamespace && catalogItemId == expectedCatalogItemId) {
        "MISMATCHED_EPIC_CATALOG_IDENTITY"
    }
}

/**
 * EpicManager handles Epic Games library management
 */
@Singleton
class EpicManager @Inject constructor(
    private val epicGameDao: EpicGameDao,
    private val ownershipLedger: AccountScopedOwnershipLedger,
) {

    private val REFRESH_BATCH_SIZE = 10

    // Deployment ID cache TTL — deployment IDs rarely change, but a periodic re-probe
    // gives automatic recovery from any poisoned cache entry (stale negative, truncated
    // value, etc.) without requiring manual intervention.
    private val DEPLOYMENT_ID_CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000  // 30 days

    private val httpClient = Net.http

    private fun getCdnClient(): okhttp3.OkHttpClient {
        val parallelDownloads = PrefManager.downloadSpeed.coerceAtLeast(1)
        return Net.httpForParallelDownloads(parallelDownloads)
    }

    data class EpicAssetList(
        val appName: String,
        val labelName: String,
        val buildVersion: String,
        val catalogItemId: String,
        val namespace: String,
        val assetId: String,
        val metadata: AssetMetadata?,
    )

    data class AssetMetadata(
        val installationPoolId: String,
        val update_type: String,
    )

    data class EpicLibraryItem(
        val namespace: String,
        val catalogItemId: String?,
        val appName: String,
        val country: String?,
        val platform: List<String>?,
        val productId: String,
        val sandboxName: String,
        val sandboxType: String,
        val recordType: String?,
        val acquisitionDate: String?,
        val dependencies: List<String>?,
    )

    data class ParsedLibraryItem(
        val appName: String,
        val namespace: String,
        val catalogItemId: String,
        val sandboxType: String?,
        val country: String?,
    )

    data class LibraryItemsResponse(
        val responseMetadata: ResponseMetadata,
        val records: List<EpicLibraryItem>?,
    )

    data class ResponseMetadata(
        val nextCursor: String?,
        val stateToken: String?,
    )

    data class ManifestSizes(
        val installSize: Long,
        val downloadSize: Long,
    )

    // Usually consists of DieselGameBox and DieselGameBoxTall that we can use.
    data class EpicKeyImage(
        val type: String,
        val url: String, // Full URL of the game art.
        val md5: String?,
        val width: Int?,
        val height: Int?,
        val size: Int?,
        val uploadedDate: String?, // "2019-12-19T21:54:10.003Z"
    )

    data class EpicCategory(
        val path: String,
    )

    data class EpicCustomAttributeValue(
        val type: String,
        val value: String,
    )

    // Custom Attributes from the payload.
    data class EpicCustomAttributes(
        val canRunOffline: Boolean = false,
        val ownershipToken: Boolean = false,
        val cloudSaveFolder: String? = null,
        val cloudIncludeList: String? = null,
        val neverUpdate: Boolean = false,
        val folderName: String? = null,
        val presenceId: String? = null,
        val monitorPresence: Boolean = false,
        val useAccessControl: Boolean = false,
        val canSkipKoreanIdVerification: Boolean = true,
        val partnerLinkType: String? = null, // Ubisoft
        val thirdPartyManagedProvider: String? = null, // UbisoftConnect
        val thirdPartyManagedApp: String? = null, // The EA App | Origin
        val partnerLinkId: String? = null,
        val backgroundProcessName: String? = null,
        val registryPath: String? = null,
        val registryLocation: String? = null,
        val registryKey: String? = null,
        val additionalCommandline: String? = null,
        val processNames: String? = null,
        val gameId: String? = null,
        val executableName: String? = null,
    )

    data class EpicReleaseInfo(
        val id: String,
        val appId: String,
        val platform: List<String>?,
        val dateAdded: String?,
        val releaseNote: String?,
        val versionTitle: String?,
    )

    data class EpicMainGameItem(
        val id: String,
        val namespace: String,
    )

    data class GameInfoResponse(
        val id: String,
        val title: String,
        val description: String,
        val keyImages: List<EpicKeyImage>,
        val categories: List<EpicCategory>,
        val namespace: String,
        val status: String?,
        val creationDate: String?, // "2025-03-04T08:39:07.841Z",
        val lastModifiedDate: String?, // "2025-03-06T07:37:16.597Z",
        val customAttributes: EpicCustomAttributes?,
        val entitlementName: String?,
        val entitlementType: String?,
        val itemType: String?,
        val releaseInfo: EpicReleaseInfo,
        val developer: String,
        val developerId: String?,
        val eulaIds: List<String>?,
        val endOfSupport: Boolean?,
        val mainGameItemList: List<String>?,
        val ageGatings: Map<String, Int>?,
        val applicationId: String?,
        val baseAppName: String?,
        val baseProductId: String?,
        val mainGameItem: EpicMainGameItem?,
    )

    /**
     * Refresh the entire library (called manually by user or after login)
     * Fetches all games from Epic via Legendary and updates the database
     */
    suspend fun refreshLibrary(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        if (!EpicAuthManager.hasStoredCredentials(context)) {
            return@withContext Result.failure(IllegalStateException("EPIC_AUTH_UNAVAILABLE"))
        }

        ownershipLedger.runCompleteSnapshot(GameSource.EPIC) {
            val libraryItems = fetchLibrary(context).getOrThrow()
            val accessToken = EpicAuthManager.getStoredCredentials(context).getOrThrow()
                .accessToken
                .also { require(it.isNotEmpty()) }
            val pendingGames = mutableListOf<EpicGame>()
            val materializedGames = mutableListOf<EpicGame>()

            for ((index, item) in libraryItems.withIndex()) {
                val game = fetchGameInfo(context, item, accessToken).getOrThrow()
                pendingGames += game
                materializedGames += game
                if ((index + 1) % REFRESH_BATCH_SIZE == 0 || index == libraryItems.lastIndex) {
                    epicGameDao.upsertPreservingInstallStatus(pendingGames)
                    pendingGames.clear()
                }
            }

            val visibleIds = materializedGames.asSequence()
                .filter { !it.isDLC && it.namespace != "ue" && it.namespace != UNREAL_ENGINE_NAMESPACE }
                .map { EpicStableSourceId.encode(it.namespace, it.catalogId) }
                .toList()
            val persistedVisibleIds = epicGameDao.getAllAsList()
                .map { EpicStableSourceId.encode(it.namespace, it.catalogId) }
                .toSet()
            require(visibleIds.all(persistedVisibleIds::contains))
            MaterializedOwnedCopySnapshot(
                value = materializedGames.size,
                stableSourceIds = visibleIds,
            )
        }
    }

    /**
     *
     * Returns list of library items with app names, namespaces, and catalog IDs
     */
    suspend fun fetchLibrary(context: Context): Result<List<ParsedLibraryItem>> = withContext(Dispatchers.IO) {
        try {
            // Get Credentials and restore them
            val credentials = EpicAuthManager.getStoredCredentials(context)
            if (credentials.isFailure) {
                return@withContext Result.failure(credentials.exceptionOrNull() ?: Exception("No credentials"))
            }

            val accessToken = credentials.getOrNull()?.accessToken
            if (accessToken.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("No access token"))
            }

            val gameList = mutableListOf<ParsedLibraryItem>()
            val seenCursors = mutableSetOf<String>()
            var cursor: String? = null

            do {
                val url = EpicConstants.EPIC_LIBRARY_API_URL.toHttpUrl().newBuilder()
                    .addQueryParameter("includeMetadata", "true")
                    .apply { cursor?.let { addQueryParameter("cursor", it) } }
                    .build()
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $accessToken")
                    .header("User-Agent", EpicConstants.EPIC_USER_AGENT)
                    .get()
                    .build()

                val responseJson = httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(Exception("Epic library HTTP failure"))
                    }
                    val body = response.body?.string()
                    if (body.isNullOrEmpty()) {
                        return@withContext Result.failure(Exception("Empty Epic library response"))
                    }
                    JSONObject(body)
                }
                val page = parseEpicLibraryPage(responseJson, seenCursors).getOrElse {
                    return@withContext Result.failure(IllegalStateException("MALFORMED_EPIC_PAGE"))
                }
                gameList += page.items
                cursor = page.nextCursor
                if (cursor != null) seenCursors += cursor
            } while (cursor != null)

            Timber.tag("Epic").i("Successfully fetched ${gameList.size} games from Epic library")
            Result.success(gameList)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.failure(IllegalStateException("EPIC_LIBRARY_FAILED"))
        }
    }

    /**
     * Resolves the effective launch executable for an Epic game.
     * Returns empty string if game is not installed or no executable can be found.
     */
    suspend fun getLaunchExecutable(appId: Int): String {
        return getInstalledExe(appId)
    }

    suspend fun getInstalledExe(appId: Int): String {
        // Strip EPIC_ prefix to get the raw Epic app name
        val game = getGameById(appId)
        if (game == null || !game.isInstalled || game.installPath.isEmpty()) {
            Timber.tag("Epic").e("Game not installed: $appId")
            return ""
        }

        // For now, return the install path - actual executable detection would require
        // parsing the game's launch manifest or config files
        // Most Epic games have a .exe in the root or Binaries folder
        val installDir = File(game.installPath)
        if (!installDir.exists()) {
            Timber.tag("Epic").e("Install directory does not exist: ${game.installPath}")
            return ""
        }

        // Try to find the main executable
        // Common patterns: Game.exe, GameName.exe, or in Binaries/Win64/
        val exeFiles = installDir.walk()
            .filter { it.extension.equals("exe", ignoreCase = true) }
            .filter { !it.name.contains("UnityCrashHandler", ignoreCase = true) }
            .filter { !it.name.contains("UnrealCEFSubProcess", ignoreCase = true) }
            .sortedBy { it.absolutePath.length } // Prefer shorter paths (usually main exe)
            .toList()

        val mainExe = exeFiles.firstOrNull()
        if (mainExe != null) {
            Timber.tag("Epic").i("Found executable: ${mainExe.absolutePath}")
            return mainExe.relativeTo(installDir).path
        }

        Timber.tag("Epic").w("No executable found in ${game.installPath}")
        return ""
    }

    private suspend fun fetchGameInfo(
        context: Context,
        game: ParsedLibraryItem,
        accessToken: String,
    ): Result<EpicGame> = withContext(Dispatchers.IO) {
        try {
            // ! We should expertiment with the country to see what affects language downloads
            val gameData = fetchCatalogItem(
                namespace = game.namespace,
                catalogItemId = game.catalogItemId,
                accessToken = accessToken,
                country = game.country ?: "US",
                includeDLCDetails = true,
                includeMainGameDetails = true,
            ) ?: return@withContext Result.failure(Exception("Game data not found in response"))
            validateEpicCatalogIdentity(
                data = gameData,
                expectedNamespace = game.namespace,
                expectedCatalogItemId = game.catalogItemId,
            ).getOrThrow()

            Result.success(parseGameFromCatalog(gameData, game.appName))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.failure(IllegalStateException("EPIC_DETAIL_FAILED"))
        }
    }

    /**
     * GET a single item from `/shared/namespace/{ns}/bulk/items` and return the
     * per-`catalogItemId` JSONObject (or null if the request failed / item missing).
     * Caller must be on a background dispatcher; the underlying HTTP call is blocking.
     */
    private fun fetchCatalogItem(
        namespace: String,
        catalogItemId: String,
        accessToken: String,
        country: String = "US",
        includeDLCDetails: Boolean = false,
        includeMainGameDetails: Boolean = false,
    ): JSONObject? {
        val params = buildString {
            append("?id=").append(catalogItemId)
            if (includeDLCDetails) append("&includeDLCDetails=true")
            if (includeMainGameDetails) append("&includeMainGameDetails=true")
            append("&country=").append(country)
        }
        val url = "${EpicConstants.EPIC_CATALOG_API_URL}/shared/namespace/$namespace/bulk/items$params"

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("User-Agent", EpicConstants.EPIC_USER_AGENT)
            .get()
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string()
            if (body.isNullOrEmpty()) return@use null
            JSONObject(body).optJSONObject(catalogItemId)
        }
    }

    /**
     * Parse customAttributes object from Epic catalog API
     */
    private fun parseCustomAttributes(customAttributesJson: JSONObject?): EpicCustomAttributes {
        if (customAttributesJson == null) {
            return EpicCustomAttributes()
        }

        // Helper function to extract value from attribute object
        fun getAttribute(name: String): String? {
            val attrObj = customAttributesJson.optJSONObject(name)
            return attrObj?.optString("value")?.takeIf { it.isNotEmpty() }
        }

        // Helper function to parse boolean attributes
        fun getBooleanAttribute(name: String, default: Boolean = false): Boolean {
            val value = getAttribute(name)
            return when (value?.lowercase()) {
                "true" -> true
                "false" -> false
                else -> default
            }
        }

        return EpicCustomAttributes(
            canRunOffline = getBooleanAttribute("CanRunOffline", false),
            ownershipToken = getBooleanAttribute("OwnershipToken", false),
            cloudSaveFolder = getAttribute("CloudSaveFolder"),
            cloudIncludeList = getAttribute("CloudIncludeList"),
            folderName = getAttribute("FolderName"),
            presenceId = getAttribute("PresenceId"),
            monitorPresence = getBooleanAttribute("MonitorPresence", false),
            useAccessControl = getBooleanAttribute("UseAccessControl", false),
            canSkipKoreanIdVerification = getBooleanAttribute("CanSkipKoreanIdVerification", true),
            thirdPartyManagedApp = getAttribute("ThirdPartyManagedApp"),
            thirdPartyManagedProvider = getAttribute("ThirdPartyManagedProvider"),
            partnerLinkType = getAttribute("partnerLinkType"),
            additionalCommandline = getAttribute("AdditionalCommandLine"),
            executableName = getAttribute("MainWindowProcessName"),
        )
    }

    /**
     * Parse Epic catalog JSON into EpicGame object
     */
    internal fun parseGameFromCatalog(data: JSONObject, libraryAppName: String): EpicGame {
        val catalogItemId = data.getString("id")
        val namespace = data.getString("namespace")
        val title = data.getString("title")
        val description = data.optString("description", "")

        val appName = libraryAppName

        val keyImages = data.optJSONArray("keyImages")
        var artCover = "" // DieselGameBoxTall - Tall cover art
        var artSquare = "" // DieselGameBox - Square box art
        var artLogo = "" // DieselGameBoxLogo - Logo image
        var artPortrait = "" // DieselStoreFrontWide - Wide banner

        if (keyImages != null) {
            for (i in 0 until keyImages.length()) {
                val img = keyImages.getJSONObject(i)
                val imgType = img.optString("type")
                val imgUrl = img.optString("url", "")

                when (imgType) {
                    "DieselGameBoxTall" -> artCover = imgUrl
                    "DieselGameBox" -> artSquare = imgUrl
                    "DieselGameBoxLogo" -> artLogo = imgUrl
                    "DieselStoreFrontWide" -> artPortrait = imgUrl
                    "Thumbnail" -> if (artSquare.isEmpty()) artSquare = imgUrl
                }
            }
        }

        // Check if this is DLC
        val isDLC = data.has("mainGameItem")
        val baseGameAppName = if (isDLC) {
            data.optJSONObject("mainGameItem")?.optString("id", "") ?: ""
        } else {
            ""
        }

        // Get developer/publisher
        val developer = data.optString("developer", "")

        // Get categories to check for mods
        val categories = data.optJSONArray("categories")
        var isMod = false
        if (categories != null) {
            for (i in 0 until categories.length()) {
                val cat = categories.getJSONObject(i)
                if (cat.optString("path") == "mods") {
                    isMod = true
                    break
                }
            }
        }

        // Release date - convert to string format
        val releaseInfo = data.optJSONArray("releaseInfo")
        var releaseDate = ""
        if (releaseInfo != null && releaseInfo.length() > 0) {
            val release = releaseInfo.getJSONObject(0)
            releaseDate = release.optString("dateAdded", "")
        }
        // Parse genres/tags from categories
        val genresList = mutableListOf<String>()
        val tagsList = mutableListOf<String>()
        if (categories != null) {
            for (i in 0 until categories.length()) {
                val cat = categories.getJSONObject(i)
                val path = cat.optString("path", "")
                if (path.startsWith("games/")) {
                    genresList.add(path.removePrefix("games/"))
                } else if (path.isNotEmpty() && path != "mods") {
                    tagsList.add(path)
                }
            }
        }

        // Parse custom attributes for cloud saves and offline support
        val parsedAttributes = parseCustomAttributes(data.optJSONObject("customAttributes"))
        val canRunOffline = parsedAttributes.canRunOffline
        val requiresOwnershipToken = parsedAttributes.ownershipToken
        val cloudSaveEnabled = !parsedAttributes.cloudSaveFolder.isNullOrEmpty()
        val saveFolder = parsedAttributes.cloudSaveFolder ?: ""
        val executable = parsedAttributes.executableName ?: ""
        val thirdPartyApp = listOfNotNull(
            parsedAttributes.thirdPartyManagedApp,
            parsedAttributes.thirdPartyManagedProvider,
            parsedAttributes.partnerLinkType,
        ).firstOrNull() ?: ""

        val isEaManaged = if (parsedAttributes.thirdPartyManagedApp != null &&
            parsedAttributes.thirdPartyManagedApp.lowercase() in listOf("origin", "the ea app")
        ) {
            true
        } else {
            false
        }

        return EpicGame(
            id = 0, // Auto-generated by Room
            catalogId = catalogItemId,
            appName = appName,
            title = title,
            namespace = namespace,
            developer = developer,
            publisher = "",
            description = description,
            artCover = artCover,
            artSquare = artSquare,
            artLogo = artLogo,
            artPortrait = artPortrait,
            isDLC = isDLC,
            baseGameAppName = baseGameAppName,
            releaseDate = releaseDate,
            genres = genresList,
            tags = tagsList,
            isInstalled = false, // Will be updated from local database
            installPath = "",
            platform = "Windows",
            version = "",
            executable = executable,
            installSize = 0,
            downloadSize = 0,
            canRunOffline = canRunOffline,
            requiresOT = requiresOwnershipToken,
            cloudSaveEnabled = cloudSaveEnabled,
            saveFolder = saveFolder,
            thirdPartyManagedApp = thirdPartyApp,
            isEAManaged = isEaManaged,
            lastPlayed = 0,
            playTime = 0,
        )
    }

    suspend fun deleteAllNonInstalledGames() {
        withContext(Dispatchers.IO) {
            epicGameDao.deleteAllNonInstalledGames()
        }
    }


    /**
     * Get a single game by ID
     */
    suspend fun getGamesById(gameIds: List<Int>): List<EpicGame> {
        return withContext(Dispatchers.IO) {
            try {
                epicGameDao.getGamesById(gameIds)
            } catch (e: Exception) {
                Timber.e(e, "Failed to get Epic games by IDs: ${gameIds.size}")
                emptyList()
            }
        }
    }

    /**
     * Get a single game by ID
     */
    suspend fun getGameById(appId: Int): EpicGame? {
        return withContext(Dispatchers.IO) {
            try {
                epicGameDao.getById(appId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to get Epic game by ID: $appId")
                null
            }
        }
    }

    suspend fun getDLCForTitle(appId: Int): List<EpicGame> {
        return withContext(Dispatchers.IO) {
            try {
                Timber.tag("Epic").i("Getting DLC for appId: $appId")
                epicGameDao.getDLCForTitle(appId).firstOrNull() ?: emptyList()
            } catch (e: Exception) {
                Timber.e(e, "Failed to get DLC for app name: $appId")
                emptyList()
            }
        }
    }

    /**
     * Get a single game by app name (Legendary identifier)
     */
    suspend fun getGameByAppName(appName: String): EpicGame? {
        return withContext(Dispatchers.IO) {
            try {
                epicGameDao.getByAppName(appName)
            } catch (e: Exception) {
                Timber.e(e, "Failed to get Epic game by app name: $appName")
                null
            }
        }
    }

    suspend fun insertGame(game: EpicGame) {
        withContext(Dispatchers.IO) {
            epicGameDao.insert(game)
        }
    }

    suspend fun updateGame(game: EpicGame) {
        withContext(Dispatchers.IO) {
            epicGameDao.update(game)
        }
    }

    suspend fun uninstall(appId: Int) {
        withContext(Dispatchers.IO) {
            epicGameDao.uninstall(appId)
        }
    }

    suspend fun getNonInstalledGames(): List<EpicGame> {
        return withContext(Dispatchers.IO) {
            epicGameDao.getNonInstalledGames()
        }
    }

    /**
     * Start background sync (called after login)
     */
    suspend fun startBackgroundSync(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!EpicAuthManager.hasStoredCredentials(context)) {
                Timber.w("Cannot start background sync: no stored credentials")
                return@withContext Result.failure(Exception("No stored credentials found"))
            }

            Timber.tag("Epic").i("Starting Epic library background sync...")

            val result = refreshLibrary(context)

            if (result.isSuccess) {
                val count = result.getOrNull() ?: 0
                Timber.tag("Epic").i("Background sync completed: $count games synced")
                Result.success(Unit)
            } else {
                val error = result.exceptionOrNull()
                Timber.e(error, "Background sync failed: ${error?.message}")
                Result.failure(error ?: Exception("Background sync failed"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync Epic library in background")
            Result.failure(e)
        }
    }

    data class ManifestResult(
        val manifestBytes: ByteArray,
        val cdnUrls: List<CdnUrl>,
    )

    data class CdnUrl(
        val baseUrl: String,
        val authQueryParams: String,
        val cloudDir: String = "", // Full build path for chunk downloads
    )

    /**
     * Fetch manifest binary data from Epic API and CDN
     *
     * Returns the raw manifest bytes and CDN base URLs from the API response
     */
    suspend fun fetchManifestFromEpic(
        context: Context,
        namespace: String,
        catalogItemId: String,
        appName: String,
    ): Result<ManifestResult> = withContext(Dispatchers.IO) {
        try {
            // Get credentials
            val credentials = EpicAuthManager.getStoredCredentials(context)
            if (credentials.isFailure) {
                return@withContext Result.failure(credentials.exceptionOrNull() ?: Exception("No credentials"))
            }

            val accessToken = credentials.getOrNull()?.accessToken
            if (accessToken.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("No access token"))
            }

            // Fetch manifest URL from Epic API
            val manifestUrl = "${EpicConstants.EPIC_LAUNCHER_API_URL}/launcher/api/public/assets/v2/platform" +
                "/Windows/namespace/$namespace/catalogItem/$catalogItemId/app" +
                "/$appName/label/Live"

            Timber.tag("Epic").d("Fetching manifest metadata from: $manifestUrl")

            val request = Request.Builder()
                .url(manifestUrl)
                .header("Authorization", "Bearer $accessToken")
                .header("User-Agent", EpicConstants.EPIC_USER_AGENT)
                .get()
                .build()

            val manifestJson = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Manifest API request failed: ${response.code}"))
                }

                val body = response.body?.string()
                if (body.isNullOrEmpty()) {
                    return@withContext Result.failure(Exception("Empty manifest API response"))
                }

                JSONObject(body)
            }
            val elements = manifestJson.optJSONArray("elements")

            if (elements == null || elements.length() == 0) {
                return@withContext Result.failure(Exception("No elements in manifest API response"))
            }

            val element = elements.getJSONObject(0)
            val manifests = element.optJSONArray("manifests")

            if (manifests == null || manifests.length() == 0) {
                return@withContext Result.failure(Exception("No manifests in API response"))
            }

            // Extract CDN base URLs from manifest URIs with their auth tokens
            // Each manifest entry represents the same content on a different CDN
            val cdnUrls = mutableListOf<CdnUrl>()
            for (i in 0 until manifests.length()) {
                val manifest = manifests.getJSONObject(i)
                val uri = manifest.getString("uri")

                // Extract base URL (e.g., "https://fastly-download.epicgames.com")
                val baseUrl = uri.substringBefore("/Builds")
                if (baseUrl.isEmpty() || !baseUrl.startsWith("http")) {
                    continue
                }

                // Extract CloudDir (build path) from URI
                // Example: https://fastly-download.epicgames.com/Builds/Org/{org}/{build}/default/...
                // CloudDir: /Builds/Org/{org}/{build}/default
                val cloudDir = if (uri.contains("/Builds")) {
                    val afterBase = uri.substringAfter(baseUrl)
                    val manifestFilename = afterBase.substringAfterLast("/")
                    afterBase.substringBefore("/" + manifestFilename)
                } else {
                    ""
                }

                // Extract authentication query parameters for this CDN
                val queryParams = manifest.optJSONArray("queryParams")
                val authParams = if (queryParams != null && queryParams.length() > 0) {
                    val params = StringBuilder("?")
                    for (j in 0 until queryParams.length()) {
                        val param = queryParams.getJSONObject(j)
                        val name = param.getString("name")
                        val value = param.getString("value")
                        if (j > 0) params.append("&")
                        params.append("$name=$value")
                    }
                    params.toString()
                } else {
                    ""
                }

                cdnUrls.add(CdnUrl(baseUrl, authParams, cloudDir))
            }

            // Error if no CDN URLs could be extracted
            if (cdnUrls.isEmpty()) {
                return@withContext Result.failure(Exception("No CDN URLs found in manifest API response"))
            }

            Timber.tag("Epic").d("Found ${cdnUrls.size} CDN mirrors")

            // Use the first manifest to download the manifest file
            val manifestObj = manifests.getJSONObject(0)
            var manifestUri = manifestObj.getString("uri")

            // Append query parameters (CDN authentication tokens) for manifest download
            val manifestQueryParams = manifestObj.optJSONArray("queryParams")
            if (manifestQueryParams != null && manifestQueryParams.length() > 0) {
                val params = StringBuilder()
                for (i in 0 until manifestQueryParams.length()) {
                    val param = manifestQueryParams.getJSONObject(i)
                    val name = param.getString("name")
                    val value = param.getString("value")
                    if (i == 0) {
                        params.append("?")
                    } else {
                        params.append("&")
                    }
                    params.append("$name=$value")
                }
                manifestUri += params.toString()
            }

            Timber.tag("Epic").d("Downloading manifest binary from: $manifestUri")

            // Manifest downloads from CDN don't need/accept Epic auth tokens
            val manifestRequest = Request.Builder()
                .url(manifestUri)
                .header("User-Agent", EpicConstants.EPIC_USER_AGENT)
                .get()
                .build()

            val manifestBytes = getCdnClient().newCall(manifestRequest).execute().use { manifestResponse ->
                if (!manifestResponse.isSuccessful) {
                    return@withContext Result.failure(Exception("Failed to download manifest binary: ${manifestResponse.code}"))
                }

                val bytes = manifestResponse.body?.bytes()
                if (bytes == null) {
                    return@withContext Result.failure(Exception("Empty manifest bytes from CDN"))
                }

                bytes
            }

            Timber.tag("Epic").d("Manifest fetched with ${cdnUrls.size} CDN URLs")
            Result.success(ManifestResult(manifestBytes, cdnUrls))
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Exception fetching manifest")
            Result.failure(e)
        }
    }

    /**
     * Fetch the EOS deployment id for a game from the launcher manifest API.
     *
     * Mirrors Legendary's sidecar handling (legendary/core.py, _update_assets_and_meta
     * and get_launch_parameters): the manifest API response contains
     * `elements[0].sidecar.config` as a JSON-encoded string, which carries the
     * game's `deploymentId`.  Passing `-epicdeploymentid=<id>` on the command
     * line is required for modern EOS-integrated games; without it, titles
     * such as "Deliver At All Costs" refuse to start with
     * "Failed to connect to the Epic Launcher".
     *
     * Cached per app-name under [Context.filesDir]/epic/deployment_ids/.
     *
     * @return the deployment id if the game exposes one, otherwise null. Null
     *         is a valid result – most titles do not have a sidecar.
     */
    suspend fun fetchDeploymentId(
        context: Context,
        namespace: String,
        catalogItemId: String,
        appName: String,
        forceRefresh: Boolean = false,
    ): String? = withContext(Dispatchers.IO) {
        val cacheDir = File(context.filesDir, "epic/deployment_ids").also { it.mkdirs() }
        val cacheFile = File(cacheDir, "${appName.sanitizeForFilename()}.txt")

        if (!forceRefresh && cacheFile.exists()) {
            val cacheAgeMs = System.currentTimeMillis() - cacheFile.lastModified()
            if (cacheAgeMs < DEPLOYMENT_ID_CACHE_TTL_MS) {
                return@withContext cacheFile.readText().trim().takeIf { it.isNotEmpty() }
            }
            Timber.tag("Epic").d(
                "fetchDeploymentId cache for $appName is stale (age=${cacheAgeMs}ms), refetching",
            )
        }

        try {
            val credentialsResult = EpicAuthManager.getStoredCredentials(context)
            val accessToken = credentialsResult.getOrNull()?.accessToken
            if (accessToken.isNullOrEmpty()) {
                Timber.tag("Epic").w("fetchDeploymentId: no access token")
                return@withContext null
            }

            val manifestUrl = "${EpicConstants.EPIC_LAUNCHER_API_URL}/launcher/api/public/assets/v2/platform" +
                "/Windows/namespace/$namespace/catalogItem/$catalogItemId/app" +
                "/$appName/label/Live"

            val request = Request.Builder()
                .url(manifestUrl)
                .header("Authorization", "Bearer $accessToken")
                .header("User-Agent", EpicConstants.EPIC_USER_AGENT)
                .get()
                .build()

            val manifestJson = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag("Epic").w("fetchDeploymentId: manifest API ${response.code} for $appName")
                    return@withContext null
                }
                val body = response.body?.string()
                if (body.isNullOrEmpty()) return@withContext null
                JSONObject(body)
            }

            val elements = manifestJson.optJSONArray("elements")
            if (elements == null || elements.length() == 0) return@withContext null

            val sidecar = elements.getJSONObject(0).optJSONObject("sidecar")
            // sidecar.config is a JSON-encoded string, NOT a nested JSON object
            val configStr = sidecar?.optString("config", "") ?: ""
            if (configStr.isEmpty()) {
                // Cache negative result to avoid refetching every launch
                cacheFile.writeText("")
                return@withContext null
            }

            val deploymentId = try {
                JSONObject(configStr).optString("deploymentId", "").takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                // Malformed sidecar (transient Epic API hiccup or schema change).
                // Do NOT persist a negative cache here — next launch will retry the parse
                // rather than permanently treating this game as having no deployment id.
                Timber.tag("Epic").w(e, "fetchDeploymentId: failed to parse sidecar.config for $appName")
                return@withContext null
            }

            cacheFile.writeText(deploymentId ?: "")
            Timber.tag("Epic").d("fetchDeploymentId($appName) = ${deploymentId ?: "<none>"}")
            deploymentId
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Exception fetching deployment id for $appName")
            null
        }
    }

    /**
     * Fetch `customAttributes.AdditionalCommandLine` from the Epic catalog API.
     * Mirrors legendary `Game.additional_command_line`. Cached per app-name with
     * the same TTL as deployment ids.
     */
    suspend fun fetchAdditionalCommandLine(
        context: Context,
        namespace: String,
        catalogItemId: String,
        appName: String,
        forceRefresh: Boolean = false,
    ): String? = withContext(Dispatchers.IO) {
        val cacheDir = File(context.filesDir, "epic/additional_cmdline").also { it.mkdirs() }
        val cacheFile = File(cacheDir, "${appName.sanitizeForFilename()}.txt")

        if (!forceRefresh && cacheFile.exists()) {
            val cacheAgeMs = System.currentTimeMillis() - cacheFile.lastModified()
            if (cacheAgeMs < DEPLOYMENT_ID_CACHE_TTL_MS) {
                return@withContext cacheFile.readText().trim().takeIf { it.isNotEmpty() }
            }
        }

        try {
            val credentials = EpicAuthManager.getStoredCredentials(context)
            val accessToken = credentials.getOrNull()?.accessToken
            if (accessToken.isNullOrEmpty()) return@withContext null

            val gameData = fetchCatalogItem(
                namespace = namespace,
                catalogItemId = catalogItemId,
                accessToken = accessToken,
            ) ?: return@withContext null

            val additionalCommandLine = parseCustomAttributes(
                gameData.optJSONObject("customAttributes"),
            ).additionalCommandline

            cacheFile.writeText(additionalCommandLine ?: "")
            additionalCommandLine
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Exception fetching additional command line for $appName")
            null
        }
    }

    /**
     * Fetch install size for a game by downloading its manifest
     * Manifest is small (~500KB-1MB) and contains all file metadata
     * Returns size in bytes, or 0 if failed
     */
    suspend fun fetchManifestSizes(context: Context, appId: Int): ManifestSizes = withContext(Dispatchers.IO) {
        try {
            // Get the game info to get namespace and catalogItemId
            val game = getGameById(appId)

            if (game == null) {
                Timber.tag("Epic").w("Game not found in database: $game.appName")
                return@withContext ManifestSizes(installSize = 0L, downloadSize = 0L)
            }

            val appName = game.appName

            // Fetch manifest using shared function
            val manifestResult = fetchManifestFromEpic(context, game.namespace, game.catalogId, game.appName)
            if (manifestResult.isFailure) {
                Timber.tag("Epic").w("Failed to fetch manifest: ${manifestResult.exceptionOrNull()?.message}")
                return@withContext ManifestSizes(installSize = 0L, downloadSize = 0L)
            }

            val manifestData = manifestResult.getOrNull()!!

            // Parse with Kotlin parser
            val manifest = app.gamenative.service.epic.manifest.EpicManifest.readAll(manifestData.manifestBytes)

            // Required-only sizes for detail page display (download uses container language via getSizesForSelectedInstallTags elsewhere).
            val (downloadSize, installSize) = app.gamenative.service.epic.manifest.ManifestUtils.getSizesForSelectedInstallTags(manifest, emptyList())
            Timber.tag("Epic").d(
                "Manifest stats for $appName: version=${manifest.version}, featureLevel=${manifest.meta?.featureLevel}, " +
                    "buildVersion=${manifest.meta?.buildVersion}, buildId=${manifest.meta?.buildId}",
            )
            Timber.tag("Epic").d(
                "Manifest stats for $appName: files=${manifest.fileManifestList?.count}, " +
                    "chunks=${manifest.chunkDataList?.count}",
            )
            Timber.tag("Epic").d("Install size for $appName: $installSize bytes")
            Timber.tag("Epic").d("Download size for $appName: $downloadSize bytes")

            return@withContext ManifestSizes(installSize = installSize, downloadSize = downloadSize)
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Exception fetching install size for appId: $appId")
            ManifestSizes(installSize = 0L, downloadSize = 0L)
        }
    }
}
