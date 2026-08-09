package app.gamenative.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.gamenative.R
import app.gamenative.service.steam.hasValidSteamWebApiKeyFormat
import app.gamenative.ui.model.SteamWebApiKeySettingsState
import app.gamenative.ui.model.SteamWebApiKeyValidationState

@Composable
fun SteamWebApiKeyEditorDialog(
    state: SteamWebApiKeySettingsState,
    onTest: (String) -> Unit,
    onSave: (String) -> Unit,
    onDelete: (() -> Unit)?,
    onClearFeedback: () -> Unit,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var keyInput by remember { mutableStateOf("") }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    val validFormat = hasValidSteamWebApiKeyFormat(keyInput)

    fun dismiss() {
        keyInput = ""
        onClearFeedback()
        onDismiss()
    }

    LaunchedEffect(state.saveSucceeded) {
        if (state.saveSucceeded) {
            keyInput = ""
            onSaved()
        }
    }

    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text(stringResource(R.string.settings_steam_web_api_key_title)) },
        text = {
            Column {
                Text(stringResource(R.string.settings_steam_web_api_key_description))
                NoExtractOutlinedTextField(
                    value = keyInput,
                    onValueChange = { value ->
                        keyInput = value.take(32)
                        onClearFeedback()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("steam-web-api-key-input"),
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_steam_web_api_key_field)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                    ),
                    isError = state.invalidFormat ||
                        state.operationFailed ||
                        state.validation == SteamWebApiKeyValidationState.INVALID ||
                        state.validation == SteamWebApiKeyValidationState.UNAVAILABLE,
                    supportingText = when {
                        state.invalidFormat -> {
                            { Text(stringResource(R.string.settings_steam_web_api_key_invalid)) }
                        }
                        state.operationFailed -> {
                            { Text(stringResource(R.string.settings_steam_web_api_key_failed)) }
                        }
                        state.validation == SteamWebApiKeyValidationState.TESTING -> {
                            { Text(stringResource(R.string.settings_steam_web_api_key_testing)) }
                        }
                        state.validation == SteamWebApiKeyValidationState.VALID -> {
                            { Text(stringResource(R.string.settings_steam_web_api_key_valid)) }
                        }
                        state.validation == SteamWebApiKeyValidationState.INVALID -> {
                            { Text(stringResource(R.string.settings_steam_web_api_key_rejected)) }
                        }
                        state.validation == SteamWebApiKeyValidationState.UNAVAILABLE -> {
                            { Text(stringResource(R.string.settings_steam_web_api_key_unavailable)) }
                        }
                        else -> null
                    },
                )
            }
        },
        confirmButton = {
            Button(
                modifier = Modifier.testTag("steam-web-api-key-save"),
                enabled = state.validation == SteamWebApiKeyValidationState.VALID && !state.saving,
                onClick = { onSave(keyInput) },
            ) {
                Text(
                    stringResource(
                        if (state.configured) {
                            R.string.settings_steam_web_api_key_replace
                        } else {
                            R.string.save
                        },
                    ),
                )
            }
        },
        dismissButton = {
            Row {
                if (state.configured && onDelete != null) {
                    TextButton(onClick = { showDeleteConfirmation = true }) {
                        Text(stringResource(R.string.delete))
                    }
                }
                TextButton(
                    modifier = Modifier.testTag("steam-web-api-key-test"),
                    enabled = validFormat &&
                        state.validation != SteamWebApiKeyValidationState.TESTING &&
                        !state.saving,
                    onClick = { onTest(keyInput) },
                ) {
                    Text(stringResource(R.string.settings_steam_web_api_key_test))
                }
                TextButton(onClick = ::dismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )

    if (showDeleteConfirmation && onDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.settings_steam_web_api_key_delete_title)) },
            text = { Text(stringResource(R.string.settings_steam_web_api_key_delete_description)) },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete()
                        keyInput = ""
                        showDeleteConfirmation = false
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
