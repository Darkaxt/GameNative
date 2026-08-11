package app.gamenative.library.community

import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SteamReviewPageProviderTest {
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
    fun `fetch maps bounded reviews with transient stable identity`() = runTest {
        server.enqueue(
            jsonResponse(
                """
                {
                  "success": 1,
                  "cursor": "next cursor",
                  "reviews": [{
                    "recommendationid": "synthetic-review-id",
                    "author": {"steamid": "private-steamid", "playtime_forever": 125},
                    "review": "Synthetic review text",
                    "timestamp_created": 100,
                    "timestamp_updated": 120,
                    "voted_up": true,
                    "votes_up": 4,
                    "votes_funny": 2,
                    "comment_count": 1,
                    "received_for_free": false,
                    "written_during_early_access": true,
                    "developer_response": "Synthetic developer response"
                  }]
                }
                """.trimIndent(),
            ),
        )

        val page = provider().fetch(
            steamAppId = 42,
            query = SteamReviewQuery(
                sort = SteamReviewSort.RECENT,
                polarity = SteamReviewPolarity.POSITIVE,
                language = SteamReviewLanguage.ALL,
                purchaseType = SteamReviewPurchaseType.STEAM,
            ),
            cursor = "start cursor",
        )

        assertEquals("next cursor", page.nextCursor)
        assertEquals(
            SteamReviewCard(
                recommended = true,
                text = "Synthetic review text",
                playtimeMinutes = 125,
                helpfulVotes = 4,
                funnyVotes = 2,
                commentCount = 1,
                postedAtEpochSeconds = 100,
                updatedAtEpochSeconds = 120,
                receivedForFree = false,
                earlyAccess = true,
                developerResponse = "Synthetic developer response",
                recommendationId = "synthetic-review-id",
            ),
            page.reviews.single(),
        )
        val request = server.takeRequest()
        assertEquals("/appreviews/42", request.requestUrl?.encodedPath)
        assertEquals("recent", request.requestUrl?.queryParameter("filter"))
        assertEquals("positive", request.requestUrl?.queryParameter("review_type"))
        assertEquals("all", request.requestUrl?.queryParameter("language"))
        assertEquals("steam", request.requestUrl?.queryParameter("purchase_type"))
        assertEquals("20", request.requestUrl?.queryParameter("num_per_page"))
        assertEquals("start cursor", request.requestUrl?.queryParameter("cursor"))
        assertEquals("no-store", request.getHeader("Cache-Control"))
        assertNull(request.getHeader("Cookie"))
        assertFalse(page.toString().contains("private-steamid"))
        assertFalse(page.toString().contains("private-review-id"))
    }

    @Test
    fun `malformed oversized and redirected responses fail with fixed message`() = runTest {
        server.enqueue(jsonResponse("{"))
        assertUnavailable { provider().fetch(42, SteamReviewQuery(), null) }

        server.enqueue(jsonResponse("""{"success":{},"reviews":[]}"""))
        assertUnavailable { provider().fetch(42, SteamReviewQuery(), null) }

        server.enqueue(jsonResponse("x".repeat(1024 * 1024 + 1)))
        assertUnavailable { provider().fetch(42, SteamReviewQuery(), null) }

        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/elsewhere")))
        assertUnavailable { provider().fetch(42, SteamReviewQuery(), null) }
    }

    @Test
    fun `rate limits retry up to a fourth successful attempt`() = runTest {
        repeat(3) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(429)
                    .setHeader("Retry-After", "0"),
            )
        }
        server.enqueue(jsonResponse("""{"success":1,"reviews":[]}"""))

        assertEquals(
            emptyList<SteamReviewCard>(),
            provider().fetch(42, SteamReviewQuery(), null).reviews,
        )
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `four rate limits fail without a fifth request`() = runTest {
        repeat(4) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(429)
                    .setHeader("Retry-After", "0"),
            )
        }

        assertUnavailable { provider().fetch(42, SteamReviewQuery(), null) }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `successful response requires JSON content type`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("""{"success":1,"reviews":[]}"""),
        )

        assertUnavailable { provider().fetch(42, SteamReviewQuery(), null) }
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun provider() = SteamReviewPageProvider(
        client = OkHttpClient.Builder()
            .cache(null)
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .build(),
        endpoint = server.url("/appreviews"),
        allowedHosts = setOf(server.hostName),
        requireHttps = false,
        allowedPorts = setOf(server.port),
    )

    private suspend fun assertUnavailable(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("Expected unavailable response")
        } catch (error: IOException) {
            assertEquals("Steam reviews unavailable", error.message)
            assertTrue(error.stackTrace.isNotEmpty())
        }
    }
}
