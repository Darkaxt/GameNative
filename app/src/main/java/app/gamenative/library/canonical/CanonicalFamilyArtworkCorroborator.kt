package app.gamenative.library.canonical

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.EpicStableSourceId
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.canonical.artwork.ArtworkFingerprint
import app.gamenative.library.canonical.artwork.ArtworkFingerprintSource
import app.gamenative.library.canonical.artwork.ArtworkUrlPolicy
import app.gamenative.library.canonical.artwork.CachedArtworkFingerprintSource
import app.gamenative.library.canonical.catalog.SteamCatalogNormalization
import app.gamenative.library.canonical.runtime.OwnedCopyRuntime
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeRegistry
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeResult
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeoutOrNull

/** Optional native evidence acquisition; the resulting family is presentation, never a catalog mutation. */
@Singleton
class CanonicalFamilyArtworkCorroborator internal constructor(
    private val fingerprints: ArtworkFingerprintSource,
    private val lookupRuntime: suspend (OwnedCopyKey) -> OwnedCopyRuntimeResult,
) {
    @Inject
    constructor(fingerprints: CachedArtworkFingerprintSource, runtimes: OwnedCopyRuntimeRegistry) :
        this(fingerprints, runtimes::resolve)

    suspend fun project(cards: List<CanonicalLibraryCard>): List<CanonicalLibraryCard> {
        if (cards.map { it.canonicalId }.distinct().size != cards.size) return cards
        val copyKeys = cards.flatMap { it.copies }.map { it.key }
        if (copyKeys.distinct().size != copyKeys.size) return cards
        val candidates = cards.filter(CanonicalGameFamilyProjection::eligible)
            .groupBy { CanonicalGameFamilyProjection.familyTitleKey(it.displayName) }
            .toSortedMap().filter { (title, members) ->
                title.isNotBlank() && members.size in 2..CanonicalGameFamilyProjection.MAX_FAMILY_MEMBERS &&
                    members.all { it.copies.size <= MAX_MEMBER_COPIES } &&
                    members.map { it.canonicalId }.distinct().size == members.size &&
                    members.flatMap { it.copies }.let { copies -> copies.map { it.key }.distinct().size == copies.size }
            }.values
        if (candidates.isEmpty()) return cards
        return try {
            withTimeoutOrNull(PHASE_TIMEOUT_MS) {
                val artworks = linkedMapOf<CanonicalGameId, ArtworkFingerprint>()
                val developers = linkedMapOf<CanonicalGameId, Set<String>>()
                val evidence = linkedMapOf<CanonicalGameId, Pair<CanonicalLibraryCard, OwnedCopyRuntime>>()
                var consideredMembers = 0
                for (members in candidates) {
                    if (consideredMembers + members.size > MAX_PHASE_MEMBERS) continue
                    consideredMembers += members.size
                    val originals = linkedMapOf<CanonicalGameId, OwnedCopyRuntime>()
                    for (member in members.sortedBy { it.canonicalId.value }) {
                        val original = originalCopy(member) ?: break
                        originals[member.canonicalId] = original
                    }
                    if (originals.size != members.size) continue
                    val acquired = linkedMapOf<CanonicalGameId, ArtworkFingerprint>()
                    for ((id, original) in originals) {
                        val fingerprint = fingerprints.original(original.key.source, requireNotNull(original.originalArtworkUrl),
                            (original.reference as? SourceOwnedCopyReference.Steam)?.appId)?.takeIf { it.isValid() } ?: break
                        acquired[id] = fingerprint
                    }
                    if (acquired.size != members.size) continue
                    artworks.putAll(acquired)
                    for (member in members) {
                        val original = originals.getValue(member.canonicalId)
                        developers[member.canonicalId] = setOf(original.developerKey)
                        evidence[member.canonicalId] = member to original
                    }
                }
                for ((member, original) in evidence.values) {
                    val refreshed = currentCopy(original.key, member.copies.single { it.key == original.key })
                    if (refreshed == null || !sameEvidence(original, refreshed)) return@withTimeoutOrNull cards
                }
                CanonicalGameFamilyProjection.project(cards, artworks, developers)
            } ?: cards
        } catch (_: IOException) {
            cards
        }
    }

    private suspend fun originalCopy(member: CanonicalLibraryCard): OwnedCopyRuntime? {
        for (summary in member.copies.sortedWith(compareBy<OwnedCopySummary> { it.source.ordinal }
            .thenBy { it.key.accountScope.value }.thenBy { it.key.stableSourceId })) {
            val copy = currentCopy(summary.key, summary) ?: continue
            val url = copy.originalArtworkUrl ?: continue
            val nativeAppId = (copy.reference as? SourceOwnedCopyReference.Steam)?.appId
            if (ArtworkUrlPolicy.Default.originalUrl(copy.key.source, url, nativeAppId) != null) return copy
        }
        return null
    }

    private suspend fun currentCopy(key: OwnedCopyKey, summary: OwnedCopySummary): OwnedCopyRuntime? {
        val copy = (lookupRuntime(key) as? OwnedCopyRuntimeResult.Available)?.copy ?: return null
        if (copy.key != key || copy.reference.key != key || summary.source != key.source || copy.isHidden ||
            copy.appType != CanonicalAppType.GAME ||
            SteamCatalogNormalization.titleKey(copy.nativeTitle) != SteamCatalogNormalization.titleKey(summary.nativeTitle) ||
            !exactNativeIdentity(copy)
        ) return null
        return copy
    }

    private fun exactNativeIdentity(copy: OwnedCopyRuntime): Boolean {
        val key = copy.key
        val expectedId = when (val reference = copy.reference) {
            is SourceOwnedCopyReference.Steam -> {
                if (key.source != GameSource.STEAM || reference.appId <= 0 || "${reference.appId}" != key.stableSourceId) return false
                "STEAM_${reference.appId}"
            }
            is SourceOwnedCopyReference.Gog -> {
                if (key.source != GameSource.GOG || reference.gameId != key.stableSourceId) return false
                "GOG_${reference.gameId}"
            }
            is SourceOwnedCopyReference.Epic -> {
                if (key.source != GameSource.EPIC || reference.namespace.isBlank() || reference.catalogId.isBlank() ||
                    EpicStableSourceId.encode(reference.namespace, reference.catalogId) != key.stableSourceId) return false
                "EPIC_${reference.localRowId}"
            }
            is SourceOwnedCopyReference.Amazon -> {
                if (key.source != GameSource.AMAZON || reference.productId != key.stableSourceId || reference.entitlementId.isBlank()) return false
                "AMAZON_${reference.localRowId}"
            }
            is SourceOwnedCopyReference.Custom -> return false
        }
        val item = copy.libraryItem ?: return key.source == GameSource.GOG && key.stableSourceId.toIntOrNull() == null
        return item.gameSource == key.source && item.appId == expectedId
    }

    private fun sameEvidence(before: OwnedCopyRuntime, after: OwnedCopyRuntime): Boolean =
        before.key == after.key && before.reference == after.reference &&
            before.originalArtworkUrl == after.originalArtworkUrl && before.nativeTitle == after.nativeTitle &&
            before.appType == after.appType && before.developerKey == after.developerKey &&
            before.releaseYear == after.releaseYear && before.libraryItem?.appId == after.libraryItem?.appId &&
            before.libraryItem?.gameSource == after.libraryItem?.gameSource

    private companion object {
        const val MAX_MEMBER_COPIES = 10
        const val MAX_PHASE_MEMBERS = 30
        const val PHASE_TIMEOUT_MS = 20_000L
    }
}
