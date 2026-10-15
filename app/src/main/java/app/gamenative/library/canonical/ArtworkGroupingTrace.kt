package app.gamenative.library.canonical

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.library.canonical.artwork.ArtworkFeatures
import app.gamenative.library.canonical.artwork.ArtworkReference
import app.gamenative.library.canonical.artwork.ArtworkUrlPolicy

class ArtworkGameTrace private constructor(
    val publicTitle: String,
    val source: GameSource,
    val correlationId: String,
    val initialMatchMethod: MatchMethod,
    val initialMatchConfidence: MatchConfidence,
) {
    companion object {
        internal fun from(card: CanonicalLibraryCard, generation: Long, ordinal: Int): ArtworkGameTrace? {
            val copy = card.copies.firstOrNull { it.source != GameSource.CUSTOM_GAME && it.nativeTitle.isNotBlank() }
                ?: return null
            return ArtworkGameTrace(copy.nativeTitle.take(120), copy.source, "$generation-$ordinal",
                copy.matchMethod, copy.confidence)
        }
    }
}

enum class ArtworkTraceStage {
    QUEUED, INELIGIBLE, INVALID_MEMBERSHIP, NATIVE_VALIDATING, NATIVE_UNAVAILABLE,
    STALE_NATIVE_EVIDENCE, ORIGINALS_ADMITTED, NO_ADMITTED_ORIGINAL, ORIGINAL_FETCHING,
    ORIGINAL_UNAVAILABLE, ORIGINAL_CACHE_BOUND, FEATURES_READY, SHORTLISTED, COMPARED, CACHE_HIT, NATIVE_MATCHER_UNAVAILABLE,
    FAMILY_PUBLISHED, FINISHED, CANCELLED, FAILED,
}

enum class ArtworkTraceMatchResult { ACCEPTED, REJECTED, UNAVAILABLE }
enum class FamilyGroupingReason { NONE, METADATA, TITLE, ARTWORK, TITLE_AND_ARTWORK }

class ArtworkTraceObservation private constructor(
    val stage: ArtworkTraceStage,
    val reference: ArtworkReference?,
    val content: String?,
    val pair: ArtworkPairDigest?,
    val matchResult: ArtworkTraceMatchResult?,
    val referenceCount: Int?,
    val candidateCount: Int?,
    val comparisonCount: Int?,
    val acceptedCount: Int?,
    val familySize: Int?,
    val groupingReason: FamilyGroupingReason?,
) {
    companion object {
        internal fun of(stage: ArtworkTraceStage, reference: ArtworkReference? = null,
            image: ArtworkFeatures? = null, pair: ArtworkPairDigest? = null, referenceCount: Int? = null,
            candidateCount: Int? = null, comparisonCount: Int? = null, acceptedCount: Int? = null,
            familySize: Int? = null, matchResult: ArtworkTraceMatchResult? = null,
            groupingReason: FamilyGroupingReason? = null,
            contentHash: String? = image?.contentHash): ArtworkTraceObservation {
            require(reference == null || ArtworkUrlPolicy.Default.groupingUrl(reference.source, reference.url, reference.steamAppId) != null)
            require(contentHash == null || ArtworkFeatures.HASH.matches(contentHash))
            require(matchResult == null || pair != null)
            return ArtworkTraceObservation(stage, reference, contentHash?.take(16), pair, matchResult,
                referenceCount, candidateCount, comparisonCount, acceptedCount, familySize, groupingReason)
        }
    }
}

internal class ArtworkTraceSession(val games: Map<CanonicalGameId, ArtworkGameTrace>) {
    private val publications = mutableMapOf<CanonicalGameId, Pair<Int, FamilyGroupingReason?>>()
    fun publicationChanged(id: CanonicalGameId, size: Int, reason: FamilyGroupingReason? = null): Boolean =
        publications.put(id, size to reason) != (size to reason)
}
