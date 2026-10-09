package app.gamenative.library.canonical

object CanonicalCardLookup {
    fun resolve(cards: List<CanonicalLibraryCard>, key: CanonicalCardKey): CanonicalLibraryCard? {
        val exact = cards.filter { it.key == key }
        if (exact.isNotEmpty()) return exact.singleOrNull()
        if (key !is CanonicalCardKey.Grouped) return null
        val claims = cards.filter {
            key.canonicalId in it.memberSteamAppIds || key.canonicalId in it.copyCanonicalIds.values
        }
        return claims.singleOrNull()?.takeIf { it.hasValidFamilyBindings() }
    }
}

internal fun CanonicalLibraryCard.hasValidFamilyBindings(): Boolean {
    val grouped = key as? CanonicalCardKey.Grouped ?: return false
    if (grouped.canonicalId != canonicalId || !isPresentationFamily) return false
    val keys = copies.map { it.key }
    return keys.isNotEmpty() && keys.distinct().size == keys.size &&
        copyCanonicalIds.keys == keys.toSet() &&
        copyCanonicalIds.values.toSet() == memberSteamAppIds.keys &&
        canonicalId in memberSteamAppIds && memberSteamAppIds[canonicalId] == steamAppId &&
        memberSteamAppIds.values.all { it == null || it > 0 }
}
