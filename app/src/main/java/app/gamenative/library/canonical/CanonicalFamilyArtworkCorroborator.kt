package app.gamenative.library.canonical

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.EpicStableSourceId
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.canonical.artwork.ArtworkFeatureCache
import app.gamenative.library.canonical.artwork.ArtworkFeatureMatcher
import app.gamenative.library.canonical.artwork.ArtworkFeatureSource
import app.gamenative.library.canonical.artwork.ArtworkFeatures
import app.gamenative.library.canonical.artwork.ArtworkMatch
import app.gamenative.library.canonical.artwork.ArtworkReference
import app.gamenative.library.canonical.artwork.ArtworkUrlPolicy
import app.gamenative.library.canonical.artwork.ArtworkVisualIndex
import app.gamenative.library.canonical.artwork.CachedArtworkFeatureSource
import app.gamenative.library.canonical.runtime.OwnedCopyRuntime
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeRegistry
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeResult
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/** Sole visual-family owner: verified artwork adds presentation relationships, never catalog/copy mutations. */
@Singleton
class CanonicalFamilyArtworkCorroborator internal constructor(
    private val features: ArtworkFeatureSource,
    private val cache: ArtworkFeatureCache,
    private val matcher: ArtworkFeatureMatcher,
    private val lookupRuntime: suspend (OwnedCopyKey) -> OwnedCopyRuntimeResult,
    private val diagnostics: CanonicalLibraryDiagnosticSink = NoOpCanonicalLibraryDiagnosticSink,
) {
    @Inject constructor(features: CachedArtworkFeatureSource, cache: ArtworkFeatureCache,
        matcher: ArtworkFeatureMatcher, runtimes: OwnedCopyRuntimeRegistry, diagnostics: CanonicalLibraryDiagnosticSink) :
        this(features, cache, matcher, runtimes::resolve, diagnostics)

    private val matching = Mutex()

    /** Card/stat updates render immediately, but only artwork/membership/override changes replace the worker. */
    internal fun observe(cardsFlow: Flow<List<CanonicalLibraryCard>>): Flow<List<CanonicalLibraryCard>> = channelFlow {
        val state = Mutex()
        var latest = emptyList<CanonicalLibraryCard>()
        var generation: List<ArtworkInput>? = null
        var verified = emptySet<Pair<CanonicalGameId, CanonicalGameId>>()
        var worker: Job? = null
        try {
            cardsFlow.collect { cards ->
                val input = inputKey(cards)
                val changed = state.withLock {
                    latest = cards
                    val changed = input != generation
                    if (changed) { generation = input; verified = emptySet() }
                    send(projectCards(cards, verified))
                    changed
                }
                if (changed) {
                    worker?.cancelAndJoin()
                    worker = launch {
                        relationships(cards).collect { evidence ->
                            state.withLock {
                                if (generation == input) {
                                    verified = evidence
                                    send(projectCards(latest, evidence))
                                }
                            }
                        }
                    }
                }
            }
            worker?.join()
        } finally { worker?.cancel() }
    }.distinctUntilChanged()

    internal fun projectBatches(cards: List<CanonicalLibraryCard>): Flow<List<CanonicalLibraryCard>> =
        relationships(cards).map { projectCards(cards, it) }.distinctUntilChanged()

    suspend fun project(cards: List<CanonicalLibraryCard>): List<CanonicalLibraryCard> {
        var projected = cards
        projectBatches(cards).collect { projected = it }
        return projected
    }

    private data class ArtworkInput(
        val canonicalId: CanonicalGameId,
        val key: CanonicalCardKey,
        val eligible: Boolean,
        val members: Set<CanonicalGameId>,
        val copies: List<CopyInput>,
        val cardArtwork: List<ArtworkReference>,
    )
    private data class CopyInput(
        val key: OwnedCopyKey,
        val availability: List<Any?>,
        val manualDecision: List<Any?>,
        val artwork: List<ArtworkReference>,
    )
    private fun inputKey(cards: List<CanonicalLibraryCard>): List<ArtworkInput> = cards.sortedBy { it.canonicalId.value }.map { card ->
        ArtworkInput(card.canonicalId, card.key, CanonicalGameFamilyProjection.eligible(card),
            card.memberSteamAppIds.keys.ifEmpty { setOf(card.canonicalId) },
            card.copies.sortedBy { it.key.toString() }.map { copy ->
                val manual = if (copy.decisionSource == MatchDecisionSource.USER) listOf(copy.matchMethod,
                    copy.confidence, copy.decisionCandidateSteamAppId, copy.decisionResolverVersion, copy.decisionRevision)
                    else emptyList()
                CopyInput(copy.key, listOf(copy.source, copy.unavailableReason, copy.isHidden), manual, copy.originalArtwork)
            }, cardArtworkReferences(card))
    }

    private fun projectCards(cards: List<CanonicalLibraryCard>, evidence: Set<Pair<CanonicalGameId, CanonicalGameId>>): List<CanonicalLibraryCard> {
        val started = System.nanoTime()
        val projected = CanonicalGameFamilyProjection.project(cards, evidence)
        diagnostics.recordSafely {
            cardsProjected(projected.size, cards.size, cards.sumOf { it.copies.size }, (System.nanoTime() - started) / 1_000_000)
        }
        return projected
    }

    private suspend fun compare(left: ArtworkFeatures, right: ArtworkFeatures): ArtworkMatch {
        val started = System.nanoTime()
        val result = withContext(Dispatchers.Default) { matcher.compare(left, right) }
        currentCoroutineContext().ensureActive()
        diagnostics.recordSafely {
            artworkCompared(ArtworkPairDigest.from(left, right), result, (System.nanoTime() - started) / 1_000_000)
        }
        return result
    }

    private fun unavailable(source: GameSource, reason: ArtworkEvidenceFailure) {
        diagnostics.recordSafely { artworkUnavailable(source, reason) }
    }

    private data class Original(val canonicalId: CanonicalGameId, val summary: OwnedCopySummary,
        val runtime: OwnedCopyRuntime, val reference: ArtworkReference)

    private fun relationships(cards: List<CanonicalLibraryCard>): Flow<Set<Pair<CanonicalGameId, CanonicalGameId>>> = flow {
        if (cards.map { it.canonicalId }.distinct().size != cards.size ||
            cards.flatMap { it.copies }.map { it.key }.let { it.distinct().size != it.size }
        ) return@flow
        val originals = arrayListOf<Original>()
        for (card in cards.filter(CanonicalGameFamilyProjection::eligible).sortedBy { it.canonicalId.value }) {
            for (summary in card.copies.sortedBy { it.key.toString() }) {
                val runtime = currentCopy(summary.key, summary) ?: continue
                val references = artworkReferences(runtime)
                if (summary.originalArtwork.isNotEmpty() && summary.originalArtwork != references) {
                    unavailable(summary.source, ArtworkEvidenceFailure.STALE_NATIVE_COPY)
                    continue
                }
                (references + cardArtworkReferences(card)).distinct().forEach {
                    originals += Original(card.canonicalId, summary, runtime, it)
                }
            }
        }
        val byReference = originals.groupBy { it.reference }
        val contentByReference = linkedMapOf<ArtworkReference, String>()
        withContext(Dispatchers.IO) {
            byReference.keys.forEach { ref -> cache.contentHash(ref)?.let { contentByReference[ref] = it } }
        }
        fun project(accepted: Set<Pair<String, String>>): Set<Pair<CanonicalGameId, CanonicalGameId>> {
            val owners = linkedMapOf<String, MutableSet<CanonicalGameId>>()
            byReference.forEach { (reference, sources) -> contentByReference[reference]?.let { hash ->
                owners.getOrPut(hash, ::linkedSetOf).addAll(sources.map { it.canonicalId })
            } }
            val edges = linkedSetOf<Pair<CanonicalGameId, CanonicalGameId>>()
            for ((left, right) in accepted) {
                val leftIds = owners[left].orEmpty()
                val rightIds = owners[right].orEmpty()
                if (leftIds.isEmpty() || rightIds.isEmpty()) continue
                val members = (leftIds + rightIds).sortedBy { it.value }
                members.drop(1).forEach { edges += members.first() to it }
            }
            return Collections.unmodifiableSet(edges)
        }
        suspend fun revalidate(hashes: Set<String>) {
            for (original in originals) {
                if (contentByReference[original.reference] !in hashes) continue
                val refreshed = currentCopy(original.summary.key, original.summary)
                if (refreshed == null || !sameEvidence(original.runtime, refreshed)) {
                    contentByReference.remove(original.reference)
                    unavailable(original.summary.source, ArtworkEvidenceFailure.STALE_NATIVE_COPY)
                }
            }
        }
        val warm = withContext(Dispatchers.IO) { cache.acceptedPairs() }
        revalidate(warm.flatMap { listOf(it.first, it.second) }.toSet())
        emit(project(warm))
        matching.withLock {
            try {
                val index = ArtworkVisualIndex()
                for (hash in withContext(Dispatchers.IO) { cache.featureHashes() }) {
                    currentCoroutineContext().ensureActive()
                    withContext(Dispatchers.IO) { cache.feature(hash) }?.let { evidence ->
                        withContext(Dispatchers.Default) { index.add(evidence) }
                    }
                }
                for ((reference, sources) in byReference) {
                    currentCoroutineContext().ensureActive()
                    val image = try { withTimeoutOrNull(20_000) { features.original(reference) } }
                        catch (_: IOException) { null }
                    val validOwners = sources.filter { original ->
                        currentCopy(original.summary.key, original.summary)?.let { sameEvidence(original.runtime, it) } == true
                    }
                    if (image == null || !image.isValid() || validOwners.size != sources.size) {
                        unavailable(reference.source, if (validOwners.size != sources.size) ArtworkEvidenceFailure.STALE_NATIVE_COPY
                            else ArtworkEvidenceFailure.ORIGINAL_UNAVAILABLE)
                        contentByReference.remove(reference)
                        emit(project(withContext(Dispatchers.IO) { cache.acceptedPairs() }))
                        continue
                    }
                    withContext(Dispatchers.IO) {
                        if (cache.contentHash(reference) != image.contentHash || cache.feature(image.contentHash) == null) cache.put(reference, image)
                    }
                    contentByReference[reference] = image.contentHash
                    val pending = if (withContext(Dispatchers.IO) { cache.isIndexed(image.contentHash) }) emptyList()
                        else withContext(Dispatchers.Default) { index.candidates(image) }
                    var completed = true
                    for (other in pending) {
                        currentCoroutineContext().ensureActive()
                        if (withContext(Dispatchers.IO) { cache.comparison(image.contentHash, other) } != null) continue
                        val candidate = withContext(Dispatchers.IO) { cache.feature(other) } ?: continue
                        val result = compare(image, candidate)
                        currentCoroutineContext().ensureActive()
                        if (result.completed) withContext(Dispatchers.IO) { cache.putComparison(image.contentHash, other, result.accepted) }
                        else {
                            completed = false
                            unavailable(reference.source, ArtworkEvidenceFailure.NATIVE_MATCHER_UNAVAILABLE)
                        }
                    }
                    val identicalOwners = byReference.filterKeys { contentByReference[it] == image.contentHash }
                        .values.flatten().map { it.canonicalId }.distinct()
                    if (identicalOwners.size > 1 && withContext(Dispatchers.IO) { cache.comparison(image.contentHash, image.contentHash) } == null) {
                        val result = compare(image, image)
                        if (result.completed) withContext(Dispatchers.IO) { cache.putComparison(image.contentHash, image.contentHash, result.accepted) }
                        else {
                            completed = false
                            unavailable(reference.source, ArtworkEvidenceFailure.NATIVE_MATCHER_UNAVAILABLE)
                        }
                    }
                    withContext(Dispatchers.Default) { index.add(image) }
                    if (completed) withContext(Dispatchers.IO) { cache.markIndexed(image.contentHash) }
                    withContext(Dispatchers.IO) { cache.flush() }
                    val accepted = withContext(Dispatchers.IO) { cache.acceptedPairs() }
                    revalidate(accepted.filter { it.first == image.contentHash || it.second == image.contentHash }
                        .flatMap { listOf(it.first, it.second) }.toSet())
                    emit(project(accepted))
                    yield()
                }
            } finally { withContext(NonCancellable + Dispatchers.IO) { cache.flush() } }
        }
    }.distinctUntilChanged()

    private suspend fun currentCopy(key: OwnedCopyKey, summary: OwnedCopySummary): OwnedCopyRuntime? {
        val copy = (withTimeoutOrNull(20_000) { lookupRuntime(key) } as? OwnedCopyRuntimeResult.Available)?.copy
        if (copy == null) {
            unavailable(summary.source, ArtworkEvidenceFailure.NATIVE_COPY_UNAVAILABLE)
            return null
        }
        if (copy.key != key || copy.reference.key != key || summary.source != key.source || copy.isHidden || !exactNativeIdentity(copy)) {
            unavailable(summary.source, ArtworkEvidenceFailure.STALE_NATIVE_COPY)
            return null
        }
        return copy
    }

    private fun exactNativeIdentity(copy: OwnedCopyRuntime): Boolean {
        val key = copy.key
        val expectedId = when (val reference = copy.reference) {
            is SourceOwnedCopyReference.Steam -> {
                if (key.source != GameSource.STEAM || reference.appId <= 0 || "${reference.appId}" != key.stableSourceId) return false
                "STEAM_${reference.appId}"
            }
            is SourceOwnedCopyReference.Gog -> {
                if (key.source != GameSource.GOG || reference.gameId != key.stableSourceId) return false
                "GOG_${reference.gameId}"
            }
            is SourceOwnedCopyReference.Epic -> {
                if (key.source != GameSource.EPIC || reference.namespace.isBlank() || reference.catalogId.isBlank() ||
                    EpicStableSourceId.encode(reference.namespace, reference.catalogId) != key.stableSourceId) return false
                "EPIC_${reference.localRowId}"
            }
            is SourceOwnedCopyReference.Amazon -> {
                if (key.source != GameSource.AMAZON || reference.productId != key.stableSourceId || reference.entitlementId.isBlank()) return false
                "AMAZON_${reference.localRowId}"
            }
            is SourceOwnedCopyReference.Custom -> return false
        }
        val item = copy.libraryItem ?: return key.source == GameSource.GOG && key.stableSourceId.toIntOrNull() == null
        return item.gameSource == key.source && item.appId == expectedId
    }

    private fun sameEvidence(before: OwnedCopyRuntime, after: OwnedCopyRuntime): Boolean =
        before.key == after.key && before.reference == after.reference &&
            artworkReferences(before) == artworkReferences(after) &&
            before.libraryItem?.appId == after.libraryItem?.appId && before.libraryItem?.gameSource == after.libraryItem?.gameSource

    companion object {
        private val STEAM_ASSET_ROOT = Regex("^/(?:steam/apps|store_item_assets/steam/apps)/([1-9][0-9]*)/")

        /** Raw CDN images selected by the existing card generator; the asset ID grants no Steam ownership. */
        private fun cardArtworkReferences(card: CanonicalLibraryCard): List<ArtworkReference> =
            listOf(card.headerImageUrl, card.heroImageUrl).filter { it.isNotBlank() && it.length <= 2_048 }.mapNotNull { raw ->
                val url = raw.toHttpUrlOrNull() ?: return@mapNotNull null
                val id = STEAM_ASSET_ROOT.find(url.encodedPath)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                ArtworkUrlPolicy.Default.groupingUrl(GameSource.STEAM, raw, id)?.let {
                    ArtworkReference(GameSource.STEAM, it.toString(), id)
                }
            }.distinct()

        internal fun artworkReferences(copy: OwnedCopyRuntime): List<ArtworkReference> {
            val appId = (copy.reference as? SourceOwnedCopyReference.Steam)?.appId
            val references = listOf(copy.headerImageUrl, copy.heroImageUrl, copy.originalArtworkUrl.orEmpty(), copy.capsuleImageUrl)
                .filter(String::isNotBlank).mapNotNull { raw ->
                    ArtworkUrlPolicy.Default.groupingUrl(copy.key.source, raw, appId)?.let { ArtworkReference(copy.key.source, it.toString(), appId) }
                }.distinct()
            return Collections.unmodifiableList(references)
        }
    }
}
