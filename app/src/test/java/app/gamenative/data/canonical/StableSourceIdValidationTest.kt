package app.gamenative.data.canonical

import app.gamenative.data.GameSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StableSourceIdValidationTest {
    @Test
    fun `accepts canonical GOG and Amazon stable source IDs`() {
        assertTrue(StableSourceIdValidation.isValid(GameSource.GOG, "1771589310"))
        assertTrue(
            StableSourceIdValidation.isValid(
                GameSource.AMAZON,
                "amzn1.adg.product.5d35cae7-39d1-4e53-ba92-36004c4a5211",
            ),
        )
    }

    @Test
    fun `rejects malformed or noncanonical GOG IDs`() {
        listOf("001771589310", "gog-1771589310", "0", "-1", "").forEach { value ->
            assertFalse(value, StableSourceIdValidation.isValid(GameSource.GOG, value))
        }
    }

    @Test
    fun `rejects malformed or noncanonical Amazon IDs`() {
        listOf(
            "5d35cae7-39d1-4e53-ba92-36004c4a5211",
            "amzn1.adg.product.5D35CAE7-39D1-4E53-BA92-36004C4A5211",
            "amzn1.adg.product.not-a-uuid",
            "",
        ).forEach { value ->
            assertFalse(value, StableSourceIdValidation.isValid(GameSource.AMAZON, value))
        }
    }

    @Test
    fun `materialization validation rejects the whole malformed source snapshot`() {
        assertThrows(IllegalArgumentException::class.java) {
            StableSourceIdValidation.requireAllValid(
                GameSource.GOG,
                listOf("1771589310", "001771589310"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            StableSourceIdValidation.requireAllValid(
                GameSource.AMAZON,
                listOf(
                    "amzn1.adg.product.5d35cae7-39d1-4e53-ba92-36004c4a5211",
                    "product-id",
                ),
            )
        }
    }
}
