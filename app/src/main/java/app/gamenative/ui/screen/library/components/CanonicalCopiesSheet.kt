package app.gamenative.ui.screen.library.components

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.library.canonical.CanonicalCardKey
import app.gamenative.library.canonical.CanonicalLibraryCard
import app.gamenative.library.canonical.CopyUnavailableReason
import app.gamenative.library.canonical.OwnedCopyOperation
import app.gamenative.library.canonical.OwnedCopySummary
import app.gamenative.ui.model.SteamMatchStatus
import app.gamenative.ui.model.steamMatchStatus

internal enum class CanonicalCopiesFeedback {
    COPY_STATE_CHANGED,
    PREFERENCE_NOT_REMEMBERED,
    PREFERENCE_CLEAR_FAILED,
    MUTATION_FAILED,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CanonicalCopiesSheet(
    card: CanonicalLibraryCard,
    sheetState: SheetState,
    onDismissRequest: () -> Unit,
    onOperation: (OwnedCopySummary, OwnedCopyOperation, Boolean) -> Unit,
    onUseAutomaticSelection: () -> Unit,
    onSeparateCopy: (OwnedCopySummary) -> Unit,
    onResetDecision: (OwnedCopySummary) -> Unit,
    onFixSteamMatch: (OwnedCopySummary) -> Unit = {},
    isSteamMatchScanning: Boolean = false,
    modifier: Modifier = Modifier,
    feedback: CanonicalCopiesFeedback? = null,
    actionInProgress: Boolean = false,
) {
    var pendingSeparation by remember(card.key) { mutableStateOf<OwnedCopySummary?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        modifier = modifier.testTag("copies-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.canonical_copies_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = card.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            feedback?.let { value ->
                Text(
                    text = stringResource(value.messageResource()),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(
                    items = card.copies,
                    key = { index, copy -> "${copy.source.name}:$index" },
                ) { _, copy ->
                    CanonicalCopyRow(
                        card = card,
                        copy = copy,
                        isPreferred = card.preferredCopy == copy.key,
                        actionInProgress = actionInProgress,
                        onOperation = onOperation,
                        onSeparate = { pendingSeparation = copy },
                        onResetDecision = { onResetDecision(copy) },
                        onFixSteamMatch = { onFixSteamMatch(copy) },
                        isSteamMatchScanning = isSteamMatchScanning,
                    )
                }
            }

            if (card.preferredCopy != null) {
                OutlinedButton(
                    onClick = onUseAutomaticSelection,
                    enabled = !actionInProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.canonical_use_automatic_selection))
                }
            }
        }
    }

    pendingSeparation?.let { copy ->
        AlertDialog(
            onDismissRequest = { pendingSeparation = null },
            title = { Text(stringResource(R.string.canonical_separate_copy_title)) },
            text = { Text(stringResource(R.string.canonical_separate_copy_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingSeparation = null
                        onSeparateCopy(copy)
                    },
                ) {
                    Text(stringResource(R.string.canonical_separate_copy_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSeparation = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun CanonicalCopyRow(
    card: CanonicalLibraryCard,
    copy: OwnedCopySummary,
    isPreferred: Boolean,
    actionInProgress: Boolean,
    onOperation: (OwnedCopySummary, OwnedCopyOperation, Boolean) -> Unit,
    onSeparate: () -> Unit,
    onResetDecision: () -> Unit,
    onFixSteamMatch: () -> Unit,
    isSteamMatchScanning: Boolean,
) {
    val source = sourceLabel(copy.source)
    val unavailable = copy.unavailableReason != null
    val stateLabel = when {
        unavailable -> stringResource(R.string.canonical_copy_unavailable)
        copy.isDownloading -> stringResource(R.string.downloading)
        copy.isInstalled -> stringResource(R.string.installed)
        else -> stringResource(R.string.not_installed)
    }
    var rememberChoice by remember(card.key, copy.key) { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("copy-row:${copy.source.name}")
            .semantics {
                contentDescription = "$source. $stateLabel"
            },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GameSourceIcon(
                    gameSource = copy.source,
                    iconSize = 18,
                    alignmentBoxSize = 28,
                    contentDescription = source,
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = source,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = copy.nativeTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (isPreferred) {
                    Text(
                        text = stringResource(R.string.canonical_preferred_copy),
                        modifier = Modifier.testTag("preferred-copy"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            CopyRuntimeDetails(copy = copy, stateLabel = stateLabel)

            DetailLine(
                stringResource(R.string.steam_match_title),
                stringResource(copy.steamMatchStatus(isSteamMatchScanning).labelResId()),
            )
            if (copy.source != GameSource.STEAM) {
                TextButton(
                    onClick = onFixSteamMatch,
                    enabled = !actionInProgress,
                    modifier = Modifier.testTag("fix-steam-match:${copy.source.name}"),
                ) {
                    Text(stringResource(R.string.steam_match_fix))
                }
            }

            if (copy.capabilities.isNotEmpty()) {
                val sortedOperations = copy.capabilities.sortedBy(::operationRank)
                val regularOperations = sortedOperations.filterNot(COMPACT_OPERATIONS::contains)
                val compactOperations = sortedOperations.filter(COMPACT_OPERATIONS::contains)

                HorizontalDivider()
                regularOperations.forEach { operation ->
                    Button(
                        onClick = { onOperation(copy, operation, rememberChoice) },
                        enabled = !actionInProgress && !unavailable,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("copy-operation:${copy.source.name}:${operation.name}"),
                    ) {
                        Text(operationLabel(operation, copy))
                    }
                }
                if (compactOperations.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        compactOperations.forEach { operation ->
                            val label = operationLabel(operation, copy)
                            IconButton(
                                onClick = { onOperation(copy, operation, rememberChoice) },
                                enabled = !actionInProgress && !unavailable,
                                modifier = Modifier
                                    .testTag(
                                        "copy-operation:${copy.source.name}:${operation.name}",
                                    )
                                    .semantics { contentDescription = label },
                            ) {
                                Icon(
                                    imageVector = compactOperationIcon(operation),
                                    contentDescription = null,
                                )
                            }
                        }
                    }
                }

                val rememberEnabled = !actionInProgress && !unavailable
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = rememberChoice,
                            enabled = rememberEnabled,
                            role = Role.Checkbox,
                            onValueChange = { rememberChoice = it },
                        )
                        .testTag("remember-copy:${copy.source.name}")
                        .semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = rememberChoice,
                        onCheckedChange = null,
                        enabled = rememberEnabled,
                    )
                    Text(
                        text = stringResource(R.string.canonical_always_use_copy),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            val canSeparate = card.key is CanonicalCardKey.Grouped &&
                card.copies.size >= 2 &&
                copy.source != GameSource.STEAM &&
                copy.canSeparateMatch &&
                (
                    copy.unavailableReason == null ||
                        copy.unavailableReason == CopyUnavailableReason.LEGACY_BRIDGE_UNSUPPORTED
                )
            if (canSeparate) {
                TextButton(
                    onClick = onSeparate,
                    enabled = !actionInProgress,
                    modifier = Modifier.testTag("separate-copy"),
                ) {
                    Text(stringResource(R.string.canonical_separate_copy_action))
                }
            }

            val canReset = card.key is CanonicalCardKey.Grouped &&
                copy.source != GameSource.STEAM &&
                copy.confidence == MatchConfidence.REJECTED &&
                copy.decisionSource == MatchDecisionSource.USER
            if (canReset) {
                TextButton(
                    onClick = onResetDecision,
                    enabled = !actionInProgress,
                    modifier = Modifier.testTag("reset-match-decision"),
                ) {
                    Text(stringResource(R.string.canonical_reset_match_decision))
                }
            }
        }
    }
}

@Composable
private fun CopyRuntimeDetails(
    copy: OwnedCopySummary,
    stateLabel: String,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        DetailLine(stringResource(R.string.status), stateLabel)
        copy.installPath?.takeIf(String::isNotBlank)?.let { path ->
            DetailLine(stringResource(R.string.location), path)
        }
        copy.installedSizeBytes?.takeIf { it >= 0L }?.let { bytes ->
            DetailLine(stringResource(R.string.size), Formatter.formatFileSize(context, bytes))
        }
        copy.branchOrVersion?.takeIf(String::isNotBlank)?.let { version ->
            DetailLine(stringResource(R.string.canonical_copy_branch_or_version), version)
        }
        if (copy.updateAvailable) {
            DetailLine(stringResource(R.string.status), stringResource(R.string.update_available))
        }
        copy.playtimeMinutes?.takeIf { it >= 0L }?.let { minutes ->
            DetailLine(
                stringResource(R.string.playtime),
                stringResource(R.string.canonical_copy_playtime_minutes, minutes),
            )
        }
        copy.lastPlayedEpochMs?.takeIf { it > 0L }?.let { lastPlayed ->
            DetailLine(
                stringResource(R.string.last_played),
                DateUtils.getRelativeTimeSpanString(lastPlayed).toString(),
            )
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Text(
        text = stringResource(R.string.canonical_copy_detail_line, label, value),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun operationLabel(
    operation: OwnedCopyOperation,
    copy: OwnedCopySummary,
): String = stringResource(
    when (operation) {
        OwnedCopyOperation.INSTALL -> R.string.install
        OwnedCopyOperation.PLAY -> R.string.run_app
        OwnedCopyOperation.UPDATE -> R.string.steam_update_title
        OwnedCopyOperation.UNINSTALL -> R.string.uninstall
        OwnedCopyOperation.PAUSE_RESUME_DOWNLOAD -> if (copy.isDownloading) {
            R.string.pause_download
        } else {
            R.string.resume_download
        }
        OwnedCopyOperation.CANCEL_DOWNLOAD -> R.string.cancel_download_prompt_title
        OwnedCopyOperation.EXPORT_SAVES -> R.string.option_export_saves
        OwnedCopyOperation.IMPORT_SAVES -> R.string.option_import_saves
        OwnedCopyOperation.OPEN_SOURCE_DETAILS -> R.string.canonical_open_source_details
    },
)

private val COMPACT_OPERATIONS = setOf(
    OwnedCopyOperation.INSTALL,
    OwnedCopyOperation.EXPORT_SAVES,
    OwnedCopyOperation.IMPORT_SAVES,
    OwnedCopyOperation.OPEN_SOURCE_DETAILS,
)

private fun compactOperationIcon(operation: OwnedCopyOperation): ImageVector = when (operation) {
    OwnedCopyOperation.INSTALL -> Icons.Default.CloudDownload
    OwnedCopyOperation.EXPORT_SAVES -> Icons.Default.ArrowUpward
    OwnedCopyOperation.IMPORT_SAVES -> Icons.Default.ArrowDownward
    OwnedCopyOperation.OPEN_SOURCE_DETAILS -> Icons.AutoMirrored.Filled.OpenInNew
    else -> error("Operation does not have a compact icon")
}

private fun operationRank(operation: OwnedCopyOperation): Int = when (operation) {
    OwnedCopyOperation.PLAY -> 0
    OwnedCopyOperation.INSTALL -> 1
    OwnedCopyOperation.UPDATE -> 2
    OwnedCopyOperation.PAUSE_RESUME_DOWNLOAD -> 3
    OwnedCopyOperation.CANCEL_DOWNLOAD -> 4
    OwnedCopyOperation.UNINSTALL -> 5
    OwnedCopyOperation.EXPORT_SAVES -> 6
    OwnedCopyOperation.IMPORT_SAVES -> 7
    OwnedCopyOperation.OPEN_SOURCE_DETAILS -> 8
}

private fun SteamMatchStatus.labelResId(): Int = when (this) {
    SteamMatchStatus.AUTOMATIC -> R.string.steam_match_status_automatic
    SteamMatchStatus.USER_CONFIRMED -> R.string.steam_match_status_user_confirmed
    SteamMatchStatus.NEEDS_REVIEW -> R.string.steam_match_status_needs_review
    SteamMatchStatus.KEPT_SEPARATE -> R.string.steam_match_status_kept_separate
    SteamMatchStatus.UNMATCHED -> R.string.steam_match_status_unmatched
    SteamMatchStatus.CHECKING -> R.string.steam_match_status_checking
    SteamMatchStatus.IMMUTABLE_STEAM -> R.string.steam_match_status_immutable
}

private fun CanonicalCopiesFeedback.messageResource(): Int = when (this) {
    CanonicalCopiesFeedback.COPY_STATE_CHANGED -> R.string.canonical_copy_state_changed
    CanonicalCopiesFeedback.PREFERENCE_NOT_REMEMBERED -> R.string.canonical_preference_not_remembered
    CanonicalCopiesFeedback.PREFERENCE_CLEAR_FAILED -> R.string.canonical_preference_clear_failed
    CanonicalCopiesFeedback.MUTATION_FAILED -> R.string.canonical_copy_mutation_failed
}
