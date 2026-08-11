package app.gamenative.library.canonical.catalog

import app.gamenative.data.canonical.CanonicalAppType
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

class SteamCatalogCandidatePolicy @Inject constructor() {
    fun evaluate(
        source: SourceCatalogEvidence,
        candidates: List<SteamCatalogCandidate>,
    ): CatalogDecision {
        val ranked = ranked(source, candidates).let { scored ->
            if (source.appType == CanonicalAppType.GAME) {
                scored.filter(ScoredCandidate::typeCompatible)
            } else {
                scored
            }
        }
        if (ranked.isEmpty()) return CatalogDecision.Unmatched

        val ambiguity = resolvePriorYearAmbiguity(source, ranked)
        if (ambiguity.forceReview) return ambiguity.ranked.reviewRequired()
        val decisionCandidates = if (ambiguity.resolved) {
            ambiguity.ranked.take(1)
        } else {
            ambiguity.ranked
        }
        return selectDecision(decisionCandidates)
    }

    fun rankCandidates(
        source: SourceCatalogEvidence,
        candidates: List<SteamCatalogCandidate>,
    ): List<SteamCatalogCandidate> = ranked(source, candidates)
        .let { scored ->
            if (source.appType == CanonicalAppType.GAME) {
                scored.filter(ScoredCandidate::typeCompatible)
            } else {
                scored
            }
        }
        .map(ScoredCandidate::candidate)

    private fun ranked(
        source: SourceCatalogEvidence,
        candidates: List<SteamCatalogCandidate>,
    ): List<ScoredCandidate> {
        val bestById = linkedMapOf<Int, ScoredCandidate>()
        candidates.forEach { candidate ->
            val scored = score(source, candidate)
            val existing = bestById[candidate.steamAppId]
            if (existing == null || scored.score > existing.score) {
                bestById[candidate.steamAppId] = scored
            }
        }
        return bestById.values.sortedWith(
            compareByDescending<ScoredCandidate>(ScoredCandidate::score)
                .thenBy { it.candidate.steamAppId },
        )
    }

    private fun score(
        source: SourceCatalogEvidence,
        candidate: SteamCatalogCandidate,
    ): ScoredCandidate {
        val candidateTitleKey = SteamCatalogNormalization.titleKey(candidate.title)
        val titleMatch = SteamCatalogNormalization.titleKeys(source.title)
            .firstOrNull { key -> key.value.isNotEmpty() && key.value == candidateTitleKey }
            ?.match
        val titleWeight = when (titleMatch) {
            CatalogTitleMatch.EXACT -> 0.56
            CatalogTitleMatch.SAFE_ALIAS_EXACT -> 0.53
            null -> 0.0
        }

        val sourceDeveloperKey = SteamCatalogNormalization.developerKey(source.developer)
        val candidateDeveloperKeys = sequenceOf(candidate.developer, candidate.publisher)
            .map(SteamCatalogNormalization::developerKey)
            .filter(String::isNotEmpty)
            .toSet()
        val developerExact = sourceDeveloperKey.isNotEmpty() &&
            sourceDeveloperKey in candidateDeveloperKeys
        val developerWeight = if (developerExact) 0.20 else 0.0

        val typeCompatible = source.appType == CanonicalAppType.GAME &&
            candidate.appType == CanonicalAppType.GAME
        val strongCrossStoreIdentity = titleMatch == CatalogTitleMatch.EXACT &&
            developerExact &&
            typeCompatible
        val yearDelta = source.releaseYear?.let { sourceYear ->
            candidate.releaseYear?.let { candidateYear -> abs(sourceYear - candidateYear) }
        }
        val yearWeight = when {
            yearDelta == 0 -> 0.14
            yearDelta == 1 -> 0.10
            yearDelta != null &&
                strongCrossStoreIdentity &&
                requireNotNull(candidate.releaseYear) > requireNotNull(source.releaseYear) -> 0.0
            yearDelta != null -> -0.10
            else -> 0.0
        }
        val typeWeight = if (typeCompatible) 0.10 else 0.0

        val sourceEditions = SteamCatalogNormalization.editionTokens(source.title)
        val candidateEditions = SteamCatalogNormalization.editionTokens(candidate.title)
        val editionConflict = sourceEditions != candidateEditions &&
            (sourceEditions.isNotEmpty() || candidateEditions.isNotEmpty())
        val sourceEditionBase = SteamCatalogNormalization.editionBaseTitle(source.title)
        val editionBaseMatch = sourceEditionBase.isNotEmpty() &&
            sourceEditionBase == SteamCatalogNormalization.editionBaseTitle(candidate.title)

        return ScoredCandidate(
            candidate = candidate,
            score = rounded((titleWeight + developerWeight + yearWeight + typeWeight).coerceIn(0.0, 1.0)),
            strongTitle = titleMatch != null,
            corroborated = developerWeight > 0.0 || yearWeight > 0.0,
            typeCompatible = typeCompatible,
            developerExact = developerExact,
            editionConflict = editionConflict,
            editionBaseMatch = editionBaseMatch,
            yearWeight = yearWeight,
        )
    }

    private fun resolvePriorYearAmbiguity(
        source: SourceCatalogEvidence,
        ranked: List<ScoredCandidate>,
    ): AmbiguityResolution {
        val family = ranked.filter { candidate ->
            candidate.typeCompatible &&
                candidate.developerExact &&
                (candidate.strongTitle || candidate.editionBaseMatch)
        }
        if (family.size < 2) return AmbiguityResolution(ranked)
        val sourceYear = source.releaseYear
            ?: return AmbiguityResolution(ranked, forceReview = true)
        val eligible = family
            .filter { candidate ->
                candidate.strongTitle &&
                    !candidate.editionConflict &&
                    candidate.candidate.releaseYear?.let { it <= sourceYear } == true
            }
            .sortedWith(
                compareByDescending<ScoredCandidate> { it.candidate.releaseYear }
                    .thenBy { it.candidate.steamAppId },
            )
        if (eligible.isEmpty()) return AmbiguityResolution(ranked, forceReview = true)
        val closestYear = eligible.first().candidate.releaseYear
        val closest = eligible.filter { it.candidate.releaseYear == closestYear }
        if (closest.size != 1) return AmbiguityResolution(ranked, forceReview = true)

        val original = closest.single()
        val selected = if (original.yearWeight == -0.10) {
            original.copy(score = rounded((original.score + 0.10).coerceAtMost(1.0)))
        } else {
            original
        }
        val eligibleIds = eligible.mapTo(mutableSetOf()) { it.candidate.steamAppId }
        return AmbiguityResolution(
            ranked = buildList {
                add(selected)
                addAll(eligible.filterNot { it.candidate.steamAppId == selected.candidate.steamAppId })
                addAll(ranked.filterNot { it.candidate.steamAppId in eligibleIds })
            },
            resolved = true,
        )
    }

    private fun selectDecision(ranked: List<ScoredCandidate>): CatalogDecision {
        if (ranked.isEmpty()) return CatalogDecision.Unmatched
        val top = ranked.first()
        val margin = if (ranked.size == 1) {
            1.0
        } else {
            rounded(top.score - ranked[1].score)
        }
        val canAccept = top.typeCompatible &&
            top.score >= 0.80 &&
            top.strongTitle &&
            top.corroborated &&
            margin >= 0.08 &&
            !top.editionConflict
        return when {
            canAccept -> CatalogDecision.AutoAccept(top.candidate.steamAppId)
            top.score >= 0.62 ||
                top.strongTitle ||
                (top.editionConflict && top.editionBaseMatch) -> ranked.reviewRequired()
            else -> CatalogDecision.Unmatched
        }
    }

    private fun List<ScoredCandidate>.reviewRequired() =
        CatalogDecision.ReviewRequired(map { it.candidate.steamAppId })

    private fun rounded(value: Double): Double = (value * 10_000).roundToInt() / 10_000.0

    private data class ScoredCandidate(
        val candidate: SteamCatalogCandidate,
        val score: Double,
        val strongTitle: Boolean,
        val corroborated: Boolean,
        val typeCompatible: Boolean,
        val developerExact: Boolean,
        val editionConflict: Boolean,
        val editionBaseMatch: Boolean,
        val yearWeight: Double,
    )

    private data class AmbiguityResolution(
        val ranked: List<ScoredCandidate>,
        val resolved: Boolean = false,
        val forceReview: Boolean = false,
    )
}
