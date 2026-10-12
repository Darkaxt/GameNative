package app.gamenative.ui.model

import androidx.lifecycle.ViewModelStore
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.library.community.ReviewSectionState
import app.gamenative.library.community.SteamDiscussionListing
import app.gamenative.library.community.SteamDiscussionSource
import app.gamenative.library.community.SteamDiscussionThread
import app.gamenative.library.community.SteamReviewCommentProvider
import app.gamenative.library.community.SteamReviewCommentState
import app.gamenative.library.community.SteamReviewPageProvider
import app.gamenative.library.discovery.SteamReviewSummary
import app.gamenative.library.discovery.SteamReviewSummarySource
import app.gamenative.library.metadata.GameDetailState
import app.gamenative.library.metadata.GameMetadataRepository
import app.gamenative.library.metadata.MetadataRefreshResult
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SteamReviewCommentMainCallerTest {
    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var mainThread: Thread

    @Before
    fun setUp() = runBlocking {
        main = Executors.newSingleThreadExecutor { task -> Thread(task, "comment-test-main").apply { isDaemon = true } }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        mainThread = withContext(main) { Thread.currentThread() }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        main.close()
    }

    @Test
    fun actualMainCallerReadsUnbufferedNativeCommentsOffMain() = runBlocking {
        val body = UnbufferedCommentBody(mainThread)
        val call = AtomicReference<Call>()
        val model = model(body, call)
        val store = ViewModelStore().also { it.put("comments", model) }
        try {
            withContext(main) { model.loadReviews(42, false) }
            assertTrue(withTimeout(10_000L) { model.reviewState.first { it != ReviewSectionState.Idle && it != ReviewSectionState.Loading } } is ReviewSectionState.Content)
            withContext(main) { model.openReviewComments("99", false) }
            val state = withTimeout(10_000L) { model.reviewCommentState.first { it != SteamReviewCommentState.Idle && it !is SteamReviewCommentState.Loading } }
            assertTrue("Actual Main comments caller must reach native content", state is SteamReviewCommentState.Content)
            assertEquals(listOf("Public comment"), (state as SteamReviewCommentState.Content).comments.map { it.text })
            assertEquals(0, body.mainReads.get())
            assertTrue(body.workerReads.get() > 0)
        } finally {
            withContext(main) { model.clearDetail(); store.clear() }
        }
    }

    @Test
    fun detailClosureCancelsNativeCommentBodyAfterHeaders() = runBlocking {
        val gate = CountDownLatch(1)
        val body = UnbufferedCommentBody(mainThread, gate)
        val call = AtomicReference<Call>()
        val model = model(body, call)
        val store = ViewModelStore().also { it.put("comments", model) }
        try {
            withContext(main) { model.loadReviews(42, false) }
            assertTrue(withTimeout(10_000L) { model.reviewState.first { it != ReviewSectionState.Idle && it != ReviewSectionState.Loading } } is ReviewSectionState.Content)
            withContext(main) { model.openReviewComments("99", false) }
            assertTrue("Native comment body phase did not start", body.started.await(5, TimeUnit.SECONDS))
            withContext(main) { model.clearDetail() }
            assertTrue("Detail closure must cancel the native body call after headers", call.get().isCanceled())
            assertEquals(SteamReviewCommentState.Idle, model.reviewCommentState.value)
        } finally {
            gate.countDown()
            withContext(main) { model.clearDetail(); store.clear() }
        }
    }

    private fun model(body: ResponseBody, call: AtomicReference<Call>): GameDetailViewModel {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val comments = chain.request().url.encodedPath.startsWith("/comment/")
            if (comments) call.set(chain.call())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(if (comments) body else REVIEWS.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return GameDetailViewModel(
            repository = object : GameMetadataRepository {
                override fun observe(canonicalId: CanonicalGameId) = flowOf(GameDetailState.Unavailable(null))
                override suspend fun refresh(canonicalId: CanonicalGameId) = MetadataRefreshResult.Refreshed
            },
            reviewSource = SteamReviewPageProvider(client, "https://store.steampowered.com/appreviews".toHttpUrl(), setOf("store.steampowered.com"), true, setOf(443)),
            discussionSource = object : SteamDiscussionSource {
                override suspend fun fetchListing(steamAppId: Int, route: String?) = SteamDiscussionListing(emptyList(), null)
                override suspend fun fetchThread(steamAppId: Int, route: String) = SteamDiscussionThread("", emptyList(), route, null)
            },
            reviewSummarySource = SteamReviewSummarySource { SteamReviewSummary(0) },
            reviewCommentSource = SteamReviewCommentProvider(client, "https://store.steampowered.com/appreviews".toHttpUrl(), "https://steamcommunity.com/".toHttpUrl(), setOf("store.steampowered.com"), setOf("steamcommunity.com"), true, setOf(443)),
        )
    }

    private class UnbufferedCommentBody(private val forbidden: Thread, private val gate: CountDownLatch? = null) : ResponseBody() {
        val mainReads = AtomicInteger()
        val workerReads = AtomicInteger()
        val started = CountDownLatch(1)
        private val bytes = Buffer().writeUtf8(COMMENTS)
        private val input = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (Thread.currentThread() === forbidden) { mainReads.incrementAndGet(); throw IOException("Main body read") }
                workerReads.incrementAndGet()
                started.countDown()
                if (gate != null && !gate.await(5, TimeUnit.SECONDS)) throw IOException("Body fixture gate timed out")
                return bytes.read(sink, byteCount)
            }
            override fun close() { bytes.clear() }
            override fun timeout() = Timeout.NONE
        }.buffer()
        override fun contentType() = "application/json".toMediaType()
        override fun contentLength() = -1L
        override fun source() = input
    }

    private companion object {
        const val REVIEWS = """{"success":1,"reviews":[{"recommendationid":"99","review":"Public review","comment_count":1,"author":{"steamid":"76561197960265735"}}]}"""
        const val COMMENTS = """{"success":true,"total_count":1,"comments_html":"<div class='commentthread_comment' id='comment_100'><div class='commentthread_comment_text'>Public comment</div></div>"}"""
    }
}
