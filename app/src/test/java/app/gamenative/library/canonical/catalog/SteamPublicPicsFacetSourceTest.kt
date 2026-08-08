package app.gamenative.library.canonical.catalog

import `in`.dragonbra.javasteam.types.KeyValue
import org.junit.Assert.assertEquals
import org.junit.Test

class SteamPublicPicsFacetSourceTest {
    @Test
    fun `PICS parser returns only positive canonical facet identifiers`() {
        val keyValues = requireNotNull(
            KeyValue.loadFromString(
                """
                "appinfo"
                {
                    "common"
                    {
                        "genres"
                        {
                            "0" "1"
                            "1" "0"
                            "2" "25"
                            "3" "not-a-number"
                        }
                        "category"
                        {
                            "category_2" "1"
                            "category_0" "1"
                            "other_9" "1"
                            "category_22" "1"
                        }
                        "store_tags"
                        {
                            "0" "19"
                            "1" "492"
                            "2" "-1"
                        }
                    }
                }
                """.trimIndent(),
            ),
        )

        assertEquals(
            SteamPublicPicsFacets(
                genreIds = setOf(1, 25),
                categoryIds = setOf(2, 22),
                storeTagIds = setOf(19, 492),
            ),
            parseSteamPublicPicsFacets(keyValues),
        )
    }
}
