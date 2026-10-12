package app.gamenative.ui.screen.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.dlc.DlcOwnership
import app.gamenative.library.dlc.DlcState
import app.gamenative.ui.screen.library.components.DetailSection

internal data class DlcCopyOption(val key: OwnedCopyKey, val nativeTitle: String, val sourceLabel: String)

@Composable
internal fun CanonicalDlcSection(
    state: DlcState,
    copies: List<DlcCopyOption>,
    selectedCopy: OwnedCopyKey?,
    onSelectCopy: (OwnedCopyKey) -> Unit,
    onRetry: () -> Unit,
) {
    val boundCopy = selectedCopy?.takeIf { key -> copies.any { it.key == key } }
    val currentState = when {
        boundCopy == null -> DlcState.Idle
        state is DlcState.Content && state.key != boundCopy -> DlcState.Loading
        else -> state
    }
    DetailSection(Modifier.testTag("canonical-detail-dlc")) {
        Text(stringResource(R.string.canonical_detail_dlc), style = MaterialTheme.typography.titleMedium)
        copies.forEachIndexed { index, copy ->
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("canonical-dlc-copy:$index")
                    .selectable(selected = copy.key == boundCopy, role = Role.RadioButton, onClick = { onSelectCopy(copy.key) })
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(
                    selected = copy.key == boundCopy,
                    onClick = null,
                    modifier = Modifier.testTag("canonical-dlc-copy-radio:$index"),
                )
                Text(
                    stringResource(R.string.canonical_dlc_copy_label, copy.nativeTitle, copy.sourceLabel, copy.key.stableSourceId),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        when (currentState) {
            DlcState.Idle -> Text(stringResource(R.string.canonical_dlc_select_copy))
            DlcState.Loading -> Text(stringResource(R.string.canonical_dlc_loading))
            DlcState.Unavailable -> Text(stringResource(R.string.canonical_dlc_unavailable))
            is DlcState.Content -> {
                if (currentState.items.isEmpty() && currentState.catalogComplete) {
                    Text(stringResource(R.string.canonical_dlc_empty))
                }
                currentState.items.forEach { item ->
                    Column(
                        modifier = Modifier.fillMaxWidth().testTag("canonical-dlc-item:${item.id}"),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(item.title ?: stringResource(R.string.canonical_dlc_product, item.id))
                        Text(stringResource(when (item.ownership) {
                            DlcOwnership.OWNED -> R.string.canonical_dlc_owned
                            DlcOwnership.NOT_OWNED -> R.string.canonical_dlc_not_owned
                            DlcOwnership.UNKNOWN -> R.string.canonical_detail_support_unknown
                        }))
                    }
                }
                if (!currentState.catalogComplete) Text(stringResource(R.string.canonical_dlc_partial_catalog))
                if (!currentState.entitlementsComplete) Text(stringResource(R.string.canonical_dlc_partial_ownership))
            }
        }
        if (currentState == DlcState.Unavailable ||
            currentState is DlcState.Content && (!currentState.catalogComplete || !currentState.entitlementsComplete)
        ) {
            OutlinedButton(onClick = onRetry, modifier = Modifier.testTag("canonical-dlc-retry")) {
                Text(stringResource(R.string.canonical_detail_retry))
            }
        }
    }
}
