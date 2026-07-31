package app.gamenative.ui.screen.library.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.RecommendationRepository
import app.gamenative.data.RecommendedGame
import app.gamenative.data.gog.GogRecommendationsRepository
import app.gamenative.ui.data.LibraryCard
import app.gamenative.ui.data.LibraryCardIdentity
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.enums.AppFilter
import app.gamenative.ui.screen.library.AppScreen
import app.gamenative.ui.screen.library.RecommendedGameScreen
import app.gamenative.ui.theme.PluviaTheme
import com.posthog.PostHog
import java.util.EnumSet

@Composable
internal fun LibraryDetailPane(
    libraryItem: LibraryItem?,
    onClickPlay: (Boolean) -> Unit,
    onTestGraphics: () -> Unit,
    onPlayWithDiagnostics: () -> Unit,
    onBack: () -> Unit,
) {
    val card = libraryItem?.let { item ->
        if (item.isRecommended || item.isFeatured) {
            LibraryCard.fromPromotion(item)
        } else {
            LibraryCard.fromSource(item)
        }
    }
    LibraryDetailPane(
        card = card,
        onClickPlay = onClickPlay,
        onTestGraphics = onTestGraphics,
        onPlayWithDiagnostics = onPlayWithDiagnostics,
        onBack = onBack,
    )
}

@Composable
internal fun LibraryDetailPane(
    card: LibraryCard?,
    sourceItem: LibraryItem? = card?.sourceItemOrNull(),
    onCopies: (() -> Unit)? = null,
    onClickPlay: (Boolean) -> Unit,
    onTestGraphics: () -> Unit,
    onPlayWithDiagnostics: () -> Unit,
    onBack: () -> Unit,
) {
    Surface {
        when {
            card == null -> EmptyLibraryDetailPane()
            card.identity is LibraryCardIdentity.Promotion -> {
                val context = LocalContext.current
                var game by remember(card.recommendedGameId) {
                    mutableStateOf<RecommendedGame?>(null)
                }
                LaunchedEffect(card.recommendedGameId) {
                    game = if (card.isFeatured) {
                        RecommendationRepository.getFeaturedGame(context)
                    } else {
                        GogRecommendationsRepository.getRecommendedGame(card.recommendedGameId)
                            ?: RecommendationRepository.getCurrentRecommendation(context)
                    }
                    if (game != null && PrefManager.usageAnalyticsEnabled) {
                        if (card.isFeatured) {
                            PostHog.capture(
                                event = "featured_opened",
                                properties = mapOf(
                                    "campaign_id" to (game?.id ?: ""),
                                    "game_name" to (game?.name ?: ""),
                                    "source" to card.recSource,
                                ),
                            )
                        } else {
                            PostHog.capture(
                                event = "recommendation_opened",
                                properties = mapOf(
                                    "game_name" to (game?.name ?: ""),
                                    "game_id" to (game?.id ?: ""),
                                    "rank" to card.index,
                                    "source" to card.recSource,
                                    "seed_count" to card.recSeedCount,
                                    "because_played" to (game?.becausePlayed ?: ""),
                                ),
                            )
                        }
                    }
                }
                game?.let { rec ->
                    RecommendedGameScreen(
                        game = rec,
                        recRank = card.index,
                        recSource = card.recSource,
                        onBack = onBack,
                    )
                }
            }
            else -> {
                if (sourceItem == null) {
                    EmptyLibraryDetailPane()
                } else if (card.identity is LibraryCardIdentity.Canonical && onCopies != null) {
                    Box {
                        AppScreen(
                            libraryItem = sourceItem,
                            onClickPlay = onClickPlay,
                            onTestGraphics = onTestGraphics,
                            onPlayWithDiagnostics = onPlayWithDiagnostics,
                            onBack = onBack,
                        )
                        TextButton(
                            onClick = onCopies,
                            modifier = Modifier
                                .align(androidx.compose.ui.Alignment.TopEnd)
                                .statusBarsPadding()
                                .padding(end = 16.dp, top = 8.dp)
                                .testTag("copies-action-detail"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = null,
                            )
                            Text(stringResource(R.string.canonical_copies_action))
                        }
                    }
                } else {
                    AppScreen(
                        libraryItem = sourceItem,
                        onClickPlay = onClickPlay,
                        onTestGraphics = onTestGraphics,
                        onPlayWithDiagnostics = onPlayWithDiagnostics,
                        onBack = onBack,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLibraryDetailPane() {
    val listState = rememberLazyGridState()
    val emptyState = remember {
        LibraryState(
            cards = emptyList(),
            appInfoSortType = EnumSet.of(AppFilter.GAME),
        )
    }

    LibraryListPane(
        state = emptyState,
        listState = listState,
        currentLayout = PrefManager.libraryLayout,
        onPageChange = {},
        onNavigate = {},
        onRefresh = {},
    )
}

/***********
 * PREVIEW *
 ***********/

@Preview(uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES or android.content.res.Configuration.UI_MODE_TYPE_NORMAL)
@Preview(device = "spec:width=1920px,height=1080px,dpi=440") // Odin2 Mini
@Composable
private fun Preview_LibraryDetailPane() {
    PrefManager.init(LocalContext.current)
    PluviaTheme {
        LibraryDetailPane(
            card = LibraryCard.fromSource(
                LibraryItem(
                    appId = "${GameSource.STEAM.name}_${Int.MAX_VALUE}",
                    name = "Preview Game",
                    iconHash = "",
                    gameSource = GameSource.STEAM,
                ),
            ),
            onClickPlay = { },
            onTestGraphics = { },
            onPlayWithDiagnostics = { },
            onBack = { },
        )
    }
}
