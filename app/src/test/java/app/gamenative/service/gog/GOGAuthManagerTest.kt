package app.gamenative.service.gog

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GameSource
import app.gamenative.library.canonical.AccountScopeInvalidations
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
    manifest = Config.NONE,
    application = android.app.Application::class
)
class GOGAuthManagerTest {
    private lateinit var context: Context
    private lateinit var mockWebServer: MockWebServer
    private lateinit var tempDir: File

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            // Silence Timber logging in all tests
            Timber.uprootAll()
        }
    }

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        tempDir = kotlin.io.path.createTempDirectory("gogtest").toFile()
        context = object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
            override fun getFilesDir(): File = tempDir
        }
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        tempDir.deleteRecursively()
    }

    @Test
    fun testAuthenticateWithCode_success() = runTest {
        // Arrange
        val code = "good_code"
        val json = JSONObject().apply {
            put("access_token", "token123")
            put("refresh_token", "refresh123")
            put("user_id", "user123")
            put("expires_in", 3600)
        }.toString()

        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(json)
            .addHeader("Content-Type", "application/json"))

        // Act
        val invalidation = async(start = CoroutineStart.UNDISPATCHED) {
            AccountScopeInvalidations.forSource(GameSource.GOG).first()
        }
        val result = withMockedHttpClient(mockWebServer.url("/token").toString()) {
            GOGAuthManager.authenticateWithCode(context, code)
        }

        // Assert
        assertTrue(result.isSuccess)
        assertEquals(Unit, invalidation.await())
        val creds = result.getOrNull()!!
        assertEquals("token123", creds.accessToken)
        assertEquals("refresh123", creds.refreshToken)
        assertEquals("user123", creds.userId)

        // Verify request
        val request = mockWebServer.takeRequest()
        assertTrue(request.path?.startsWith("/token?") == true)
        assertTrue(request.path?.contains("authorization_code") == true)
    }

    @Test
    fun authenticationFinishingAfterClearCannotRecreateCredentials() = runTest {
        val controlledResponse = controlledTokenResponse("authenticated-token")
        mockWebServer.dispatcher = controlledResponse

        val authentication = async(Dispatchers.IO) {
            withMockedHttpClient(mockWebServer.url("/token").toString()) {
                GOGAuthManager.authenticateWithCode(context, "good_code")
            }
        }
        assertTrue(controlledResponse.requestStarted.await(10, TimeUnit.SECONDS))

        val invalidation = async(start = CoroutineStart.UNDISPATCHED) {
            AccountScopeInvalidations.forSource(GameSource.GOG).first()
        }
        assertTrue(GOGAuthManager.clearStoredCredentials(context))
        assertEquals(Unit, invalidation.await())
        controlledResponse.releaseResponse.countDown()

        assertTrue(authentication.await().isFailure)
        assertFalse(File(tempDir, "gog_auth.json").exists())
    }

    @Test
    fun testAuthenticateWithCode_failure() = runTest {
        // Arrange
        val code = "bad_code"
        val json = JSONObject().apply {
            put("error", "invalid_grant")
            put("error_description", "Invalid code")
        }.toString()

        mockWebServer.enqueue(MockResponse()
            .setResponseCode(400)
            .setBody(json)
            .addHeader("Content-Type", "application/json"))

        // Act
        val result = withMockedHttpClient(mockWebServer.url("/token").toString()) {
            GOGAuthManager.authenticateWithCode(context, code)
        }

        // Assert
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Invalid code") == true)
    }

    @Test
    fun testGetStoredCredentials_success() = runTest {
        val authJson = JSONObject().apply {
            put(GOGConstants.GOG_CLIENT_ID, JSONObject().apply {
                put("access_token", "token123")
                put("refresh_token", "refresh123")
                put("user_id", "user123")
                put("expires_in", 3600)
                put("loginTime", System.currentTimeMillis() / 1000.0 + 1000)
            })
        }.toString()
        val authFile = File(tempDir, "gog_auth.json")
        authFile.writeText(authJson)

        val result = GOGAuthManager.getStoredCredentials(context)
        assertTrue(result.isSuccess)
        val creds = result.getOrNull()!!
        assertEquals("token123", creds.accessToken)
    }

    @Test
    fun storedUserIdReadsOnlyLocalGalaxyIdentityWithoutRefreshing() {
        val authFile = File(tempDir, "gog_auth.json")
        val authJson = JSONObject().apply {
            put(GOGConstants.GOG_CLIENT_ID, JSONObject().apply {
                put("access_token", "local-only-access-token")
                put("refresh_token", "local-only-refresh-token")
                put("user_id", "stored-user-id")
                put("expires_in", 1)
                put("loginTime", 0)
            })
        }.toString()
        authFile.writeText(authJson)

        assertEquals("stored-user-id", GOGAuthManager.getStoredUserId(context))
        assertEquals(0, mockWebServer.requestCount)
        assertEquals(authJson, authFile.readText())
    }

    @Test
    fun storedUserIdReturnsNullForMissingMalformedOrBlankFiles() {
        val authFile = File(tempDir, "gog_auth.json")
        assertNull(GOGAuthManager.getStoredUserId(context))

        authFile.writeText("not-json")
        assertNull(GOGAuthManager.getStoredUserId(context))

        authFile.writeText(
            JSONObject()
                .put(GOGConstants.GOG_CLIENT_ID, JSONObject().put("user_id", ""))
                .toString(),
        )
        assertNull(GOGAuthManager.getStoredUserId(context))
        assertEquals(0, mockWebServer.requestCount)
    }

    @Test
    fun staleClearDoesNotDeleteReplacementCredentials() {
        val staleGeneration = GOGAuthManager.captureCredentialGeneration()
        assertTrue(GOGAuthManager.clearStoredCredentials(context))
        val replacement = File(tempDir, "gog_auth.json").apply {
            writeText("replacement-credentials")
        }

        val cleared = GOGAuthManager.clearStoredCredentialsIfCurrent(
            context = context,
            expectedGeneration = staleGeneration,
        )

        assertNull(cleared)
        assertEquals("replacement-credentials", replacement.readText())
    }

    @Test
    fun clearStoredCredentialsReturnsFalseWhenCredentialFileCannotBeDeleted() {
        val authPath = File(tempDir, "gog_auth.json").also { it.mkdirs() }
        File(authPath, "blocking-child").writeText("x")

        assertFalse(GOGAuthManager.clearStoredCredentials(context))
        assertTrue(authPath.exists())
    }

    @Test
    fun testGetStoredCredentials_expired_refreshSuccess() = runTest {
        // Arrange
        val authJson = JSONObject().apply {
            put(GOGConstants.GOG_CLIENT_ID, JSONObject().apply {
                put("access_token", "old_token")
                put("refresh_token", "refresh123")
                put("user_id", "user123")
                put("expires_in", 1)
                put("loginTime", 0)
            })
        }.toString()
        val authFile = File(tempDir, "gog_auth.json")
        authFile.writeText(authJson)

        // Mock refresh token response
        val refreshJson = JSONObject().apply {
            put("access_token", "new_token")
            put("refresh_token", "refresh123")
            put("user_id", "user123")
            put("expires_in", 3600)
        }.toString()

        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(refreshJson)
            .addHeader("Content-Type", "application/json"))

        // Act
        val result = withMockedHttpClient(mockWebServer.url("/token").toString()) {
            GOGAuthManager.getStoredCredentials(context)
        }

        // Assert
        assertTrue(result.isSuccess)
        val creds = result.getOrNull()!!
        assertEquals("new_token", creds.accessToken)

        // Verify refresh request
        val request = mockWebServer.takeRequest()
        assertTrue(request.path?.contains("refresh_token") == true)
    }

    @Test
    fun refreshCannotReplaceTheStoredAccountIdentity() = runTest {
        writeGalaxyCredentials(loginTime = 0.0, expiresIn = 1)
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    JSONObject()
                        .put("access_token", "new-token")
                        .put("refresh_token", "refresh-token")
                        .put("user_id", "different-user")
                        .put("expires_in", 3600)
                        .toString(),
                )
                .addHeader("Content-Type", "application/json"),
        )

        val result = withMockedHttpClient(mockWebServer.url("/token").toString()) {
            GOGAuthManager.getStoredCredentials(context)
        }

        assertTrue(result.isFailure)
        assertEquals("user123", GOGAuthManager.getStoredUserId(context))
    }

    @Test
    fun refreshFinishingAfterClearCannotRecreateCredentials() = runTest {
        writeGalaxyCredentials(loginTime = 0.0, expiresIn = 1)
        val controlledResponse = controlledTokenResponse("refreshed-token")
        mockWebServer.dispatcher = controlledResponse

        val refresh = async(Dispatchers.IO) {
            withMockedHttpClient(mockWebServer.url("/token").toString()) {
                GOGAuthManager.getStoredCredentials(context)
            }
        }
        assertTrue(controlledResponse.requestStarted.await(10, TimeUnit.SECONDS))

        assertTrue(GOGAuthManager.clearStoredCredentials(context))
        controlledResponse.releaseResponse.countDown()

        assertTrue(refresh.await().isFailure)
        assertFalse(File(tempDir, "gog_auth.json").exists())
    }

    @Test
    fun gameTokenFinishingAfterClearCannotRecreateCredentials() = runTest {
        writeGalaxyCredentials(
            loginTime = System.currentTimeMillis() / 1000.0,
            expiresIn = 3600,
        )
        val controlledResponse = controlledTokenResponse("game-token")
        mockWebServer.dispatcher = controlledResponse

        val gameToken = async(Dispatchers.IO) {
            withMockedHttpClient(mockWebServer.url("/token").toString()) {
                GOGAuthManager.getGameCredentials(context, "game-client", "game-secret")
            }
        }
        assertTrue(controlledResponse.requestStarted.await(10, TimeUnit.SECONDS))

        assertTrue(GOGAuthManager.clearStoredCredentials(context))
        controlledResponse.releaseResponse.countDown()

        assertTrue(gameToken.await().isFailure)
        assertFalse(File(tempDir, "gog_auth.json").exists())
    }

    @Test
    fun testValidateCredentials_success() = runTest {
        val authJson = JSONObject().apply {
            put(GOGConstants.GOG_CLIENT_ID, JSONObject().apply {
                put("access_token", "token123")
                put("refresh_token", "refresh123")
                put("user_id", "user123")
                put("expires_in", 3600)
                put("loginTime", System.currentTimeMillis() / 1000.0 + 1000)
            })
        }.toString()
        val authFile = File(tempDir, "gog_auth.json")
        authFile.writeText(authJson)

        val result = GOGAuthManager.validateCredentials(context)
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull() == true)
    }

    @Test
    fun testValidateCredentials_failure() = runTest {
        // No file created, so should fail
        val result = GOGAuthManager.validateCredentials(context)
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull() == false)
    }

    @Test
    fun testExtractCodeFromInput_withFullUrl() {
        val url = "https://embed.gog.com/on_login_success?code=ABC123XYZ&origin=client"
        val result = GOGAuthManager.extractCodeFromInput(url)
        assertEquals("ABC123XYZ", result)
    }

    @Test
    fun testExtractCodeFromInput_withUrlMultipleParams() {
        val url = "https://embed.gog.com/on_login_success?code=DEF456&origin=client&state=test"
        val result = GOGAuthManager.extractCodeFromInput(url)
        assertEquals("DEF456", result)
    }

    @Test
    fun testExtractCodeFromInput_withUrlNoCode() {
        val url = "https://embed.gog.com/on_login_success?origin=client"
        val result = GOGAuthManager.extractCodeFromInput(url)
        assertEquals("", result)
    }

    @Test
    fun testExtractCodeFromInput_withPlainCode() {
        val code = "PLAIN_CODE_123"
        val result = GOGAuthManager.extractCodeFromInput(code)
        assertEquals("PLAIN_CODE_123", result)
    }

    // --- Helpers ---
    private fun tokenResponse(accessToken: String): String = JSONObject().apply {
        put("access_token", accessToken)
        put("refresh_token", "refresh123")
        put("user_id", "user123")
        put("expires_in", 3600)
    }.toString()

    private fun controlledTokenResponse(accessToken: String): ControlledResponse = ControlledResponse(
        MockResponse()
            .setResponseCode(200)
            .setBody(tokenResponse(accessToken))
            .addHeader("Content-Type", "application/json"),
    )

    private fun writeGalaxyCredentials(loginTime: Double, expiresIn: Int) {
        File(tempDir, "gog_auth.json").writeText(
            JSONObject().apply {
                put(GOGConstants.GOG_CLIENT_ID, JSONObject().apply {
                    put("access_token", "old-token")
                    put("refresh_token", "refresh123")
                    put("user_id", "user123")
                    put("expires_in", expiresIn)
                    put("loginTime", loginTime)
                })
            }.toString(),
        )
    }

    private class ControlledResponse(
        private val response: MockResponse,
    ) : Dispatcher() {
        val requestStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)

        override fun dispatch(request: RecordedRequest): MockResponse {
            requestStarted.countDown()
            return if (releaseResponse.await(10, TimeUnit.SECONDS)) {
                response
            } else {
                MockResponse().setResponseCode(500)
            }
        }
    }

    private suspend fun <T> withMockedHttpClient(testTokenUrl: String, block: suspend () -> T): T {
        // Override the token URL to point to MockWebServer
        val originalTokenUrl = GOGAuthManager.tokenUrl
        GOGAuthManager.tokenUrl = testTokenUrl

        try {
            return block()
        } finally {
            GOGAuthManager.tokenUrl = originalTokenUrl
        }
    }
}
