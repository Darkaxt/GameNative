package app.gamenative.diagnostics

import android.content.SharedPreferences
import java.io.File
import kotlinx.serialization.json.Json

data class FocusedDiagnosticCaptureState(
    val title: String = "",
    val enabled: Boolean = false,
    val full: Boolean = false,
    val writeFailures: Int = 0,
    val available: Boolean = true,
)

internal data class FocusedDiagnosticCaptureReport(
    val state: FocusedDiagnosticCaptureState,
    val events: List<DiagnosticEvent>,
    val maxBytes: Long,
)

internal class FocusedDiagnosticCapture(
    directory: File,
    private val preferences: SharedPreferences,
    private val maxBytes: Long = 8L * 1024L * 1024L,
    private val monotonicClockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val store = DiagnosticLogStore(directory, json, maxFileBytes = maxBytes, maxFiles = 1)
    private val urls = mutableSetOf<String>()
    private val contents = mutableSetOf<String>()
    private class SchedulerSnapshot(val event: DiagnosticEvent, val receivedAtMs: Long, var lastPersistedAtMs: Long? = null)
    private var schedulerSnapshot: SchedulerSnapshot? = null
    private var schedulerLastWriteAtMs: Long? = null
    private var schedulerLastFullQueueSend: Pair<String?, String?>? = null
    @Volatile
    private var state = FocusedDiagnosticCaptureState(
        title = preferences.getString("title", "").orEmpty(),
        enabled = preferences.getBoolean("enabled", false),
        full = preferences.getBoolean("full", false),
    )

    fun state(): FocusedDiagnosticCaptureState = state

    @Synchronized
    fun start(title: String): Boolean {
        val safe = DiagnosticRedactor.sanitize(mapOf(DiagnosticAttribute.PUBLIC_TITLE to title))
            .getValue(DiagnosticAttribute.PUBLIC_TITLE.wireName)
        if (safe.isBlank() || safe == "[redacted]") return false
        resetScheduler()
        return try {
            store.clear()
            urls.clear()
            contents.clear()
            state = FocusedDiagnosticCaptureState(title = safe, enabled = true)
            check(save())
            true
        } catch (_: Exception) {
            state = state.copy(enabled = false)
            failed()
            false
        }
    }

    @Synchronized
    fun stop(): Boolean {
        flushSchedulerSnapshot()
        state = state.copy(enabled = false)
        return try { check(save()); true } catch (_: Exception) { failed(); false }
    }

    @Synchronized
    fun record(event: DiagnosticEvent) {
        if (!state.enabled || state.full || state.title.isBlank()) return
        val safe = event.copy(attributes = DiagnosticRedactor.sanitizePersisted(event.attributes))
        val attributes = safe.attributes
        // Scheduler evidence has its own identity-free boundary and cannot seed associations.
        if (attributes["operation"] == "ARTWORK_SCHEDULER") return
        val title = attributes["public_title"]
        val url = attributes["public_url_id"]
        val content = attributes["public_content_id"]
        val selected = if (title != null) title.contains(state.title, ignoreCase = true) else
            (url != null && url in urls) || content?.split('/')?.any { it in contents } == true
        if (!selected && event.name != DiagnosticEventName.APP_STARTED) return
        if (append(safe)) {
            url?.takeIf { DIGEST.matches(it) }?.let(urls::add)
            content?.takeIf { DIGEST.matches(it) }?.let(contents::add)
        }
    }

    @Synchronized
    internal fun recordScheduler(event: DiagnosticEvent) {
        if (!state.enabled || state.full || state.title.isBlank()) return
        val safe = sanitizeScheduler(event) ?: return
        val now = monotonicClockMs()
        val previous = schedulerSnapshot?.event?.attributes
        val attributes = safe.attributes
        val snapshot = SchedulerSnapshot(safe, now)
        schedulerSnapshot = snapshot
        val generationChanged = previous?.get("correlation_id") != attributes["correlation_id"]
        val terminalBoundary = attributes["stage"] in TERMINAL_STAGES && previous?.get("stage") != attributes["stage"]
        val fullQueueSend = if (attributes["producer_phase"] == "QUEUE_SEND" && attributes["queue_size"] == "64")
            attributes["correlation_id"] to attributes["producer_rank"] else null
        val newFullQueueSend = fullQueueSend != null && fullQueueSend != schedulerLastFullQueueSend
        val lastWrite = schedulerLastWriteAtMs
        if (generationChanged || terminalBoundary || newFullQueueSend || lastWrite == null || now - lastWrite >= SCHEDULER_INTERVAL_MS) {
            schedulerLastWriteAtMs = now
            if (fullQueueSend != null) schedulerLastFullQueueSend = fullQueueSend
            if (append(safe)) snapshot.lastPersistedAtMs = now
        }
    }

    @Synchronized
    fun report(): FocusedDiagnosticCaptureReport {
        flushSchedulerSnapshot()
        val events = try {
            check(store.bytesOnDisk() <= maxBytes)
            store.recent(Int.MAX_VALUE) { failed() }.mapNotNull {
                val safe = it.copy(attributes = DiagnosticRedactor.sanitizePersisted(it.attributes))
                if (safe.attributes["operation"] == "ARTWORK_SCHEDULER") sanitizeScheduler(safe, allowSnapshot = true) else safe
            }
        } catch (_: Exception) { failed(); emptyList() }
        return FocusedDiagnosticCaptureReport(state, events, maxBytes)
    }

    @Synchronized
    fun clear(): Boolean {
        resetScheduler()
        return try {
            store.clear()
            urls.clear()
            contents.clear()
            state = state.copy(full = false, writeFailures = 0)
            check(save())
            true
        } catch (_: Exception) { failed(); false }
    }

    private fun flushSchedulerSnapshot() {
        if (!state.enabled || state.full) return
        val snapshot = schedulerSnapshot ?: return
        val now = monotonicClockMs()
        val lastPersistedAt = snapshot.lastPersistedAtMs
        // A persisted terminal event is final. Running phases can remain blocked without new callbacks,
        // so later explicit exports must show their age even if the phase-start was already written.
        if (lastPersistedAt != null && (snapshot.event.attributes["stage"] != "RUNNING" || lastPersistedAt == now)) return
        val event = snapshot.event.copy(
            timestampEpochMs = System.currentTimeMillis(),
            attributes = snapshot.event.attributes + mapOf(
                "stage" to "CAPTURE_SNAPSHOT",
                "snapshot_age_ms" to (now - snapshot.receivedAtMs).coerceAtLeast(0L).toString(),
            ),
        )
        // Elapsed values and duration remain measured at observation, not at export time.
        schedulerLastWriteAtMs = now
        if (append(event)) snapshot.lastPersistedAtMs = now
    }

    private fun resetScheduler() {
        schedulerSnapshot = null
        schedulerLastWriteAtMs = null
        schedulerLastFullQueueSend = null
    }

    private fun append(event: DiagnosticEvent): Boolean = try {
        if (store.appendIfFits(event)) true else {
            state = state.copy(full = true)
            check(save())
            false
        }
    } catch (_: Exception) { failed(); false }

    private fun save(): Boolean = preferences.edit().putString("title", state.title)
        .putBoolean("enabled", state.enabled).putBoolean("full", state.full).commit()

    private fun failed() {
        state = state.copy(writeFailures = (state.writeFailures.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    private companion object {
        val DIGEST = Regex("[a-f0-9]{16}")
        const val SCHEDULER_INTERVAL_MS = 2_000L
        val TERMINAL_STAGES = setOf("FINISHED", "CANCELLED", "FAILED")
        val SCHEDULER_PHASES = setOf("IDLE", "NATIVE_VALIDATION", "LOAD_BINDINGS", "LOAD_COMPARISONS",
            "WARM_REVALIDATION", "PRIME_COMPARISONS", "LOAD_FEATURE_HASHES", "ACQUIRE", "NATIVE_REVALIDATION",
            "CACHE_WRITE", "QUEUE_SEND", "DONE", "MATCHING_LOCK", "INDEX", "RECEIVE", "SHORTLIST", "COMPARE",
            "INDEX_WRITE", "CACHE_FLUSH", "ACCEPTED_PAIRS", "PUBLISH", "REPLAY_COMPARISONS")
        val PHASE_ATTRIBUTES = setOf("preparation_phase", "producer_phase", "consumer_phase")
        val NUMERIC_ATTRIBUTES = setOf(DiagnosticAttribute.CORRELATION_ID, DiagnosticAttribute.ACQUISITION_RANK,
            DiagnosticAttribute.REFERENCE_COUNT, DiagnosticAttribute.PRODUCER_RANK, DiagnosticAttribute.CONSUMER_RANK,
            DiagnosticAttribute.ENQUEUED_COUNT, DiagnosticAttribute.DEQUEUED_COUNT, DiagnosticAttribute.COMPLETED_COUNT,
            DiagnosticAttribute.QUEUE_SIZE, DiagnosticAttribute.QUEUE_HIGH_WATER, DiagnosticAttribute.INDEX_PROGRESS,
            DiagnosticAttribute.INDEX_TOTAL, DiagnosticAttribute.PRODUCER_ELAPSED_MS, DiagnosticAttribute.CONSUMER_ELAPSED_MS,
            DiagnosticAttribute.PREPARATION_ELAPSED_MS, DiagnosticAttribute.SNAPSHOT_AGE_MS).map { it.wireName }.toSet()

        fun sanitizeScheduler(event: DiagnosticEvent, allowSnapshot: Boolean = false): DiagnosticEvent? {
            val safe = DiagnosticRedactor.sanitizePersisted(event.attributes)
            if (safe["operation"] != "ARTWORK_SCHEDULER") return null
            val stage = safe["stage"] ?: return null
            if (stage != "RUNNING" && stage !in TERMINAL_STAGES && !(allowSnapshot && stage == "CAPTURE_SNAPSHOT")) return null
            val attributes = safe.mapNotNull { (key, value) ->
                when {
                    key == "operation" || key == "stage" -> key to value
                    key in PHASE_ATTRIBUTES && value in SCHEDULER_PHASES -> key to value
                    key in NUMERIC_ATTRIBUTES -> value.toLongOrNull()?.takeIf { it >= 0L }?.let { key to it.toString() }
                    else -> null
                }
            }.toMap()
            if (attributes["correlation_id"] == null) return null
            val outcome = when (stage) {
                "FINISHED" -> DiagnosticOutcome.SUCCEEDED
                "CANCELLED" -> DiagnosticOutcome.SKIPPED
                "FAILED" -> DiagnosticOutcome.FAILED
                "CAPTURE_SNAPSHOT" -> event.outcome
                else -> DiagnosticOutcome.STARTED
            }
            return event.copy(area = DiagnosticArea.MATCHING, name = DiagnosticEventName.GAME_RESOLUTION,
                outcome = outcome, attributes = attributes)
        }
    }
}
