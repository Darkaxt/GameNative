package app.gamenative.library.canonical

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameEntity
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.CanonicalIdGenerator
import app.gamenative.data.canonical.CanonicalNormalization
import app.gamenative.data.canonical.ClassificationState
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.data.canonical.StoreMatchEntity
import app.gamenative.db.dao.CanonicalGameDao
import app.gamenative.db.dao.StoreMatchDao
import app.gamenative.library.canonical.source.OwnedCopyProjection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalGameResolverTest {
    private val accountScope = AccountScope.parse("a".repeat(64))

    @Test
    fun resolverBoundaryTypesRejectMalformedIdentityState() {
        assertThrows(IllegalArgumentException::class.java) {
            TrustedSteamMapping(
                steamAppId = 0,
                mapVersion = SUPPORTED_TRUSTED_MAP_VERSION,
                validatedOneToOne = true,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrustedSteamMapping(
                steamAppId = 620,
                mapVersion = 0,
                validatedOneToOne = true,
            )
        }

        val canonical = canonical(index = 1, title = "Control", steamAppId = null)
        val mismatched = match(
            copy = copy(),
            canonicalId = canonicalId(2),
            method = MatchMethod.UNMATCHED,
            confidence = MatchConfidence.UNMATCHED,
        )
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalResolution(
                canonical = canonical,
                match = mismatched,
                createdCanonical = false,
            )
        }
    }

    @Test
    fun directSteamIdentityOverridesHistoricalUserDecision() = runTest {
        val wrongCanonical = canonical(
            index = 1,
            title = "Wrong game",
            steamAppId = null,
        )
        val historicalDecision = match(
            copy = copy(source = GameSource.STEAM, stableSourceId = "620", directSteamAppId = 620),
            canonicalId = wrongCanonical.canonicalId,
            method = MatchMethod.MANUAL,
            confidence = MatchConfidence.REJECTED,
            decisionSource = MatchDecisionSource.USER,
        )
        val idGenerator = SequentialIdGenerator(start = 10)
        val resolver = resolver(
            canonicals = listOf(wrongCanonical),
            matches = listOf(historicalDecision),
            idGenerator = idGenerator,
        )

        val result = resolver.resolve(
            copy = copy(
                source = GameSource.STEAM,
                stableSourceId = "620",
                displayName = "Portal 2",
                developer = "Valve",
                releaseYear = 2011,
                directSteamAppId = 620,
            ),
            nowEpochMs = 1_000,
        )

        assertTrue(result.createdCanonical)
        assertEquals(canonicalId(10), result.canonical.canonicalId)
        assertEquals(620, result.canonical.steamAppId)
        assertEquals(GameSource.STEAM, result.canonical.primaryMetadataSource)
        assertEquals(MatchMethod.DIRECT_STEAM, result.match.matchMethod)
        assertEquals(MatchConfidence.VERIFIED, result.match.confidence)
        assertEquals(MatchDecisionSource.AUTOMATIC, result.match.decisionSource)
        assertEquals(620, result.match.candidateSteamAppId)
        assertEquals(1, idGenerator.generatedCount)
    }

    @Test
    fun directSteamIdentityReusesUniqueSteamCanonicalAndRefreshesSteamMetadata() = runTest {
        val target = canonical(
            index = 1,
            title = "Old title",
            steamAppId = 620,
            source = GameSource.GOG,
            developer = "Old developer",
            year = 2000,
        )
        val wrong = canonical(index = 2, title = "Wrong", steamAppId = null)
        val copy = copy(
            source = GameSource.STEAM,
            stableSourceId = "620",
            displayName = "Portal 2",
            developer = "Valve Corporation",
            releaseYear = 2011,
            directSteamAppId = 620,
        )
        val resolver = resolver(
            canonicals = listOf(target, wrong),
            matches = listOf(
                match(
                    copy = copy,
                    canonicalId = wrong.canonicalId,
                    method = MatchMethod.UNMATCHED,
                    confidence = MatchConfidence.UNMATCHED,
                ),
            ),
        )

        val result = resolver.resolve(copy, nowEpochMs = 2_000)

        assertFalse(result.createdCanonical)
        assertEquals(target.canonicalId, result.canonical.canonicalId)
        assertEquals("Portal 2", result.canonical.displayName)
        assertEquals("portal 2", result.canonical.matchTitleKey)
        assertEquals("valve", result.canonical.developerKey)
        assertEquals(2011, result.canonical.releaseYear)
        assertEquals(GameSource.STEAM, result.canonical.primaryMetadataSource)
        assertEquals(target.createdAt, result.canonical.createdAt)
        assertEquals(2_000, result.canonical.updatedAt)
        assertEquals(target.canonicalId, result.match.canonicalId)
    }

    @Test
    fun unchangedDirectSteamIdentityPreservesOriginalMatchTime() = runTest {
        val ownedCopy = copy(
            source = GameSource.STEAM,
            stableSourceId = "620",
            displayName = "Portal 2",
            developer = "Valve",
            releaseYear = 2011,
            directSteamAppId = 620,
        )
        val target = canonical(
            index = 1,
            title = "Portal 2",
            steamAppId = 620,
            developer = "Valve",
            year = 2011,
            createdAt = 50,
        )
        val stored = match(
            copy = ownedCopy,
            canonicalId = target.canonicalId,
            method = MatchMethod.DIRECT_STEAM,
            confidence = MatchConfidence.VERIFIED,
            matchedAt = 100,
            candidateSteamAppId = 620,
        )
        val resolver = resolver(
            canonicals = listOf(target),
            matches = listOf(stored),
        )

        val result = resolver.resolve(ownedCopy, nowEpochMs = 2_000)

        assertEquals(target, result.canonical)
        assertEquals(100, result.match.matchedAt)
        assertEquals(stored, result.match)
        assertFalse(result.createdCanonical)
    }

    @Test
    fun storedNonSteamUserDecisionsWinBeforeProvidersAndMetadata() = runTest {
        for (confidence in listOf(MatchConfidence.VERIFIED, MatchConfidence.REJECTED)) {
            val selected = canonical(index = 1, title = "Selected", steamAppId = null)
            val exactSteam = canonical(index = 2, title = "Control", steamAppId = 870780)
            val copy = copy(displayName = "Control", developer = "Remedy Entertainment")
            val stored = match(
                copy = copy,
                canonicalId = selected.canonicalId,
                method = MatchMethod.MANUAL,
                confidence = confidence,
                decisionSource = MatchDecisionSource.USER,
                resolverVersion = 0,
                isPresent = false,
            )
            val resolver = resolver(
                canonicals = listOf(selected, exactSteam),
                matches = listOf(stored),
                providers = setOf(
                    TrustedSteamMappingProvider {
                        error("Provider must not run before a stored user decision")
                    },
                ),
            )

            val result = resolver.resolve(copy, nowEpochMs = 2_000)

            assertEquals(selected, result.canonical)
            assertEquals(selected.canonicalId, result.match.canonicalId)
            assertEquals(MatchMethod.MANUAL, result.match.matchMethod)
            assertEquals(confidence, result.match.confidence)
            assertEquals(MatchDecisionSource.USER, result.match.decisionSource)
            assertEquals(stored.matchedAt, result.match.matchedAt)
            assertTrue(result.match.isPresent)
            assertFalse(result.createdCanonical)
        }
    }

    @Test
    fun currentAutomaticRelationIsReevaluatedWithoutNoOpChurn() = runTest {
        val standalone = canonical(
            index = 1,
            title = "Control",
            steamAppId = null,
            source = GameSource.GOG,
            developer = "Remedy Entertainment",
            year = 2019,
        )
        val ownedCopy = copy()
        val stored = match(
            copy = ownedCopy,
            canonicalId = standalone.canonicalId,
            method = MatchMethod.UNMATCHED,
            confidence = MatchConfidence.UNMATCHED,
            resolverVersion = CURRENT_RESOLVER_VERSION,
            matchedAt = 123,
            isPresent = false,
        )
        var providerCalls = 0
        val idGenerator = SequentialIdGenerator()
        val resolver = resolver(
            canonicals = listOf(standalone),
            matches = listOf(stored),
            providers = setOf(
                TrustedSteamMappingProvider {
                    providerCalls++
                    null
                },
            ),
            idGenerator = idGenerator,
        )

        val result = resolver.resolve(ownedCopy, nowEpochMs = 2_000)

        assertEquals(standalone, result.canonical)
        assertEquals(standalone.canonicalId, result.match.canonicalId)
        assertEquals(MatchMethod.UNMATCHED, result.match.matchMethod)
        assertEquals(MatchConfidence.UNMATCHED, result.match.confidence)
        assertEquals(123, result.match.matchedAt)
        assertTrue(result.match.isPresent)
        assertEquals(1, providerCalls)
        assertEquals(0, idGenerator.generatedCount)
    }

    @Test
    fun currentAutomaticUnmatchedRelationUsesNewIndependentCandidate() = runTest {
        val standalone = canonical(
            index = 1,
            title = "Control",
            steamAppId = null,
            source = GameSource.GOG,
            developer = "Remedy Entertainment",
            year = 2019,
        )
        val steamCandidate = canonical(
            index = 2,
            title = "Control",
            steamAppId = 870780,
            developer = "Remedy Entertainment",
            year = 2019,
        )
        val ownedCopy = copy()
        val resolver = resolver(
            canonicals = listOf(standalone, steamCandidate),
            matches = listOf(
                match(
                    copy = ownedCopy,
                    canonicalId = standalone.canonicalId,
                    method = MatchMethod.UNMATCHED,
                    confidence = MatchConfidence.UNMATCHED,
                    resolverVersion = CURRENT_RESOLVER_VERSION,
                    matchedAt = 123,
                ),
            ),
        )

        val result = resolver.resolve(ownedCopy, nowEpochMs = 2_000)

        assertEquals(steamCandidate.canonicalId, result.canonical.canonicalId)
        assertEquals(MatchMethod.EXACT_METADATA, result.match.matchMethod)
        assertEquals(MatchConfidence.HIGH, result.match.confidence)
        assertEquals(870780, result.match.candidateSteamAppId)
        assertEquals(2_000, result.match.matchedAt)
        assertFalse(result.createdCanonical)
    }

    @Test
    fun acceptedSteamCatalogDecisionSurvivesResolverUpgrade() = runTest {
        val ownedCopy = copy(displayName = "Control", developer = "Remedy", releaseYear = 2019)
        val selected = canonical(
            index = 1,
            title = "Control",
            steamAppId = 870780,
            source = GameSource.GOG,
            developer = "Remedy",
            year = 2019,
        )
        val stored = match(
            copy = ownedCopy,
            canonicalId = selected.canonicalId,
            method = MatchMethod.STEAM_CATALOG,
            confidence = MatchConfidence.HIGH,
            resolverVersion = CURRENT_RESOLVER_VERSION - 1,
            matchedAt = 123,
            candidateSteamAppId = 870780,
        )
        var providerCalls = 0
        val resolver = resolver(
            canonicals = listOf(selected),
            matches = listOf(stored),
            providers = setOf(TrustedSteamMappingProvider { providerCalls++; null }),
        )

        val result = resolver.resolve(ownedCopy, nowEpochMs = 2_000)

        assertEquals(MatchMethod.STEAM_CATALOG, result.match.matchMethod)
        assertEquals(MatchConfidence.HIGH, result.match.confidence)
        assertEquals(870780, result.match.candidateSteamAppId)
        assertEquals(123, result.match.matchedAt)
        assertEquals(0, providerCalls)
    }

    @Test
    fun reviewRequiredSteamCatalogDecisionSurvivesLocalProjection() = runTest {
        val ownedCopy = copy(displayName = "Control Deluxe", developer = "Remedy", releaseYear = 2019)
        val selected = canonical(
            index = 1,
            title = "Control Deluxe",
            steamAppId = null,
            source = GameSource.GOG,
            developer = "Remedy",
            year = 2019,
        )
        val stored = match(
            copy = ownedCopy,
            canonicalId = selected.canonicalId,
            method = MatchMethod.STEAM_CATALOG,
            confidence = MatchConfidence.REVIEW_REQUIRED,
            matchedAt = 123,
            candidateSteamAppId = 870780,
        )
        val resolver = resolver(canonicals = listOf(selected), matches = listOf(stored))

        val result = resolver.resolve(ownedCopy, nowEpochMs = 2_000)

        assertEquals(stored, result.match)
        assertEquals(selected, result.canonical)
    }

    @Test
    fun currentRelationRefreshesItsPrimarySourceMetadataWithoutRematching() = runTest {
        val selected = canonical(
            index = 1,
            title = "Old source title",
            steamAppId = null,
            source = GameSource.GOG,
            developer = "Old developer",
            year = 2018,
            createdAt = 50,
        )
        val ownedCopy = copy(
            displayName = "Updated source title",
            developer = "Updated developer",
            releaseYear = 2019,
        )
        val stored = match(
            copy = ownedCopy,
            canonicalId = selected.canonicalId,
            method = MatchMethod.UNMATCHED,
            confidence = MatchConfidence.UNMATCHED,
            matchedAt = 100,
        )
        val resolver = resolver(
            canonicals = listOf(selected),
            matches = listOf(stored),
        )

        val result = resolver.resolve(ownedCopy, nowEpochMs = 2_000)

        assertEquals(selected.canonicalId, result.canonical.canonicalId)
        assertEquals("Updated source title", result.canonical.displayName)
        assertEquals("updated developer", result.canonical.developerKey)
        assertEquals(2019, result.canonical.releaseYear)
        assertEquals(50, result.canonical.createdAt)
        assertEquals(2_000, result.canonical.updatedAt)
        assertEquals(100, result.match.matchedAt)
        assertFalse(result.createdCanonical)
    }

    @Test
    fun oldAutomaticRelationIsReevaluatedAgainstCurrentRules() = runTest {
        val old = canonical(index = 1, title = "Old unrelated title", steamAppId = null)
        val exact = canonical(
            index = 2,
            title = "Control",
            steamAppId = 870780,
            developer = "Remedy Entertainment",
        )
        val copy = copy(displayName = "Control", developer = "Remedy Entertainment")
        val resolver = resolver(
            canonicals = listOf(old, exact),
            matches = listOf(
                match(
                    copy = copy,
                    canonicalId = old.canonicalId,
                    method = MatchMethod.UNMATCHED,
                    confidence = MatchConfidence.UNMATCHED,
                    resolverVersion = CURRENT_RESOLVER_VERSION - 1,
                ),
            ),
        )

        val result = resolver.resolve(copy, nowEpochMs = 2_000)

        assertEquals(exact.canonicalId, result.canonical.canonicalId)
        assertEquals(MatchMethod.EXACT_METADATA, result.match.matchMethod)
        assertEquals(MatchConfidence.HIGH, result.match.confidence)
        assertEquals(870780, result.match.candidateSteamAppId)
        assertEquals(CURRENT_RESOLVER_VERSION, result.match.resolverVersion)
        assertFalse(result.createdCanonical)
    }

    @Test
    fun oldStandaloneRelationDoesNotCorroborateItselfOrChangeCanonicalId() = runTest {
        val standalone = canonical(
            index = 1,
            title = "Control",
            steamAppId = null,
            developer = "Remedy Entertainment",
        )
        val ownedCopy = copy(
            displayName = "Control",
            developer = "Remedy Entertainment",
        )
        val idGenerator = SequentialIdGenerator(start = 100)
        val resolver = resolver(
            canonicals = listOf(standalone),
            matches = listOf(
                match(
                    copy = ownedCopy,
                    canonicalId = standalone.canonicalId,
                    method = MatchMethod.UNMATCHED,
                    confidence = MatchConfidence.UNMATCHED,
                    resolverVersion = CURRENT_RESOLVER_VERSION - 1,
                ),
            ),
            idGenerator = idGenerator,
        )

        val result = resolver.resolve(ownedCopy, nowEpochMs = 2_000)

        assertEquals(standalone.canonicalId, result.canonical.canonicalId)
        assertEquals(MatchMethod.UNMATCHED, result.match.matchMethod)
        assertEquals(MatchConfidence.UNMATCHED, result.match.confidence)
        assertNull(result.match.candidateSteamAppId)
        assertFalse(result.createdCanonical)
        assertEquals(0, idGenerator.generatedCount)
    }

    @Test
    fun anotherCopyCanCorroborateTheExistingCanonicalDuringReevaluation() = runTest {
        val canonical = canonical(
            index = 1,
            title = "Control",
            steamAppId = null,
            developer = "Remedy Entertainment",
        )
        val ownedCopy = copy(
            displayName = "Control",
            developer = "Remedy Entertainment",
        )
        val siblingCopy = copy(
            source = GameSource.EPIC,
            stableSourceId = "sibling-copy",
            displayName = "Control",
            developer = "Remedy Entertainment",
        )
        val resolver = resolver(
            canonicals = listOf(canonical),
            matches = listOf(
                match(
                    copy = ownedCopy,
                    canonicalId = canonical.canonicalId,
                    method = MatchMethod.UNMATCHED,
                    confidence = MatchConfidence.UNMATCHED,
                    resolverVersion = CURRENT_RESOLVER_VERSION - 1,
                ),
                match(
                    copy = siblingCopy,
                    canonicalId = canonical.canonicalId,
                    method = MatchMethod.EXACT_METADATA,
                    confidence = MatchConfidence.HIGH,
                ),
            ),
        )

        val result = resolver.resolve(ownedCopy, nowEpochMs = 2_000)

        assertEquals(canonical.canonicalId, result.canonical.canonicalId)
        assertEquals(MatchMethod.EXACT_METADATA, result.match.matchMethod)
        assertEquals(MatchConfidence.HIGH, result.match.confidence)
        assertFalse(result.createdCanonical)
    }

    @Test
    fun oldStandaloneRelationCannotManufactureAmbiguityWithIndependentCandidate() = runTest {
        val standalone = canonical(
            index = 1,
            title = "Control",
            steamAppId = null,
            developer = "Remedy Entertainment",
        )
        val independent = canonical(
            index = 2,
            title = "Control",
            steamAppId = 870780,
            developer = "Remedy Entertainment",
        )
        val ownedCopy = copy(
            displayName = "Control",
            developer = "Remedy Entertainment",
        )
        val idGenerator = SequentialIdGenerator(start = 100)
        val resolver = resolver(
            canonicals = listOf(standalone, independent),
            matches = listOf(
                match(
                    copy = ownedCopy,
                    canonicalId = standalone.canonicalId,
                    method = MatchMethod.UNMATCHED,
                    confidence = MatchConfidence.UNMATCHED,
                    resolverVersion = CURRENT_RESOLVER_VERSION - 1,
                ),
            ),
            idGenerator = idGenerator,
        )

        val result = resolver.resolve(ownedCopy, nowEpochMs = 2_000)

        assertEquals(independent.canonicalId, result.canonical.canonicalId)
        assertEquals(MatchMethod.EXACT_METADATA, result.match.matchMethod)
        assertEquals(MatchConfidence.HIGH, result.match.confidence)
        assertEquals(870780, result.match.candidateSteamAppId)
        assertFalse(result.createdCanonical)
        assertEquals(0, idGenerator.generatedCount)
    }

    @Test
    fun resetStandaloneSteamAssociationReusesIdentityWithoutKeepingSteamId() = runTest {
        val standalone = canonical(
            index = 1,
            title = "Control",
            steamAppId = 870780,
            developer = "Remedy Entertainment",
        )
        val ownedCopy = copy(
            displayName = "Control",
            developer = "Remedy Entertainment",
        )
        val idGenerator = SequentialIdGenerator(start = 100)
        val resolver = resolver(
            canonicals = listOf(standalone),
            matches = listOf(
                match(
                    copy = ownedCopy,
                    canonicalId = standalone.canonicalId,
                    method = MatchMethod.UNMATCHED,
                    confidence = MatchConfidence.UNMATCHED,
                    resolverVersion = 0,
                ),
            ),
            idGenerator = idGenerator,
        )

        val result = resolver.resolve(ownedCopy, nowEpochMs = 2_000)

        assertEquals(standalone.canonicalId, result.canonical.canonicalId)
        assertNull(result.canonical.steamAppId)
        assertEquals(MatchMethod.UNMATCHED, result.match.matchMethod)
        assertEquals(MatchConfidence.UNMATCHED, result.match.confidence)
        assertFalse(result.createdCanonical)
        assertEquals(0, idGenerator.generatedCount)
    }

    @Test
    fun validatedSupportedOneToOneMapReusesCompatibleSteamCanonical() = runTest {
        val target = canonical(
            index = 1,
            title = "Steam title",
            steamAppId = 620,
            type = CanonicalAppType.GAME,
        )
        val resolver = resolver(
            canonicals = listOf(target),
            providers = setOf(
                TrustedSteamMappingProvider {
                    TrustedSteamMapping(
                        steamAppId = 620,
                        mapVersion = SUPPORTED_TRUSTED_MAP_VERSION,
                        validatedOneToOne = true,
                    )
                },
            ),
        )

        val result = resolver.resolve(copy(displayName = "Provider title"), nowEpochMs = 2_000)

        assertEquals(target, result.canonical)
        assertEquals(MatchMethod.TRUSTED_DIRECT_MAP, result.match.matchMethod)
        assertEquals(MatchConfidence.VERIFIED, result.match.confidence)
        assertEquals(620, result.match.candidateSteamAppId)
        assertFalse(result.createdCanonical)
    }

    @Test
    fun supportedValidatedMapWinsOverUntrustedMapForSameAppId() = runTest {
        val target = canonical(index = 1, title = "Steam title", steamAppId = 620)
        val resolver = resolver(
            canonicals = listOf(target),
            providers = linkedSetOf(
                TrustedSteamMappingProvider {
                    TrustedSteamMapping(620, SUPPORTED_TRUSTED_MAP_VERSION + 1, true)
                },
                TrustedSteamMappingProvider {
                    TrustedSteamMapping(620, SUPPORTED_TRUSTED_MAP_VERSION, true)
                },
            ),
        )

        val result = resolver.resolve(copy(displayName = "Provider title"), nowEpochMs = 2_000)

        assertEquals(target.canonicalId, result.canonical.canonicalId)
        assertEquals(MatchMethod.TRUSTED_DIRECT_MAP, result.match.matchMethod)
        assertEquals(MatchConfidence.VERIFIED, result.match.confidence)
    }

    @Test
    fun validatedMapCreatesSteamAssociatedCanonicalWhenTargetIsMissing() = runTest {
        val idGenerator = SequentialIdGenerator(start = 5)
        val resolver = resolver(
            providers = setOf(
                TrustedSteamMappingProvider {
                    TrustedSteamMapping(
                        steamAppId = 620,
                        mapVersion = SUPPORTED_TRUSTED_MAP_VERSION,
                        validatedOneToOne = true,
                    )
                },
            ),
            idGenerator = idGenerator,
        )

        val result = resolver.resolve(
            copy(displayName = "Portal 2", developer = "Valve", releaseYear = 2011),
            nowEpochMs = 2_000,
        )

        assertTrue(result.createdCanonical)
        assertEquals(canonicalId(5), result.canonical.canonicalId)
        assertEquals(620, result.canonical.steamAppId)
        assertEquals(MatchMethod.TRUSTED_DIRECT_MAP, result.match.matchMethod)
        assertEquals(MatchConfidence.VERIFIED, result.match.confidence)
    }

    @Test
    fun untrustedOrIncompatibleMapsRemainIndependentReviewCandidates() = runTest {
        data class Case(
            val name: String,
            val copyType: CanonicalAppType,
            val mapping: TrustedSteamMapping,
            val targetType: CanonicalAppType? = null,
        )

        val cases = listOf(
            Case(
                name = "unvalidated",
                copyType = CanonicalAppType.GAME,
                mapping = TrustedSteamMapping(620, SUPPORTED_TRUSTED_MAP_VERSION, false),
            ),
            Case(
                name = "unsupported version",
                copyType = CanonicalAppType.GAME,
                mapping = TrustedSteamMapping(620, SUPPORTED_TRUSTED_MAP_VERSION + 1, true),
            ),
            Case(
                name = "unknown source type",
                copyType = CanonicalAppType.UNKNOWN,
                mapping = TrustedSteamMapping(620, SUPPORTED_TRUSTED_MAP_VERSION, true),
            ),
            Case(
                name = "incompatible target type",
                copyType = CanonicalAppType.GAME,
                mapping = TrustedSteamMapping(620, SUPPORTED_TRUSTED_MAP_VERSION, true),
                targetType = CanonicalAppType.DLC,
            ),
        )

        cases.forEachIndexed { index, case ->
            val target = case.targetType?.let {
                canonical(
                    index = 50 + index,
                    title = "Different title",
                    steamAppId = 620,
                    type = it,
                )
            }
            val resolver = resolver(
                canonicals = listOfNotNull(target),
                providers = setOf(TrustedSteamMappingProvider { case.mapping }),
                idGenerator = SequentialIdGenerator(start = 100 + index),
            )

            val result = resolver.resolve(
                copy(displayName = "No exact candidate ${case.name}", type = case.copyType),
                nowEpochMs = 2_000,
            )

            assertTrue(case.name, result.createdCanonical)
            assertNull(case.name, result.canonical.steamAppId)
            assertEquals(case.name, MatchMethod.OPTIONAL_RESOLVER, result.match.matchMethod)
            assertEquals(case.name, MatchConfidence.REVIEW_REQUIRED, result.match.confidence)
            assertEquals(case.name, 620, result.match.candidateSteamAppId)
        }
    }

    @Test
    fun conflictingTrustedProvidersFailClosedWithoutChoosingSetOrder() = runTest {
        val resolver = resolver(
            providers = setOf(
                TrustedSteamMappingProvider {
                    TrustedSteamMapping(620, SUPPORTED_TRUSTED_MAP_VERSION, true)
                },
                TrustedSteamMappingProvider {
                    TrustedSteamMapping(730, SUPPORTED_TRUSTED_MAP_VERSION, true)
                },
            ),
        )

        val result = resolver.resolve(copy(displayName = "Provider conflict"), nowEpochMs = 2_000)

        assertTrue(result.createdCanonical)
        assertNull(result.canonical.steamAppId)
        assertEquals(MatchMethod.OPTIONAL_RESOLVER, result.match.matchMethod)
        assertEquals(MatchConfidence.REVIEW_REQUIRED, result.match.confidence)
        assertNull(result.match.candidateSteamAppId)
    }

    @Test
    fun exactMetadataUsesEqualDeveloperOrCompatibleYear() = runTest {
        val developerTarget = canonical(
            index = 1,
            title = "Control",
            steamAppId = 870780,
            developer = "Remedy Entertainment",
        )
        val yearTarget = canonical(
            index = 2,
            title = "Portal 2",
            steamAppId = 620,
            developer = "",
            year = 2011,
        )
        val resolver = resolver(canonicals = listOf(developerTarget, yearTarget))

        val developerResult = resolver.resolve(
            copy(displayName = "Control", developer = "Remedy Entertainment, Inc.", releaseYear = null),
            nowEpochMs = 2_000,
        )
        val yearResult = resolver.resolve(
            copy(
                stableSourceId = "second-copy",
                displayName = "Portal 2",
                developer = "",
                releaseYear = 2012,
            ),
            nowEpochMs = 2_000,
        )

        assertEquals(developerTarget.canonicalId, developerResult.canonical.canonicalId)
        assertEquals(MatchConfidence.HIGH, developerResult.match.confidence)
        assertEquals(yearTarget.canonicalId, yearResult.canonical.canonicalId)
        assertEquals(MatchConfidence.HIGH, yearResult.match.confidence)
    }

    @Test
    fun initialExactMatchConvergesPrimarySourceMetadataInOnePass() = runTest {
        val target = canonical(
            index = 1,
            title = "Control",
            steamAppId = null,
            source = GameSource.GOG,
            developer = "Remedy Entertainment",
            year = 2019,
            createdAt = 50,
        )
        val ownedCopy = copy(
            displayName = "Control",
            developer = "Remedy Entertainment",
            releaseYear = 2020,
        )
        val first = resolver(canonicals = listOf(target))
            .resolve(ownedCopy, nowEpochMs = 2_000)

        assertEquals(target.canonicalId, first.canonical.canonicalId)
        assertEquals(2020, first.canonical.releaseYear)
        assertEquals(2_000, first.canonical.updatedAt)
        assertEquals(MatchConfidence.HIGH, first.match.confidence)

        val second = resolver(
            canonicals = listOf(first.canonical),
            matches = listOf(first.match),
        ).resolve(ownedCopy, nowEpochMs = 3_000)

        assertEquals(first.canonical, second.canonical)
        assertEquals(first.canonical.updatedAt, second.canonical.updatedAt)
        assertEquals(first.match.matchedAt, second.match.matchedAt)
        assertFalse(second.createdCanonical)
    }

    @Test
    fun uniqueCompatibleExactCandidateWinsOverSameTitleIncompatibleRows() = runTest {
        val compatible = canonical(
            index = 1,
            title = "Control",
            steamAppId = 870780,
            type = CanonicalAppType.GAME,
            developer = "Remedy Entertainment",
        )
        val incompatible = canonical(
            index = 2,
            title = "Control",
            steamAppId = 999999,
            type = CanonicalAppType.DLC,
            developer = "Remedy Entertainment",
        )
        val resolver = resolver(canonicals = listOf(incompatible, compatible))

        val result = resolver.resolve(
            copy(
                displayName = "Control",
                developer = "Remedy Entertainment",
                type = CanonicalAppType.GAME,
            ),
            nowEpochMs = 2_000,
        )

        assertEquals(compatible.canonicalId, result.canonical.canonicalId)
        assertEquals(MatchConfidence.HIGH, result.match.confidence)
        assertEquals(870780, result.match.candidateSteamAppId)
        assertFalse(result.createdCanonical)
    }

    @Test
    fun uniqueReviewableCandidateIsRetainedBesideIncompatibleRows() = runTest {
        val reviewable = canonical(
            index = 1,
            title = "Control",
            steamAppId = 870780,
            type = CanonicalAppType.GAME,
            developer = "Remedy Entertainment",
        )
        val incompatible = canonical(
            index = 2,
            title = "Control",
            steamAppId = 999999,
            type = CanonicalAppType.DLC,
        )
        val resolver = resolver(canonicals = listOf(reviewable, incompatible))

        val result = resolver.resolve(
            copy(
                displayName = "Control",
                developer = "Different Studio",
                type = CanonicalAppType.GAME,
            ),
            nowEpochMs = 2_000,
        )

        assertTrue(result.createdCanonical)
        assertEquals(MatchConfidence.REVIEW_REQUIRED, result.match.confidence)
        assertEquals(870780, result.match.candidateSteamAppId)
    }

    @Test
    fun ambiguousOrConflictingExactMetadataNeverAutoMerges() = runTest {
        data class Case(
            val name: String,
            val copy: OwnedCopyProjection,
            val candidate: CanonicalGameEntity,
            val expectedConfidence: MatchConfidence,
        )

        val cases = listOf(
            Case(
                name = "developer conflict",
                copy = copy(displayName = "Control", developer = "Different Studio", releaseYear = 2019),
                candidate = canonical(
                    index = 10,
                    title = "Control",
                    steamAppId = 870780,
                    developer = "Remedy Entertainment",
                    year = 2019,
                ),
                expectedConfidence = MatchConfidence.REVIEW_REQUIRED,
            ),
            Case(
                name = "year conflict",
                copy = copy(displayName = "Control", developer = "Remedy", releaseYear = 2024),
                candidate = canonical(
                    index = 11,
                    title = "Control",
                    steamAppId = 870780,
                    developer = "Remedy",
                    year = 2019,
                ),
                expectedConfidence = MatchConfidence.REVIEW_REQUIRED,
            ),
            Case(
                name = "missing corroboration",
                copy = copy(displayName = "Control", developer = "", releaseYear = null),
                candidate = canonical(
                    index = 12,
                    title = "Control",
                    steamAppId = 870780,
                    developer = "",
                    year = null,
                ),
                expectedConfidence = MatchConfidence.REVIEW_REQUIRED,
            ),
            Case(
                name = "unknown source type",
                copy = copy(displayName = "Control", type = CanonicalAppType.UNKNOWN),
                candidate = canonical(index = 13, title = "Control", steamAppId = 870780),
                expectedConfidence = MatchConfidence.REVIEW_REQUIRED,
            ),
            Case(
                name = "incompatible known type",
                copy = copy(displayName = "Control", type = CanonicalAppType.DLC),
                candidate = canonical(
                    index = 14,
                    title = "Control",
                    steamAppId = 870780,
                    type = CanonicalAppType.GAME,
                ),
                expectedConfidence = MatchConfidence.UNMATCHED,
            ),
        )

        cases.forEachIndexed { index, case ->
            val resolver = resolver(
                canonicals = listOf(case.candidate),
                idGenerator = SequentialIdGenerator(start = 100 + index),
            )

            val result = resolver.resolve(case.copy, nowEpochMs = 2_000)

            assertTrue(case.name, result.createdCanonical)
            assertFalse(case.name, result.canonical.canonicalId == case.candidate.canonicalId)
            assertNull(case.name, result.canonical.steamAppId)
            assertEquals(case.name, case.expectedConfidence, result.match.confidence)
            if (case.expectedConfidence == MatchConfidence.REVIEW_REQUIRED) {
                assertEquals(case.name, MatchMethod.EXACT_METADATA, result.match.matchMethod)
                assertEquals(case.name, 870780, result.match.candidateSteamAppId)
            } else {
                assertEquals(case.name, MatchMethod.UNMATCHED, result.match.matchMethod)
                assertNull(case.name, result.match.candidateSteamAppId)
            }
        }
    }

    @Test
    fun multipleExactCandidatesProduceReviewRequiredIndependentCanonical() = runTest {
        val first = canonical(
            index = 1,
            title = "Control",
            steamAppId = 870780,
            developer = "Remedy",
            createdAt = 20,
        )
        val second = canonical(
            index = 2,
            title = "Control",
            steamAppId = 999999,
            developer = "Remedy",
            createdAt = 10,
        )
        val resolver = resolver(canonicals = listOf(first, second))

        val result = resolver.resolve(
            copy(displayName = "Control", developer = "Remedy"),
            nowEpochMs = 2_000,
        )

        assertTrue(result.createdCanonical)
        assertEquals(MatchMethod.EXACT_METADATA, result.match.matchMethod)
        assertEquals(MatchConfidence.REVIEW_REQUIRED, result.match.confidence)
        assertNull(result.match.candidateSteamAppId)
        assertFalse(result.canonical.canonicalId in setOf(first.canonicalId, second.canonicalId))
    }

    @Test
    fun meaningfulEditionMismatchFallsBackToUnmatchedCanonical() = runTest {
        val baseEdition = canonical(
            index = 1,
            title = "Control",
            steamAppId = 870780,
            developer = "Remedy Entertainment",
        )
        val resolver = resolver(canonicals = listOf(baseEdition))

        val result = resolver.resolve(
            copy(
                displayName = "Control Ultimate Edition",
                developer = "Remedy Entertainment",
            ),
            nowEpochMs = 2_000,
        )

        assertTrue(result.createdCanonical)
        assertNull(result.canonical.steamAppId)
        assertEquals(MatchMethod.UNMATCHED, result.match.matchMethod)
        assertEquals(MatchConfidence.UNMATCHED, result.match.confidence)
        assertNull(result.match.candidateSteamAppId)
    }

    private fun resolver(
        canonicals: List<CanonicalGameEntity> = emptyList(),
        matches: List<StoreMatchEntity> = emptyList(),
        providers: Set<TrustedSteamMappingProvider> = emptySet(),
        idGenerator: SequentialIdGenerator = SequentialIdGenerator(start = 1_000),
    ): CanonicalGameResolver = CanonicalGameResolver(
        canonicalGameDao = FakeCanonicalGameDao(canonicals),
        storeMatchDao = FakeStoreMatchDao(matches),
        trustedSteamMappingProviders = providers,
        idGenerator = idGenerator,
    )

    private fun copy(
        source: GameSource = GameSource.GOG,
        stableSourceId: String = "source-id",
        displayName: String = "Control",
        developer: String = "Remedy Entertainment",
        releaseYear: Int? = 2019,
        type: CanonicalAppType = CanonicalAppType.GAME,
        directSteamAppId: Int? = null,
    ): OwnedCopyProjection = OwnedCopyProjection(
        key = OwnedCopyKey(accountScope, source, stableSourceId),
        displayName = displayName,
        developer = developer,
        releaseYear = releaseYear,
        appType = type,
        directSteamAppId = directSteamAppId,
    )

    private fun canonical(
        index: Int,
        title: String,
        steamAppId: Int?,
        source: GameSource = GameSource.STEAM,
        type: CanonicalAppType = CanonicalAppType.GAME,
        developer: String = "",
        year: Int? = null,
        createdAt: Long = index.toLong(),
    ): CanonicalGameEntity = CanonicalGameEntity(
        canonicalId = canonicalId(index),
        steamAppId = steamAppId,
        displayName = CanonicalNormalization.displayName(title),
        matchTitleKey = CanonicalNormalization.titleKey(title),
        primaryMetadataSource = source,
        appType = type,
        releaseYear = year,
        developerKey = CanonicalNormalization.developerKey(developer),
        classificationState = ClassificationState.UNCLASSIFIED,
        steamReviewCount = null,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun match(
        copy: OwnedCopyProjection,
        canonicalId: String,
        method: MatchMethod,
        confidence: MatchConfidence,
        decisionSource: MatchDecisionSource = MatchDecisionSource.AUTOMATIC,
        resolverVersion: Int = CURRENT_RESOLVER_VERSION,
        matchedAt: Long = 100,
        isPresent: Boolean = true,
        candidateSteamAppId: Int? = null,
    ): StoreMatchEntity = StoreMatchEntity(
        accountScope = copy.key.accountScope.value,
        source = copy.key.source,
        stableSourceId = copy.key.stableSourceId,
        canonicalId = canonicalId,
        candidateSteamAppId = candidateSteamAppId,
        matchMethod = method,
        confidence = confidence,
        decisionSource = decisionSource,
        resolverVersion = resolverVersion,
        matchedAt = matchedAt,
        isPresent = isPresent,
        evidenceDisplayName = CanonicalNormalization.displayName(copy.displayName),
        evidenceTitleKey = CanonicalNormalization.titleKey(copy.displayName),
        evidenceDeveloperKey = CanonicalNormalization.developerKey(copy.developer),
        evidenceReleaseYear = copy.releaseYear,
        evidenceAppType = copy.appType,
    )

    private fun canonicalId(index: Int): String =
        "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"

    private inner class SequentialIdGenerator(
        start: Int = 1,
    ) : CanonicalIdGenerator {
        private var next: Int = start
        var generatedCount: Int = 0
            private set

        override fun generate(): CanonicalGameId {
            generatedCount++
            return CanonicalGameId.parse(canonicalId(next++))
        }
    }

    private class FakeCanonicalGameDao(
        entities: List<CanonicalGameEntity>,
    ) : CanonicalGameDao {
        private val rows = entities.associateBy { it.canonicalId }

        override suspend fun get(canonicalId: String): CanonicalGameEntity? = rows[canonicalId]

        override suspend fun findBySteamAppId(steamAppId: Int): CanonicalGameEntity? =
            rows.values.singleOrNull { it.steamAppId == steamAppId }

        override suspend fun findByTitleKey(titleKey: String): List<CanonicalGameEntity> = rows.values
            .filter { it.matchTitleKey == titleKey }
            .sortedWith(compareBy(CanonicalGameEntity::createdAt, CanonicalGameEntity::canonicalId))

        override suspend fun getAll(): List<CanonicalGameEntity> =
            error("Resolver must not load every canonical game")

        override suspend fun updateSteamReviewCountIfMissing(
            canonicalId: String,
            steamAppId: Int,
            totalReviews: Long,
        ): Int = error("Resolver must not persist Steam review counts")

        override suspend fun insert(entity: CanonicalGameEntity): Unit =
            error("Resolver must not persist canonical games")

        override suspend fun update(entity: CanonicalGameEntity): Unit =
            error("Resolver must not persist canonical games")

        override suspend fun delete(canonicalId: String): Unit =
            error("Resolver must not persist canonical games")
    }

    private class FakeStoreMatchDao(
        entities: List<StoreMatchEntity>,
    ) : StoreMatchDao {
        private data class Key(
            val accountScope: String,
            val source: GameSource,
            val stableSourceId: String,
        )

        private val rows = entities.associateBy(::keyOf)

        override suspend fun get(
            accountScope: String,
            source: GameSource,
            stableSourceId: String,
        ): StoreMatchEntity? = rows[Key(accountScope, source, stableSourceId)]

        override suspend fun getPresent(
            accountScope: String,
            source: GameSource,
            stableSourceId: String,
        ): StoreMatchEntity? = get(accountScope, source, stableSourceId)?.takeIf { it.isPresent }

        override suspend fun getByCanonicalId(canonicalId: String): List<StoreMatchEntity> =
            error("Resolver must not load canonical reference groups")

        override suspend fun getAll(): List<StoreMatchEntity> =
            error("Resolver must not load every store match")

        override suspend fun getPresentWithoutSteamIdentity(
            excludedSource: GameSource,
        ): List<StoreMatchEntity> = error("Resolver must not scan catalog candidates")

        override suspend fun upsert(entity: StoreMatchEntity): Unit =
            error("Resolver must not persist store matches")

        override suspend fun markAbsentForCompleteSnapshot(
            accountScope: String,
            source: GameSource,
        ): Unit = error("Resolver must not update presence")

        override suspend fun markAbsentForSource(source: GameSource): Unit =
            error("Resolver must not update presence")

        override suspend fun markOtherAccountsAbsent(
            accountScope: String,
            source: GameSource,
        ): Unit = error("Resolver must not update presence")

        override suspend fun repoint(fromCanonicalId: String, toCanonicalId: String): Unit =
            error("Resolver must not repoint store matches")

        override suspend fun countPresentReferences(canonicalId: String): Int =
            error("Resolver must not count references")

        override suspend fun countAllReferences(canonicalId: String): Int =
            rows.values.count { it.canonicalId == canonicalId }

        private companion object {
            fun keyOf(entity: StoreMatchEntity): Key = Key(
                accountScope = entity.accountScope,
                source = entity.source,
                stableSourceId = entity.stableSourceId,
            )
        }
    }
}
