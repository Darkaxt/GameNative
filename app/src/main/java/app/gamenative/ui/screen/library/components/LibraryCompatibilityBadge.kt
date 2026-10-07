package app.gamenative.ui.screen.library.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.gamenative.data.CommunityCompatibilityVerdict
import app.gamenative.data.GameCompatibilityStatus
import app.gamenative.ui.component.CommunityCompatibilityBadge
import app.gamenative.ui.component.CompatibilityBadge
import app.gamenative.ui.data.LibraryCard

@Composable
internal fun LibraryCompatibilityBadge(card: LibraryCard, modifier: Modifier = Modifier) {
    if (card.isRecommended) {
        CompatibilityBadge(status = GameCompatibilityStatus.RECOMMENDED, showLabel = true, modifier = modifier)
    } else {
        val summary = card.communityCompatibility
        CommunityCompatibilityBadge(
            verdict = summary?.verdict ?: CommunityCompatibilityVerdict.UNKNOWN,
            verdictLoaded = summary?.verdictLoaded == true,
            loadFailed = summary?.loadFailed == true,
            checking = summary?.isChecking == true,
            showLabel = true,
            modifier = modifier,
        )
    }
}
