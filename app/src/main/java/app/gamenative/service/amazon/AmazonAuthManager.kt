package app.gamenative.service.amazon

import android.content.Context
import app.gamenative.data.AmazonCredentials
import app.gamenative.data.GameSource
import app.gamenative.library.canonical.AccountScopeInvalidations
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Manages Amazon authentication and credential lifecycle. */
object AmazonAuthManager {

    private val credentialLifecycleLock = Any()
    private var credentialGeneration = 0L

    /** In-flight PKCE state between start and code exchange. */
    private var pendingCodeVerifier: String? = null
    private var pendingDeviceSerial: String? = null
    private var pendingClientId: String? = null

    // ── Paths ───────────────────────────────────────────────────────────────

    private fun getCredentialsFilePath(context: Context): String {
        val dir = File(context.filesDir, "amazon")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "credentials.json").absolutePath
    }

    // ── Public query ────────────────────────────────────────────────────────

    fun hasStoredCredentials(context: Context): Boolean {
        return File(getCredentialsFilePath(context)).exists()
    }

    // ── Auth flow (step 1): prepare PKCE & return the sign-in URL ───────────

    /** Prepare a new PKCE session and return the sign-in URL. */
    fun startAuthFlow(): String {
        val serial = AmazonPKCEGenerator.generateDeviceSerial()
        val clientId = AmazonPKCEGenerator.generateClientId(serial)
        val verifier = AmazonPKCEGenerator.generateCodeVerifier()
        val challenge = AmazonPKCEGenerator.generateCodeChallenge(verifier)

        synchronized(credentialLifecycleLock) {
            credentialGeneration++
            pendingCodeVerifier = verifier
            pendingDeviceSerial = serial
            pendingClientId = clientId
        }

        val authUrl = AmazonConstants.buildAuthUrl(clientId, challenge)

        Timber.d("[Amazon] Auth flow started")

        return authUrl
    }

    // ── Auth flow (step 2): exchange auth-code for tokens ───────────────────

    /** Complete PKCE by exchanging an authorization code for tokens. */
    suspend fun authenticateWithCode(
        context: Context,
        authorizationCode: String,
    ): Result<AmazonCredentials> {
        val pending = synchronized(credentialLifecycleLock) {
            PendingAuthentication(
                generation = credentialGeneration,
                codeVerifier = pendingCodeVerifier,
                deviceSerial = pendingDeviceSerial,
                clientId = pendingClientId,
            )
        }
        val verifier = pending.codeVerifier
        val serial = pending.deviceSerial
        val clientId = pending.clientId

        if (verifier == null || serial == null || clientId == null) {
            return Result.failure(Exception("No pending auth flow – call startAuthFlow() first"))
        }

        return try {
            Timber.i("[Amazon] Exchanging auth code for tokens…")

            val result = AmazonAuthClient.registerDevice(
                authorizationCode = authorizationCode,
                codeVerifier = verifier,
                deviceSerial = serial,
                clientId = clientId,
            )

            if (result.isFailure) {
                val error = result.exceptionOrNull()
                Timber.e(
                    "[Amazon] Device registration failed: ${error?.javaClass?.simpleName ?: "Exception"}",
                )
                return Result.failure(error ?: Exception("Device registration failed"))
            }

            val authResponse = result.getOrNull()!!
            val expiresAt = System.currentTimeMillis() + (authResponse.expiresIn * 1000L)

            val credentials = AmazonCredentials(
                accessToken = authResponse.accessToken,
                refreshToken = authResponse.refreshToken,
                deviceSerial = serial,
                clientId = clientId,
                expiresAt = expiresAt,
                profileScopeId = UUID.randomUUID().toString(),
            )

            if (!saveCredentialsIfCurrent(
                    context = context,
                    credentials = credentials,
                    expectedGeneration = pending.generation,
                    clearPendingAuthentication = true,
                )
            ) {
                return Result.failure(IOException("Amazon credential lifecycle changed during authentication"))
            }

            Timber.i("[Amazon] Authentication successful")
            Result.success(credentials)
        } catch (e: Exception) {
            Timber.e("[Amazon] Authentication exception: ${e.javaClass.simpleName}")
            Result.failure(Exception("Authentication exception", e))
        }
    }

    // ── Get / refresh credentials ───────────────────────────────────────────

    /** Return stored credentials, refreshing access token when needed. */
    suspend fun getStoredCredentials(context: Context): Result<AmazonCredentials> {
        return try {
            val state = loadCredentialState(context)
            val credentials = state.credentials
                ?: return Result.failure(Exception("No stored credentials found"))
            val operationGeneration = state.generation

            // Check expiration (5-minute buffer)
            val bufferMs = 5 * 60 * 1000L
            if (System.currentTimeMillis() + bufferMs >= credentials.expiresAt) {
                Timber.d("[Amazon] Access token expired, refreshing…")

                val refreshResult = AmazonAuthClient.refreshAccessToken(
                    refreshToken = credentials.refreshToken,
                    clientId = credentials.clientId,
                )

                if (refreshResult.isFailure) {
                    Timber.e("[Amazon] Token refresh failed")
                    return Result.failure(Exception("Failed to refresh token"))
                }

                val auth = refreshResult.getOrNull()!!
                val refreshed = credentials.copy(
                    accessToken = auth.accessToken,
                    expiresAt = System.currentTimeMillis() + (auth.expiresIn * 1000L),
                )

                if (!saveCredentialsIfCurrent(context, refreshed, operationGeneration)) {
                    return Result.failure(IOException("Amazon credential lifecycle changed during refresh"))
                }
                Timber.i("[Amazon] Token refreshed successfully")
                return Result.success(refreshed)
            }

            if (!isCredentialGenerationCurrent(operationGeneration)) {
                return Result.failure(IOException("Amazon credential lifecycle changed while reading credentials"))
            }
            Result.success(credentials)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.e("[Amazon] Error getting credentials: ${error.javaClass.simpleName}")
            Result.failure(Exception("Error getting credentials", error))
        }
    }

    // ── Logout ──────────────────────────────────────────────────────────────

    /** Logout by best-effort deregister + mandatory local credential cleanup. */
    suspend fun logout(context: Context): Result<Unit> {
        val state = loadCredentialState(context)
        val credentialsCleared = clearStoredCredentialsIfCurrent(
            context = context,
            expectedGeneration = state.generation,
        )
        if (credentialsCleared == null) {
            return Result.success(Unit)
        }

        try {
            if (state.credentials != null) {
                AmazonAuthClient.deregisterDevice(
                    accessToken = state.credentials.accessToken,
                    deviceSerial = state.credentials.deviceSerial,
                    clientId = state.credentials.clientId,
                )
            }
        } catch (error: Exception) {
            Timber.e(
                "[Amazon] Remote logout failed: ${error.javaClass.simpleName}",
            )
        }

        return if (credentialsCleared) {
            Timber.i("[Amazon] Logged out successfully")
            Result.success(Unit)
        } else {
            Result.failure(IOException("Unable to delete Amazon credentials"))
        }
    }

    fun clearStoredCredentials(context: Context): Boolean {
        val expectedGeneration = captureCredentialGeneration()
        return clearStoredCredentialsIfCurrent(context, expectedGeneration) != false
    }

    internal fun clearStoredCredentialsIfCurrent(
        context: Context,
        expectedGeneration: Long,
    ): Boolean? = synchronized(credentialLifecycleLock) {
        if (credentialGeneration != expectedGeneration) return@synchronized null

        AccountScopeInvalidations.runLifecycleChange(GameSource.AMAZON) {
            credentialGeneration++
            clearPendingStateLocked()
            try {
                val file = File(getCredentialsFilePath(context))
                if (file.exists()) file.delete() else true
            } catch (e: Exception) {
                Timber.e("[Amazon] Failed to clear credentials: ${e.javaClass.simpleName}")
                false
            }
        }
    }

    /**
     * Returns the random local profile identity stored beside valid credentials.
     * Legacy credential JSON is migrated once without validating or refreshing tokens.
     */
    internal fun getOrCreateProfileScopeId(context: Context): String? =
        synchronized(credentialLifecycleLock) {
            getOrCreateProfileScopeIdLocked(context)
        }

    private fun getOrCreateProfileScopeIdLocked(context: Context): String? {
        val file = File(getCredentialsFilePath(context))
        if (!file.exists()) return null

        return runCatching {
            val json = JSONObject(file.readText())
            require(json.hasRequiredCredentialFields())

            val existing = json.optString("profile_scope_id").takeIf(String::isNotBlank)
            if (existing != null) {
                require(UUID.fromString(existing).toString() == existing)
                existing
            } else {
                UUID.randomUUID().toString().also { generated ->
                    json.put("profile_scope_id", generated)
                    AccountScopeInvalidations.runLifecycleChange(GameSource.AMAZON) {
                        replaceFileAtomically(file) { output ->
                            output.write(json.toString().toByteArray(Charsets.UTF_8))
                        }
                        credentialGeneration++
                    }
                }
            }
        }.onFailure { error ->
            Timber.w(
                "[Amazon] Unable to read or migrate profile scope: %s",
                error.javaClass.simpleName,
            )
        }.getOrNull()
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private fun clearPendingStateLocked() {
        pendingCodeVerifier = null
        pendingDeviceSerial = null
        pendingClientId = null
    }

    internal fun captureCredentialGeneration(): Long = synchronized(credentialLifecycleLock) {
        credentialGeneration
    }

    private fun isCredentialGenerationCurrent(expectedGeneration: Long): Boolean =
        synchronized(credentialLifecycleLock) {
            credentialGeneration == expectedGeneration
        }

    internal fun saveCredentials(context: Context, credentials: AmazonCredentials) {
        val expectedGeneration = captureCredentialGeneration()
        check(saveCredentialsIfCurrent(context, credentials, expectedGeneration)) {
            "Amazon credential lifecycle changed while saving credentials"
        }
    }

    internal fun saveCredentialsIfCurrent(
        context: Context,
        credentials: AmazonCredentials,
        expectedGeneration: Long,
        clearPendingAuthentication: Boolean = false,
    ): Boolean = synchronized(credentialLifecycleLock) {
        if (credentialGeneration != expectedGeneration) return@synchronized false

        require(UUID.fromString(credentials.profileScopeId).toString() == credentials.profileScopeId)

        val file = File(getCredentialsFilePath(context))
        val priorProfileScopeId = runCatching {
            if (file.isFile) {
                JSONObject(file.readText()).optString("profile_scope_id").ifBlank { null }
            } else {
                null
            }
        }.getOrNull()
        val json = JSONObject().apply {
            put("access_token", credentials.accessToken)
            put("refresh_token", credentials.refreshToken)
            put("device_serial", credentials.deviceSerial)
            put("client_id", credentials.clientId)
            put("expires_at", credentials.expiresAt)
            put("profile_scope_id", credentials.profileScopeId)
        }

        val accountChanged = priorProfileScopeId != credentials.profileScopeId
        AccountScopeInvalidations.runLifecycleChange(
            source = GameSource.AMAZON,
            shouldAdvance = accountChanged,
        ) {
            replaceFileAtomically(file) { output ->
                output.write(json.toString().toByteArray(Charsets.UTF_8))
            }
            if (clearPendingAuthentication) clearPendingStateLocked()
            if (accountChanged || clearPendingAuthentication) {
                credentialGeneration++
            }
            Timber.d("[Amazon] Credentials saved")
            true
        }
    }

    internal fun loadCredentials(context: Context): AmazonCredentials? =
        synchronized(credentialLifecycleLock) {
            loadCredentialsLocked(context)
        }

    private fun loadCredentialState(context: Context): LoadedCredentialState =
        synchronized(credentialLifecycleLock) {
            LoadedCredentialState(
                credentials = loadCredentialsLocked(context),
                generation = credentialGeneration,
            )
        }

    private fun loadCredentialsLocked(context: Context): AmazonCredentials? {
        val profileScopeId = getOrCreateProfileScopeIdLocked(context) ?: return null
        return runCatching {
            val json = JSONObject(File(getCredentialsFilePath(context)).readText())
            AmazonCredentials(
                accessToken = json.getString("access_token"),
                refreshToken = json.getString("refresh_token"),
                deviceSerial = json.getString("device_serial"),
                clientId = json.getString("client_id"),
                expiresAt = json.getLong("expires_at"),
                profileScopeId = profileScopeId,
            )
        }.onFailure { error ->
            Timber.e("[Amazon] Failed to load credentials: ${error.javaClass.simpleName}")
        }.getOrNull()
    }

    internal fun replaceFileAtomically(
        file: File,
        writeContents: (FileOutputStream) -> Unit,
    ) {
        val parent = requireNotNull(file.parentFile) { "Atomic replacement requires a parent directory" }
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

    private data class LoadedCredentialState(
        val credentials: AmazonCredentials?,
        val generation: Long,
    )

    private data class PendingAuthentication(
        val generation: Long,
        val codeVerifier: String?,
        val deviceSerial: String?,
        val clientId: String?,
    )

    private fun JSONObject.hasRequiredCredentialFields(): Boolean = runCatching {
        require(getString("access_token").isNotBlank())
        require(getString("refresh_token").isNotBlank())
        require(getString("device_serial").isNotBlank())
        require(getString("client_id").isNotBlank())
        getLong("expires_at")
        true
    }.getOrDefault(false)
}
