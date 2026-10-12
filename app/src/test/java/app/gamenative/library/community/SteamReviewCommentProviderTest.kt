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
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class SteamReviewCommentProviderTest {
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
    fun exactPublicRecommendationUsesAppIdThreadAndDiscardsRoutingIdentity() = runTest {
        server.enqueue(json(reviewPage()))
        server.enqueue(json(comments()))
        val page = available { provider().fetch(42, SteamReviewQuery(), "public-page", "99", 0) }
        assertEquals(listOf(SteamReviewComment("100", "Public comment")), page.comments)
        assertNull(page.nextStart)
        val reviewRequest = server.takeRequest()
        assertEquals("/appreviews/42", reviewRequest.requestUrl?.encodedPath)
        assertEquals("public-page", reviewRequest.requestUrl?.queryParameter("cursor"))
        val commentRequest = server.takeRequest()
        assertEquals("/comment/Recommendation/render/$SYNTHETIC_AUTHOR/42/", commentRequest.requestUrl?.encodedPath)
        assertEquals("0", commentRequest.requestUrl?.queryParameter("start"))
        assertEquals("20", commentRequest.requestUrl?.queryParameter("count"))
        assertNull(commentRequest.getHeader("Cookie"))
        assertNull(commentRequest.getHeader("Authorization"))
        assertFalse(page.toString().contains(SYNTHETIC_AUTHOR))
        assertFalse(page.toString().contains("Synthetic person"))
    }

    @Test
    fun exactReviewQueryIsReacquiredBeforeEachCommentPage() = runTest {
        repeat(2) { server.enqueue(json(reviewPage())); server.enqueue(json(comments(total = 41))) }
        val query = SteamReviewQuery(SteamReviewSort.RECENT, SteamReviewPolarity.NEGATIVE, SteamReviewLanguage.ALL, SteamReviewPurchaseType.STEAM)
        val source = provider()
        assertEquals(20, available { source.fetch(42, query, "public-page", "99", 0) }.nextStart)
        assertEquals(40, available { source.fetch(42, query, "public-page", "99", 20) }.nextStart)
        repeat(2) { page ->
            val request = server.takeRequest()
            assertEquals("recent", request.requestUrl?.queryParameter("filter"))
            assertEquals("negative", request.requestUrl?.queryParameter("review_type"))
            assertEquals("all", request.requestUrl?.queryParameter("language"))
            assertEquals("steam", request.requestUrl?.queryParameter("purchase_type"))
            assertEquals("public-page", request.requestUrl?.queryParameter("cursor"))
            assertEquals((page * 20).toString(), server.takeRequest().requestUrl?.queryParameter("start"))
        }
    }

    @Test
    fun removedRecommendationCannotFetchAnotherAuthorsThread() = runTest {
        server.enqueue(json(reviewPage(id = "98")))
        assertUnavailable { provider().fetch(42, SteamReviewQuery(), null, "99", 0) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun ambiguousRecommendationCannotEstablishRoutingAuthority() = runTest {
        server.enqueue(json("""{"success":1,"reviews":[{"recommendationid":"99","author":{"steamid":"$SYNTHETIC_AUTHOR"}},{"recommendationid":"99","author":{"steamid":"76561197960265729"}}]}"""))
        assertUnavailable { provider().fetch(42, SteamReviewQuery(), null, "99", 0) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun missingOrMalformedRoutingIdentityFailsWithoutCommentRequest() = runTest {
        for (author in listOf("", "not-a-steamid", "0", "76561197960265728/other")) {
            server.enqueue(json(reviewPage(author = author)))
            assertUnavailable { provider().fetch(42, SteamReviewQuery(), null, "99", 0) }
        }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun invalidSelectionOrPageIsRejectedBeforeNetwork() = runTest {
        for ((app, id, start) in listOf(Triple(0, "99", 0), Triple(42, "../99", 0), Triple(42, "page:fallback", 0), Triple(42, "99", -1), Triple(42, "99", 1), Triple(42, "99", 100))) {
            assertUnavailable { provider().fetch(app, SteamReviewQuery(), null, id, start) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun explicitEmptyIsDifferentFromMalformedOrRejectedRepresentation() = runTest {
        server.enqueue(json(reviewPage()))
        server.enqueue(json("""{"success":true,"total_count":0,"comments_html":""}"""))
        assertEquals(SteamReviewCommentPage(emptyList(), null), available { provider().fetch(42, SteamReviewQuery(), null, "99", 0) })
        for (payload in listOf("{}", """{"success":false,"total_count":0,"comments_html":""}""", """{"success":true,"comments_html":""}""", """{"success":true,"total_count":1,"comments_html":""}""", """{"success":"true","total_count":0,"comments_html":""}""")) {
            server.enqueue(json(reviewPage())); server.enqueue(json(payload))
            assertUnavailable { provider().fetch(42, SteamReviewQuery(), null, "99", 0) }
        }
    }

    @Test
    fun unsafeMarkupAndAuthorPresentationDoNotEnterNativeComments() = runTest {
        server.enqueue(json(reviewPage()))
        server.enqueue(json("""{"success":true,"total_count":1,"comments_html":"<div class='commentthread_comment' id='comment_100'><a class='commentthread_author_link'>Synthetic person</a><div class='commentthread_comment_text'>Public <b>comment</b><script>private script</script><iframe>private frame</iframe><span data-miniprofile='1'>Synthetic person</span></div></div>"}"""))
        assertEquals(listOf(SteamReviewComment("100", "Public comment")), available { provider().fetch(42, SteamReviewQuery(), null, "99", 0) }.comments)
    }

    @Test
    fun missingStableCommentIdentityCannotMasqueradeAsAValidPage() = runTest {
        server.enqueue(json(reviewPage()))
        server.enqueue(json("""{"success":true,"total_count":1,"comments_html":"<div class='commentthread_comment'><div class='commentthread_comment_text'>Public comment</div></div>"}"""))
        assertUnavailable { provider().fetch(42, SteamReviewQuery(), null, "99", 0) }
    }

    @Test
    fun redirectedCommentOrCrossAppReviewResponseFailsClosed() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/appreviews/43")))
        assertUnavailable { provider().fetch(42, SteamReviewQuery(), null, "99", 0) }
        assertEquals(1, server.requestCount)
        server.enqueue(json(reviewPage()))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/comment/Recommendation/render/$SYNTHETIC_AUTHOR/43/")))
        assertUnavailable { provider().fetch(42, SteamReviewQuery(), null, "99", 0) }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun oversizedOrWrongTypeResponsesAreNotEmptySuccess() = runTest {
        server.enqueue(json("x".repeat(1024 * 1024 + 1)))
        assertUnavailable { provider().fetch(42, SteamReviewQuery(), null, "99", 0) }
        server.enqueue(json(reviewPage()))
        server.enqueue(json("x".repeat(1024 * 1024 + 1)))
        assertUnavailable { provider().fetch(42, SteamReviewQuery(), null, "99", 0) }
        server.enqueue(json(reviewPage()))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>not JSON</html>"))
        assertUnavailable { provider().fetch(42, SteamReviewQuery(), null, "99", 0) }
    }

    private fun provider() = SteamReviewCommentProvider(
        client = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false).build(),
        reviewEndpoint = server.url("/appreviews"),
        commentEndpoint = server.url("/"),
        allowedReviewHosts = setOf(server.hostName),
        allowedCommentHosts = setOf(server.hostName),
        requireHttps = false,
        allowedPorts = setOf(server.port),
    )

    private suspend fun available(fetch: suspend () -> SteamReviewCommentPage): SteamReviewCommentPage {
        val page = try { fetch() } catch (_: IOException) { null }
        assertNotNull("Verified public comments must be available through the native source", page)
        return page!!
    }

    private suspend fun assertUnavailable(fetch: suspend () -> Unit) {
        try {
            fetch()
            throw AssertionError("Expected unavailable comments, not fabricated empty/content")
        } catch (error: IOException) {
            assertEquals("Steam review comments unavailable", error.message)
            assertNull(error.cause)
            assertFalse(error.toString().contains(SYNTHETIC_AUTHOR))
        }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun reviewPage(id: String = "99", author: String = SYNTHETIC_AUTHOR) = """{"success":1,"reviews":[{"recommendationid":"$id","author":{"steamid":"$author"},"review":"Public review"}]}"""
    private fun comments(total: Int = 1) = """{"success":true,"total_count":$total,"comments_html":"<div class='commentthread_comment' id='comment_100'><a class='commentthread_author_link'>Synthetic person</a><div class='commentthread_comment_text'>Public comment</div></div>"}"""

    private companion object {
        const val SYNTHETIC_AUTHOR = "76561197960265728"
    }
}
