package app.gamenative.library.canonical

import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.library.canonical.source.OwnedCopySourceAdapter
import app.gamenative.library.canonical.source.SnapshotReason
import app.gamenative.library.canonical.source.SourceProjectionBatch
import app.gamenative.library.canonical.source.sourceReadFailed
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun interface CanonicalProjectionGate {
    fun isEnabled(): Boolean
}

fun interface CanonicalProjectionClock {
    fun nowEpochMs(): Long
}

@Singleton
class PrefManagerCanonicalProjectionGate @Inject constructor() : CanonicalProjectionGate {
    override fun isEnabled(): Boolean = PrefManager.canonicalProjectionEnabled
}

@Singleton
class SystemCanonicalProjectionClock @Inject constructor() : CanonicalProjectionClock {
    override fun nowEpochMs(): Long = System.currentTimeMillis()
}

@Singleton
class CanonicalProjectionCoordinator @Inject constructor(
    adapters: Set<@JvmSuppressWildcards OwnedCopySourceAdapter>,
    private val runner: CanonicalProjectionRunner,
    private val diagnostics: CanonicalDiagnosticSink,
    private val gate: CanonicalProjectionGate,
    private val clock: CanonicalProjectionClock,
    private val accountLifecycleState: AccountLifecycleState,
) {
    private val orderedAdapters = adapters.sortedBy { adapter -> sourceRank(adapter.source) }

    fun start(scope: CoroutineScope): Job = scope.launch {
        coroutineScope {
            val triggers = Channel<Unit>(Channel.CONFLATED)
            val invalidationJobs = orderedAdapters.map { adapter ->
                launch { collectInvalidations(adapter, triggers) }
            }
            triggers.trySend(Unit)
            try {
                for (ignored in triggers) {
                    rebuildSafely()
                }
            } finally {
                invalidationJobs.forEach { job -> job.cancel() }
                triggers.close()
            }
        }
    }

    private suspend fun collectInvalidations(
        adapter: OwnedCopySourceAdapter,
        triggers: Channel<Unit>,
    ) {
        var retryDelayMs = MIN_INVALIDATION_RETRY_DELAY_MS
        while (true) {
            try {
                adapter.invalidations().collect {
                    retryDelayMs = MIN_INVALIDATION_RETRY_DELAY_MS
                    triggers.trySend(Unit)
                }
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                diagnostics.invalidationFailed(adapter.source, error::class)
                delay(retryDelayMs)
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_INVALIDATION_RETRY_DELAY_MS)
            }
        }
    }

    private suspend fun rebuildSafely() {
        var startedAt: Long? = null
        try {
            if (!gate.isEnabled()) {
                diagnostics.indexSkipped(SnapshotReason.FEATURE_DISABLED)
                return
            }

            startedAt = clock.nowEpochMs()
            diagnostics.indexStarted()
            val batches = orderedAdapters.map { adapter -> snapshot(adapter) }
            val result = runner.rebuild(
                batches = batches,
                nowEpochMs = clock.nowEpochMs(),
            )
            result.matchCounts.entries
                .sortedWith(
                    compareBy(
                        { (bucket, _) -> bucket.method.ordinal },
                        { (bucket, _) -> bucket.confidence.ordinal },
                    ),
                )
                .forEach { (bucket, count) -> diagnostics.matchBucket(bucket, count) }
            diagnostics.indexSucceeded(result, elapsedSince(startedAt))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            diagnostics.indexFailed(error::class, elapsedSince(startedAt))
        }
    }

    private suspend fun snapshot(adapter: OwnedCopySourceAdapter): SourceProjectionBatch {
        val batch = try {
            adapter.snapshot()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            sourceReadFailed(
                source = adapter.source,
                accountScope = null,
                error = error,
                lifecycleGeneration = fallbackGeneration(adapter.source),
            )
        }
        diagnostics.sourceSnapshot(
            source = batch.source,
            completeness = batch.completeness,
            copyCount = batch.copies.size,
            reason = batch.reason,
            errorClass = batch.errorClass,
        )
        return batch
    }

    private fun fallbackGeneration(source: GameSource): Long? =
        if (source == GameSource.CUSTOM_GAME) null else accountLifecycleState.generation(source)

    private fun elapsedSince(startedAt: Long?): Long {
        if (startedAt == null) return 0
        val finishedAt = try {
            clock.nowEpochMs()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return 0
        }
        return (finishedAt - startedAt).coerceAtLeast(0)
    }

    private fun sourceRank(source: GameSource): Int = when (source) {
        GameSource.STEAM -> 0
        GameSource.GOG -> 1
        GameSource.EPIC -> 2
        GameSource.AMAZON -> 3
        GameSource.CUSTOM_GAME -> 4
    }

    private companion object {
        const val MIN_INVALIDATION_RETRY_DELAY_MS = 1_000L
        const val MAX_INVALIDATION_RETRY_DELAY_MS = 60_000L
    }
}
