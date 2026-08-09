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
import org.junit.Before
import org.junit.Test

class SteamDiscussionProviderTest {
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
    fun `listing extracts bounded plain metadata and validated continuation`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                <html><body>
                  <div class="forum_topic">
                    <a class="forum_topic_overlay" href="/app/42/discussions/0/100/"></a>
                    <div class="forum_topic_name">First &amp; topic</div>
                    <div class="forum_topic_reply_count">12</div>
                    <div class="forum_topic_view_count">34</div>
                    <div class="forum_topic_lastpost">2 hours ago</div>
                    <div class="forum_topic_op"><a data-miniprofile="private-steamid">Private user</a></div>
                  </div>
                  <a class="pagebtn" rel="next" href="/app/42/discussions/?ctp=2">Next</a>
                </body></html>
                """.trimIndent(),
            ),
        )

        val listing = provider().fetchListing(42, null)

        assertEquals(
            SteamDiscussionSummary(
                title = "First & topic",
                replyCount = 12,
                activityLabel = "2 hours ago",
                route = "/app/42/discussions/0/100/",
                viewCount = 34,
            ),
            listing.threads.single(),
        )
        assertEquals("/app/42/discussions/?ctp=2", listing.nextRoute)
        val request = server.takeRequest()
        assertEquals("/app/42/discussions/", request.requestUrl?.encodedPath)
        assertEquals("no-store", request.getHeader("Cache-Control"))
        assertNull(request.getHeader("Cookie"))
        assertFalse(listing.toString().contains("Private user"))
        assertFalse(listing.toString().contains("private-steamid"))
    }

    @Test
    fun `thread extracts plain posts and discards identity markup`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                <html><body>
                  <div class="forum_op">
                    <a class="forum_author_link" data-miniprofile="private-steamid">Private user</a>
                    <div class="topic">Synthetic topic</div>
                    <div class="content">First <b>plain</b> post<script>secret()</script></div>
                  </div>
                  <div class="commentthread_comment_text">Second post</div>
                  <a class="pagebtn" rel="next" href="/app/42/discussions/0/100/?ctp=2">Next</a>
                </body></html>
                """.trimIndent(),
            ),
        )

        val thread = provider().fetchThread(42, "/app/42/discussions/0/100/")

        assertEquals("Synthetic topic", thread.title)
        assertEquals(listOf("First plain post", "Second post"), thread.posts.map(SteamDiscussionPost::text))
        assertEquals("/app/42/discussions/0/100/?ctp=2", thread.nextRoute)
        assertFalse(thread.toString().contains("Private user"))
        assertFalse(thread.toString().contains("private-steamid"))
        assertFalse(thread.toString().contains("secret"))
    }

    @Test
    fun `cross app routes redirects and oversized bodies fail closed`() = runTest {
        assertUnavailable {
            provider().fetchThread(42, "/app/99/discussions/0/100/")
        }

        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "/app/99/discussions/"),
        )
        assertUnavailable { provider().fetchListing(42, null) }

        server.enqueue(MockResponse().setBody("x".repeat(1024 * 1024 + 1)))
        assertUnavailable { provider().fetchListing(42, null) }
    }

    private fun provider() = SteamDiscussionProvider(
        client = OkHttpClient.Builder()
            .cache(null)
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .build(),
        endpoint = server.url("/"),
        allowedHosts = setOf(server.hostName),
        requireHttps = false,
        allowedPorts = setOf(server.port),
    )

    private suspend fun assertUnavailable(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("Expected unavailable response")
        } catch (error: IOException) {
            assertEquals("Steam discussions unavailable", error.message)
        }
    }
}
