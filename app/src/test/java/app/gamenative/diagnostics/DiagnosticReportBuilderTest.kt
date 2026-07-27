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
