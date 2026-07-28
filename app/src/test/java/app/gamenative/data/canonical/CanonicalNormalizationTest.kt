package app.gamenative.data.canonical

import app.gamenative.enums.AppType
import java.time.Year
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalNormalizationTest {
    @Test
    fun `title normalization retains edition tokens`() {
        assertEquals(
            "control ultimate edition",
            CanonicalNormalization.titleKey("  Control™: Ultimate Edition  "),
        )
        assertNotEquals(
            CanonicalNormalization.titleKey("Control"),
            CanonicalNormalization.titleKey("Control Ultimate Edition"),
        )
    }

    @Test
    fun `matching keys normalize Unicode punctuation symbols and whitespace`() {
        val inputs = mapOf(
            "  Ｃｏｎｔｒｏｌ®\tUltimate—Edition ©  " to "control ultimate edition",
            "NieR:Automata™ Game-of-the-YoRHa Edition" to
                "nier automata game of the yorha edition",
            "Half-Life® 2" to "half life 2",
            "A+++ Game" to "a game",
        )

        inputs.forEach { (input, expected) ->
            assertEquals(expected, CanonicalNormalization.titleKey(input))
        }
    }

    @Test
    fun `display names preserve case while normalizing Unicode and whitespace`() {
        assertEquals(
            "Control Ultimate Edition",
            CanonicalNormalization.displayName("  Ｃｏｎｔｒｏｌ\t Ultimate　Edition  "),
        )
        assertEquals("", CanonicalNormalization.displayName(" \n\t "))
    }

    @Test
    fun `developer normalization only drops trailing legal suffixes`() {
        val inputs = mapOf(
            "Remedy Entertainment, Inc." to "remedy entertainment",
            "Coffee-Stain North AB" to "coffee stain north",
            "Example Studio GmbH, Ltd." to "example studio",
            "Incorporated Games" to "incorporated games",
            "AB Test Studio" to "ab test studio",
            "" to "",
        )

        inputs.forEach { (input, expected) ->
            assertEquals(expected, CanonicalNormalization.developerKey(input))
        }
    }

    @Test
    fun `release year parsers use UTC deterministically`() {
        assertEquals(2024, CanonicalNormalization.releaseYear("2024-03-21T00:00:00Z"))
        assertEquals(2024, CanonicalNormalization.releaseYear(1_704_067_200L))
        assertNull(CanonicalNormalization.releaseYear(0L))
        assertNull(CanonicalNormalization.releaseYear(-1L))
        assertNull(CanonicalNormalization.releaseYear("unknown"))
        assertNull(CanonicalNormalization.releaseYear(Long.MAX_VALUE))
    }

    @Test
    fun `release years stay within the supported UTC range`() {
        val currentUtcYear = Year.now(ZoneOffset.UTC).value

        assertNull(CanonicalNormalization.releaseYear("1969-12-31"))
        assertEquals(
            currentUtcYear + 1,
            CanonicalNormalization.releaseYear("${currentUtcYear + 1}-01-01"),
        )
        assertNull(CanonicalNormalization.releaseYear("${currentUtcYear + 2}-01-01"))
    }

    @Test
    fun `unknown app types do not become games`() {
        val directMappings = mapOf(
            AppType.game to CanonicalAppType.GAME,
            AppType.application to CanonicalAppType.APPLICATION,
            AppType.tool to CanonicalAppType.TOOL,
            AppType.demo to CanonicalAppType.DEMO,
            AppType.dlc to CanonicalAppType.DLC,
            AppType.music to CanonicalAppType.SOUNDTRACK,
        )

        directMappings.forEach { (source, expected) ->
            assertEquals(expected, CanonicalNormalization.appType(source))
        }
        (AppType.entries - directMappings.keys).forEach { source ->
            assertEquals(CanonicalAppType.UNKNOWN, CanonicalNormalization.appType(source))
        }
    }

    @Test
    fun `matching enums expose exact persisted contracts`() {
        assertEquals(
            listOf("GAME", "APPLICATION", "TOOL", "DEMO", "DLC", "SOUNDTRACK", "UNKNOWN"),
            CanonicalAppType.entries.map(Enum<*>::name),
        )
        assertEquals(
            listOf("CLASSIFIED", "PARTIALLY_CLASSIFIED", "UNCLASSIFIED"),
            ClassificationState.entries.map(Enum<*>::name),
        )
        assertEquals(
            listOf(
                "DIRECT_STEAM",
                "STORED_USER_DECISION",
                "TRUSTED_DIRECT_MAP",
                "EXACT_METADATA",
                "OPTIONAL_RESOLVER",
                "FUZZY_CANDIDATE",
                "MANUAL",
                "UNMATCHED",
            ),
            MatchMethod.entries.map(Enum<*>::name),
        )
        assertEquals(
            listOf("VERIFIED", "HIGH", "REVIEW_REQUIRED", "REJECTED", "UNMATCHED"),
            MatchConfidence.entries.map(Enum<*>::name),
        )
        assertEquals(
            listOf("AUTOMATIC", "USER"),
            MatchDecisionSource.entries.map(Enum<*>::name),
        )
    }
}
