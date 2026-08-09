package app.gamenative.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.library.community.DiscussionSectionState
import app.gamenative.library.community.ReviewSectionState
import app.gamenative.library.community.SteamDiscussionSource
import app.gamenative.library.community.SteamDiscussionSummary
import app.gamenative.library.community.SteamReviewPageSource
import app.gamenative.library.community.SteamReviewQuery
import app.gamenative.library.metadata.GameDetailState
import app.gamenative.library.metadata.GameMetadataRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GameDetailViewModel @Inject constructor(
    private val repository: GameMetadataRepository,
    private val reviewSource: SteamReviewPageSource,
    private val discussionSource: SteamDiscussionSource,
) : ViewModel() {
    private val selectedCanonicalId = MutableStateFlow<CanonicalGameId?>(null)
    private val reloadRevision = MutableStateFlow(0L)
    private val mutableReviewState = MutableStateFlow<ReviewSectionState>(ReviewSectionState.Idle)
    private val mutableReviewQuery = MutableStateFlow(SteamReviewQuery())
    private var reviewJob: Job? = null
    private var activeReviewAppId: Int? = null
    private var nextReviewCursor: String? = null
    private var reviewPageCount = 0
    private val consumedReviewCursors = mutableSetOf<String>()
    private var reviewRevision = 0L
    private val mutableDiscussionState =
        MutableStateFlow<DiscussionSectionState>(DiscussionSectionState.Idle)
    private var discussionJob: Job? = null
    private var activeDiscussionAppId: Int? = null
    private var nextDiscussionRoute: String? = null
    private var discussionPageCount = 0
    private val visitedDiscussionRoutes = mutableSetOf<String>()
    private var discussionRevision = 0L
    private var retainedDiscussionListing: DiscussionSectionState.Listing? = null

    val state = combine(selectedCanonicalId, reloadRevision) { canonicalId, _ -> canonicalId }
        .flatMapLatest { canonicalId ->
            canonicalId?.let(repository::observe) ?: flowOf(GameDetailState.Loading)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = GameDetailState.Loading,
        )

    val reviewState: StateFlow<ReviewSectionState> = mutableReviewState.asStateFlow()
    val reviewQuery: StateFlow<SteamReviewQuery> = mutableReviewQuery.asStateFlow()
    val discussionState: StateFlow<DiscussionSectionState> = mutableDiscussionState.asStateFlow()

    fun load(canonicalId: CanonicalGameId) {
        if (selectedCanonicalId.value != canonicalId) clearCommunity()
        selectedCanonicalId.value = canonicalId
    }

    fun retry() {
        if (selectedCanonicalId.value != null) {
            reloadRevision.value += 1L
        }
    }

    fun loadReviews(
        steamAppId: Int,
        isOffline: Boolean,
        force: Boolean = false,
    ) {
        if (steamAppId <= 0) {
            clearReviews()
            mutableReviewState.value = ReviewSectionState.Unavailable
            return
        }
        if (isOffline) {
            reviewJob?.cancel()
            activeReviewAppId = steamAppId
            mutableReviewState.value = ReviewSectionState.Offline
            return
        }
        val sameRequest = activeReviewAppId == steamAppId
        if (!force && sameRequest && mutableReviewState.value !is ReviewSectionState.Offline) return

        val previousContent = mutableReviewState.value as? ReviewSectionState.Content
        reviewJob?.cancel()
        activeReviewAppId = steamAppId
        nextReviewCursor = null
        reviewPageCount = 0
        consumedReviewCursors.clear()
        val revision = ++reviewRevision
        if (previousContent == null || !sameRequest) {
            mutableReviewState.value = ReviewSectionState.Loading
        }
        reviewJob = viewModelScope.launch {
            try {
                val page = reviewSource.fetch(steamAppId, mutableReviewQuery.value, null)
                if (revision != reviewRevision) return@launch
                reviewPageCount = 1
                nextReviewCursor = page.nextCursor
                mutableReviewState.value = if (page.reviews.isEmpty()) {
                    ReviewSectionState.Empty
                } else {
                    ReviewSectionState.Content(
                        reviews = page.reviews.distinct().take(MAX_REVIEW_CARDS),
                        canLoadMore = canLoadMore(),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (revision != reviewRevision) return@launch
                mutableReviewState.value = previousContent
                    ?.copy(loadingMore = false, refreshFailed = true)
                    ?: ReviewSectionState.Unavailable
            }
        }
    }

    fun updateReviewQuery(query: SteamReviewQuery, isOffline: Boolean) {
        if (mutableReviewQuery.value == query) return
        mutableReviewQuery.value = query
        val appId = activeReviewAppId ?: return
        mutableReviewState.value = ReviewSectionState.Idle
        activeReviewAppId = null
        loadReviews(appId, isOffline, force = true)
    }

    fun refreshReviews(isOffline: Boolean) {
        val appId = activeReviewAppId ?: return
        loadReviews(appId, isOffline, force = true)
    }

    fun loadMoreReviews() {
        val appId = activeReviewAppId ?: return
        val cursor = nextReviewCursor ?: return
        val current = mutableReviewState.value as? ReviewSectionState.Content ?: return
        if (!current.canLoadMore || current.loadingMore || cursor in consumedReviewCursors) return
        val revision = reviewRevision
        mutableReviewState.value = current.copy(loadingMore = true, refreshFailed = false)
        reviewJob?.cancel()
        reviewJob = viewModelScope.launch {
            try {
                val page = reviewSource.fetch(appId, mutableReviewQuery.value, cursor)
                if (revision != reviewRevision) return@launch
                consumedReviewCursors += cursor
                reviewPageCount += 1
                nextReviewCursor = page.nextCursor
                val reviews = (current.reviews + page.reviews).distinct().take(MAX_REVIEW_CARDS)
                mutableReviewState.value = ReviewSectionState.Content(
                    reviews = reviews,
                    canLoadMore = canLoadMore() && reviews.size < MAX_REVIEW_CARDS,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (revision == reviewRevision) {
                    mutableReviewState.value = current.copy(
                        loadingMore = false,
                        refreshFailed = true,
                    )
                }
            }
        }
    }

    fun loadDiscussions(
        steamAppId: Int,
        isOffline: Boolean,
        force: Boolean = false,
    ) {
        if (steamAppId <= 0) {
            clearDiscussions()
            mutableDiscussionState.value = DiscussionSectionState.Unavailable
            return
        }
        if (isOffline) {
            discussionJob?.cancel()
            activeDiscussionAppId = steamAppId
            mutableDiscussionState.value = DiscussionSectionState.Offline
            return
        }
        val sameRequest = activeDiscussionAppId == steamAppId
        if (!force && sameRequest && mutableDiscussionState.value !is DiscussionSectionState.Offline) return

        val previous = mutableDiscussionState.value as? DiscussionSectionState.Listing
        discussionJob?.cancel()
        activeDiscussionAppId = steamAppId
        retainedDiscussionListing = null
        nextDiscussionRoute = null
        discussionPageCount = 0
        visitedDiscussionRoutes.clear()
        val revision = ++discussionRevision
        if (previous == null || !sameRequest) {
            mutableDiscussionState.value = DiscussionSectionState.Loading
        }
        discussionJob = viewModelScope.launch {
            try {
                val page = discussionSource.fetchListing(steamAppId, null)
                if (revision != discussionRevision) return@launch
                discussionPageCount = 1
                nextDiscussionRoute = page.nextRoute
                mutableDiscussionState.value = if (page.threads.isEmpty()) {
                    DiscussionSectionState.Empty
                } else {
                    DiscussionSectionState.Listing(
                        threads = page.threads.distinctBy(SteamDiscussionSummary::route)
                            .take(MAX_DISCUSSION_ITEMS),
                        canLoadMore = canLoadMoreDiscussions(),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (revision != discussionRevision) return@launch
                mutableDiscussionState.value = previous
                    ?.copy(loadingMore = false, refreshFailed = true)
                    ?: DiscussionSectionState.Unavailable
            }
        }
    }

    fun openDiscussion(route: String, isOffline: Boolean) {
        val appId = activeDiscussionAppId ?: return
        if (isOffline) {
            discussionJob?.cancel()
            mutableDiscussionState.value = DiscussionSectionState.Offline
            return
        }
        (mutableDiscussionState.value as? DiscussionSectionState.Listing)?.let { listing ->
            retainedDiscussionListing = listing.copy(loadingMore = false)
        }
        loadDiscussionThread(appId, route, previous = null)
    }

    fun refreshDiscussions(isOffline: Boolean) {
        val appId = activeDiscussionAppId ?: return
        when (val current = mutableDiscussionState.value) {
            is DiscussionSectionState.Thread -> {
                if (isOffline) {
                    mutableDiscussionState.value = DiscussionSectionState.Offline
                } else {
                    loadDiscussionThread(appId, current.route, previous = current)
                }
            }
            else -> loadDiscussions(appId, isOffline, force = true)
        }
    }

    fun loadMoreDiscussions() {
        val appId = activeDiscussionAppId ?: return
        val route = nextDiscussionRoute ?: return
        if (route in visitedDiscussionRoutes || discussionPageCount >= MAX_DISCUSSION_PAGES) return
        when (val current = mutableDiscussionState.value) {
            is DiscussionSectionState.Listing -> loadMoreDiscussionListing(appId, route, current)
            is DiscussionSectionState.Thread -> loadMoreDiscussionThread(appId, route, current)
            else -> Unit
        }
    }

    fun closeDiscussionThread(): Boolean {
        val listing = retainedDiscussionListing ?: return false
        discussionJob?.cancel()
        discussionJob = null
        discussionRevision += 1L
        nextDiscussionRoute = null
        discussionPageCount = 0
        visitedDiscussionRoutes.clear()
        retainedDiscussionListing = null
        mutableDiscussionState.value = listing
        return true
    }

    private fun loadDiscussionThread(
        appId: Int,
        route: String,
        previous: DiscussionSectionState.Thread?,
    ) {
        discussionJob?.cancel()
        nextDiscussionRoute = null
        discussionPageCount = 0
        visitedDiscussionRoutes.clear()
        val revision = ++discussionRevision
        if (previous == null) mutableDiscussionState.value = DiscussionSectionState.Loading
        discussionJob = viewModelScope.launch {
            try {
                val thread = discussionSource.fetchThread(appId, route)
                if (revision != discussionRevision) return@launch
                discussionPageCount = 1
                nextDiscussionRoute = thread.nextRoute
                mutableDiscussionState.value = DiscussionSectionState.Thread(
                    title = thread.title,
                    posts = thread.posts.take(MAX_DISCUSSION_ITEMS),
                    route = thread.route,
                    canLoadMore = canLoadMoreDiscussions(),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (revision != discussionRevision) return@launch
                mutableDiscussionState.value = previous
                    ?.copy(loadingMore = false, refreshFailed = true)
                    ?: DiscussionSectionState.Unavailable
            }
        }
    }

    private fun loadMoreDiscussionListing(
        appId: Int,
        route: String,
        current: DiscussionSectionState.Listing,
    ) {
        if (!current.canLoadMore || current.loadingMore) return
        val revision = discussionRevision
        mutableDiscussionState.value = current.copy(loadingMore = true, refreshFailed = false)
        discussionJob?.cancel()
        discussionJob = viewModelScope.launch {
            try {
                val page = discussionSource.fetchListing(appId, route)
                if (revision != discussionRevision) return@launch
                visitedDiscussionRoutes += route
                discussionPageCount += 1
                nextDiscussionRoute = page.nextRoute
                val threads = (current.threads + page.threads)
                    .distinctBy(SteamDiscussionSummary::route)
                    .take(MAX_DISCUSSION_ITEMS)
                mutableDiscussionState.value = DiscussionSectionState.Listing(
                    threads = threads,
                    canLoadMore = canLoadMoreDiscussions() && threads.size < MAX_DISCUSSION_ITEMS,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (revision == discussionRevision) {
                    mutableDiscussionState.value = current.copy(
                        loadingMore = false,
                        refreshFailed = true,
                    )
                }
            }
        }
    }

    private fun loadMoreDiscussionThread(
        appId: Int,
        route: String,
        current: DiscussionSectionState.Thread,
    ) {
        if (!current.canLoadMore || current.loadingMore) return
        val revision = discussionRevision
        mutableDiscussionState.value = current.copy(loadingMore = true, refreshFailed = false)
        discussionJob?.cancel()
        discussionJob = viewModelScope.launch {
            try {
                val page = discussionSource.fetchThread(appId, route)
                if (revision != discussionRevision) return@launch
                visitedDiscussionRoutes += route
                discussionPageCount += 1
                nextDiscussionRoute = page.nextRoute
                val posts = (current.posts + page.posts).take(MAX_DISCUSSION_ITEMS)
                mutableDiscussionState.value = current.copy(
                    posts = posts,
                    canLoadMore = canLoadMoreDiscussions() && posts.size < MAX_DISCUSSION_ITEMS,
                    loadingMore = false,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (revision == discussionRevision) {
                    mutableDiscussionState.value = current.copy(
                        loadingMore = false,
                        refreshFailed = true,
                    )
                }
            }
        }
    }

    fun clearDetail() {
        selectedCanonicalId.value = null
        clearCommunity()
    }

    private fun clearCommunity() {
        clearReviews()
        clearDiscussions()
    }

    private fun clearReviews() {
        reviewJob?.cancel()
        reviewJob = null
        activeReviewAppId = null
        nextReviewCursor = null
        reviewPageCount = 0
        consumedReviewCursors.clear()
        reviewRevision += 1L
        mutableReviewState.value = ReviewSectionState.Idle
    }

    private fun canLoadMore(): Boolean =
        !nextReviewCursor.isNullOrBlank() &&
            nextReviewCursor !in consumedReviewCursors &&
            reviewPageCount < MAX_REVIEW_PAGES

    private fun clearDiscussions() {
        discussionJob?.cancel()
        discussionJob = null
        activeDiscussionAppId = null
        nextDiscussionRoute = null
        discussionPageCount = 0
        visitedDiscussionRoutes.clear()
        retainedDiscussionListing = null
        discussionRevision += 1L
        mutableDiscussionState.value = DiscussionSectionState.Idle
    }

    private fun canLoadMoreDiscussions(): Boolean =
        !nextDiscussionRoute.isNullOrBlank() &&
            nextDiscussionRoute !in visitedDiscussionRoutes &&
            discussionPageCount < MAX_DISCUSSION_PAGES

    private companion object {
        const val MAX_REVIEW_PAGES = 5
        const val MAX_REVIEW_CARDS = 100
        const val MAX_DISCUSSION_PAGES = 5
        const val MAX_DISCUSSION_ITEMS = 100
    }
}
