package app.gamenative.service.amazon

import app.gamenative.utils.Net
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/** Token response from Amazon device registration. */
data class AmazonAuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int,
    val tokenType: String,
)

/** Low-level client for Amazon OAuth/device-auth APIs. */
object AmazonAuthClient {

    private val httpClient = Net.http
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** Exchange a PKCE authorization code for access and refresh tokens. */
    suspend fun registerDevice(
        authorizationCode: String,
        codeVerifier: String,
        deviceSerial: String,
        clientId: String,
    ): Result<AmazonAuthResponse> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("auth_data", JSONObject().apply {
                    put("authorization_code", authorizationCode)
                    put("client_domain", "DeviceLegacy")
                    put("client_id", clientId)
                    put("code_algorithm", "SHA-256")
                    put("code_verifier", codeVerifier)
                    put("use_global_authentication", false)
                })
                put("registration_data", JSONObject().apply {
                    put("app_name", AmazonConstants.APP_NAME)
                    put("app_version", AmazonConstants.APP_VERSION)
                    put("device_model", "Windows")
                    put("device_name", JSONObject.NULL)
                    put("device_serial", deviceSerial)
                    put("device_type", AmazonConstants.DEVICE_TYPE)
                    put("domain", "Device")
                    put("os_version", "10.0.19044.0")
                })
                put("requested_extensions", JSONArray().apply { put("customer_info"); put("device_info") })
                put("requested_token_type", JSONArray().apply { put("bearer"); put("mac_dms") })
                put("user_context_map", JSONObject())
            }

            val request = Request.Builder()
                .url(AmazonConstants.AUTH_REGISTER_URL)
                .header("User-Agent", AmazonConstants.USER_AGENT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()

            val response = httpClient.newCall(request).awaitAmazonResponse { received ->
                received.body?.string()
            }
            val responseBody = response.body.orEmpty()

            if (!response.isSuccessful) {
                Timber.e("[Amazon] Device registration failed: HTTP ${response.code}")
                return@withContext Result.failure(Exception("HTTP ${response.code}"))
            }

            val json = JSONObject(responseBody)

            // The response nests tokens inside response → success → tokens → bearer
            val tokensObj = json
                .getJSONObject("response")
                .getJSONObject("success")
                .getJSONObject("tokens")
                .getJSONObject("bearer")

            val authResponse = AmazonAuthResponse(
                accessToken = tokensObj.getString("access_token"),
                refreshToken = tokensObj.getString("refresh_token"),
                expiresIn = tokensObj.optInt("expires_in", 3600),
                tokenType = tokensObj.optString("token_type", "bearer"),
            )

            Timber.i("[Amazon] Device registration successful")
            Result.success(authResponse)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.e("[Amazon] Device registration exception: ${error.javaClass.simpleName}")
            Result.failure(error)
        }
    }

    /** Refresh an access token using a stored refresh token. */
    suspend fun refreshAccessToken(
        refreshToken: String,
        clientId: String,
    ): Result<AmazonAuthResponse> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("source_token", refreshToken)
                put("source_token_type", "refresh_token")
                put("requested_token_type", "access_token")
                put("app_name", AmazonConstants.APP_NAME)
                put("app_version", AmazonConstants.APP_VERSION)
            }

            val request = Request.Builder()
                .url(AmazonConstants.AUTH_TOKEN_URL)
                .header("User-Agent", AmazonConstants.USER_AGENT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("x-amzn-identity-auth-domain", "api.amazon.com")
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()

            val response = httpClient.newCall(request).awaitAmazonResponse { received ->
                received.body?.string()
            }
            val responseBody = response.body.orEmpty()

            if (!response.isSuccessful) {
                Timber.e("[Amazon] Token refresh failed: HTTP ${response.code}")
                return@withContext Result.failure(Exception("HTTP ${response.code}"))
            }

            val json = JSONObject(responseBody)

            val authResponse = AmazonAuthResponse(
                accessToken = json.getString("access_token"),
                refreshToken = refreshToken, // refresh token stays the same
                expiresIn = json.optInt("expires_in", 3600),
                tokenType = json.optString("token_type", "bearer"),
            )

            Timber.i("[Amazon] Token refresh successful")
            Result.success(authResponse)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.e("[Amazon] Token refresh exception: ${error.javaClass.simpleName}")
            Result.failure(error)
        }
    }

    /** De-register a device as part of logout. */
    suspend fun deregisterDevice(
        accessToken: String,
        deviceSerial: String,
        clientId: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("requested_extensions", JSONArray().apply { put("device_info"); put("customer_info") })
            }

            val request = Request.Builder()
                .url(AmazonConstants.AUTH_DEREGISTER_URL)
                .header("User-Agent", AmazonConstants.USER_AGENT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $accessToken")
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()

            val response = httpClient.newCall(request).awaitAmazonResponse { Unit }
            if (!response.isSuccessful) {
                Timber.w("[Amazon] Deregister returned HTTP ${response.code}")
                // Non-fatal: credentials will still be cleared locally
            } else {
                Timber.i("[Amazon] Device deregistered successfully")
            }

            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.w("[Amazon] Device deregister exception: ${error.javaClass.simpleName}")
            // Still succeed locally – we'll clear creds regardless
            Result.success(Unit)
        }
    }
}
