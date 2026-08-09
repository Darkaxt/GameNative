package app.gamenative.ui.screen.library.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.library.community.DiscussionSectionState
import app.gamenative.library.community.SteamDiscussionPost
import app.gamenative.library.community.SteamDiscussionSummary
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
internal fun SteamDiscussionsTab(
    state: DiscussionSectionState,
    onOpenDiscussion: (String) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onBackToListing: () -> Unit,
    onOpenCommunity: (() -> Unit)?,
    onOpenThread: ((String) -> Unit)?,
) {
    when (state) {
        DiscussionSectionState.Idle,
        DiscussionSectionState.Loading,
        -> DiscussionMessage(
            message = stringResource(R.string.canonical_discussions_loading),
            loading = true,
            onOpenExternal = onOpenCommunity,
        )
        DiscussionSectionState.Empty -> DiscussionMessage(
            message = stringResource(R.string.canonical_discussions_empty),
            onRefresh = onRefresh,
            onOpenExternal = onOpenCommunity,
        )
        DiscussionSectionState.Offline -> DiscussionMessage(
            message = stringResource(R.string.canonical_discussions_offline),
            onOpenExternal = onOpenCommunity,
        )
        DiscussionSectionState.Unavailable -> DiscussionMessage(
            message = stringResource(R.string.canonical_discussions_unavailable),
            onRefresh = onRefresh,
            onOpenExternal = onOpenCommunity,
        )
        is DiscussionSectionState.Listing -> DiscussionListing(
            state = state,
            onOpenDiscussion = onOpenDiscussion,
            onRefresh = onRefresh,
            onLoadMore = onLoadMore,
            onOpenCommunity = onOpenCommunity,
        )
        is DiscussionSectionState.Thread -> DiscussionThread(
            state = state,
            onRefresh = onRefresh,
            onLoadMore = onLoadMore,
            onBackToListing = onBackToListing,
            onOpenThread = onOpenThread,
        )
    }
}

@Composable
private fun DiscussionListing(
    state: DiscussionSectionState.Listing,
    onOpenDiscussion: (String) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenCommunity: (() -> Unit)?,
) {
    val listState = rememberLazyListState()
    LazyLoadEffect(
        listState = listState,
        itemCount = state.threads.size,
        canLoadMore = state.canLoadMore,
        loadingMore = state.loadingMore,
        onLoadMore = onLoadMore,
    )
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .testTag("steam-discussions-list"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        item(key = "discussion-controls") {
            DiscussionControls(
                onRefresh = onRefresh,
                externalLabel = stringResource(R.string.canonical_detail_open_steam_discussions),
                onOpenExternal = onOpenCommunity,
            )
        }
        if (state.refreshFailed) {
            item(key = "discussion-refresh-failed") {
                Text(
                    text = stringResource(R.string.canonical_discussions_refresh_failed),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        itemsIndexed(
            items = state.threads,
            key = { _, thread -> thread.route },
        ) { _, thread ->
            DiscussionSummaryCard(thread, onOpenDiscussion)
        }
        if (state.canLoadMore && !state.loadingMore) {
            item(key = "discussion-load-more") {
                Button(
                    onClick = onLoadMore,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.canonical_discussions_load_more))
                }
            }
        }
        if (state.loadingMore) {
            item(key = "discussion-loading-more") { LoadingRow() }
        }
    }
}

@Composable
private fun DiscussionThread(
    state: DiscussionSectionState.Thread,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onBackToListing: () -> Unit,
    onOpenThread: ((String) -> Unit)?,
) {
    val listState = rememberLazyListState()
    LazyLoadEffect(
        listState = listState,
        itemCount = state.posts.size,
        canLoadMore = state.canLoadMore,
        loadingMore = state.loadingMore,
        onLoadMore = onLoadMore,
    )
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .testTag("steam-discussion-thread"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        item(key = "thread-controls") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBackToListing) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Text(stringResource(R.string.canonical_discussions_back))
                }
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                DiscussionControls(
                    onRefresh = onRefresh,
                    externalLabel = stringResource(R.string.canonical_discussions_open_thread),
                    onOpenExternal = onOpenThread?.let { open -> { open(state.route) } },
                )
            }
        }
        if (state.refreshFailed) {
            item(key = "thread-refresh-failed") {
                Text(
                    text = stringResource(R.string.canonical_discussions_refresh_failed),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        itemsIndexed(state.posts) { index, post ->
            DiscussionPostCard(index, post)
        }
        if (state.canLoadMore && !state.loadingMore) {
            item(key = "thread-load-more") {
                Button(
                    onClick = onLoadMore,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.canonical_discussions_load_more))
                }
            }
        }
        if (state.loadingMore) {
            item(key = "thread-loading-more") { LoadingRow() }
        }
    }
}

@Composable
private fun LazyLoadEffect(
    listState: androidx.compose.foundation.lazy.LazyListState,
    itemCount: Int,
    canLoadMore: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
) {
    LaunchedEffect(listState, itemCount, canLoadMore, loadingMore) {
        if (!canLoadMore || loadingMore) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .map { lastVisible -> lastVisible >= listState.layoutInfo.totalItemsCount - LOAD_MORE_THRESHOLD }
            .distinctUntilChanged()
            .collect { nearBottom -> if (nearBottom) onLoadMore() }
    }
}

@Composable
private fun DiscussionSummaryCard(
    thread: SteamDiscussionSummary,
    onOpenDiscussion: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenDiscussion(thread.route) },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = thread.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                thread.replyCount?.let { replies ->
                    Text(stringResource(R.string.canonical_discussions_replies, replies))
                }
                thread.viewCount?.let { views ->
                    Text(stringResource(R.string.canonical_discussions_views, views))
                }
                thread.activityLabel?.let { activity -> Text(activity) }
            }
        }
    }
}

@Composable
private fun DiscussionPostCard(index: Int, post: SteamDiscussionPost) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.canonical_discussions_steam_user, index + 1),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(post.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun DiscussionControls(
    onRefresh: () -> Unit,
    externalLabel: String,
    onOpenExternal: (() -> Unit)?,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onRefresh) {
            Text(stringResource(R.string.canonical_discussions_refresh))
        }
        onOpenExternal?.let { open ->
            OutlinedButton(onClick = open) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                Text(externalLabel)
            }
        }
    }
}

@Composable
private fun DiscussionMessage(
    message: String,
    loading: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    onOpenExternal: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (loading) CircularProgressIndicator()
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Row(
            modifier = Modifier.padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            onRefresh?.let { refresh ->
                Button(onClick = refresh) {
                    Text(stringResource(R.string.canonical_discussions_refresh))
                }
            }
            onOpenExternal?.let { open ->
                OutlinedButton(onClick = open) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    Text(stringResource(R.string.canonical_detail_open_steam_discussions))
                }
            }
        }
    }
}

@Composable
private fun LoadingRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
    }
}

private const val LOAD_MORE_THRESHOLD = 5
