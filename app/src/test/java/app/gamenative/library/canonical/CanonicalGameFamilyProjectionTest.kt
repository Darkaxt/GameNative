package app.gamenative.library.canonical

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.EpicStableSourceId
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.OwnedCopyKey
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Projection consumes already-verified visual relationships; extraction/geometry have separate owning tests. */
class CanonicalGameFamilyProjectionTest {
    @get:Rule val timeout = Timeout.seconds(10)

    @Test
    fun sharedOriginalArtworkGroupsEditionsWithoutReassigningCatalogOrCopyIdentity() {
        val base = card(1, "Fixture Game", steamAppId = 42)
        val deluxe = card(2, "Fixture Game Deluxe Edition", GameSource.EPIC, steamAppId = 43)
        val input = listOf(base, deluxe)
        val family = project(input).single()
        assertEquals("Fixture Game", family.displayName)
        assertEquals(input.flatMap { it.copies }.toSet(), family.copies.toSet())
        assertEquals(setOf(GameSource.GOG, GameSource.EPIC), family.ownedSources)
        assertFalse(family.copies.any { it.source == GameSource.STEAM })
        assertEquals(mapOf(base.copies.single().key to base.canonicalId,
            deluxe.copies.single().key to deluxe.canonicalId), family.copyCanonicalIds)
        assertEquals(mapOf(base.canonicalId to 42, deluxe.canonicalId to 43), family.memberSteamAppIds)
        assertEquals(42, base.steamAppId)
        assertEquals(43, deluxe.steamAppId)
    }

    @Test
    fun familyAnchorAndCopyOrderingAreStableAcrossReorderAndRecreation() {
        val base = card(2, "Fixture Game")
        val deluxe = card(1, "Fixture Game Deluxe", GameSource.EPIC)
        val first = project(listOf(base, deluxe)).single()
        assertEquals(first, project(listOf(deluxe.copy(), base.copy())).single())
        assertEquals(base.canonicalId, first.canonicalId)
        assertEquals(CanonicalCardKey.Grouped(base.canonicalId), first.key)
    }

    @Test
    fun missingVerificationLeavesIndependentCardsEvenWithIdenticalMetadata() {
        val cards = listOf(card(1, "Same name"), card(2, "Same name", GameSource.EPIC))
        assertEquals(2, CanonicalGameFamilyProjection.project(cards, emptySet()).size)
    }

    @Test
    fun unrelatedTitlesAndEditionPrefixesDoNotVetoVerifiedBackgrounds() {
        for (name in listOf("Fixture Game 2", "Deluxe Fixture Game", "Complete Fixture Game", "Redux Fixture Game", "Different product")) {
            assertEquals(1, project(listOf(card(1, "Fixture Game"), card(2, name, GameSource.EPIC))).size)
        }
    }

    @Test
    fun stickyKeepSeparateCannotBeUndoneByFamilyPresentation() {
        val base = card(1, "Fixture Game")
        val second = card(2, "Fixture Game Deluxe", GameSource.EPIC)
        val separated = second.copy(copies = listOf(second.copies.single().copy(
            matchMethod = MatchMethod.MANUAL, confidence = MatchConfidence.REJECTED, decisionSource = MatchDecisionSource.USER,
        )))
        val result = project(listOf(base, separated))
        assertEquals(2, result.size)
        assertTrue(result.any { it.copies.single().confidence == MatchConfidence.REJECTED })
        assertEquals(2, project(listOf(base, second.copy(familyGroupingSuppressed = true))).size)
    }

    @Test
    fun stronglyVerifiedPartialOverlapsResolveThroughTheExistingFamilyGraph() {
        val cards = listOf(card(1, "Left crop"), card(2, "Whole artwork"), card(3, "Right crop"))
        val verified = setOf(cards[0].canonicalId to cards[1].canonicalId, cards[1].canonicalId to cards[2].canonicalId)
        val family = CanonicalGameFamilyProjection.project(cards, verified).single()
        assertEquals(3, family.copies.size)
        assertEquals(cards.map { it.canonicalId }.toSet(), family.memberSteamAppIds.keys)
        assertEquals(family, CanonicalGameFamilyProjection.project(cards.reversed(), verified.reversed().toSet()).single())
    }

    @Test
    fun validExplicitPreferenceSurvivesButConflictingMemberPreferencesDoNotPickAWinner() {
        val base = card(1, "Fixture Game")
        val deluxe = card(2, "Fixture Game Deluxe", GameSource.EPIC)
        val preferredBase = base.copy(preferredCopy = base.copies.single().key)
        assertEquals(preferredBase.preferredCopy, project(listOf(preferredBase, deluxe)).single().preferredCopy)
        assertEquals(null, project(listOf(preferredBase, deluxe.copy(preferredCopy = deluxe.copies.single().key))).single().preferredCopy)
    }

    @Test
    fun independentCopyCardIsNeverFoldedIntoAnAutomaticFamily() {
        val base = card(1, "Fixture Game")
        val deluxe = card(2, "Fixture Game Deluxe", GameSource.EPIC)
        assertEquals(2, project(listOf(base, deluxe.copy(key = CanonicalCardKey.Independent(deluxe.copies.single().key)))).size)
    }

    @Test
    fun twoInstalledEditionsFromTheSameStoreRetainIndependentRuntimeStateAndLabels() {
        val base = card(1, "Fixture Game", installed = true)
        val deluxe = card(2, "Fixture Game Deluxe", installed = true)
        val family = project(listOf(base, deluxe)).single()
        assertEquals(setOf("Fixture Game", "Fixture Game Deluxe"), family.copies.map { it.nativeTitle }.toSet())
        assertEquals(setOf("fixture-install-1", "fixture-install-2"), family.copies.map { it.installPath }.toSet())
        assertEquals(setOf(101L, 102L), family.copies.map { it.installedSizeBytes }.toSet())
        assertTrue(family.copies.all { it.isInstalled && OwnedCopyOperation.PLAY in it.capabilities })
        assertEquals(setOf(base.canonicalId, deluxe.canonicalId), family.copyCanonicalIds.values.toSet())
    }

    @Test
    fun publishedFamilyMembershipIsFrozenAgainstInputAndConsumerMutation() {
        val base = card(1, "Fixture Game")
        val mutableCopies = base.copies.toMutableList()
        val deluxe = card(2, "Fixture Game Deluxe", GameSource.EPIC)
        val family = project(listOf(base.copy(copies = mutableCopies), deluxe)).single()
        mutableCopies.clear()
        assertEquals(2, family.copies.size)
        val members = family.copyCanonicalIds as MutableMap<OwnedCopyKey, CanonicalGameId>
        assertTrue(runCatching { members.clear() }.exceptionOrNull() is UnsupportedOperationException)
        assertEquals(2, family.copyCanonicalIds.size)
    }

    @Test
    fun malformedCopyToMemberBindingsCannotBePublishedAsAFamily() {
        val base = card(1, "Fixture Game", steamAppId = 42)
        val deluxe = card(2, "Fixture Game Deluxe", GameSource.EPIC, steamAppId = 43)
        val cases = listOf(
            base.copy(copyCanonicalIds = mapOf(base.copies.single().key to deluxe.canonicalId), memberSteamAppIds = mapOf(base.canonicalId to 42)),
            base.copy(memberSteamAppIds = mapOf(base.canonicalId to 43)),
            base.copy(memberSteamAppIds = mapOf(deluxe.canonicalId to 42)),
            base.copy(copyCanonicalIds = mapOf(deluxe.copies.single().key to base.canonicalId)),
        )
        cases.forEach { assertEquals(2, project(listOf(it, deluxe)).size) }
    }

    @Test
    fun strongArtworkGroupsUnrelatedTitlesAndConflictingCatalogIdentities() {
        val left = card(1, "Harbour adventure", steamAppId = 42)
        val right = card(2, "Collector bonus pack", GameSource.EPIC, steamAppId = 999)
        val result = project(listOf(left, right))
        assertEquals(1, result.size)
        assertEquals(setOf(left.canonicalId, right.canonicalId), result.single().memberSteamAppIds.keys)
        assertEquals(setOf(42, 999), result.single().memberSteamAppIds.values.toSet())
        assertEquals(listOf(left, right).flatMap { it.copies }.toSet(), result.single().copies.toSet())
    }

    @Test
    fun strongArtworkGroupsEveryProductTypeWithoutGrantingAnExecutionIdentity() {
        for (type in CanonicalAppType.entries) {
            val left = card(1, "Fixture Game", installed = true)
            val right = card(2, "Fixture Game Deluxe", GameSource.EPIC).copy(appType = type)
            val result = project(listOf(left, right))
            assertEquals("Product type $type must not veto verified artwork", 1, result.size)
            assertEquals(setOf(left.copies.single().key, right.copies.single().key), result.single().copies.map { it.key }.toSet())
            assertEquals(left.copies.single(), result.single().copies.single { it.key == left.copies.single().key })
        }
    }

    @Test
    fun visualFamiliesAreNotLimitedToMetadataBucketSize() {
        val cards = (1L..12L).map { card(it, "Distinct product $it") }
        val result = project(cards)
        assertEquals(1, result.size)
        assertEquals(12, result.single().copies.size)
        assertEquals(cards.map { it.canonicalId }.toSet(), result.single().memberSteamAppIds.keys)
    }

    private fun project(cards: List<CanonicalLibraryCard>): List<CanonicalLibraryCard> {
        val verified = cards.indices.flatMap { left -> (left + 1 until cards.size).map { right ->
            cards[left].canonicalId to cards[right].canonicalId
        } }.toSet()
        return CanonicalGameFamilyProjection.project(cards, verified)
    }

    private fun card(index: Long, name: String, source: GameSource = GameSource.GOG,
        steamAppId: Int? = null, installed: Boolean = false): CanonicalLibraryCard {
        val canonicalId = CanonicalGameId.parse(UUID(0, index).toString())
        val stableId = if (source == GameSource.EPIC) EpicStableSourceId.encode("fixture-namespace", "fixture-$index") else "$index"
        val key = OwnedCopyKey(AccountScope("2".repeat(64)), source, stableId)
        val copy = OwnedCopySummary(
            key = key, source = source, nativeTitle = name, installPath = if (installed) "fixture-install-$index" else null,
            installedSizeBytes = if (installed) 100L + index else null, branchOrVersion = "fixture-$index",
            isInstalled = installed, isDownloading = false, hasPartialDownload = false, updateAvailable = false,
            isShared = false, lastPlayedEpochMs = if (installed) index else null, playtimeMinutes = null,
            capabilities = if (installed) setOf(OwnedCopyOperation.PLAY) else setOf(OwnedCopyOperation.INSTALL),
            unavailableReason = null, canSeparateMatch = true, matchMethod = MatchMethod.STEAM_CATALOG,
            confidence = MatchConfidence.HIGH, decisionSource = MatchDecisionSource.AUTOMATIC,
            decisionCandidateSteamAppId = steamAppId, decisionResolverVersion = CURRENT_RESOLVER_VERSION, decisionRevision = 100,
        )
        return CanonicalLibraryCard(
            key = CanonicalCardKey.Grouped(canonicalId), canonicalId = canonicalId, displayName = name, appType = CanonicalAppType.GAME,
            iconUrl = "", capsuleImageUrl = "", headerImageUrl = "", heroImageUrl = "", gridHeroImageScale = 1f,
            aliases = setOf(name), ownedSources = setOf(source), copies = listOf(copy), preferredCopy = null,
            steamCollectionAppIds = emptySet(), isShared = false, steamAppId = steamAppId,
        )
    }
}
