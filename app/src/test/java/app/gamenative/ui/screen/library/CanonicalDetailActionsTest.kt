package app.gamenative.ui.screen.library

import android.app.Application
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.canonical.CanonicalCardKey
import app.gamenative.library.canonical.CanonicalLibraryCard
import app.gamenative.library.canonical.OwnedCopyOperation
import app.gamenative.library.canonical.OwnedCopySummary
import app.gamenative.library.canonical.action.ActionFailureReason
import app.gamenative.library.canonical.action.OwnedCopyRouteResult
import app.gamenative.library.community.DiscussionSectionState
import app.gamenative.library.discovery.SteamReviewSummary
import app.gamenative.ui.model.ReviewSummaryState
import app.gamenative.library.metadata.CanonicalGameMetadata
import app.gamenative.library.metadata.GameDetailState
import app.gamenative.library.metadata.MetadataProvider
import kotlinx.serialization.json.Json
import app.gamenative.ui.data.LibraryCard
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.model.CanonicalCopyChangeResult
import app.gamenative.ui.theme.PluviaTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], qualifiers = "w1200dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
class CanonicalDetailActionsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        PrefManager.init(ApplicationProvider.getApplicationContext())
        PrefManager.libraryLayout = PaneType.LIST
    }

    @Test
    fun installedOptionsReplaceSeparateCopiesAndSourceDetailsButtons() {
        val card = card(setOf(OwnedCopyOperation.OPEN_SOURCE_DETAILS))
        val operations = mutableListOf<OwnedCopyOperation>()
        screen(card, operations)

        composeRule.onNodeWithTag("canonical-card").performClick()
        composeRule.onNodeWithTag("canonical-detail-copies").assertDoesNotExist()
        composeRule.onNodeWithTag("canonical-detail-source-details").assertDoesNotExist()
        composeRule.onNodeWithTag("canonical-detail-options").assertIsDisplayed().performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(OwnedCopyOperation.OPEN_SOURCE_DETAILS), operations)
        }
    }

    @Test
    fun readySourceOptionsKeepCanonicalPageTabAndDetailOwner() {
        app.gamenative.service.gog.GOGConstants.init(ApplicationProvider.getApplicationContext())
        val card = card(setOf(OwnedCopyOperation.OPEN_SOURCE_DETAILS))
        val item = app.gamenative.data.LibraryItem(
            appId = "GOG_42", name = "Synthetic GOG game", gameSource = GameSource.GOG,
        )
        val guard = io.mockk.mockk<app.gamenative.library.canonical.action.OwnedCopyActionGuard>()
        io.mockk.every { guard.initialLibraryItem } returns item
        var clears = 0
        screen(card, mutableListOf(), onClearDetail = { clears++ }) {
            OwnedCopyRouteResult.Ready(guard, app.gamenative.library.canonical.action.ActionSelectionPolicy.SOLE_COPY)
        }

        composeRule.onNodeWithTag("canonical-card").performClick()
        composeRule.onNodeWithTag("canonical-detail-tab:DETAILS").performClick()
        composeRule.onNodeWithTag("canonical-detail-options").performClick()

        composeRule.onNodeWithTag("canonical-detail-screen").assertExists()
        composeRule.onNodeWithTag("canonical-detail-tab:DETAILS").assertIsSelected()
        composeRule.runOnIdle { assertEquals(0, clears) }
        composeRule.onNodeWithTag("canonical-detail-options-panel").assertIsDisplayed()
        composeRule.onRoot().performKeyInput { pressKey(Key.ButtonB) }
        composeRule.onNodeWithTag("canonical-detail-options-panel").assertDoesNotExist()
        composeRule.onNodeWithTag("canonical-detail-screen").assertExists()
        composeRule.onNodeWithTag("canonical-detail-source-information").assertExists()
        composeRule.onNodeWithText("Synthetic GOG game").assertExists()
        composeRule.runOnIdle { assertEquals(0, clears) }
    }

    @Test
    fun keyboardOptionsBackRestoresTheShopWrenchFocus() {
        app.gamenative.service.gog.GOGConstants.init(ApplicationProvider.getApplicationContext())
        val card = card(setOf(OwnedCopyOperation.OPEN_SOURCE_DETAILS))
        val item = app.gamenative.data.LibraryItem(
            appId = "GOG_42", name = "Synthetic GOG game", gameSource = GameSource.GOG,
        )
        val guard = io.mockk.mockk<app.gamenative.library.canonical.action.OwnedCopyActionGuard>()
        io.mockk.every { guard.initialLibraryItem } returns item
        screen(card, mutableListOf()) {
            OwnedCopyRouteResult.Ready(guard, app.gamenative.library.canonical.action.ActionSelectionPolicy.SOLE_COPY)
        }
        composeRule.onNodeWithTag("canonical-card").onChild()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.onNodeWithTag("canonical-detail-options")
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.onNodeWithTag("canonical-detail-options-panel").assertIsDisplayed()
        composeRule.onRoot().performKeyInput { pressKey(Key.ButtonB) }
        composeRule.onNodeWithTag("canonical-detail-options-panel").assertDoesNotExist()
        composeRule.onNodeWithTag("canonical-detail-options").assertIsFocused()
        composeRule.onNodeWithTag("canonical-detail-screen").assertExists()
    }

    @Test
    fun installedNonSteamCopyCanInvokePlayFromCanonicalDetailWithoutSteamOwnership() {
        val card = card(setOf(OwnedCopyOperation.PLAY, OwnedCopyOperation.OPEN_SOURCE_DETAILS))
        val operations = mutableListOf<OwnedCopyOperation>()
        screen(card, operations)

        composeRule.onNodeWithTag("canonical-card").performClick()
        composeRule.onNodeWithTag("canonical-detail-operation:PLAY").assertIsDisplayed().performClick()

        composeRule.runOnIdle { assertEquals(listOf(OwnedCopyOperation.PLAY), operations) }
    }

    @Test
    fun installAlwaysChoosesOwnedSourceFirstAndCancelPreservesDetail() {
        val card = card(setOf(OwnedCopyOperation.INSTALL, OwnedCopyOperation.OPEN_SOURCE_DETAILS), installed = false)
        val operations = mutableListOf<OwnedCopyOperation>()
        screen(card, operations)

        composeRule.onNodeWithTag("canonical-card").performClick()
        composeRule.onNodeWithTag("canonical-detail-operation:INSTALL").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("copies-sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("canonical-detail-operation:PLAY").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(emptyList<OwnedCopyOperation>(), operations) }

        composeRule.onNodeWithTag("copies-sheet").performKeyInput { pressKey(Key.ButtonB) }
        composeRule.onNodeWithTag("copies-sheet").assertDoesNotExist()
        composeRule.onNodeWithTag("canonical-detail-screen").assertExists()
        composeRule.runOnIdle { assertEquals(emptyList<OwnedCopyOperation>(), operations) }
    }

    @Test
    fun installChoiceOnlyShowsCapableSourcesAndKeepsExplicitInstallIntent() {
        val initial = card(setOf(OwnedCopyOperation.INSTALL, OwnedCopyOperation.OPEN_SOURCE_DETAILS), installed = false)
        val installed = initial.copies.single().copy(
            key = OwnedCopyKey(AccountScope("a".repeat(64)), GameSource.AMAZON, "amzn1.adg.product.22222222-2222-2222-2222-222222222222"),
            source = GameSource.AMAZON, isInstalled = true,
            capabilities = setOf(OwnedCopyOperation.PLAY, OwnedCopyOperation.OPEN_SOURCE_DETAILS),
        )
        val card = initial.copy(copies = initial.copies + installed, ownedSources = setOf(GameSource.GOG, GameSource.AMAZON))
        val operations = mutableListOf<OwnedCopyOperation>()
        screen(card, operations, expectedExplicitKey = initial.copies.single().key)
        composeRule.onNodeWithTag("canonical-card").performClick()
        composeRule.onNodeWithTag("canonical-detail-operation:INSTALL").performClick()
        composeRule.onNodeWithTag("copy-row:AMAZON").assertDoesNotExist()
        composeRule.onNodeWithTag("copy-operation:GOG:OPEN_SOURCE_DETAILS").assertDoesNotExist()
        // Modal pointer injection is not reliable in this host runner; signed-device
        // acceptance owns touch. This owner checks the enabled action's exact intent.
        composeRule.onNodeWithTag("copy-operation:GOG:INSTALL")
            .assertIsDisplayed().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.runOnIdle { assertEquals(listOf(OwnedCopyOperation.INSTALL), operations) }
        composeRule.onNodeWithTag("canonical-detail-screen").assertExists()
    }

    @Test
    fun installChoiceRoutesItsEnabledAccessibilityActionToExactCopy() {
        val card = card(setOf(OwnedCopyOperation.INSTALL, OwnedCopyOperation.OPEN_SOURCE_DETAILS), installed = false)
        val operations = mutableListOf<OwnedCopyOperation>()
        screen(card, operations, expectedExplicitKey = card.copies.single().key)
        composeRule.onNodeWithTag("canonical-card").performClick()
        composeRule.onNodeWithTag("canonical-detail-operation:INSTALL").performClick()
        composeRule.onNodeWithTag("copy-operation:GOG:INSTALL")
            .assertIsDisplayed().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.runOnIdle { assertEquals(listOf(OwnedCopyOperation.INSTALL), operations) }
        composeRule.onNodeWithTag("canonical-detail-screen").assertExists()
    }

    @Test
    fun gamepadBackClosesCanonicalDetailInsteadOfDeferringToAbsentSourceScreen() {
        val card = card(setOf(OwnedCopyOperation.OPEN_SOURCE_DETAILS))
        screen(card, mutableListOf())

        composeRule.onNodeWithTag("canonical-card").performClick()
        composeRule.onNodeWithTag("canonical-detail-screen").assertIsDisplayed()
        composeRule.onRoot().performKeyInput { pressKey(Key.ButtonB) }

        composeRule.onNodeWithTag("canonical-detail-screen").assertDoesNotExist()
        composeRule.onNodeWithTag("canonical-card").assertIsDisplayed()
    }

    @Test
    fun keyboardDetailEntryFocusesItsBackAction() {
        screen(card(setOf(OwnedCopyOperation.OPEN_SOURCE_DETAILS)), mutableListOf())

        composeRule.onNodeWithTag("canonical-card").onChild().performSemanticsAction(SemanticsActions.OnClick) { it() }

        composeRule.onNodeWithContentDescription("Back").assertIsFocused()
    }

    @Test
    fun actionBarRoutesImmediateOperationsWithoutChoosingACopyFromPresentation() {
        val supported = listOf(OwnedCopyOperation.PLAY, OwnedCopyOperation.UPDATE,
            OwnedCopyOperation.PAUSE_RESUME_DOWNLOAD, OwnedCopyOperation.CANCEL_DOWNLOAD)
        val card = card(supported.toSet())
        val operations = mutableListOf<OwnedCopyOperation>()
        screen(card, operations)

        composeRule.onNodeWithTag("canonical-card").performClick()
        supported.forEach { operation ->
            composeRule.onNodeWithTag("canonical-detail-operation:${operation.name}")
                .assertIsDisplayed().performClick()
        }

        composeRule.runOnIdle { assertEquals(supported, operations) }
    }

    @Test
    fun acceptedSteamPresentationDoesNotInventActionsForUnavailableOwnedCopy() {
        val initial = card(setOf(OwnedCopyOperation.PLAY, OwnedCopyOperation.INSTALL))
        val card = initial.copy(
            steamAppId = 10,
            copies = initial.copies.map { it.copy(unavailableReason = app.gamenative.library.canonical.CopyUnavailableReason.SOURCE_READ_FAILED) },
        )
        val operations = mutableListOf<OwnedCopyOperation>()
        screen(card, operations)

        composeRule.onNodeWithTag("canonical-card").performClick()
        OwnedCopyOperation.entries.forEach { operation ->
            composeRule.onNodeWithTag("canonical-detail-operation:${operation.name}").assertDoesNotExist()
        }
        composeRule.runOnIdle { assertEquals(emptyList<OwnedCopyOperation>(), operations) }
    }

    @Test
    fun automaticActionThatNeedsACopyOpensExistingChooserWithoutClosingCanonicalDetail() {
        val initial = card(setOf(OwnedCopyOperation.PLAY, OwnedCopyOperation.OPEN_SOURCE_DETAILS))
        val second = initial.copies.single().copy(
            key = OwnedCopyKey(AccountScope("a".repeat(64)), GameSource.AMAZON, "amzn1.adg.product.22222222-2222-2222-2222-222222222222"),
            source = GameSource.AMAZON,
        )
        val card = initial.copy(copies = initial.copies + second, ownedSources = setOf(GameSource.GOG, GameSource.AMAZON))
        val operations = mutableListOf<OwnedCopyOperation>()
        screen(card, operations) { OwnedCopyRouteResult.NeedsChooser(card.copies.map(OwnedCopySummary::key)) }

        composeRule.onNodeWithTag("canonical-card").performClick()
        composeRule.onNodeWithTag("canonical-detail-operation:PLAY").performClick()

        composeRule.onNodeWithTag("copies-sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("canonical-detail-screen").assertExists()
        composeRule.runOnIdle { assertEquals(listOf(OwnedCopyOperation.PLAY), operations) }
        composeRule.onNodeWithTag("copies-sheet").performKeyInput { pressKey(Key.ButtonB) }
        composeRule.onNodeWithTag("copies-sheet").assertDoesNotExist()
        composeRule.onNodeWithTag("canonical-detail-screen").assertExists()
    }

    @Test
    fun detailsShowGenresAndDistinctMinimumRecommendedRequirements() {
        screen(card(setOf(OwnedCopyOperation.OPEN_SOURCE_DETAILS)), mutableListOf(), detailState = detailState())
        composeRule.onNodeWithTag("canonical-card").performClick()
        composeRule.onNodeWithTag("canonical-detail-tab:DETAILS").performClick()

        composeRule.onNodeWithText("Genres").assertExists()
        composeRule.onNodeWithText("Strategy").assertExists()
        composeRule.onNodeWithText("Minimum requirements").assertExists()
        composeRule.onNodeWithText("Recommended requirements").assertExists()
    }

    @Test
    fun overviewSeparatesOwnedGogCopyFromSteamMetadataProvenance() {
        screen(card(setOf(OwnedCopyOperation.OPEN_SOURCE_DETAILS)), mutableListOf(), detailState = detailState())
        composeRule.onNodeWithTag("canonical-card").performClick()

        composeRule.onNodeWithText("Metadata source").assertExists()
        composeRule.onNodeWithText("Steam Store").assertExists()
        composeRule.onNodeWithTag("canonical-detail-ownership").assertExists()
    }

    @Test
    fun epicFallbackDisplaysTruthfulProviderInsteadOfSteamProvenance() {
        screen(card(setOf(OwnedCopyOperation.OPEN_SOURCE_DETAILS)), mutableListOf(),
            detailState = detailState().copy(provider = MetadataProvider.EPIC_CMS))
        composeRule.onNodeWithTag("canonical-card").performClick()

        composeRule.onNodeWithText("Epic Games Store").assertExists()
        composeRule.onNodeWithText("Steam Store").assertDoesNotExist()
    }

    @Test
    fun detailsExposeReadOnlyStorePriceRatingsAndExternalLinks() {
        screen(card(setOf(OwnedCopyOperation.OPEN_SOURCE_DETAILS)), mutableListOf(), detailState = detailState())
        composeRule.onNodeWithTag("canonical-card").performClick()
        composeRule.onNodeWithTag("canonical-detail-tab:DETAILS").performClick()

        composeRule.onNodeWithTag("canonical-detail-store-price").assertExists()
        composeRule.onNodeWithText("Fixture Deluxe Edition").assertExists()
        composeRule.onNodeWithText("Fantasy violence").assertExists()
        composeRule.onNodeWithText("Website").assertExists()
        composeRule.onNodeWithText("Support").assertExists()
        composeRule.onNodeWithText("Manual").assertExists()
        composeRule.onNodeWithTag("canonical-detail-provenance").assertExists()
    }

    @Test
    fun disposingLibraryHostClearsCanonicalDetailOwnerExactlyOnce() {
        val mounted = mutableStateOf(true)
        var clears = 0
        screen(card(setOf(OwnedCopyOperation.OPEN_SOURCE_DETAILS)), mutableListOf(),
            mounted = mounted, onClearDetail = { clears++ })
        composeRule.onNodeWithTag("canonical-card").performClick()
        composeRule.runOnIdle { mounted.value = false }
        composeRule.runOnIdle { assertEquals(1, clears) }
    }

    @Test
    fun matchedOverviewExposesItsAggregateSteamReviewSummary() {
        val card = card(setOf(OwnedCopyOperation.OPEN_SOURCE_DETAILS)).copy(steamAppId = 480, steamReviewCount = 100)
        screen(card, mutableListOf(), detailState = detailState())
        composeRule.onNodeWithTag("canonical-card").performClick()
        composeRule.onNodeWithTag("canonical-detail-review-summary").assertExists()
    }

    @Test
    fun backClosesDetailAndClearsItsOwnerOnlyOnce() {
        var clears = 0
        screen(card(setOf(OwnedCopyOperation.OPEN_SOURCE_DETAILS)), mutableListOf(), onClearDetail = { clears++ })
        composeRule.onNodeWithTag("canonical-card").performClick()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.runOnIdle { assertEquals(1, clears) }
    }

    @Test
    fun retainedDiscussionThreadDoesNotHijackBackOnAnotherTab() {
        var threadCloses = 0
        screen(card(setOf(OwnedCopyOperation.OPEN_SOURCE_DETAILS)).copy(steamAppId = 480), mutableListOf(),
            discussionState = DiscussionSectionState.Thread("Fixture thread", emptyList(), "/app/480/discussions/0/1/", false),
            onCloseThread = { threadCloses++; true })
        composeRule.onNodeWithTag("canonical-card").performClick()
        composeRule.onNodeWithTag("canonical-detail-tab:DETAILS").performClick()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithTag("canonical-detail-screen").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, threadCloses) }
    }

    @Test
    fun overviewSummaryRemainsVisibleWhenStoreMetadataIsUnavailable() {
        screen(card(setOf(OwnedCopyOperation.OPEN_SOURCE_DETAILS)).copy(steamAppId = 480, steamReviewCount = 100),
            mutableListOf(), detailState = GameDetailState.Unavailable(null))
        composeRule.onNodeWithTag("canonical-card").performClick()
        composeRule.onNodeWithTag("canonical-detail-review-summary").assertExists()
        composeRule.onNodeWithText("100 reviews").assertExists()
    }

    @Test
    fun visibleDiscussionGamepadBackReturnsToListThenClosesDetail() {
        val discussion = mutableStateOf<DiscussionSectionState>(
            DiscussionSectionState.Thread("Fixture thread", emptyList(), "/app/480/discussions/0/1/", false))
        var closes = 0
        screen(card(setOf(OwnedCopyOperation.OPEN_SOURCE_DETAILS)).copy(steamAppId = 480), mutableListOf(),
            liveDiscussionState = discussion, onCloseThread = {
                closes++
                discussion.value = DiscussionSectionState.Listing(emptyList(), false)
                true
            })
        composeRule.onNodeWithTag("canonical-card").performClick()
        composeRule.onNodeWithTag("canonical-detail-tab:DISCUSSIONS").performClick()
        composeRule.onRoot().performKeyInput { pressKey(Key.ButtonB) }
        composeRule.onNodeWithTag("canonical-detail-screen").assertExists()
        composeRule.runOnIdle { assertEquals(1, closes) }
        composeRule.onRoot().performKeyInput { pressKey(Key.ButtonB) }
        composeRule.onNodeWithTag("canonical-detail-screen").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, closes) }
    }

    @Test
    fun overviewDisplaysPublicRatingAndCountWithoutOwnedSteamCopy() {
        screen(card(setOf(OwnedCopyOperation.OPEN_SOURCE_DETAILS)).copy(steamAppId = 480), mutableListOf(),
            detailState = detailState(), reviewSummaryState = ReviewSummaryState.Content(
                SteamReviewSummary(100, 90, 10, 8, "Very Positive"), 1234))
        composeRule.onNodeWithTag("canonical-card").performClick()
        composeRule.onNodeWithText("Very Positive").assertExists()
        composeRule.onNodeWithText("100 reviews").assertExists()
        composeRule.onNodeWithText("90% positive").assertExists()
    }

    @Test
    fun mergingAnOpenNonAnchorEditionReloadsTheFamilyOwnerWithoutLosingItsTab() {
        val deluxe = card(setOf(OwnedCopyOperation.PLAY)).copy(displayName = "Fixture Game Deluxe", steamAppId = 43)
        val family = transitionedFamily(deluxe)
        val live = mutableStateOf(listOf(deluxe))
        val opened = mutableListOf<CanonicalGameId>()
        val operations = mutableListOf<OwnedCopyOperation>()
        var clears = 0
        screen(deluxe, operations, liveCanonicalCards = live, onOpenDetail = { opened += it }, onClearDetail = { clears++ })
        composeRule.onNodeWithTag("canonical-card").performClick()
        composeRule.onNodeWithTag("canonical-detail-tab:DETAILS").performClick()
        composeRule.runOnIdle { live.value = listOf(family) }
        composeRule.onNodeWithTag("canonical-detail-screen").assertExists()
        composeRule.onNodeWithTag("canonical-detail-tab:DETAILS").assertIsSelected()
        composeRule.runOnIdle {
            assertEquals(listOf(deluxe.canonicalId, family.canonicalId), opened)
            assertEquals(0, clears)
            assertEquals(emptyList<OwnedCopyOperation>(), operations)
        }
    }

    @Test
    fun dissolvingAnOpenFamilyReloadsTheExactOriginalEditionAndBackClearsOnce() {
        val deluxe = card(setOf(OwnedCopyOperation.PLAY)).copy(displayName = "Fixture Game Deluxe", steamAppId = 43)
        val family = transitionedFamily(deluxe)
        val live = mutableStateOf(listOf(deluxe))
        val opened = mutableListOf<CanonicalGameId>()
        var clears = 0
        screen(deluxe, mutableListOf(), liveCanonicalCards = live, onOpenDetail = { opened += it }, onClearDetail = { clears++ })
        composeRule.onNodeWithTag("canonical-card").performClick()
        composeRule.onNodeWithTag("canonical-detail-tab:DETAILS").performClick()
        composeRule.runOnIdle { live.value = listOf(family) }
        composeRule.onNodeWithTag("canonical-detail-screen").assertExists()
        composeRule.runOnIdle { live.value = listOf(deluxe) }
        composeRule.onNodeWithTag("canonical-detail-tab:DETAILS").assertIsSelected()
        composeRule.runOnIdle { assertEquals(listOf(deluxe.canonicalId, family.canonicalId, deluxe.canonicalId), opened) }
        composeRule.onRoot().performKeyInput { pressKey(Key.ButtonB) }
        composeRule.onNodeWithTag("canonical-detail-screen").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, clears) }
    }

    @Test
    fun familySeparationFromInstallChooserUsesCapturedMemberCallbackAndKeepsDetailTab() {
        val deluxe = card(setOf(OwnedCopyOperation.INSTALL), installed = false).copy(steamAppId = 43)
        val family = transitionedFamily(deluxe)
        val calls = mutableListOf<Triple<CanonicalLibraryCard, OwnedCopyKey, Boolean>>()
        var legacyCalls = 0
        screen(family, mutableListOf(), onSeparateLegacy = { _, _ -> legacyCalls++; CanonicalCopyChangeResult.SUCCESS },
            onFamilyGrouping = { captured, key, suppressed ->
                calls += Triple(captured, key, suppressed)
                CanonicalCopyChangeResult.SUCCESS
            })
        composeRule.onNodeWithTag("canonical-card").performClick()
        composeRule.onNodeWithTag("canonical-detail-tab:DETAILS").performClick()
        composeRule.onNodeWithTag("canonical-detail-operation:INSTALL").performClick()
        composeRule.onAllNodesWithTag("separate-family-edition")[1].performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.onNodeWithTag("confirm-copy-separation").performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.runOnIdle {
            assertEquals(listOf(Triple(family, deluxe.copies.single().key, true)), calls)
            assertEquals(0, legacyCalls)
        }
        composeRule.onNodeWithTag("copies-sheet").assertDoesNotExist()
        composeRule.onNodeWithTag("canonical-detail-tab:DETAILS").assertIsSelected()
    }

    @Test
    fun changedFamilyConfirmationInActualLibraryScreenCallsNeitherMutationBoundary() {
        val family = transitionedFamily(card(setOf(OwnedCopyOperation.INSTALL), installed = false).copy(steamAppId = 43))
        val live = mutableStateOf(listOf(family))
        var calls = 0
        screen(family, mutableListOf(), liveCanonicalCards = live,
            onSeparateLegacy = { _, _ -> calls++; CanonicalCopyChangeResult.SUCCESS },
            onFamilyGrouping = { _, _, _ -> calls++; CanonicalCopyChangeResult.SUCCESS })
        composeRule.onNodeWithTag("canonical-card").performClick()
        composeRule.onNodeWithTag("canonical-detail-operation:INSTALL").performClick()
        composeRule.onAllNodesWithTag("separate-family-edition")[0].performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.runOnIdle { live.value = listOf(family.copy(copies = family.copies.take(1),
            copyCanonicalIds = mapOf(family.copies.first().key to family.canonicalId),
            memberSteamAppIds = mapOf(family.canonicalId to 42), memberPreferences = mapOf(family.canonicalId to null))) }
        composeRule.onNodeWithTag("confirm-copy-separation").performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.runOnIdle { assertEquals(0, calls) }
        composeRule.onNodeWithTag("copies-sheet").assertExists()
        composeRule.onNodeWithText(ApplicationProvider.getApplicationContext<Application>().getString(app.gamenative.R.string.canonical_copy_state_changed)).assertExists()
        composeRule.onNodeWithTag("canonical-detail-screen").assertExists()
    }

    @Test
    fun rawEditionGroupingResetInActualLibraryScreenNeverResetsSteamMatch() {
        val family = transitionedFamily(card(setOf(OwnedCopyOperation.INSTALL), installed = false).copy(steamAppId = 43))
        val raw = family.copy(copies = family.copies.take(1), copyCanonicalIds = mapOf(family.copies.first().key to family.canonicalId),
            memberSteamAppIds = mapOf(family.canonicalId to 42), memberPreferences = mapOf(family.canonicalId to null), familyGroupingSuppressed = true)
        val calls = mutableListOf<Triple<CanonicalLibraryCard, OwnedCopyKey, Boolean>>()
        var matchResets = 0
        screen(raw, mutableListOf(), onResetLegacy = { _, _ -> matchResets++; CanonicalCopyChangeResult.SUCCESS },
            onFamilyGrouping = { captured, key, suppressed -> calls += Triple(captured, key, suppressed); CanonicalCopyChangeResult.SUCCESS })
        composeRule.onNodeWithTag("canonical-card").performClick()
        composeRule.onNodeWithTag("canonical-detail-operation:INSTALL").performClick()
        composeRule.onNodeWithTag("reset-family-grouping").performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.runOnIdle { assertEquals(listOf(Triple(raw, raw.copies.single().key, false)), calls); assertEquals(0, matchResets) }
        composeRule.onNodeWithTag("copies-sheet").assertDoesNotExist()
        composeRule.onNodeWithTag("canonical-detail-screen").assertExists()
    }

    private fun transitionedFamily(deluxe: CanonicalLibraryCard): CanonicalLibraryCard {
        val id = CanonicalGameId.parse("22222222-2222-2222-2222-222222222222")
        val copy = deluxe.copies.single().copy(key = deluxe.copies.single().key.copy(stableSourceId = "43"), nativeTitle = "Fixture Game")
        return deluxe.copy(key = CanonicalCardKey.Grouped(id), canonicalId = id, displayName = "Fixture Game", steamAppId = 42,
            copies = listOf(copy, deluxe.copies.single()),
            copyCanonicalIds = mapOf(copy.key to id, deluxe.copies.single().key to deluxe.canonicalId),
            memberSteamAppIds = mapOf(id to 42, deluxe.canonicalId to 43))
    }

    private fun detailState(): GameDetailState.Content {
        val metadata = Json { ignoreUnknownKeys = true }.decodeFromString<CanonicalGameMetadata>("""
            {
              "title":"Fixture Game", "shortDescription":"Public description", "about":null,
              "headerImageUrl":null, "screenshots":[], "movies":[], "developers":["Fixture Studio"],
              "publishers":[], "releaseDate":"2020", "platforms":["WINDOWS"], "languages":["English"],
              "requirements":{"minimum":"8 GB RAM","recommended":"16 GB RAM"},
              "genres":[{"id":2,"label":"Strategy"}], "features":[], "achievementCount":12,
              "dlcCount":2, "fetchedAtEpochMs":1234,
              "storePrice":{"currency":"GBP","country":"GB","initialMinor":2499,"finalMinor":1249,"discountPercent":50},
              "storePackages":[{"packageId":123,"label":"Fixture Deluxe Edition","finalMinor":2499}],
              "contentRatings":{"requiredAge":18,"criticScore":86,"descriptorIds":[2,5],"notes":"Fantasy violence"},
              "storeLinks":{"website":"https://studio.example/game","support":"https://studio.example/support","manual":"https://store.steampowered.com/manual/424242"}
            }
        """.trimIndent())
        return GameDetailState.Content(metadata, stale = false)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun screen(
        card: CanonicalLibraryCard,
        operations: MutableList<OwnedCopyOperation>,
        detailState: GameDetailState = GameDetailState.Loading,
        mounted: State<Boolean>? = null,
        onClearDetail: () -> Unit = {},
        discussionState: DiscussionSectionState = DiscussionSectionState.Idle,
        liveDiscussionState: State<DiscussionSectionState>? = null,
        reviewSummaryState: ReviewSummaryState = ReviewSummaryState.Idle,
        onCloseThread: () -> Boolean = { false },
        expectedExplicitKey: OwnedCopyKey? = null,
        liveCanonicalCards: State<List<CanonicalLibraryCard>>? = null,
        onOpenDetail: (CanonicalGameId) -> Unit = {},
        onFamilyGrouping: suspend (CanonicalLibraryCard, OwnedCopyKey, Boolean) -> CanonicalCopyChangeResult =
            { _, _, _ -> CanonicalCopyChangeResult.INVALID_REQUEST },
        onSeparateLegacy: suspend (CanonicalCardKey, OwnedCopyKey) -> CanonicalCopyChangeResult =
            { _, _ -> CanonicalCopyChangeResult.INVALID_REQUEST },
        onResetLegacy: suspend (CanonicalCardKey, OwnedCopyKey) -> CanonicalCopyChangeResult =
            { _, _ -> CanonicalCopyChangeResult.INVALID_REQUEST },
        routeResult: (OwnedCopyOperation) -> OwnedCopyRouteResult = {
            OwnedCopyRouteResult.Unavailable(ActionFailureReason.COPY_UNAVAILABLE)
        },
    ) {
        val presentation = LibraryCard.canonical(
            key = card.key, index = 0, name = card.displayName, ownedSources = card.ownedSources,
        )
        val state = LibraryState(cards = listOf(presentation), canonicalSnapshotRevision = 1L)
        composeRule.setContent {
            val currentCards = liveCanonicalCards?.value ?: listOf(card)
            val currentState = if (liveCanonicalCards == null) state else state.copy(
                cards = currentCards.mapIndexed { index, current -> LibraryCard.canonical(
                    key = current.key, index = index, name = current.displayName, ownedSources = current.ownedSources) },
                canonicalSnapshotRevision = if (currentCards == listOf(card)) 1L else 2L,
            )
            val inputModeManager = LocalInputModeManager.current
            LaunchedEffect(Unit) { inputModeManager.requestInputMode(InputMode.Keyboard) }
            if (mounted?.value == false) return@setContent
            PluviaTheme {
                LibraryScreenContent(
                    state = currentState,
                    listState = rememberLazyGridState(),
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    onFilterChanged = {},
                    onPageChange = {},
                    onModalBottomSheet = {},
                    onIsSearching = {},
                    onSearchQuery = {},
                    onClickPlay = { _, _ -> },
                    onTestGraphics = {},
                    onPlayWithDiagnostics = {},
                    onAiDebugRun = {},
                    onRefresh = {},
                    onNavigateRoute = {},
                    onLogout = {},
                    onGoOnline = {},
                    onSourceToggle = {},
                    onAddCustomGameFolder = {},
                    onSortOptionChanged = {},
                    onSteamCollectionToggle = {},
                    onClearSteamCollections = {},
                    onOptionsPanelToggle = {},
                    onTabChanged = {},
                    onPreviousTab = {},
                    onNextTab = {},
                    gameDetailState = detailState,
                    onClearCanonicalDetail = onClearDetail,
                    discussionState = liveDiscussionState?.value ?: discussionState,
                    reviewSummaryState = reviewSummaryState,
                    onCloseDiscussionThread = onCloseThread,
                    onOpenCanonicalDetail = onOpenDetail,
                    canonicalCard = { requested -> currentCards.singleOrNull { candidate ->
                        candidate.key == requested || (requested is CanonicalCardKey.Grouped && requested.canonicalId in candidate.memberSteamAppIds)
                    } },
                    onRouteCanonicalAction = { key, operation, explicitKey, rememberChoice ->
                        assertEquals(card.key, key)
                        assertEquals(expectedExplicitKey, explicitKey)
                        assertEquals(false, rememberChoice)
                        operations += operation
                        routeResult(operation)
                    },
                    onUseAutomaticCopySelection = { CanonicalCopyChangeResult.INVALID_REQUEST },
                    onSeparateCanonicalCopy = onSeparateLegacy,
                    onResetCanonicalDecision = onResetLegacy,
                    onChangeFamilyGrouping = onFamilyGrouping,
                )
            }
        }
    }

    private fun card(capabilities: Set<OwnedCopyOperation>, installed: Boolean = true): CanonicalLibraryCard {
        val id = CanonicalGameId.parse("11111111-1111-1111-1111-111111111111")
        val key = OwnedCopyKey(AccountScope("a".repeat(64)), GameSource.GOG, "42")
        return CanonicalLibraryCard(
            key = CanonicalCardKey.Grouped(id), canonicalId = id, displayName = "Synthetic owned game",
            appType = CanonicalAppType.GAME, iconUrl = "", capsuleImageUrl = "", headerImageUrl = "",
            heroImageUrl = "", gridHeroImageScale = 1f, aliases = emptySet(),
            ownedSources = setOf(GameSource.GOG), preferredCopy = null, steamCollectionAppIds = emptySet(),
            isShared = false,
            copies = listOf(OwnedCopySummary(
                key = key, source = key.source, nativeTitle = "Synthetic GOG game", installPath = null,
                installedSizeBytes = null, branchOrVersion = null, isInstalled = installed,
                isDownloading = false, hasPartialDownload = false, updateAvailable = false, isShared = false,
                lastPlayedEpochMs = null, playtimeMinutes = null, capabilities = capabilities,
                unavailableReason = null, canSeparateMatch = true, matchMethod = MatchMethod.EXACT_METADATA,
                confidence = MatchConfidence.HIGH, decisionSource = MatchDecisionSource.AUTOMATIC,
                decisionCandidateSteamAppId = null, decisionResolverVersion = 4, decisionRevision = 1L,
            )),
        )
    }
}
