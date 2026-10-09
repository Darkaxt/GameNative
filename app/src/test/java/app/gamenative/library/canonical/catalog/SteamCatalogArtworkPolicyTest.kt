package app.gamenative.library.canonical.catalog

import app.gamenative.data.canonical.CanonicalAppType
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class SteamCatalogArtworkPolicyTest {
    @get:Rule val timeout = Timeout.seconds(5)
    private val policy = SteamCatalogCandidatePolicy()

    @Test
    fun uniqueExactGameCanUseIndependentArtworkWhenOtherCorroboratorsAreMissing() {
        assertEquals(CatalogDecision.AutoAccept(42), evaluate(source(), listOf(candidate()), setOf(42)))
    }

    @Test
    fun unavailableArtworkPreservesThePreviousReviewDecision() {
        assertReview(evaluate(source(), listOf(candidate()), emptySet()))
    }

    @Test
    fun anotherAppIdsArtworkCannotCorroborateTheCandidate() {
        assertReview(evaluate(source(), listOf(candidate()), setOf(43)))
    }

    @Test
    fun knownPartyContradictionIsNotOverriddenByMatchingArtwork() {
        assertReview(evaluate(source(developer = "Source Studio"), listOf(candidate(developer = "Other Studio")), setOf(42)))
    }

    @Test
    fun sharedEditionArtworkDoesNotAssignTheBaseCatalogToADeluxeCopy() {
        assertReview(evaluate(source(title = "Fixture Game Deluxe Edition"), listOf(candidate()), setOf(42)))
    }

    @Test
    fun matchingArtworkNeverPromotesDlcDemoOrSoundtrackToAGame() {
        listOf(CanonicalAppType.DLC, CanonicalAppType.DEMO, CanonicalAppType.SOUNDTRACK).forEach { type ->
            assertEquals(CatalogDecision.NoPlausibleCandidate,
                evaluate(source(), listOf(candidate(type = type)), setOf(42)))
        }
    }

    @Test
    fun matchingArtworkCannotTurnASequelIntoAnExactTitle() {
        assertEquals(CatalogDecision.NoPlausibleCandidate,
            evaluate(source(), listOf(candidate(title = "Fixture Game 2")), setOf(42)))
    }

    @Test
    fun unknownSourceTypeRemainsReviewOnly() {
        assertReview(evaluate(source(type = CanonicalAppType.UNKNOWN), listOf(candidate()), setOf(42)))
    }

    @Test
    fun twoExactGamesWithSharedArtworkAndMissingSourceYearRemainAmbiguous() {
        assertReview(evaluate(source(), listOf(candidate(), candidate(id = 43)), setOf(42, 43)))
    }

    @Test
    fun artworkCorroboratedAmbiguityUsesTheUniqueClosestEligiblePriorYear() {
        assertEquals(CatalogDecision.AutoAccept(43), evaluate(
            source(year = 2024), listOf(candidate(year = 2020), candidate(id = 43, year = 2023)), setOf(42, 43),
        ))
    }

    @Test
    fun tiedPriorYearsRemainReviewOnlyEvenWhenBothImagesMatch() {
        assertReview(evaluate(source(year = 2024),
            listOf(candidate(year = 2023), candidate(id = 43, year = 2023)), setOf(42, 43)))
    }

    @Test
    fun noEligiblePriorYearCannotBeReplacedByAnArtworkScoreMargin() {
        assertReview(evaluate(source(year = 2024),
            listOf(candidate(year = 2025), candidate(id = 43, year = 2026)), setOf(42, 43)))
    }

    @Test
    fun existingDeveloperCorroborationStillWorksWithoutAnyArtwork() {
        assertEquals(CatalogDecision.AutoAccept(42), evaluate(
            source(developer = "Fixture Studio"), listOf(candidate(developer = "Fixture Studio")), emptySet(),
        ))
    }

    @Test
    fun artworkAloneDoesNotEraseALargeKnownYearDifference() {
        assertReview(evaluate(source(year = 2024), listOf(candidate(year = 2010)), setOf(42)))
    }

    private fun source(
        title: String = "Fixture Game", developer: String? = null, year: Int? = null,
        type: CanonicalAppType = CanonicalAppType.GAME,
    ) = SourceCatalogEvidence(title, developer, year, type)

    private fun candidate(
        id: Int = 42, title: String = "Fixture Game", developer: String? = null, year: Int? = null,
        type: CanonicalAppType = CanonicalAppType.GAME,
    ) = SteamCatalogCandidate(id, title, developer, year, type, headerImageUrl = null)

    private fun assertReview(decision: CatalogDecision) {
        org.junit.Assert.assertTrue("Identity must remain reviewable, not auto-assigned: $decision", decision is CatalogDecision.ReviewRequired)
    }

    private fun evaluate(
        source: SourceCatalogEvidence, candidates: List<SteamCatalogCandidate>, artworkAppIds: Set<Int>,
    ): CatalogDecision {
        val method = policy.javaClass.methods.singleOrNull {
            it.name == "evaluate" && it.parameterCount == 3 && it.parameterTypes.last() == Set::class.java
        }
        assertNotNull("Independent artwork policy integration is required", method)
        return try {
            requireNotNull(method).invoke(policy, source, candidates, artworkAppIds) as CatalogDecision
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }
}
