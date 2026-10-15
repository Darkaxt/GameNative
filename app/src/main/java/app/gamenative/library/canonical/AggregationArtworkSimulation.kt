package app.gamenative.library.canonical

import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.canonical.artwork.ArtworkFeatureCache
import app.gamenative.library.canonical.artwork.ArtworkFeatureMatcher
import app.gamenative.library.canonical.artwork.ArtworkFeatures
import app.gamenative.library.canonical.artwork.ArtworkMatch
import app.gamenative.library.canonical.artwork.ArtworkOriginalInspection
import app.gamenative.library.canonical.artwork.ArtworkReference
import app.gamenative.library.canonical.catalog.SteamCatalogNormalization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class AggregationOriginalCheck(
    val reference: ArtworkReference,
    val cachedContent: String?,
    val cachedFresh: Boolean,
    val current: ArtworkOriginalInspection?,
)

internal data class AggregationPairCheck(
    val left: ArtworkReference,
    val right: ArtworkReference,
    val cachedAccepted: Boolean?,
    val replay: ArtworkMatch?,
)

internal data class AggregationArtworkSimulation(
    val focused: List<CanonicalLibraryCard>,
    val references: Map<CanonicalGameId, List<ArtworkReference>>,
    val originals: List<AggregationOriginalCheck>,
    val pairs: List<AggregationPairCheck>,
    val cachedEdges: Set<Pair<CanonicalGameId, CanonicalGameId>>,
    val replayEdges: Set<Pair<CanonicalGameId, CanonicalGameId>>,
    val omittedEntries: Int,
    val omittedOriginals: Int,
    val omittedPairs: Int,
    val budgetExhausted: Boolean,
    val stale: Boolean,
)

/** Bounded replay using the real family graph and matcher, without writing their evidence. */
internal object AggregationArtworkSimulator {
    const val MAX_ENTRIES = 24
    const val MAX_ORIGINALS = 32
    const val MAX_PAIRS = 128
    const val BUDGET_MS = 60_000L

    suspend fun run(
        cards: List<CanonicalLibraryCard>,
        selectedKeys: Set<OwnedCopyKey>,
        cache: ArtworkFeatureCache,
        matcher: ArtworkFeatureMatcher,
        validate: suspend (OwnedCopySummary) -> Boolean,
        inspect: suspend (ArtworkReference) -> ArtworkOriginalInspection,
        budgetMs: Long = BUDGET_MS,
    ): AggregationArtworkSimulation = withContext(Dispatchers.Default) {
        require(budgetMs in 1..BUDGET_MS)
        if (selectedKeys.isEmpty() || !CanonicalGameFamilyProjection.relationships(cards, emptySet()).valid) {
            return@withContext AggregationArtworkSimulation(emptyList(), emptyMap(), emptyList(), emptyList(),
                emptySet(), emptySet(), 0, 0, 0, false, true)
        }
        val admitted = cards.filter(CanonicalGameFamilyProjection::eligible)
        val references = admitted.associate { card -> card.canonicalId to
            (card.copies.flatMap { it.originalArtwork } + CanonicalFamilyArtworkCorroborator.cardArtworkReferences(card)).distinct() }
        val cached = linkedMapOf<ArtworkReference, Pair<ArtworkFeatures?, Boolean>>()
        val hashes = linkedMapOf<ArtworkReference, String>()
        // Keep only digests for the library-wide graph; the cache retains its own bounded descriptor memory.
        withContext(Dispatchers.IO) {
            references.values.flatten().distinct().forEach { ref ->
                ensureActive()
                cache.contentHash(ref)?.let { hashes[ref] = it }
            }
        }
        val savedPairs = withContext(Dispatchers.IO) { cache.comparisonResults() }
        val cachedEdges = CanonicalFamilyArtworkCorroborator.edgesFromContent(references, hashes, savedPairs.filterValues { it }.keys)
        val selectedIds = cards.filter { card -> card.copies.any { it.key in selectedKeys } }.map { it.canonicalId }.toSet()
        val titleKeys = cards.filter { it.canonicalId in selectedIds }
            .map { SteamCatalogNormalization.titleKey(it.displayName) }.filter(String::isNotBlank).toSet()
        val projected = CanonicalGameFamilyProjection.project(cards, cachedEdges)
        val familyIds = projected.filter { card -> card.copies.any { it.key in selectedKeys } }
            .flatMap { it.memberSteamAppIds.keys.ifEmpty { setOf(it.canonicalId) } }.toSet()
        val relevant = cards.filter { it.canonicalId in selectedIds || it.canonicalId in familyIds ||
            SteamCatalogNormalization.titleKey(it.displayName) in titleKeys }
            .sortedWith(compareBy<CanonicalLibraryCard> { if (it.canonicalId in selectedIds) 0 else 1 }.thenBy { it.canonicalId.value })
        val focused = relevant.take(MAX_ENTRIES)
        val focusReferences = focused.flatMap { references[it.canonicalId].orEmpty() }.distinct()
        val retainedReferences = focusReferences.take(MAX_ORIGINALS)
        withContext(Dispatchers.IO) {
            retainedReferences.forEach { ref ->
                val image = cache.original(ref, freshOnly = false)
                cached[ref] = image to (cache.original(ref) != null)
            }
        }
        val checks = linkedMapOf<ArtworkReference, ArtworkOriginalInspection>()
        val pairs = arrayListOf<AggregationPairCheck>()
        val replayHashes = linkedMapOf<ArtworkReference, String>()
        val acceptedReplay = linkedSetOf<Pair<String, String>>()
        val owners = references.entries.flatMap { (id, refs) -> refs.map { it to id } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
        val focusedIds = focused.filter(CanonicalGameFamilyProjection::eligible).mapTo(hashSetOf()) { it.canonicalId }
        // Production also tests one original against itself when multiple entries own it.
        val candidatePairs = retainedReferences.filter { ref -> owners[ref].orEmpty().count { it in focusedIds } > 1 }
            .map { it to it } + retainedReferences.flatMapIndexed { index, left ->
                retainedReferences.drop(index + 1).filter { right ->
                    owners[left].orEmpty().any { a -> owners[right].orEmpty().any { b -> a != b } }
                }.map { left to it }
            }
        var stale = false
        val completed = withTimeoutOrNull(budgetMs) {
            val validIds = linkedSetOf<CanonicalGameId>()
            for (card in focused.filter(CanonicalGameFamilyProjection::eligible)) {
                if (card.copies.all { validate(it) }) validIds += card.canonicalId else stale = true
            }
            for (reference in retainedReferences) {
                ensureActive()
                if (owners[reference].orEmpty().none { it in validIds }) continue
                val result = inspect(reference)
                checks[reference] = result
                result.features?.takeIf(ArtworkFeatures::isValid)?.let { replayHashes[reference] = it.contentHash }
            }
            for ((left, right) in candidatePairs.take(MAX_PAIRS)) {
                ensureActive()
                val leftImage = checks[left]?.features?.takeIf(ArtworkFeatures::isValid)
                val rightImage = checks[right]?.features?.takeIf(ArtworkFeatures::isValid)
                val result = if (leftImage != null && rightImage != null) matcher.compare(leftImage, rightImage) else null
                val saved = hashes[left]?.let { a -> hashes[right]?.let { b -> savedPairs[ordered(a, b)] } }
                pairs += AggregationPairCheck(left, right, saved, result)
                if (result?.completed == true && result.accepted) acceptedReplay += ordered(leftImage!!.contentHash, rightImage!!.contentHash)
            }
            // Revalidate after acquisition and native matching; never explain a retired native binding as current.
            for (card in focused.filter { it.canonicalId in validIds }) {
                if (!card.copies.all { validate(it) }) stale = true
            }
            true
        } == true
        // Retain cached verdicts even when the bounded fresh pass could not reach their pair.
        for ((left, right) in candidatePairs.take(MAX_PAIRS).drop(pairs.size)) {
            val saved = hashes[left]?.let { a -> hashes[right]?.let { b -> savedPairs[ordered(a, b)] } }
            pairs += AggregationPairCheck(left, right, saved, null)
        }
        AggregationArtworkSimulation(focused, references,
            retainedReferences.map { ref -> AggregationOriginalCheck(ref, cached[ref]?.first?.contentHash,
                cached[ref]?.second == true, checks[ref]) }, pairs, cachedEdges,
            CanonicalFamilyArtworkCorroborator.edgesFromContent(references, replayHashes, acceptedReplay),
            relevant.size - focused.size, focusReferences.size - retainedReferences.size,
            (candidatePairs.size - MAX_PAIRS).coerceAtLeast(0), !completed, stale)
    }

    private fun ordered(left: String, right: String) = if (left <= right) left to right else right to left
}
