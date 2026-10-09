package app.gamenative.library.canonical.catalog

internal fun selectSteamCatalogHits(
    query: String,
    hits: List<SteamStoreSearchHit>,
    complete: Boolean,
): SteamCatalogSearchResult {
    val titleKeys = SteamCatalogNormalization.titleKeys(query).map { it.value }
    val ordered = hits.distinctBy(SteamStoreSearchHit::steamAppId).sortedWith(
        compareBy { hit ->
            titleKeys.indexOf(SteamCatalogNormalization.titleKey(hit.title)).takeIf { it >= 0 }
                ?: Int.MAX_VALUE
        },
    )
    return SteamCatalogSearchResult(
        hits = ordered.take(10),
        complete = complete && ordered.size <= 10,
    )
}
