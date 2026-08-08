package app.gamenative.library.canonical.catalog

import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalNormalization
import kotlin.math.abs

class SteamCatalogCandidatePolicy {
    fun evaluate(
        source: SourceCatalogEvidence,
        candidates: List<SteamCatalogCandidate>,
    ): CatalogDecision {
        val uniqueCandidates = candidates
            .distinctBy(SteamCatalogCandidate::steamAppId)
            .sortedBy(SteamCatalogCandidate::steamAppId)
        if (uniqueCandidates.isEmpty()) return CatalogDecision.Unmatched

        val sourceTitleKey = CanonicalNormalization.titleKey(source.title)
        if (sourceTitleKey.isEmpty()) return CatalogDecision.Unmatched
        if (source.appType == CanonicalAppType.UNKNOWN) {
            return uniqueCandidates.reviewRequired()
        }

        val eligible = uniqueCandidates.filter { candidate ->
            candidate.appType == source.appType &&
                CanonicalNormalization.titleKey(candidate.title) == sourceTitleKey
        }
        if (eligible.isEmpty()) return uniqueCandidates.reviewRequired()

        val sourceDeveloperKey = source.developer
            ?.let(CanonicalNormalization::developerKey)
            .orEmpty()
        val corroborated = eligible.filter { candidate ->
            val candidateDeveloperKey = candidate.developer
                ?.let(CanonicalNormalization::developerKey)
                .orEmpty()
            val developerMatches = sourceDeveloperKey.isNotEmpty() &&
                candidateDeveloperKey == sourceDeveloperKey
            val yearMatches = source.releaseYear != null &&
                candidate.releaseYear != null &&
                abs(source.releaseYear - candidate.releaseYear) <= 1
            val developerConflicts = sourceDeveloperKey.isNotEmpty() &&
                candidateDeveloperKey.isNotEmpty() &&
                sourceDeveloperKey != candidateDeveloperKey
            val yearConflicts = source.releaseYear != null &&
                candidate.releaseYear != null &&
                abs(source.releaseYear - candidate.releaseYear) > 1
            !developerConflicts && !yearConflicts && (developerMatches || yearMatches)
        }

        return when {
            corroborated.size == 1 -> CatalogDecision.AutoAccept(
                corroborated.single().steamAppId,
            )
            corroborated.size > 1 -> corroborated.reviewRequired()
            else -> eligible.reviewRequired()
        }
    }

    private fun List<SteamCatalogCandidate>.reviewRequired() =
        CatalogDecision.ReviewRequired(map(SteamCatalogCandidate::steamAppId))
}
