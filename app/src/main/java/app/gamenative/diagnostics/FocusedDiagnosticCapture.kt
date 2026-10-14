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
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val store = DiagnosticLogStore(directory, json, maxFileBytes = maxBytes, maxFiles = 1)
    private val urls = mutableSetOf<String>()
    private val contents = mutableSetOf<String>()
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
        state = state.copy(enabled = false)
        return try { check(save()); true } catch (_: Exception) { failed(); false }
    }

    @Synchronized
    fun record(event: DiagnosticEvent) {
        if (!state.enabled || state.full || state.title.isBlank()) return
        val safe = event.copy(attributes = DiagnosticRedactor.sanitizePersisted(event.attributes))
        val attributes = safe.attributes
        val title = attributes["public_title"]
        val url = attributes["public_url_id"]
        val content = attributes["public_content_id"]
        val selected = if (title != null) title.contains(state.title, ignoreCase = true) else
            (url != null && url in urls) || content?.split('/')?.any { it in contents } == true
        if (!selected && event.name != DiagnosticEventName.APP_STARTED) return
        try {
            if (!store.appendIfFits(safe)) {
                state = state.copy(full = true)
                check(save())
                return
            }
            url?.takeIf { DIGEST.matches(it) }?.let(urls::add)
            content?.takeIf { DIGEST.matches(it) }?.let(contents::add)
        } catch (_: Exception) { failed() }
    }

    @Synchronized
    fun report(): FocusedDiagnosticCaptureReport {
        val events = try {
            check(store.bytesOnDisk() <= maxBytes)
            store.recent(Int.MAX_VALUE) { failed() }.map { it.copy(attributes = DiagnosticRedactor.sanitizePersisted(it.attributes)) }
        } catch (_: Exception) { failed(); emptyList() }
        return FocusedDiagnosticCaptureReport(state, events, maxBytes)
    }

    @Synchronized
    fun clear(): Boolean = try {
        store.clear()
        urls.clear()
        contents.clear()
        state = state.copy(full = false, writeFailures = 0)
        check(save())
        true
    } catch (_: Exception) { failed(); false }

    private fun save(): Boolean = preferences.edit().putString("title", state.title)
        .putBoolean("enabled", state.enabled).putBoolean("full", state.full).commit()

    private fun failed() {
        state = state.copy(writeFailures = (state.writeFailures.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    private companion object {
        val DIGEST = Regex("[a-f0-9]{16}")
    }
}
