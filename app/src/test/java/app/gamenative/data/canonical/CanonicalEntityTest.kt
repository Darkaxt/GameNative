package app.gamenative.data.canonical

import app.gamenative.data.GameSource
import app.gamenative.db.converters.CanonicalConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CanonicalEntityTest {
    private val canonicalId = "3f84adbe-7c61-4a4a-83cf-6f2e4ec2eb55"
    private val accountScope = "a".repeat(64)
    private val stableSourceId = EpicStableSourceId.encode("namespace", "catalog/id")

    @Test
    fun `complete preferred copy fields round trip to an owned copy key`() {
        val preference = preference(
            preferredAccountScope = accountScope,
            preferredSource = GameSource.EPIC,
            preferredStableSourceId = stableSourceId,
        )

        assertEquals(
            OwnedCopyKey(
                accountScope = AccountScope.parse(accountScope),
                source = GameSource.EPIC,
                stableSourceId = stableSourceId,
            ),
            preference.preferredCopyKeyOrNull(),
        )
    }

    @Test
    fun `partial preferred copy fields return null`() {
        listOf(
            Triple(null, null, null),
            Triple(accountScope, null, null),
            Triple(null, GameSource.EPIC, null),
            Triple(null, null, stableSourceId),
            Triple(accountScope, GameSource.EPIC, null),
            Triple(accountScope, null, stableSourceId),
            Triple(null, GameSource.EPIC, stableSourceId),
        ).forEach { (scope, source, sourceId) ->
            assertNull(
                preference(
                    preferredAccountScope = scope,
                    preferredSource = source,
                    preferredStableSourceId = sourceId,
                ).preferredCopyKeyOrNull(),
            )
        }
    }

    @Test
    fun `invalid preferred copy fields return null`() {
        listOf(
            Triple("raw-account-id", GameSource.EPIC, stableSourceId),
            Triple(accountScope, GameSource.EPIC, " "),
        ).forEach { (scope, source, sourceId) ->
            assertNull(
                preference(
                    preferredAccountScope = scope,
                    preferredSource = source,
                    preferredStableSourceId = sourceId,
                ).preferredCopyKeyOrNull(),
            )
        }
    }

    @Test
    fun `store match retains structured identity and evidence fields`() {
        val match = StoreMatchEntity(
            accountScope = accountScope,
            source = GameSource.GOG,
            stableSourceId = "gog-game-id",
            canonicalId = canonicalId,
            candidateSteamAppId = 620,
            matchMethod = MatchMethod.EXACT_METADATA,
            confidence = MatchConfidence.HIGH,
            decisionSource = MatchDecisionSource.AUTOMATIC,
            resolverVersion = 1,
            matchedAt = 123L,
            isPresent = true,
            evidenceDisplayName = "Portal 2",
            evidenceTitleKey = "portal 2",
            evidenceDeveloperKey = "valve",
            evidenceReleaseYear = 2011,
            evidenceAppType = CanonicalAppType.GAME,
        )

        assertEquals(accountScope, match.accountScope)
        assertEquals(GameSource.GOG, match.source)
        assertEquals("gog-game-id", match.stableSourceId)
        assertEquals("Portal 2", match.evidenceDisplayName)
        assertEquals("portal 2", match.evidenceTitleKey)
        assertEquals("valve", match.evidenceDeveloperKey)
        assertEquals(2011, match.evidenceReleaseYear)
        assertEquals(CanonicalAppType.GAME, match.evidenceAppType)
    }

    @Test
    fun `canonical enum converters round trip enum names`() {
        val converter = CanonicalConverter()

        GameSource.entries.forEach { value ->
            assertEquals(value.name, converter.fromGameSource(value))
            assertEquals(value, converter.toGameSource(value.name))
        }
        CanonicalAppType.entries.forEach { value ->
            assertEquals(value.name, converter.fromCanonicalAppType(value))
            assertEquals(value, converter.toCanonicalAppType(value.name))
        }
        ClassificationState.entries.forEach { value ->
            assertEquals(value.name, converter.fromClassificationState(value))
            assertEquals(value, converter.toClassificationState(value.name))
        }
        MatchMethod.entries.forEach { value ->
            assertEquals(value.name, converter.fromMatchMethod(value))
            assertEquals(value, converter.toMatchMethod(value.name))
        }
        MatchConfidence.entries.forEach { value ->
            assertEquals(value.name, converter.fromMatchConfidence(value))
            assertEquals(value, converter.toMatchConfidence(value.name))
        }
        MatchDecisionSource.entries.forEach { value ->
            assertEquals(value.name, converter.fromMatchDecisionSource(value))
            assertEquals(value, converter.toMatchDecisionSource(value.name))
        }
    }

    @Test
    fun `canonical enum converters reject unknown database values`() {
        val converter = CanonicalConverter()

        listOf<(String) -> Any>(
            converter::toGameSource,
            converter::toCanonicalAppType,
            converter::toClassificationState,
            converter::toMatchMethod,
            converter::toMatchConfidence,
            converter::toMatchDecisionSource,
        ).forEach { parse ->
            assertThrows(IllegalArgumentException::class.java) {
                parse("UNKNOWN_DATABASE_VALUE")
            }
        }
    }

    private fun preference(
        preferredAccountScope: String?,
        preferredSource: GameSource?,
        preferredStableSourceId: String?,
    ) = CanonicalGamePreferenceEntity(
        canonicalId = canonicalId,
        preferredAccountScope = preferredAccountScope,
        preferredSource = preferredSource,
        preferredStableSourceId = preferredStableSourceId,
        titleOverride = null,
        artworkOverrideJson = null,
        updatedAt = 456L,
    )
}
