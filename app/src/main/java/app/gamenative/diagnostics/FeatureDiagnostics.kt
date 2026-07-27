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
        val activeStore = store ?: return
        val event = DiagnosticEvent(
            timestampEpochMs = System.currentTimeMillis(),
            sessionId = sessionId,
            area = area,
            name = name,
            outcome = outcome,
            durationMs = durationMs,
            attributes = DiagnosticRedactor.sanitize(attributes),
        )
        try {
            activeStore.append(event)
        } catch (error: Exception) {
            Timber.tag("FeatureDiagnostics").w(error, "Unable to persist diagnostic event")
        }
    }

    fun recent(limit: Int = 1_000): List<DiagnosticEvent> {
        val activeStore = store ?: return emptyList()
        return try {
            activeStore.recent(limit)
        } catch (error: Exception) {
            Timber.tag("FeatureDiagnostics").w(error, "Unable to read feature diagnostics")
            emptyList()
        }
    }

    fun clear(): Boolean {
        val activeStore = store ?: return false
        return try {
            activeStore.clear()
            true
        } catch (error: Exception) {
            Timber.tag("FeatureDiagnostics").w(error, "Unable to clear diagnostics")
            false
        }
    }
}
