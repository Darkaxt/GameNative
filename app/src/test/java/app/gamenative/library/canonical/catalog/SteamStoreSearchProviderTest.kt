package app.gamenative.library.canonical.catalog

import app.gamenative.library.metadata.MetadataLocale
import app.gamenative.library.metadata.SteamHttpRetryExecutor
import app.gamenative.library.metadata.SteamRateLimitExhaustedException
import app.gamenative.library.metadata.SteamUrlPolicy
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SteamStoreSearchProviderTest {
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
    fun searchesKeylessStoreEndpointWithEncodedLocaleAndCountry() = runTest {
        server.enqueue(MockResponse().setBody(searchFixture()))

        val hits = provider().search("Alan Wake 2", MetadataLocale("en-US", "us"))

        assertEquals(listOf(SteamStoreSearchHit(42, "Alan Wake 2", null)), hits)
        val request = server.takeRequest()
        assertEquals("/api/storesearch/", request.requestUrl?.encodedPath)
        assertEquals("Alan Wake 2", request.requestUrl?.queryParameter("term"))
        assertEquals("english", request.requestUrl?.queryParameter("l"))
        assertEquals("US", request.requestUrl?.queryParameter("cc"))
        assertTrue(request.headers["x-webapi-key"] == null)
    }

    @Test
    fun retriesRateLimitAndReturnsNoResultOnTypedExhaustion() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setBody(searchFixture()))
        assertEquals(1, provider().search("Example", MetadataLocale("en-US", "US")).size)

        repeat(4) { server.enqueue(MockResponse().setResponseCode(429)) }
        try {
            provider().search("Example", MetadataLocale("en-US", "US"))
            fail("Expected typed rate-limit exhaustion")
        } catch (_: SteamRateLimitExhaustedException) {
        }

        assertEquals(6, server.requestCount)
    }

    @Test
    fun rejectsSameHostWrongPathRedirectBeforeFollowingIt() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", server.url("/api/appdetails")),
        )

        try {
            provider().search("Example", MetadataLocale("en-US", "US"))
            fail("Expected endpoint-bound redirect rejection")
        } catch (_: SteamCatalogSearchException) {
        }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun deduplicatesPositiveAppsAndCapsResultsAtTen() = runTest {
        val items = buildList {
            add("""{"type":"app","name":"Game 1","id":1}""")
            add("""{"type":"app","name":"Duplicate","id":1}""")
            add("""{"type":"app","name":"Invalid","id":0}""")
            add("""{"type":"bundle","name":"Bundle","id":2}""")
            (2..20).forEach { id ->
                add("""{"type":"app","name":"Game $id","id":$id}""")
            }
        }
        server.enqueue(MockResponse().setBody("""{"total":24,"items":[${items.joinToString()}]}"""))

        val hits = provider().search("Game", MetadataLocale("en-US", "US"))

        assertEquals(10, hits.size)
        assertEquals((1..10).toList(), hits.map(SteamStoreSearchHit::steamAppId))
    }

    @Test
    fun malformedOrOversizedResponseFailsClosed() = runTest {
        server.enqueue(MockResponse().setBody("not-json"))
        server.enqueue(MockResponse().setBody(searchFixture() + " ".repeat(MAX_RESPONSE_BYTES + 1)))

        repeat(2) {
            try {
                provider().search("Example", MetadataLocale("en-US", "US"))
                fail("Expected invalid response to fail closed")
            } catch (_: SteamCatalogSearchException) {
            }
        }
    }

    private fun provider() = SteamStoreSearchProvider(
        client = OkHttpClient.Builder().followRedirects(false).build(),
        searchEndpoint = server.url("/api/storesearch/"),
        urlPolicy = SteamUrlPolicy(
            apiHosts = setOf(server.hostName),
            mediaHosts = SteamUrlPolicy.STEAM_MEDIA_HOSTS,
            requireHttps = false,
            allowedPorts = setOf(server.port, 443),
        ),
        retryExecutor = SteamHttpRetryExecutor(sleep = {}, nowEpochMs = { 0L }),
    )

    private fun searchFixture() =
        """{"total":1,"items":[{"type":"app","name":"Alan Wake 2","id":42}]}"""

    private companion object {
        const val MAX_RESPONSE_BYTES = 1024 * 1024
    }
}
