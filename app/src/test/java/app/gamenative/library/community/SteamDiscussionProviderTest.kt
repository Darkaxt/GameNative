package app.gamenative.library.community

import app.gamenative.diagnostics.DiagnosticOutcome
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
            htmlResponse(
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
                  <a class="pagebtn" rel="next" href="/app/42/discussions/?fp=2">Next</a>
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
        assertEquals("/app/42/discussions/?fp=2", listing.nextRoute)
        val request = server.takeRequest()
        assertEquals("/app/42/discussions/", request.requestUrl?.encodedPath)
        assertNull(request.getHeader("Cache-Control"))
        assertEquals(
            "GameNative/1.0 python-requests-compatible",
            request.getHeader("User-Agent"),
        )
        assertNull(request.getHeader("Cookie"))
        assertFalse(listing.toString().contains("Private user"))
        assertFalse(listing.toString().contains("private-steamid"))
    }

    @Test
    fun `thread extracts plain posts and discards identity markup`() = runTest {
        server.enqueue(
            htmlResponse(
                """
                <html><body>
                  <div class="forum_op" id="forum_op_100">
                    <a class="forum_author_link" data-miniprofile="private-steamid">Private user</a>
                    <div class="topic">Synthetic topic</div>
                    <div class="content">First <b>plain</b> post<script>secret()</script></div>
                  </div>
                  <div class="commentthread_comment" data-commentid="102">
                    <div class="commentthread_comment_text">Second post</div>
                  </div>
                  <a class="pagebtn" rel="next" href="/app/42/discussions/0/100/?ctp=2">Next</a>
                </body></html>
                """.trimIndent(),
            ),
        )

        val thread = provider().fetchThread(42, "/app/42/discussions/0/100/")

        assertEquals("Synthetic topic", thread.title)
        assertEquals(
            listOf(
                SteamDiscussionPost("First plain post", postId = "forum_op_100"),
                SteamDiscussionPost("Second post", postId = "comment:102"),
            ),
            thread.posts,
        )
        assertEquals("/app/42/discussions/0/100/?ctp=2", thread.nextRoute)
        assertFalse(thread.toString().contains("Private user"))
        assertFalse(thread.toString().contains("private-steamid"))
        assertFalse(thread.toString().contains("secret"))
    }

    @Test
    fun `current Steam paging summary supplies thread continuation without a next anchor`() = runTest {
        server.enqueue(
            htmlResponse(
                """
                <html><body>
                  <div class="forum_op" id="forum_op_100">
                    <div class="content">Opening post</div>
                  </div>
                  <div class="forum_paging_summary">
                    <span>1</span><span>15</span><span>46</span>
                  </div>
                </body></html>
                """.trimIndent(),
            ),
        )

        val thread = provider().fetchThread(42, "/app/42/discussions/0/100/")

        assertEquals("/app/42/discussions/0/100/?ctp=2", thread.nextRoute)
    }

    @Test
    fun `continued thread omits repeated opening post and maps stable reply identity`() = runTest {
        server.enqueue(
            htmlResponse(
                """
                <html><body>
                  <div class="forum_op" id="forum_op_1">
                    <div class="content">Repeated opening post</div>
                  </div>
                  <div class="forum_post" data-postid="202">
                    <div class="content">Page two reply</div>
                  </div>
                </body></html>
                """.trimIndent(),
            ),
        )

        val thread = provider().fetchThread(42, "/app/42/discussions/0/100/?ctp=2")

        assertEquals(
            listOf(SteamDiscussionPost("Page two reply", postId = "post:202")),
            thread.posts,
        )
    }

    @Test
    fun `id-less duplicate posts receive distinct transient page identities`() = runTest {
        server.enqueue(
            htmlResponse(
                """
                <html><body>
                  <div class="commentthread_comment">
                    <div class="commentthread_comment_text">Same valid post</div>
                  </div>
                  <div class="commentthread_comment">
                    <div class="commentthread_comment_text">Same valid post</div>
                  </div>
                </body></html>
                """.trimIndent(),
            ),
        )

        val thread = provider().fetchThread(42, "/app/42/discussions/0/100/")

        assertEquals(2, thread.posts.size)
        assertEquals(2, thread.posts.map { it.postId }.distinct().size)
        assertFalse(thread.posts.any { it.postId.isBlank() })
        assertFalse(thread.posts.any { it.postId.contains('/') })
    }

    @Test
    fun `thread continuation preserves path and advances exactly one page`() = runTest {
        server.enqueue(
            htmlResponse(
                """
                <html><body>
                  <div class="forum_op"><div class="content">Opening post</div></div>
                  <a class="pagebtn" rel="next"
                     href="/app/42/discussions/0/999/?ctp=2">Next</a>
                  <a class="pagebtn" href="/app/42/discussions/0/100/?ctp=1">Next</a>
                </body></html>
                """.trimIndent(),
            ),
        )

        val thread = provider().fetchThread(42, "/app/42/discussions/0/100/")

        assertNull(thread.nextRoute)
    }

    @Test
    fun `thread redirect cannot switch discussion identity`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "/app/42/discussions/0/999/"),
        )
        server.enqueue(
            htmlResponse(
                """
                <html><body>
                  <div class="forum_op"><div class="content">Wrong thread</div></div>
                </body></html>
                """.trimIndent(),
            ),
        )

        assertUnavailable {
            provider().fetchThread(42, "/app/42/discussions/0/100/")
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `thread preserves inert emoticon alt text`() = runTest {
        server.enqueue(
            htmlResponse(
                """
                <html><body>
                  <div class="commentthread_comment" id="comment_99">
                    <div class="commentthread_comment_text">
                      <img class="emoticon" alt=":1scoreSD:" src="https://example.invalid/emoticon.png">
                    </div>
                  </div>
                </body></html>
                """.trimIndent(),
            ),
        )

        val thread = provider().fetchThread(42, "/app/42/discussions/0/100/")

        assertEquals(
            listOf(SteamDiscussionPost(":1scoreSD:", postId = "comment_99")),
            thread.posts,
        )
    }

    @Test
    fun `blank posts are omitted when another mapped post has text`() = runTest {
        server.enqueue(
            htmlResponse(
                """
                <html><body>
                  <div class="commentthread_comment" id="comment_98">
                    <div class="commentthread_comment_text">A visible reply</div>
                  </div>
                  <div class="commentthread_comment" id="comment_99">
                    <div class="commentthread_comment_text"><br><br><br></div>
                  </div>
                </body></html>
                """.trimIndent(),
            ),
        )

        val thread = provider().fetchThread(42, "/app/42/discussions/0/100/")

        assertEquals(
            listOf(SteamDiscussionPost("A visible reply", postId = "comment_98")),
            thread.posts,
        )
    }

    @Test
    fun `thread with only blank post containers fails closed`() = runTest {
        server.enqueue(
            htmlResponse(
                """
                <html><body>
                  <div class="commentthread_comment" id="comment_99">
                    <div class="commentthread_comment_text"><br><br><br></div>
                  </div>
                </body></html>
                """.trimIndent(),
            ),
        )

        assertUnavailable {
            provider().fetchThread(42, "/app/42/discussions/0/100/")
        }
    }

    @Test
    fun `listing and thread pagination accept only their own query key`() = runTest {
        assertUnavailable {
            provider().fetchListing(42, "/app/42/discussions/?ctp=2")
        }
        assertUnavailable {
            provider().fetchThread(42, "/app/42/discussions/0/100/?fp=2")
        }

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `discussion pages record only bounded aggregate diagnostics`() = runTest {
        server.enqueue(
            htmlResponse(
                """
                <html><body>
                  <div class="commentthread_comment" id="comment_98">
                    <div class="commentthread_comment_text">Public post body</div>
                  </div>
                  <div class="commentthread_comment" id="comment_99">
                    <div class="commentthread_comment_text"><br><br></div>
                  </div>
                </body></html>
                """.trimIndent(),
            ),
        )
        val events = mutableListOf<SteamCommunityPageDiagnostic>()

        provider(SteamCommunityDiagnosticSink(events::add))
            .fetchThread(42, "/app/42/discussions/0/100/")

        val event = events.single()
        assertEquals(SteamCommunityPageOperation.DISCUSSION_THREAD, event.operation)
        assertEquals(DiagnosticOutcome.SUCCEEDED, event.outcome)
        assertEquals(200, event.httpStatus)
        assertEquals(1, event.attemptCount)
        assertEquals(1, event.itemCount)
        assertEquals(0, event.skippedItemCount)
        assertEquals(1, event.blankItemCount)
        assertEquals(0, event.duplicateItemCount)
        assertNull(event.failureReason)
    }

    @Test
    fun `client-rendered discussion shell records fixed failure reason`() = runTest {
        server.enqueue(
            htmlResponse(
                """
                <html><body>
                  <div id="application_root"></div>
                  <script src="/javascript/applications/community/main.js"></script>
                </body></html>
                """.trimIndent(),
            ),
        )
        val events = mutableListOf<SteamCommunityPageDiagnostic>()

        assertUnavailable {
            provider(SteamCommunityDiagnosticSink(events::add)).fetchListing(42, null)
        }

        val event = events.single()
        assertEquals(DiagnosticOutcome.FAILED, event.outcome)
        assertEquals(SteamCommunityFailureReason.CLIENT_RENDERED_SHELL, event.failureReason)
        assertEquals(200, event.httpStatus)
        assertEquals(1, event.attemptCount)
    }

    @Test
    fun `listing parser distinguishes explicit empty from unexpected representation`() = runTest {
        server.enqueue(htmlResponse("<html><body><div class=\"forum_no_topics\"></div></body></html>"))
        assertEquals(emptyList<SteamDiscussionSummary>(), provider().fetchListing(42, null).threads)

        server.enqueue(htmlResponse("<html><body><div>arbitrary shell</div></body></html>"))
        assertUnavailable { provider().fetchListing(42, null) }

        server.enqueue(
            htmlResponse(
                """
                <html><body>
                  <div id="application_root"></div>
                  <script src="/javascript/applications/community/main.js"></script>
                </body></html>
                """.trimIndent(),
            ),
        )
        assertUnavailable { provider().fetchListing(42, null) }
    }

    @Test
    fun `topic selector drift and unexpected thread representation fail closed`() = runTest {
        server.enqueue(
            htmlResponse(
                "<html><body><div class=\"forum_topic\"><div>missing required selectors</div></div></body></html>",
            ),
        )
        assertUnavailable { provider().fetchListing(42, null) }

        server.enqueue(htmlResponse("<html><body><div>missing posts</div></body></html>"))
        assertUnavailable { provider().fetchThread(42, "/app/42/discussions/0/100/") }

        server.enqueue(
            htmlResponse(
                "<html><body><div class=\"forum_thread_empty\"></div></body></html>",
            ),
        )
        assertEquals(
            emptyList<SteamDiscussionPost>(),
            provider().fetchThread(42, "/app/42/discussions/0/100/").posts,
        )
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

        server.enqueue(htmlResponse("x".repeat(1024 * 1024 + 1)))
        assertUnavailable { provider().fetchListing(42, null) }
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
        server.enqueue(htmlResponse("<html><body><div class=\"forum_no_topics\"></div></body></html>"))

        assertEquals(
            emptyList<SteamDiscussionSummary>(),
            provider().fetchListing(42, null).threads,
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

        assertUnavailable { provider().fetchListing(42, null) }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `successful response requires HTML content type`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("<html><body><div class=\"forum_no_topics\"></div></body></html>"),
        )

        assertUnavailable { provider().fetchListing(42, null) }
    }

    private fun htmlResponse(body: String) = MockResponse()
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody(body)

    private fun provider(
        diagnostics: SteamCommunityDiagnosticSink = NoOpSteamCommunityDiagnostics,
    ) = SteamDiscussionProvider(
        client = OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .build(),
        endpoint = server.url("/"),
        allowedHosts = setOf(server.hostName),
        requireHttps = false,
        allowedPorts = setOf(server.port),
        diagnostics = diagnostics,
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
