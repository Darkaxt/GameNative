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
    fun `sanitize redacts credentials paths and untyped opaque values`() {
        val values = listOf(
            "request failed: https://example.invalid/private?api_key=0123456789abcdef",
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
    fun `sanitize preserves bounded typed public catalog and community values`() {
        val attributes = DiagnosticRedactor.sanitize(
            mapOf(
                DiagnosticAttribute.STEAM_APP_ID to "620",
                DiagnosticAttribute.STOREFRONT_PRODUCT_ID to
                    "amzn1.adg.product.11111111-1111-1111-1111-111111111111",
                DiagnosticAttribute.PUBLIC_TITLE to "Portal 2",
                DiagnosticAttribute.PUBLIC_URL to
                    "https://shared.akamai.steamstatic.com/store_item_assets/header.jpg",
                DiagnosticAttribute.PUBLIC_ROUTE to
                    "/app/620/discussions/0/1234567890123456789/",
                DiagnosticAttribute.PUBLIC_CONTENT_ID to
                    "123456789012345678901234567890123456",
            ),
        )

        assertEquals("620", attributes["steam_app_id"])
        assertEquals(
            "amzn1.adg.product.11111111-1111-1111-1111-111111111111",
            attributes["storefront_product_id"],
        )
        assertEquals("Portal 2", attributes["public_title"])
        assertEquals(
            "https://shared.akamai.steamstatic.com/store_item_assets/header.jpg",
            attributes["public_url"],
        )
        assertEquals(
            "/app/620/discussions/0/1234567890123456789/",
            attributes["public_route"],
        )
        assertEquals(
            "123456789012345678901234567890123456",
            attributes["public_content_id"],
        )
    }

    @Test
    fun `typed public values still redact credentials and local paths`() {
        val attributes = DiagnosticRedactor.sanitize(
            mapOf(
                DiagnosticAttribute.PUBLIC_URL to
                    "https://example.invalid/header.jpg?access_token=secret-value",
                DiagnosticAttribute.PUBLIC_ROUTE to "C:\\Users\\person\\Games\\Portal 2",
            ),
        )

        assertEquals("[redacted]", attributes["public_url"])
        assertEquals("[redacted]", attributes["public_route"])
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
