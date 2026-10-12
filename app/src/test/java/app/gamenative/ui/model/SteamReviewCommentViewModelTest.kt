package app.gamenative.ui.model

import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.library.community.DiscussionSectionState
import app.gamenative.library.community.ReviewSectionState
import app.gamenative.library.community.SteamDiscussionListing
import app.gamenative.library.community.SteamDiscussionSource
import app.gamenative.library.community.SteamDiscussionThread
import app.gamenative.library.community.SteamReviewCard
import app.gamenative.library.community.SteamReviewComment
import app.gamenative.library.community.SteamReviewCommentPage
import app.gamenative.library.community.SteamReviewCommentSource
import app.gamenative.library.community.SteamReviewCommentState
import app.gamenative.library.community.SteamReviewPage
import app.gamenative.library.community.SteamReviewPageSource
import app.gamenative.library.community.SteamReviewQuery
import app.gamenative.library.community.SteamReviewSort
import app.gamenative.library.discovery.SteamReviewSummary
import app.gamenative.library.discovery.SteamReviewSummarySource
import app.gamenative.library.metadata.GameDetailState
import app.gamenative.library.metadata.GameMetadataRepository
import app.gamenative.library.metadata.MetadataRefreshResult
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SteamReviewCommentViewModelTest {
    private lateinit var scheduler: TestCoroutineScheduler

    @Before
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun currentReviewSelectionBindsExactAppQueryAndOriginalPage() = runTest(scheduler) {
        val requests = mutableListOf<List<Any?>>()
        val model = model(SteamReviewCommentSource { app, query, cursor, id, start ->
            requests += listOf(app, query, cursor, id, start)
            SteamReviewCommentPage(listOf(SteamReviewComment("100", "Public comment")), null)
        })
        model.loadReviews(42, false); runCurrent()
        model.loadMoreReviews(); runCurrent()
        val before = model.reviewState.value
        model.openReviewComments("98", false); runCurrent()
        assertEquals(listOf(listOf(42, SteamReviewQuery(), "page-two", "98", 0)), requests)
        assertTrue(model.reviewCommentState.value is SteamReviewCommentState.Content)
        assertEquals(listOf("Public comment"), (model.reviewCommentState.value as SteamReviewCommentState.Content).comments.map { it.text })
        assertEquals(before, model.reviewState.value)
    }

    @Test
    fun nonexistentReviewSelectionCannotInvokeCommentSource() = runTest(scheduler) {
        var requests = 0
        val model = model(SteamReviewCommentSource { _, _, _, _, _ -> requests++; SteamReviewCommentPage(emptyList(), null) })
        model.loadReviews(42, false); runCurrent()
        model.openReviewComments("not-current", false); runCurrent()
        assertEquals(0, requests)
        assertEquals(SteamReviewCommentState.Idle, model.reviewCommentState.value)
    }

    @Test
    fun backClosesReaderWithoutLosingReviewsOrTheirPagination() = runTest(scheduler) {
        val model = model(SteamReviewCommentSource { _, _, _, _, _ -> SteamReviewCommentPage(emptyList(), null) })
        model.loadReviews(42, false); runCurrent()
        val before = model.reviewState.value
        model.openReviewComments("99", false); runCurrent()
        assertTrue(model.closeReviewComments())
        assertEquals(SteamReviewCommentState.Idle, model.reviewCommentState.value)
        assertEquals(before, model.reviewState.value)
        assertFalse(model.closeReviewComments())
        model.loadMoreReviews(); runCurrent()
        assertEquals(listOf("99", "98"), (model.reviewState.value as ReviewSectionState.Content).reviews.map { it.recommendationId })
    }

    @Test
    fun latePreviousSelectionCannotReplaceCurrentReader() = runTest(scheduler) {
        val old = CompletableDeferred<SteamReviewCommentPage>()
        val model = model(SteamReviewCommentSource { _, _, _, id, _ ->
            if (id == "99") withContext(NonCancellable) { old.await() }
            else SteamReviewCommentPage(listOf(SteamReviewComment("200", "Current comment")), null)
        })
        model.loadReviews(42, false); runCurrent()
        model.loadMoreReviews(); runCurrent()
        model.openReviewComments("99", false); runCurrent()
        model.openReviewComments("98", false); runCurrent()
        old.complete(SteamReviewCommentPage(listOf(SteamReviewComment("100", "Stale comment")), null)); runCurrent()
        assertTrue(model.reviewCommentState.value is SteamReviewCommentState.Content)
        val state = model.reviewCommentState.value as SteamReviewCommentState.Content
        assertEquals("98", state.recommendationId)
        assertEquals(listOf("Current comment"), state.comments.map { it.text })
    }

    @Test
    fun filterGameAndDetailChangesClearAndFenceTheReader() = runTest(scheduler) {
        val model = model(SteamReviewCommentSource { _, _, _, _, _ -> SteamReviewCommentPage(emptyList(), null) })
        model.loadReviews(42, false); runCurrent()
        model.openReviewComments("99", false); runCurrent()
        assertTrue(model.reviewCommentState.value is SteamReviewCommentState.Empty)
        model.updateReviewQuery(SteamReviewQuery(sort = SteamReviewSort.RECENT), false); runCurrent()
        assertEquals(SteamReviewCommentState.Idle, model.reviewCommentState.value)
        model.openReviewComments("99", false); runCurrent()
        model.loadReviews(43, false); runCurrent()
        assertEquals(SteamReviewCommentState.Idle, model.reviewCommentState.value)
        model.openReviewComments("99", false); runCurrent()
        model.clearDetail(); runCurrent()
        assertEquals(SteamReviewCommentState.Idle, model.reviewCommentState.value)
        assertEquals(ReviewSectionState.Idle, model.reviewState.value)
    }

    @Test
    fun commentFailureAndOfflineLeaveOtherCommunitySectionsIntact() = runTest(scheduler) {
        var requests = 0
        val model = model(SteamReviewCommentSource { _, _, _, _, _ -> requests++; throw IOException("Synthetic unavailable") })
        model.loadReviews(42, false); model.loadDiscussions(42, false); runCurrent()
        val reviews = model.reviewState.value
        val discussions = model.discussionState.value
        model.openReviewComments("99", true); runCurrent()
        assertTrue(model.reviewCommentState.value is SteamReviewCommentState.Offline)
        assertEquals(0, requests)
        model.openReviewComments("99", false); runCurrent()
        assertTrue(model.reviewCommentState.value is SteamReviewCommentState.Unavailable)
        assertEquals(reviews, model.reviewState.value)
        assertEquals(discussions, model.discussionState.value)
    }

    @Test
    fun paginationDeduplicatesAndStopsAtFivePagesEvenWithARepeatedCursor() = runTest(scheduler) {
        val starts = mutableListOf<Int>()
        val model = model(SteamReviewCommentSource { _, _, _, _, start ->
            starts += start
            SteamReviewCommentPage(listOf(SteamReviewComment("100", "Public comment")), if (start < 80) start + 20 else 80)
        })
        model.loadReviews(42, false); runCurrent()
        model.openReviewComments("99", false); runCurrent()
        repeat(7) { model.loadMoreReviewComments(); runCurrent() }
        assertEquals(listOf(0, 20, 40, 60, 80), starts)
        assertTrue(model.reviewCommentState.value is SteamReviewCommentState.Content)
        val state = model.reviewCommentState.value as SteamReviewCommentState.Content
        assertEquals(1, state.comments.size)
        assertFalse(state.canLoadMore)
    }

    private fun model(comments: SteamReviewCommentSource) = GameDetailViewModel(
        repository = object : GameMetadataRepository {
            override fun observe(canonicalId: CanonicalGameId) = flowOf(GameDetailState.Unavailable(null))
            override suspend fun refresh(canonicalId: CanonicalGameId) = MetadataRefreshResult.Refreshed
        },
        reviewSource = SteamReviewPageSource { _, _, cursor ->
            SteamReviewPage(listOf(review(if (cursor == null) "99" else "98")), if (cursor == null) "page-two" else null)
        },
        discussionSource = object : SteamDiscussionSource {
            override suspend fun fetchListing(steamAppId: Int, route: String?) = SteamDiscussionListing(emptyList(), null)
            override suspend fun fetchThread(steamAppId: Int, route: String) = SteamDiscussionThread("", emptyList(), route, null)
        },
        reviewSummarySource = SteamReviewSummarySource { SteamReviewSummary(0) },
        reviewCommentSource = comments,
    )

    private fun review(id: String) = SteamReviewCard(true, "Public review", null, 0, 0, 1, 0, 0, false, false, null, id)
}
