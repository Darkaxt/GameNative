package app.gamenative.library.canonical.catalog

import app.gamenative.data.canonical.CanonicalAppType

data class SourceCatalogEvidence(
    val title: String,
    val developer: String?,
    val releaseYear: Int?,
    val appType: CanonicalAppType,
)

data class SteamCatalogCandidate(
    val steamAppId: Int,
    val title: String,
    val developer: String?,
    val releaseYear: Int?,
    val appType: CanonicalAppType,
    val headerImageUrl: String?,
    val publisher: String? = null,
) {
    init {
        require(steamAppId > 0) { "Steam AppID must be positive" }
    }
}

sealed interface CatalogDecision {
    data class AutoAccept(val steamAppId: Int) : CatalogDecision

    data class ReviewRequired(val steamAppIds: List<Int>) : CatalogDecision

    data object Unmatched : CatalogDecision
}
