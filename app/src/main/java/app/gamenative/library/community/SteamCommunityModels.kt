package app.gamenative.library.community

enum class SteamReviewSort { HELPFUL, RECENT }
enum class SteamReviewPolarity { ALL, POSITIVE, NEGATIVE }
enum class SteamReviewLanguage { APP_LANGUAGE, ALL }
enum class SteamReviewPurchaseType { ALL, STEAM }

data class SteamReviewQuery(
    val sort: SteamReviewSort = SteamReviewSort.HELPFUL,
    val polarity: SteamReviewPolarity = SteamReviewPolarity.ALL,
    val language: SteamReviewLanguage = SteamReviewLanguage.APP_LANGUAGE,
    val purchaseType: SteamReviewPurchaseType = SteamReviewPurchaseType.ALL,
)

data class SteamReviewCard(
    val recommended: Boolean,
    val text: String,
    val playtimeMinutes: Int?,
    val helpfulVotes: Int,
    val funnyVotes: Int,
    val commentCount: Int,
    val postedAtEpochSeconds: Long,
    val updatedAtEpochSeconds: Long,
    val receivedForFree: Boolean,
    val earlyAccess: Boolean,
    val developerResponse: String?,
    val recommendationId: String = "",
)

data class SteamReviewPage(
    val reviews: List<SteamReviewCard>,
    val nextCursor: String?,
)

sealed interface ReviewSectionState {
    data object Idle : ReviewSectionState
    data object Loading : ReviewSectionState
    data class Content(
        val reviews: List<SteamReviewCard>,
        val canLoadMore: Boolean,
        val loadingMore: Boolean = false,
        val refreshFailed: Boolean = false,
    ) : ReviewSectionState
    data object Empty : ReviewSectionState
    data object Offline : ReviewSectionState
    data object Unavailable : ReviewSectionState
}

fun interface SteamReviewPageSource {
    suspend fun fetch(
        steamAppId: Int,
        query: SteamReviewQuery,
        cursor: String?,
    ): SteamReviewPage
}

data class SteamDiscussionSummary(
    val title: String,
    val replyCount: Int?,
    val activityLabel: String?,
    val route: String,
    val viewCount: Int? = null,
)

data class SteamDiscussionListing(
    val threads: List<SteamDiscussionSummary>,
    val nextRoute: String?,
)

data class SteamDiscussionPost(val text: String)

data class SteamDiscussionThread(
    val title: String,
    val posts: List<SteamDiscussionPost>,
    val route: String,
    val nextRoute: String?,
)

sealed interface DiscussionSectionState {
    data object Idle : DiscussionSectionState
    data object Loading : DiscussionSectionState
    data class Listing(
        val threads: List<SteamDiscussionSummary>,
        val canLoadMore: Boolean,
        val loadingMore: Boolean = false,
        val refreshFailed: Boolean = false,
    ) : DiscussionSectionState
    data class Thread(
        val title: String,
        val posts: List<SteamDiscussionPost>,
        val route: String,
        val canLoadMore: Boolean,
        val loadingMore: Boolean = false,
        val refreshFailed: Boolean = false,
    ) : DiscussionSectionState
    data object Empty : DiscussionSectionState
    data object Offline : DiscussionSectionState
    data object Unavailable : DiscussionSectionState
}

interface SteamDiscussionSource {
    suspend fun fetchListing(steamAppId: Int, route: String?): SteamDiscussionListing
    suspend fun fetchThread(steamAppId: Int, route: String): SteamDiscussionThread
}
