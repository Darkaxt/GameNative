# Steam Library Stage 0 Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Delegated agents must not invoke or spawn subagents.

**Goal:** Add a bounded, privacy-safe diagnostic event system that explains current filtering and action routing and provides the reporting contract for later indexing, matching, metadata, and detail stages.

**Architecture:** New Steam-library work records structured events through one explicit facade rather than persisting arbitrary Timber output. Events contain enum-defined names and allowlisted attributes, are sanitized before being written to a rotating JSONL store in app-private storage, and are included in crash reports or manually exported from Debug settings. No diagnostics are uploaded automatically.

**Tech Stack:** Kotlin, kotlinx.serialization, app-private files, Timber, Jetpack Compose settings, JUnit 4, Robolectric

---

## File map

**Create:**

- `app/src/main/java/app/gamenative/diagnostics/DiagnosticEvent.kt` — serializable event vocabulary.
- `app/src/main/java/app/gamenative/diagnostics/DiagnosticRedactor.kt` — allowlisted attributes, value sanitization, and short hashed correlation IDs.
- `app/src/main/java/app/gamenative/diagnostics/DiagnosticLogStore.kt` — bounded rotating JSONL persistence.
- `app/src/main/java/app/gamenative/diagnostics/FeatureDiagnostics.kt` — process-wide recording facade.
- `app/src/main/java/app/gamenative/diagnostics/DiagnosticReportBuilder.kt` — user-exportable report.
- `app/src/test/java/app/gamenative/diagnostics/DiagnosticRedactorTest.kt`
- `app/src/test/java/app/gamenative/diagnostics/DiagnosticLogStoreTest.kt`
- `app/src/test/java/app/gamenative/diagnostics/DiagnosticReportBuilderTest.kt`
- `docs/superpowers/reviews/2026-07-27-steam-library-stage-0-cross-check.md` — written after implementation with observed evidence.

**Modify:**

- `app/src/main/java/app/gamenative/PluviaApp.kt:52-79` — initialize diagnostics before the crash handler.
- `app/src/main/java/app/gamenative/CrashHandler.kt:92-129` — record crashes and append diagnostic tail.
- `app/src/main/java/app/gamenative/ui/model/LibraryViewModel.kt:566-1045` — record filter duration/result counts without search text or titles.
- `app/src/main/java/app/gamenative/ui/PluviaMain.kt:160-217,263-269` — record game-resolution and launch-request outcomes.
- `app/src/main/java/app/gamenative/ui/screen/settings/SettingsGroupDebug.kt:98-129,180-187` — export and clear diagnostics.
- `app/src/main/res/values/strings.xml` — diagnostics setting labels.

Do not modify `ReleaseTree.kt`: release logcat remains independent, and arbitrary existing Timber messages must not enter the persistent report. Diagnostic values must be fixed programmatic categories or aggregate numbers; never pass user/provider text into an approved attribute just because its key is allowlisted.

### Task 1: Define the safe diagnostic vocabulary

**Files:**
- Create: `app/src/main/java/app/gamenative/diagnostics/DiagnosticEvent.kt`
- Create: `app/src/main/java/app/gamenative/diagnostics/DiagnosticRedactor.kt`
- Test: `app/src/test/java/app/gamenative/diagnostics/DiagnosticRedactorTest.kt`

- [ ] **Step 1: Write the redaction tests**

```kotlin
package app.gamenative.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRedactorTest {
    @Test
    fun `sanitize uses only enum-defined keys and strips control characters`() {
        val result = DiagnosticRedactor.sanitize(
            mapOf(
                DiagnosticAttribute.SOURCE to "STEAM\nforged",
                DiagnosticAttribute.RESULT_COUNT to "42",
            ),
        )

        assertEquals("STEAM forged", result["source"])
        assertEquals("42", result["result_count"])
    }

    @Test
    fun `sanitize redacts urls paths bearer values and jwt values`() {
        val values = listOf(
            "request failed: https://example.invalid/private",
            "/storage/emulated/0/Games/Secret",
            "Bearer abcdef",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature",
        )

        values.forEach { value ->
            val result = DiagnosticRedactor.sanitize(
                mapOf(DiagnosticAttribute.REASON to value),
            )
            assertEquals("[redacted]", result.getValue("reason"))
        }
    }

    @Test
    fun `correlation id is stable short and does not expose input`() {
        val raw = "steam:76561198000000000:620"
        val first = DiagnosticRedactor.correlationId(raw)
        val second = DiagnosticRedactor.correlationId(raw)

        assertEquals(first, second)
        assertEquals(12, first.length)
        assertNotEquals(raw, first)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.diagnostics.DiagnosticRedactorTest"
```

Expected: FAIL because the diagnostics types do not exist.

- [ ] **Step 3: Add the serializable event vocabulary**

Create `DiagnosticEvent.kt`:

```kotlin
package app.gamenative.diagnostics

import kotlinx.serialization.Serializable

@Serializable
data class DiagnosticEvent(
    val timestampEpochMs: Long,
    val sessionId: String,
    val area: DiagnosticArea,
    val name: DiagnosticEventName,
    val outcome: DiagnosticOutcome,
    val durationMs: Long? = null,
    val attributes: Map<String, String> = emptyMap(),
)

@Serializable
enum class DiagnosticArea {
    APP,
    DATABASE,
    CANONICAL_INDEX,
    MATCHING,
    LIBRARY_FILTER,
    ACTION_ROUTING,
    METADATA,
    FACETS,
    GAME_DETAIL,
    REVIEWS,
    DISCUSSIONS,
}

@Serializable
enum class DiagnosticOutcome {
    STARTED,
    SUCCEEDED,
    FAILED,
    SKIPPED,
    CACHE_HIT,
    STALE,
    UNAVAILABLE,
}

@Serializable
enum class DiagnosticEventName {
    APP_STARTED,
    APP_CRASHED,
    DATABASE_MIGRATION,
    CANONICAL_INDEX_BUILD,
    MATCH_RESOLUTION,
    LIBRARY_FILTER,
    GAME_RESOLUTION,
    ACTION_ROUTE,
    METADATA_FETCH,
    FACET_REFRESH,
    DETAIL_SECTION,
    REVIEW_PAGE,
    DISCUSSION_PAGE,
}

enum class DiagnosticAttribute(val wireName: String) {
    APP_VERSION("app_version"),
    BUILD_FLAVOR("build_flavor"),
    SOURCE("source"),
    OPERATION("operation"),
    REASON("reason"),
    ERROR_TYPE("error_type"),
    RESULT_COUNT("result_count"),
    STEAM_COUNT("steam_count"),
    GOG_COUNT("gog_count"),
    EPIC_COUNT("epic_count"),
    AMAZON_COUNT("amazon_count"),
    CUSTOM_COUNT("custom_count"),
    CANONICAL_COUNT("canonical_count"),
    COPY_COUNT("copy_count"),
    MATCH_METHOD("match_method"),
    CONFIDENCE("confidence"),
    PROVIDER("provider"),
    CACHE_STATE("cache_state"),
    HTTP_STATUS("http_status"),
    FILTER_GROUPS("filter_groups"),
    TAG_MODE("tag_mode"),
    POPULARITY_THRESHOLD("popularity_threshold"),
    SECTION("section"),
    CAPABILITY("capability"),
    DB_VERSION("db_version"),
    MIGRATION("migration"),
    CORRELATION_ID("correlation_id"),
}
```

- [ ] **Step 4: Implement sanitization and correlation**

Create `DiagnosticRedactor.kt`:

```kotlin
package app.gamenative.diagnostics

import java.security.MessageDigest

object DiagnosticRedactor {
    private const val MAX_VALUE_LENGTH = 120

    private val forbiddenPatterns = listOf(
        Regex("(?i)https?://"),
        Regex("(?i)(?:/storage/|/sdcard/|/data/user/)"),
        Regex("(?i)[a-z]:[\\\\/]"),
        Regex("(?i)bearer\\s+"),
        Regex("(?i)(?:token|secret|authorization|cookie)\\s*[=:]"),
        Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"),
        Regex("[A-Za-z0-9_-]{32,}"),
    )
    private val allowedWireNames = DiagnosticAttribute.values().mapTo(mutableSetOf()) { it.wireName }

    fun sanitize(attributes: Map<DiagnosticAttribute, String>): Map<String, String> =
        attributes.entries.associate { (key, rawValue) ->
            key.wireName to sanitizeValue(rawValue)
        }

    internal fun sanitizePersisted(attributes: Map<String, String>): Map<String, String> =
        attributes
            .filterKeys(allowedWireNames::contains)
            .mapValues { (_, rawValue) -> sanitizeValue(rawValue) }

    fun correlationId(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun sanitizeValue(rawValue: String): String {
        val singleLine = rawValue
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .trim()
        if (forbiddenPatterns.any { it.containsMatchIn(singleLine) }) return "[redacted]"
        return singleLine.take(MAX_VALUE_LENGTH)
    }
}
```

- [ ] **Step 5: Run the test and verify it passes**

Run:

```bash
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.diagnostics.DiagnosticRedactorTest"
```

Expected: PASS.

- [ ] **Step 6: Commit the vocabulary**

```bash
git add app/src/main/java/app/gamenative/diagnostics app/src/test/java/app/gamenative/diagnostics/DiagnosticRedactorTest.kt
git commit -m "feat: define safe feature diagnostics events"
```

### Task 2: Persist events in a bounded rotating store

**Files:**
- Create: `app/src/main/java/app/gamenative/diagnostics/DiagnosticLogStore.kt`
- Test: `app/src/test/java/app/gamenative/diagnostics/DiagnosticLogStoreTest.kt`

- [ ] **Step 1: Write store tests for ordering, rotation, and clearing**

```kotlin
package app.gamenative.diagnostics

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticLogStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `recent returns chronological tail across rotations`() {
        val directory = temporaryFolder.newFolder("diagnostics")
        val store = DiagnosticLogStore(directory, json, maxFileBytes = 1, maxFiles = 2)

        repeat(4) { index -> store.append(event(index.toLong())) }

        assertEquals(listOf(2L, 3L), store.recent(10).map { it.timestampEpochMs })
        assertTrue(directory.listFiles().orEmpty().size <= 2)
    }

    @Test
    fun `append drops unknown keys and redacts forbidden approved values`() {
        val store = DiagnosticLogStore(
            temporaryFolder.newFolder("privacy"),
            json,
        )
        store.append(
            event(1).copy(
                attributes = mapOf(
                    "unapproved" to "steam:76561198000000000:620",
                    "reason" to "failed at https://example.invalid/private",
                ),
            ),
        )

        val attributes = store.recent(1).single().attributes
        assertFalse(attributes.containsKey("unapproved"))
        assertEquals("[redacted]", attributes["reason"])
    }

    @Test
    fun `clear removes every rotated file`() {
        val directory = temporaryFolder.newFolder("diagnostics")
        val store = DiagnosticLogStore(directory, json, maxFileBytes = 220, maxFiles = 3)
        repeat(8) { index -> store.append(event(index.toLong())) }

        store.clear()

        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertTrue(store.recent(10).isEmpty())
    }

    private fun event(timestamp: Long) = DiagnosticEvent(
        timestampEpochMs = timestamp,
        sessionId = "session",
        area = DiagnosticArea.APP,
        name = DiagnosticEventName.APP_STARTED,
        outcome = DiagnosticOutcome.SUCCEEDED,
    )
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

```bash
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.diagnostics.DiagnosticLogStoreTest"
```

Expected: FAIL because `DiagnosticLogStore` does not exist.

- [ ] **Step 3: Implement deterministic JSONL rotation**

Create `DiagnosticLogStore.kt`:

```kotlin
package app.gamenative.diagnostics

import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DiagnosticLogStore(
    private val directory: File,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val maxFileBytes: Long = 512L * 1024L,
    private val maxFiles: Int = 3,
) {
    init {
        require(maxFileBytes > 0)
        require(maxFiles > 0)
        check(directory.isDirectory || directory.mkdirs()) {
            "Unable to create diagnostic directory"
        }
    }

    @Synchronized
    fun append(event: DiagnosticEvent) {
        val safeEvent = event.copy(
            attributes = DiagnosticRedactor.sanitizePersisted(event.attributes),
        )
        val line = json.encodeToString(safeEvent) + "\n"
        val bytes = line.toByteArray(Charsets.UTF_8).size
        val current = file(0)
        if (current.exists() && current.length() + bytes > maxFileBytes) rotate()
        file(0).appendText(line, Charsets.UTF_8)
    }

    @Synchronized
    fun recent(limit: Int): List<DiagnosticEvent> {
        if (limit <= 0) return emptyList()
        return (0 until maxFiles)
            .asSequence()
            .map(::file)
            .filter(File::exists)
            .flatMap { it.readLines(Charsets.UTF_8).asReversed().asSequence() }
            .mapNotNull { line -> runCatching { json.decodeFromString<DiagnosticEvent>(line) }.getOrNull() }
            .take(limit)
            .toList()
            .asReversed()
    }

    @Synchronized
    fun clear() {
        directory.listFiles().orEmpty()
            .filter { it.name.startsWith(FILE_PREFIX) }
            .forEach { diagnosticFile ->
                check(diagnosticFile.delete() || !diagnosticFile.exists()) {
                    "Unable to delete diagnostic rotation"
                }
            }
    }

    private fun rotate() {
        val oldest = file(maxFiles - 1)
        check(oldest.delete() || !oldest.exists()) { "Unable to delete oldest diagnostic rotation" }
        for (index in maxFiles - 2 downTo 0) {
            val source = file(index)
            if (source.exists()) {
                val target = file(index + 1)
                if (!source.renameTo(target)) {
                    source.copyTo(target, overwrite = true)
                    check(source.delete()) { "Unable to finalize diagnostic rotation" }
                }
            }
        }
    }

    private fun file(index: Int): File = File(directory, "$FILE_PREFIX.$index.jsonl")

    private companion object {
        const val FILE_PREFIX = "feature-events"
    }
}
```

- [ ] **Step 4: Run the store tests**

```bash
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.diagnostics.DiagnosticLogStoreTest"
```

Expected: PASS.

- [ ] **Step 5: Commit bounded persistence**

```bash
git add app/src/main/java/app/gamenative/diagnostics/DiagnosticLogStore.kt app/src/test/java/app/gamenative/diagnostics/DiagnosticLogStoreTest.kt
git commit -m "feat: persist bounded feature diagnostics"
```

### Task 3: Add the recording facade and report builder

**Files:**
- Create: `app/src/main/java/app/gamenative/diagnostics/FeatureDiagnostics.kt`
- Create: `app/src/main/java/app/gamenative/diagnostics/DiagnosticReportBuilder.kt`
- Test: `app/src/test/java/app/gamenative/diagnostics/DiagnosticReportBuilderTest.kt`

- [ ] **Step 1: Write the report test**

```kotlin
package app.gamenative.diagnostics

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticReportBuilderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `report includes event evidence and excludes raw correlation input`() {
        val store = DiagnosticLogStore(
            directory = temporaryFolder.newFolder("report"),
            json = Json { ignoreUnknownKeys = true },
        )
        val rawId = "steam:76561198000000000:620"
        store.append(
            DiagnosticEvent(
                timestampEpochMs = 100,
                sessionId = "session-a",
                area = DiagnosticArea.ACTION_ROUTING,
                name = DiagnosticEventName.ACTION_ROUTE,
                outcome = DiagnosticOutcome.FAILED,
                attributes = mapOf(
                    "reason" to "copy_missing",
                    "correlation_id" to DiagnosticRedactor.correlationId(rawId),
                    "unapproved" to rawId,
                ),
            ),
        )

        val report = DiagnosticReportBuilder.build(
            header = DiagnosticReportHeader(
                appVersion = "1.2.3 (24)",
                buildFlavor = "legacy",
                device = "Test Device",
                androidVersion = "35",
            ),
            events = store.recent(100),
        )

        assertTrue(report.contains("ACTION_ROUTE"))
        assertTrue(report.contains("copy_missing"))
        assertFalse(report.contains(rawId))
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

```bash
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.diagnostics.DiagnosticReportBuilderTest"
```

Expected: FAIL because the facade/report types do not exist.

- [ ] **Step 3: Implement the process-wide facade**

Create `FeatureDiagnostics.kt`:

```kotlin
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

    fun recent(limit: Int = 1_000): List<DiagnosticEvent> = try {
        store?.recent(limit).orEmpty()
    } catch (error: Exception) {
        Timber.tag("FeatureDiagnostics").w(error, "Unable to read feature diagnostics")
        emptyList()
    }

    fun clear(): Boolean = try {
        store?.clear()
        true
    } catch (error: Exception) {
        Timber.tag("FeatureDiagnostics").w(error, "Unable to clear diagnostics")
        false
    }
}
```

- [ ] **Step 4: Implement deterministic report formatting**

Create `DiagnosticReportBuilder.kt`:

```kotlin
package app.gamenative.diagnostics

import android.content.Context
import android.os.Build
import app.gamenative.BuildConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class DiagnosticReportHeader(
    val appVersion: String,
    val buildFlavor: String,
    val device: String,
    val androidVersion: String,
)

object DiagnosticReportBuilder {
    private val json = Json { encodeDefaults = true }

    fun build(context: Context, eventLimit: Int = 1_000): String {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return build(
            header = DiagnosticReportHeader(
                appVersion = "${packageInfo.versionName} (${packageInfo.longVersionCode})",
                buildFlavor = BuildConfig.FLAVOR,
                device = "${Build.MANUFACTURER} ${Build.MODEL}",
                androidVersion = Build.VERSION.RELEASE,
            ),
            events = FeatureDiagnostics.recent(eventLimit),
        )
    }

    internal fun build(
        header: DiagnosticReportHeader,
        events: List<DiagnosticEvent>,
    ): String = buildString {
        appendLine("GameNative feature diagnostic report")
        appendLine("App: ${header.appVersion}")
        appendLine("Flavor: ${header.buildFlavor}")
        appendLine("Device: ${header.device}")
        appendLine("Android: ${header.androidVersion}")
        appendLine("Events: ${events.size}")
        appendLine("Upload: manual export only")
        appendLine()
        events.forEach { event -> appendLine(json.encodeToString(event)) }
    }
}
```

- [ ] **Step 5: Run all diagnostics unit tests**

```bash
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.diagnostics.*"
```

Expected: PASS.

- [ ] **Step 6: Commit the facade and report**

```bash
git add app/src/main/java/app/gamenative/diagnostics app/src/test/java/app/gamenative/diagnostics
git commit -m "feat: export structured feature diagnostics"
```

### Task 4: Initialize diagnostics and attach them to crash reports

**Files:**
- Modify: `app/src/main/java/app/gamenative/PluviaApp.kt:52-79`
- Modify: `app/src/main/java/app/gamenative/CrashHandler.kt:92-129`

- [ ] **Step 1: Initialize diagnostics before installing the crash handler**

Add imports in `PluviaApp.kt`:

```kotlin
import app.gamenative.diagnostics.FeatureDiagnostics
```

Replace the crash-handler initialization block with:

```kotlin
FeatureDiagnostics.initialize(this)

// Init our custom crash handler after diagnostics so crashes include the event tail.
CrashHandler.initialize(this)
```

Keep Timber planting before this block.

- [ ] **Step 2: Record the crash without persisting its message**

Add imports in `CrashHandler.kt`:

```kotlin
import app.gamenative.diagnostics.DiagnosticArea
import app.gamenative.diagnostics.DiagnosticAttribute
import app.gamenative.diagnostics.DiagnosticEventName
import app.gamenative.diagnostics.DiagnosticOutcome
import app.gamenative.diagnostics.DiagnosticReportBuilder
import app.gamenative.diagnostics.FeatureDiagnostics
```

At the start of `uncaughtException`, before `saveCrashToFile`, add:

```kotlin
FeatureDiagnostics.record(
    area = DiagnosticArea.APP,
    name = DiagnosticEventName.APP_CRASHED,
    outcome = DiagnosticOutcome.FAILED,
    attributes = mapOf(
        DiagnosticAttribute.ERROR_TYPE to throwable.javaClass.simpleName,
    ),
)
```

Do not record `throwable.message`; it may contain paths, titles, URLs, or account data.

- [ ] **Step 3: Append the bounded event tail to the existing crash file**

In the `crashReport = buildString` block, after the Logcat section, add:

```kotlin
appendLine()
appendLine("---------- Feature Diagnostics ----------")
appendLine(DiagnosticReportBuilder.build(context, eventLimit = 100))
```

- [ ] **Step 4: Compile both debug variants**

```bash
./gradlew :app:compileLegacyDebugKotlin :app:compileModernDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit application/crash wiring**

```bash
git add app/src/main/java/app/gamenative/PluviaApp.kt app/src/main/java/app/gamenative/CrashHandler.kt
git commit -m "feat: include feature diagnostics in crash reports"
```

### Task 5: Add export and clear actions to Debug settings

**Files:**
- Modify: `app/src/main/java/app/gamenative/ui/screen/settings/SettingsGroupDebug.kt:98-129,180-187`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add user-facing strings**

Add to `app/src/main/res/values/strings.xml`:

```xml
<string name="settings_debug_export_feature_diagnostics_title">Export feature diagnostics</string>
<string name="settings_debug_export_feature_diagnostics_subtitle">Save a privacy-filtered report for library, metadata, and launch troubleshooting</string>
<string name="settings_debug_clear_feature_diagnostics_title">Clear feature diagnostics</string>
<string name="settings_debug_clear_feature_diagnostics_subtitle">Delete locally stored feature events</string>
<string name="settings_debug_feature_diagnostics_exported">Diagnostic report saved</string>
<string name="settings_debug_feature_diagnostics_cleared">Feature diagnostics cleared</string>
<string name="settings_debug_feature_diagnostics_export_failed">Could not save diagnostic report</string>
<string name="settings_debug_feature_diagnostics_clear_failed">Could not clear feature diagnostics</string>
```

- [ ] **Step 2: Add the document export launcher**

Add imports to `SettingsGroupDebug.kt`:

```kotlin
import androidx.compose.runtime.rememberCoroutineScope
import app.gamenative.diagnostics.DiagnosticReportBuilder
import app.gamenative.diagnostics.FeatureDiagnostics
import kotlinx.coroutines.launch
```

Immediately after `val context = LocalContext.current`, add:

```kotlin
val scope = rememberCoroutineScope()
```

Immediately after the existing `saveLogCat` launcher, add:

```kotlin
val saveFeatureDiagnostics = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("text/plain"),
) { resultUri ->
    resultUri?.let { uri ->
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val outputStream = checkNotNull(context.contentResolver.openOutputStream(uri))
                    outputStream.use {
                        it.write(DiagnosticReportBuilder.build(context).toByteArray(Charsets.UTF_8))
                    }
                }
                SnackbarManager.show(context.getString(R.string.settings_debug_feature_diagnostics_exported))
            } catch (_: Exception) {
                SnackbarManager.show(context.getString(R.string.settings_debug_feature_diagnostics_export_failed))
            }
        }
    }
}
```

- [ ] **Step 3: Add export and clear menu rows**

Inside `SettingsGroup`, immediately after the existing Save logcat row, add:

```kotlin
SettingsMenuLink(
    colors = settingsTileColorsAlt(),
    title = { Text(text = stringResource(R.string.settings_debug_export_feature_diagnostics_title)) },
    subtitle = { Text(text = stringResource(R.string.settings_debug_export_feature_diagnostics_subtitle)) },
    onClick = {
        saveFeatureDiagnostics.launch("gamenative_feature_diagnostics_${CrashHandler.timestamp}.txt")
    },
)

SettingsMenuLink(
    colors = settingsTileColors(),
    title = { Text(text = stringResource(R.string.settings_debug_clear_feature_diagnostics_title)) },
    subtitle = { Text(text = stringResource(R.string.settings_debug_clear_feature_diagnostics_subtitle)) },
    onClick = {
        scope.launch {
            val message = if (withContext(Dispatchers.IO) { FeatureDiagnostics.clear() }) {
                R.string.settings_debug_feature_diagnostics_cleared
            } else {
                R.string.settings_debug_feature_diagnostics_clear_failed
            }
            SnackbarManager.show(context.getString(message))
        }
    },
)
```

- [ ] **Step 4: Compile resources and Compose code**

```bash
./gradlew :app:compileLegacyDebugKotlin :app:compileModernDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit settings integration**

```bash
git add app/src/main/java/app/gamenative/ui/screen/settings/SettingsGroupDebug.kt app/src/main/res/values/strings.xml
git commit -m "feat: export and clear feature diagnostics"
```

### Task 6: Instrument current library filtering and game resolution

**Files:**
- Modify: `app/src/main/java/app/gamenative/ui/model/LibraryViewModel.kt:566-1045`
- Modify: `app/src/main/java/app/gamenative/ui/PluviaMain.kt:160-217,263-269`

- [ ] **Step 1: Add filtering instrumentation without recording search text or titles**

Add imports to `LibraryViewModel.kt`:

```kotlin
import android.os.SystemClock
import app.gamenative.diagnostics.DiagnosticArea
import app.gamenative.diagnostics.DiagnosticAttribute
import app.gamenative.diagnostics.DiagnosticEventName
import app.gamenative.diagnostics.DiagnosticOutcome
import app.gamenative.diagnostics.FeatureDiagnostics
import kotlinx.coroutines.CancellationException
```

Add this helper next to `usesStats`:

```kotlin
private fun diagnosticFilterGroups(state: LibraryState): String = buildList {
    if (state.searchQuery.isNotEmpty()) add("search")
    if (state.appInfoSortType.isNotEmpty()) add("app_filter")
    if (state.selectedSteamCollectionIds.isNotEmpty()) add("collection")
    add("source_tab")
}.joinToString(",")
```

The helper records that search is active, never the query.

At the start of `onFilterApps`, replace the direct `return viewModelScope.launch(Dispatchers.IO)` with:

```kotlin
val diagnosticStartedAt = SystemClock.elapsedRealtime()
val diagnosticState = _state.value
FeatureDiagnostics.record(
    area = DiagnosticArea.LIBRARY_FILTER,
    name = DiagnosticEventName.LIBRARY_FILTER,
    outcome = DiagnosticOutcome.STARTED,
    attributes = mapOf(
        DiagnosticAttribute.FILTER_GROUPS to diagnosticFilterGroups(diagnosticState),
    ),
)
val job = viewModelScope.launch(Dispatchers.IO) {
```

Immediately after the final `_state.update` in the launch body, add:

```kotlin
FeatureDiagnostics.record(
    area = DiagnosticArea.LIBRARY_FILTER,
    name = DiagnosticEventName.LIBRARY_FILTER,
    outcome = DiagnosticOutcome.SUCCEEDED,
    durationMs = SystemClock.elapsedRealtime() - diagnosticStartedAt,
    attributes = mapOf(
        DiagnosticAttribute.RESULT_COUNT to totalFound.toString(),
        DiagnosticAttribute.STEAM_COUNT to steamEntries.size.toString(),
        DiagnosticAttribute.GOG_COUNT to gogEntries.size.toString(),
        DiagnosticAttribute.EPIC_COUNT to epicEntries.size.toString(),
        DiagnosticAttribute.AMAZON_COUNT to amazonEntries.size.toString(),
        DiagnosticAttribute.CUSTOM_COUNT to customEntries.size.toString(),
    ),
)
```

After the launch body closes and before `onFilterApps` closes, return the job with failure reporting:

```kotlin
return job.also { filterJob ->
    filterJob.invokeOnCompletion { error ->
        if (error != null && error !is CancellationException) {
            FeatureDiagnostics.record(
                area = DiagnosticArea.LIBRARY_FILTER,
                name = DiagnosticEventName.LIBRARY_FILTER,
                outcome = DiagnosticOutcome.FAILED,
                durationMs = SystemClock.elapsedRealtime() - diagnosticStartedAt,
                attributes = mapOf(
                    DiagnosticAttribute.ERROR_TYPE to error.javaClass.simpleName,
                ),
            )
        }
    }
}
```

Do not record exception messages.

- [ ] **Step 2: Record game resolution outcomes with hashed correlation only**

Add these imports to `PluviaMain.kt`:

```kotlin
import app.gamenative.diagnostics.DiagnosticArea
import app.gamenative.diagnostics.DiagnosticAttribute
import app.gamenative.diagnostics.DiagnosticEventName
import app.gamenative.diagnostics.DiagnosticOutcome
import app.gamenative.diagnostics.DiagnosticRedactor
import app.gamenative.diagnostics.FeatureDiagnostics
```

In `resolveGameAppId`, immediately after `isInstalled` is computed, add:

```kotlin
val correlationId = DiagnosticRedactor.correlationId(appId)
val diagnosticAttributes = mapOf(
    DiagnosticAttribute.SOURCE to gameSource.name,
    DiagnosticAttribute.CORRELATION_ID to correlationId,
)
```

At the top of the existing `if (!isInstalled)` block, before returning `NotFound`, add:

```kotlin
FeatureDiagnostics.record(
    area = DiagnosticArea.ACTION_ROUTING,
    name = DiagnosticEventName.GAME_RESOLUTION,
    outcome = DiagnosticOutcome.FAILED,
    attributes = diagnosticAttributes +
        (DiagnosticAttribute.REASON to "copy_not_installed"),
)
```

Immediately before the final `GameResolutionResult.Success` return, add:

```kotlin
FeatureDiagnostics.record(
    area = DiagnosticArea.ACTION_ROUTING,
    name = DiagnosticEventName.GAME_RESOLUTION,
    outcome = DiagnosticOutcome.SUCCEEDED,
    attributes = diagnosticAttributes +
        (DiagnosticAttribute.REASON to "installed_copy_resolved"),
)
```

Do not record `appId`, `gameId`, game title, account data, or path.

- [ ] **Step 3: Record launch requests through the existing analytics entry point**

In `trackGameLaunched(appId: String)`, before analytics handling, add:

```kotlin
val source = ContainerUtils.extractGameSourceFromContainerId(appId)
FeatureDiagnostics.record(
    area = DiagnosticArea.ACTION_ROUTING,
    name = DiagnosticEventName.ACTION_ROUTE,
    outcome = DiagnosticOutcome.STARTED,
    attributes = mapOf(
        DiagnosticAttribute.SOURCE to source.name,
        DiagnosticAttribute.OPERATION to "play",
        DiagnosticAttribute.CORRELATION_ID to DiagnosticRedactor.correlationId(appId),
    ),
)
```

- [ ] **Step 4: Run diagnostics tests and compile both variants**

```bash
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.diagnostics.*" :app:compileLegacyDebugKotlin :app:compileModernDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit current-flow instrumentation**

```bash
git add app/src/main/java/app/gamenative/ui/model/LibraryViewModel.kt app/src/main/java/app/gamenative/ui/PluviaMain.kt
git commit -m "feat: trace library and action routing outcomes"
```

### Task 7: Verify Stage 0 and cross-check the design

**Files:**
- Create: `docs/superpowers/reviews/2026-07-27-steam-library-stage-0-cross-check.md`

- [ ] **Step 1: Run the complete unit-test matrix**

```bash
./gradlew :app:testLegacyDebugUnitTest :app:testModernDebugUnitTest
```

Expected: BUILD SUCCESSFUL with both unit-test variants passing.

- [ ] **Step 2: Run lint for the modified Android UI/resources**

```bash
./gradlew :app:lintLegacyDebug
```

Expected: BUILD SUCCESSFUL. Record any baseline warnings separately; do not claim success if lint fails.

- [ ] **Step 3: Build a release APK locally enough to verify minification references**

```bash
./gradlew :app:assembleLegacyRelease
```

Expected: BUILD SUCCESSFUL. This does not publish anything.

- [ ] **Step 4: Manually verify export on a device or emulator**

1. Launch the APK.
2. Open Library and change one filter.
3. Attempt to open or play one game.
4. Open `Settings → Debug → Export feature diagnostics`.
5. Save the report.
6. Confirm it contains `APP_STARTED`, `LIBRARY_FILTER`, and `GAME_RESOLUTION` or `ACTION_ROUTE`.
7. Search the report for a known game title, search query, account ID, install path, and token fragment; confirm none appears.
8. Use Clear feature diagnostics, export again, and confirm only events created after clearing remain.

- [ ] **Step 5: Write the Stage 0 design cross-check using observed evidence**

Use the roadmap's cross-check template. Check design Sections 15 and 17-21, especially acceptance criterion 18. Explicitly record:

- Storage is app-private, bounded to three 512 KiB files.
- Export is manual and local-only.
- Attribute names are enum-constrained.
- Forbidden content is absent from the observed report.
- Crash reports contain the final 100 feature events.
- Future stages have event names for index, matching, metadata, facets, details, reviews, and discussions.

- [ ] **Step 6: Commit and push Stage 0 evidence**

```bash
git add docs/superpowers/reviews/2026-07-27-steam-library-stage-0-cross-check.md
git commit -m "docs: cross-check Steam library diagnostics"
git push
```

Stage 1 planning may begin only after this cross-check has no unresolved Critical or High finding.
