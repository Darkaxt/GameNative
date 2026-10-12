package app.gamenative.ui.screen.library

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import app.gamenative.R
import app.gamenative.library.community.ReviewSectionState
import app.gamenative.library.community.SteamReviewCard
import app.gamenative.library.community.SteamReviewComment
import app.gamenative.library.community.SteamReviewCommentState
import app.gamenative.library.metadata.GameDetailState
import app.gamenative.ui.theme.PluviaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.junit.rules.TimeoutRule

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], qualifiers = "w1200dp-h900dp")
@LooperMode(LooperMode.Mode.PAUSED)
class CanonicalReviewCommentsTest {
    @get:Rule(order = 0) val timeout = TimeoutRule.seconds(30)
    @get:Rule(order = 1) val composeRule = createComposeRule()

    @Test
    fun actualReviewsTabOpensNativeCommentsAndBackRestoresList() {
        val comments = mutableStateOf<SteamReviewCommentState>(SteamReviewCommentState.Idle)
        var exits = 0
        var selections = 0
        screen(
            comments = { comments.value },
            onOpen = { id -> assertEquals("99", id); selections++; comments.value = content() },
            onClose = { comments.value = SteamReviewCommentState.Idle; true },
            onBack = { exits++ },
        )
        composeRule.onNodeWithTag("canonical-detail-tab:REVIEWS").performClick()
        composeRule.onNodeWithTag("steam-review-comments:99").performClick()
        composeRule.onNodeWithText("Public comment").assertIsDisplayed()
        assertEquals(1, selections)
        composeRule.onNodeWithContentDescription(ApplicationProvider.getApplicationContext<Application>().getString(R.string.back)).performClick()
        assertEquals(0, exits)
        composeRule.onNodeWithText("Public review").assertIsDisplayed()
        composeRule.onNodeWithTag("steam-review-comments-list").assertDoesNotExist()
    }

    @Test
    fun headerBackAlsoClosesLoadingEmptyAndUnavailableReaders() {
        val comments = mutableStateOf<SteamReviewCommentState>(SteamReviewCommentState.Loading("99"))
        var closes = 0
        var exits = 0
        screen(comments = { comments.value }, onClose = { closes++; comments.value = SteamReviewCommentState.Idle; true }, onBack = { exits++ })
        composeRule.onNodeWithTag("canonical-detail-tab:REVIEWS").performClick()
        for (state in listOf(SteamReviewCommentState.Loading("99"), SteamReviewCommentState.Empty("99"), SteamReviewCommentState.Unavailable("99"))) {
            composeRule.runOnIdle { comments.value = state }
            composeRule.onNodeWithContentDescription(ApplicationProvider.getApplicationContext<Application>().getString(R.string.back)).performClick()
        }
        assertEquals(3, closes)
        assertEquals(0, exits)
    }

    @Test
    @Config(qualifiers = "w420dp-h900dp")
    fun narrowLargeFontCommentsStayInsideActualFourTabViewport() {
        screen(comments = { content() }, fontScale = 1.6f)
        composeRule.onNodeWithTag("canonical-detail-tab:REVIEWS").performClick()
        composeRule.onNodeWithTag("steam-review-comments-list").performScrollToNode(hasText("Public comment"))
        composeRule.onNodeWithText("Public comment").assertIsDisplayed()
        val body = composeRule.onNodeWithText("Public comment").fetchSemanticsNode().boundsInRoot
        val root = composeRule.onNodeWithTag("canonical-detail-screen").fetchSemanticsNode().boundsInRoot
        assertTrue(body.left >= root.left && body.right <= root.right)
        for (tab in listOf("OVERVIEW", "REVIEWS", "DISCUSSIONS", "DETAILS")) {
            composeRule.onNodeWithTag("canonical-detail-tab:$tab").assertExists()
        }
    }

    private fun content() = SteamReviewCommentState.Content("99", listOf(SteamReviewComment("100", "Public comment")), canLoadMore = false)

    private fun screen(
        comments: () -> SteamReviewCommentState,
        onOpen: (String) -> Unit = {},
        onClose: () -> Boolean = { false },
        onBack: () -> Unit = {},
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                PluviaTheme {
                    CanonicalGameDetailScreen(
                        state = GameDetailState.Unavailable(null), fallbackTitle = "Fixture game", fallbackImageUrl = "",
                        steamAppId = 42, ownedSources = emptySet(), compatibilityStatus = null, hltbStats = null, isOffline = false,
                        onBack = onBack, onCopies = {}, onSourceDetails = {}, onRetry = {},
                        reviewState = ReviewSectionState.Content(listOf(SteamReviewCard(true, "Public review", null, 0, 0, 1, 0, 0, false, false, null, "99")), false),
                        reviewCommentState = comments(), onOpenReviewComments = onOpen, onCloseReviewComments = onClose,
                    )
                }
            }
        }
    }
}
