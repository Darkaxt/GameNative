package app.gamenative.library.canonical.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class SteamCatalogNormalizationTest {
    @Test
    fun `title keys match validated catalog normalization without changing canonical keys`() {
        assertEquals(
            "control ultimate edition",
            SteamCatalogNormalization.titleKey("  Ｃontrol™: Ultimate—Edition®  "),
        )
        assertEquals("clouds and sheep 2", SteamCatalogNormalization.titleKey("Clouds & Sheep 2"))
        assertEquals("clouds and sheep 2", SteamCatalogNormalization.titleKey("Clouds ＆ Sheep 2"))
        assertEquals("baldur s gate 3", SteamCatalogNormalization.titleKey("Baldur’s Gate III"))
    }

    @Test
    fun `developer keys remove only validated legal suffixes`() {
        assertEquals("thekla", SteamCatalogNormalization.developerKey("Thekla, Inc."))
        assertEquals(
            "unknown worlds entertainment",
            SteamCatalogNormalization.developerKey("Unknown Worlds Entertainment, LLC"),
        )
        assertEquals("studio gmbh", SteamCatalogNormalization.developerKey("Studio GmbH"))
    }

    @Test
    fun `title queries add only bounded safe aliases and distinct normalized forms`() {
        assertEquals(
            listOf("Playdead's INSIDE", "INSIDE", "playdead s inside"),
            SteamCatalogNormalization.titleQueries("Playdead's INSIDE"),
        )
        assertEquals(
            listOf("Playdead’s LIMBO", "LIMBO", "playdead s limbo"),
            SteamCatalogNormalization.titleQueries("Playdead’s LIMBO"),
        )
        assertEquals(
            listOf("Baldur's Gate 3", "baldur s gate 3"),
            SteamCatalogNormalization.titleQueries("Baldur's Gate 3"),
        )
        assertEquals(
            listOf("Control Ultimate Edition"),
            SteamCatalogNormalization.titleQueries("Control Ultimate Edition"),
        )
    }

    @Test
    fun `edition tokens and base titles preserve validated edition conflicts`() {
        assertEquals(
            setOf("definitive"),
            SteamCatalogNormalization.editionTokens(
                "Divinity: Original Sin 2 - Definitive Edition",
            ),
        )
        assertEquals(
            setOf("final cut"),
            SteamCatalogNormalization.editionTokens("Disco Elysium - The Final Cut"),
        )
        assertEquals(
            "control",
            SteamCatalogNormalization.editionBaseTitle("Control Ultimate Edition"),
        )
    }
}
