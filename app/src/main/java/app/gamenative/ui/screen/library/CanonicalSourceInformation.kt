package app.gamenative.ui.screen.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.component.CommunityCompatibilitySection
import app.gamenative.ui.component.InfoCard
import app.gamenative.ui.data.Achievement
import app.gamenative.ui.data.GameDisplayInfo

@Composable
internal fun CanonicalSourceInformation(source: OwnedSourceDetailPresentation) {
    val info = source.displayInfo
    val download = source.downloadDetails
    Column(
        modifier = Modifier.fillMaxWidth().testTag("canonical-detail-source-information"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(info.name, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard(
                label = stringResource(R.string.status),
                value = stringResource(when {
                    download.isDownloading -> R.string.installing
                    download.isInstalled -> R.string.installed
                    else -> R.string.not_installed
                }),
                isCompact = true,
                modifier = Modifier.weight(1f),
                focusableForNavigation = true,
            )
            InfoCard(
                label = stringResource(R.string.size),
                value = (if (download.isInstalled) info.sizeOnDisk else info.sizeFromStore)
                    ?: stringResource(R.string.library_compatibility_unknown),
                isCompact = true,
                modifier = Modifier.weight(1f),
                focusableForNavigation = true,
            )
        }
        if (download.isInstalled) {
            info.installLocation?.let {
                InfoCard(label = stringResource(R.string.location), value = it, isCompact = true,
                    modifier = Modifier.fillMaxWidth(), focusableForNavigation = true)
            }
        }
        info.playtimeText?.let {
            InfoCard(label = stringResource(R.string.play_time), value = it, isCompact = true,
                modifier = Modifier.fillMaxWidth(), focusableForNavigation = true)
        }
        info.lastPlayedText?.let {
            InfoCard(label = stringResource(R.string.last_played), value = it, isCompact = true,
                modifier = Modifier.fillMaxWidth(), focusableForNavigation = true)
        }
        if (download.isDownloading) {
            Text("${(download.downloadProgress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
        }
        if (info.isLoadingPreferredCopy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.loading_preferred_copy), style = MaterialTheme.typography.bodySmall)
            }
        } else {
            info.preferredCopyStatusText?.let { Text(it) }
            if (info.showChangePreferredCopy) {
                info.onChangePreferredCopy?.let { changeCopy ->
                    TextButton(onClick = changeCopy) { Text(stringResource(R.string.change_preferred_copy)) }
                }
            }
        }
        source.supplementalContent()
    }
}

@Composable
internal fun SourceDetailExtras(
    displayInfo: GameDisplayInfo,
    isInstalled: Boolean,
    achievements: List<Achievement>?,
    immersiveMode: ImmersiveModeUiState,
    communityCompatibility: CommunityCompatibilityUiState,
) {
    if (isInstalled && immersiveMode.isSupported) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = immersiveMode.isEnabled, onCheckedChange = immersiveMode.onChange)
            Text(stringResource(R.string.launch_immersive_mode))
        }
        if (immersiveMode.isEnabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = immersiveMode.isVrEnabled, onCheckedChange = immersiveMode.onVrChange)
                Text(stringResource(R.string.xr_windows_vr_toggle))
            }
        }
    }
    CommunityCompatibilitySection(
        gameKey = displayInfo.appId,
        summary = communityCompatibility.summary,
        loading = communityCompatibility.loading,
        loadError = communityCompatibility.loadError,
        onRetry = communityCompatibility.onRetry,
        onViewReports = communityCompatibility.onViewReports,
    )
    if (!achievements.isNullOrEmpty()) AchievementsRow(achievements)
}
