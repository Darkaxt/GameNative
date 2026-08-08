package app.gamenative.library.canonical.catalog

import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.db.dao.CanonicalGameDao
import app.gamenative.library.discovery.GameFacetRepository
import app.gamenative.library.discovery.SteamPopularityEnricher
import app.gamenative.library.discovery.SteamPopularityTarget
import app.gamenative.library.metadata.GameMetadataRepository
import app.gamenative.library.metadata.MetadataLocale
import app.gamenative.library.metadata.MetadataPersistenceResult
import app.gamenative.library.metadata.SteamCatalogRecord
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

sealed interface SteamAcceptedIdentityEnrichmentResult {
    data object Enriched : SteamAcceptedIdentityEnrichmentResult
    data object StaleIdentity : SteamAcceptedIdentityEnrichmentResult
    data object MetadataFailed : SteamAcceptedIdentityEnrichmentResult
}

fun interface SteamAcceptedIdentityEnrichmentSink {
    suspend fun enrich(
        trustedSteamAppId: Int,
        locale: MetadataLocale,
        record: SteamCatalogRecord,
    ): SteamAcceptedIdentityEnrichmentResult
}

@Singleton
class SteamAcceptedIdentityEnricher @Inject constructor(
    private val canonicalGameDao: CanonicalGameDao,
    private val metadataRepository: GameMetadataRepository,
    private val facetRepository: GameFacetRepository,
    private val picsSource: SteamPublicPicsFacetSource,
    private val popularityEnricher: SteamPopularityEnricher,
) : SteamAcceptedIdentityEnrichmentSink {
    override suspend fun enrich(
        trustedSteamAppId: Int,
        locale: MetadataLocale,
        record: SteamCatalogRecord,
    ): SteamAcceptedIdentityEnrichmentResult {
        if (trustedSteamAppId <= 0 || record.steamAppId != trustedSteamAppId) {
            return SteamAcceptedIdentityEnrichmentResult.StaleIdentity
        }
        val canonical = canonicalGameDao.findBySteamAppId(trustedSteamAppId)
            ?: return SteamAcceptedIdentityEnrichmentResult.StaleIdentity
        val canonicalId = CanonicalGameId.parse(canonical.canonicalId)
        when (
            metadataRepository.persistValidatedSteamRecord(
                canonicalId = canonicalId,
                trustedSteamAppId = trustedSteamAppId,
                locale = locale,
                record = record,
            )
        ) {
            MetadataPersistenceResult.Persisted -> Unit
            MetadataPersistenceResult.StaleIdentity -> {
                return SteamAcceptedIdentityEnrichmentResult.StaleIdentity
            }
            MetadataPersistenceResult.Failed -> {
                return SteamAcceptedIdentityEnrichmentResult.MetadataFailed
            }
        }

        val picsFacets = try {
            picsSource.fetch(trustedSteamAppId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (picsFacets != null) {
            try {
                facetRepository.upsertSteamPicsFacets(
                    canonicalId = canonicalId,
                    trustedSteamAppId = trustedSteamAppId,
                    facets = picsFacets,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // AppDetails metadata remains usable when optional PICS facets fail.
            }
        }

        popularityEnricher.enrich(
            visibleTargets = listOf(
                SteamPopularityTarget(
                    canonicalId = canonicalId,
                    steamAppId = trustedSteamAppId,
                    steamReviewCount = canonical.steamReviewCount
                        ?.coerceAtMost(Int.MAX_VALUE.toLong())
                        ?.toInt(),
                ),
            ),
            allTargets = emptyList(),
        )
        return SteamAcceptedIdentityEnrichmentResult.Enriched
    }
}
