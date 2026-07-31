package app.gamenative.ui.screen.library

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
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
import app.gamenative.library.canonical.CopyUnavailableReason
import app.gamenative.library.canonical.OwnedCopyOperation
import app.gamenative.library.canonical.OwnedCopySummary
import app.gamenative.ui.data.LibraryCard
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.screen.library.components.AppItem
import app.gamenative.ui.screen.library.components.CanonicalCopiesFeedback
import app.gamenative.ui.screen.library.components.CanonicalCopiesSheet
import app.gamenative.ui.screen.library.components.OwnedSourceBadges
import app.gamenative.ui.theme.PluviaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CanonicalLibraryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun canonicalCardShowsDeterministicAccessibleBadgesAndCopiesAction() {
        val card = canonicalCard()
        var copiesOpened = false

        composeRule.setContent {
            PluviaTheme {
                AppItem(
                    card = LibraryCard.canonical(
                        key = card.key,
                        index = 0,
                        name = card.displayName,
                        ownedSources = card.ownedSources,
                    ),
                    onClick = {},
                    onCopies = { copiesOpened = true },
                    paneType = PaneType.LIST,
                )
            }
        }

        composeRule.onAllNodesWithTag("canonical-card").assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("Owned on Steam").assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("Owned on GOG").assertCountEquals(1)
        composeRule.onNodeWithTag("copies-action").assertHasClickAction().performClick()
        composeRule.runOnIdle { assertTrue(copiesOpened) }
    }

    @Test
    fun badgesUseFixedSteamGogEpicAmazonCustomOrder() {
        composeRule.setContent {
            PluviaTheme {
                OwnedSourceBadges(
                    sources = listOf(
                        GameSource.CUSTOM_GAME,
                        GameSource.AMAZON,
                        GameSource.EPIC,
                        GameSource.GOG,
                        GameSource.STEAM,
                    ),
                )
            }
        }

        val leftEdges = listOf("STEAM", "GOG", "EPIC", "AMAZON", "CUSTOM_GAME").map { source ->
            composeRule.onNodeWithTag("owned-source-badge:$source").fetchSemanticsNode().boundsInRoot.left
        }
        assertEquals(leftEdges.sorted(), leftEdges)
        composeRule.onNodeWithTag("owned-source-badges").assertIsDisplayed()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun copiesSheetShowsCapabilitiesUnavailableRowsPreferenceAndSafeSeparateControls() {
        val card = canonicalCard()
        val operations = mutableListOf<Triple<GameSource, OwnedCopyOperation, Boolean>>()
        var automaticSelections = 0
        var separateSelections = 0

        composeRule.setContent {
            PluviaTheme {
                CanonicalCopiesSheet(
                    card = card,
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    onDismissRequest = {},
                    onOperation = { copy, operation, rememberChoice ->
                        operations += Triple(copy.source, operation, rememberChoice)
                    },
                    onUseAutomaticSelection = { automaticSelections += 1 },
                    onSeparateCopy = { separateSelections += 1 },
                    onResetDecision = {},
                )
            }
        }

        composeRule.onNodeWithTag("copies-sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("copy-row:STEAM").assertIsDisplayed()
        composeRule.onNodeWithTag("copy-row:GOG").assertIsDisplayed()
        composeRule.onNodeWithTag("preferred-copy").assertIsDisplayed()
        composeRule.onNodeWithTag("copy-operation:STEAM:PLAY").assertIsEnabled()
        composeRule.onNodeWithTag("copy-operation:STEAM:INSTALL").assertDoesNotExist()
        composeRule.onNodeWithTag("copy-operation:GOG:PLAY", useUnmergedTree = true).assertIsNotEnabled()
        composeRule.onNodeWithTag("separate-copy").assertDoesNotExist()

        composeRule.onNodeWithTag("remember-copy:STEAM").performClick()
        composeRule.onNodeWithTag("copy-operation:STEAM:PLAY").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(Triple(GameSource.STEAM, OwnedCopyOperation.PLAY, true)), operations)
        }

        composeRule.onNodeWithText("Use automatic selection").performClick()
        composeRule.runOnIdle { assertEquals(1, automaticSelections) }

        assertFalse(composeRule.onRoot().printToString().contains(accountScope.value))
        assertFalse(composeRule.onRoot().printToString().contains(steamKey.stableSourceId))
        assertEquals(0, separateSelections)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun availableNonSteamGroupedCopyCanBeSeparatedAndFixedFailureKeepsSheetVisible() {
        val separable = copy(
            key = epicKey,
            capabilities = setOf(OwnedCopyOperation.OPEN_SOURCE_DETAILS),
            canSeparate = true,
        )
        val card = canonicalCard().copy(
            copies = listOf(copy(steamKey), separable),
            ownedSources = setOf(GameSource.STEAM, GameSource.EPIC),
            preferredCopy = null,
        )
        var selectedKey: OwnedCopyKey? = null

        composeRule.setContent {
            PluviaTheme {
                CanonicalCopiesSheet(
                    card = card,
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    onDismissRequest = {},
                    onOperation = { _, _, _ -> },
                    onUseAutomaticSelection = {},
                    onSeparateCopy = { selectedKey = it.key },
                    onResetDecision = {},
                    feedback = CanonicalCopiesFeedback.MUTATION_FAILED,
                )
            }
        }

        composeRule.onNodeWithTag("separate-copy").performClick()
        composeRule.onNodeWithText("Separate copy?").assertIsDisplayed()
        composeRule.onNodeWithText("Separate").performClick()
        composeRule.runOnIdle { assertEquals(epicKey, selectedKey) }
        composeRule.onNodeWithTag("copies-sheet").assertIsDisplayed()
        composeRule.onNodeWithText("Could not change copy grouping. Try again.").assertIsDisplayed()
    }

    private fun canonicalCard(): CanonicalLibraryCard = CanonicalLibraryCard(
        key = CanonicalCardKey.Grouped(canonicalId),
        canonicalId = canonicalId,
        displayName = "Visible title",
        appType = CanonicalAppType.GAME,
        iconUrl = "",
        capsuleImageUrl = "",
        headerImageUrl = "",
        heroImageUrl = "",
        gridHeroImageScale = 1f,
        aliases = emptySet(),
        ownedSources = setOf(GameSource.STEAM, GameSource.GOG),
        copies = listOf(
            copy(
                key = steamKey,
                capabilities = setOf(OwnedCopyOperation.PLAY, OwnedCopyOperation.OPEN_SOURCE_DETAILS),
                preferred = true,
            ),
            copy(
                key = gogKey,
                unavailable = CopyUnavailableReason.SOURCE_READ_FAILED,
                capabilities = setOf(OwnedCopyOperation.PLAY),
            ),
        ),
        preferredCopy = steamKey,
        steamCollectionAppIds = setOf(10),
        isShared = false,
    )

    private fun copy(
        key: OwnedCopyKey,
        capabilities: Set<OwnedCopyOperation> = setOf(OwnedCopyOperation.OPEN_SOURCE_DETAILS),
        unavailable: CopyUnavailableReason? = null,
        canSeparate: Boolean = unavailable == null,
        preferred: Boolean = false,
    ): OwnedCopySummary = OwnedCopySummary(
        key = key,
        source = key.source,
        nativeTitle = if (preferred) "Steam title" else "Source title",
        installPath = if (unavailable == null) "/private/runtime/path" else null,
        installedSizeBytes = if (unavailable == null) 1024L else null,
        branchOrVersion = if (unavailable == null) "runtime-version" else null,
        isInstalled = unavailable == null,
        isDownloading = false,
        hasPartialDownload = false,
        updateAvailable = false,
        isShared = false,
        lastPlayedEpochMs = null,
        playtimeMinutes = 30,
        capabilities = capabilities,
        unavailableReason = unavailable,
        canSeparateMatch = canSeparate,
        matchMethod = MatchMethod.DIRECT_STEAM,
        confidence = MatchConfidence.VERIFIED,
        decisionSource = MatchDecisionSource.AUTOMATIC,
    )

    private companion object {
        val accountScope = AccountScope("f".repeat(64))
        val canonicalId = CanonicalGameId.parse("11111111-1111-1111-1111-111111111111")
        val steamKey = OwnedCopyKey(accountScope, GameSource.STEAM, "raw-steam-id")
        val gogKey = OwnedCopyKey(accountScope, GameSource.GOG, "raw-gog-id")
        val epicKey = OwnedCopyKey(accountScope, GameSource.EPIC, "raw-epic-id")
    }
}
