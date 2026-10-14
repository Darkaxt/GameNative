package app.gamenative.ui.screen.settings

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import app.gamenative.R
import app.gamenative.diagnostics.FocusedDiagnosticCaptureState
import app.gamenative.ui.theme.PluviaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.junit.rules.TimeoutRule

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], qualifiers = "w800dp-h900dp")
@LooperMode(LooperMode.Mode.PAUSED)
class FocusedDiagnosticCaptureDialogTest {
    @get:Rule(order = 0) val timeout = TimeoutRule.seconds(30)
    @get:Rule(order = 1) val compose = createComposeRule()

    @Test
    fun editableFilterStartsCaptureOnlyOnExplicitButton() {
        val state = mutableStateOf(FocusedDiagnosticCaptureState())
        var selected: String? = null
        compose.setContent {
            PluviaTheme {
                FocusedDiagnosticCaptureDialog(state.value, onStart = {
                    selected = it
                    state.value = state.value.copy(title = it, enabled = true)
                }, onStop = {}, onExport = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText(text(R.string.settings_debug_focused_diagnostics_start)).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("DREDGE")
        assertEquals(null, selected)
        compose.onNodeWithText(text(R.string.settings_debug_focused_diagnostics_start)).performClick()
        assertEquals("DREDGE", selected)
        compose.onNodeWithText("Capturing: DREDGE").assertIsDisplayed()
    }

    @Test
    fun fullCaptureHasVisibleWarningAndSeparateStopExportAndCloseActions() {
        var stops = 0
        var exports = 0
        var closes = 0
        compose.setContent {
            PluviaTheme {
                FocusedDiagnosticCaptureDialog(
                    FocusedDiagnosticCaptureState(title = "DREDGE", enabled = true, full = true),
                    onStart = {}, onStop = { stops++ }, onExport = { exports++ }, onDismiss = { closes++ },
                )
            }
        }
        compose.onNodeWithText(text(R.string.settings_debug_focused_diagnostics_full)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.settings_debug_focused_diagnostics_stop)).performClick()
        compose.onNodeWithText(text(R.string.settings_debug_focused_diagnostics_export)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.settings_debug_focused_diagnostics_close)).performClick()
        assertEquals(1, stops)
        assertEquals(1, exports)
        assertEquals(1, closes)
    }

    @Test
    fun busyConfigurationCannotSubmitAnotherStartStopOrExport() {
        compose.setContent {
            PluviaTheme {
                FocusedDiagnosticCaptureDialog(FocusedDiagnosticCaptureState(title = "DREDGE", enabled = true),
                    busy = true, onStart = {}, onStop = {}, onExport = {}, onDismiss = {})
            }
        }
        for (label in listOf(R.string.settings_debug_focused_diagnostics_start,
                R.string.settings_debug_focused_diagnostics_stop, R.string.settings_debug_focused_diagnostics_export)) {
            compose.onNodeWithText(text(label)).assertIsNotEnabled()
        }
    }

    private fun text(id: Int): String = ApplicationProvider.getApplicationContext<Application>().getString(id)
}
