package app.gamenative.ui.screen.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.ui.data.LibraryCard

@Composable
internal fun OwnedSourceBadges(
    sources: List<GameSource>,
    modifier: Modifier = Modifier,
    iconSize: Int = 12,
) {
    val orderedSources = LibraryCard.OWNED_SOURCE_ORDER.filter(sources.toSet()::contains)
    Row(
        modifier = modifier
            .testTag("owned-source-badges")
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(horizontal = 3.dp, vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        orderedSources.forEach { source ->
            GameSourceIcon(
                gameSource = source,
                modifier = Modifier.testTag("owned-source-badge:${source.name}"),
                iconSize = iconSize,
                alignmentBoxSize = iconSize + 6,
                contentDescription = stringResource(
                    R.string.canonical_owned_source_description,
                    sourceLabel(source),
                ),
            )
        }
    }
}

@Composable
internal fun sourceLabel(source: GameSource): String = stringResource(
    when (source) {
        GameSource.STEAM -> R.string.tab_steam
        GameSource.GOG -> R.string.tab_gog
        GameSource.EPIC -> R.string.tab_epic
        GameSource.AMAZON -> R.string.tab_amazon
        GameSource.CUSTOM_GAME -> R.string.tab_local
    },
)
