package app.gamenative.library.canonical.catalog

import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.canonical.artwork.ArtworkFingerprintSource
import app.gamenative.library.canonical.artwork.ArtworkUrlPolicy
import app.gamenative.library.canonical.artwork.CachedArtworkFingerprintSource
import app.gamenative.library.canonical.artwork.steamPortraitArtworkUrl
import app.gamenative.library.canonical.runtime.OwnedCopyRuntime
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeRegistry
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeResult
import app.gamenative.library.canonical.runtime.requireIdentity
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class SteamCatalogArtworkCorroborator internal constructor(
    private val fingerprints: ArtworkFingerprintSource,
    private val lookupRuntime: suspend (OwnedCopyKey) -> OwnedCopyRuntimeResult,
) {
    @Inject
    constructor(
        fingerprints: CachedArtworkFingerprintSource,
        runtimes: OwnedCopyRuntimeRegistry,
    ) : this(fingerprints, runtimes::resolve)

    suspend fun corroborate(
        key: OwnedCopyKey,
        source: SourceCatalogEvidence,
        candidates: List<SteamCatalogCandidate>,
    ): Set<Int> {
        if (source.appType != CanonicalAppType.GAME) return emptySet()
        val titleKey = SteamCatalogNormalization.titleKey(source.title)
        if (titleKey.isEmpty()) return emptySet()
        val exactGames = candidates.filter {
            it.appType == CanonicalAppType.GAME && SteamCatalogNormalization.titleKey(it.title) == titleKey
        }.distinctBy(SteamCatalogCandidate::steamAppId)
        if (exactGames.isEmpty() || exactGames.size > MAX_ARTWORK_CANDIDATES) return emptySet()

        return try {
            withTimeoutOrNull(ARTWORK_PHASE_TIMEOUT_MS) {
                val originalCopy = currentCopy(key, source) ?: return@withTimeoutOrNull emptySet()
                val originalUrl = originalCopy.originalArtworkUrl ?: return@withTimeoutOrNull emptySet()
                val nativeSteamAppId = (originalCopy.reference as? SourceOwnedCopyReference.Steam)?.appId
                if (ArtworkUrlPolicy.Default.originalUrl(key.source, originalUrl, nativeSteamAppId) == null) {
                    return@withTimeoutOrNull emptySet()
                }
                val original = fingerprints.original(key.source, originalUrl, nativeSteamAppId)
                    ?.takeIf { it.isValid() } ?: return@withTimeoutOrNull emptySet()
                val corroborated = mutableSetOf<Int>()
                for (candidate in exactGames) {
                    val portrait = fingerprints.candidate(candidate.steamAppId, steamPortraitArtworkUrl(candidate.steamAppId))
                        ?.takeIf { it.isValid() } ?: return@withTimeoutOrNull emptySet()
                    if (original.corroborates(portrait)) corroborated += candidate.steamAppId
                }
                val refreshed = currentCopy(key, source) ?: return@withTimeoutOrNull emptySet()
                if (refreshed.originalArtworkUrl != originalUrl || refreshed.reference != originalCopy.reference) {
                    return@withTimeoutOrNull emptySet()
                }
                corroborated.toSet()
            }.orEmpty()
        } catch (_: IOException) {
            emptySet()
        }
    }

    private suspend fun currentCopy(key: OwnedCopyKey, source: SourceCatalogEvidence): OwnedCopyRuntime? {
        val copy = (lookupRuntime(key) as? OwnedCopyRuntimeResult.Available)?.copy ?: return null
        if (copy.key != key || copy.reference.key != key || copy.appType != source.appType ||
            SteamCatalogNormalization.titleKey(copy.nativeTitle) != SteamCatalogNormalization.titleKey(source.title) ||
            SteamCatalogNormalization.developerKey(copy.developerKey) != SteamCatalogNormalization.developerKey(source.developer) ||
            copy.releaseYear != source.releaseYear
        ) return null
        copy.requireIdentity(key)
        return copy
    }

    private companion object {
        const val MAX_ARTWORK_CANDIDATES = 3
        const val ARTWORK_PHASE_TIMEOUT_MS = 20_000L
    }
}
