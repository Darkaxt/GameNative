package app.gamenative.library.canonical.catalog

import app.gamenative.data.canonical.CanonicalAppType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SteamCatalogCandidatePolicyTest {
    private val policy = SteamCatalogCandidatePolicy()

    @Test
    fun sameNameDredgeAutomaticallyResolvesWithoutDeveloperOrYear() {
        assertEquals(
            CatalogDecision.AutoAccept(1562430),
            policy.evaluate(source("DREDGE"), listOf(candidate(1562430, "DREDGE"))),
        )
    }

    @Test
    fun sameNameDredgeDoesNotRequireMatchingPartyMetadata() {
        assertEquals(
            CatalogDecision.AutoAccept(1562430),
            policy.evaluate(
                source("DREDGE", developer = "Store Publisher", year = 2024),
                listOf(candidate(1562430, "DREDGE", "Black Salt Games", 2023)),
            ),
        )
    }

    @Test
    fun sameNameGamesUseClosestPriorYearWithoutPartyEvidence() {
        assertEquals(
            CatalogDecision.AutoAccept(12),
            policy.evaluate(
                source("Example", year = 2015),
                listOf(candidate(10, "Example", year = 2010), candidate(12, "Example", year = 2012),
                    candidate(20, "Example", year = 2020)),
            ),
        )
    }

    @Test
    fun sameNameOwnedSteamGameWinsBeforeReleaseTimeAndPopularity() {
        assertEquals(CatalogDecision.AutoAccept(84), policy.evaluate(
            source("Example", year = 2020),
            listOf(candidate(42, "Example", year = 2020), candidate(84, "Example", year = 2010)),
            ownedSteamAppIds = setOf(84), reviewCounts = mapOf(42 to 100_000, 84 to 2),
        ))
    }

    @Test
    fun sameNamePopularityBreaksClosestReleaseTimeTie() {
        assertEquals(CatalogDecision.AutoAccept(84), policy.evaluate(
            source("Example", year = 2020),
            listOf(candidate(42, "Example", year = 2019), candidate(84, "Example", year = 2019)),
            reviewCounts = mapOf(42 to 2, 84 to 100_000),
        ))
    }

    @Test
    fun uniqueExactCandidateWithDeveloperEvidenceAutoAccepts() {
        val result = policy.evaluate(
            source = source(title = "Example Deluxe", developer = "Studio Ltd", year = 2020),
            candidates = listOf(candidate(42, "Example Deluxe", "Studio", 2020)),
        )

        assertEquals(CatalogDecision.AutoAccept(42), result)
    }

    @Test
    fun exactTitleAndYearMeetAutomaticScoreBoundary() {
        val result = policy.evaluate(
            source = source(title = "Example", developer = null, year = 2020),
            candidates = listOf(candidate(42, "Example", null, 2020)),
        )

        assertEquals(CatalogDecision.AutoAccept(42), result)
    }

    @Test
    fun uniqueExactCandidateWithAdjacentReleaseYearAutoAcceptsWithoutPartyEvidence() {
        for (candidateYear in listOf(2019, 2021)) {
            val result = policy.evaluate(
                source = source(title = "Example", developer = null, year = 2020),
                candidates = listOf(candidate(42, "Example", "Studio", candidateYear)),
            )

            assertEquals(CatalogDecision.AutoAccept(42), result)
        }
    }

    @Test
    fun corroboratedCrossStoreReleaseYearGapDoesNotBlockAutoAccept() {
        val result = policy.evaluate(
            source = source(title = "Example", developer = "Studio", year = 2005),
            candidates = listOf(candidate(42, "Example", "Studio", 2012)),
        )

        assertEquals(CatalogDecision.AutoAccept(42), result)
    }

    @Test
    fun uniqueClosestCandidateAtOrBeforeSourceYearAutoAccepts() {
        val result = policy.evaluate(
            source = source(title = "Example", developer = "Studio", year = 2015),
            candidates = listOf(
                candidate(10, "Example", "Studio", 2010),
                candidate(12, "Example", "Studio", 2012),
                candidate(20, "Example", "Studio", 2020),
            ),
        )

        assertEquals(CatalogDecision.AutoAccept(12), result)
    }

    @Test
    fun exactSourceYearWinsVerifiedAmbiguity() {
        val result = policy.evaluate(
            source = source(title = "Example", developer = "Studio", year = 2015),
            candidates = listOf(
                candidate(10, "Example", "Studio", 2010),
                candidate(15, "Example", "Studio", 2015),
            ),
        )

        assertEquals(CatalogDecision.AutoAccept(15), result)
    }

    @Test
    fun tiedClosestPriorCandidatesUseStableDefault() {
        val result = policy.evaluate(
            source = source(title = "Example", developer = "Studio", year = 2015),
            candidates = listOf(
                candidate(42, "Example", "Studio", 2012),
                candidate(84, "Example", "Studio", 2012),
            ),
        )

        assertEquals(CatalogDecision.AutoAccept(42), result)
    }

    @Test
    fun futureCandidatesUseClosestReleaseYear() {
        val result = policy.evaluate(
            source = source(title = "Example", developer = "Studio", year = 2010),
            candidates = listOf(
                candidate(42, "Example", "Studio", 2012),
                candidate(84, "Example", "Studio", 2020),
            ),
        )

        assertEquals(CatalogDecision.AutoAccept(42), result)
    }

    @Test
    fun missingSourceYearUsesStableDefault() {
        val result = policy.evaluate(
            source = source(title = "Example", developer = "Studio", year = null),
            candidates = listOf(
                candidate(42, "Example", "Studio", 2012),
                candidate(84, "Example", "Studio", 2020),
            ),
        )

        assertEquals(CatalogDecision.AutoAccept(42), result)
    }

    @Test
    fun titleOnlyCandidateAutomaticallyDefaults() {
        val result = policy.evaluate(
            source = source(title = "Example", developer = null, year = null),
            candidates = listOf(candidate(42, "Example", null, null)),
        )

        assertEquals(CatalogDecision.AutoAccept(42), result)
    }

    @Test
    fun editionConflictRequiresReview() {
        val result = policy.evaluate(
            source = source(
                title = "Example Ultimate Edition",
                developer = "Studio",
                year = 2020,
            ),
            candidates = listOf(
                candidate(42, "Example Definitive Edition", "Studio", 2015),
            ),
        )

        assertEquals(CatalogDecision.ReviewRequired(listOf(42)), result)
    }

    @Test
    fun safeAliasExactCandidateCanAutoAccept() {
        val result = policy.evaluate(
            source = source(title = "Playdead's INSIDE", developer = "Playdead", year = 2016),
            candidates = listOf(candidate(304430, "INSIDE", "Playdead", 2016)),
        )

        assertEquals(CatalogDecision.AutoAccept(304430), result)
    }

    @Test
    fun exactReleaseYearDefaultsDespiteSmallScoreMargin() {
        val result = policy.evaluate(
            source = source(title = "Example", developer = null, year = 2020),
            candidates = listOf(
                candidate(10, "Example", null, 2021),
                candidate(20, "Example", null, 2020),
            ),
        )

        assertEquals(CatalogDecision.AutoAccept(20), result)
    }

    @Test
    fun lowPlausibilityGameHitsAreUnmatched() {
        val result = policy.evaluate(
            source = source(title = "Alan Wake 2", developer = "Remedy", year = 2023),
            candidates = listOf(
                candidate(42, "Unrelated Game", "Other Studio", 2023),
            ),
        )

        assertEquals(CatalogDecision.NoPlausibleCandidate, result)
    }

    @Test
    fun exactNameWinsOverUnrelatedCandidate() {
        val result = policy.evaluate(
            source = source(title = "Example", developer = null, year = null),
            candidates = listOf(
                candidate(42, "Unrelated"),
                candidate(84, "Example"),
            ),
        )

        assertEquals(CatalogDecision.AutoAccept(84), result)
    }

    @Test
    fun equallyEligibleCandidatesUseStableDefault() {
        val result = policy.evaluate(
            source = source(title = "Example", developer = "Studio", year = 2020),
            candidates = listOf(
                candidate(42, "Example", "Studio", 2020),
                candidate(84, "Example", "Studio", 2020),
            ),
        )

        assertEquals(CatalogDecision.AutoAccept(42), result)
    }

    @Test
    fun unknownSourceTypeRequiresReview() {
        val result = policy.evaluate(
            source = source(title = "Example", appType = CanonicalAppType.UNKNOWN),
            candidates = listOf(candidate(42, "Example", appType = CanonicalAppType.GAME)),
        )

        assertEquals(CatalogDecision.ReviewRequired(listOf(42)), result)
    }

    @Test
    fun incompatibleCandidateTypeIsUnmatched() {
        val result = policy.evaluate(
            source = source(title = "Alan Wake 2", appType = CanonicalAppType.GAME),
            candidates = listOf(
                candidate(
                    3_274_290,
                    "Alan Wake 2",
                    appType = CanonicalAppType.APPLICATION,
                ),
            ),
        )

        assertEquals(CatalogDecision.NoPlausibleCandidate, result)
    }

    @Test
    fun sourceDeveloperMatchingSteamPublisherCorroboratesExactCandidate() {
        val result = policy.evaluate(
            source = source(title = "Example", developer = "Publisher", year = null),
            candidates = listOf(
                candidate(
                    steamAppId = 42,
                    title = "Example",
                    developer = "Actual Studio",
                    publisher = "Publisher",
                ),
            ),
        )

        assertEquals(CatalogDecision.AutoAccept(42), result)
    }

    @Test
    fun sameNameOverridesDeveloperConflict() {
        val result = policy.evaluate(
            source = source(title = "Example", developer = "Studio A", year = null),
            candidates = listOf(candidate(42, "Example", "Studio B", null)),
        )

        assertEquals(CatalogDecision.AutoAccept(42), result)
    }

    @Test
    fun sameNameAndYearOverrideDeveloperConflict() {
        val result = policy.evaluate(
            source = source(title = "Example", developer = "Studio A", year = 2020),
            candidates = listOf(candidate(42, "Example", "Studio B", 2020)),
        )

        assertEquals(CatalogDecision.AutoAccept(42), result)
    }

    @Test
    fun sameNameOverridesPublisherOnlyConflict() {
        val result = policy.evaluate(
            source = source(title = "Example", developer = "Studio A", year = 2020),
            candidates = listOf(
                candidate(42, "Example", year = 2020, publisher = "Publisher B"),
            ),
        )

        assertEquals(CatalogDecision.AutoAccept(42), result)
    }

    @Test
    fun missingCandidatePartyEvidenceDoesNotContradictMatchingYear() {
        val result = policy.evaluate(
            source = source(title = "Example", developer = "Studio A", year = 2020),
            candidates = listOf(candidate(42, "Example", year = 2020)),
        )

        assertEquals(CatalogDecision.AutoAccept(42), result)
    }

    @Test
    fun matchingPublisherAndYearDoNotCreateDeveloperConflict() {
        val result = policy.evaluate(
            source = source(title = "Example", developer = "Publisher", year = 2020),
            candidates = listOf(
                candidate(42, "Example", "Actual Studio", 2020, publisher = "Publisher"),
            ),
        )

        assertEquals(CatalogDecision.AutoAccept(42), result)
    }

    @Test
    fun sameNameOverridesCrossStoreReleaseYearGap() {
        val result = policy.evaluate(
            source = source(title = "Example", developer = null, year = 2020),
            candidates = listOf(candidate(42, "Example", null, 2022)),
        )

        assertEquals(CatalogDecision.AutoAccept(42), result)
    }

    @Test
    fun emptyCandidateListIsUnmatched() {
        assertEquals(CatalogDecision.NoPlausibleCandidate, policy.evaluate(source(), emptyList()))
    }

    @Test
    fun sameNameDefaultsRemoveDuplicateAppIds() {
        val result = policy.evaluate(
            source = source(title = "Example", developer = null, year = null),
            candidates = listOf(
                candidate(84, "Example"),
                candidate(42, "Example"),
                candidate(84, "Example"),
            ),
        )

        assertEquals(CatalogDecision.AutoAccept(42), result)
    }

    @Test
    fun candidateRequiresPositiveSteamAppId() {
        assertThrows(IllegalArgumentException::class.java) {
            candidate(steamAppId = 0, title = "Example")
        }
    }

    private fun source(
        title: String = "Example",
        developer: String? = null,
        year: Int? = null,
        appType: CanonicalAppType = CanonicalAppType.GAME,
    ) = SourceCatalogEvidence(
        title = title,
        developer = developer,
        releaseYear = year,
        appType = appType,
    )

    private fun candidate(
        steamAppId: Int,
        title: String,
        developer: String? = null,
        year: Int? = null,
        appType: CanonicalAppType = CanonicalAppType.GAME,
        publisher: String? = null,
    ) = SteamCatalogCandidate(
        steamAppId = steamAppId,
        title = title,
        developer = developer,
        releaseYear = year,
        appType = appType,
        headerImageUrl = null,
        publisher = publisher,
    )
}
