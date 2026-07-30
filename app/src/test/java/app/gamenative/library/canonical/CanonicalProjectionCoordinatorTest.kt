package app.gamenative.library.canonical

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.diagnostics.DiagnosticAttribute
import app.gamenative.diagnostics.DiagnosticEventName
import app.gamenative.diagnostics.DiagnosticOutcome
import app.gamenative.library.canonical.source.OwnedCopySourceAdapter
import app.gamenative.library.canonical.source.SnapshotCompleteness
import app.gamenative.library.canonical.source.SnapshotReason
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import app.gamenative.library.canonical.source.SourceProjectionBatch
import java.util.concurrent.CancellationException
import kotlin.reflect.KClass
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CanonicalProjectionCoordinatorTest {

    private val scope = AccountScope("1".repeat(64))

    @Test
    fun `startup snapshots every adapter in explicit source order`() = runTest {
        val adapters = sourceOrder.reversed().map { source -> FakeAdapter(completeBatch(source)) }
        val runner = RecordingRunner()
        val diagnostics = RecordingDiagnostics()
        val coordinator = coordinator(adapters, runner, diagnostics)

        val job = coordinator.start(backgroundScope)
        runCurrent()

        assertEquals(1, runner.batches.size)
        assertEquals(sourceOrder, runner.batches.single().map { it.source })
        assertEquals(sourceOrder, diagnostics.sources.map { it.source })
        assertEquals(1, diagnostics.started)
        assertEquals(1, diagnostics.succeeded.size)
        assertTrue(diagnostics.failed.isEmpty())
        job.cancel()
    }

    @Test
    fun `invalidations during a rebuild conflate into one later rebuild`() = runTest {
        val adapter = FakeAdapter(completeBatch(GameSource.STEAM))
        val runner = BlockingFirstRunner()
        val coordinator = coordinator(listOf(adapter), runner, RecordingDiagnostics())

        val job = coordinator.start(backgroundScope)
        runCurrent()
        assertTrue(runner.firstStarted.isCompleted)

        repeat(10) { adapter.invalidate() }
        runCurrent()
        runner.releaseFirst.complete(Unit)
        runCurrent()

        assertEquals(2, runner.callCount)
        job.cancel()
    }

    @Test
    fun `adapter failure becomes unavailable without blocking other sources`() = runTest {
        val privateMessage = "private-title-and-account"
        val failing = FakeAdapter(completeBatch(GameSource.GOG)).apply {
            snapshotFailure = IllegalStateException(privateMessage)
        }
        val steam = FakeAdapter(completeBatch(GameSource.STEAM))
        val runner = RecordingRunner()
        val diagnostics = RecordingDiagnostics()
        val coordinator = coordinator(listOf(failing, steam), runner, diagnostics)

        val job = coordinator.start(backgroundScope)
        runCurrent()

        val projected = runner.batches.single()
        assertEquals(listOf(GameSource.STEAM, GameSource.GOG), projected.map { it.source })
        val unavailable = projected.single { it.source == GameSource.GOG }
        assertEquals(SnapshotCompleteness.UNAVAILABLE, unavailable.completeness)
        assertEquals(SnapshotReason.SOURCE_READ_FAILED, unavailable.reason)
        assertEquals(IllegalStateException::class, unavailable.errorClass)
        assertFalse(unavailable.toString().contains(privateMessage))
        assertEquals(
            IllegalStateException::class,
            diagnostics.sources.single { it.source == GameSource.GOG }.errorClass,
        )
        assertEquals(1, steam.snapshotCalls)
        job.cancel()
    }

    @Test
    fun `adapter failure carries current lifecycle generation except for Custom`() = runTest {
        val lifecycleState = InMemoryAccountLifecycleState().apply {
            repeat(7) { advanceGeneration(GameSource.GOG) }
        }
        val gog = FakeAdapter(completeBatch(GameSource.GOG)).apply {
            snapshotFailure = IllegalStateException("private GOG failure")
        }
        val custom = FakeAdapter(completeBatch(GameSource.CUSTOM_GAME)).apply {
            snapshotFailure = IllegalStateException("private Custom failure")
        }
        val runner = RecordingRunner()
        val coordinator = coordinator(
            adapters = listOf(gog, custom),
            runner = runner,
            diagnostics = RecordingDiagnostics(),
            accountLifecycleState = lifecycleState,
        )

        val job = coordinator.start(backgroundScope)
        runCurrent()

        val failed = runner.batches.single().single { it.source == GameSource.GOG }
        assertEquals(7L, failed.lifecycleGeneration)
        assertNull(failed.accountScope)
        assertEquals(SnapshotCompleteness.UNAVAILABLE, failed.completeness)
        assertEquals(SnapshotReason.SOURCE_READ_FAILED, failed.reason)
        val customFailed = runner.batches.single().single { it.source == GameSource.CUSTOM_GAME }
        assertNull(customFailed.lifecycleGeneration)
        assertNull(customFailed.accountScope)
        assertEquals(SnapshotCompleteness.UNAVAILABLE, customFailed.completeness)
        assertEquals(SnapshotReason.SOURCE_READ_FAILED, customFailed.reason)
        job.cancel()
    }

    @Test
    fun `runner failure is recorded and later invalidation retries`() = runTest {
        val adapter = FakeAdapter(completeBatch(GameSource.STEAM))
        val runner = RecordingRunner(failuresRemaining = 1)
        val diagnostics = RecordingDiagnostics()
        val coordinator = coordinator(listOf(adapter), runner, diagnostics)

        val job = coordinator.start(backgroundScope)
        runCurrent()
        assertEquals(1, runner.batches.size)
        assertEquals(listOf(IllegalStateException::class), diagnostics.failed.map { it.errorClass })

        adapter.invalidate()
        runCurrent()

        assertEquals(2, runner.batches.size)
        assertEquals(1, diagnostics.succeeded.size)
        assertTrue(job.isActive)
        job.cancel()
    }

    @Test
    fun `invalidation flow failure is reported and collection restarts`() = runTest {
        val adapter = FakeAdapter(completeBatch(GameSource.STEAM)).apply {
            invalidationFailuresRemaining = 1
        }
        val runner = RecordingRunner()
        val diagnostics = RecordingDiagnostics()
        val coordinator = coordinator(listOf(adapter), runner, diagnostics)

        val job = coordinator.start(backgroundScope)
        runCurrent()

        assertEquals(
            listOf(GameSource.STEAM to IllegalStateException::class),
            diagnostics.invalidationFailures,
        )
        assertEquals(1, runner.batches.size)

        advanceTimeBy(1_000)
        runCurrent()
        adapter.invalidate()
        runCurrent()

        assertEquals(2, runner.batches.size)
        assertTrue(job.isActive)
        job.cancel()
    }

    @Test
    fun `gate failure is recorded and later invalidation retries`() = runTest {
        val adapter = FakeAdapter(completeBatch(GameSource.STEAM))
        val runner = RecordingRunner()
        val diagnostics = RecordingDiagnostics()
        var gateCalls = 0
        val coordinator = coordinator(
            adapters = listOf(adapter),
            runner = runner,
            diagnostics = diagnostics,
            gate = CanonicalProjectionGate {
                if (gateCalls++ == 0) error("private gate message")
                true
            },
        )

        val job = coordinator.start(backgroundScope)
        runCurrent()
        assertEquals(listOf(IllegalStateException::class), diagnostics.failed.map { it.errorClass })
        assertTrue(runner.batches.isEmpty())

        adapter.invalidate()
        runCurrent()

        assertEquals(1, runner.batches.size)
        assertTrue(job.isActive)
        job.cancel()
    }

    @Test
    fun `disabled gate skips snapshots and projection`() = runTest {
        val adapter = FakeAdapter(completeBatch(GameSource.STEAM))
        val runner = RecordingRunner()
        val diagnostics = RecordingDiagnostics()
        val coordinator = coordinator(
            adapters = listOf(adapter),
            runner = runner,
            diagnostics = diagnostics,
            gate = CanonicalProjectionGate { false },
        )

        val job = coordinator.start(backgroundScope)
        runCurrent()

        assertEquals(0, adapter.snapshotCalls)
        assertTrue(runner.batches.isEmpty())
        assertEquals(listOf(SnapshotReason.FEATURE_DISABLED), diagnostics.skipped)
        assertEquals(0, diagnostics.started)
        job.cancel()
    }

    @Test
    fun `cancellation escapes without being recorded as failure`() = runTest {
        val adapter = FakeAdapter(completeBatch(GameSource.STEAM)).apply {
            snapshotFailure = CancellationException("stop")
        }
        val diagnostics = RecordingDiagnostics()
        val coordinator = coordinator(listOf(adapter), RecordingRunner(), diagnostics)

        val job = coordinator.start(backgroundScope)
        runCurrent()

        assertTrue(job.isCancelled)
        assertTrue(diagnostics.failed.isEmpty())
    }

    @Test
    fun `diagnostic wrapper emits aggregate allowlisted attributes only`() {
        val recorder = RecordingEventRecorder()
        val diagnostics = CanonicalDiagnostics(recorder)
        diagnostics.indexStarted()
        diagnostics.sourceSnapshot(
            source = GameSource.GOG,
            completeness = SnapshotCompleteness.PARTIAL,
            copyCount = 3,
            reason = SnapshotReason.MISSING_MATERIALIZED_ROW,
            errorClass = null,
        )
        diagnostics.matchBucket(
            MatchBucket(MatchMethod.EXACT_METADATA, MatchConfidence.HIGH),
            count = 2,
        )
        diagnostics.indexSucceeded(
            CanonicalProjectionResult(
                sourceCounts = linkedMapOf(
                    GameSource.STEAM to 2,
                    GameSource.GOG to 1,
                ),
                canonicalCount = 2,
                copyCount = 3,
                matchCounts = mapOf(
                    MatchBucket(MatchMethod.DIRECT_STEAM, MatchConfidence.VERIFIED) to 1,
                    MatchBucket(MatchMethod.EXACT_METADATA, MatchConfidence.HIGH) to 2,
                ),
                unavailableSources = emptyMap(),
            ),
            durationMs = 25,
        )
        diagnostics.indexFailed(IllegalStateException::class, durationMs = 10)
        diagnostics.indexSkipped(SnapshotReason.FEATURE_DISABLED)
        diagnostics.playHistoryFailed(
            source = GameSource.AMAZON,
            origin = PlayHistoryOrigin.POINT,
            errorClass = IllegalStateException::class,
        )
        diagnostics.updateObservationFailed(
            source = GameSource.STEAM,
            errorClass = IllegalStateException::class,
        )

        val featureEvents = recorder.events.map { event -> event.toFeatureEvent() }
        val allowed = setOf(
            DiagnosticAttribute.SOURCE,
            DiagnosticAttribute.REASON,
            DiagnosticAttribute.ERROR_TYPE,
            DiagnosticAttribute.RESULT_COUNT,
            DiagnosticAttribute.STEAM_COUNT,
            DiagnosticAttribute.GOG_COUNT,
            DiagnosticAttribute.EPIC_COUNT,
            DiagnosticAttribute.AMAZON_COUNT,
            DiagnosticAttribute.CUSTOM_COUNT,
            DiagnosticAttribute.CANONICAL_COUNT,
            DiagnosticAttribute.COPY_COUNT,
            DiagnosticAttribute.MATCH_METHOD,
            DiagnosticAttribute.CONFIDENCE,
            DiagnosticAttribute.OPERATION,
        )
        assertTrue(featureEvents.flatMap { it.attributes.keys }.all { it in allowed })
        val forbiddenKeys = setOf(
            "account_scope",
            "stable_source_id",
            "title",
            "candidate_title",
            "path",
            "url",
            "token",
        )
        assertTrue(featureEvents.flatMap { it.attributes.keys }.none { it.wireName in forbiddenKeys })
        assertEquals(
            DiagnosticOutcome.STALE,
            featureEvents.single { it.attributes[DiagnosticAttribute.SOURCE] == "GOG" }.outcome,
        )
        assertEquals(
            mapOf(
                DiagnosticAttribute.SOURCE to "AMAZON",
                DiagnosticAttribute.OPERATION to "PLAY_HISTORY",
                DiagnosticAttribute.REASON to "POINT",
                DiagnosticAttribute.ERROR_TYPE to "IllegalStateException",
            ),
            featureEvents.single {
                it.attributes[DiagnosticAttribute.OPERATION] == "PLAY_HISTORY"
            }.attributes,
        )
        assertEquals(
            mapOf(
                DiagnosticAttribute.SOURCE to "STEAM",
                DiagnosticAttribute.OPERATION to "UPDATE_OBSERVATION",
                DiagnosticAttribute.ERROR_TYPE to "IllegalStateException",
            ),
            featureEvents.single {
                it.attributes[DiagnosticAttribute.OPERATION] == "UPDATE_OBSERVATION"
            }.attributes,
        )
        assertEquals(
            setOf(DiagnosticEventName.CANONICAL_INDEX_BUILD, DiagnosticEventName.MATCH_RESOLUTION),
            featureEvents.map { it.name }.toSet(),
        )
    }

    private fun coordinator(
        adapters: List<OwnedCopySourceAdapter>,
        runner: CanonicalProjectionRunner,
        diagnostics: CanonicalDiagnosticSink,
        gate: CanonicalProjectionGate = CanonicalProjectionGate { true },
        accountLifecycleState: AccountLifecycleState = InMemoryAccountLifecycleState(),
    ): CanonicalProjectionCoordinator = CanonicalProjectionCoordinator(
        adapters = adapters.toSet(),
        runner = runner,
        diagnostics = diagnostics,
        gate = gate,
        clock = IncrementingClock(),
        accountLifecycleState = accountLifecycleState,
    )

    private fun completeBatch(source: GameSource): SourceProjectionBatch = SourceProjectionBatch(
        source = source,
        accountScope = scope,
        completeness = SnapshotCompleteness.COMPLETE,
        copies = emptyList(),
    )

    private class FakeAdapter(
        private val batch: SourceProjectionBatch,
    ) : OwnedCopySourceAdapter {
        private val events = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
        var snapshotCalls: Int = 0
        var snapshotFailure: Throwable? = null
        var invalidationFailuresRemaining: Int = 0

        override val source: GameSource = batch.source

        override fun invalidations(): Flow<Unit> = flow {
            if (invalidationFailuresRemaining > 0) {
                invalidationFailuresRemaining -= 1
                error("private invalidation message")
            }
            emitAll(events)
        }

        override suspend fun snapshot(): SourceProjectionBatch {
            snapshotCalls += 1
            snapshotFailure?.let { throw it }
            return batch
        }

        override suspend fun resolve(key: OwnedCopyKey): SourceOwnedCopyReference? = null

        fun invalidate() {
            check(events.tryEmit(Unit))
        }
    }

    private open class RecordingRunner(
        private var failuresRemaining: Int = 0,
    ) : CanonicalProjectionRunner {
        val batches = mutableListOf<List<SourceProjectionBatch>>()

        override suspend fun rebuild(
            batches: List<SourceProjectionBatch>,
            nowEpochMs: Long,
        ): CanonicalProjectionResult {
            this.batches += batches
            if (failuresRemaining > 0) {
                failuresRemaining -= 1
                error("private runner message")
            }
            return emptyResult(batches)
        }
    }

    private class BlockingFirstRunner : CanonicalProjectionRunner {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var callCount = 0

        override suspend fun rebuild(
            batches: List<SourceProjectionBatch>,
            nowEpochMs: Long,
        ): CanonicalProjectionResult {
            callCount += 1
            if (callCount == 1) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
            return emptyResult(batches)
        }
    }

    private class IncrementingClock : CanonicalProjectionClock {
        private var now = 1_000L

        override fun nowEpochMs(): Long = now.also { now += 10 }
    }

    private class RecordingDiagnostics : CanonicalDiagnosticSink {
        var started = 0
        val sources = mutableListOf<SourceCall>()
        val buckets = mutableListOf<Pair<MatchBucket, Int>>()
        val succeeded = mutableListOf<Pair<CanonicalProjectionResult, Long>>()
        val failed = mutableListOf<FailureCall>()
        val invalidationFailures = mutableListOf<Pair<GameSource, KClass<out Throwable>>>()
        val skipped = mutableListOf<SnapshotReason>()

        override fun indexStarted() {
            started += 1
        }

        override fun sourceSnapshot(
            source: GameSource,
            completeness: SnapshotCompleteness,
            copyCount: Int,
            reason: SnapshotReason?,
            errorClass: KClass<out Throwable>?,
        ) {
            sources += SourceCall(source, completeness, copyCount, reason, errorClass)
        }

        override fun invalidationFailed(
            source: GameSource,
            errorClass: KClass<out Throwable>,
        ) {
            invalidationFailures += source to errorClass
        }

        override fun playHistoryFailed(
            source: GameSource?,
            origin: PlayHistoryOrigin,
            errorClass: KClass<out Throwable>,
        ) = Unit

        override fun updateObservationFailed(
            source: GameSource,
            errorClass: KClass<out Throwable>,
        ) = Unit

        override fun matchBucket(bucket: MatchBucket, count: Int) {
            buckets += bucket to count
        }

        override fun indexSucceeded(result: CanonicalProjectionResult, durationMs: Long) {
            succeeded += result to durationMs
        }

        override fun indexFailed(errorClass: KClass<out Throwable>, durationMs: Long) {
            failed += FailureCall(errorClass, durationMs)
        }

        override fun indexSkipped(reason: SnapshotReason) {
            skipped += reason
        }
    }

    private class RecordingEventRecorder : CanonicalEventRecorder {
        val events = mutableListOf<CanonicalDiagnosticEvent>()

        override fun record(event: CanonicalDiagnosticEvent) {
            events += event
        }
    }

    private data class SourceCall(
        val source: GameSource,
        val completeness: SnapshotCompleteness,
        val copyCount: Int,
        val reason: SnapshotReason?,
        val errorClass: KClass<out Throwable>?,
    )

    private data class FailureCall(
        val errorClass: KClass<out Throwable>,
        val durationMs: Long,
    )

    private companion object {
        val sourceOrder = listOf(
            GameSource.STEAM,
            GameSource.GOG,
            GameSource.EPIC,
            GameSource.AMAZON,
            GameSource.CUSTOM_GAME,
        )

        fun emptyResult(batches: List<SourceProjectionBatch>): CanonicalProjectionResult =
            CanonicalProjectionResult(
                sourceCounts = batches.associate { batch -> batch.source to 0 },
                canonicalCount = 0,
                copyCount = 0,
                matchCounts = emptyMap(),
                unavailableSources = batches
                    .filter { it.completeness == SnapshotCompleteness.UNAVAILABLE }
                    .associate { batch -> batch.source to requireNotNull(batch.reason) },
            )
    }
}
