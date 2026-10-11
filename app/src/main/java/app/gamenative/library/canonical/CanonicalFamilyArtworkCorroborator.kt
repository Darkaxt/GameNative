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
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

    internal fun projectBatches(cards: List<CanonicalLibraryCard>): Flow<List<CanonicalLibraryCard>> = flow {
        if (!validSnapshot(cards)) return@flow
        var projected = cards
        for (members in candidates(cards)) {
            currentCoroutineContext().ensureActive()
            val result = project(members)
            if (result.size < members.size) {
                projected = replaceMembers(projected, members, result)
                emit(projected)
            }
        }
    }

    suspend fun project(cards: List<CanonicalLibraryCard>): List<CanonicalLibraryCard> {
        if (!validSnapshot(cards)) return cards
        var projected = cards
        withTimeoutOrNull(PHASE_TIMEOUT_MS) {
            var consideredMembers = 0
            for (members in candidates(cards)) {
                if (consideredMembers + members.size > MAX_PHASE_MEMBERS) continue
                consideredMembers += members.size
                val result = try {
                    corroborate(members)
                } catch (_: IOException) {
                    members
                }
                if (result.size < members.size) projected = replaceMembers(projected, members, result)
            }
        }
        return projected
    }

    private fun validSnapshot(cards: List<CanonicalLibraryCard>): Boolean {
        if (cards.map { it.canonicalId }.distinct().size != cards.size) return false
        val copyKeys = cards.flatMap { it.copies }.map { it.key }
        return copyKeys.distinct().size == copyKeys.size
    }

    private fun candidates(cards: List<CanonicalLibraryCard>): Collection<List<CanonicalLibraryCard>> =
        cards.filter(CanonicalGameFamilyProjection::eligible)
            .groupBy { CanonicalGameFamilyProjection.familyTitleKey(it.displayName) }
            .toSortedMap().filter { (title, members) ->
                title.isNotBlank() && members.size in 2..CanonicalGameFamilyProjection.MAX_FAMILY_MEMBERS &&
                    members.all { it.copies.size <= MAX_MEMBER_COPIES }
            }.values

    private suspend fun corroborate(members: List<CanonicalLibraryCard>): List<CanonicalLibraryCard> {
        val originals = linkedMapOf<CanonicalGameId, OwnedCopyRuntime>()
        for (member in members.sortedBy { it.canonicalId.value }) {
            originals[member.canonicalId] = originalCopy(member) ?: return members
        }
        val artworks = linkedMapOf<CanonicalGameId, ArtworkFingerprint>()
        for ((id, original) in originals) {
            artworks[id] = fingerprints.original(original.key.source, requireNotNull(original.originalArtworkUrl),
                (original.reference as? SourceOwnedCopyReference.Steam)?.appId)?.takeIf { it.isValid() } ?: return members
        }
        for (member in members) {
            val original = originals.getValue(member.canonicalId)
            val refreshed = currentCopy(original.key, member.copies.single { it.key == original.key })
            if (refreshed == null || !sameEvidence(original, refreshed)) return members
        }
        return CanonicalGameFamilyProjection.project(members, artworks,
            originals.mapValues { (_, original) -> setOf(original.developerKey) })
    }

    private fun replaceMembers(
        cards: List<CanonicalLibraryCard>,
        members: List<CanonicalLibraryCard>,
        result: List<CanonicalLibraryCard>,
    ): List<CanonicalLibraryCard> {
        val ids = members.mapTo(hashSetOf()) { it.canonicalId }
        return Collections.unmodifiableList((cards.filterNot { it.canonicalId in ids } + result)
            .sortedBy { it.key.stableComposeKey() })
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
