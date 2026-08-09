package app.gamenative.ui.screen.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.library.community.ReviewSectionState
import app.gamenative.library.community.SteamReviewCard
import app.gamenative.library.community.SteamReviewLanguage
import app.gamenative.library.community.SteamReviewPolarity
import app.gamenative.library.community.SteamReviewPurchaseType
import app.gamenative.library.community.SteamReviewQuery
import app.gamenative.library.community.SteamReviewSort
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
internal fun SteamReviewsTab(
    state: ReviewSectionState,
    query: SteamReviewQuery,
    onQueryChange: (SteamReviewQuery) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenSteam: (() -> Unit)?,
) {
    when (state) {
        ReviewSectionState.Idle,
        ReviewSectionState.Loading,
        -> ReviewMessage(
            message = stringResource(R.string.canonical_reviews_loading),
            loading = true,
            onOpenSteam = onOpenSteam,
        )
        ReviewSectionState.Empty -> ReviewMessage(
            message = stringResource(R.string.canonical_reviews_empty),
            onRefresh = onRefresh,
            onOpenSteam = onOpenSteam,
        )
        ReviewSectionState.Offline -> ReviewMessage(
            message = stringResource(R.string.canonical_reviews_offline),
            onOpenSteam = onOpenSteam,
        )
        ReviewSectionState.Unavailable -> ReviewMessage(
            message = stringResource(R.string.canonical_reviews_unavailable),
            onRefresh = onRefresh,
            onOpenSteam = onOpenSteam,
        )
        is ReviewSectionState.Content -> ReviewList(
            state = state,
            query = query,
            onQueryChange = onQueryChange,
            onRefresh = onRefresh,
            onLoadMore = onLoadMore,
            onOpenSteam = onOpenSteam,
        )
    }
}

@Composable
private fun ReviewList(
    state: ReviewSectionState.Content,
    query: SteamReviewQuery,
    onQueryChange: (SteamReviewQuery) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenSteam: (() -> Unit)?,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(listState, state.reviews.size, state.canLoadMore, state.loadingMore) {
        if (!state.canLoadMore || state.loadingMore) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .map { lastVisible -> lastVisible >= listState.layoutInfo.totalItemsCount - LOAD_MORE_THRESHOLD }
            .distinctUntilChanged()
            .collect { nearBottom -> if (nearBottom) onLoadMore() }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .testTag("steam-reviews-list"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        item(key = "review-controls") {
            ReviewControls(
                query = query,
                onQueryChange = onQueryChange,
                onRefresh = onRefresh,
                onOpenSteam = onOpenSteam,
            )
        }
        if (state.refreshFailed) {
            item(key = "review-refresh-failed") {
                Text(
                    text = stringResource(R.string.canonical_reviews_refresh_failed),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        itemsIndexed(
            items = state.reviews,
            key = { index, review -> "${review.postedAtEpochSeconds}:$index" },
        ) { _, review ->
            SteamReviewCard(review)
        }
        if (state.canLoadMore && !state.loadingMore) {
            item(key = "reviews-load-more") {
                Button(
                    onClick = onLoadMore,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.canonical_reviews_load_more))
                }
            }
        }
        if (state.loadingMore) {
            item(key = "reviews-loading-more") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewControls(
    query: SteamReviewQuery,
    onQueryChange: (SteamReviewQuery) -> Unit,
    onRefresh: () -> Unit,
    onOpenSteam: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ReviewFilterChip(
                label = stringResource(R.string.canonical_reviews_helpful),
                selected = query.sort == SteamReviewSort.HELPFUL,
                onClick = { onQueryChange(query.copy(sort = SteamReviewSort.HELPFUL)) },
            )
            ReviewFilterChip(
                label = stringResource(R.string.canonical_reviews_recent),
                selected = query.sort == SteamReviewSort.RECENT,
                onClick = { onQueryChange(query.copy(sort = SteamReviewSort.RECENT)) },
            )
            ReviewFilterChip(
                label = stringResource(R.string.canonical_reviews_all),
                selected = query.polarity == SteamReviewPolarity.ALL,
                onClick = { onQueryChange(query.copy(polarity = SteamReviewPolarity.ALL)) },
            )
            ReviewFilterChip(
                label = stringResource(R.string.canonical_reviews_positive),
                selected = query.polarity == SteamReviewPolarity.POSITIVE,
                onClick = { onQueryChange(query.copy(polarity = SteamReviewPolarity.POSITIVE)) },
            )
            ReviewFilterChip(
                label = stringResource(R.string.canonical_reviews_negative),
                selected = query.polarity == SteamReviewPolarity.NEGATIVE,
                onClick = { onQueryChange(query.copy(polarity = SteamReviewPolarity.NEGATIVE)) },
            )
            ReviewFilterChip(
                label = stringResource(R.string.canonical_reviews_app_language),
                selected = query.language == SteamReviewLanguage.APP_LANGUAGE,
                onClick = { onQueryChange(query.copy(language = SteamReviewLanguage.APP_LANGUAGE)) },
            )
            ReviewFilterChip(
                label = stringResource(R.string.canonical_reviews_all_languages),
                selected = query.language == SteamReviewLanguage.ALL,
                onClick = { onQueryChange(query.copy(language = SteamReviewLanguage.ALL)) },
            )
            ReviewFilterChip(
                label = stringResource(R.string.canonical_reviews_all_purchases),
                selected = query.purchaseType == SteamReviewPurchaseType.ALL,
                onClick = { onQueryChange(query.copy(purchaseType = SteamReviewPurchaseType.ALL)) },
            )
            ReviewFilterChip(
                label = stringResource(R.string.canonical_reviews_steam_purchases),
                selected = query.purchaseType == SteamReviewPurchaseType.STEAM,
                onClick = { onQueryChange(query.copy(purchaseType = SteamReviewPurchaseType.STEAM)) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRefresh) {
                Text(stringResource(R.string.canonical_reviews_refresh))
            }
            onOpenSteam?.let { onOpen ->
                OutlinedButton(onClick = onOpen) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    Text(stringResource(R.string.canonical_detail_open_steam_reviews))
                }
            }
        }
    }
}

@Composable
private fun ReviewFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}

@Composable
private fun SteamReviewCard(review: SteamReviewCard) {
    val date = remember(review.postedAtEpochSeconds) {
        DateFormat.getDateInstance(DateFormat.MEDIUM)
            .format(Date(review.postedAtEpochSeconds * 1_000L))
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(
                    if (review.recommended) {
                        R.string.canonical_reviews_recommended
                    } else {
                        R.string.canonical_reviews_not_recommended
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            review.playtimeMinutes?.let { minutes ->
                Text(
                    text = stringResource(R.string.canonical_reviews_playtime, minutes / 60f),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(review.text, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(
                    R.string.canonical_reviews_stats,
                    date,
                    review.helpfulVotes,
                    review.funnyVotes,
                    review.commentCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (review.receivedForFree) {
                Text(stringResource(R.string.canonical_reviews_received_free))
            }
            if (review.earlyAccess) {
                Text(stringResource(R.string.canonical_reviews_early_access))
            }
            review.developerResponse?.let { response ->
                Text(
                    text = stringResource(R.string.canonical_reviews_developer_response),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(response)
            }
        }
    }
}

@Composable
private fun ReviewMessage(
    message: String,
    loading: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    onOpenSteam: (() -> Unit)? = null,
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
                    Text(stringResource(R.string.canonical_reviews_refresh))
                }
            }
            onOpenSteam?.let { onOpen ->
                OutlinedButton(onClick = onOpen) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    Text(stringResource(R.string.canonical_detail_open_steam_reviews))
                }
            }
        }
    }
}

private const val LOAD_MORE_THRESHOLD = 5
