package app.gamenative.ui.screen.library.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.library.discovery.GameFacet
import app.gamenative.library.discovery.SteamTagFacet

@Composable
fun LibraryActiveFilterChips(
    genreFacets: List<GameFacet> = emptyList(),
    selectedGenreKeys: Set<String> = emptySet(),
    onRemoveGenre: (String) -> Unit = {},
    tagFacets: List<SteamTagFacet> = emptyList(),
    selectedTagIds: Set<Int> = emptySet(),
    onRemoveTag: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tagFacetsById = tagFacets.associateBy(SteamTagFacet::tagId)
    val selectedTagFacets = selectedTagIds.mapNotNull(tagFacetsById::get)
    if (selectedGenreKeys.isEmpty() && selectedTagFacets.isEmpty()) return
    val facetsByKey = genreFacets.associateBy(GameFacet::key)
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        selectedGenreKeys.map { key -> key to facetsByKey[key] }
            .sortedWith(compareBy({ it.second?.label?.lowercase().orEmpty() }, { it.first }))
            .forEach { (key, facet) ->
                val label = facet?.label ?: stringResource(R.string.genre_unknown)
                InputChip(
                    selected = true,
                    onClick = { onRemoveGenre(key) },
                    label = { Text(label) },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.genre_remove, label),
                        )
                    },
                    modifier = Modifier.testTag("genre-chip:$key"),
                )
            }
        selectedTagFacets
            .sortedWith(
                compareBy<SteamTagFacet> { it.label.lowercase() }
                    .thenBy(SteamTagFacet::label)
                    .thenBy(SteamTagFacet::tagId),
            )
            .forEach { facet ->
                InputChip(
                    selected = true,
                    onClick = { onRemoveTag(facet.tagId) },
                    label = { Text(facet.label) },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.steam_tags_remove, facet.label),
                        )
                    },
                    modifier = Modifier.testTag("steam-tag-chip:${facet.tagId}"),
                )
            }
    }
}
