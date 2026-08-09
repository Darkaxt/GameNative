package app.gamenative.ui.screen.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.gamenative.data.GameCompatibilityStatus
import app.gamenative.data.GameSource
import app.gamenative.library.metadata.CanonicalGameMetadata
import app.gamenative.library.metadata.GameDetailState
import app.gamenative.library.metadata.GameMovie
import app.gamenative.library.metadata.GamePlatform
import app.gamenative.library.metadata.GameRequirements
import app.gamenative.library.metadata.MetadataFacet
import app.gamenative.ui.model.SteamMatchStatus
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.utils.HltbService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CanonicalGameDetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tabsShowHonestContentActionsResourcesAndOfflineStaleSemantics() {
        var copiesClicks = 0
        var sourceDetailsClicks = 0
        var fixMatchClicks = 0
        composeRule.setContent {
            PluviaTheme {
                CanonicalGameDetailScreen(
                    state = GameDetailState.Content(
                        metadata = metadata(),
                        stale = true,
                        refreshFailed = true,
                    ),
                    fallbackTitle = "Canonical fallback",
                    fallbackImageUrl = "",
                    steamAppId = 123456,
                    ownedSources = setOf(GameSource.STEAM, GameSource.GOG),
                    compatibilityStatus = GameCompatibilityStatus.COMPATIBLE,
                    hltbStats = HltbService.Stats("10", "15", "20", "12"),
                    isOffline = true,
                    onBack = {},
                    onCopies = { copiesClicks += 1 },
                    onSourceDetails = { sourceDetailsClicks += 1 },
                    onRetry = {},
                    steamMatchStatus = SteamMatchStatus.NEEDS_REVIEW,
                    onFixSteamMatch = { fixMatchClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("canonical-detail-screen").assertIsDisplayed()
        composeRule.onNodeWithText("Overview").assertIsDisplayed()
        composeRule.onNodeWithText("Reviews").assertIsDisplayed()
        composeRule.onNodeWithText("Discussions").assertIsDisplayed()
        composeRule.onNodeWithText("Details").assertIsDisplayed()
        composeRule.onNodeWithTag("canonical-detail-tab:RESOURCES").assertDoesNotExist()
        composeRule.onNodeWithText("Showing saved details offline").assertIsDisplayed()
        composeRule.onNodeWithText("Saved details may be out of date").assertIsDisplayed()
        composeRule.onNodeWithText("Plain short description").assertIsDisplayed()
        composeRule.onNodeWithText("Single-player").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Owned on Steam").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Owned on GOG").assertIsDisplayed()
        composeRule.onNodeWithText("Compatible").assertIsDisplayed()
        composeRule.onNodeWithText("Main story: 10 hours").assertIsDisplayed()

        composeRule.onNodeWithTag("canonical-detail-copies").performClick()
        composeRule.onNodeWithTag("canonical-detail-source-details").performClick()
        composeRule.runOnIdle {
            assertEquals(1, copiesClicks)
            assertEquals(1, sourceDetailsClicks)
        }

        composeRule.onNodeWithText("Reviews").performClick()
        composeRule.onNodeWithText("Native review browsing is not available yet.").assertIsDisplayed()
        composeRule.onNodeWithText("Discussions").performClick()
        composeRule.onNodeWithText("Native discussion browsing is not available yet.").assertIsDisplayed()
        composeRule.onNodeWithText("Details").performClick()
        composeRule.onNodeWithText("Fixture Studio").assertIsDisplayed()
        composeRule.onNodeWithText("Fixture Publisher").assertIsDisplayed()
        composeRule.onNodeWithText("31 Jul, 2026").assertIsDisplayed()
        composeRule.onNodeWithText("Windows, Linux").assertIsDisplayed()
        composeRule.onNodeWithText("English, French").assertIsDisplayed()
        composeRule.onNodeWithText("Minimum: 8 GB RAM").assertIsDisplayed()
        composeRule.onNodeWithText("12").assertIsDisplayed()
        composeRule.onNodeWithText("2").assertIsDisplayed()
        composeRule.onNodeWithText("Steam match").assertIsDisplayed()
        composeRule.onNodeWithText("Needs review").assertIsDisplayed()
        composeRule.onNodeWithTag("canonical-detail-fix-steam-match").performClick()
        composeRule.onNodeWithText("Resources").assertIsDisplayed()
        composeRule.onNodeWithText("Steam store").assertIsDisplayed()
        composeRule.onNodeWithText("Community hub").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, fixMatchClicks) }
    }

    @Test
    fun steamMetadataUsesScopedImageAndVideoLoaders() {
        composeRule.setContent {
            PluviaTheme {
                CanonicalGameDetailScreen(
                    state = GameDetailState.Content(
                        metadata = metadata().copy(
                            headerImageUrl = "https://shared.akamai.steamstatic.com/header.jpg",
                            screenshots = listOf(
                                "https://shared.akamai.steamstatic.com/screenshot.jpg",
                            ),
                            movies = listOf(
                                GameMovie(
                                    name = "Trailer",
                                    previewImageUrl = "https://shared.akamai.steamstatic.com/poster.jpg",
                                    streamUrl = "https://video.akamai.steamstatic.com/trailer.webm",
                                ),
                            ),
                        ),
                        stale = false,
                    ),
                    fallbackTitle = "Canonical fallback",
                    fallbackImageUrl = "",
                    steamAppId = 123456,
                    ownedSources = setOf(GameSource.STEAM),
                    compatibilityStatus = null,
                    hltbStats = null,
                    isOffline = false,
                    onBack = {},
                    onCopies = {},
                    onSourceDetails = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithTag("steam-media-gallery").assertIsDisplayed()
        composeRule.onNodeWithTag("steam-media-video").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Unmute trailer").performClick()
        composeRule.onNodeWithContentDescription("Mute trailer").assertIsDisplayed()
        composeRule.onNodeWithTag("steam-media-thumbnail:1").performClick()
        composeRule.onNodeWithTag("steam-media-thumbnail:0").performClick()
        composeRule.onNodeWithContentDescription("Mute trailer").assertIsDisplayed()
    }

    @Test
    fun constrainedHeightKeepsMediaViewportAndCarouselInsideOverview() {
        composeRule.setContent {
            PluviaTheme {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(650.dp)
                        .testTag("detail-height-host"),
                ) {
                    CanonicalGameDetailScreen(
                        state = GameDetailState.Content(
                            metadata = metadata().copy(
                                screenshots = listOf("first", "second"),
                            ),
                            stale = false,
                        ),
                        fallbackTitle = "Canonical fallback",
                        fallbackImageUrl = "",
                        steamAppId = 123456,
                        ownedSources = setOf(GameSource.STEAM),
                        compatibilityStatus = null,
                        hltbStats = null,
                        isOffline = false,
                        onBack = {},
                        onCopies = {},
                        onSourceDetails = {},
                        onRetry = {},
                    )
                }
            }
        }

        val hostBottom = composeRule.onNodeWithTag("detail-height-host")
            .fetchSemanticsNode().boundsInRoot.bottom
        val carouselBottom = composeRule.onNodeWithTag("steam-media-thumbnails")
            .fetchSemanticsNode().boundsInRoot.bottom

        assertTrue(carouselBottom <= hostBottom)
        composeRule.onNodeWithTag("steam-media-gallery-viewport").assertIsDisplayed()
        composeRule.onNodeWithTag("steam-media-thumbnails").assertIsDisplayed()
    }

    private fun metadata() = CanonicalGameMetadata(
        title = "Fixture Game",
        shortDescription = "Plain short description",
        about = "Plain about description",
        headerImageUrl = null,
        screenshots = emptyList(),
        movies = emptyList(),
        developers = listOf("Fixture Studio"),
        publishers = listOf("Fixture Publisher"),
        releaseDate = "31 Jul, 2026",
        platforms = setOf(GamePlatform.WINDOWS, GamePlatform.LINUX),
        languages = listOf("English", "French"),
        requirements = GameRequirements(
            minimum = "Minimum: 8 GB RAM",
            recommended = "Recommended: 16 GB RAM",
        ),
        features = listOf(MetadataFacet(2, "Single-player")),
        achievementCount = 12,
        dlcCount = 2,
        fetchedAtEpochMs = 1L,
    )
}
