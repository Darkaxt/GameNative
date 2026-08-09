package app.gamenative.ui.screen.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.component.SteamWebApiKeyEditorDialog
import app.gamenative.ui.model.SteamMatchUiState
import app.gamenative.ui.model.SteamWebApiKeySettingsState

@Composable
internal fun SteamResolutionStatus(
    state: SteamMatchUiState,
    onReviewMatches: () -> Unit,
    onRetry: () -> Unit,
    onGetApiKey: () -> Unit = {},
    onEnterApiKey: () -> Unit = {},
    keySettingsState: SteamWebApiKeySettingsState = SteamWebApiKeySettingsState(),
    onTestApiKey: (String) -> Unit = {},
    onSaveApiKey: (String) -> Unit = {},
    onClearApiKeyFeedback: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showKeyEditor by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("steam-resolution-status"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.steam_resolution_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (state.keyRequired) {
            Text(
                text = stringResource(R.string.steam_resolution_key_required),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = onGetApiKey,
                modifier = Modifier.testTag("steam-resolution-get-api-key"),
            ) {
                Text(stringResource(R.string.steam_resolution_get_api_key))
            }
            TextButton(
                onClick = {
                    onClearApiKeyFeedback()
                    onEnterApiKey()
                    showKeyEditor = true
                },
                modifier = Modifier.testTag("steam-resolution-enter-api-key"),
            ) {
                Text(stringResource(R.string.steam_resolution_enter_api_key))
            }
        } else {
            Text(
                text = stringResource(
                    R.string.steam_resolution_coverage,
                    state.coverage.resolved,
                    state.coverage.eligible,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    R.string.steam_resolution_review_unmatched,
                    state.coverage.needsReview,
                    state.coverage.unmatched,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    R.string.steam_resolution_progress,
                    state.progress.completed,
                    state.progress.total,
                    state.progress.failed,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.isScanning && state.progress.total > 0) {
                LinearProgressIndicator(
                    progress = {
                        state.progress.completed.toFloat() / state.progress.total.toFloat()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onReviewMatches,
                    enabled = state.coverage.needsReview > 0 && !state.isScanning,
                    modifier = Modifier.testTag("steam-resolution-review"),
                ) {
                    Text(stringResource(R.string.steam_resolution_review_matches))
                }
                TextButton(
                    onClick = onRetry,
                    enabled = !state.isScanning,
                    modifier = Modifier.testTag("steam-resolution-retry"),
                ) {
                    Text(stringResource(R.string.steam_resolution_retry))
                }
            }
        }
    }

    if (showKeyEditor) {
        SteamWebApiKeyEditorDialog(
            state = keySettingsState,
            onTest = onTestApiKey,
            onSave = onSaveApiKey,
            onDelete = null,
            onClearFeedback = onClearApiKeyFeedback,
            onDismiss = { showKeyEditor = false },
            onSaved = { showKeyEditor = false },
        )
    }
}
