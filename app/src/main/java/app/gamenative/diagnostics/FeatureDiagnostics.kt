package app.gamenative.diagnostics

import android.content.Context
import app.gamenative.BuildConfig
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import timber.log.Timber

object FeatureDiagnostics {
    private val sessionId: String = UUID.randomUUID().toString()

    @Volatile
    private var store: DiagnosticLogStore? = null
    @Volatile
    private var focusedCapture: FocusedDiagnosticCapture? = null
    private val gameStates = linkedMapOf<String, DiagnosticEvent>()
    private const val MAX_GAME_STATES = 8_192

    @Synchronized
    fun initialize(context: Context) {
        if (store != null) return
        val initializedStore = try {
            DiagnosticLogStore(
                directory = File(context.filesDir, "diagnostics"),
                json = Json { ignoreUnknownKeys = true },
            )
        } catch (error: Exception) {
            Timber.tag("FeatureDiagnostics").w(error, "Unable to initialize feature diagnostics")
            return
        }
        store = initializedStore
        focusedCapture = try {
            FocusedDiagnosticCapture(
                File(context.filesDir, "diagnostics/focused"),
                context.getSharedPreferences("focused-diagnostics", Context.MODE_PRIVATE),
            )
        } catch (_: Exception) { null }
        record(
            area = DiagnosticArea.APP,
            name = DiagnosticEventName.APP_STARTED,
            outcome = DiagnosticOutcome.SUCCEEDED,
            attributes = mapOf(
                DiagnosticAttribute.APP_VERSION to BuildConfig.VERSION_NAME,
                DiagnosticAttribute.BUILD_FLAVOR to BuildConfig.FLAVOR,
            ),
        )
    }

    fun record(
        area: DiagnosticArea,
        name: DiagnosticEventName,
        outcome: DiagnosticOutcome,
        durationMs: Long? = null,
        attributes: Map<DiagnosticAttribute, String> = emptyMap(),
    ) {
        recordAcknowledged(
            area = area,
            name = name,
            outcome = outcome,
            durationMs = durationMs,
            attributes = attributes,
        )
    }

    internal fun recordAcknowledged(
        area: DiagnosticArea,
        name: DiagnosticEventName,
        outcome: DiagnosticOutcome,
        durationMs: Long? = null,
        attributes: Map<DiagnosticAttribute, String> = emptyMap(),
    ): Boolean {
        val activeStore = store ?: return false
        val event = DiagnosticEvent(
            timestampEpochMs = System.currentTimeMillis(),
            sessionId = sessionId,
            area = area,
            name = name,
            outcome = outcome,
            durationMs = durationMs,
            attributes = DiagnosticRedactor.sanitize(attributes),
        )
        retainGameState(event)
        focusedCapture?.record(event)
        return try {
            activeStore.append(event)
            true
        } catch (error: Exception) {
            Timber.tag("FeatureDiagnostics").w(error, "Unable to persist diagnostic event")
            false
        }
    }

    // Shared scheduler progress must not fan out into the standard log or per-game retained state.
    internal fun recordArtworkScheduler(durationMs: Long, attributes: Map<DiagnosticAttribute, String>) {
        val capture = focusedCapture ?: return
        val state = capture.state()
        if (!state.enabled || state.full) return
        val outcome = when (attributes[DiagnosticAttribute.STAGE]) {
            "FINISHED" -> DiagnosticOutcome.SUCCEEDED
            "CANCELLED" -> DiagnosticOutcome.SKIPPED
            "FAILED" -> DiagnosticOutcome.FAILED
            else -> DiagnosticOutcome.STARTED
        }
        capture.recordScheduler(DiagnosticEvent(
            timestampEpochMs = System.currentTimeMillis(),
            sessionId = sessionId,
            area = DiagnosticArea.MATCHING,
            name = DiagnosticEventName.GAME_RESOLUTION,
            outcome = outcome,
            durationMs = durationMs,
            attributes = DiagnosticRedactor.sanitize(attributes),
        ))
    }

    // Per-original schedules use the ordinary focused title filter, but never the standard disk log.
    internal fun recordArtworkSchedule(attributes: Map<DiagnosticAttribute, String>) {
        val capture = focusedCapture ?: return
        val state = capture.state()
        if (!state.enabled || state.full) return
        capture.record(DiagnosticEvent(
            timestampEpochMs = System.currentTimeMillis(),
            sessionId = sessionId,
            area = DiagnosticArea.MATCHING,
            name = DiagnosticEventName.GAME_RESOLUTION,
            outcome = DiagnosticOutcome.STARTED,
            attributes = DiagnosticRedactor.sanitize(attributes),
        ))
    }

    @Synchronized
    private fun retainGameState(event: DiagnosticEvent) {
        val operation = event.attributes[DiagnosticAttribute.OPERATION.wireName]
        val identity = when (operation) {
            "ARTWORK_TRACE" -> event.attributes[DiagnosticAttribute.CORRELATION_ID.wireName]
            "STEAM_CATALOG_RESOLUTION" -> event.attributes[DiagnosticAttribute.PUBLIC_TITLE.wireName]
            "ARTWORK_ORIGINAL" -> event.attributes[DiagnosticAttribute.CORRELATION_ID.wireName]
                ?: event.attributes[DiagnosticAttribute.PUBLIC_URL.wireName]
            "STEAM_STORE_COOLDOWN" -> "shared"
            else -> null
        } ?: return
        val key = "$operation:${event.attributes[DiagnosticAttribute.SOURCE.wireName]}:$identity"
        if (operation == "ARTWORK_TRACE") {
            // Lifecycle/count summaries must not attach one original's content to another URL,
            // or make the last comparison stand for every pair considered for this game.
            event.attributes[DiagnosticAttribute.PUBLIC_URL_ID.wireName]?.let { original ->
                retainSnapshot("$key:original:$original", event.copy(attributes = event.attributes - pairAttributes),
                    replace = event.attributes[DiagnosticAttribute.STAGE.wireName] == "ORIGINAL_FETCHING")
            }
            event.attributes[DiagnosticAttribute.CONFIDENCE.wireName]?.takeIf { it.startsWith("pair=") }?.let { pair ->
                retainSnapshot("$key:$pair", event.copy(attributes = event.attributes - originalAttributes), replace = true)
            }
            retainSnapshot(key, event.copy(attributes = event.attributes - originalAttributes - pairAttributes))
        } else {
            retainSnapshot(key, event, replace = operation == "STEAM_CATALOG_RESOLUTION" ||
                (operation == "ARTWORK_ORIGINAL" && event.attributes[DiagnosticAttribute.STAGE.wireName] in
                    setOf("FETCHING", "DESCRIPTOR_CACHE_HIT")))
        }
        while (gameStates.size > MAX_GAME_STATES) gameStates.remove(gameStates.keys.first())
    }

    private val originalAttributes = setOf(DiagnosticAttribute.PUBLIC_URL, DiagnosticAttribute.PUBLIC_URL_ID,
        DiagnosticAttribute.PROVIDER, DiagnosticAttribute.PUBLIC_CONTENT_ID).map { it.wireName }.toSet()
    private val pairAttributes = setOf(DiagnosticAttribute.CONFIDENCE, DiagnosticAttribute.MATCH_RESULT).map { it.wireName }.toSet()

    private fun retainSnapshot(key: String, event: DiagnosticEvent, replace: Boolean = false) {
        val previous = gameStates.remove(key)
        gameStates[key] = if (replace) event else event.copy(attributes = previous?.attributes.orEmpty() + event.attributes)
    }

    @Synchronized
    internal fun currentGameStates(): List<DiagnosticEvent> = gameStates.values.toList()

    fun recent(limit: Int = 1_000): List<DiagnosticEvent> {
        val activeStore = store ?: return emptyList()
        return try {
            activeStore.recent(limit)
        } catch (error: Exception) {
            Timber.tag("FeatureDiagnostics").w(error, "Unable to read feature diagnostics")
            emptyList()
        }
    }

    fun focusedCaptureState(): FocusedDiagnosticCaptureState = focusedCapture?.state()
        ?: FocusedDiagnosticCaptureState(available = false)

    fun startFocusedCapture(title: String): Boolean = focusedCapture?.start(title) ?: false

    fun stopFocusedCapture(): Boolean = focusedCapture?.stop() ?: false

    internal fun focusedCaptureReport(): FocusedDiagnosticCaptureReport = focusedCapture?.report()
        ?: FocusedDiagnosticCaptureReport(focusedCaptureState(), emptyList(), 8L * 1024L * 1024L)

    @Synchronized
    fun clear(): Boolean {
        gameStates.clear()
        val activeStore = store ?: return false
        val cleared = try {
            activeStore.clear()
            true
        } catch (error: Exception) {
            Timber.tag("FeatureDiagnostics").w(error, "Unable to clear diagnostics")
            false
        }
        return (focusedCapture?.clear() ?: true) && cleared
    }
}
