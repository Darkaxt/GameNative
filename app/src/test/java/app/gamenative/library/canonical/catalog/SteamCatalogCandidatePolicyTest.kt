package app.gamenative.library.canonical.catalog

import app.gamenative.data.canonical.CanonicalAppType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SteamCatalogCandidatePolicyTest {
    private val policy = SteamCatalogCandidatePolicy()

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
    fun uniqueExactCandidateWithAdjacentReleaseYearRequiresReviewBelowScoreThreshold() {
        val result = policy.evaluate(
            source = source(title = "Example", developer = null, year = 2020),
            candidates = listOf(candidate(42, "Example", null, 2021)),
        )

        assertEquals(CatalogDecision.ReviewRequired(listOf(42)), result)
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
    fun tiedClosestPriorCandidatesRequireReview() {
        val result = policy.evaluate(
            source = source(title = "Example", developer = "Studio", year = 2015),
            candidates = listOf(
                candidate(42, "Example", "Studio", 2012),
                candidate(84, "Example", "Studio", 2012),
            ),
        )

        assertEquals(CatalogDecision.ReviewRequired(listOf(42, 84)), result)
    }

    @Test
    fun ambiguityWithoutCandidateAtOrBeforeSourceYearRequiresReview() {
        val result = policy.evaluate(
            source = source(title = "Example", developer = "Studio", year = 2010),
            candidates = listOf(
                candidate(42, "Example", "Studio", 2012),
                candidate(84, "Example", "Studio", 2020),
            ),
        )

        assertEquals(CatalogDecision.ReviewRequired(listOf(42, 84)), result)
    }

    @Test
    fun ambiguityWithoutSourceYearRequiresReview() {
        val result = policy.evaluate(
            source = source(title = "Example", developer = "Studio", year = null),
            candidates = listOf(
                candidate(42, "Example", "Studio", 2012),
                candidate(84, "Example", "Studio", 2020),
            ),
        )

        assertEquals(CatalogDecision.ReviewRequired(listOf(42, 84)), result)
    }

    @Test
    fun titleOnlyCandidateRequiresReview() {
        val result = policy.evaluate(
            source = source(title = "Example", developer = null, year = null),
            candidates = listOf(candidate(42, "Example", null, null)),
        )

        assertEquals(CatalogDecision.ReviewRequired(listOf(42)), result)
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
    fun marginBelowEightHundredthsRequiresScoreRankedReview() {
        val result = policy.evaluate(
            source = source(title = "Example", developer = null, year = 2020),
            candidates = listOf(
                candidate(10, "Example", null, 2021),
                candidate(20, "Example", null, 2020),
            ),
        )

        assertEquals(CatalogDecision.ReviewRequired(listOf(20, 10)), result)
    }

    @Test
    fun lowPlausibilityGameHitsAreUnmatched() {
        val result = policy.evaluate(
            source = source(title = "Alan Wake 2", developer = "Remedy", year = 2023),
            candidates = listOf(
                candidate(42, "Unrelated Game", "Other Studio", 2023),
            ),
        )

        assertEquals(CatalogDecision.Unmatched, result)
    }

    @Test
    fun reviewCandidatesAreRankedByScoreBeforeAppId() {
        val result = policy.evaluate(
            source = source(title = "Example", developer = null, year = null),
            candidates = listOf(
                candidate(42, "Unrelated"),
                candidate(84, "Example"),
            ),
        )

        assertEquals(CatalogDecision.ReviewRequired(listOf(84, 42)), result)
    }

    @Test
    fun equallyEligibleCandidatesRequireReview() {
        val result = policy.evaluate(
            source = source(title = "Example", developer = "Studio", year = 2020),
            candidates = listOf(
                candidate(42, "Example", "Studio", 2020),
                candidate(84, "Example", "Studio", 2020),
            ),
        )

        assertEquals(CatalogDecision.ReviewRequired(listOf(42, 84)), result)
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

        assertEquals(CatalogDecision.Unmatched, result)
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
    fun developerConflictPreventsAutoAccept() {
        val result = policy.evaluate(
            source = source(title = "Example", developer = "Studio A", year = null),
            candidates = listOf(candidate(42, "Example", "Studio B", null)),
        )

        assertEquals(CatalogDecision.ReviewRequired(listOf(42)), result)
    }

    @Test
    fun releaseYearGapGreaterThanOnePreventsAutoAccept() {
        val result = policy.evaluate(
            source = source(title = "Example", developer = null, year = 2020),
            candidates = listOf(candidate(42, "Example", null, 2022)),
        )

        assertEquals(CatalogDecision.ReviewRequired(listOf(42)), result)
    }

    @Test
    fun emptyCandidateListIsUnmatched() {
        assertEquals(CatalogDecision.Unmatched, policy.evaluate(source(), emptyList()))
    }

    @Test
    fun reviewCandidatesRemoveDuplicateAppIdsAndUseStableOrdering() {
        val result = policy.evaluate(
            source = source(title = "Example", developer = null, year = null),
            candidates = listOf(
                candidate(84, "Example"),
                candidate(42, "Example"),
                candidate(84, "Example"),
            ),
        )

        assertEquals(CatalogDecision.ReviewRequired(listOf(42, 84)), result)
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
