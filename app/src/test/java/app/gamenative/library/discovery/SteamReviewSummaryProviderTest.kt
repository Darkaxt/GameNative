package app.gamenative.library.discovery

import java.io.IOException
import java.lang.reflect.Modifier
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class SteamReviewSummaryProviderTest {
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
    fun aggregateOnlyResponseUsesMinimumRequestAndReturnsOnlyTotal() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "success": 1,
                  "query_summary": {"total_reviews": 12345, "review_score_desc": "private-summary"},
                  "reviews": [{"author": {"steamid": "private-steamid"}, "review": "private-review-body"}]
                }
                """.trimIndent(),
            ),
        )

        val summary = provider().fetch(480)

        assertEquals(SteamReviewSummary(totalReviews = 12_345), summary)
        assertEquals(
            listOf("totalReviews"),
            SteamReviewSummary::class.java.declaredFields
                .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
                .map { it.name },
        )
        val request = server.takeRequest()
        assertEquals("/appreviews/480", request.requestUrl?.encodedPath)
        assertEquals("1", request.requestUrl?.queryParameter("json"))
        assertEquals("summary", request.requestUrl?.queryParameter("filter"))
        assertEquals("all", request.requestUrl?.queryParameter("language"))
        assertEquals("all", request.requestUrl?.queryParameter("purchase_type"))
        assertEquals("0", request.requestUrl?.queryParameter("num_per_page"))
        assertFalse(logs.joinToString("\n").contains("private-"))
    }

    @Test
    fun rejectsNegativeOverflowStringAndMissingTotals() = runTest {
        listOf(
            "{\"query_summary\":{\"total_reviews\":-1}}",
            "{\"query_summary\":{\"total_reviews\":2147483648}}",
            "{\"query_summary\":{\"total_reviews\":\"100\"}}",
            "{\"query_summary\":{}}",
        ).forEach { body ->
            server.enqueue(MockResponse().setBody(body))
            assertUnavailable { provider().fetch(480) }
        }
    }

    @Test
    fun malformedPrivateContentFailsWithFixedSafeException() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "{\"query_summary\":{\"total_reviews\":5}," +
                    "\"reviews\":[{\"review\":\"private-malformed-body\"}",
            ),
        )

        try {
            provider().fetch(480)
            fail("Expected malformed response failure")
        } catch (error: IOException) {
            assertEquals("Steam review summary unavailable", error.message)
        }
        assertFalse(logs.joinToString("\n").contains("private-malformed-body"))
    }

    @Test
    fun oversizedReviewContentIsDiscardedWithFixedSafeFailure() = runTest {
        val privateBody = "private-review-body".repeat(8_000)
        server.enqueue(
            MockResponse().setBody(
                "{\"query_summary\":{\"total_reviews\":5}," +
                    "\"reviews\":[{\"review\":\"$privateBody\"}]}",
            ),
        )

        try {
            provider().fetch(480)
            fail("Expected oversized response failure")
        } catch (error: IOException) {
            assertEquals("Steam review summary unavailable", error.message)
        }
        assertFalse(logs.joinToString("\n").contains("private-review-body"))
    }

    @Test
    fun rejectsUntrustedInitialRedirectAndFinalHosts() = runTest {
        val untrustedInitial = SteamReviewSummaryProvider(
            client = client(),
            endpoint = "https://store.steampowered.com.evil.example/appreviews".toHttpUrlForTest(),
            allowedHosts = setOf("store.steampowered.com"),
            requireHttps = true,
            allowedPorts = setOf(443),
        )
        assertUnavailable { untrustedInitial.fetch(480) }
        assertEquals(0, server.requestCount)

        server.enqueue(
            MockResponse().setResponseCode(302).setHeader(
                "Location",
                "https://store.steampowered.com.evil.example/appreviews/480",
            ),
        )
        assertUnavailable { provider().fetch(480) }
        assertEquals(1, server.requestCount)

        server.enqueue(MockResponse().setBody("{\"query_summary\":{\"total_reviews\":1}}"))
        val finalHostClient = client().newBuilder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            response.newBuilder()
                .request(
                    Request.Builder()
                        .url("https://store.steampowered.com.evil.example/appreviews/480")
                        .build(),
                )
                .build()
        }.build()
        val finalHostProvider = provider(finalHostClient)
        assertUnavailable { finalHostProvider.fetch(480) }
    }

    @Test
    fun followsOnlyTrustedRedirects() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(307).setHeader(
                "Location",
                server.url("/appreviews/480?json=1&filter=summary&language=all&purchase_type=all&num_per_page=0"),
            ),
        )
        server.enqueue(MockResponse().setBody("{\"query_summary\":{\"total_reviews\":7}}"))

        assertEquals(SteamReviewSummary(7), provider().fetch(480))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun cancellationEscapesCancelsHttpAndDoesNotLeakRequestOrContent() = runTest {
        server.enqueue(
            MockResponse()
                .setBody(
                    "{\"query_summary\":{\"total_reviews\":9}," +
                        "\"reviews\":[{\"review\":\"private-review-body\"}]}",
                )
                .setBodyDelay(1, TimeUnit.MINUTES),
        )
        val job = launch { provider().fetch(987654) }
        while (server.requestCount == 0) kotlinx.coroutines.yield()

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        val output = logs.joinToString("\n")
        assertFalse(output.contains("987654"))
        assertFalse(output.contains("appreviews"))
        assertFalse(output.contains(server.hostName))
        assertFalse(output.contains("private-review-body"))
    }

    private fun provider(client: OkHttpClient = client()) = SteamReviewSummaryProvider(
        client = client,
        endpoint = server.url("/appreviews"),
        allowedHosts = setOf(server.hostName),
        requireHttps = false,
        allowedPorts = setOf(server.port),
    )

    private fun client() = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private suspend fun assertUnavailable(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected Steam review summary to be unavailable")
        } catch (_: IOException) {
        }
    }
}

private fun String.toHttpUrlForTest() = toHttpUrl()
