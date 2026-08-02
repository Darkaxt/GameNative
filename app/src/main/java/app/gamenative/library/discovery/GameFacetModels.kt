package app.gamenative.library.discovery

import java.util.Collections

/** A stable catalog facet key and its sanitized presentation label. */
data class GameFacet(
    val key: String,
    val label: String,
)

data class SteamTagFacet(
    val tagId: Int,
    val label: String,
)

enum class TagMatchMode { ANY, ALL }

data class DiscoveryFilterState(
    val selectedGenreKeys: Set<String> = emptySet(),
    val selectedTagIds: Set<Int> = emptySet(),
    val tagMatchMode: TagMatchMode = TagMatchMode.ANY,
)

internal fun immutableGenreKeys(values: Collection<String>): Set<String> =
    if (values.isEmpty()) emptySet()
    else Collections.unmodifiableSet(values.toSortedSet())

internal fun immutableTagIds(values: Collection<Int>): Set<Int> {
    val sorted = values.asSequence().filter { it > 0 }.distinct().sorted().toCollection(linkedSetOf())
    return if (sorted.isEmpty()) emptySet() else Collections.unmodifiableSet(sorted)
}

internal fun matchesTags(
    cardTags: Set<Int>,
    selected: Set<Int>,
    mode: TagMatchMode,
): Boolean = selected.isEmpty() || when (mode) {
    TagMatchMode.ANY -> selected.any(cardTags::contains)
    TagMatchMode.ALL -> selected.all(cardTags::contains)
}
