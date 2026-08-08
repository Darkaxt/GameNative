package app.gamenative.library.canonical.catalog

import app.gamenative.library.metadata.MetadataLocale
import app.gamenative.library.metadata.SteamUrlPolicy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import timber.log.Timber

@RunWith(RobolectricTestRunner::class)
class SteamCatalogSearchProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var logs: MutableList<String>
    private lateinit var logTree: Timber.Tree

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        logs = mutableListOf()
        logTree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logs += listOfNotNull(tag, message, t?.message).joinToString(" ")
            }
        }
        Timber.plant(logTree)
    }

    @After
    fun tearDown() {
        Timber.uproot(logTree)
        server.shutdown()
    }

    @Test
    fun encodesOnlyTrimmedTermCountryAndLanguage() = runTest {
        server.enqueue(fixtureResponse())

        val results = provider().search("  A&B + Deluxe  ", MetadataLocale("pt-BR", "br"))

        assertEquals(2, results.size)
        val request = server.takeRequest()
        assertEquals("/api/storesearch/", request.requestUrl?.encodedPath)
        assertEquals(setOf("term", "cc", "l"), request.requestUrl?.queryParameterNames)
        assertEquals("A&B + Deluxe", request.requestUrl?.queryParameter("term"))
        assertEquals("BR", request.requestUrl?.queryParameter("cc"))
        assertEquals("brazilian", request.requestUrl?.queryParameter("l"))
        assertEquals("no-store", request.getHeader("Cache-Control"))
        assertNull(request.getHeader("Cookie"))
    }

    @Test
    fun productionPolicyRequiresExactHttpsSteamStoreSearchUrl() {
        val policy = SteamUrlPolicy()
        val allowed = "https://store.steampowered.com/api/storesearch/?term=Example&cc=US&l=english"
            .toHttpUrlForTest()

        assertTrue(policy.isAllowedStoreSearchRequest(allowed))
        listOf(
            "http://store.steampowered.com/api/storesearch/",
            "https://store.steampowered.com:444/api/storesearch/",
            "https://store.steampowered.com.evil.example/api/storesearch/",
            "https://user@store.steampowered.com/api/storesearch/",
            "https://store.steampowered.com/api/storesearch/#fragment",
            "https://store.steampowered.com/api/storesearch",
            "https://store.steampowered.com/api/appdetails",
            "https://store.steampowered.com/api/storesearch/?term=x&extra=y",
        ).forEach { raw ->
            assertFalse(raw, policy.isAllowedStoreSearchRequest(raw.toHttpUrlForTest()))
        }
    }

    @Test
    fun rejectsUnsafeEndpointBeforeNetwork() = runTest {
        val unsafeEndpoints = listOf(
            server.url("/api/appdetails"),
            server.url("/api/storesearch/").newBuilder().username("user").build(),
            server.url("/api/storesearch/").newBuilder().fragment("fragment").build(),
        )

        unsafeEndpoints.forEach { endpoint ->
            assertFixedFailure { provider(endpoint = endpoint).search("Marker query", locale()) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun rejectsCrossHostAndCleartextRedirects() = runTest {
        listOf(
            "https://store.steampowered.com.evil.example/api/storesearch/",
            "http://store.steampowered.com/api/storesearch/",
        ).forEach { location ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", location))
            assertFixedFailure { provider().search("Marker query", locale()) }
        }

        assertEquals(2, server.requestCount)
    }

    @Test
    fun followsOnlyRevalidatedSameHostRedirects() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(302).setHeader("Location", "/api/storesearch/"),
        )
        server.enqueue(fixtureResponse())

        val results = provider().search("Example", locale())

        assertEquals(listOf(42, 84), results.map(SteamStoreSearchHit::steamAppId))
        assertEquals(2, server.requestCount)
        assertEquals("no-store", server.takeRequest().getHeader("Cache-Control"))
        assertEquals("no-store", server.takeRequest().getHeader("Cache-Control"))
    }

    @Test
    fun revalidatesTheEffectiveResponseRequestUrl() = runTest {
        server.enqueue(fixtureResponse())
        val rewritingClient = testClient().newBuilder()
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                response.newBuilder()
                    .request(
                        response.request.newBuilder().url(server.url("/wrong-path")).build(),
                    )
                    .build()
            }
            .build()

        assertFixedFailure {
            provider(client = rewritingClient).search("Marker query", locale())
        }
        assertEquals("/api/storesearch/", server.takeRequest().requestUrl?.encodedPath)
    }

    @Test
    fun rejectsExcessRedirectHops() = runTest {
        repeat(5) {
            server.enqueue(
                MockResponse().setResponseCode(302).setHeader("Location", "/api/storesearch/"),
            )
        }

        assertFixedFailure { provider().search("Marker query", locale()) }

        assertEquals(4, server.requestCount)
    }

    @Test
    fun rejectsBlankOrOverlongQueriesBeforeNetwork() = runTest {
        assertIllegalArgument { provider().search("   ", locale()) }
        val overlong = buildString { repeat(257) { appendCodePoint(0x1F3AE) } }
        assertIllegalArgument { provider().search(overlong, locale()) }

        assertEquals(0, server.requestCount)
    }

    @Test
    fun rejectsOversizedAndMalformedResponses() = runTest {
        server.enqueue(MockResponse().setBody("x".repeat(129)))
        assertFixedFailure {
            provider(maxResponseBytes = 128L).search("Marker query", locale())
        }
        server.enqueue(MockResponse().setBody("not json Marker response"))
        assertFixedFailure { provider().search("Marker query", locale()) }
    }

    @Test
    fun dropsMalformedDuplicateAndNonpositiveHits() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "items": [
                    {"type":"app","id":42,"name":"First"},
                    {"type":"app","id":42,"name":"Duplicate"},
                    {"type":"app","id":0,"name":"Zero"},
                    {"type":"app","id":-7,"name":"Negative"},
                    {"type":"bundle","id":84,"name":"Bundle"},
                    {"type":"app","id":84,"name":"Second"},
                    {"type":"app","id":126,"name":"   "},
                    null
                  ]
                }
                """.trimIndent(),
            ),
        )

        val results = provider().search("Example", locale())

        assertEquals(listOf(42, 84), results.map(SteamStoreSearchHit::steamAppId))
        assertEquals(listOf("First", "Second"), results.map(SteamStoreSearchHit::title))
    }

    @Test
    fun returnsAtMostTenUniqueHits() = runTest {
        val items = (1..12).joinToString(",") { id ->
            """{"type":"app","id":$id,"name":"Result $id"}"""
        }
        server.enqueue(MockResponse().setBody("""{"items":[$items]}"""))

        val results = provider().search("Example", locale())

        assertEquals((1..10).toList(), results.map(SteamStoreSearchHit::steamAppId))
    }

    @Test
    fun cancellationEscapesAndCancelsTheHttpCall() = runTest {
        server.enqueue(
            fixtureResponse().setBodyDelay(1, TimeUnit.MINUTES),
        )
        val job = launch { provider().search("Marker query", locale()) }
        while (server.requestCount == 0) {
            kotlinx.coroutines.yield()
        }

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
    }

    @Test
    fun failuresNeverLogQueryResponseOrUrlText() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Marker response"))

        assertFixedFailure { provider().search("Marker query", locale()) }

        val output = logs.joinToString("\n")
        assertFalse(output.contains("Marker query"))
        assertFalse(output.contains("Marker response"))
        assertFalse(output.contains(server.hostName))
        assertFalse(output.contains("storesearch"))
    }

    private fun provider(
        endpoint: HttpUrl = server.url("/api/storesearch/"),
        client: OkHttpClient = testClient(),
        maxResponseBytes: Long = 1_000_000L,
    ) = SteamCatalogSearchProvider(
        client = client,
        endpoint = endpoint,
        urlPolicy = SteamUrlPolicy(
            apiHosts = setOf(server.hostName),
            mediaHosts = SteamUrlPolicy.STEAM_MEDIA_HOSTS,
            requireHttps = false,
            allowedPorts = setOf(server.port, 443),
        ),
        maxResponseBytes = maxResponseBytes,
    )

    private fun testClient(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private fun locale() = MetadataLocale("en-US", "US")

    private fun fixtureResponse() = MockResponse().setBody(
        requireNotNull(javaClass.getResource("/steam/store-search.json")).readText(),
    )

    private suspend fun assertFixedFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected Steam catalog search failure")
        } catch (error: SteamCatalogSearchException) {
            assertEquals("Steam catalog search unavailable", error.message)
        }
    }

    private suspend fun assertIllegalArgument(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected invalid search input")
        } catch (_: IllegalArgumentException) {
        }
    }
}

private fun String.toHttpUrlForTest(): HttpUrl = toHttpUrl()
