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
    fun `sanitize redacts urls paths bearer values jwt values and opaque secrets`() {
        val values = listOf(
            "request failed: https://example.invalid/private",
            "/storage/emulated/0/Games/Secret",
            "Bearer abcdef",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature",
            "0123456789abcdef0123456789abcdef",
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
