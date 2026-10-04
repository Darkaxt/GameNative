package app.gamenative.ui.screen.library

import android.app.Application
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onNodeWithTag
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
    fun sourceDetailsControlUsesExistingAutomaticGuardedRoute() {
        val card = card(setOf(OwnedCopyOperation.OPEN_SOURCE_DETAILS))
        val operations = mutableListOf<OwnedCopyOperation>()
        screen(card, operations)

        composeRule.onNodeWithTag("canonical-card").performClick()
        composeRule.onNodeWithTag("canonical-detail-source-details").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(OwnedCopyOperation.OPEN_SOURCE_DETAILS), operations)
        }
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
    fun uninstalledNonSteamCopyCanInvokeInstallFromCanonicalDetail() {
        val card = card(setOf(OwnedCopyOperation.INSTALL, OwnedCopyOperation.OPEN_SOURCE_DETAILS), installed = false)
        val operations = mutableListOf<OwnedCopyOperation>()
        screen(card, operations)

        composeRule.onNodeWithTag("canonical-card").performClick()
        composeRule.onNodeWithTag("canonical-detail-operation:INSTALL").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("canonical-detail-operation:PLAY").assertDoesNotExist()

        composeRule.runOnIdle { assertEquals(listOf(OwnedCopyOperation.INSTALL), operations) }
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
    fun actionBarRoutesEverySupportedOperationWithoutChoosingACopyFromPresentation() {
        val supported = OwnedCopyOperation.entries.filter { it != OwnedCopyOperation.OPEN_SOURCE_DETAILS }
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
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun screen(
        card: CanonicalLibraryCard,
        operations: MutableList<OwnedCopyOperation>,
        routeResult: (OwnedCopyOperation) -> OwnedCopyRouteResult = {
            OwnedCopyRouteResult.Unavailable(ActionFailureReason.COPY_UNAVAILABLE)
        },
    ) {
        val presentation = LibraryCard.canonical(
            key = card.key, index = 0, name = card.displayName, ownedSources = card.ownedSources,
        )
        val state = LibraryState(cards = listOf(presentation), canonicalSnapshotRevision = 1L)
        composeRule.setContent {
            val inputModeManager = LocalInputModeManager.current
            LaunchedEffect(Unit) { inputModeManager.requestInputMode(InputMode.Keyboard) }
            PluviaTheme {
                LibraryScreenContent(
                    state = state,
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
                    canonicalCard = { card.takeIf { candidate -> candidate.key == it } },
                    onRouteCanonicalAction = { key, operation, explicitKey, rememberChoice ->
                        assertEquals(card.key, key)
                        assertEquals(null, explicitKey)
                        assertEquals(false, rememberChoice)
                        operations += operation
                        routeResult(operation)
                    },
                    onUseAutomaticCopySelection = { CanonicalCopyChangeResult.INVALID_REQUEST },
                    onSeparateCanonicalCopy = { _, _ -> CanonicalCopyChangeResult.INVALID_REQUEST },
                    onResetCanonicalDecision = { _, _ -> CanonicalCopyChangeResult.INVALID_REQUEST },
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
