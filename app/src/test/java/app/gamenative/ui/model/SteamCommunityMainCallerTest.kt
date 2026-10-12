package app.gamenative.ui.model

import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.library.community.DiscussionSectionState
import app.gamenative.library.community.ReviewSectionState
import app.gamenative.library.community.SteamDiscussionListing
import app.gamenative.library.community.SteamDiscussionProvider
import app.gamenative.library.community.SteamDiscussionSource
import app.gamenative.library.community.SteamDiscussionThread
import app.gamenative.library.community.SteamReviewPage
import app.gamenative.library.community.SteamReviewPageProvider
import app.gamenative.library.community.SteamReviewPageSource
import app.gamenative.library.community.SteamReviewQuery
import app.gamenative.library.discovery.SteamReviewSummary
import app.gamenative.library.discovery.SteamReviewSummarySource
import app.gamenative.library.metadata.GameDetailState
import app.gamenative.library.metadata.GameMetadataRepository
import app.gamenative.library.metadata.MetadataRefreshResult
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SteamCommunityMainCallerTest {
    private lateinit var scheduler: TestCoroutineScheduler

    @Before
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun actualReviewViewModelConsumesUnbufferedBodyOffMain() = runTest(scheduler) {
        val body = ObservedBody(REVIEW_JSON, "application/json", forbiddenThread = Thread.currentThread())
        val model = model(reviews = reviewProvider(client { body }))
        try {
            model.loadReviews(42, isOffline = false)
            val state = model.reviewState.first { it != ReviewSectionState.Loading && it != ReviewSectionState.Idle }
            assertTrue("An unbuffered review response must reach content through the actual Main caller", state is ReviewSectionState.Content)
            assertEquals(listOf("Public review"), (state as ReviewSectionState.Content).reviews.map { it.text })
            assertEquals(0, body.forbiddenReads.get())
            assertTrue(body.workerReads.get() > 0)
        } finally {
            model.clearDetail()
        }
    }

    @Test
    fun actualDiscussionViewModelConsumesHybridListingBodyOffMain() = runTest(scheduler) {
        val body = ObservedBody(LISTING_HTML, "text/html", forbiddenThread = Thread.currentThread())
        val model = model(discussions = discussionProvider(client { body }))
        try {
            model.loadDiscussions(42, isOffline = false)
            val state = model.discussionState.first { it != DiscussionSectionState.Loading && it != DiscussionSectionState.Idle }
            assertTrue("Supported server topics must reach the actual Main caller even in a hybrid shell", state is DiscussionSectionState.Listing)
            assertEquals(listOf("Public topic"), (state as DiscussionSectionState.Listing).threads.map { it.title })
            assertEquals(0, body.forbiddenReads.get())
            assertTrue(body.workerReads.get() > 0)
        } finally {
            model.clearDetail()
        }
    }

    @Test
    fun actualDiscussionViewModelConsumesThreadBodyOffMain() = runTest(scheduler) {
        val body = ObservedBody(THREAD_HTML, "text/html", forbiddenThread = Thread.currentThread())
        val model = model(discussions = discussionProvider(client { path ->
            if (path.endsWith("/0/99/")) body else LISTING_HTML.toResponseBody("text/html".toMediaType())
        }))
        try {
            model.loadDiscussions(42, isOffline = false)
            assertTrue(model.discussionState.first { it != DiscussionSectionState.Loading && it != DiscussionSectionState.Idle } is DiscussionSectionState.Listing)
            model.openDiscussion("/app/42/discussions/0/99/", isOffline = false)
            val state = model.discussionState.first { it != DiscussionSectionState.Loading }
            assertTrue("Unbuffered thread posts must reach the actual Main caller", state is DiscussionSectionState.Thread)
            assertEquals(listOf("Opening post", "Public reply"), (state as DiscussionSectionState.Thread).posts.map { it.text })
            assertEquals(0, body.forbiddenReads.get())
            assertTrue(body.workerReads.get() > 0)
        } finally {
            model.clearDetail()
        }
    }

    @Test
    fun reviewCancellationAfterHeadersStillCancelsTheBodyCall() = runTest(scheduler) {
        val body = ObservedBody(REVIEW_JSON, "application/json", gate = CountDownLatch(1))
        val call = AtomicReference<Call>()
        val provider = reviewProvider(client(call) { body })
        val job = launch(Dispatchers.Default) { provider.fetch(42, SteamReviewQuery(), null) }
        try {
            assertTrue("Body phase did not start", body.readStarted.await(5, TimeUnit.SECONDS))
            job.cancel()
            assertTrue("Cancellation after headers must cancel the underlying call", call.get().isCanceled())
        } finally {
            body.release()
            withContext(NonCancellable) { job.cancelAndJoin() }
        }
    }

    @Test
    fun discussionCancellationAfterHeadersStillCancelsTheBodyCall() = runTest(scheduler) {
        val body = ObservedBody(LISTING_HTML, "text/html", gate = CountDownLatch(1))
        val call = AtomicReference<Call>()
        val provider = discussionProvider(client(call) { body })
        val job = launch(Dispatchers.Default) { provider.fetchListing(42, null) }
        try {
            assertTrue("Body phase did not start", body.readStarted.await(5, TimeUnit.SECONDS))
            job.cancel()
            assertTrue("Cancellation after headers must cancel the underlying call", call.get().isCanceled())
        } finally {
            body.release()
            withContext(NonCancellable) { job.cancelAndJoin() }
        }
    }

    private fun client(call: AtomicReference<Call> = AtomicReference(), body: (String) -> ResponseBody) =
        OkHttpClient.Builder().addInterceptor { chain ->
            call.set(chain.call())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body(chain.request().url.encodedPath)).build()
        }.build()

    private fun reviewProvider(client: OkHttpClient) = SteamReviewPageProvider(
        client, "https://store.steampowered.com/appreviews".toHttpUrl(), setOf("store.steampowered.com"), true, setOf(443),
    )

    private fun discussionProvider(client: OkHttpClient) = SteamDiscussionProvider(
        client, "https://steamcommunity.com/".toHttpUrl(), setOf("steamcommunity.com"), true, setOf(443),
    )

    private fun model(
        reviews: SteamReviewPageSource = SteamReviewPageSource { _, _, _ -> SteamReviewPage(emptyList(), null) },
        discussions: SteamDiscussionSource = object : SteamDiscussionSource {
            override suspend fun fetchListing(steamAppId: Int, route: String?) = SteamDiscussionListing(emptyList(), null)
            override suspend fun fetchThread(steamAppId: Int, route: String) = SteamDiscussionThread("", emptyList(), route, null)
        },
    ) = GameDetailViewModel(
        object : GameMetadataRepository {
            override fun observe(canonicalId: CanonicalGameId) = flowOf(GameDetailState.Unavailable(null))
            override suspend fun refresh(canonicalId: CanonicalGameId) = MetadataRefreshResult.Refreshed
        }, reviews, discussions, SteamReviewSummarySource { SteamReviewSummary(0) },
    )

    private class ObservedBody(
        text: String,
        mime: String,
        private val forbiddenThread: Thread? = null,
        private val gate: CountDownLatch? = null,
    ) : ResponseBody() {
        val forbiddenReads = AtomicInteger()
        val workerReads = AtomicInteger()
        val readStarted = CountDownLatch(1)
        private val payload = Buffer().writeUtf8(text)
        private val type = mime.toMediaType()
        private val input = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (Thread.currentThread() === forbiddenThread) {
                    forbiddenReads.incrementAndGet()
                    throw IOException("Blocking body read on the Main caller")
                }
                workerReads.incrementAndGet()
                readStarted.countDown()
                if (gate != null && !gate.await(5, TimeUnit.SECONDS)) throw IOException("Synthetic body gate timed out")
                return payload.read(sink, byteCount)
            }
            override fun timeout() = Timeout.NONE
            override fun close() { payload.clear() }
        }.buffer()
        override fun contentType() = type
        override fun contentLength() = -1L
        override fun source(): BufferedSource = input
        fun release() { gate?.countDown() }
    }

    private companion object {
        const val REVIEW_JSON = """{"success":1,"reviews":[{"recommendationid":"99","review":"Public review","voted_up":true}],"cursor":""}"""
        const val LISTING_HTML = """<div id="application_root"></div><script src="/public/javascript/applications/community/main.js"></script><div class="forum_topic"><div class="forum_topic_name">Public topic</div><a class="forum_topic_overlay" href="/app/42/discussions/0/99/"></a><div class="forum_topic_reply_count">1</div></div>"""
        const val THREAD_HTML = """<h1>Public topic</h1><div class="forum_op" id="forum_op_99"><div class="content">Opening post</div></div><div class="commentthread_comment" data-commentid="100"><div class="commentthread_comment_text">Public reply</div></div>"""
    }
}
