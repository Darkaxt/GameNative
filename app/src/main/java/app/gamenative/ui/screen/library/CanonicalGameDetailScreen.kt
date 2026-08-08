package app.gamenative.ui.screen.library

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
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
import app.gamenative.ui.model.SteamMatchStatus
import app.gamenative.ui.screen.library.components.GameMediaItem
import app.gamenative.ui.screen.library.components.GameMediaPager
import app.gamenative.ui.screen.library.components.OwnedSourceBadges
import app.gamenative.ui.screen.library.components.SteamMediaGallery
import app.gamenative.ui.screen.library.components.SteamMediaImage
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
    steamAppId: Int?,
    ownedSources: Set<GameSource>,
    compatibilityStatus: GameCompatibilityStatus?,
    hltbStats: HltbService.Stats?,
    isOffline: Boolean,
    onBack: () -> Unit,
    onCopies: () -> Unit,
    onSourceDetails: () -> Unit,
    onRetry: () -> Unit,
    steamMatchStatus: SteamMatchStatus? = null,
    onFixSteamMatch: (() -> Unit)? = null,
) {
    val metadata = when (state) {
        is GameDetailState.Content -> state.metadata
        is GameDetailState.Unavailable -> state.cached
        GameDetailState.Loading -> null
    }
    val title = metadata?.title?.takeIf(String::isNotBlank) ?: fallbackTitle
    val media = remember(metadata) { metadata?.let(::canonicalSteamMediaItems).orEmpty() }
    val tabs = CanonicalDetailTab.entries
    val uriHandler = LocalUriHandler.current
    var selectedTab by remember { mutableIntStateOf(0) }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("canonical-detail-screen"),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        BoxWithConstraints {
            val heroHeight = if (maxWidth >= TABLET_BREAKPOINT) 190.dp else 240.dp
            Column(modifier = Modifier.fillMaxSize()) {
                CanonicalHero(
                    title = title,
                    imageUrl = metadata?.headerImageUrl,
                    fallbackImageUrl = fallbackImageUrl,
                    ownedSources = ownedSources,
                    heroHeight = heroHeight,
                    onBack = onBack,
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = DETAIL_MAX_WIDTH)
                        .weight(1f)
                        .align(Alignment.CenterHorizontally),
                ) {
                    DetailActions(
                        onCopies = onCopies,
                        onSourceDetails = onSourceDetails,
                    )
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
                        modifier = Modifier.fillMaxWidth(),
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
                            media = media,
                            title = title,
                            compatibilityStatus = compatibilityStatus,
                            hltbStats = hltbStats,
                            onRetry = onRetry,
                        )
                        CanonicalDetailTab.REVIEWS -> DetailPlaceholder(
                            text = stringResource(R.string.canonical_detail_reviews_placeholder),
                            actionLabel = steamAppId?.let {
                                stringResource(R.string.canonical_detail_open_steam_reviews)
                            },
                            onAction = steamAppId?.let { appId ->
                                { uriHandler.openUri(steamReviewUrl(appId)) }
                            },
                        )
                        CanonicalDetailTab.DISCUSSIONS -> DetailPlaceholder(
                            text = stringResource(R.string.canonical_detail_discussions_placeholder),
                            actionLabel = steamAppId?.let {
                                stringResource(R.string.canonical_detail_open_steam_discussions)
                            },
                            onAction = steamAppId?.let { appId ->
                                { uriHandler.openUri(steamDiscussionUrl(appId)) }
                            },
                        )
                        CanonicalDetailTab.DETAILS -> DetailFields(
                            metadata = metadata,
                            steamMatchStatus = steamMatchStatus,
                            onFixSteamMatch = onFixSteamMatch,
                            links = remember(steamAppId) { steamResourceLinks(steamAppId) },
                            onOpen = uriHandler::openUri,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CanonicalHero(
    title: String,
    imageUrl: String?,
    fallbackImageUrl: String,
    ownedSources: Set<GameSource>,
    heroHeight: androidx.compose.ui.unit.Dp,
    onBack: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heroHeight),
    ) {
        if (!imageUrl.isNullOrBlank()) {
            SteamMediaImage(
                imageUrl = imageUrl,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            GameMediaPager(
                media = emptyList(),
                fallbackImageUrl = fallbackImageUrl,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f)),
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
}

@Composable
private fun DetailActions(
    onCopies: () -> Unit,
    onSourceDetails: () -> Unit,
) {
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailOverview(
    state: GameDetailState,
    metadata: CanonicalGameMetadata?,
    media: List<GameMediaItem>,
    title: String,
    compatibilityStatus: GameCompatibilityStatus?,
    hltbStats: HltbService.Stats?,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
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
                if (media.isNotEmpty() || !metadata.headerImageUrl.isNullOrBlank()) {
                    SteamMediaGallery(
                        media = media,
                        fallbackImageUrl = metadata.headerImageUrl,
                        contentDescription = title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = MEDIA_MAX_WIDTH)
                            .align(Alignment.CenterHorizontally),
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = READING_MAX_WIDTH)
                        .align(Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
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
        }
    }
}

@Composable
private fun DetailPlaceholder(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onAction) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun DetailFields(
    metadata: CanonicalGameMetadata?,
    steamMatchStatus: SteamMatchStatus?,
    onFixSteamMatch: (() -> Unit)?,
    links: List<SteamResourceLink>,
    onOpen: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .widthIn(max = READING_MAX_WIDTH),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (metadata == null) {
            Text(stringResource(R.string.canonical_detail_unavailable))
        } else {
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
        steamMatchStatus?.let { status ->
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                text = stringResource(R.string.steam_match_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(status.labelResId()),
                style = MaterialTheme.typography.bodyLarge,
            )
            onFixSteamMatch?.let { onFix ->
                OutlinedButton(
                    onClick = onFix,
                    modifier = Modifier.testTag("canonical-detail-fix-steam-match"),
                ) {
                    Text(stringResource(R.string.steam_match_fix))
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        DetailResourcesSection(links = links, onOpen = onOpen)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailResourcesSection(
    links: List<SteamResourceLink>,
    onOpen: (String) -> Unit,
) {
    Text(
        text = stringResource(R.string.canonical_detail_resources),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        text = stringResource(R.string.canonical_detail_resources_description),
        style = MaterialTheme.typography.bodyLarge,
    )
    if (links.isEmpty()) {
        Text(stringResource(R.string.canonical_detail_resources_unavailable))
    } else {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            links.forEach { link ->
                OutlinedButton(onClick = { onOpen(link.url) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(link.labelResId))
                }
            }
        }
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

internal fun canonicalSteamMediaItems(metadata: CanonicalGameMetadata): List<GameMediaItem> = buildList {
    metadata.movies.forEach { movie ->
        add(
            GameMediaItem(
                imageUrl = movie.previewImageUrl ?: metadata.headerImageUrl,
                videoUrl = movie.streamUrl,
            ),
        )
    }
    metadata.screenshots.forEach { imageUrl ->
        add(GameMediaItem(imageUrl = imageUrl))
    }
}.distinctBy { item -> item.videoUrl ?: item.imageUrl }

internal data class SteamResourceLink(
    @StringRes val labelResId: Int,
    val url: String,
)

internal fun steamResourceLinks(steamAppId: Int?): List<SteamResourceLink> {
    val appId = steamAppId?.takeIf { it > 0 } ?: return emptyList()
    return listOf(
        SteamResourceLink(R.string.canonical_resource_store, "https://store.steampowered.com/app/$appId/"),
        SteamResourceLink(R.string.canonical_resource_community, "https://steamcommunity.com/app/$appId/"),
        SteamResourceLink(R.string.canonical_resource_discussions, steamDiscussionUrl(appId)),
        SteamResourceLink(R.string.canonical_resource_guides, "https://steamcommunity.com/app/$appId/guides/"),
        SteamResourceLink(R.string.canonical_resource_workshop, "https://steamcommunity.com/app/$appId/workshop/"),
        SteamResourceLink(R.string.canonical_resource_news, "https://store.steampowered.com/news/app/$appId/"),
        SteamResourceLink(R.string.canonical_resource_achievements, "https://steamcommunity.com/stats/$appId/achievements/"),
    )
}

private fun steamReviewUrl(appId: Int): String = "https://steamcommunity.com/app/$appId/reviews/"

private fun steamDiscussionUrl(appId: Int): String = "https://steamcommunity.com/app/$appId/discussions/"

private fun CanonicalDetailTab.labelResId(): Int = when (this) {
    CanonicalDetailTab.OVERVIEW -> R.string.canonical_detail_overview
    CanonicalDetailTab.REVIEWS -> R.string.canonical_detail_reviews
    CanonicalDetailTab.DISCUSSIONS -> R.string.canonical_detail_discussions
    CanonicalDetailTab.DETAILS -> R.string.canonical_detail_details
}

private fun SteamMatchStatus.labelResId(): Int = when (this) {
    SteamMatchStatus.AUTOMATIC -> R.string.steam_match_status_automatic
    SteamMatchStatus.USER_CONFIRMED -> R.string.steam_match_status_user_confirmed
    SteamMatchStatus.NEEDS_REVIEW -> R.string.steam_match_status_needs_review
    SteamMatchStatus.KEPT_SEPARATE -> R.string.steam_match_status_kept_separate
    SteamMatchStatus.UNMATCHED -> R.string.steam_match_status_unmatched
    SteamMatchStatus.CHECKING -> R.string.steam_match_status_checking
    SteamMatchStatus.IMMUTABLE_STEAM -> R.string.steam_match_status_immutable
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

private val TABLET_BREAKPOINT = 840.dp
private val DETAIL_MAX_WIDTH = 1180.dp
private val MEDIA_MAX_WIDTH = 960.dp
private val READING_MAX_WIDTH = 840.dp
