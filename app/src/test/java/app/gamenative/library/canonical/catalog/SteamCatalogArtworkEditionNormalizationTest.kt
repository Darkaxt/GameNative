package app.gamenative.library.canonical.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class SteamCatalogArtworkEditionNormalizationTest {
    @get:Rule val timeout = Timeout.seconds(5)

    @Test
    fun deluxeEditionSuffixRetainsItsEditionLabelAndBaseTitle() {
        listOf("Fixture Game Deluxe", "Fixture Game Deluxe Edition", "Fixture Game DELUXE VERSION").forEach { title ->
            assertEquals(setOf("deluxe"), SteamCatalogNormalization.editionTokens(title))
            assertEquals("fixture game", SteamCatalogNormalization.editionBaseTitle(title))
        }
    }

    @Test
    fun deluxeWithinTheActualGameTitleIsNotAnEditionSuffix() {
        assertTrue(SteamCatalogNormalization.editionTokens("Deluxe Paint").isEmpty())
        assertEquals("deluxe paint", SteamCatalogNormalization.editionBaseTitle("Deluxe Paint"))
    }

    @Test
    fun deluxeAsPartOfAnotherWordCannotBecomeAnEditionLabel() {
        assertTrue(SteamCatalogNormalization.editionTokens("Fixture Game Superdeluxe Edition").isEmpty())
        assertEquals("fixture game superdeluxe",
            SteamCatalogNormalization.editionBaseTitle("Fixture Game Superdeluxe Edition"))
    }
}
