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
import java.util.concurrent.atomic.AtomicLong
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
    private val traceGeneration = AtomicLong()
    private data class Evidence(val edges: Set<Pair<CanonicalGameId, CanonicalGameId>>, val trace: ArtworkTraceSession)

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
                                    verified = evidence.edges
                                    send(projectCards(latest, evidence.edges, evidence.trace))
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
        relationships(cards).map { projectCards(cards, it.edges, it.trace) }.distinctUntilChanged()

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

    private fun projectCards(cards: List<CanonicalLibraryCard>, evidence: Set<Pair<CanonicalGameId, CanonicalGameId>>,
        trace: ArtworkTraceSession? = null): List<CanonicalLibraryCard> {
        val started = System.nanoTime()
        val projected = CanonicalGameFamilyProjection.project(cards, evidence)
        diagnostics.recordSafely {
            cardsProjected(projected.size, cards.size, cards.sumOf { it.copies.size }, (System.nanoTime() - started) / 1_000_000)
            if (trace != null) for (family in projected) {
                val members = family.memberSteamAppIds.keys.ifEmpty { setOf(family.canonicalId) }
                for (id in members) {
                    val game = trace.games[id] ?: continue
                    if (trace.publicationChanged(id, members.size)) artworkState(game,
                        ArtworkTraceObservation.of(ArtworkTraceStage.FAMILY_PUBLISHED, familySize = members.size))
                }
            }
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

    private fun relationships(cards: List<CanonicalLibraryCard>): Flow<Evidence> = flow {
        val generation = traceGeneration.incrementAndGet()
        val trace = ArtworkTraceSession(cards.mapIndexedNotNull { index, card ->
            ArtworkGameTrace.from(card, generation, index)?.let { card.canonicalId to it }
        }.toMap())
        fun state(id: CanonicalGameId, observation: ArtworkTraceObservation) {
            trace.games[id]?.let { game -> diagnostics.recordSafely { artworkState(game, observation) } }
        }
        fun stage(id: CanonicalGameId, value: ArtworkTraceStage, reference: ArtworkReference? = null,
            image: ArtworkFeatures? = null, referenceCount: Int? = null, candidateCount: Int? = null) =
            state(id, ArtworkTraceObservation.of(value, reference, image, referenceCount = referenceCount, candidateCount = candidateCount))
        val eligible = cards.filter(CanonicalGameFamilyProjection::eligible).sortedBy { it.canonicalId.value }
        cards.forEach { stage(it.canonicalId, if (CanonicalGameFamilyProjection.eligible(it)) ArtworkTraceStage.QUEUED else ArtworkTraceStage.INELIGIBLE) }
        if (cards.map { it.canonicalId }.distinct().size != cards.size ||
            cards.flatMap { it.copies }.map { it.key }.let { it.distinct().size != it.size }
        ) {
            cards.forEach { stage(it.canonicalId, ArtworkTraceStage.INVALID_MEMBERSHIP) }
            return@flow
        }
        try {
            val originals = arrayListOf<Original>()
            for (card in eligible) {
                stage(card.canonicalId, ArtworkTraceStage.NATIVE_VALIDATING)
                for (summary in card.copies.sortedBy { it.key.toString() }) {
                    val runtime = currentCopy(summary.key, summary) { reason ->
                        stage(card.canonicalId, if (reason == ArtworkEvidenceFailure.NATIVE_COPY_UNAVAILABLE)
                            ArtworkTraceStage.NATIVE_UNAVAILABLE else ArtworkTraceStage.STALE_NATIVE_EVIDENCE)
                    } ?: continue
                    val references = artworkReferences(runtime)
                    if (summary.originalArtwork.isNotEmpty() && summary.originalArtwork != references) {
                        unavailable(summary.source, ArtworkEvidenceFailure.STALE_NATIVE_COPY)
                        stage(card.canonicalId, ArtworkTraceStage.STALE_NATIVE_EVIDENCE)
                        continue
                    }
                    (references + cardArtworkReferences(card)).distinct().forEach {
                        originals += Original(card.canonicalId, summary, runtime, it)
                    }
                }
            }
            val byReference = originals.groupBy { it.reference }
            eligible.forEach { card ->
                val refs = originals.filter { it.canonicalId == card.canonicalId }.map { it.reference }.distinct()
                stage(card.canonicalId, if (refs.isEmpty()) ArtworkTraceStage.NO_ADMITTED_ORIGINAL else ArtworkTraceStage.ORIGINALS_ADMITTED,
                    referenceCount = refs.size)
            }
            val comparisons = mutableMapOf<CanonicalGameId, MutableSet<String>>()
            val acceptances = mutableMapOf<CanonicalGameId, MutableSet<String>>()
            val contentByReference = linkedMapOf<ArtworkReference, String>()
            fun contentOwners(): Map<String, Set<CanonicalGameId>> {
                val owners = linkedMapOf<String, MutableSet<CanonicalGameId>>()
                byReference.forEach { (reference, sources) -> contentByReference[reference]?.let { hash ->
                    owners.getOrPut(hash, ::linkedSetOf).addAll(sources.map { it.canonicalId })
                } }
                return owners
            }
            var comparisonOwners = emptyMap<String, Set<CanonicalGameId>>()
            val knownComparisons = mutableMapOf<String, MutableMap<Pair<String, String>, ArtworkTraceMatchResult>>()
            val tracedComparisons = mutableMapOf<CanonicalGameId, MutableSet<String>>()
            fun compared(left: String, right: String, accepted: Boolean, cached: Boolean, completed: Boolean = true) {
                val result = if (!completed) ArtworkTraceMatchResult.UNAVAILABLE else if (accepted)
                    ArtworkTraceMatchResult.ACCEPTED else ArtworkTraceMatchResult.REJECTED
                val hashes = if (left <= right) left to right else right to left
                knownComparisons.getOrPut(left, ::linkedMapOf)[hashes] = result
                knownComparisons.getOrPut(right, ::linkedMapOf)[hashes] = result
                val ids = comparisonOwners[left].orEmpty() + comparisonOwners[right].orEmpty()
                val pair = ArtworkPairDigest.fromHashes(left, right)
                for (id in ids) {
                    val firstObservation = tracedComparisons.getOrPut(id, ::linkedSetOf).add("${pair.value}:${result.name}")
                    if (cached && !firstObservation) continue
                    if (!cached) comparisons.getOrPut(id, ::linkedSetOf).add(pair.value)
                    if (accepted) acceptances.getOrPut(id, ::linkedSetOf).add(pair.value)
                    state(id, ArtworkTraceObservation.of(if (!completed) ArtworkTraceStage.NATIVE_MATCHER_UNAVAILABLE
                        else if (cached) ArtworkTraceStage.CACHE_HIT else ArtworkTraceStage.COMPARED,
                        pair = pair, matchResult = result,
                        comparisonCount = comparisons[id].orEmpty().size, acceptedCount = acceptances[id].orEmpty().size))
                }
            }
            fun replayComparisons(content: String) {
                knownComparisons[content].orEmpty().toList().forEach { (pair, result) ->
                    compared(pair.first, pair.second, result == ArtworkTraceMatchResult.ACCEPTED, cached = true,
                        completed = result != ArtworkTraceMatchResult.UNAVAILABLE)
                }
            }
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
                        stage(original.canonicalId, ArtworkTraceStage.STALE_NATIVE_EVIDENCE, original.reference)
                    }
                }
            }
            val savedComparisons = withContext(Dispatchers.IO) { cache.comparisonResults() }
            val warm = savedComparisons.filterValues { it }.keys
            revalidate(warm.flatMap { listOf(it.first, it.second) }.toSet())
            comparisonOwners = contentOwners()
            for ((pair, accepted) in savedComparisons) compared(pair.first, pair.second, accepted, true)
            emit(Evidence(project(warm), trace))
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
                        sources.map { it.canonicalId }.distinct().forEach { stage(it, ArtworkTraceStage.ORIGINAL_FETCHING, reference) }
                        val image = try { withTimeoutOrNull(20_000) { features.original(reference) } }
                            catch (_: IOException) { null }
                        val validOwners = sources.filter { original ->
                            currentCopy(original.summary.key, original.summary)?.let { sameEvidence(original.runtime, it) } == true
                        }
                        if (image == null || !image.isValid() || validOwners.size != sources.size) {
                            unavailable(reference.source, if (validOwners.size != sources.size) ArtworkEvidenceFailure.STALE_NATIVE_COPY
                                else ArtworkEvidenceFailure.ORIGINAL_UNAVAILABLE)
                            sources.map { it.canonicalId }.distinct().forEach { stage(it,
                                if (validOwners.size != sources.size) ArtworkTraceStage.STALE_NATIVE_EVIDENCE else ArtworkTraceStage.ORIGINAL_UNAVAILABLE,
                                reference) }
                            contentByReference.remove(reference)
                            emit(Evidence(project(withContext(Dispatchers.IO) { cache.acceptedPairs() }), trace))
                            continue
                        }
                        withContext(Dispatchers.IO) {
                            if (cache.contentHash(reference) != image.contentHash || cache.feature(image.contentHash) == null) cache.put(reference, image)
                        }
                        contentByReference[reference] = image.contentHash
                        comparisonOwners = contentOwners()
                        sources.map { it.canonicalId }.distinct().forEach { stage(it, ArtworkTraceStage.FEATURES_READY, reference, image) }
                        replayComparisons(image.contentHash)
                        val indexed = withContext(Dispatchers.IO) { cache.isIndexed(image.contentHash) }
                        val pending = if (indexed) emptyList()
                            else withContext(Dispatchers.Default) { index.candidates(image) }
                        sources.map { it.canonicalId }.distinct().forEach { stage(it,
                            if (indexed) ArtworkTraceStage.CACHE_HIT else ArtworkTraceStage.SHORTLISTED,
                            reference, image, candidateCount = pending.size) }
                        var completed = true
                        for (other in pending) {
                            currentCoroutineContext().ensureActive()
                            val cached = withContext(Dispatchers.IO) { cache.comparison(image.contentHash, other) }
                            val candidate = withContext(Dispatchers.IO) { cache.feature(other) } ?: continue
                            if (cached != null) {
                                compared(image.contentHash, candidate.contentHash, cached, true)
                                continue
                            }
                            val result = compare(image, candidate)
                            compared(image.contentHash, candidate.contentHash, result.accepted, false, result.completed)
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
                            compared(image.contentHash, image.contentHash, result.accepted, false, result.completed)
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
                        emit(Evidence(project(accepted), trace))
                        yield()
                    }
                } finally { withContext(NonCancellable + Dispatchers.IO) { cache.flush() } }
            }
            eligible.forEach { state(it.canonicalId, ArtworkTraceObservation.of(ArtworkTraceStage.FINISHED,
                comparisonCount = comparisons[it.canonicalId].orEmpty().size, acceptedCount = acceptances[it.canonicalId].orEmpty().size)) }
        } catch (error: kotlinx.coroutines.CancellationException) {
            eligible.forEach { stage(it.canonicalId, ArtworkTraceStage.CANCELLED) }
            throw error
        } catch (error: Exception) {
            eligible.forEach { stage(it.canonicalId, ArtworkTraceStage.FAILED) }
            throw error
        }
    }.distinctUntilChanged()

    private suspend fun currentCopy(key: OwnedCopyKey, summary: OwnedCopySummary,
        reportFailure: (ArtworkEvidenceFailure) -> Unit = {}): OwnedCopyRuntime? {
        val copy = (withTimeoutOrNull(20_000) { lookupRuntime(key) } as? OwnedCopyRuntimeResult.Available)?.copy
        if (copy == null) {
            unavailable(summary.source, ArtworkEvidenceFailure.NATIVE_COPY_UNAVAILABLE)
            reportFailure(ArtworkEvidenceFailure.NATIVE_COPY_UNAVAILABLE)
            return null
        }
        if (copy.key != key || copy.reference.key != key || summary.source != key.source || copy.isHidden || !exactNativeIdentity(copy)) {
            unavailable(summary.source, ArtworkEvidenceFailure.STALE_NATIVE_COPY)
            reportFailure(ArtworkEvidenceFailure.STALE_NATIVE_COPY)
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
