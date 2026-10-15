package app.gamenative.ui.screen.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.ui.model.AggregationDiagnosticState
import app.gamenative.ui.model.AggregationExportStatus

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AggregationDiagnosticDialog(
    state: AggregationDiagnosticState,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onExport: () -> Unit,
) {
    if (state == AggregationDiagnosticState.Closed) return
    val closeFocus = remember { FocusRequester() }
    val content = state as? AggregationDiagnosticState.Content
    val exporting = content?.exportStatus in setOf(AggregationExportStatus.CHOOSING, AggregationExportStatus.WRITING)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val inputMode = LocalInputModeManager.current.inputMode
        Surface(
            modifier = Modifier.widthIn(max = 960.dp).fillMaxWidth(0.94f)
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.9f).dp)
                .testTag("aggregation-dialog")
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && (event.key == Key.ButtonB || event.key == Key.Escape)) {
                        onClose()
                        true
                    } else false
                },
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.canonical_explain_aggregation), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.aggregation_read_only), style = MaterialTheme.typography.bodySmall)
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()).testTag("aggregation-report-scroll"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (state) {
                        AggregationDiagnosticState.Loading -> {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.aggregation_loading))
                        }
                        AggregationDiagnosticState.Failed -> Text(stringResource(R.string.aggregation_failed))
                        is AggregationDiagnosticState.Content -> SelectionContainer {
                            Text(state.report, modifier = Modifier.testTag("aggregation-report"), style = MaterialTheme.typography.bodyMedium)
                        }
                        AggregationDiagnosticState.Closed -> Unit
                    }
                }
                when (content?.exportStatus) {
                    AggregationExportStatus.WRITING -> Text(stringResource(R.string.aggregation_exporting))
                    AggregationExportStatus.SUCCEEDED -> Text(stringResource(R.string.aggregation_exported))
                    AggregationExportStatus.FAILED -> Text(stringResource(R.string.aggregation_export_failed))
                    else -> Unit
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onClose, modifier = Modifier.focusRequester(closeFocus).testTag("aggregation-close")) {
                        Text(stringResource(if (state == AggregationDiagnosticState.Loading) R.string.cancel else R.string.close))
                    }
                    if (state == AggregationDiagnosticState.Failed) {
                        TextButton(onClick = onRetry, modifier = Modifier.testTag("aggregation-retry")) {
                            Text(stringResource(R.string.canonical_detail_retry))
                        }
                    }
                    TextButton(onClick = onExport, enabled = content != null && !exporting, modifier = Modifier.testTag("aggregation-export")) {
                        Text(stringResource(R.string.aggregation_export))
                    }
                }
            }
        }
        LaunchedEffect(inputMode) {
            if (inputMode == InputMode.Keyboard) closeFocus.requestFocus()
        }
    }
}
