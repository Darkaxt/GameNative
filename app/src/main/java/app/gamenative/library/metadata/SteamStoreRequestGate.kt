package app.gamenative.library.metadata

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import app.gamenative.diagnostics.DiagnosticArea
import app.gamenative.diagnostics.DiagnosticAttribute
import app.gamenative.diagnostics.DiagnosticEventName
import app.gamenative.diagnostics.DiagnosticOutcome
import app.gamenative.diagnostics.FeatureDiagnostics
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Response

internal data class SteamStoreRequestState(val intervalMs: Long = 2_000L, val blockedUntilMs: Long = 0L)

/** Shared by catalog search, AppDetails and public popularity requests, including their redirects. */
@Singleton
class SteamStoreRequestGate internal constructor(
    readState: () -> SteamStoreRequestState = { SteamStoreRequestState() },
    private val writeState: suspend (SteamStoreRequestState) -> Unit = {},
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val nowMonotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    @Inject constructor(@ApplicationContext context: Context) :
        this(context.getSharedPreferences("steam_store_request_gate_v1", Context.MODE_PRIVATE))

    private constructor(preferences: SharedPreferences) : this(
        readState = {
            try { SteamStoreRequestState(preferences.getLong("interval_ms", 2_000L),
                preferences.getLong("blocked_until_ms", 0L)) } catch (_: Exception) { SteamStoreRequestState() }
        },
        writeState = { state -> withContext(Dispatchers.IO) {
            check(preferences.edit().putLong("interval_ms", state.intervalMs)
                .putLong("blocked_until_ms", state.blockedUntilMs).commit())
        } },
        nowMonotonicMs = SystemClock::elapsedRealtime,
    )

    private val mutex = Mutex()
    private var state = readState().let {
        it.copy(intervalMs = it.intervalMs.coerceIn(2_000L, 30_000L), blockedUntilMs = it.blockedUntilMs.coerceAtLeast(0L))
    }
    private var lastStartedMonotonicMs: Long? = null
    // Epoch deadlines survive restarts; elapsed time prevents a wall-clock correction from
    // shortening Retry-After while this process is alive.
    private var cooldownStartedMonotonicMs = nowMonotonicMs()
    private var cooldownDurationMs = (state.blockedUntilMs - nowEpochMs().coerceAtLeast(0L)).coerceAtLeast(0L)
    private var successes = 0

    internal suspend fun execute(request: suspend () -> Response): Response = mutex.withLock {
        currentCoroutineContext().ensureActive()
        val cooldownElapsed = (nowMonotonicMs() - cooldownStartedMonotonicMs).coerceAtLeast(0L)
        val remaining = (cooldownDurationMs - cooldownElapsed).coerceAtLeast(0L)
        if (remaining > 0L) {
            FeatureDiagnostics.record(DiagnosticArea.METADATA, DiagnosticEventName.METADATA_FETCH,
                DiagnosticOutcome.DEFERRED, attributes = mapOf(
                    DiagnosticAttribute.OPERATION to "STEAM_STORE_COOLDOWN",
                    DiagnosticAttribute.REASON to "SHARED_COOLDOWN",
                    DiagnosticAttribute.RETRY_AFTER_MS to remaining.toString(),
                    DiagnosticAttribute.REQUEST_INTERVAL_MS to state.intervalMs.toString(),
                ))
            throw SteamRateLimitExhaustedException(remaining)
        }
        lastStartedMonotonicMs?.let { started ->
            val elapsed = (nowMonotonicMs() - started).coerceAtLeast(0L)
            if (elapsed < state.intervalMs) sleep(state.intervalMs - elapsed)
        }
        currentCoroutineContext().ensureActive()
        lastStartedMonotonicMs = nowMonotonicMs()
        val response = request()
        if (response.code == 429) {
            val blockedFor = retryAfterMs(response.header("Retry-After"), nowEpochMs()).coerceAtLeast(60_000L)
            response.close()
            successes = 0
            val current = nowEpochMs().coerceAtLeast(0L)
            state = SteamStoreRequestState((state.intervalMs * 2).coerceAtMost(30_000L),
                current + blockedFor.coerceAtMost(Long.MAX_VALUE - current))
            cooldownStartedMonotonicMs = nowMonotonicMs()
            cooldownDurationMs = blockedFor
            val persistenceFailure = withContext(NonCancellable) { persist() }
            currentCoroutineContext().ensureActive()
            FeatureDiagnostics.record(DiagnosticArea.METADATA, DiagnosticEventName.METADATA_FETCH,
                DiagnosticOutcome.DEFERRED, attributes = mapOf(
                    DiagnosticAttribute.OPERATION to "STEAM_STORE_COOLDOWN",
                    DiagnosticAttribute.HTTP_STATUS to "429",
                    DiagnosticAttribute.RETRY_AFTER_MS to blockedFor.toString(),
                    DiagnosticAttribute.REQUEST_INTERVAL_MS to state.intervalMs.toString(),
                ))
            throw SteamRateLimitExhaustedException(blockedFor).also { error ->
                persistenceFailure?.let(error::addSuppressed)
            }
        }
        try {
            if (response.isSuccessful && ++successes >= 10) {
                successes = 0
                if (state.intervalMs > 2_000L) {
                    state = state.copy(intervalMs = (state.intervalMs / 2).coerceAtLeast(2_000L))
                    persist()
                }
            }
            currentCoroutineContext().ensureActive()
            response
        } catch (error: Exception) {
            response.close()
            throw error
        }
    }

    /** Keep the in-memory cooldown even if storage fails; surface the durability failure to callers. */
    private suspend fun persist(): Exception? {
        return try { writeState(state); null }
        catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) {
            FeatureDiagnostics.record(DiagnosticArea.METADATA, DiagnosticEventName.METADATA_FETCH,
                DiagnosticOutcome.UNAVAILABLE, attributes = mapOf(
                    DiagnosticAttribute.OPERATION to "STEAM_STORE_COOLDOWN",
                    DiagnosticAttribute.REASON to "COOLDOWN_PERSISTENCE_UNAVAILABLE",
                ))
            error
        }
    }

    private fun retryAfterMs(header: String?, now: Long): Long {
        header?.trim()?.toLongOrNull()?.takeIf { it >= 0L }?.let {
            return it.coerceAtMost(Long.MAX_VALUE / 1_000L) * 1_000L
        }
        return header?.let { runCatching {
            ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull() }?.let { (it - now).coerceAtLeast(0L) } ?: 60_000L
    }
}
