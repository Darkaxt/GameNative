package app.gamenative.ui.screen.library

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import app.gamenative.R
import app.gamenative.library.community.DiscussionSectionState
import app.gamenative.library.community.ReviewSectionState
import app.gamenative.library.community.SteamDiscussionSummary
import app.gamenative.library.community.SteamReviewCard
import app.gamenative.library.metadata.CanonicalGameMetadata
import app.gamenative.library.metadata.GameDetailState
import app.gamenative.library.metadata.GameLanguageSupport
import app.gamenative.library.metadata.GamePlatform
import app.gamenative.library.metadata.GameRequirements
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
class CanonicalResponsiveLayoutTest {
    @get:Rule(order = 0) val timeout = TimeoutRule.seconds(30)
    @get:Rule(order = 1) val composeRule = createComposeRule()

    @Test
    fun wideDetailsFactsUseColumnsInsteadOfAContinuousTextScroll() {
        screen()
        tab("DETAILS")
        val developer = bounds("canonical-detail-fact:developer")
        val publisher = bounds("canonical-detail-fact:publisher")
        assertEquals(developer.top, publisher.top, 1f)
        assertTrue(publisher.left > developer.right)
        composeRule.onNodeWithText("Fixture Studio").assertIsDisplayed()
        composeRule.onNodeWithText("Fixture Publisher").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w420dp-h900dp")
    fun narrowDetailsFactsStackInsideTheViewport() {
        screen()
        tab("DETAILS")
        val developer = bounds("canonical-detail-fact:developer")
        val publisher = bounds("canonical-detail-fact:publisher")
        assertTrue(publisher.top >= developer.bottom)
        assertEquals(developer.left, publisher.left, 1f)
        val root = bounds("canonical-detail-screen")
        assertTrue(developer.left >= root.left && developer.right <= root.right)
        assertTrue(publisher.left >= root.left && publisher.right <= root.right)
    }

    @Test
    fun languageCapabilitiesHaveSeparateAlignedColumnsAndUnknownIsNotFalse() {
        screen()
        tab("DETAILS")
        composeRule.onNodeWithTag("canonical-detail-languages").performScrollTo()
        val interfaceCell = bounds("canonical-detail-language:0:interface")
        val audioCell = bounds("canonical-detail-language:0:audio")
        val subtitlesCell = bounds("canonical-detail-language:0:subtitles")
        assertEquals(interfaceCell.top, audioCell.top, 1f)
        assertEquals(audioCell.top, subtitlesCell.top, 1f)
        assertTrue(interfaceCell.right <= audioCell.left && audioCell.right <= subtitlesCell.left)
        val context = ApplicationProvider.getApplicationContext<Application>()
        composeRule.onNodeWithTag("canonical-detail-language:0:subtitles")
            .assertTextEquals(context.getString(R.string.canonical_detail_support_unknown))
        composeRule.onNodeWithTag("canonical-detail-language:0:audio")
            .assertTextEquals(context.getString(R.string.no))
    }

    @Test
    fun languageNamesWithoutCapabilityRowsRemainVisibleAsUnknown() {
        screen()
        tab("DETAILS")
        composeRule.onNodeWithTag("canonical-detail-languages").performScrollTo()
        composeRule.onNodeWithText("French").assertExists()
        val context = ApplicationProvider.getApplicationContext<Application>()
        composeRule.onNodeWithTag("canonical-detail-language:1:audio")
            .assertTextEquals(context.getString(R.string.canonical_detail_support_unknown))
    }

    @Test
    fun requirementsAreExpandableAndKeepTheirStateAcrossTabs() {
        screen()
        tab("DETAILS")
        composeRule.onNodeWithText("Minimum fixture specification").assertDoesNotExist()
        composeRule.onNodeWithTag("canonical-detail-requirements:minimum")
            .performScrollTo().performClick()
        composeRule.onNodeWithText("Minimum fixture specification").assertExists()
        tab("REVIEWS")
        tab("DETAILS")
        composeRule.onNodeWithTag("canonical-detail-tab:DETAILS").assertIsSelected()
        composeRule.onNodeWithText("Minimum fixture specification").assertExists()
    }

    @Test
    fun wideOverviewSeparatesReadingContentFromSummaryAndSourceStatus() {
        screen()
        val reading = bounds("canonical-detail-overview-reading")
        val summary = bounds("canonical-detail-overview-summary")
        assertEquals(reading.top, summary.top, 1f)
        assertTrue(reading.right < summary.left)
    }

    @Test
    fun wideReviewsKeepControlsBesideReadableReviewCards() {
        screen()
        tab("REVIEWS")
        val controls = bounds("steam-reviews-controls")
        val content = bounds("steam-reviews-list")
        assertTrue(controls.right < content.left)
        composeRule.onNodeWithText("Fixture review text").assertIsDisplayed()
    }

    @Test
    fun wideDiscussionsKeepControlsBesideTheNativeList() {
        screen()
        tab("DISCUSSIONS")
        val controls = bounds("steam-discussions-controls")
        val content = bounds("steam-discussions-list")
        assertTrue(controls.right < content.left)
        composeRule.onNodeWithText("Fixture discussion").assertIsDisplayed()
    }

    @Test
    fun requirementsKeepTheirOwnDisclosureWhenTheGridChangesColumnCount() {
        val fontScale = mutableStateOf(1f)
        screen(fontScale = { fontScale.value })
        tab("DETAILS")
        composeRule.onNodeWithTag("canonical-detail-requirements:minimum")
            .performScrollTo().performClick()
        composeRule.onNodeWithText("Minimum fixture specification").assertExists()
        composeRule.onNodeWithText("Recommended fixture specification").assertDoesNotExist()

        composeRule.runOnIdle { fontScale.value = 2f }

        composeRule.onNodeWithText("Minimum fixture specification").assertExists()
        composeRule.onNodeWithText("Recommended fixture specification").assertDoesNotExist()
        composeRule.onNodeWithTag("canonical-detail-requirements:minimum").performScrollTo()
        assertWithinViewport("canonical-detail-requirements:minimum")
        composeRule.onNodeWithTag("canonical-detail-requirements:minimum").performClick()
        composeRule.onNodeWithTag("canonical-detail-requirements:recommended")
            .performScrollTo().performClick()
        composeRule.runOnIdle { fontScale.value = 1f }
        composeRule.onNodeWithText("Minimum fixture specification").assertDoesNotExist()
        composeRule.onNodeWithText("Recommended fixture specification").assertExists()
        tab("REVIEWS")
        tab("DETAILS")
        composeRule.onNodeWithText("Minimum fixture specification").assertDoesNotExist()
        composeRule.onNodeWithText("Recommended fixture specification").assertExists()
    }

    @Test
    @Config(qualifiers = "w420dp-h900dp")
    fun narrowReviewsKeepInlineControlsAndLargeFontCardsInsideTheViewport() {
        screen(fontScale = { 1.6f })
        tab("REVIEWS")
        assertWithinViewport("steam-reviews-controls")
        composeRule.onNodeWithTag("steam-reviews-list")
            .performScrollToNode(hasText("Fixture review text"))
        composeRule.onNodeWithText("Fixture review text").assertIsDisplayed()
        val review = composeRule.onNodeWithText("Fixture review text").fetchSemanticsNode().boundsInRoot
        val root = bounds("canonical-detail-screen")
        assertTrue(review.left >= root.left && review.right <= root.right)
    }

    @Test
    @Config(qualifiers = "w420dp-h900dp")
    fun narrowDiscussionsKeepInlineControlsAndLargeFontThreadsInsideTheViewport() {
        screen(fontScale = { 1.6f })
        tab("DISCUSSIONS")
        assertWithinViewport("steam-discussions-controls")
        composeRule.onNodeWithTag("steam-discussions-list")
            .performScrollToNode(hasText("Fixture discussion"))
        composeRule.onNodeWithText("Fixture discussion").assertIsDisplayed()
        val thread = composeRule.onNodeWithText("Fixture discussion").fetchSemanticsNode().boundsInRoot
        val root = bounds("canonical-detail-screen")
        assertTrue(thread.left >= root.left && thread.right <= root.right)
    }

    @Test
    fun reviewScrollAnchorSurvivesLeavingAndReturningToTheTab() {
        screen(communityEntries = 40)
        tab("REVIEWS")
        val anchor = "Fixture review text 24"
        composeRule.onNodeWithTag("steam-reviews-list").performScrollToNode(hasText(anchor))
        val before = composeRule.onNodeWithText(anchor).fetchSemanticsNode().boundsInRoot.top
        tab("DETAILS")
        tab("REVIEWS")
        composeRule.onNodeWithText(anchor).assertIsDisplayed()
        val after = composeRule.onNodeWithText(anchor).fetchSemanticsNode().boundsInRoot.top
        assertEquals(before, after, 1f)
    }

    private fun assertWithinViewport(tag: String) {
        val item = bounds(tag)
        val root = bounds("canonical-detail-screen")
        assertTrue("$tag must not overflow horizontally", item.left >= root.left && item.right <= root.right)
    }

    private fun tab(name: String) {
        composeRule.onNodeWithTag("canonical-detail-tab:$name").performClick()
    }

    private fun bounds(tag: String) = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot

    private fun screen(fontScale: (() -> Float)? = null, communityEntries: Int = 1) {
        val metadata = CanonicalGameMetadata(
            title = "Fixture game", shortDescription = "Fixture short description", about = "Fixture about",
            headerImageUrl = null, screenshots = emptyList(), movies = emptyList(),
            developers = listOf("Fixture Studio"), publishers = listOf("Fixture Publisher"),
            releaseDate = "2023", platforms = setOf(GamePlatform.WINDOWS), languages = listOf("English", "French"),
            requirements = GameRequirements("Minimum fixture specification", "Recommended fixture specification"),
            features = emptyList(), achievementCount = 10, dlcCount = 2, fetchedAtEpochMs = 0L,
            languageSupport = listOf(
                GameLanguageSupport(
                    "English", interfaceSupported = true,
                    fullAudioSupported = false, subtitlesSupported = null,
                ),
            ),
        )
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale?.invoke() ?: density.fontScale),
            ) {
                PluviaTheme {
                    CanonicalGameDetailScreen(
                        state = GameDetailState.Content(metadata, stale = false),
                        fallbackTitle = metadata.title, fallbackImageUrl = "", steamAppId = 42,
                        ownedSources = emptySet(), compatibilityStatus = null, hltbStats = null, isOffline = false,
                        onBack = {}, onCopies = {}, onSourceDetails = {}, onRetry = {},
                        reviewState = ReviewSectionState.Content(
                            List(communityEntries) { index ->
                                SteamReviewCard(
                                    recommended = true,
                                    text = if (communityEntries == 1) "Fixture review text" else "Fixture review text $index",
                                    playtimeMinutes = 60,
                                    helpfulVotes = 2, funnyVotes = 0, commentCount = 0, postedAtEpochSeconds = 1L,
                                    updatedAtEpochSeconds = 1L, receivedForFree = false,
                                    earlyAccess = false, developerResponse = null,
                                )
                            },
                            canLoadMore = false,
                        ),
                        discussionState = DiscussionSectionState.Listing(
                            List(communityEntries) { index ->
                                SteamDiscussionSummary(
                                    if (communityEntries == 1) "Fixture discussion" else "Fixture discussion $index",
                                    2, null, "/app/42/discussions/0/${123 + index}/",
                                )
                            },
                            canLoadMore = false,
                        ),
                    )
                }
            }
        }
    }
}
