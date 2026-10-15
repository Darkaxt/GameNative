package app.gamenative.library.canonical

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.EpicStableSourceId
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.canonical.ArtworkSchedulerPhase as SchedulerPhase
import app.gamenative.library.canonical.ArtworkSchedulerStage as SchedulerStage
import app.gamenative.library.canonical.artwork.ArtworkFeatureCache
import app.gamenative.library.canonical.artwork.ArtworkFeatureMatcher
import app.gamenative.library.canonical.artwork.ArtworkFeatureSource
import app.gamenative.library.canonical.artwork.ArtworkFeatures
import app.gamenative.library.canonical.artwork.ArtworkMatch
import app.gamenative.library.canonical.artwork.ArtworkReference
import app.gamenative.library.canonical.artwork.ArtworkUrlPolicy
import app.gamenative.library.canonical.artwork.ArtworkVisualIndex
import app.gamenative.library.canonical.artwork.CachedArtworkFeatureSource
import app.gamenative.library.canonical.artwork.ArtworkOriginalInspection
import app.gamenative.library.canonical.artwork.ArtworkAcquisitionStage
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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
    private val inspectOriginal: suspend (ArtworkReference) -> ArtworkOriginalInspection = { ref ->
        ArtworkOriginalInspection(ref, cache.original(ref, freshOnly = false), ArtworkAcquisitionStage.DESCRIPTOR_CACHE_HIT)
    },
) {
    @Inject constructor(features: CachedArtworkFeatureSource, cache: ArtworkFeatureCache,
        matcher: ArtworkFeatureMatcher, runtimes: OwnedCopyRuntimeRegistry, diagnostics: CanonicalLibraryDiagnosticSink) :
        this(features, cache, matcher, runtimes::resolve, diagnostics, features::inspectOriginal)

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
        val acquisitions = PendingAcquisition(this)
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
                    acquisitions.retain(input.toSet())
                    worker = launch {
                        relationships(cards, acquisitions).collect { evidence ->
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
        } finally {
            withContext(NonCancellable) {
                worker?.cancelAndJoin()
                acquisitions.close()
            }
        }
    }.distinctUntilChanged()

    internal fun projectBatches(cards: List<CanonicalLibraryCard>): Flow<List<CanonicalLibraryCard>> =
        relationships(cards).map { projectCards(cards, it.edges, it.trace) }.distinctUntilChanged()

    suspend fun project(cards: List<CanonicalLibraryCard>): List<CanonicalLibraryCard> {
        var projected = cards
        projectBatches(cards).collect { projected = it }
        return projected
    }

    internal suspend fun simulate(cards: List<CanonicalLibraryCard>, selectedKeys: Set<OwnedCopyKey>): AggregationArtworkSimulation =
        AggregationArtworkSimulator.run(cards, selectedKeys, cache, matcher, validate = { summary ->
            val runtime = currentCopy(summary.key, summary, diagnose = false)
            runtime != null && (summary.originalArtwork.isEmpty() || summary.originalArtwork == artworkReferences(runtime))
        }, inspect = inspectOriginal)

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
        val relationships = CanonicalGameFamilyProjection.relationships(cards, evidence)
        diagnostics.recordSafely {
            cardsProjected(projected.size, cards.size, cards.sumOf { it.copies.size }, (System.nanoTime() - started) / 1_000_000)
            if (trace != null) for (family in projected) {
                val members = family.memberSteamAppIds.keys.ifEmpty { setOf(family.canonicalId) }
                val reason = relationships.reason(members, family.copies.size)
                for (id in members) {
                    val game = trace.games[id] ?: continue
                    if (trace.publicationChanged(id, members.size, reason)) artworkState(game,
                        ArtworkTraceObservation.of(ArtworkTraceStage.FAMILY_PUBLISHED, familySize = members.size,
                            groupingReason = reason))
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

    private class PendingOriginal(val reference: ArtworkReference, var owners: Set<ArtworkInput>,
        val work: Deferred<ArtworkFeatures?>)
    private data class FailedOriginal(val owners: Set<ArtworkInput>, val atNanos: Long)

    /** One observation-owned acquisition survives unrelated matching generations; no descriptor backlog. */
    private inner class PendingAcquisition(private val scope: CoroutineScope) {
        private val lock = Mutex()
        private var pending: PendingOriginal? = null
        private val failures = mutableMapOf<ArtworkReference, FailedOriginal>()

        suspend fun retain(inputs: Set<ArtworkInput>) {
            val eligible = inputs.filterTo(hashSetOf()) { it.eligible }.takeIf { it.size >= 2 }.orEmpty()
            val cancelled = lock.withLock {
                failures.entries.removeAll { (_, failure) ->
                    failure.owners.none { it in eligible } || System.nanoTime() - failure.atNanos >= ORIGINAL_RETRY_NANOS
                }
                pending?.let { current ->
                    current.owners = current.owners.intersect(eligible)
                    if (current.owners.isEmpty()) { pending = null; current.work } else null
                }
            }
            cancelled?.cancelAndJoin()
        }

        suspend fun failedReferences(): Set<ArtworkReference> = lock.withLock { failures.keys.toSet() }

        suspend fun original(reference: ArtworkReference, owners: Set<ArtworkInput>): ArtworkFeatures? {
            while (true) {
                val current = lock.withLock {
                    failures[reference]?.let { failure ->
                        if (System.nanoTime() - failure.atNanos < ORIGINAL_RETRY_NANOS) return null
                        failures.remove(reference)
                    }
                    pending ?: PendingOriginal(reference, owners, scope.async {
                        this@CanonicalFamilyArtworkCorroborator.original(reference)
                    }).also { pending = it }
                }
                val image = current.work.await()
                currentCoroutineContext().ensureActive()
                lock.withLock {
                    if (pending === current) {
                        pending = null
                        if (image == null) failures[current.reference] = FailedOriginal(current.owners, System.nanoTime())
                    }
                }
                // A different earlier request may have survived cancellation. Its raw cache work is reusable,
                // but its result grants no authority to the current reference or native owners.
                if (current.reference == reference) return image
            }
        }

        suspend fun close() {
            val work = lock.withLock { pending?.work.also { pending = null; failures.clear() } }
            work?.cancelAndJoin()
        }
    }

    private suspend fun original(reference: ArtworkReference): ArtworkFeatures? =
        try { withTimeoutOrNull(20_000) { features.original(reference) } } catch (_: IOException) { null }

    private data class Original(val canonicalId: CanonicalGameId, val summary: OwnedCopySummary,
        val runtime: OwnedCopyRuntime, val reference: ArtworkReference)
    private data class AcquiredOriginal(val reference: ArtworkReference, val image: ArtworkFeatures?, val stale: Boolean, val rank: Int)

    private fun relationships(cards: List<CanonicalLibraryCard>, acquisitions: PendingAcquisition? = null): Flow<Evidence> = flow {
        val generation = traceGeneration.incrementAndGet()
        val scheduler = ArtworkSchedulerTrace(generation, diagnostics)
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
            // No inter-canonical visual edge is possible without a peer; title/metadata projection still runs.
            if (eligible.size < 2) {
                emit(Evidence(emptySet(), trace))
                eligible.forEach { state(it.canonicalId, ArtworkTraceObservation.of(ArtworkTraceStage.FINISHED,
                    comparisonCount = 0, acceptedCount = 0)) }
                scheduler.end(SchedulerStage.FINISHED)
                return@flow
            }
            scheduler.preparing(SchedulerPhase.NATIVE_VALIDATION)
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
            val referencesByCanonical = originals.groupBy({ it.canonicalId }, { it.reference })
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
            fun rememberComparison(left: String, right: String, result: ArtworkTraceMatchResult) {
                val hashes = if (left <= right) left to right else right to left
                knownComparisons.getOrPut(left, ::linkedMapOf)[hashes] = result
                knownComparisons.getOrPut(right, ::linkedMapOf)[hashes] = result
            }
            suspend fun compared(left: String, right: String, accepted: Boolean, cached: Boolean, completed: Boolean = true) {
                currentCoroutineContext().ensureActive()
                val result = if (!completed) ArtworkTraceMatchResult.UNAVAILABLE else if (accepted)
                    ArtworkTraceMatchResult.ACCEPTED else ArtworkTraceMatchResult.REJECTED
                rememberComparison(left, right, result)
                val ids = comparisonOwners[left].orEmpty() + comparisonOwners[right].orEmpty()
                val pair = ArtworkPairDigest.fromHashes(left, right)
                for (id in ids) {
                    currentCoroutineContext().ensureActive()
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
            suspend fun replayComparisons(content: String, acceptedOnly: Boolean = false) {
                for ((index, entry) in knownComparisons[content].orEmpty().toList().withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val (pair, result) = entry
                    if (!acceptedOnly || result == ArtworkTraceMatchResult.ACCEPTED) {
                        compared(pair.first, pair.second, result == ArtworkTraceMatchResult.ACCEPTED, cached = true,
                            completed = result != ArtworkTraceMatchResult.UNAVAILABLE)
                    }
                    if (index % 32 == 31) yield()
                }
            }
            scheduler.preparing(SchedulerPhase.LOAD_BINDINGS)
            withContext(Dispatchers.IO) {
                byReference.keys.forEach { ref -> cache.contentHash(ref)?.let { contentByReference[ref] = it } }
            }
            fun project(accepted: Set<Pair<String, String>>): Set<Pair<CanonicalGameId, CanonicalGameId>> =
                edgesFromContent(referencesByCanonical, contentByReference, accepted)
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
            scheduler.preparing(SchedulerPhase.LOAD_COMPARISONS)
            val savedComparisons = withContext(Dispatchers.IO) { cache.comparisonResults() }
            val warm = savedComparisons.filterValues { it }.keys
            scheduler.preparing(SchedulerPhase.WARM_REVALIDATION)
            revalidate(warm.flatMap { listOf(it.first, it.second) }.toSet())
            byReference.forEach { (reference, sources) -> contentByReference[reference]?.let { content ->
                sources.map { it.canonicalId }.distinct().forEach { id ->
                    state(id, ArtworkTraceObservation.of(ArtworkTraceStage.ORIGINAL_CACHE_BOUND,
                        reference = reference, contentHash = content))
                }
            } }
            comparisonOwners = contentOwners()
            // Prime evidence without draining historical rejection diagnostics before missing originals can run.
            scheduler.preparing(SchedulerPhase.PRIME_COMPARISONS)
            for ((pair, accepted) in savedComparisons) {
                currentCoroutineContext().ensureActive()
                rememberComparison(pair.first, pair.second, if (accepted) ArtworkTraceMatchResult.ACCEPTED else ArtworkTraceMatchResult.REJECTED)
            }
            for (pair in warm) compared(pair.first, pair.second, accepted = true, cached = true)
            emit(Evidence(project(warm), trace))
            val failed = acquisitions?.failedReferences().orEmpty()
            val acquisitionOrder = byReference.entries.sortedWith(compareBy(
                { contentByReference.containsKey(it.key) }, { it.key in failed }))
            scheduler.scheduled(acquisitionOrder.size)
            acquisitionOrder.forEachIndexed { index, (reference, sources) ->
                currentCoroutineContext().ensureActive()
                val cacheState = when {
                    contentByReference.containsKey(reference) && reference in failed -> ArtworkScheduleCacheState.WARM_COOLDOWN
                    contentByReference.containsKey(reference) -> ArtworkScheduleCacheState.WARM
                    reference in failed -> ArtworkScheduleCacheState.MISSING_COOLDOWN
                    else -> ArtworkScheduleCacheState.MISSING
                }
                sources.map { it.canonicalId }.distinct().forEach { id ->
                    state(id, ArtworkTraceObservation.of(ArtworkTraceStage.ORIGINAL_SCHEDULED, reference,
                        referenceCount = acquisitionOrder.size, acquisitionRank = index + 1, scheduleCacheState = cacheState))
                }
            }
            val inputs = inputKey(cards).associateBy { it.canonicalId }
            scheduler.preparing(SchedulerPhase.LOAD_FEATURE_HASHES)
            val indexedHashes = withContext(Dispatchers.IO) { cache.featureHashes() }
            scheduler.preparing(SchedulerPhase.DONE)
            coroutineScope {
                // Bound queued descriptors, not just cache-write failures: cache eviction cannot lose queued work.
                val acquired = Channel<AcquiredOriginal>(64)
                val fetching = launch {
                    try {
                        for ((index, entry) in acquisitionOrder.withIndex()) {
                            val (reference, sources) = entry
                            val rank = index + 1
                            currentCoroutineContext().ensureActive()
                            scheduler.producing(SchedulerPhase.ACQUIRE, rank)
                            sources.map { it.canonicalId }.distinct().forEach { stage(it, ArtworkTraceStage.ORIGINAL_FETCHING, reference) }
                            val owners = sources.map { inputs.getValue(it.canonicalId) }.toSet()
                            val image = if (acquisitions == null) original(reference) else acquisitions.original(reference, owners)
                            currentCoroutineContext().ensureActive()
                            scheduler.producing(SchedulerPhase.NATIVE_REVALIDATION, rank)
                            val stale = sources.any { source ->
                                currentCopy(source.summary.key, source.summary)?.let { sameEvidence(source.runtime, it) } != true
                            }
                            val usable = image?.takeIf { it.isValid() && !stale }
                            if (usable == null) {
                                unavailable(reference.source, if (stale) ArtworkEvidenceFailure.STALE_NATIVE_COPY
                                    else ArtworkEvidenceFailure.ORIGINAL_UNAVAILABLE)
                                sources.map { it.canonicalId }.distinct().forEach { stage(it,
                                    if (stale) ArtworkTraceStage.STALE_NATIVE_EVIDENCE else ArtworkTraceStage.ORIGINAL_UNAVAILABLE, reference) }
                            } else {
                                scheduler.producing(SchedulerPhase.CACHE_WRITE, rank)
                                withContext(Dispatchers.IO) {
                                    if (cache.contentHash(reference) != usable.contentHash || cache.feature(usable.contentHash) == null)
                                        cache.put(reference, usable)
                                }
                                currentCoroutineContext().ensureActive()
                                sources.map { it.canonicalId }.distinct().forEach { stage(it, ArtworkTraceStage.FEATURES_READY, reference, usable) }
                            }
                            scheduler.producing(SchedulerPhase.QUEUE_SEND, rank)
                            acquired.send(AcquiredOriginal(reference, usable, stale, rank))
                            scheduler.enqueued(rank)
                        }
                        scheduler.producing(SchedulerPhase.DONE, acquisitionOrder.size)
                    } catch (error: Exception) {
                        acquired.close(error)
                        throw error
                    } finally { acquired.close() }
                }
                try {
                    scheduler.consuming(SchedulerPhase.MATCHING_LOCK)
                    matching.withLock {
                        try {
                            scheduler.consuming(SchedulerPhase.INDEX)
                            val index = ArtworkVisualIndex()
                            scheduler.indexed(0, indexedHashes.size)
                            for ((position, hash) in indexedHashes.withIndex()) {
                                currentCoroutineContext().ensureActive()
                                withContext(Dispatchers.IO) { cache.feature(hash) }?.let { evidence ->
                                    withContext(Dispatchers.Default) { index.add(evidence) }
                                }
                                scheduler.indexed(position + 1, indexedHashes.size)
                            }
                            scheduler.consuming(SchedulerPhase.RECEIVE)
                            for (result in acquired) {
                                scheduler.received(result.rank)
                                currentCoroutineContext().ensureActive()
                                val reference = result.reference
                                val sources = byReference.getValue(reference)
                                val image = result.image
                                // Acquisition and queue residence confer no lasting native authority.
                                val stale = result.stale || sources.any { source ->
                                    currentCopy(source.summary.key, source.summary)?.let { sameEvidence(source.runtime, it) } != true
                                }
                                if (image == null || stale) {
                                    if (stale && !result.stale) {
                                        unavailable(reference.source, ArtworkEvidenceFailure.STALE_NATIVE_COPY)
                                        sources.map { it.canonicalId }.distinct().forEach { stage(it, ArtworkTraceStage.STALE_NATIVE_EVIDENCE, reference) }
                                    }
                                    contentByReference.remove(reference)
                                    scheduler.consuming(SchedulerPhase.ACCEPTED_PAIRS)
                                    val accepted = withContext(Dispatchers.IO) { cache.acceptedPairs() }
                                    scheduler.consuming(SchedulerPhase.PUBLISH)
                                    emit(Evidence(project(accepted), trace))
                                    scheduler.completed()
                                    scheduler.consuming(SchedulerPhase.RECEIVE)
                                    continue
                                }
                                contentByReference[reference] = image.contentHash
                                comparisonOwners = contentOwners()
                                scheduler.consuming(SchedulerPhase.REPLAY_COMPARISONS)
                                replayComparisons(image.contentHash, acceptedOnly = true)
                                scheduler.consuming(SchedulerPhase.SHORTLIST)
                                val indexed = withContext(Dispatchers.IO) { cache.isIndexed(image.contentHash) }
                                val pending = if (indexed) emptyList()
                                    else withContext(Dispatchers.Default) { index.candidates(image) }
                                sources.map { it.canonicalId }.distinct().forEach { stage(it,
                                    if (indexed) ArtworkTraceStage.CACHE_HIT else ArtworkTraceStage.SHORTLISTED,
                                    reference, image, candidateCount = pending.size) }
                                var completed = true
                                for (other in pending) {
                                    currentCoroutineContext().ensureActive()
                                    scheduler.consuming(SchedulerPhase.SHORTLIST)
                                    val cached = withContext(Dispatchers.IO) { cache.comparison(image.contentHash, other) }
                                    val candidate = withContext(Dispatchers.IO) { cache.feature(other) }
                                    if (candidate == null) {
                                        completed = false
                                        continue
                                    }
                                    if (cached != null) {
                                        compared(image.contentHash, candidate.contentHash, cached, true)
                                        continue
                                    }
                                    scheduler.consuming(SchedulerPhase.COMPARE)
                                    val match = compare(image, candidate)
                                    compared(image.contentHash, candidate.contentHash, match.accepted, false, match.completed)
                                    currentCoroutineContext().ensureActive()
                                    if (match.completed) withContext(Dispatchers.IO) { cache.putComparison(image.contentHash, other, match.accepted) }
                                    else {
                                        completed = false
                                        unavailable(reference.source, ArtworkEvidenceFailure.NATIVE_MATCHER_UNAVAILABLE)
                                    }
                                }
                                val identicalOwners = byReference.filterKeys { contentByReference[it] == image.contentHash }
                                    .values.flatten().map { it.canonicalId }.distinct()
                                if (identicalOwners.size > 1 && withContext(Dispatchers.IO) { cache.comparison(image.contentHash, image.contentHash) } == null) {
                                    scheduler.consuming(SchedulerPhase.COMPARE)
                                    val match = compare(image, image)
                                    compared(image.contentHash, image.contentHash, match.accepted, false, match.completed)
                                    if (match.completed) withContext(Dispatchers.IO) { cache.putComparison(image.contentHash, image.contentHash, match.accepted) }
                                    else {
                                        completed = false
                                        unavailable(reference.source, ArtworkEvidenceFailure.NATIVE_MATCHER_UNAVAILABLE)
                                    }
                                }
                                scheduler.consuming(SchedulerPhase.INDEX_WRITE)
                                withContext(Dispatchers.Default) { index.add(image) }
                                if (completed) withContext(Dispatchers.IO) { cache.markIndexed(image.contentHash) }
                                scheduler.consuming(SchedulerPhase.CACHE_FLUSH)
                                withContext(Dispatchers.IO) { cache.flush() }
                                scheduler.consuming(SchedulerPhase.ACCEPTED_PAIRS)
                                val accepted = withContext(Dispatchers.IO) { cache.acceptedPairs() }
                                scheduler.consuming(SchedulerPhase.NATIVE_REVALIDATION)
                                revalidate(accepted.filter { it.first == image.contentHash || it.second == image.contentHash }
                                    .flatMap { listOf(it.first, it.second) }.toSet())
                                scheduler.consuming(SchedulerPhase.PUBLISH)
                                emit(Evidence(project(accepted), trace))
                                scheduler.completed()
                                scheduler.consuming(SchedulerPhase.RECEIVE)
                                yield()
                            }
                            scheduler.consuming(SchedulerPhase.DONE)
                        } finally { withContext(NonCancellable + Dispatchers.IO) { cache.flush() } }
                    }
                } finally {
                    acquired.cancel()
                    fetching.cancelAndJoin()
                }
            }
            // Preserve cached rejection explanations and late owner attribution after acquisition/publication progress.
            scheduler.preparing(SchedulerPhase.REPLAY_COMPARISONS)
            comparisonOwners = contentOwners()
            for (content in comparisonOwners.keys) replayComparisons(content)
            eligible.forEach { state(it.canonicalId, ArtworkTraceObservation.of(ArtworkTraceStage.FINISHED,
                comparisonCount = comparisons[it.canonicalId].orEmpty().size, acceptedCount = acceptances[it.canonicalId].orEmpty().size)) }
            scheduler.end(SchedulerStage.FINISHED)
        } catch (error: kotlinx.coroutines.CancellationException) {
            scheduler.end(SchedulerStage.CANCELLED)
            eligible.forEach { stage(it.canonicalId, ArtworkTraceStage.CANCELLED) }
            throw error
        } catch (error: Exception) {
            scheduler.end(SchedulerStage.FAILED)
            eligible.forEach { stage(it.canonicalId, ArtworkTraceStage.FAILED) }
            throw error
        }
    }.distinctUntilChanged()

    private suspend fun currentCopy(key: OwnedCopyKey, summary: OwnedCopySummary,
        diagnose: Boolean = true, reportFailure: (ArtworkEvidenceFailure) -> Unit = {}): OwnedCopyRuntime? {
        val copy = (withTimeoutOrNull(20_000) { lookupRuntime(key) } as? OwnedCopyRuntimeResult.Available)?.copy
        if (copy == null) {
            if (diagnose) unavailable(summary.source, ArtworkEvidenceFailure.NATIVE_COPY_UNAVAILABLE)
            reportFailure(ArtworkEvidenceFailure.NATIVE_COPY_UNAVAILABLE)
            return null
        }
        if (copy.key != key || copy.reference.key != key || summary.source != key.source || copy.isHidden || !exactNativeIdentity(copy)) {
            if (diagnose) unavailable(summary.source, ArtworkEvidenceFailure.STALE_NATIVE_COPY)
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
        private const val ORIGINAL_RETRY_NANOS = 60_000_000_000L
        private val STEAM_ASSET_ROOT = Regex("^/(?:steam/apps|store_item_assets/steam/apps)/([1-9][0-9]*)/")

        internal fun edgesFromContent(references: Map<CanonicalGameId, List<ArtworkReference>>,
            contentByReference: Map<ArtworkReference, String>, accepted: Set<Pair<String, String>>): Set<Pair<CanonicalGameId, CanonicalGameId>> {
            val owners = linkedMapOf<String, MutableSet<CanonicalGameId>>()
            references.forEach { (id, refs) -> refs.forEach { reference -> contentByReference[reference]?.let { hash ->
                owners.getOrPut(hash, ::linkedSetOf).add(id)
            } } }
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

        /** Raw CDN images selected by the existing card generator; the asset ID grants no Steam ownership. */
        internal fun cardArtworkReferences(card: CanonicalLibraryCard): List<ArtworkReference> =
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
