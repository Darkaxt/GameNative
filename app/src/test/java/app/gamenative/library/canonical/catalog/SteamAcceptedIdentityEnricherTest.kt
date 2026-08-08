package app.gamenative.library.canonical.catalog

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameEntity
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.ClassificationState
import app.gamenative.data.canonical.GameDetailSnapshotEntity
import app.gamenative.db.dao.CanonicalGameDao
import app.gamenative.library.discovery.GameFacet
import app.gamenative.library.discovery.GameFacetRepository
import app.gamenative.library.discovery.SteamPopularityEnricher
import app.gamenative.library.discovery.SteamReviewSummary
import app.gamenative.library.discovery.SteamReviewSummarySource
import app.gamenative.library.metadata.CanonicalGameMetadata
import app.gamenative.library.metadata.GameDetailState
import app.gamenative.library.metadata.GameMetadataRepository
import app.gamenative.library.metadata.MetadataLocale
import app.gamenative.library.metadata.MetadataPersistenceResult
import app.gamenative.library.metadata.MetadataRefreshResult
import app.gamenative.library.metadata.SteamCatalogRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamAcceptedIdentityEnricherTest {
    @Test
    fun `accepted non-owned identity persists metadata PICS facets and popularity`() = runTest {
        val gameDao = FakeCanonicalGameDao(canonical())
        val metadataRepository = FakeMetadataRepository()
        val facetRepository = FakeFacetRepository()
        val picsSource = SteamPublicPicsFacetSource {
            SteamPublicPicsFacets(
                genreIds = setOf(1),
                categoryIds = setOf(2),
                storeTagIds = setOf(19),
            )
        }
        val popularity = SteamPopularityEnricher(
            source = SteamReviewSummarySource { SteamReviewSummary(totalReviews = 12_345) },
            canonicalGameDao = gameDao,
        )
        val enricher = SteamAcceptedIdentityEnricher(
            canonicalGameDao = gameDao,
            metadataRepository = metadataRepository,
            facetRepository = facetRepository,
            picsSource = picsSource,
            popularityEnricher = popularity,
        )
        val record = record()

        val result = enricher.enrich(
            trustedSteamAppId = STEAM_APP_ID,
            locale = LOCALE,
            record = record,
        )

        assertEquals(SteamAcceptedIdentityEnrichmentResult.Enriched, result)
        assertEquals(listOf(MetadataWrite(CANONICAL_ID, STEAM_APP_ID, LOCALE, record)), metadataRepository.writes)
        assertEquals(
            listOf(PicsWrite(CANONICAL_ID, STEAM_APP_ID, SteamPublicPicsFacets(setOf(1), setOf(2), setOf(19)))),
            facetRepository.picsWrites,
        )
        assertEquals(12_345L, gameDao.current?.steamReviewCount)
        assertTrue(gameDao.inserted.isEmpty())
    }

    @Test
    fun `unavailable PICS keeps AppDetails metadata and still enriches popularity`() = runTest {
        val gameDao = FakeCanonicalGameDao(canonical())
        val metadataRepository = FakeMetadataRepository()
        val facetRepository = FakeFacetRepository()
        val enricher = SteamAcceptedIdentityEnricher(
            canonicalGameDao = gameDao,
            metadataRepository = metadataRepository,
            facetRepository = facetRepository,
            picsSource = SteamPublicPicsFacetSource { null },
            popularityEnricher = SteamPopularityEnricher(
                source = SteamReviewSummarySource { SteamReviewSummary(totalReviews = 321) },
                canonicalGameDao = gameDao,
            ),
        )

        val result = enricher.enrich(STEAM_APP_ID, LOCALE, record())

        assertEquals(SteamAcceptedIdentityEnrichmentResult.Enriched, result)
        assertEquals(1, metadataRepository.writes.size)
        assertTrue(facetRepository.picsWrites.isEmpty())
        assertEquals(321L, gameDao.current?.steamReviewCount)
    }

    private class FakeMetadataRepository : GameMetadataRepository {
        val writes = mutableListOf<MetadataWrite>()

        override fun observe(canonicalId: CanonicalGameId): Flow<GameDetailState> = emptyFlow()

        override suspend fun refresh(canonicalId: CanonicalGameId): MetadataRefreshResult =
            MetadataRefreshResult.Failed

        override suspend fun persistValidatedSteamRecord(
            canonicalId: CanonicalGameId,
            trustedSteamAppId: Int,
            locale: MetadataLocale,
            record: SteamCatalogRecord,
        ): MetadataPersistenceResult {
            writes += MetadataWrite(canonicalId, trustedSteamAppId, locale, record)
            return MetadataPersistenceResult.Persisted
        }
    }

    private class FakeFacetRepository : GameFacetRepository {
        val picsWrites = mutableListOf<PicsWrite>()

        override suspend fun upsertSteamGenresAndSnapshot(
            canonicalId: CanonicalGameId,
            genres: List<app.gamenative.library.metadata.MetadataFacet>,
            snapshot: GameDetailSnapshotEntity,
        ) = Unit

        override suspend fun upsertSteamPicsFacets(
            canonicalId: CanonicalGameId,
            trustedSteamAppId: Int,
            facets: SteamPublicPicsFacets,
        ): Boolean {
            picsWrites += PicsWrite(canonicalId, trustedSteamAppId, facets)
            return true
        }

        override fun resolveGenres(
            keys: Set<String>,
            snapshots: List<GameDetailSnapshotEntity>,
        ): List<GameFacet> = emptyList()
    }

    private class FakeCanonicalGameDao(
        var current: CanonicalGameEntity?,
    ) : CanonicalGameDao {
        val inserted = mutableListOf<CanonicalGameEntity>()

        override suspend fun get(canonicalId: String): CanonicalGameEntity? =
            current?.takeIf { it.canonicalId == canonicalId }

        override suspend fun findBySteamAppId(steamAppId: Int): CanonicalGameEntity? =
            current?.takeIf { it.steamAppId == steamAppId }

        override suspend fun findByTitleKey(titleKey: String): List<CanonicalGameEntity> = emptyList()

        override suspend fun getAll(): List<CanonicalGameEntity> = listOfNotNull(current)

        override suspend fun updateSteamReviewCountIfMissing(
            canonicalId: String,
            steamAppId: Int,
            totalReviews: Long,
        ): Int {
            val existing = current?.takeIf {
                it.canonicalId == canonicalId && it.steamAppId == steamAppId && it.steamReviewCount == null
            } ?: return 0
            current = existing.copy(steamReviewCount = totalReviews)
            return 1
        }

        override suspend fun insert(entity: CanonicalGameEntity) {
            inserted += entity
            current = entity
        }

        override suspend fun update(entity: CanonicalGameEntity) {
            current = entity
        }

        override suspend fun delete(canonicalId: String) {
            current = null
        }
    }

    private data class MetadataWrite(
        val canonicalId: CanonicalGameId,
        val steamAppId: Int,
        val locale: MetadataLocale,
        val record: SteamCatalogRecord,
    )

    private data class PicsWrite(
        val canonicalId: CanonicalGameId,
        val steamAppId: Int,
        val facets: SteamPublicPicsFacets,
    )

    private companion object {
        const val STEAM_APP_ID = 42
        val CANONICAL_ID = CanonicalGameId.parse("11111111-1111-1111-1111-111111111111")
        val LOCALE = MetadataLocale("en-US", "US")

        fun canonical() = CanonicalGameEntity(
            canonicalId = CANONICAL_ID.value,
            steamAppId = STEAM_APP_ID,
            displayName = "Catalog game",
            matchTitleKey = "catalog game",
            primaryMetadataSource = GameSource.GOG,
            appType = CanonicalAppType.GAME,
            releaseYear = 2020,
            developerKey = "studio",
            classificationState = ClassificationState.UNCLASSIFIED,
            steamReviewCount = null,
            createdAt = 1,
            updatedAt = 1,
        )

        fun record() = SteamCatalogRecord(
            steamAppId = STEAM_APP_ID,
            appType = CanonicalAppType.GAME,
            releaseYear = 2020,
            metadata = CanonicalGameMetadata(
                title = "Catalog game",
                shortDescription = null,
                about = null,
                headerImageUrl = null,
                screenshots = emptyList(),
                movies = emptyList(),
                developers = listOf("Studio"),
                publishers = emptyList(),
                releaseDate = "2020",
                platforms = emptySet(),
                languages = emptyList(),
                requirements = null,
                features = emptyList(),
                achievementCount = null,
                dlcCount = null,
                fetchedAtEpochMs = 1,
            ),
        )
    }
}
