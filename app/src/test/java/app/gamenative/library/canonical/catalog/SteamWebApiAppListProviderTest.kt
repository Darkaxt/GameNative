package app.gamenative.library.canonical.catalog

import app.gamenative.library.metadata.SteamUrlPolicy
import app.gamenative.service.steam.SteamWebApiKeyValidationResult
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SteamWebApiAppListProviderTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun paginatesGameCatalogWithHeaderCredentialAndNoTitleQuery() = runTest {
        server.enqueue(page(appId = 10, name = "First", haveMore = true, lastAppId = 10))
        server.enqueue(page(appId = 20, name = "Second", haveMore = false, lastAppId = 20))

        val entries = provider().fetchAll(API_KEY)

        assertEquals(listOf(10, 20), entries.map(SteamAppListEntry::steamAppId))
        assertEquals(listOf("First", "Second"), entries.map(SteamAppListEntry::title))
        val first = server.takeRequest()
        assertEquals("/IStoreService/GetAppList/v1/", first.requestUrl?.encodedPath)
        assertEquals(API_KEY, first.getHeader("x-webapi-key"))
        assertNull(first.requestUrl?.queryParameter("key"))
        assertNull(first.requestUrl?.queryParameter("term"))
        assertEquals("true", first.requestUrl?.queryParameter("include_games"))
        assertEquals("false", first.requestUrl?.queryParameter("include_dlc"))
        assertEquals("50000", first.requestUrl?.queryParameter("max_results"))
        val second = server.takeRequest()
        assertEquals("10", second.requestUrl?.queryParameter("last_appid"))
        assertEquals(API_KEY, second.getHeader("x-webapi-key"))
    }

    @Test
    fun omittedHaveMoreResultsCompletesTheTerminalPage() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "response": {
                    "apps": [
                      {"appid": 10, "name": "Final", "last_modified": 100}
                    ]
                  }
                }
                """.trimIndent(),
            ),
        )

        val entries = provider().fetchAll(API_KEY)

        assertEquals(listOf(10), entries.map(SteamAppListEntry::steamAppId))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun validatesCredentialWithOneBoundedHeaderOnlyRequest() = runTest {
        server.enqueue(page(appId = 10, name = "First", haveMore = true, lastAppId = 10))

        assertEquals(SteamWebApiKeyValidationResult.VALID, provider().validate(API_KEY))

        val request = server.takeRequest()
        assertEquals("/IStoreService/GetAppList/v1/", request.requestUrl?.encodedPath)
        assertEquals(API_KEY, request.getHeader("x-webapi-key"))
        assertNull(request.requestUrl?.queryParameter("key"))
        assertEquals("1", request.requestUrl?.queryParameter("max_results"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun distinguishesRejectedCredentialFromProviderFailure() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setBody("{}"))

        assertEquals(SteamWebApiKeyValidationResult.INVALID, provider().validate(API_KEY))
        assertEquals(SteamWebApiKeyValidationResult.INVALID, provider().validate(API_KEY))
        assertEquals(SteamWebApiKeyValidationResult.UNAVAILABLE, provider().validate(API_KEY))
        assertEquals(SteamWebApiKeyValidationResult.UNAVAILABLE, provider().validate(API_KEY))
    }

    @Test
    fun validationRejectsInvalidFormatAndOversizedResponse() = runTest {
        assertEquals(SteamWebApiKeyValidationResult.INVALID, provider().validate("not-a-key"))
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse().setBody("x".repeat(129)))

        assertEquals(
            SteamWebApiKeyValidationResult.UNAVAILABLE,
            provider(maxValidationResponseBytes = 128).validate(API_KEY),
        )
    }

    @Test
    fun validationCancellationEscapesAndCancelsTheHttpCall() = runTest {
        server.enqueue(
            page(appId = 10, name = "First", haveMore = true, lastAppId = 10)
                .setBodyDelay(1, TimeUnit.MINUTES),
        )
        val job = launch { provider().validate(API_KEY) }
        while (server.requestCount == 0) {
            kotlinx.coroutines.yield()
        }

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
    }

    @Test
    fun validationBodyReadDoesNotBlockTheCallerDispatcher() = runBlocking {
        server.enqueue(
            page(appId = 10, name = "First", haveMore = true, lastAppId = 10)
                .setBodyDelay(2, TimeUnit.SECONDS),
        )
        val callerDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val validation = launch(callerDispatcher) { provider().validate(API_KEY) }
        try {
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            delay(250)

            val callerResponsive = async(callerDispatcher) { true }

            assertEquals(true, withTimeoutOrNull(500) { callerResponsive.await() })
            validation.cancel()
            assertEquals(
                true,
                withTimeoutOrNull(500) {
                    validation.join()
                    true
                },
            )
        } finally {
            validation.cancelAndJoin()
            callerDispatcher.close()
        }
    }

    @Test
    fun paginationUsesAuthoritativeCursorWhenTheLastPublicTitleIsUnusable() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "response": {
                    "apps": [
                      {"appid": 10, "name": "First", "last_modified": 100},
                      {"appid": 20, "name": "   ", "last_modified": 100}
                    ],
                    "have_more_results": true,
                    "last_appid": 20
                  }
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(page(appId = 30, name = "Third", haveMore = false, lastAppId = 30))

        val entries = provider().fetchAll(API_KEY)

        assertEquals(listOf(10, 30), entries.map(SteamAppListEntry::steamAppId))
        server.takeRequest()
        assertEquals("20", server.takeRequest().requestUrl?.queryParameter("last_appid"))
    }

    @Test
    fun redirectsAndMalformedCursorsFailClosedWithoutForwardingCredential() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/other")))
        assertUnavailable { provider().fetchAll(API_KEY) }
        assertEquals(1, server.requestCount)

        server.enqueue(page(appId = 10, name = "First", haveMore = true, lastAppId = 0))
        assertUnavailable { provider().fetchAll(API_KEY) }
    }

    @Test
    fun rejectsOversizedResponses() = runTest {
        server.enqueue(MockResponse().setBody("x".repeat(129)))

        assertUnavailable { provider(maxResponseBytes = 128).fetchAll(API_KEY) }
    }

    private fun provider(
        endpoint: HttpUrl = server.url("/IStoreService/GetAppList/v1/"),
        maxResponseBytes: Long = 16L * 1024L * 1024L,
        maxValidationResponseBytes: Long = 64L * 1024L,
    ) = SteamWebApiAppListProvider(
        client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build(),
        endpoint = endpoint,
        urlPolicy = SteamUrlPolicy(
            webApiHosts = setOf(server.hostName),
            requireHttps = false,
            allowedPorts = setOf(server.port, 443),
        ),
        maxResponseBytes = maxResponseBytes,
        maxValidationResponseBytes = maxValidationResponseBytes,
    )

    private fun page(
        appId: Int,
        name: String,
        haveMore: Boolean,
        lastAppId: Int,
    ) = MockResponse().setBody(
        """
        {
          "response": {
            "apps": [
              {"appid": $appId, "name": "$name", "last_modified": 100}
            ],
            "have_more_results": $haveMore,
            "last_appid": $lastAppId
          }
        }
        """.trimIndent(),
    )

    private suspend fun assertUnavailable(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected Steam AppList failure")
        } catch (error: SteamCatalogSearchException) {
            assertEquals("Steam catalog search unavailable", error.message)
        }
    }

    private companion object {
        const val API_KEY = "0123456789abcdef0123456789ABCDEF"
    }
}
