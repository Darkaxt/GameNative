package app.gamenative.library.community

import java.io.IOException
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.ExperimentalCoroutinesApi

data class SteamReviewComment(val commentId: String, val text: String)

data class SteamReviewCommentPage(val comments: List<SteamReviewComment>, val nextStart: Int?)

sealed interface SteamReviewCommentState {
    data object Idle : SteamReviewCommentState
    data class Loading(val recommendationId: String) : SteamReviewCommentState
    data class Content(
        val recommendationId: String,
        val comments: List<SteamReviewComment>,
        val canLoadMore: Boolean,
        val loadingMore: Boolean = false,
        val refreshFailed: Boolean = false,
    ) : SteamReviewCommentState
    data class Empty(val recommendationId: String) : SteamReviewCommentState
    data class Offline(val recommendationId: String) : SteamReviewCommentState
    data class Unavailable(val recommendationId: String) : SteamReviewCommentState
}

fun interface SteamReviewCommentSource {
    suspend fun fetch(
        steamAppId: Int,
        query: SteamReviewQuery,
        reviewCursor: String?,
        recommendationId: String,
        start: Int,
    ): SteamReviewCommentPage
}

internal object UnavailableSteamReviewComments : SteamReviewCommentSource {
    override suspend fun fetch(
        steamAppId: Int,
        query: SteamReviewQuery,
        reviewCursor: String?,
        recommendationId: String,
        start: Int,
    ): SteamReviewCommentPage = throw SteamReviewCommentsUnavailable()
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class SteamReviewCommentsUnavailable :
    IOException("Steam review comments unavailable"), CopyableThrowable<SteamReviewCommentsUnavailable> {
    override fun createCopy(): SteamReviewCommentsUnavailable? = null
}
