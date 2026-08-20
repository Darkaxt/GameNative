package app.gamenative.diagnostics

import java.io.File
import kotlinx.serialization.encodeToString
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
    fun `append after truncated final record remains readable`() {
        val directory = temporaryFolder.newFolder("truncated")
        File(directory, "feature-events.0.jsonl").writeText(
            "{\"timestampEpochMs\":",
            Charsets.UTF_8,
        )
        val store = DiagnosticLogStore(directory, json)

        store.append(event(42))

        assertEquals(listOf(42L), store.recent(10).map { it.timestampEpochMs })
    }

    @Test
    fun `separator required after truncated record counts toward rotation limit`() {
        val directory = temporaryFolder.newFolder("truncated-rotation")
        val partialRecord = "{\"timestampEpochMs\":"
        File(directory, "feature-events.0.jsonl").writeText(partialRecord, Charsets.UTF_8)
        val appendedEvent = event(43)
        val appendedLineBytes = (json.encodeToString(appendedEvent) + "\n")
            .toByteArray(Charsets.UTF_8)
            .size
        val store = DiagnosticLogStore(
            directory,
            json,
            maxFileBytes = partialRecord.toByteArray(Charsets.UTF_8).size + appendedLineBytes.toLong(),
        )

        store.append(appendedEvent)

        assertTrue(File(directory, "feature-events.1.jsonl").exists())
        assertEquals(listOf(43L), store.recent(10).map { it.timestampEpochMs })
    }

    @Test
    fun `append drops unknown keys and redacts credential-bearing approved values`() {
        val store = DiagnosticLogStore(
            temporaryFolder.newFolder("redaction"),
            json,
        )
        store.append(
            event(1).copy(
                attributes = mapOf(
                    "unapproved" to "steam:76561198000000000:620",
                    "reason" to "failed at https://example.invalid/path?api_key=secret-value",
                ),
            ),
        )

        val attributes = store.recent(1).single().attributes
        assertFalse(attributes.containsKey("unapproved"))
        assertEquals("[redacted]", attributes["reason"])
    }

    @Test
    fun `append preserves approved typed public values`() {
        val store = DiagnosticLogStore(
            temporaryFolder.newFolder("public-values"),
            json,
        )
        store.append(
            event(2).copy(
                attributes = mapOf(
                    "steam_app_id" to "620",
                    "public_url" to "https://store.steampowered.com/app/620/Portal_2/",
                    "public_content_id" to "123456789012345678901234567890123456",
                ),
            ),
        )

        assertEquals(
            mapOf(
                "steam_app_id" to "620",
                "public_url" to "https://store.steampowered.com/app/620/Portal_2/",
                "public_content_id" to "123456789012345678901234567890123456",
            ),
            store.recent(1).single().attributes,
        )
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
