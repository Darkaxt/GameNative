package app.gamenative.ui.screen.library.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.gamenative.R
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.library.canonical.catalog.SteamCatalogCandidate
import app.gamenative.ui.model.SteamMatchPickerState

@Composable
internal fun SteamMatchPicker(
    state: SteamMatchPickerState,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSelectCandidate: (Int) -> Unit,
    onConfirm: () -> Unit,
    onKeepSeparate: () -> Unit,
    onResetToAutomatic: () -> Unit,
    onCancel: () -> Unit,
) {
    if (state == SteamMatchPickerState.Closed) return

    val expected = when (state) {
        SteamMatchPickerState.Closed -> return
        is SteamMatchPickerState.Searching -> state.expected
        is SteamMatchPickerState.Results -> state.expected
        is SteamMatchPickerState.Empty -> state.expected
        is SteamMatchPickerState.Unavailable -> state.expected
    }
    val selectedSteamAppId = (state as? SteamMatchPickerState.Results)?.selectedSteamAppId
    val canKeepSeparate = selectedSteamAppId != null || expected.candidateSteamAppId != null
    val searchFocusRequester = remember(expected.key) { FocusRequester() }

    LaunchedEffect(expected.key) {
        searchFocusRequester.requestFocus()
    }

    Dialog(onDismissRequest = onCancel) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 680.dp)
                .testTag("steam-match-picker"),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.steam_match_fix),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text(stringResource(R.string.steam_match_search_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(searchFocusRequester)
                        .testTag("steam-match-query"),
                )
                Button(
                    onClick = onSearch,
                    enabled = state !is SteamMatchPickerState.Searching && query.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().testTag("steam-match-search"),
                ) {
                    Text(stringResource(R.string.steam_match_search))
                }

                when (state) {
                    SteamMatchPickerState.Closed -> Unit
                    is SteamMatchPickerState.Searching -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Text(stringResource(R.string.steam_match_searching))
                        }
                    }
                    is SteamMatchPickerState.Results -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(
                                items = state.candidates,
                                key = SteamCatalogCandidate::steamAppId,
                            ) { candidate ->
                                SteamMatchCandidateCard(
                                    candidate = candidate,
                                    selected = candidate.steamAppId == state.selectedSteamAppId,
                                    current = candidate.steamAppId == expected.candidateSteamAppId,
                                    onClick = { onSelectCandidate(candidate.steamAppId) },
                                )
                            }
                        }
                    }
                    is SteamMatchPickerState.Empty -> {
                        Text(
                            text = stringResource(R.string.steam_match_empty),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    is SteamMatchPickerState.Unavailable -> {
                        Text(
                            text = stringResource(R.string.steam_match_unavailable),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.testTag("steam-match-cancel"),
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        onClick = onConfirm,
                        enabled = selectedSteamAppId != null,
                        modifier = Modifier.testTag("steam-match-confirm"),
                    ) {
                        Text(stringResource(R.string.steam_match_confirm))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onKeepSeparate,
                        enabled = canKeepSeparate,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("steam-match-keep-separate"),
                    ) {
                        Text(stringResource(R.string.steam_match_keep_separate))
                    }
                    if (expected.decisionSource == MatchDecisionSource.USER) {
                        OutlinedButton(
                            onClick = onResetToAutomatic,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("steam-match-reset"),
                        ) {
                            Text(stringResource(R.string.steam_match_reset_automatic))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SteamMatchCandidateCard(
    candidate: SteamCatalogCandidate,
    selected: Boolean,
    current: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("steam-match-candidate:${candidate.steamAppId}"),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val artwork = candidate.headerImageUrl
            if (artwork.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .size(width = 112.dp, height = 52.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Steam", style = MaterialTheme.typography.labelMedium)
                }
            } else {
                SteamMediaImage(
                    imageUrl = artwork,
                    contentDescription = candidate.title,
                    modifier = Modifier.size(width = 112.dp, height = 52.dp),
                    contentScale = ContentScale.Crop,
                    sessionOnly = true,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = candidate.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.steam_match_candidate_details,
                        candidate.developer ?: stringResource(R.string.steam_match_candidate_unknown),
                        candidate.releaseYear?.toString()
                            ?: stringResource(R.string.steam_match_candidate_unknown),
                        candidate.appType.displayLabel(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (current) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.steam_match_current),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

private fun CanonicalAppType.displayLabel(): String = when (this) {
    CanonicalAppType.GAME -> "Game"
    CanonicalAppType.APPLICATION -> "Application"
    CanonicalAppType.TOOL -> "Tool"
    CanonicalAppType.DEMO -> "Demo"
    CanonicalAppType.DLC -> "DLC"
    CanonicalAppType.SOUNDTRACK -> "Soundtrack"
    CanonicalAppType.UNKNOWN -> "Unknown"
}
