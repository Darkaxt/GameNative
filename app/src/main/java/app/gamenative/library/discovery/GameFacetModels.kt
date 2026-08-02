package app.gamenative.library.discovery

import java.util.Collections

/** A stable catalog facet key and its sanitized presentation label. */
data class GameFacet(
    val key: String,
    val label: String,
)

data class DiscoveryFilterState(
    val selectedGenreKeys: Set<String> = emptySet(),
)

internal fun immutableGenreKeys(values: Collection<String>): Set<String> =
    if (values.isEmpty()) emptySet()
    else Collections.unmodifiableSet(values.toSortedSet())
