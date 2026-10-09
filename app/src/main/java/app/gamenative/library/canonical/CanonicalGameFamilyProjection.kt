package app.gamenative.library.canonical

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.canonical.artwork.ArtworkFingerprint
import app.gamenative.library.canonical.catalog.SteamCatalogNormalization
import java.util.Collections

/** Presentation only: original artwork is supplied independently, and no catalog or copy identity is reassigned. */
object CanonicalGameFamilyProjection {
    internal const val MAX_FAMILY_MEMBERS = 10
    private val editionSuffix = Regex(
        "\\s+(?:deluxe|definitive|enhanced|ultimate|remastered|redux|complete|anniversary|" +
            "director s cut|final cut|game of the year)(?: edition| version)?$",
    )

    fun project(
        cards: List<CanonicalLibraryCard>,
        artworks: Map<CanonicalGameId, ArtworkFingerprint>,
        developerKeys: Map<CanonicalGameId, Set<String>>,
    ): List<CanonicalLibraryCard> {
        val eligible = cards.filter(::eligible)
            .groupBy { familyTitleKey(it.displayName) }
        val families = linkedMapOf<CanonicalGameId, CanonicalLibraryCard>()
        val grouped = hashSetOf<CanonicalGameId>()
        for ((title, unsorted) in eligible.toSortedMap()) {
            if (title.isBlank() || unsorted.size !in 2..MAX_FAMILY_MEMBERS) continue
            val members = unsorted.sortedWith(
                compareBy<CanonicalLibraryCard> { if (SteamCatalogNormalization.titleKey(it.displayName) == title) 0 else 1 }
                    .thenBy { if (it.steamAppId != null) 0 else 1 }
                    .thenBy { if (GameSource.STEAM in it.ownedSources) 0 else 1 }
                    .thenBy { it.canonicalId.value },
            )
            if (members.map { it.canonicalId }.distinct().size != members.size) continue
            val copies = members.flatMap { it.copies }
            if (copies.map { it.key }.distinct().size != copies.size) continue
            if (!pairwiseCorroborated(members, artworks, developerKeys)) continue
            val family = family(members)
            families[family.canonicalId] = family
            grouped += members.map { it.canonicalId }
        }
        return freezeList((cards.filterNot { it.canonicalId in grouped } + families.values)
            .sortedBy { it.key.stableComposeKey() })
    }

    internal fun familyTitleKey(value: String): String {
        var key = SteamCatalogNormalization.titleKey(value)
        while (true) {
            val stripped = editionSuffix.replace(key, "").trim()
            if (stripped == key) return key
            key = stripped
        }
    }

    internal fun eligible(card: CanonicalLibraryCard): Boolean =
        card.key is CanonicalCardKey.Grouped && card.key.canonicalId == card.canonicalId &&
            card.appType == CanonicalAppType.GAME && card.copies.isNotEmpty() && !card.familyGroupingSuppressed &&
            card.memberSteamAppIds.size <= 1 && validMemberBindings(card) &&
            card.copies.none { copy ->
                copy.unavailableReason != null ||
                    (copy.decisionSource == MatchDecisionSource.USER && copy.confidence == MatchConfidence.REJECTED)
            }

    private fun validMemberBindings(card: CanonicalLibraryCard): Boolean {
        if (card.steamAppId != null && card.steamAppId <= 0) return false
        if (card.memberSteamAppIds.isNotEmpty() && card.memberSteamAppIds != mapOf(card.canonicalId to card.steamAppId)) return false
        val keys = card.copies.mapTo(hashSetOf(), OwnedCopySummary::key)
        return card.copyCanonicalIds.isEmpty() ||
            (card.copyCanonicalIds.keys == keys && card.copyCanonicalIds.values.all { it == card.canonicalId })
    }

    private fun pairwiseCorroborated(
        members: List<CanonicalLibraryCard>,
        artworks: Map<CanonicalGameId, ArtworkFingerprint>,
        developerKeys: Map<CanonicalGameId, Set<String>>,
    ): Boolean {
        val parties = members.associate { card ->
            card.canonicalId to developerKeys[card.canonicalId].orEmpty()
                .map(SteamCatalogNormalization::developerKey).filter(String::isNotBlank).toSet()
        }
        for (index in members.indices) {
            val left = members[index].canonicalId
            val original = artworks[left] ?: return false
            if (!original.corroborates(original)) return false
            for (other in members.drop(index + 1)) {
                val right = other.canonicalId
                val candidate = artworks[right] ?: return false
                if (!original.corroborates(candidate)) return false
                val leftParties = parties.getValue(left)
                val rightParties = parties.getValue(right)
                if (leftParties.isNotEmpty() && rightParties.isNotEmpty() && leftParties.intersect(rightParties).isEmpty()) {
                    return false
                }
            }
        }
        return true
    }

    private fun family(members: List<CanonicalLibraryCard>): CanonicalLibraryCard {
        val anchor = members.first()
        val copies = members.flatMap { it.copies }.sortedWith(
            compareBy<OwnedCopySummary> { sourceRank(it.source) }
                .thenBy { it.key.accountScope.value }.thenBy { it.key.stableSourceId },
        ).map { it.copy(capabilities = freezeSet(it.capabilities)) }
        val keys = copies.mapTo(hashSetOf()) { it.key }
        val preferences = members.mapNotNull { it.preferredCopy }.filter(keys::contains).distinct()
        val copyCanonicalIds = linkedMapOf<OwnedCopyKey, CanonicalGameId>()
        val memberSteamAppIds = linkedMapOf<CanonicalGameId, Int?>()
        val genreLabels = linkedMapOf<String, String>()
        for (member in members) {
            member.copies.forEach { copy ->
                copyCanonicalIds[copy.key] = member.copyCanonicalIds[copy.key] ?: member.canonicalId
            }
            memberSteamAppIds.putAll(member.memberSteamAppIds.ifEmpty { mapOf(member.canonicalId to member.steamAppId) })
            member.genreLabels.toSortedMap().forEach { (key, value) -> genreLabels.putIfAbsent(key, value) }
        }
        return anchor.copy(
            copies = freezeList(copies),
            aliases = freezeSet(members.flatMap { it.aliases + it.displayName + it.copies.map(OwnedCopySummary::nativeTitle) }),
            ownedSources = freezeSet(copies.map(OwnedCopySummary::source)),
            preferredCopy = preferences.singleOrNull(),
            steamCollectionAppIds = freezeSet(members.flatMap { it.steamCollectionAppIds }.sorted()),
            isShared = copies.any(OwnedCopySummary::isShared),
            genreKeys = freezeSet(members.flatMap { it.genreKeys }.sorted()),
            genreLabels = freezeMap(genreLabels),
            tagIds = freezeSet(members.flatMap { it.tagIds }.sorted()),
            copyCanonicalIds = freezeMap(copyCanonicalIds),
            memberSteamAppIds = freezeMap(memberSteamAppIds),
            memberPreferences = freezeMap(members.flatMap { it.memberPreferences.entries }.associate { it.key to it.value }),
        )
    }

    private fun sourceRank(source: GameSource): Int = when (source) {
        GameSource.STEAM -> 0
        GameSource.GOG -> 1
        GameSource.EPIC -> 2
        GameSource.AMAZON -> 3
        GameSource.CUSTOM_GAME -> 4
    }

    private fun <T> freezeList(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
    private fun <T> freezeSet(values: Collection<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))
    private fun <K, V> freezeMap(values: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(values))
}
