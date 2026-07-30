package app.gamenative.library.canonical.runtime

import app.gamenative.data.canonical.AccountScope
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Explicit result of one provider update observation. */
internal sealed interface UpdateRefreshOutcome {
    data class Observed(val updateAvailable: Boolean) : UpdateRefreshOutcome

    data class Failed(
        val errorClass: KClass<out Throwable>,
    ) : UpdateRefreshOutcome
}

internal class MissingUpdateObservationException : Exception()

enum class UpdateObservation {
    UNKNOWN,
    CURRENT,
    UPDATE_AVAILABLE,
}

internal enum class UpdateSnapshotCoverage {
    POINT,
    COMPLETE,
}

data class UpdateObservationOwner(
    val accountScope: AccountScope,
    val generation: Long,
)

internal data class UpdateObservationLifecycle(
    val accountScope: AccountScope?,
    val generation: Long,
)

internal data class UpdateObservationRequest<K : Any, F : Any>(
    val key: K,
    val fingerprint: F,
)

internal class ObservedUpdateStateStore<K : Any, F : Any>(
    private val scope: CoroutineScope,
    private val nowMonotonicMs: () -> Long,
    private val ttlMs: Long,
    private val retryDelayMs: Long,
    maxEntries: Int,
    private val maxRefreshBatch: Int = maxEntries,
    private val refreshTimeoutMs: Long = DEFAULT_REFRESH_TIMEOUT_MS,
    private val isOwnerCurrent: suspend (UpdateObservationOwner) -> Boolean = { true },
    private val onRefreshFailure: (KClass<out Throwable>) -> Unit = {},
    private val refresh: (suspend (
        List<UpdateObservationRequest<K, F>>,
    ) -> Map<K, UpdateRefreshOutcome>)? = null,
    private val ownerAwareRefresh: (suspend (
        UpdateObservationOwner,
        List<UpdateObservationRequest<K, F>>,
    ) -> Map<K, UpdateRefreshOutcome>)? = null,
) {
    private val lock = Any()
    private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var owner: UpdateObservationOwner? = null
    private var highestGeneration = -1L
    private var lifecycleInitialized = false
    private var lifecycleAccountScope: AccountScope? = null
    private var ownerJob: Job? = null
    private var ownerContext: CoroutineContext? = null
    private var workerJob: Job? = null
    private var timerJob: Job? = null
    private val queued = linkedMapOf<K, F>()
    private val requestedFingerprints = linkedMapOf<K, F>()
    private val entries = linkedMapOf<K, Entry<F>>()
    private val pending = mutableMapOf<K, F>()
    private val failures = linkedMapOf<K, Failure<F>>()

    init {
        require(maxEntries > 0)
        require(maxRefreshBatch > 0)
        require(refreshTimeoutMs > 0L)
        require((refresh == null) != (ownerAwareRefresh == null))
    }

    fun invalidations(): Flow<Unit> = changed.asSharedFlow()

    fun snapshot(
        requestedOwner: UpdateObservationOwner,
        fingerprints: Map<K, F>,
        coverage: UpdateSnapshotCoverage = UpdateSnapshotCoverage.POINT,
    ): Map<K, UpdateObservation> {
        val requests = synchronized(lock) {
            if (!activateOwnerLocked(requestedOwner)) return fingerprints.unknownObservations()
            if (coverage == UpdateSnapshotCoverage.COMPLETE) {
                requestedFingerprints.keys.retainAll(fingerprints.keys)
            }
            fingerprints.forEach { (key, fingerprint) ->
                requestedFingerprints[key] = fingerprint
            }
            if (coverage == UpdateSnapshotCoverage.COMPLETE) {
                pruneInactiveLocked()
                if (requestedFingerprints.isEmpty()) {
                    workerJob?.cancel()
                    workerJob = null
                    timerJob?.cancel()
                    timerJob = null
                }
            }
            val now = nowMonotonicMs()
            buildList {
                requestedFingerprints.forEach { (key, fingerprint) ->
                    val entry = entries[key]
                    val isCurrent = entry != null &&
                        entry.fingerprint == fingerprint &&
                        now - entry.observedAtMonotonicMs < ttlMs
                    if (isCurrent) return@forEach

                    if (entry != null) entries.remove(key)
                    val failure = failures[key]
                    val retryAllowed = failure == null ||
                        failure.fingerprint != fingerprint ||
                        now >= failure.retryAtMonotonicMs
                    if (
                        pending[key] != fingerprint && retryAllowed &&
                        size < maxRefreshBatch
                    ) {
                        pending[key] = fingerprint
                        add(UpdateObservationRequest(key, fingerprint))
                    }
                }
            }
        }
        if (requests.isNotEmpty()) schedule(requestedOwner, requests)
        return synchronized(lock) {
            if (owner != requestedOwner) return fingerprints.unknownObservations()
            fingerprints.mapValues { (key, fingerprint) ->
                entries[key]
                    ?.takeIf { it.fingerprint == fingerprint }
                    ?.observation
                    ?: UpdateObservation.UNKNOWN
            }
        }
    }

    fun transitionLifecycle(accountScope: AccountScope?, generation: Long) {
        require(generation >= 0L)
        synchronized(lock) {
            if (generation < highestGeneration) return
            if (lifecycleInitialized && generation == highestGeneration) {
                if (lifecycleAccountScope == accountScope) return
                if (lifecycleAccountScope == null) return
                lifecycleAccountScope = accountScope
                cancelActiveOwnerLocked()
                return
            }

            highestGeneration = generation
            lifecycleInitialized = true
            lifecycleAccountScope = accountScope
            val transitionedOwner = accountScope?.let { UpdateObservationOwner(it, generation) }
            if (owner != transitionedOwner) cancelActiveOwnerLocked()
        }
    }

    private fun schedule(
        requestedOwner: UpdateObservationOwner,
        requests: List<UpdateObservationRequest<K, F>>,
    ) {
        synchronized(lock) {
            val context = ownerContext.takeIf { owner == requestedOwner } ?: return
            requests.forEach { request ->
                if (pending[request.key] != request.fingerprint) return@forEach
                if (request.key in queued || queued.size < maxRefreshBatch) {
                    queued[request.key] = request.fingerprint
                } else {
                    pending.remove(request.key)
                }
            }
            if (workerJob?.isActive == true) return
            workerJob = CoroutineScope(context).launch {
                drainQueue(requestedOwner)
            }
        }
    }

    private suspend fun drainQueue(requestedOwner: UpdateObservationOwner) {
        while (true) {
            val requests = synchronized(lock) {
                if (owner != requestedOwner) return
                buildList {
                    val iterator = queued.iterator()
                    while (iterator.hasNext() && size < maxRefreshBatch) {
                        val (key, fingerprint) = iterator.next()
                        iterator.remove()
                        if (pending[key] == fingerprint) {
                            add(UpdateObservationRequest(key, fingerprint))
                        }
                    }
                }.also {
                    if (it.isEmpty()) {
                        workerJob = null
                        armTimerLocked(requestedOwner)
                    }
                }
            }
            if (requests.isEmpty()) return
            try {
                if (!isOwnerCurrent(requestedOwner)) {
                    deactivateOwner(requestedOwner)
                    return
                }
                val refreshed = withTimeout(refreshTimeoutMs) {
                    ownerAwareRefresh?.invoke(requestedOwner, requests)
                        ?: requireNotNull(refresh).invoke(requests)
                }
                if (!isOwnerCurrent(requestedOwner)) {
                    deactivateOwner(requestedOwner)
                    return
                }
                complete(requestedOwner, requests, refreshed)
            } catch (error: TimeoutCancellationException) {
                onRefreshFailure(error::class)
                fail(requestedOwner, requests)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onRefreshFailure(error::class)
                fail(requestedOwner, requests)
            } finally {
                clearPending(requestedOwner, requests)
            }
        }
    }

    private fun complete(
        requestedOwner: UpdateObservationOwner,
        requests: List<UpdateObservationRequest<K, F>>,
        refreshed: Map<K, UpdateRefreshOutcome>,
    ) {
        var didChange = false
        val refreshFailures = linkedSetOf<KClass<out Throwable>>()
        synchronized(lock) {
            if (owner != requestedOwner) return
            val now = nowMonotonicMs()
            requests.forEach { request ->
                if (pending[request.key] != request.fingerprint) return@forEach
                pending.remove(request.key)
                if (requestedFingerprints[request.key] != request.fingerprint) return@forEach
                when (
                    val outcome = refreshed[request.key]
                        ?: UpdateRefreshOutcome.Failed(MissingUpdateObservationException::class)
                ) {
                    is UpdateRefreshOutcome.Failed -> {
                        recordFailure(request, now)
                        refreshFailures += outcome.errorClass
                    }
                    is UpdateRefreshOutcome.Observed -> {
                        failures.remove(request.key)
                        val observation = if (outcome.updateAvailable) {
                            UpdateObservation.UPDATE_AVAILABLE
                        } else {
                            UpdateObservation.CURRENT
                        }
                        val old = entries.put(
                            request.key,
                            Entry(request.fingerprint, observation, now),
                        )
                        didChange = didChange || old?.observation != observation ||
                            old.fingerprint != request.fingerprint
                    }
                }
            }
            armTimerLocked(requestedOwner)
        }
        refreshFailures.forEach(onRefreshFailure)
        if (didChange) changed.tryEmit(Unit)
    }

    private fun fail(
        requestedOwner: UpdateObservationOwner,
        requests: List<UpdateObservationRequest<K, F>>,
    ) {
        synchronized(lock) {
            if (owner != requestedOwner) return
            val now = nowMonotonicMs()
            requests.forEach { request ->
                if (
                    pending[request.key] == request.fingerprint &&
                    requestedFingerprints[request.key] == request.fingerprint
                ) {
                    pending.remove(request.key)
                    recordFailure(request, now)
                }
            }
            armTimerLocked(requestedOwner)
        }
    }

    private fun clearPending(
        requestedOwner: UpdateObservationOwner,
        requests: List<UpdateObservationRequest<K, F>>,
    ) {
        synchronized(lock) {
            if (owner != requestedOwner) return
            requests.forEach { request ->
                if (pending[request.key] == request.fingerprint) {
                    pending.remove(request.key)
                }
            }
        }
    }

    private fun recordFailure(
        request: UpdateObservationRequest<K, F>,
        now: Long,
    ) {
        val previous = failures[request.key]
            ?.takeIf { it.fingerprint == request.fingerprint }
        val attempts = (previous?.attempts ?: 0) + 1
        val multiplier = 1L shl (attempts - 1).coerceAtMost(6)
        failures.remove(request.key)
        failures[request.key] = Failure(
            fingerprint = request.fingerprint,
            attempts = attempts,
            retryAtMonotonicMs = now + (retryDelayMs * multiplier),
        )
    }

    private fun armTimerLocked(requestedOwner: UpdateObservationOwner) {
        timerJob?.cancel()
        val context = ownerContext.takeIf { owner == requestedOwner } ?: return
        val now = nowMonotonicMs()
        val deadline = requestedFingerprints.mapNotNull { (key, fingerprint) ->
            if (pending[key] == fingerprint) return@mapNotNull null
            failures[key]
                ?.takeIf { it.fingerprint == fingerprint }
                ?.retryAtMonotonicMs
                ?: entries[key]
                    ?.takeIf { it.fingerprint == fingerprint }
                    ?.let { it.observedAtMonotonicMs + ttlMs }
                ?: now
        }.minOrNull() ?: return
        timerJob = CoroutineScope(context).launch {
            delay((deadline - now).coerceAtLeast(0L))
            synchronized(lock) {
                if (owner != requestedOwner) return@launch
                timerJob = null
            }
            refreshDue(requestedOwner)
        }
    }

    private fun refreshDue(requestedOwner: UpdateObservationOwner) {
        var invalidated = false
        val requests = synchronized(lock) {
            if (owner != requestedOwner) return
            val now = nowMonotonicMs()
            buildList {
                requestedFingerprints.forEach { (key, fingerprint) ->
                    if (size >= maxRefreshBatch) return@forEach
                    if (pending[key] == fingerprint) return@forEach
                    val failure = failures[key]?.takeIf { it.fingerprint == fingerprint }
                    if (failure != null && now < failure.retryAtMonotonicMs) return@forEach
                    val entry = entries[key]?.takeIf { it.fingerprint == fingerprint }
                    if (entry != null && now < entry.observedAtMonotonicMs + ttlMs) return@forEach
                    if (entry != null) {
                        entries.remove(key)
                        invalidated = true
                    }
                    pending[key] = fingerprint
                    add(UpdateObservationRequest(key, fingerprint))
                }
            }.also {
                if (it.isEmpty()) armTimerLocked(requestedOwner)
            }
        }
        if (invalidated) changed.tryEmit(Unit)
        if (requests.isNotEmpty()) schedule(requestedOwner, requests)
    }

    private fun activateOwnerLocked(requestedOwner: UpdateObservationOwner): Boolean {
        if (owner == requestedOwner) return true
        if (!lifecycleInitialized) {
            if (requestedOwner.generation <= highestGeneration) return false
            highestGeneration = requestedOwner.generation
            lifecycleInitialized = true
            lifecycleAccountScope = requestedOwner.accountScope
        } else {
            if (requestedOwner.generation < highestGeneration) return false
            if (requestedOwner.generation == highestGeneration) {
                if (lifecycleAccountScope != requestedOwner.accountScope) return false
            } else {
                highestGeneration = requestedOwner.generation
                lifecycleAccountScope = requestedOwner.accountScope
            }
        }

        cancelActiveOwnerLocked()
        owner = requestedOwner
        val job = SupervisorJob(scope.coroutineContext[Job])
        ownerJob = job
        ownerContext = scope.coroutineContext + job
        return true
    }

    private fun deactivateOwner(requestedOwner: UpdateObservationOwner) {
        synchronized(lock) {
            if (owner == requestedOwner) cancelActiveOwnerLocked()
        }
    }

    private fun cancelActiveOwnerLocked() {
        owner = null
        ownerJob?.cancel()
        ownerJob = null
        ownerContext = null
        workerJob = null
        timerJob = null
        queued.clear()
        requestedFingerprints.clear()
        entries.clear()
        pending.clear()
        failures.clear()
    }

    private fun pruneInactiveLocked() {
        entries.keys.retainAll(requestedFingerprints.keys)
        failures.keys.retainAll(requestedFingerprints.keys)
        pending.keys.retainAll(requestedFingerprints.keys)
        queued.keys.retainAll(requestedFingerprints.keys)
    }

    private fun Map<K, F>.unknownObservations(): Map<K, UpdateObservation> =
        keys.associateWith { UpdateObservation.UNKNOWN }

    private companion object {
        const val DEFAULT_REFRESH_TIMEOUT_MS = 30_000L
    }

    private data class Entry<F : Any>(
        val fingerprint: F,
        val observation: UpdateObservation,
        val observedAtMonotonicMs: Long,
    )

    private data class Failure<F : Any>(
        val fingerprint: F,
        val attempts: Int,
        val retryAtMonotonicMs: Long,
    )
}
