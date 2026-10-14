package app.gamenative.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.diagnostics.FocusedDiagnosticCaptureState
import app.gamenative.ui.component.NoExtractOutlinedTextField

@Composable
internal fun FocusedDiagnosticCaptureDialog(
    state: FocusedDiagnosticCaptureState,
    busy: Boolean = false,
    onStart: (String) -> Unit,
    onStop: () -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(state.title) { mutableStateOf(state.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_debug_focused_diagnostics_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.settings_debug_focused_diagnostics_description))
                NoExtractOutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(120) },
                    label = { Text(stringResource(R.string.settings_debug_focused_diagnostics_filter)) },
                    placeholder = { Text(stringResource(R.string.settings_debug_focused_diagnostics_hint)) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                FocusedDiagnosticCaptureStatus(state)
                TextButton(onClick = onExport, enabled = !busy && state.available) {
                    Text(stringResource(R.string.settings_debug_focused_diagnostics_export))
                }
            }
        },
        confirmButton = {
            Button(onClick = { onStart(title) }, enabled = !busy && state.available && title.isNotBlank()) {
                Text(stringResource(R.string.settings_debug_focused_diagnostics_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onStop, enabled = !busy && state.enabled) {
                Text(stringResource(R.string.settings_debug_focused_diagnostics_stop))
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_debug_focused_diagnostics_close))
            }
        },
    )
}

@Composable
internal fun FocusedDiagnosticCaptureStatus(state: FocusedDiagnosticCaptureState) {
    Text(when {
        !state.available || state.writeFailures > 0 -> stringResource(R.string.settings_debug_focused_diagnostics_failures)
        state.full -> stringResource(R.string.settings_debug_focused_diagnostics_full)
        state.enabled -> stringResource(R.string.settings_debug_focused_diagnostics_active, state.title)
        else -> stringResource(R.string.settings_debug_focused_diagnostics_disabled)
    })
}
