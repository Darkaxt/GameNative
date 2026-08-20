package app.gamenative.library.metadata

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.EventListener
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

@RunWith(RobolectricTestRunner::class)
class SteamMediaDataSourceTest {
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
    fun followsApprovedAbsoluteRedirectsAndReturnsFinalBody() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", server.url("/final.jpg")),
        )
        server.enqueue(MockResponse().setBody("image bytes"))

        val body = source().open(server.url("/start.jpg").toString()).use {
            it.body.string()
        }

        assertEquals("image bytes", body)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun EpicMediaPolicyFollowsOnlyEpicApprovedRedirects() = runTest {
        server.enqueue(redirectTo("/final.jpg"))
        server.enqueue(MockResponse().setBody("Epic image bytes"))

        val body = epicSource().open(server.url("/start.jpg").toString()).use {
            it.body.string()
        }

        assertEquals("Epic image bytes", body)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun EpicMediaPolicyRejectsCrossProviderRedirect() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader(
                    "Location",
                    "https://shared.akamai.steamstatic.com/store_item_assets/image.jpg",
                ),
        )

        expectUnavailable {
            epicSource().open(server.url("/start.jpg").toString())
        }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun publicMediaRequestsRemainCacheEligibleAcrossRedirects() = runTest {
        server.enqueue(redirectTo("/final.jpg"))
        server.enqueue(MockResponse().setBody("image bytes"))

        source().open(server.url("/start.jpg").toString()).close()

        assertNull(server.takeRequest().getHeader("Cache-Control"))
        assertNull(server.takeRequest().getHeader("Cache-Control"))
    }

    @Test
    fun rejectsRelativeAndMalformedRedirectsWithoutAnotherRequest() = runTest {
        listOf("/relative.jpg", ":// malformed").forEach { location ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", location),
            )

            expectUnavailable {
                source().open(server.url("/start.jpg").toString())
            }
        }

        assertEquals(2, server.requestCount)
    }

    @Test
    fun rejectsUntrustedRedirectWithoutLeakingItsUrl() = runTest {
        val untrusted = "https://media.example.invalid/private/image.jpg"
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", untrusted),
        )

        val error = expectUnavailable {
            source().open(server.url("/start.jpg").toString())
        }

        assertFalse(error.message.orEmpty().contains(untrusted))
        assertFalse(error.message.orEmpty().contains(server.hostName))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun rejectsRedirectLoopBeforeRepeatingARequest() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", server.url("/second.jpg")),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", server.url("/start.jpg")),
        )

        expectUnavailable {
            source().open(server.url("/start.jpg").toString())
        }

        assertEquals(2, server.requestCount)
    }

    @Test
    fun rejectsRedirectsBeyondStrictHopBound() = runTest {
        server.enqueue(redirectTo("/second.jpg"))
        server.enqueue(redirectTo("/third.jpg"))
        server.enqueue(redirectTo("/fourth.jpg"))

        expectUnavailable {
            source(maxRedirects = 2).open(server.url("/start.jpg").toString())
        }

        assertEquals(3, server.requestCount)
    }

    @Test
    fun closesIntermediateRedirectResponses() = runTest {
        val completedBodies = AtomicInteger()
        val client = OkHttpClient.Builder()
            .eventListener(
                object : EventListener() {
                    override fun responseBodyEnd(call: Call, byteCount: Long) {
                        completedBodies.incrementAndGet()
                    }
                },
            )
            .build()
        server.enqueue(redirectTo("/final.jpg").setBody("discarded redirect body"))
        server.enqueue(MockResponse().setBody("image bytes"))

        val finalResponse = source(client = client).open(server.url("/start.jpg").toString())

        assertEquals(1, completedBodies.get())
        finalResponse.close()
        assertEquals(2, completedBodies.get())
    }

    @Test
    fun cancellationEscapesAndCancelsActiveMediaCall() = runTest {
        server.enqueue(
            MockResponse()
                .setBody("image bytes")
                .setHeadersDelay(1, TimeUnit.MINUTES),
        )
        val job = launch {
            source().open(server.url("/slow.jpg").toString()).close()
        }
        while (server.requestCount == 0) {
            kotlinx.coroutines.yield()
        }

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
    }

    private fun source(
        client: OkHttpClient = OkHttpClient(),
        maxRedirects: Int = 3,
    ): SteamMediaDataSource = SteamMediaDataSource(
        baseClient = client,
        urlPolicy = SteamUrlPolicy(
            apiHosts = emptySet(),
            mediaHosts = setOf(server.hostName),
            requireHttps = false,
            allowedPorts = setOf(server.port),
        ),
        maxRedirects = maxRedirects,
    )

    private fun epicSource(): SteamMediaDataSource = SteamMediaDataSource(
        baseClient = OkHttpClient(),
        urlPolicy = EpicUrlPolicy(
            cmsHosts = emptySet(),
            mediaRoots = setOf(server.hostName),
            requireHttps = false,
            allowedPorts = setOf(server.port),
        ),
    )

    private fun redirectTo(path: String): MockResponse = MockResponse()
        .setResponseCode(302)
        .setHeader("Location", server.url(path))

    private suspend fun expectUnavailable(block: suspend () -> Unit): SteamMediaException = try {
        block()
        fail("Expected Steam media request to fail closed")
        error("unreachable")
    } catch (error: SteamMediaException) {
        error
    }
}
