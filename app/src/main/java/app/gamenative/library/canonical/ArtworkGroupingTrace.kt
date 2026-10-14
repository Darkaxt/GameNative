package app.gamenative.library.canonical

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.library.canonical.artwork.ArtworkFeatures
import app.gamenative.library.canonical.artwork.ArtworkReference
import app.gamenative.library.canonical.artwork.ArtworkUrlPolicy

class ArtworkGameTrace private constructor(val publicTitle: String, val source: GameSource, val correlationId: String) {
    companion object {
        internal fun from(card: CanonicalLibraryCard, generation: Long, ordinal: Int): ArtworkGameTrace? {
            val copy = card.copies.firstOrNull { it.source != GameSource.CUSTOM_GAME && it.nativeTitle.isNotBlank() }
                ?: return null
            return ArtworkGameTrace(copy.nativeTitle.take(120), copy.source, "$generation-$ordinal")
        }
    }
}

enum class ArtworkTraceStage {
    QUEUED, INELIGIBLE, INVALID_MEMBERSHIP, NATIVE_VALIDATING, NATIVE_UNAVAILABLE,
    STALE_NATIVE_EVIDENCE, ORIGINALS_ADMITTED, NO_ADMITTED_ORIGINAL, ORIGINAL_FETCHING,
    ORIGINAL_UNAVAILABLE, FEATURES_READY, SHORTLISTED, COMPARED, CACHE_HIT, NATIVE_MATCHER_UNAVAILABLE,
    FAMILY_PUBLISHED, FINISHED, CANCELLED, FAILED,
}

enum class ArtworkTraceMatchResult { ACCEPTED, REJECTED, UNAVAILABLE }

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
) {
    companion object {
        internal fun of(stage: ArtworkTraceStage, reference: ArtworkReference? = null,
            image: ArtworkFeatures? = null, pair: ArtworkPairDigest? = null, referenceCount: Int? = null,
            candidateCount: Int? = null, comparisonCount: Int? = null, acceptedCount: Int? = null,
            familySize: Int? = null, matchResult: ArtworkTraceMatchResult? = null): ArtworkTraceObservation {
            require(reference == null || ArtworkUrlPolicy.Default.groupingUrl(reference.source, reference.url, reference.steamAppId) != null)
            require(image == null || ArtworkFeatures.HASH.matches(image.contentHash))
            require(matchResult == null || pair != null)
            return ArtworkTraceObservation(stage, reference, image?.contentHash?.take(16), pair, matchResult,
                referenceCount, candidateCount, comparisonCount, acceptedCount, familySize)
        }
    }
}

internal class ArtworkTraceSession(val games: Map<CanonicalGameId, ArtworkGameTrace>) {
    private val publishedSizes = mutableMapOf<CanonicalGameId, Int>()
    fun publicationChanged(id: CanonicalGameId, size: Int): Boolean = publishedSizes.put(id, size) != size
}
