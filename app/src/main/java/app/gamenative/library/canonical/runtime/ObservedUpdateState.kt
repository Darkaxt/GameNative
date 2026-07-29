package app.gamenative.library.canonical.runtime

import app.gamenative.data.canonical.AccountScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

enum class UpdateObservation {
    UNKNOWN,
    CURRENT,
    UPDATE_AVAILABLE,
}

data class UpdateObservationOwner(
    val accountScope: AccountScope,
    val generation: Long,
)

internal data class UpdateObservationRequest<K : Any, F : Any>(
    val key: K,
    val fingerprint: F,
)

internal class ObservedUpdateStateStore<K : Any, F : Any>(
    private val scope: CoroutineScope,
    private val nowEpochMs: () -> Long,
    private val ttlMs: Long,
    private val retryDelayMs: Long,
    private val maxEntries: Int,
    private val maxRefreshBatch: Int = maxEntries,
    private val refresh: suspend (List<UpdateObservationRequest<K, F>>) -> Map<K, Boolean>,
) {
    private val lock = Any()
    private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var owner: UpdateObservationOwner? = null
    private val entries = linkedMapOf<K, Entry<F>>()
    private val pending = mutableMapOf<K, F>()
    private val failures = linkedMapOf<K, Failure<F>>()

    fun invalidations(): Flow<Unit> = changed.asSharedFlow()

    fun snapshot(
        requestedOwner: UpdateObservationOwner,
        fingerprints: Map<K, F>,
    ): Map<K, UpdateObservation> {
        val requests = synchronized(lock) {
            switchOwnerIfNeeded(requestedOwner)
            val now = nowEpochMs()
            buildList {
                fingerprints.forEach { (key, fingerprint) ->
                    val entry = entries[key]
                    val isCurrent = entry != null &&
                        entry.fingerprint == fingerprint &&
                        now - entry.observedAtEpochMs < ttlMs
                    if (isCurrent) return@forEach

                    if (entry != null) entries.remove(key)
                    val failure = failures[key]
                    val retryAllowed = failure == null ||
                        failure.fingerprint != fingerprint ||
                        now >= failure.retryAtEpochMs
                    if (
                        pending[key] != fingerprint && retryAllowed &&
                        size < maxRefreshBatch.coerceAtLeast(1)
                    ) {
                        pending[key] = fingerprint
                        add(UpdateObservationRequest(key, fingerprint))
                    }
                }
            }
        }
        if (requests.isNotEmpty()) schedule(requestedOwner, requests)
        return synchronized(lock) {
            fingerprints.mapValues { (key, fingerprint) ->
                entries[key]
                    ?.takeIf { it.fingerprint == fingerprint }
                    ?.observation
                    ?: UpdateObservation.UNKNOWN
            }
        }
    }

    private fun schedule(
        requestedOwner: UpdateObservationOwner,
        requests: List<UpdateObservationRequest<K, F>>,
    ) {
        scope.launch {
            try {
                val refreshed = refresh(requests)
                complete(requestedOwner, requests, refreshed)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                fail(requestedOwner, requests)
            }
        }
    }

    private fun complete(
        requestedOwner: UpdateObservationOwner,
        requests: List<UpdateObservationRequest<K, F>>,
        refreshed: Map<K, Boolean>,
    ) {
        var didChange = false
        synchronized(lock) {
            if (owner != requestedOwner) return
            val now = nowEpochMs()
            requests.forEach { request ->
                if (pending[request.key] != request.fingerprint) return@forEach
                pending.remove(request.key)
                val value = refreshed[request.key]
                if (value == null) {
                    recordFailure(request, now)
                    return@forEach
                }
                failures.remove(request.key)
                val observation = if (value) {
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
            trimEntries()
        }
        if (didChange) changed.tryEmit(Unit)
    }

    private fun fail(
        requestedOwner: UpdateObservationOwner,
        requests: List<UpdateObservationRequest<K, F>>,
    ) {
        synchronized(lock) {
            if (owner != requestedOwner) return
            val now = nowEpochMs()
            requests.forEach { request ->
                if (pending[request.key] == request.fingerprint) {
                    pending.remove(request.key)
                    recordFailure(request, now)
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
            retryAtEpochMs = now + (retryDelayMs * multiplier),
        )
        while (failures.size > maxEntries.coerceAtLeast(1)) {
            failures.remove(failures.keys.first())
        }
    }

    private fun switchOwnerIfNeeded(requestedOwner: UpdateObservationOwner) {
        if (owner == requestedOwner) return
        owner = requestedOwner
        entries.clear()
        pending.clear()
        failures.clear()
    }

    private fun trimEntries() {
        while (entries.size > maxEntries.coerceAtLeast(1)) {
            entries.remove(entries.keys.first())
        }
    }

    private data class Entry<F : Any>(
        val fingerprint: F,
        val observation: UpdateObservation,
        val observedAtEpochMs: Long,
    )

    private data class Failure<F : Any>(
        val fingerprint: F,
        val attempts: Int,
        val retryAtEpochMs: Long,
    )
}
