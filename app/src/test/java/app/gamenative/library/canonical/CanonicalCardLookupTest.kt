package app.gamenative.library.canonical

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.OwnedCopyKey
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalCardLookupTest {
    private val base = card(1)
    private val deluxe = card(2)

    @Test
    fun exactCurrentCardKeyResolvesWithoutReassigningItsCanonical() {
        assertSame(base, resolve(listOf(base, deluxe), base.key))
    }

    @Test
    fun anOldNonAnchorDetailKeyResolvesItsCurrentPresentationFamily() {
        val family = family()
        assertSame(family, resolve(listOf(family), deluxe.key))
        assertEquals(deluxe.canonicalId, family.copyCanonicalIds[deluxe.copies.single().key])
    }

    @Test
    fun dissolvedFamilyReturnsTheRequestedRawEditionRatherThanItsFormerSibling() {
        assertSame(deluxe, resolve(listOf(base, deluxe), deluxe.key))
        assertNull(resolve(listOf(base), deluxe.key))
    }

    @Test
    fun ambiguousFamilyMembershipNeverChoosesTheFirstDisplayAnchor() {
        val family = family()
        val other = family.copy(key = CanonicalCardKey.Grouped(id(3)), canonicalId = id(3))
        assertNull(resolve(listOf(family, other), deluxe.key))
    }

    @Test
    fun malformedMemberCatalogOrCopyBindingsCannotAuthorizeAnAliasRoute() {
        val family = family()
        for (malformed in listOf(family.copy(copyCanonicalIds = emptyMap()),
            family.copy(copyCanonicalIds = mapOf(base.copies.single().key to base.canonicalId)),
            family.copy(memberSteamAppIds = mapOf(base.canonicalId to 42)))) {
            assertNull(resolve(listOf(malformed), deluxe.key))
        }
    }

    @Test
    fun anIndependentCopyKeyCannotBePromotedToAnUnrelatedFamilyDetail() {
        assertNull(resolve(listOf(family()), CanonicalCardKey.Independent(deluxe.copies.single().key)))
    }

    private fun resolve(cards: List<CanonicalLibraryCard>, key: CanonicalCardKey): CanonicalLibraryCard? {
        val type = runCatching { Class.forName("app.gamenative.library.canonical.CanonicalCardLookup") }.getOrNull()
        assertTrue("Missing exact-member family detail lookup boundary", type != null)
        val method = type!!.methods.single { it.name == "resolve" && it.parameterCount == 2 }
        return method.invoke(type.getField("INSTANCE").get(null), cards, key) as CanonicalLibraryCard?
    }

    private fun family() = base.copy(copies = base.copies + deluxe.copies,
        copyCanonicalIds = mapOf(base.copies.single().key to base.canonicalId, deluxe.copies.single().key to deluxe.canonicalId),
        memberSteamAppIds = mapOf(base.canonicalId to 42, deluxe.canonicalId to 43))
    private fun id(index: Int) = CanonicalGameId.parse(UUID(0, index.toLong()).toString())
    private fun card(index: Int): CanonicalLibraryCard {
        val key = OwnedCopyKey(AccountScope("2".repeat(64)), GameSource.GOG, "$index")
        return CanonicalLibraryCard(key = CanonicalCardKey.Grouped(id(index)), canonicalId = id(index), displayName = "Fixture Game",
            appType = CanonicalAppType.GAME, iconUrl = "", capsuleImageUrl = "", headerImageUrl = "", heroImageUrl = "",
            gridHeroImageScale = 1f, aliases = emptySet(), ownedSources = setOf(GameSource.GOG), preferredCopy = null,
            steamCollectionAppIds = emptySet(), isShared = false, steamAppId = 41 + index,
            copies = listOf(OwnedCopySummary(key, GameSource.GOG, "Fixture Game $index", null, null, null, false, false,
                false, false, false, null, null, setOf(OwnedCopyOperation.INSTALL), null, true,
                MatchMethod.STEAM_CATALOG, MatchConfidence.HIGH, MatchDecisionSource.AUTOMATIC, 41 + index, CURRENT_RESOLVER_VERSION, 100)))
    }
}
