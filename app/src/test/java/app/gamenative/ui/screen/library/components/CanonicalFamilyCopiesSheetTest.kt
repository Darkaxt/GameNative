package app.gamenative.ui.screen.library.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.CanonicalGamePreferenceEntity
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.canonical.CanonicalCardKey
import app.gamenative.library.canonical.CanonicalLibraryCard
import app.gamenative.library.canonical.OwnedCopyOperation
import app.gamenative.library.canonical.OwnedCopySummary
import app.gamenative.ui.theme.PluviaTheme
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.junit.rules.TimeoutRule

@OptIn(ExperimentalMaterial3Api::class, ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [29], qualifiers = "w1000dp-h1200dp")
@LooperMode(LooperMode.Mode.PAUSED)
class CanonicalFamilyCopiesSheetTest {
    @get:Rule val composeRule = createComposeRule()
    @get:Rule val timeout = TimeoutRule.seconds(30)
    private val first = copy(1)
    private val second = copy(2)

    @Before
    fun setUp() = PrefManager.init(ApplicationProvider.getApplicationContext())

    @Test
    fun sameStoreInstallRowsRetainEditionLabelsAndExplicitSecondCopyIntent() {
        val calls = mutableListOf<Pair<OwnedCopyKey, OwnedCopyOperation>>()
        sheet(mutableStateOf(family()), OwnedCopyOperation.INSTALL, onOperation = { copy, operation, _ -> calls += copy.key to operation })
        composeRule.onNodeWithText("Fixture Game Deluxe").assertExists()
        composeRule.onNodeWithText("Fixture Game", substring = false).assertExists()
        composeRule.onAllNodesWithTag("copy-row:GOG").assertCountEquals(2)
        composeRule.onAllNodesWithTag("copy-operation:GOG:PLAY").assertCountEquals(0)
        composeRule.onAllNodesWithTag("copy-operation:GOG:INSTALL")[1].performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.runOnIdle { assertEquals(listOf(second.key to OwnedCopyOperation.INSTALL), calls) }
    }

    @Test
    fun runSheetExcludesAnUninstalledEditionEvenWhenItsStaleSummaryAdvertisesPlay() {
        val card = family().copy(copies = listOf(first.copy(isInstalled = true, capabilities = setOf(OwnedCopyOperation.PLAY)),
            second.copy(isInstalled = false, capabilities = setOf(OwnedCopyOperation.PLAY))))
        sheet(mutableStateOf(card), OwnedCopyOperation.PLAY)
        composeRule.onAllNodesWithTag("copy-row:GOG").assertCountEquals(1)
        composeRule.onNodeWithText("Fixture Game Deluxe").assertDoesNotExist()
    }

    @Test
    fun nativeSteamMemberExposesFamilySeparationWithoutSteamMatchMutation() {
        val steam = first.copy(key = OwnedCopyKey(first.key.accountScope, GameSource.STEAM, "42"), source = GameSource.STEAM)
        val card = family().copy(copies = listOf(steam, second), ownedSources = setOf(GameSource.STEAM, GameSource.GOG),
            copyCanonicalIds = mapOf(steam.key to id(1), second.key to id(2)))
        sheet(mutableStateOf(card))
        composeRule.onAllNodesWithTag("separate-family-edition").assertCountEquals(2)
        composeRule.onAllNodesWithTag("reset-match-decision").assertCountEquals(0)
    }

    @Test
    fun suppressedRawEditionOffersGroupingResetNotSteamMatchReset() {
        val raw = family().copy(key = CanonicalCardKey.Grouped(id(2)), canonicalId = id(2), steamAppId = 43,
            copies = listOf(second), copyCanonicalIds = mapOf(second.key to id(2)), memberSteamAppIds = mapOf(id(2) to 43),
            memberPreferences = mapOf(id(2) to null), familyGroupingSuppressed = true)
        sheet(mutableStateOf(raw))
        composeRule.onNodeWithTag("reset-family-grouping").assertExists()
        composeRule.onNodeWithTag("reset-match-decision").assertDoesNotExist()
    }

    @Test
    fun familyConfirmationExplainsPreservedCatalogAndInstallationsAndNeverCallsLegacyUnmerge() {
        var legacyCalls = 0
        sheet(mutableStateOf(family()), onSeparate = { legacyCalls++ })
        separateFirst()
        composeRule.onNodeWithText("Keep this edition as a separate library entry. Its Steam match and installed copies will stay unchanged.").assertExists()
        confirmSeparation()
        composeRule.runOnIdle { assertEquals(0, legacyCalls) }
    }

    @Test
    fun changedFamilySnapshotCannotSubmitAnOldSeparationConfirmation() {
        var legacyCalls = 0
        val state = mutableStateOf(family())
        sheet(state, onSeparate = { legacyCalls++ })
        separateFirst()
        composeRule.runOnIdle {
            state.value = state.value.copy(copies = listOf(first), copyCanonicalIds = mapOf(first.key to id(1)),
                memberSteamAppIds = mapOf(id(1) to 42), memberPreferences = mapOf(id(1) to null))
        }
        confirmSeparation()
        composeRule.runOnIdle { assertEquals(0, legacyCalls) }
        composeRule.onNodeWithTag("copies-sheet").assertExists()
    }

    @Test
    fun nestedGamepadBackClosesConfirmationBeforeTheCopiesSheetWithoutMutation() {
        var dismissals = 0
        var legacyCalls = 0
        sheet(mutableStateOf(family()), onDismiss = { dismissals++ }, onSeparate = { legacyCalls++ })
        separateFirst()
        composeRule.onNodeWithTag("confirm-copy-separation").performKeyInput { pressKey(Key.ButtonB) }
        composeRule.onNode(hasText("Separate copy?") or hasText("Separate edition?")).assertDoesNotExist()
        composeRule.onNodeWithTag("copies-sheet").assertExists().assertIsFocused()
        composeRule.runOnIdle { assertEquals(0, dismissals); assertEquals(0, legacyCalls) }
    }

    @Test
    fun cancelingSeparationLeavesTheLabeledCopiesSheetAndPerformsNoOperation() {
        var legacyCalls = 0
        var operations = 0
        sheet(mutableStateOf(family()), onSeparate = { legacyCalls++ }, onOperation = { _, _, _ -> operations++ })
        separateFirst()
        composeRule.onNodeWithText("Cancel").performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.onNodeWithTag("copies-sheet").assertExists()
        composeRule.onNodeWithText("Fixture Game Deluxe").assertExists()
        composeRule.runOnIdle { assertEquals(0, legacyCalls); assertEquals(0, operations) }
    }

    @Test
    fun conflictingMemberPreferencesStillOfferExplicitAutomaticSelectionReset() {
        val card = family().copy(memberPreferences = listOf(first, second).mapIndexed { index, copy ->
            id(index + 1) to CanonicalGamePreferenceEntity(id(index + 1).value, copy.key.accountScope.value,
                copy.source, copy.key.stableSourceId, null, null, 100)
        }.toMap())
        sheet(mutableStateOf(card))
        composeRule.onNodeWithTag("use-automatic-copy-selection").assertExists()
    }

    @Test
    fun confirmedFamilySeparationDispatchesTheCapturedCardAndExactEditionKey() {
        val card = family()
        val calls = mutableListOf<Triple<CanonicalLibraryCard, OwnedCopyKey, Boolean>>()
        sheet(mutableStateOf(card), onFamilyGrouping = { captured, key, suppressed -> calls += Triple(captured, key, suppressed) })
        composeRule.onAllNodesWithTag("separate-family-edition")[1].performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.onNodeWithTag("copy-separation-confirmation").assertExists()
        composeRule.runOnIdle { assertEquals(emptyList<Triple<CanonicalLibraryCard, OwnedCopyKey, Boolean>>(), calls) }
        confirmSeparation()
        composeRule.runOnIdle { assertEquals(listOf(Triple(card, second.key, true)), calls) }
    }

    @Test
    fun rawGroupingResetDispatchesOnlyTheCapturedMemberAndResetIntent() {
        val raw = family().copy(copies = listOf(first), copyCanonicalIds = mapOf(first.key to id(1)),
            memberSteamAppIds = mapOf(id(1) to 42), memberPreferences = mapOf(id(1) to null), familyGroupingSuppressed = true)
        val calls = mutableListOf<Triple<CanonicalLibraryCard, OwnedCopyKey, Boolean>>()
        var matchResets = 0
        sheet(mutableStateOf(raw), onFamilyGrouping = { captured, key, suppressed -> calls += Triple(captured, key, suppressed) },
            onMatchReset = { matchResets++ })
        composeRule.onNodeWithTag("reset-family-grouping").performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.runOnIdle { assertEquals(listOf(Triple(raw, first.key, false)), calls); assertEquals(0, matchResets) }
    }

    private fun separateFirst() = composeRule.onAllNodes(hasTestTag("separate-copy") or hasTestTag("separate-family-edition"))[0]
        .performSemanticsAction(SemanticsActions.OnClick) { it() }
    private fun confirmSeparation() = composeRule.onNodeWithTag("confirm-copy-separation")
        .performSemanticsAction(SemanticsActions.OnClick) { it() }

    private fun sheet(
        state: State<CanonicalLibraryCard>,
        operation: OwnedCopyOperation? = null,
        onDismiss: () -> Unit = {},
        onSeparate: (OwnedCopySummary) -> Unit = {},
        onOperation: (OwnedCopySummary, OwnedCopyOperation, Boolean) -> Unit = { _, _, _ -> },
        onFamilyGrouping: (CanonicalLibraryCard, OwnedCopyKey, Boolean) -> Unit = { _, _, _ -> },
        onMatchReset: (OwnedCopySummary) -> Unit = {},
    ) {
        composeRule.setContent {
            val inputModeManager = LocalInputModeManager.current
            LaunchedEffect(Unit) { inputModeManager.requestInputMode(InputMode.Keyboard) }
            PluviaTheme {
                Box(Modifier.fillMaxSize()) {
                    CanonicalCopiesSheet(card = state.value, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                        onDismissRequest = onDismiss, onOperation = onOperation, onUseAutomaticSelection = {},
                        onSeparateCopy = onSeparate, onResetDecision = onMatchReset, requestedOperation = operation,
                        onChangeFamilyGrouping = onFamilyGrouping)
                }
            }
        }
    }

    private fun id(index: Int) = CanonicalGameId.parse(UUID(0, index.toLong()).toString())
    private fun family() = CanonicalLibraryCard(key = CanonicalCardKey.Grouped(id(1)), canonicalId = id(1), displayName = "Fixture Game family",
        appType = CanonicalAppType.GAME, iconUrl = "", capsuleImageUrl = "", headerImageUrl = "", heroImageUrl = "", gridHeroImageScale = 1f,
        aliases = emptySet(), ownedSources = setOf(GameSource.GOG), copies = listOf(first, second), preferredCopy = null,
        steamCollectionAppIds = emptySet(), isShared = false, steamAppId = 42,
        copyCanonicalIds = mapOf(first.key to id(1), second.key to id(2)), memberSteamAppIds = mapOf(id(1) to 42, id(2) to 43),
        memberPreferences = mapOf(id(1) to null, id(2) to null))
    private fun copy(index: Int) = OwnedCopySummary(OwnedCopyKey(AccountScope("2".repeat(64)), GameSource.GOG, "$index"), GameSource.GOG,
        if (index == 1) "Fixture Game" else "Fixture Game Deluxe", null, null, null, false, false, false, false, false, null, null,
        setOf(OwnedCopyOperation.INSTALL), null, true, MatchMethod.STEAM_CATALOG, MatchConfidence.HIGH,
        MatchDecisionSource.AUTOMATIC, 41 + index, 7, 100)
}
