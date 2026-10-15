package app.gamenative.library.canonical

internal enum class ArtworkSchedulerStage { RUNNING, FINISHED, CANCELLED, FAILED }
internal enum class ArtworkSchedulerPhase {
    IDLE, NATIVE_VALIDATION, LOAD_BINDINGS, LOAD_COMPARISONS, WARM_REVALIDATION, PRIME_COMPARISONS,
    LOAD_FEATURE_HASHES, ACQUIRE, NATIVE_REVALIDATION, CACHE_WRITE, QUEUE_SEND, DONE,
    MATCHING_LOCK, INDEX, RECEIVE, SHORTLIST, COMPARE, INDEX_WRITE, CACHE_FLUSH, ACCEPTED_PAIRS,
    PUBLISH, REPLAY_COMPARISONS,
}

/** Only bounded counts, phases and monotonic durations cross the shared-work diagnostic boundary. */
class ArtworkSchedulerSnapshot internal constructor(
    internal val generation: Long,
    internal val stage: ArtworkSchedulerStage,
    internal val preparation: ArtworkSchedulerPhase,
    internal val producer: ArtworkSchedulerPhase,
    internal val consumer: ArtworkSchedulerPhase,
    internal val producerRank: Int,
    internal val consumerRank: Int,
    internal val total: Int,
    internal val enqueued: Int,
    internal val dequeued: Int,
    internal val completed: Int,
    internal val queueSize: Int,
    internal val queueHighWater: Int,
    internal val indexProgress: Int,
    internal val indexTotal: Int,
    internal val elapsedMs: Long,
    internal val preparationElapsedMs: Long,
    internal val producerElapsedMs: Long,
    internal val consumerElapsedMs: Long,
)

/**
 * Observes the existing producer/consumer; it never selects work or grants native evidence authority.
 * Queue counts are acknowledged transfers minus observed receives, not Channel internals: a handoff
 * can briefly precede the consumer callback. QUEUE_SEND is a phase start, not a verdict of blockage.
 */
internal class ArtworkSchedulerTrace(private val generation: Long, private val diagnostics: CanonicalLibraryDiagnosticSink) {
    private val lock = Any()
    private val started = System.nanoTime()
    private var stage = ArtworkSchedulerStage.RUNNING
    private var preparation = ArtworkSchedulerPhase.IDLE
    private var producer = ArtworkSchedulerPhase.IDLE
    private var consumer = ArtworkSchedulerPhase.IDLE
    private var preparationAt = started
    private var producerAt = started
    private var consumerAt = started
    private var producerRank = 0
    private var consumerRank = 0
    private var total = 0
    private var enqueued = 0
    private var dequeued = 0
    private var completed = 0
    private var highWater = 0
    private var indexProgress = 0
    private var indexTotal = 0

    fun preparing(value: ArtworkSchedulerPhase) = update {
        preparation = value
        preparationAt = System.nanoTime()
    }

    fun scheduled(count: Int) = update { total = count }

    fun producing(value: ArtworkSchedulerPhase, rank: Int) = update {
        producer = value
        producerRank = rank
        producerAt = System.nanoTime()
    }

    fun enqueued(rank: Int) = update {
        // Receiving also confirms transfer: a consumer can resume before send's continuation.
        enqueued = maxOf(enqueued, rank)
        highWater = maxOf(highWater, enqueued - dequeued)
    }

    fun consuming(value: ArtworkSchedulerPhase) = update {
        consumer = value
        consumerAt = System.nanoTime()
    }

    fun received(rank: Int) = update {
        enqueued = maxOf(enqueued, rank)
        dequeued = rank
        consumerRank = rank
        consumer = ArtworkSchedulerPhase.NATIVE_REVALIDATION
        consumerAt = System.nanoTime()
    }

    fun indexed(progress: Int, count: Int) = update {
        indexProgress = progress
        indexTotal = count
    }

    fun completed() = update { completed++ }
    fun end(value: ArtworkSchedulerStage) = update { stage = value }

    private fun update(change: () -> Unit) {
        synchronized(lock) {
            change()
            val now = System.nanoTime()
            fun elapsed(at: Long) = ((now - at) / 1_000_000).coerceAtLeast(0)
            val snapshot = ArtworkSchedulerSnapshot(generation, stage, preparation, producer, consumer, producerRank, consumerRank,
                total, enqueued, dequeued, completed, (enqueued - dequeued).coerceAtLeast(0), highWater,
                indexProgress, indexTotal, elapsed(started), elapsed(preparationAt), elapsed(producerAt), elapsed(consumerAt))
            diagnostics.recordSafely { artworkScheduler(snapshot) }
        }
    }
}
