package app.gamenative.ui.screen.library.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.canonical.ExpectedMatchState
import app.gamenative.library.canonical.catalog.SteamCatalogCandidate
import app.gamenative.library.canonical.catalog.SteamResolutionProgress
import app.gamenative.ui.model.SteamMatchPickerState
import app.gamenative.ui.model.SteamMatchUiState
import app.gamenative.ui.model.SteamResolutionCoverage
import app.gamenative.ui.theme.PluviaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SteamMatchComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun statusShowsSourceAgnosticCoverageProgressAndActions() {
        var reviewClicks = 0
        var retryClicks = 0
        composeRule.setContent {
            PluviaTheme {
                SteamResolutionStatus(
                    state = SteamMatchUiState(
                        coverage = SteamResolutionCoverage(
                            resolved = 7,
                            eligible = 10,
                            needsReview = 2,
                            unmatched = 1,
                        ),
                        progress = SteamResolutionProgress(completed = 8, total = 10, failed = 1),
                    ),
                    onReviewMatches = { reviewClicks += 1 },
                    onRetry = { retryClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithText("Steam resolution").assertIsDisplayed()
        composeRule.onNodeWithText("7 / 10 matched").assertIsDisplayed()
        composeRule.onNodeWithText("2 need review · 1 unmatched").assertIsDisplayed()
        composeRule.onNodeWithText("8 / 10 checked · 1 failed").assertIsDisplayed()
        composeRule.onNodeWithTag("steam-resolution-review").performClick()
        composeRule.onNodeWithTag("steam-resolution-retry").performClick()
        composeRule.runOnIdle {
            assertEquals(1, reviewClicks)
            assertEquals(1, retryClicks)
        }
    }

    @Test
    fun missingKeyOffersOfficialSetupAndHidesResolverActions() {
        var getKeyClicks = 0
        var enterKeyClicks = 0
        composeRule.setContent {
            PluviaTheme {
                SteamResolutionStatus(
                    state = SteamMatchUiState(keyRequired = true),
                    onReviewMatches = {},
                    onRetry = {},
                    onGetApiKey = { getKeyClicks += 1 },
                    onEnterApiKey = { enterKeyClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithText("Steam Web API key required").assertIsDisplayed()
        composeRule.onNodeWithText("Get Steam Web API key").performClick()
        composeRule.onNodeWithText("Enter API key").performClick()
        composeRule.onNodeWithTag("steam-web-api-key-input").assertIsDisplayed()
        composeRule.onNodeWithTag("steam-resolution-review").assertDoesNotExist()
        composeRule.onNodeWithTag("steam-resolution-retry").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(1, getKeyClicks)
            assertEquals(1, enterKeyClicks)
        }
    }

    @Test
    fun pickerRequiresSelectionAndExposesCandidateCorrectionActions() {
        val selected = mutableStateOf<Int?>(null)
        var confirmClicks = 0
        var keepSeparateClicks = 0
        var resetClicks = 0
        var cancelClicks = 0
        composeRule.setContent {
            PluviaTheme {
                SteamMatchPicker(
                    state = SteamMatchPickerState.Results(
                        expected = expected(),
                        candidates = listOf(candidate()),
                        selectedSteamAppId = selected.value,
                    ),
                    query = "Fixture",
                    onQueryChange = {},
                    onSearch = {},
                    onSelectCandidate = { selected.value = it },
                    onConfirm = { confirmClicks += 1 },
                    onKeepSeparate = { keepSeparateClicks += 1 },
                    onResetToAutomatic = { resetClicks += 1 },
                    onCancel = { cancelClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithText("Fixture candidate").assertIsDisplayed()
        composeRule.onNodeWithText("Fixture Studio · 2020 · Game").assertIsDisplayed()
        composeRule.onNodeWithText("Current match").assertIsDisplayed()
        composeRule.onNodeWithTag("steam-match-confirm").assertIsNotEnabled()
        composeRule.onNodeWithTag("steam-match-candidate:42").performClick()
        composeRule.onNodeWithTag("steam-match-confirm").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("steam-match-keep-separate").performClick()
        composeRule.onNodeWithTag("steam-match-reset").performClick()
        composeRule.onNodeWithTag("steam-match-cancel").performClick()
        composeRule.runOnIdle {
            assertEquals(42, selected.value)
            assertEquals(1, confirmClicks)
            assertEquals(1, keepSeparateClicks)
            assertEquals(1, resetClicks)
            assertEquals(1, cancelClicks)
        }
    }

    private fun expected() = ExpectedMatchState(
        key = OwnedCopyKey(
            accountScope = AccountScope.parse("a".repeat(64)),
            source = GameSource.GOG,
            stableSourceId = "1",
        ),
        canonicalId = "11111111-1111-1111-1111-111111111111",
        matchMethod = MatchMethod.MANUAL,
        confidence = MatchConfidence.VERIFIED,
        decisionSource = MatchDecisionSource.USER,
        candidateSteamAppId = 42,
        resolverVersion = 2,
        decisionRevision = 1,
    )

    private fun candidate() = SteamCatalogCandidate(
        steamAppId = 42,
        title = "Fixture candidate",
        developer = "Fixture Studio",
        releaseYear = 2020,
        appType = CanonicalAppType.GAME,
        headerImageUrl = null,
    )
}
