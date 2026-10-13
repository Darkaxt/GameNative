package app.gamenative.library.canonical

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.canonical.catalog.SteamCatalogNormalization
import java.util.Collections

/** Presentation only: original artwork is supplied independently, and no catalog or copy identity is reassigned. */
object CanonicalGameFamilyProjection {
    private val editionSuffix = Regex(
        "\\s+(?:deluxe|definitive|enhanced|ultimate|remastered|redux|complete|anniversary|" +
            "director s cut|final cut|game of the year)(?: edition| version)?$",
    )

    fun project(
        cards: List<CanonicalLibraryCard>,
        verifiedArtwork: Set<Pair<CanonicalGameId, CanonicalGameId>>,
    ): List<CanonicalLibraryCard> {
        if (cards.map { it.canonicalId }.distinct().size != cards.size ||
            cards.flatMap { it.copies }.map { it.key }.let { it.distinct().size != it.size }
        ) return cards
        val admitted = cards.filter(::eligible).associateBy { it.canonicalId }
        val neighbours = hashMapOf<CanonicalGameId, MutableSet<CanonicalGameId>>()
        for ((left, right) in verifiedArtwork) {
            if (left == right || left !in admitted || right !in admitted) continue
            neighbours.getOrPut(left, ::linkedSetOf).add(right)
            neighbours.getOrPut(right, ::linkedSetOf).add(left)
        }
        val families = arrayListOf<CanonicalLibraryCard>()
        val grouped = hashSetOf<CanonicalGameId>()
        for (root in neighbours.keys.sortedBy { it.value }) {
            if (root in grouped) continue
            val component = linkedSetOf<CanonicalGameId>()
            val pending = ArrayDeque<CanonicalGameId>().apply { add(root) }
            while (pending.isNotEmpty()) {
                val next = pending.removeFirst()
                if (!component.add(next)) continue
                neighbours[next].orEmpty().sortedBy { it.value }.forEach(pending::addLast)
            }
            if (component.size < 2) continue
            val members = component.map(admitted::getValue).sortedWith(
                compareBy<CanonicalLibraryCard> {
                    if (SteamCatalogNormalization.titleKey(it.displayName) == familyTitleKey(it.displayName)) 0 else 1
                }.thenBy { if (it.steamAppId != null) 0 else 1 }
                    .thenBy { if (GameSource.STEAM in it.ownedSources) 0 else 1 }
                    .thenBy { it.canonicalId.value },
            )
            families += family(members)
            grouped += component
        }
        return freezeList((cards.filterNot { it.canonicalId in grouped } + families)
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
            card.copies.isNotEmpty() && !card.familyGroupingSuppressed &&
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

    private fun family(members: List<CanonicalLibraryCard>): CanonicalLibraryCard {
        val anchor = members.first()
        val copies = members.flatMap { it.copies }.sortedWith(
            compareBy<OwnedCopySummary> { sourceRank(it.source) }
                .thenBy { it.key.accountScope.value }.thenBy { it.key.stableSourceId },
        ).map { it.copy(capabilities = freezeSet(it.capabilities), originalArtwork = freezeList(it.originalArtwork)) }
        val keys = copies.mapTo(hashSetOf()) { it.key }
        val preferences = members.mapNotNull { it.preferredCopy }.filter(keys::contains).distinct()
        val copyCanonicalIds = linkedMapOf<OwnedCopyKey, CanonicalGameId>()
        val memberSteamAppIds = linkedMapOf<CanonicalGameId, Int?>()
        val memberAppTypes = linkedMapOf<CanonicalGameId, CanonicalAppType>()
        val genreLabels = linkedMapOf<String, String>()
        for (member in members) {
            member.copies.forEach { copy ->
                copyCanonicalIds[copy.key] = member.copyCanonicalIds[copy.key] ?: member.canonicalId
            }
            memberSteamAppIds.putAll(member.memberSteamAppIds.ifEmpty { mapOf(member.canonicalId to member.steamAppId) })
            memberAppTypes[member.canonicalId] = member.appType
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
            memberAppTypes = freezeMap(memberAppTypes),
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
