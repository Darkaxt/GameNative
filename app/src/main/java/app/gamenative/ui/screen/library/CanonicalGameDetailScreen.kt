package app.gamenative.ui.screen.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.GameCompatibilityStatus
import app.gamenative.data.GameSource
import app.gamenative.library.metadata.CanonicalGameMetadata
import app.gamenative.library.metadata.GameDetailState
import app.gamenative.library.metadata.GamePlatform
import app.gamenative.ui.screen.library.components.GameMediaItem
import app.gamenative.ui.screen.library.components.GameMediaLoadingPolicy
import app.gamenative.ui.screen.library.components.GameMediaPager
import app.gamenative.ui.screen.library.components.OwnedSourceBadges
import app.gamenative.utils.HltbService

private enum class CanonicalDetailTab {
    OVERVIEW,
    REVIEWS,
    DISCUSSIONS,
    DETAILS,
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CanonicalGameDetailScreen(
    state: GameDetailState,
    fallbackTitle: String,
    fallbackImageUrl: String,
    ownedSources: Set<GameSource>,
    compatibilityStatus: GameCompatibilityStatus?,
    hltbStats: HltbService.Stats?,
    isOffline: Boolean,
    onBack: () -> Unit,
    onCopies: () -> Unit,
    onSourceDetails: () -> Unit,
    onRetry: () -> Unit,
) {
    val metadata = when (state) {
        is GameDetailState.Content -> state.metadata
        is GameDetailState.Unavailable -> state.cached
        GameDetailState.Loading -> null
    }
    val title = metadata?.title?.takeIf(String::isNotBlank) ?: fallbackTitle
    val steamImageUrls = remember(metadata) {
        metadata?.let(::canonicalSteamImageUrls).orEmpty()
    }
    val media = remember(steamImageUrls) {
        steamImageUrls.map { imageUrl -> GameMediaItem(imageUrl = imageUrl) }
    }
    val hasSteamMedia = media.isNotEmpty() || !metadata?.headerImageUrl.isNullOrBlank()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = CanonicalDetailTab.entries

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("canonical-detail-screen"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
        ) {
            GameMediaPager(
                media = media,
                fallbackImageUrl = if (hasSteamMedia) {
                    metadata?.headerImageUrl
                } else {
                    fallbackImageUrl
                },
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                loadingPolicy = if (hasSteamMedia) {
                    GameMediaLoadingPolicy.STEAM_IMAGES_ONLY
                } else {
                    GameMediaLoadingPolicy.DEFAULT
                },
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                        ),
                    ),
            )
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = Color.White,
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                if (ownedSources.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    OwnedSourceBadges(sources = ownedSources.toList(), iconSize = 16)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onCopies,
                modifier = Modifier
                    .weight(1f)
                    .testTag("canonical-detail-copies"),
            ) {
                Text(stringResource(R.string.canonical_copies_action))
            }
            OutlinedButton(
                onClick = onSourceDetails,
                modifier = Modifier
                    .weight(1f)
                    .testTag("canonical-detail-source-details"),
            ) {
                Text(stringResource(R.string.canonical_open_source_details))
            }
        }

        if (isOffline) {
            DetailStatusBanner(stringResource(R.string.canonical_detail_offline))
        }
        if (state is GameDetailState.Content && state.stale) {
            DetailStatusBanner(stringResource(R.string.canonical_detail_stale))
        }
        if (state is GameDetailState.Content && state.refreshFailed) {
            DetailStatusBanner(stringResource(R.string.canonical_detail_refresh_failed))
        }

        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            edgePadding = 0.dp,
        ) {
            tabs.forEachIndexed { index, tab ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(stringResource(tab.labelResId())) },
                    modifier = Modifier.testTag("canonical-detail-tab:${tab.name}"),
                )
            }
        }

        when (tabs[selectedTab]) {
            CanonicalDetailTab.OVERVIEW -> DetailOverview(
                state = state,
                metadata = metadata,
                compatibilityStatus = compatibilityStatus,
                hltbStats = hltbStats,
                onRetry = onRetry,
            )
            CanonicalDetailTab.REVIEWS -> DetailPlaceholder(
                text = stringResource(R.string.canonical_detail_reviews_placeholder),
            )
            CanonicalDetailTab.DISCUSSIONS -> DetailPlaceholder(
                text = stringResource(R.string.canonical_detail_discussions_placeholder),
            )
            CanonicalDetailTab.DETAILS -> DetailFields(metadata)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailOverview(
    state: GameDetailState,
    metadata: CanonicalGameMetadata?,
    compatibilityStatus: GameCompatibilityStatus?,
    hltbStats: HltbService.Stats?,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            state == GameDetailState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            metadata == null -> {
                Text(stringResource(R.string.canonical_detail_unavailable))
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.canonical_detail_retry))
                }
            }
            else -> {
                metadata.shortDescription?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                metadata.about?.let { about ->
                    Text(
                        text = stringResource(R.string.canonical_detail_about),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(text = about, style = MaterialTheme.typography.bodyMedium)
                }
                if (metadata.features.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.canonical_detail_features),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        metadata.features.forEach { feature ->
                            AssistChip(onClick = {}, label = { Text(feature.label) })
                        }
                    }
                }
            }
        }

        compatibilityStatus?.let { status ->
            HorizontalDivider()
            Text(
                text = stringResource(status.labelResId()),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        hltbStats?.let { stats ->
            Text(
                text = stringResource(R.string.canonical_detail_hltb_main, stats.mainHours),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun DetailPlaceholder(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DetailFields(metadata: CanonicalGameMetadata?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (metadata == null) {
            Text(stringResource(R.string.canonical_detail_unavailable))
            return@Column
        }
        DetailField(R.string.canonical_detail_developer, metadata.developers.joinToString(", "))
        DetailField(R.string.canonical_detail_publisher, metadata.publishers.joinToString(", "))
        DetailField(R.string.canonical_detail_release, metadata.releaseDate)
        DetailField(
            R.string.canonical_detail_platforms,
            GamePlatform.entries
                .filter(metadata.platforms::contains)
                .joinToString(", ") { platform -> platform.label() },
        )
        DetailField(R.string.canonical_detail_languages, metadata.languages.joinToString(", "))
        metadata.requirements?.minimum?.let { minimum ->
            DetailField(R.string.canonical_detail_requirements, minimum)
        }
        metadata.requirements?.recommended?.let { recommended ->
            DetailField(R.string.canonical_detail_requirements, recommended)
        }
        DetailField(R.string.canonical_detail_achievements, metadata.achievementCount?.toString())
        DetailField(R.string.canonical_detail_dlc, metadata.dlcCount?.toString())
    }
}

@Composable
private fun DetailField(labelResId: Int, value: String?) {
    if (value.isNullOrBlank()) return
    Column {
        Text(
            text = stringResource(labelResId),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DetailStatusBanner(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

internal fun canonicalSteamImageUrls(metadata: CanonicalGameMetadata): List<String> = buildList {
    metadata.movies.forEach { movie ->
        (movie.previewImageUrl ?: metadata.headerImageUrl)?.let(::add)
    }
    addAll(metadata.screenshots)
}.filter(String::isNotBlank).distinct()

private fun CanonicalDetailTab.labelResId(): Int = when (this) {
    CanonicalDetailTab.OVERVIEW -> R.string.canonical_detail_overview
    CanonicalDetailTab.REVIEWS -> R.string.canonical_detail_reviews
    CanonicalDetailTab.DISCUSSIONS -> R.string.canonical_detail_discussions
    CanonicalDetailTab.DETAILS -> R.string.canonical_detail_details
}

private fun GameCompatibilityStatus.labelResId(): Int = when (this) {
    GameCompatibilityStatus.NOT_COMPATIBLE -> R.string.library_not_compatible
    GameCompatibilityStatus.UNKNOWN -> R.string.library_compatibility_unknown
    GameCompatibilityStatus.COMPATIBLE -> R.string.library_compatible
    GameCompatibilityStatus.GPU_COMPATIBLE -> R.string.library_gpu_compatible
    GameCompatibilityStatus.RECOMMENDED -> R.string.recommended_badge
}

private fun GamePlatform.label(): String = when (this) {
    GamePlatform.WINDOWS -> "Windows"
    GamePlatform.MACOS -> "macOS"
    GamePlatform.LINUX -> "Linux"
}
