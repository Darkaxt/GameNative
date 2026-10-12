package app.gamenative.ui.screen.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.library.community.SteamReviewCommentState

@Composable
internal fun SteamReviewCommentsPane(
    state: SteamReviewCommentState,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
) {
    val controls: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.canonical_review_comments), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClose, modifier = Modifier.testTag("steam-review-comments-back")) {
                Text(stringResource(R.string.canonical_review_comments_back))
            }
            if (state !is SteamReviewCommentState.Loading && state !is SteamReviewCommentState.Offline) {
                Button(onClick = onRefresh) { Text(stringResource(R.string.canonical_reviews_refresh)) }
            }
        }
    }
    AdaptiveCommunityPane(controls = controls) { inlineControls ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("steam-review-comments-list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (inlineControls) item(key = "comment-controls") { controls() }
            when (state) {
                is SteamReviewCommentState.Content -> {
                    if (state.refreshFailed) item(key = "comment-refresh-failed") {
                        Text(stringResource(R.string.canonical_review_comments_refresh_failed), color = MaterialTheme.colorScheme.error)
                    }
                    items(state.comments, key = { "comment:${it.commentId}" }) { comment ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.canonical_community_steam_user), style = MaterialTheme.typography.labelMedium)
                                Text(comment.text, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    if (state.loadingMore) item(key = "comment-loading-more") { CircularProgressIndicator() }
                    else if (state.canLoadMore) item(key = "comments-load-more") {
                        Button(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.canonical_reviews_load_more))
                        }
                    }
                }
                else -> item(key = "comment-message") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state is SteamReviewCommentState.Loading) CircularProgressIndicator()
                        Text(stringResource(when (state) {
                            is SteamReviewCommentState.Loading -> R.string.canonical_review_comments_loading
                            is SteamReviewCommentState.Empty -> R.string.canonical_review_comments_empty
                            is SteamReviewCommentState.Offline -> R.string.canonical_review_comments_offline
                            else -> R.string.canonical_review_comments_unavailable
                        }))
                    }
                }
            }
        }
    }
}
