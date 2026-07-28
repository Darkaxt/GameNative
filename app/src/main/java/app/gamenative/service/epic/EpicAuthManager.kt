package app.gamenative.service.epic

import android.content.Context
import app.gamenative.data.EpicCredentials
import app.gamenative.data.EpicGameToken
import app.gamenative.data.GameSource
import app.gamenative.library.canonical.AccountScopeInvalidations
import app.gamenative.utils.sanitizeForFilename
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import org.json.JSONObject
import timber.log.Timber

/**
 * Manages Epic Games authentication and account operations.
 */
object EpicAuthManager {
    private val credentialLifecycleLock = Any()
    private var credentialGeneration = 0L

    // Denuvo ownership tokens are valid ~30 minutes and the endpoint is rate-limited
    // (~5 requests / 24h / game). Cache to disk and re-use a few minutes under the
    // server-side validity window to avoid burning the quota on relaunches.
    private const val OWNERSHIP_TOKEN_CACHE_TTL_MS = 25L * 60L * 1000L

    private fun getCredentialsFilePath(context: Context): String {
        val dir = File(context.filesDir, "epic")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "credentials.json").absolutePath
    }

    private fun ownershipTokenCacheFile(
        context: Context,
        accountId: String,
        namespace: String,
        catalogItemId: String,
    ): File {
        val accountCacheKey = MessageDigest.getInstance("SHA-256")
            .digest(
                ("gamenative-epic-token-cache-v1" + 0.toChar() + accountId)
                    .toByteArray(Charsets.UTF_8),
            )
            .joinToString("") { "%02x".format(it) }
        val dir = File(context.filesDir, "epic/ownership_tokens/$accountCacheKey").also { it.mkdirs() }
        return File(dir, "${namespace.sanitizeForFilename()}_${catalogItemId.sanitizeForFilename()}.hex")
    }

    private fun readCachedOwnershipTokenHex(
        context: Context,
        accountId: String,
        namespace: String,
        catalogItemId: String,
    ): String? {
        val file = ownershipTokenCacheFile(context, accountId, namespace, catalogItemId)
        if (!file.exists()) return null
        if (System.currentTimeMillis() - file.lastModified() >= OWNERSHIP_TOKEN_CACHE_TTL_MS) return null
        return runCatching { file.readText().trim().takeIf { it.isNotEmpty() } }.getOrNull()
    }

    private fun writeOwnershipTokenHex(
        context: Context,
        accountId: String,
        namespace: String,
        catalogItemId: String,
        hex: String,
        expectedGeneration: Long,
    ): Boolean = synchronized(credentialLifecycleLock) {
        if (credentialGeneration != expectedGeneration) return@synchronized false
        runCatching {
            ownershipTokenCacheFile(context, accountId, namespace, catalogItemId).writeText(hex)
        }.onFailure { Timber.tag("Epic").w(it, "Failed caching ownership token") }
            .isSuccess
    }

    private fun clearOwnershipTokenCache(context: Context) {
        runCatching {
            File(context.filesDir, "epic/ownership_tokens")
                .listFiles()
                ?.forEach(File::deleteRecursively)
        }.onFailure { Timber.tag("Epic").w(it, "Failed clearing ownership token cache") }
    }

    fun hasStoredCredentials(context: Context): Boolean {
        val credentialsFile = File(getCredentialsFilePath(context))
        return credentialsFile.exists()
    }

    /** Reads only the locally stored account ID. This never validates or refreshes credentials. */
    internal fun getStoredAccountId(context: Context): String? =
        synchronized(credentialLifecycleLock) {
            val file = File(context.filesDir, "epic/credentials.json")
            if (!file.isFile) return@synchronized null

            runCatching {
                JSONObject(file.readText())
                    .optString("account_id")
                    .takeIf(String::isNotBlank)
            }.onFailure { error ->
                Timber.tag("Epic").w(
                    "Unable to read local account identity: %s",
                    error.javaClass.simpleName,
                )
            }.getOrNull()
        }

    /**
     * Clear stored credentials (logout)
     */
    fun clearStoredCredentials(context: Context): Boolean {
        val expectedGeneration = captureCredentialGeneration()
        return clearStoredCredentialsIfCurrent(context, expectedGeneration) != false
    }

    internal fun clearStoredCredentialsIfCurrent(
        context: Context,
        expectedGeneration: Long,
    ): Boolean? = synchronized(credentialLifecycleLock) {
        if (credentialGeneration != expectedGeneration) return@synchronized null

        AccountScopeInvalidations.runLifecycleChange(GameSource.EPIC) {
            credentialGeneration++
            clearOwnershipTokenCache(context)
            try {
                val authFile = File(getCredentialsFilePath(context))
                if (authFile.exists()) authFile.delete() else true
            } catch (error: Exception) {
                Timber.e(error, "Failed to clear Epic credentials")
                false
            }
        }
    }

    /**
     * Extract authorization code from various input formats:
     * - Full URL: https://www.epicgames.com/id/api/redirect?code=abc123
     * - Just code: abc123
     */
    private fun extractCodeFromInput(input: String): String {
        val trimmed = input.trim()
        // Check if it's a URL with code parameter
        if (trimmed.startsWith("http")) {
            val codeMatch = Regex("[?&]code=([^&]+)").find(trimmed)
            return codeMatch?.groupValues?.get(1) ?: ""
        }
        // Otherwise assume it's already the code
        return trimmed
    }

    /**
     * Authenticate with Epic Games using authorization code from OAuth2 flow
     * Users must visit Epic login page, authenticate, and copy the authorization code
     *
     * @param context Android context
     * @param authorizationCode OAuth authorization code from Epic redirect
     * @return Result containing EpicCredentials on success, exception on failure
     */
    suspend fun authenticateWithCode(context: Context, authorizationCode: String): Result<EpicCredentials> {
        val operationGeneration = captureCredentialGeneration()
        return try {
            Timber.i("Starting Epic authentication with authorization code...")

            // Extract the actual authorization code from URL if needed
            val actualCode = extractCodeFromInput(authorizationCode)
            if (actualCode.isEmpty()) {
                return Result.failure(Exception("Invalid authorization URL: no code parameter found"))
            }

            // Use native API client for authentication
            Timber.d("Authenticating via EpicAuthClient...")

            val authResult = EpicAuthClient.authenticateWithCode(actualCode)

            if (authResult.isFailure) {
                val error = authResult.exceptionOrNull()
                Timber.e(error, "Epic authentication failed: ${error?.message}")
                return Result.failure(error ?: Exception("Authentication failed"))
            }

            val authResponse = authResult.getOrNull()!!

            // Save credentials to file
            val credentials = EpicCredentials(
                accessToken = authResponse.accessToken,
                refreshToken = authResponse.refreshToken,
                accountId = authResponse.accountId,
                displayName = authResponse.displayName,
                expiresAt = authResponse.expiresAt
            )

            if (!saveCredentialsIfCurrent(context, credentials, operationGeneration)) {
                return Result.failure(
                    IOException("Epic credential lifecycle changed during authentication"),
                )
            }

            Timber.i("Epic authentication successful: ${credentials.displayName}")
            Result.success(credentials)
        } catch (e: Exception) {
            Timber.e(e, "Epic authentication exception: ${e.message}")
            Result.failure(Exception("Authentication exception: ${e.message}", e))
        }
    }

    suspend fun getStoredCredentials(context: Context): Result<EpicCredentials> {
        val operationGeneration = captureCredentialGeneration()
        return try {
            if (!hasStoredCredentials(context)) {
                return Result.failure(Exception("No stored credentials found"))
            }

            val credentials = loadCredentials(context)
            if (credentials == null) {
                return Result.failure(Exception("Failed to load credentials"))
            }

            // Check if token is expired (with 5 minute buffer)
            val now = System.currentTimeMillis()
            val expiresAt = credentials.expiresAt
            val bufferMs = 5 * 60 * 1000 // 5 minutes

            if (now + bufferMs >= expiresAt) {
                Timber.d("Access token expired, refreshing...")

                val refreshResult = EpicAuthClient.refreshAccessToken(credentials.refreshToken)

                if (refreshResult.isFailure) {
                    Timber.e("Failed to refresh token")
                    return Result.failure(Exception("Failed to refresh expired token: ${refreshResult.exceptionOrNull()?.message}"))
                }

                val authResponse = refreshResult.getOrNull()!!
                require(authResponse.accountId == credentials.accountId) {
                    "Epic refresh changed account identity"
                }
                val refreshedCredentials = EpicCredentials(
                    accessToken = authResponse.accessToken,
                    refreshToken = authResponse.refreshToken,
                    accountId = authResponse.accountId,
                    displayName = authResponse.displayName,
                    expiresAt = authResponse.expiresAt
                )

                if (!saveCredentialsIfCurrent(context, refreshedCredentials, operationGeneration)) {
                    return Result.failure(
                        IOException("Epic credential lifecycle changed during refresh"),
                    )
                }
                Timber.i("Access token refreshed successfully")

                return Result.success(refreshedCredentials)
            }

            if (!isCredentialGenerationCurrent(operationGeneration)) {
                return Result.failure(
                    IOException("Epic credential lifecycle changed while reading credentials"),
                )
            }
            Result.success(credentials)
        } catch (e: Exception) {
            Timber.e(e, "Error getting Epic credentials: ${e.message}")
            Result.failure(Exception("Error getting credentials: ${e.message}", e))
        }
    }

    /**
     * Get game launch token for authenticating with Epic Games Services
     * This should be called immediately before launching a game that requires online authentication
     *
     */
    suspend fun getGameLaunchToken(
        context: Context,
        namespace: String? = null,
        catalogItemId: String? = null,
        requiresOwnershipToken: Boolean = false
    ): Result<EpicGameToken> {
        val operationGeneration = captureCredentialGeneration()
        return try {
            // Get current valid credentials (will refresh if expired)
            val credentialsResult = getStoredCredentials(context)
            if (credentialsResult.isFailure) {
                return Result.failure(credentialsResult.exceptionOrNull() ?: Exception("Not authenticated"))
            }

            val credentials = credentialsResult.getOrNull()!!
            if (!isCredentialGenerationCurrent(operationGeneration)) {
                return Result.failure(IOException("Epic credential lifecycle changed before launch token"))
            }

            // Get game exchange token (required for all games)
            Timber.d("Getting game exchange token for launch...")
            val exchangeTokenResult = EpicAuthClient.getGameExchangeToken(credentials.accessToken)
            if (exchangeTokenResult.isFailure) {
                return Result.failure(exchangeTokenResult.exceptionOrNull() ?: Exception("Failed to get exchange token"))
            }
            val exchangeCode = exchangeTokenResult.getOrNull()!!

            // Get ownership token if required (for DRM-protected games)
            var ownershipTokenHex: String? = null
            if (requiresOwnershipToken) {
                if (namespace.isNullOrEmpty() || catalogItemId.isNullOrEmpty()) {
                    return Result.failure(Exception("Namespace and catalogItemId required for ownership token"))
                }

                val cachedHex = readCachedOwnershipTokenHex(
                    context,
                    credentials.accountId,
                    namespace,
                    catalogItemId,
                )
                if (cachedHex != null) {
                    Timber.d("Using cached ownership token for $namespace:$catalogItemId")
                    ownershipTokenHex = cachedHex
                } else {
                    Timber.d("Getting ownership token for $namespace:$catalogItemId...")
                    val ownershipResult = EpicAuthClient.getOwnershipToken(
                        accessToken = credentials.accessToken,
                        accountId = credentials.accountId,
                        namespace = namespace,
                        catalogItemId = catalogItemId
                    )

                    if (ownershipResult.isFailure) {
                        val error = ownershipResult.exceptionOrNull()?.message ?: "Unknown error"
                        Timber.e("Failed to get required ownership token: $error")
                        return Result.failure(
                            Exception("Failed to get ownership token for DRM-protected game: $error")
                        )
                    } else {
                        // Convert binary token to hex string for easier handling
                        // Use toInt() and 0xFF to prevent sign extension of negative bytes
                        val tokenBytes = ownershipResult.getOrNull()!!
                        ownershipTokenHex = tokenBytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                        if (!isCredentialGenerationCurrent(operationGeneration)) {
                            return Result.failure(
                                IOException("Epic credential lifecycle changed during ownership token"),
                            )
                        }
                        val cached = writeOwnershipTokenHex(
                            context = context,
                            accountId = credentials.accountId,
                            namespace = namespace,
                            catalogItemId = catalogItemId,
                            hex = ownershipTokenHex,
                            expectedGeneration = operationGeneration,
                        )
                        if (!cached) {
                            return Result.failure(
                                IOException("Epic credential lifecycle changed during ownership token"),
                            )
                        }
                        Timber.d("Ownership token obtained (${tokenBytes.size} bytes) and cached")
                    }
                }
            }

            if (!isCredentialGenerationCurrent(operationGeneration)) {
                return Result.failure(
                    IOException("Epic credential lifecycle changed during launch token"),
                )
            }
            val gameToken = EpicGameToken(
                authCode = exchangeCode,
                accountId = credentials.accountId,
                displayName = credentials.displayName,
                ownershipToken = ownershipTokenHex
            )

            Timber.i("Successfully obtained game launch token")
            Result.success(gameToken)
        } catch (e: Exception) {
            Timber.e(e, "Failed to get game launch token")
            Result.failure(e)
        }
    }

    suspend fun logout(context: Context): Result<Unit> =
        if (clearStoredCredentials(context)) {
            Timber.i("Epic credentials cleared")
            Result.success(Unit)
        } else {
            Result.failure(IOException("Unable to delete Epic credentials"))
        }

    internal fun captureCredentialGeneration(): Long = synchronized(credentialLifecycleLock) {
        credentialGeneration
    }

    private fun isCredentialGenerationCurrent(expectedGeneration: Long): Boolean =
        synchronized(credentialLifecycleLock) {
            credentialGeneration == expectedGeneration
        }

    internal fun saveCredentials(context: Context, credentials: EpicCredentials) {
        val expectedGeneration = captureCredentialGeneration()
        check(saveCredentialsIfCurrent(context, credentials, expectedGeneration)) {
            "Epic credential lifecycle changed while saving credentials"
        }
    }

    internal fun saveCredentialsIfCurrent(
        context: Context,
        credentials: EpicCredentials,
        expectedGeneration: Long,
    ): Boolean = synchronized(credentialLifecycleLock) {
        if (credentialGeneration != expectedGeneration) return@synchronized false

        val file = File(getCredentialsFilePath(context))
        val priorAccountId = runCatching {
            if (file.isFile) JSONObject(file.readText()).optString("account_id").ifBlank { null } else null
        }.getOrNull()
        val json = JSONObject().apply {
            put("access_token", credentials.accessToken)
            put("refresh_token", credentials.refreshToken)
            put("account_id", credentials.accountId)
            put("display_name", credentials.displayName)
            put("expires_at", credentials.expiresAt)
        }

        val accountChanged = priorAccountId != credentials.accountId
        AccountScopeInvalidations.runLifecycleChange(
            source = GameSource.EPIC,
            shouldAdvance = accountChanged,
        ) {
            replaceFileAtomically(file) { output ->
                output.write(json.toString().toByteArray(Charsets.UTF_8))
            }
            if (accountChanged) {
                credentialGeneration++
            }

            Timber.d("Credentials saved")
            true
        }
    }

    private fun loadCredentials(context: Context): EpicCredentials? =
        synchronized(credentialLifecycleLock) {
            try {
                val file = File(getCredentialsFilePath(context))
                if (!file.exists()) return@synchronized null

                val json = JSONObject(file.readText())
                EpicCredentials(
                    accessToken = json.getString("access_token"),
                    refreshToken = json.getString("refresh_token"),
                    accountId = json.getString("account_id"),
                    displayName = json.getString("display_name"),
                    expiresAt = json.getLong("expires_at"),
                )
            } catch (error: Exception) {
                Timber.e(error, "Failed to load credentials")
                null
            }
        }

    internal fun replaceFileAtomically(
        file: File,
        writeContents: (FileOutputStream) -> Unit,
    ) {
        val parent = requireNotNull(file.parentFile) { "Atomic replacement requires a parent directory" }
        parent.mkdirs()
        val temporaryFile = Files.createTempFile(
            parent.toPath(),
            "${file.name}.",
            ".tmp",
        ).toFile()

        try {
            FileOutputStream(temporaryFile).use { output ->
                writeContents(output)
                output.fd.sync()
            }
            Files.move(
                temporaryFile.toPath(),
                file.toPath(),
                ATOMIC_MOVE,
                REPLACE_EXISTING,
            )
        } finally {
            temporaryFile.delete()
        }
    }
}
