package app.gamenative.ui.screen.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.ui.component.SteamWebApiKeyEditorDialog
import app.gamenative.ui.model.SteamWebApiKeySettingsState
import app.gamenative.ui.theme.settingsTileColors
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink

@Composable
fun SettingsGroupSteam(
    state: SteamWebApiKeySettingsState,
    onTest: (String) -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
    onClearFeedback: () -> Unit,
) {
    var showEditor by remember { mutableStateOf(false) }

    SettingsGroup {
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(stringResource(R.string.settings_steam_web_api_key_title)) },
            subtitle = {
                Text(
                    stringResource(
                        if (state.configured) {
                            R.string.settings_steam_web_api_key_configured
                        } else {
                            R.string.settings_steam_web_api_key_not_configured
                        },
                    ),
                )
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.VpnKey,
                    contentDescription = null,
                )
            },
            onClick = {
                onClearFeedback()
                showEditor = true
            },
        )
    }

    if (showEditor) {
        SteamWebApiKeyEditorDialog(
            state = state,
            onTest = onTest,
            onSave = onSave,
            onDelete = onDelete,
            onClearFeedback = onClearFeedback,
            onDismiss = { showEditor = false },
            onSaved = { showEditor = false },
        )
    }
}
