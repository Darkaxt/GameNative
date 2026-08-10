package app.gamenative.library.canonical.catalog

import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalNormalization
import javax.inject.Inject
import kotlin.math.abs

class SteamCatalogCandidatePolicy @Inject constructor() {
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
            val candidateDeveloperKeys = sequenceOf(candidate.developer, candidate.publisher)
                .filterNotNull()
                .map(CanonicalNormalization::developerKey)
                .filter(String::isNotEmpty)
                .toSet()
            val developerMatches = sourceDeveloperKey.isNotEmpty() &&
                sourceDeveloperKey in candidateDeveloperKeys
            val yearMatches = source.releaseYear != null &&
                candidate.releaseYear != null &&
                abs(source.releaseYear - candidate.releaseYear) <= 1
            val developerConflicts = sourceDeveloperKey.isNotEmpty() &&
                candidateDeveloperKeys.isNotEmpty() &&
                sourceDeveloperKey !in candidateDeveloperKeys
            !developerConflicts && (developerMatches || yearMatches)
        }

        return when {
            corroborated.size == 1 -> CatalogDecision.AutoAccept(
                corroborated.single().steamAppId,
            )
            corroborated.size > 1 -> selectClosestPriorCandidate(
                sourceYear = source.releaseYear,
                candidates = corroborated,
            )
            else -> eligible.reviewRequired()
        }
    }

    private fun selectClosestPriorCandidate(
        sourceYear: Int?,
        candidates: List<SteamCatalogCandidate>,
    ): CatalogDecision {
        if (sourceYear == null) return candidates.reviewRequired()
        val eligible = candidates.filter { candidate ->
            candidate.releaseYear?.let { it <= sourceYear } == true
        }
        val closestYear = eligible.maxOfOrNull { requireNotNull(it.releaseYear) }
            ?: return candidates.reviewRequired()
        val closest = eligible.filter { it.releaseYear == closestYear }
        return closest.singleOrNull()
            ?.let { CatalogDecision.AutoAccept(it.steamAppId) }
            ?: candidates.reviewRequired()
    }

    private fun List<SteamCatalogCandidate>.reviewRequired() =
        CatalogDecision.ReviewRequired(map(SteamCatalogCandidate::steamAppId))
}
