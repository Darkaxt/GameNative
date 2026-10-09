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
import app.gamenative.library.canonical.artwork.ArtworkFingerprint
import app.gamenative.library.canonical.artwork.ArtworkFixtures
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class CanonicalGameFamilyProjectionTest {
    @get:Rule val timeout = Timeout.seconds(10)
    private val original = fingerprint(192, 288)

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
            deluxe.copies.single().key to deluxe.canonicalId), copyCanonicalIds(family))
        assertEquals(mapOf(base.canonicalId to 42, deluxe.canonicalId to 43), memberSteamAppIds(family))
        assertEquals(42, base.steamAppId)
        assertEquals(43, deluxe.steamAppId)
    }

    @Test
    fun familyAnchorAndCopyOrderingAreStableAcrossReorderAndRecreation() {
        val base = card(2, "Fixture Game")
        val deluxe = card(1, "Fixture Game Deluxe", GameSource.EPIC)
        val first = project(listOf(base, deluxe)).single()
        val recreated = project(listOf(deluxe.copy(), base.copy())).single()
        assertEquals(first, recreated)
        assertEquals(base.canonicalId, first.canonicalId)
        assertEquals(CanonicalCardKey.Grouped(base.canonicalId), first.key)
    }

    @Test
    fun resizedIndependentUsableArtworkCanCorroborateAnEditionFamily() {
        val cards = listOf(card(1, "Fixture Game"), card(2, "Fixture Game Deluxe", GameSource.EPIC))
        val resized = fingerprint(128, 192)
        assertTrue(original.corroborates(resized))
        assertEquals(1, project(cards, mapOf(cards[0].canonicalId to original, cards[1].canonicalId to resized)).size)
    }

    @Test
    fun missingDistinctOrInvalidArtworkLeavesIndependentCards() {
        val cards = listOf(card(1, "Fixture Game"), card(2, "Fixture Game Deluxe", GameSource.EPIC))
        val second = cards[1].canonicalId
        val cases = listOf(
            mapOf(cards[0].canonicalId to original),
            mapOf(cards[0].canonicalId to original, second to original.copy(bits = original.bits xor 0xFFFL)),
            mapOf(cards[0].canonicalId to original, second to original.copy(algorithmVersion = 99)),
        )
        cases.forEach { assertEquals(2, project(cards, it).size) }
    }

    @Test
    fun dlcAndUnknownTypesCannotJoinAGameFamilyDespiteSharedArtwork() {
        for (type in listOf(CanonicalAppType.DLC, CanonicalAppType.UNKNOWN)) {
            val cards = listOf(card(1, "Fixture Game"), card(2, "Fixture Game Deluxe", GameSource.EPIC).copy(appType = type))
            assertEquals(2, project(cards).size)
        }
    }

    @Test
    fun sequelAndActualEditionTitlePrefixesDoNotBecomeEditionAliases() {
        for (name in listOf("Fixture Game 2", "Deluxe Fixture Game", "Complete Fixture Game", "Redux Fixture Game")) {
            assertEquals(2, project(listOf(card(1, "Fixture Game"), card(2, name, GameSource.EPIC))).size)
        }
    }

    @Test
    fun knownDeveloperContradictionPreventsArtworkOnlyGrouping() {
        val cards = listOf(card(1, "Fixture Game"), card(2, "Fixture Game Deluxe", GameSource.EPIC))
        assertEquals(2, project(cards, developers = mapOf(
            cards[0].canonicalId to setOf("fixture studio"), cards[1].canonicalId to setOf("other studio"),
        )).size)
    }

    @Test
    fun stickyKeepSeparateCannotBeUndoneByFamilyPresentation() {
        val base = card(1, "Fixture Game")
        val second = card(2, "Fixture Game Deluxe", GameSource.EPIC)
        val separated = second.copy(copies = listOf(second.copies.single().copy(
            matchMethod = MatchMethod.MANUAL, confidence = MatchConfidence.REJECTED,
            decisionSource = MatchDecisionSource.USER,
        )))
        val result = project(listOf(base, separated))
        assertEquals(2, result.size)
        assertTrue(result.any { it.copies.single().confidence == MatchConfidence.REJECTED })
    }

    @Test
    fun similarityIsNotTransitivelyExpandedThroughAnIntermediateEdition() {
        val cards = listOf(card(1, "Fixture Game"), card(2, "Fixture Game Deluxe", GameSource.EPIC),
            card(3, "Fixture Game Deluxe Version"))
        val middle = original.copy(bits = original.bits xor 0x3FL)
        val last = original.copy(bits = original.bits xor 0xFFFL)
        assertTrue(original.corroborates(middle))
        assertTrue(middle.corroborates(last))
        assertFalse(original.corroborates(last))
        assertEquals(3, project(cards, mapOf(cards[0].canonicalId to original,
            cards[1].canonicalId to middle, cards[2].canonicalId to last)).size)
    }

    @Test
    fun validExplicitPreferenceSurvivesButConflictingMemberPreferencesDoNotPickAWinner() {
        val base = card(1, "Fixture Game")
        val deluxe = card(2, "Fixture Game Deluxe", GameSource.EPIC)
        val preferredBase = base.copy(preferredCopy = base.copies.single().key)
        assertEquals(preferredBase.preferredCopy, project(listOf(preferredBase, deluxe)).single().preferredCopy)
        val preferredDeluxe = deluxe.copy(preferredCopy = deluxe.copies.single().key)
        assertEquals(null, project(listOf(preferredBase, preferredDeluxe)).single().preferredCopy)
    }

    @Test
    fun independentCopyCardIsNeverFoldedIntoAnAutomaticFamily() {
        val base = card(1, "Fixture Game")
        val deluxe = card(2, "Fixture Game Deluxe", GameSource.EPIC)
        val independent = deluxe.copy(key = CanonicalCardKey.Independent(deluxe.copies.single().key))
        assertEquals(2, project(listOf(base, independent)).size)
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
        assertEquals(setOf(base.canonicalId, deluxe.canonicalId), copyCanonicalIds(family).values.toSet())
    }

    @Test
    fun publishedFamilyMembershipIsFrozenAgainstInputAndConsumerMutation() {
        val base = card(1, "Fixture Game")
        val mutableCopies = base.copies.toMutableList()
        val deluxe = card(2, "Fixture Game Deluxe", GameSource.EPIC)
        val family = project(listOf(base.copy(copies = mutableCopies), deluxe)).single()
        mutableCopies.clear()
        assertEquals(2, family.copies.size)
        val members = copyCanonicalIds(family) as MutableMap<OwnedCopyKey, CanonicalGameId>
        val failure = runCatching { members.clear() }.exceptionOrNull()
        assertTrue("Published family member identities must be immutable", failure is UnsupportedOperationException)
        assertEquals(2, copyCanonicalIds(family).size)
    }

    @Test
    fun malformedCopyToMemberBindingsCannotBePublishedAsAFamily() {
        val base = card(1, "Fixture Game", steamAppId = 42)
        val deluxe = card(2, "Fixture Game Deluxe", GameSource.EPIC, steamAppId = 43)
        val cases = listOf(
            base.copy(copyCanonicalIds = mapOf(base.copies.single().key to deluxe.canonicalId),
                memberSteamAppIds = mapOf(base.canonicalId to 42)),
            base.copy(memberSteamAppIds = mapOf(base.canonicalId to 43)),
            base.copy(memberSteamAppIds = mapOf(deluxe.canonicalId to 42)),
            base.copy(copyCanonicalIds = mapOf(deluxe.copies.single().key to base.canonicalId)),
        )
        cases.forEach { assertEquals(2, project(listOf(it, deluxe)).size) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun project(
        cards: List<CanonicalLibraryCard>,
        artworks: Map<CanonicalGameId, ArtworkFingerprint> = cards.associate { it.canonicalId to original },
        developers: Map<CanonicalGameId, Set<String>> = emptyMap(),
    ): List<CanonicalLibraryCard> {
        val type = runCatching { Class.forName("app.gamenative.library.canonical.CanonicalGameFamilyProjection") }.getOrNull()
        assertTrue("Missing deterministic game-family projection boundary", type != null)
        val method = type!!.methods.singleOrNull { it.name == "project" && it.parameterCount == 3 }
        assertTrue("Family projection must consume independent artwork and party evidence", method != null)
        return try {
            method!!.invoke(type.getField("INSTANCE").get(null), cards, artworks, developers) as List<CanonicalLibraryCard>
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun copyCanonicalIds(card: CanonicalLibraryCard): Map<OwnedCopyKey, CanonicalGameId> =
        requiredProperty(card, "getCopyCanonicalIds") as Map<OwnedCopyKey, CanonicalGameId>

    @Suppress("UNCHECKED_CAST")
    private fun memberSteamAppIds(card: CanonicalLibraryCard): Map<CanonicalGameId, Int?> =
        requiredProperty(card, "getMemberSteamAppIds") as Map<CanonicalGameId, Int?>

    private fun requiredProperty(card: CanonicalLibraryCard, getter: String): Any {
        val method = card.javaClass.methods.singleOrNull { it.name == getter && it.parameterCount == 0 }
        assertTrue("Family presentation must retain exact member identity: $getter", method != null)
        return requireNotNull(method!!.invoke(card))
    }

    private fun fingerprint(width: Int, height: Int) =
        requireNotNull(ArtworkFingerprint.fromArgb(width, height, ArtworkFixtures.cover(width, height)))

    private fun card(
        index: Long,
        name: String,
        source: GameSource = GameSource.GOG,
        steamAppId: Int? = null,
        installed: Boolean = false,
    ): CanonicalLibraryCard {
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
            key = CanonicalCardKey.Grouped(canonicalId), canonicalId = canonicalId, displayName = name,
            appType = CanonicalAppType.GAME, iconUrl = "", capsuleImageUrl = "", headerImageUrl = "", heroImageUrl = "",
            gridHeroImageScale = 1f, aliases = setOf(name), ownedSources = setOf(source), copies = listOf(copy),
            preferredCopy = null, steamCollectionAppIds = emptySet(), isShared = false, steamAppId = steamAppId,
        )
    }
}
