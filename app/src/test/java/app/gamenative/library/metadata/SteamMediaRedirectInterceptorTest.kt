package app.gamenative.library.metadata

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SteamMediaRedirectInterceptorTest {
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
    fun followsApprovedRedirectAndPreservesRangeRequest() {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", server.url("/final.webm")),
        )
        server.enqueue(MockResponse().setResponseCode(206).setBody("video bytes"))

        client().newCall(
            Request.Builder()
                .url(server.url("/start.webm"))
                .header("Range", "bytes=100-199")
                .build(),
        ).execute().use { response ->
            assertEquals(206, response.code)
            assertEquals("video bytes", response.body.string())
        }

        assertEquals("bytes=100-199", server.takeRequest().getHeader("Range"))
        assertEquals("bytes=100-199", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun EpicMediaPolicyPreservesRangeRequestAcrossApprovedRedirect() {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", server.url("/segment.m4s")),
        )
        server.enqueue(MockResponse().setResponseCode(206).setBody("Epic video bytes"))

        epicClient().newCall(
            Request.Builder()
                .url(server.url("/manifest.m3u8"))
                .header("Range", "bytes=200-399")
                .build(),
        ).execute().use { response ->
            assertEquals(206, response.code)
            assertEquals("Epic video bytes", response.body.string())
        }

        assertEquals("bytes=200-399", server.takeRequest().getHeader("Range"))
        assertEquals("bytes=200-399", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun EpicMediaPolicyRejectsCrossProviderRedirect() {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader(
                    "Location",
                    "https://video.akamai.steamstatic.com/store_trailers/video.m3u8",
                ),
        )

        expectUnavailable {
            epicClient().newCall(
                Request.Builder().url(server.url("/manifest.m3u8")).build(),
            ).execute()
        }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun rejectsUntrustedRedirectBeforeSendingAnotherRequest() {
        val untrusted = "https://media.example.invalid/private/video.webm"
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", untrusted),
        )

        val error = expectUnavailable {
            client().newCall(Request.Builder().url(server.url("/start.webm")).build()).execute()
        }

        assertFalse(error.message.orEmpty().contains(untrusted))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun rejectsRedirectLoopWithinStrictHopBound() {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", server.url("/second.webm")),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", server.url("/start.webm")),
        )

        expectUnavailable {
            client().newCall(Request.Builder().url(server.url("/start.webm")).build()).execute()
        }

        assertEquals(2, server.requestCount)
    }

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(
            SteamMediaRedirectInterceptor(
                urlPolicy = SteamUrlPolicy(
                    apiHosts = emptySet(),
                    mediaHosts = setOf(server.hostName),
                    requireHttps = false,
                    allowedPorts = setOf(server.port),
                ),
            ),
        )
        .build()

    private fun epicClient(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(
            SteamMediaRedirectInterceptor(
                urlPolicy = EpicUrlPolicy(
                    cmsHosts = emptySet(),
                    mediaRoots = setOf(server.hostName),
                    requireHttps = false,
                    allowedPorts = setOf(server.port),
                ),
            ),
        )
        .build()

    private fun expectUnavailable(block: () -> Unit): SteamMediaException = try {
        block()
        fail("Expected Steam media request to fail closed")
        error("unreachable")
    } catch (error: SteamMediaException) {
        error
    }
}
