package app.gamenative.ui.screen.library

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import app.gamenative.ui.model.AggregationDiagnosticState
import app.gamenative.ui.model.AggregationExportStatus
import app.gamenative.ui.theme.PluviaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], qualifiers = "w1200dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class AggregationDiagnosticDialogTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun loadingCanBeCancelledAndNeverOffersExport() {
        var state by mutableStateOf<AggregationDiagnosticState>(AggregationDiagnosticState.Loading)
        composeRule.setContent { PluviaTheme {
            AggregationDiagnosticDialog(state, onClose = { state = AggregationDiagnosticState.Closed }, onRetry = {}, onExport = {})
        } }
        composeRule.onNodeWithText("Preparing aggregation report…").assertIsDisplayed()
        composeRule.onNodeWithTag("aggregation-close").assertIsFocused()
        composeRule.onNodeWithTag("aggregation-export").assertIsNotEnabled()
        composeRule.onNodeWithTag("aggregation-close").performClick()
        composeRule.onNodeWithTag("aggregation-dialog").assertDoesNotExist()
    }

    @Test fun fixedFailureCanRetryAndKeyboardBackClosesDialog() {
        var retries = 0
        var state by mutableStateOf<AggregationDiagnosticState>(AggregationDiagnosticState.Failed)
        composeRule.setContent { PluviaTheme {
            AggregationDiagnosticDialog(state, onClose = { state = AggregationDiagnosticState.Closed }, onRetry = { retries++ }, onExport = {})
        } }
        composeRule.onNodeWithText("Could not prepare the aggregation report. Try again.").assertIsDisplayed()
        composeRule.onNodeWithTag("aggregation-retry").performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
        composeRule.onNodeWithTag("aggregation-close").performKeyInput { pressKey(Key.Escape) }
        composeRule.onNodeWithTag("aggregation-dialog").assertDoesNotExist()
    }

    @Test fun completedReportIsScrollableAndExportDoesNotChangeDisplayedText() {
        val report = (1..200).joinToString("\n") { "Public diagnostic line $it" }
        var exports = 0
        var state by mutableStateOf<AggregationDiagnosticState>(AggregationDiagnosticState.Content(report))
        composeRule.setContent { PluviaTheme {
            AggregationDiagnosticDialog(state, onClose = { state = AggregationDiagnosticState.Closed }, onRetry = {}, onExport = { exports++ })
        } }
        composeRule.onNodeWithTag("aggregation-report").assertIsDisplayed()
        composeRule.onNodeWithText(report, substring = false).assertExists()
        val scroll = composeRule.onNodeWithTag("aggregation-report-scroll")
        assertTrue(scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0f)
        scroll.performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        assertTrue(scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        composeRule.onNodeWithTag("aggregation-export").assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(1, exports)
            state = AggregationDiagnosticState.Content(report, AggregationExportStatus.WRITING)
        }
        composeRule.onNodeWithTag("aggregation-export").assertIsNotEnabled()
        composeRule.onNodeWithText("Exporting report…").assertIsDisplayed()
        composeRule.onNodeWithTag("aggregation-close").performKeyInput { pressKey(Key.ButtonB) }
        composeRule.onNodeWithTag("aggregation-dialog").assertDoesNotExist()
    }

    @Test fun exportFailureKeepsTheCompletedReportAndAllowsAnotherManualExport() {
        var exports = 0
        composeRule.setContent { PluviaTheme {
            AggregationDiagnosticDialog(AggregationDiagnosticState.Content("Completed report", AggregationExportStatus.FAILED),
                onClose = {}, onRetry = {}, onExport = { exports++ })
        } }
        composeRule.onNodeWithText("Completed report").assertExists()
        composeRule.onNodeWithText("Could not export the report. Try again.").assertIsDisplayed()
        composeRule.onNodeWithTag("aggregation-export").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, exports) }
        composeRule.onNodeWithText("Completed report").assertExists()
    }

    @Suppress("DEPRECATION")
    @Test fun systemBackClosesDialogWithoutExporting() {
        var exports = 0
        var state by mutableStateOf<AggregationDiagnosticState>(AggregationDiagnosticState.Content("Completed report"))
        composeRule.setContent { PluviaTheme {
            AggregationDiagnosticDialog(state, onClose = { state = AggregationDiagnosticState.Closed },
                onRetry = {}, onExport = { exports++ })
        } }
        composeRule.onNodeWithTag("aggregation-dialog").assertIsDisplayed()
        composeRule.runOnIdle { org.robolectric.shadows.ShadowDialog.getLatestDialog().onBackPressed() }
        composeRule.onNodeWithTag("aggregation-dialog").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, exports) }
    }
}
