package app.gamenative.ui.model

import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.library.community.DiscussionSectionState
import app.gamenative.library.community.ReviewSectionState
import app.gamenative.library.community.SteamDiscussionListing
import app.gamenative.library.community.SteamDiscussionPost
import app.gamenative.library.community.SteamDiscussionSource
import app.gamenative.library.community.SteamDiscussionSummary
import app.gamenative.library.community.SteamDiscussionThread
import app.gamenative.library.community.SteamReviewCard
import app.gamenative.library.community.SteamReviewPage
import app.gamenative.library.community.SteamReviewPageSource
import app.gamenative.library.community.SteamReviewQuery
import app.gamenative.library.metadata.CanonicalGameMetadata
import app.gamenative.library.metadata.GameDetailState
import app.gamenative.library.metadata.GameMetadataRepository
import app.gamenative.library.metadata.MetadataRefreshResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameDetailViewModelTest {
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var dispatcher: TestDispatcher

    @Before
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        dispatcher = StandardTestDispatcher(scheduler)
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadObservesCanonicalMetadataWithoutSavedStateIdentifier() = runTest(scheduler) {
        val id = canonicalId("11111111-1111-1111-1111-111111111111")
        val repository = FakeRepository(
            mutableMapOf(
                id to MutableStateFlow(
                    GameDetailState.Content(metadata("Visible details"), stale = false),
                ),
            ),
        )
        val viewModel = GameDetailViewModel(repository, emptyReviews(), emptyDiscussions())

        viewModel.load(id)
        runCurrent()

        val state = viewModel.state.value as GameDetailState.Content
        assertEquals("Visible details", state.metadata.title)
        assertEquals(listOf(id), repository.observeCalls)
    }

    @Test
    fun selectingAnotherCanonicalCancelsOldObservationAndShowsNewContent() = runTest(scheduler) {
        val first = canonicalId("11111111-1111-1111-1111-111111111111")
        val second = canonicalId("22222222-2222-2222-2222-222222222222")
        val repository = FakeRepository(
            mutableMapOf(
                first to MutableStateFlow(
                    GameDetailState.Content(metadata("First"), stale = false),
                ),
                second to MutableStateFlow(
                    GameDetailState.Content(metadata("Second"), stale = true),
                ),
            ),
        )
        val viewModel = GameDetailViewModel(repository, emptyReviews(), emptyDiscussions())

        viewModel.load(first)
        runCurrent()
        viewModel.load(second)
        runCurrent()

        val state = viewModel.state.value as GameDetailState.Content
        assertEquals("Second", state.metadata.title)
        assertTrue(state.stale)
        assertEquals(listOf(first, second), repository.observeCalls)
    }

    @Test
    fun retryRestartsTheCurrentRepositoryObservation() = runTest(scheduler) {
        val id = canonicalId("33333333-3333-3333-3333-333333333333")
        val repository = FakeRepository(
            mutableMapOf(id to MutableStateFlow(GameDetailState.Unavailable(null))),
        )
        val viewModel = GameDetailViewModel(repository, emptyReviews(), emptyDiscussions())
        viewModel.load(id)
        runCurrent()

        viewModel.retry()
        runCurrent()

        assertEquals(listOf(id, id), repository.observeCalls)
    }

    @Test
    fun reviewsLoadAppendAndClearOnlyInMemoryContent() = runTest(scheduler) {
        val cursors = mutableListOf<String?>()
        val reviewSource = SteamReviewPageSource { _, _, cursor ->
            cursors += cursor
            if (cursor == null) {
                SteamReviewPage(listOf(review("First")), nextCursor = "next")
            } else {
                SteamReviewPage(listOf(review("Second")), nextCursor = null)
            }
        }
        val repository = FakeRepository(mutableMapOf())
        val viewModel = GameDetailViewModel(repository, reviewSource, emptyDiscussions())

        viewModel.loadReviews(steamAppId = 42, isOffline = false)
        runCurrent()
        var state = viewModel.reviewState.value as ReviewSectionState.Content
        assertEquals(listOf("First"), state.reviews.map(SteamReviewCard::text))
        assertTrue(state.canLoadMore)

        viewModel.loadMoreReviews()
        runCurrent()
        state = viewModel.reviewState.value as ReviewSectionState.Content
        assertEquals(listOf("First", "Second"), state.reviews.map(SteamReviewCard::text))
        assertEquals(listOf(null, "next"), cursors)

        viewModel.clearDetail()
        assertEquals(ReviewSectionState.Idle, viewModel.reviewState.value)
    }

    @Test
    fun discussionsLazyLoadListingAndThreadAndBackReturnsToListing() = runTest(scheduler) {
        val listingRoutes = mutableListOf<String?>()
        val threadRoutes = mutableListOf<String>()
        val source = object : SteamDiscussionSource {
            override suspend fun fetchListing(
                steamAppId: Int,
                route: String?,
            ): SteamDiscussionListing {
                listingRoutes += route
                return if (route == null) {
                    SteamDiscussionListing(
                        threads = listOf(discussion("First", "/app/42/discussions/0/1/")),
                        nextRoute = "/app/42/discussions/?ctp=2",
                    )
                } else {
                    SteamDiscussionListing(
                        threads = listOf(discussion("Second", "/app/42/discussions/0/2/")),
                        nextRoute = null,
                    )
                }
            }

            override suspend fun fetchThread(
                steamAppId: Int,
                route: String,
            ): SteamDiscussionThread {
                threadRoutes += route
                return if (route.endsWith("ctp=2")) {
                    SteamDiscussionThread(
                        title = "First",
                        posts = listOf(SteamDiscussionPost("Reply two")),
                        route = "/app/42/discussions/0/1/",
                        nextRoute = null,
                    )
                } else {
                    SteamDiscussionThread(
                        title = "First",
                        posts = listOf(SteamDiscussionPost("Reply one")),
                        route = route,
                        nextRoute = "$route?ctp=2",
                    )
                }
            }
        }
        val viewModel = GameDetailViewModel(FakeRepository(mutableMapOf()), emptyReviews(), source)

        viewModel.loadDiscussions(steamAppId = 42, isOffline = false)
        runCurrent()
        viewModel.loadMoreDiscussions()
        runCurrent()
        var state = viewModel.discussionState.value as DiscussionSectionState.Listing
        assertEquals(listOf("First", "Second"), state.threads.map(SteamDiscussionSummary::title))

        viewModel.openDiscussion("/app/42/discussions/0/1/", isOffline = false)
        runCurrent()
        viewModel.loadMoreDiscussions()
        runCurrent()
        val thread = viewModel.discussionState.value as DiscussionSectionState.Thread
        assertEquals(listOf("Reply one", "Reply two"), thread.posts.map(SteamDiscussionPost::text))
        assertTrue(viewModel.closeDiscussionThread())

        state = viewModel.discussionState.value as DiscussionSectionState.Listing
        assertEquals(listOf("First", "Second"), state.threads.map(SteamDiscussionSummary::title))
        assertEquals(listOf(null, "/app/42/discussions/?ctp=2"), listingRoutes)
        assertEquals(
            listOf("/app/42/discussions/0/1/", "/app/42/discussions/0/1/?ctp=2"),
            threadRoutes,
        )
    }

    private fun emptyReviews() = SteamReviewPageSource { _, _, _ ->
        SteamReviewPage(emptyList(), null)
    }

    private fun emptyDiscussions() = object : SteamDiscussionSource {
        override suspend fun fetchListing(steamAppId: Int, route: String?) =
            SteamDiscussionListing(emptyList(), null)

        override suspend fun fetchThread(steamAppId: Int, route: String) =
            SteamDiscussionThread("", emptyList(), route, null)
    }

    private fun discussion(title: String, route: String) = SteamDiscussionSummary(
        title = title,
        replyCount = 0,
        activityLabel = null,
        route = route,
    )

    private fun review(text: String) = SteamReviewCard(
        recommended = true,
        text = text,
        playtimeMinutes = 60,
        helpfulVotes = 1,
        funnyVotes = 0,
        commentCount = 0,
        postedAtEpochSeconds = 1,
        updatedAtEpochSeconds = 1,
        receivedForFree = false,
        earlyAccess = false,
        developerResponse = null,
    )

    private class FakeRepository(
        private val states: MutableMap<CanonicalGameId, MutableStateFlow<GameDetailState>>,
    ) : GameMetadataRepository {
        val observeCalls = mutableListOf<CanonicalGameId>()

        override fun observe(canonicalId: CanonicalGameId): Flow<GameDetailState> {
            observeCalls += canonicalId
            return requireNotNull(states[canonicalId])
        }

        override suspend fun refresh(canonicalId: CanonicalGameId): MetadataRefreshResult =
            MetadataRefreshResult.Refreshed
    }

    private fun metadata(title: String): CanonicalGameMetadata = CanonicalGameMetadata(
        title = title,
        shortDescription = null,
        about = null,
        headerImageUrl = null,
        screenshots = emptyList(),
        movies = emptyList(),
        developers = emptyList(),
        publishers = emptyList(),
        releaseDate = null,
        platforms = emptySet(),
        languages = emptyList(),
        requirements = null,
        features = emptyList(),
        achievementCount = null,
        dlcCount = null,
        fetchedAtEpochMs = 1L,
    )

    private fun canonicalId(value: String) = CanonicalGameId.parse(value)
}
