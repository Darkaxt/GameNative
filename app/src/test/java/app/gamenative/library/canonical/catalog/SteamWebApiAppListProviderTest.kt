package app.gamenative.library.canonical.catalog

import app.gamenative.library.metadata.SteamUrlPolicy
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
