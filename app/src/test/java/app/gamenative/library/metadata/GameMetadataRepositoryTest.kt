package app.gamenative.library.metadata

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameEntity
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.ClassificationState
import app.gamenative.data.canonical.GameDetailSnapshotEntity
import app.gamenative.library.discovery.GameFacet
import app.gamenative.library.discovery.GameFacetRepository
import app.gamenative.db.dao.CanonicalGameDao
import app.gamenative.db.dao.GameDetailSnapshotDao
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameMetadataRepositoryTest {
    @Test
    fun resolvesOnlyTrustedCanonicalSteamIdAndPersistsTypedSnapshot() = runTest {
        val canonicalId = canonicalId()
        val gameDao = FakeCanonicalGameDao(canonical(steamAppId = TRUSTED_APP_ID))
        val snapshotDao = FakeSnapshotDao()
        val facetRepository = FakeGameFacetRepository(snapshotDao)
        val provider = FakeProvider(
            metadata("Network title", NOW).copy(
                genres = listOf(MetadataFacet(1, "<b>Action</b>"), MetadataFacet(null, "Ignored")),
                features = listOf(MetadataFacet(2, "Single-player")),
            ),
        )
        val repository = repository(gameDao, snapshotDao, provider, facetRepository)

        val result = repository.refresh(canonicalId)

        assertEquals(MetadataRefreshResult.Refreshed, result)
        assertEquals(listOf(TRUSTED_APP_ID), provider.requestedIds)
        val stored = requireNotNull(snapshotDao.current.value)
        assertEquals(canonicalId.value, stored.canonicalId)
        assertEquals("en-US", stored.locale)
        assertEquals("US", stored.country)
        assertEquals("steam_appdetails_v1", stored.sourceRevision)
        assertTrue(stored.provenanceJson.contains("STEAM_APPDETAILS"))
        assertFalse(stored.payloadJson.contains(TRUSTED_APP_ID.toString()))
        assertEquals(listOf(MetadataFacet(1, "Action")), facetRepository.lastGenres)
        assertEquals(listOf(MetadataFacet(2, "Single-player")), facetRepository.lastFeatures)
        assertEquals(1, facetRepository.writeCount)
    }

    @Test
    fun persistsValidatedRecordWithoutRefetchAndPromotesSteamPresentation() = runTest {
        val canonicalId = canonicalId()
        val gameDao = FakeCanonicalGameDao(
            canonical(TRUSTED_APP_ID).copy(primaryMetadataSource = GameSource.GOG),
        )
        val snapshotDao = FakeSnapshotDao()
        val facetRepository = FakeGameFacetRepository(snapshotDao)
        val provider = FakeProvider(failure = AssertionError("validated persistence must not refetch"))
        val repository = repository(gameDao, snapshotDao, provider, facetRepository)
        val validated = SteamCatalogRecord(
            steamAppId = TRUSTED_APP_ID,
            appType = CanonicalAppType.GAME,
            releaseYear = 2020,
            metadata = metadata("Validated title", NOW - 100).copy(
                developers = listOf("Validated Studio"),
                genres = listOf(MetadataFacet(1, "Action")),
                features = listOf(MetadataFacet(2, "Single-player")),
            ),
        )

        val result = repository.persistValidatedSteamRecord(
            canonicalId = canonicalId,
            trustedSteamAppId = TRUSTED_APP_ID,
            locale = MetadataLocale("en-US", "US"),
            record = validated,
        )

        assertEquals(MetadataPersistenceResult.Persisted, result)
        assertTrue(provider.requestedIds.isEmpty())
        val updated = requireNotNull(gameDao.get(canonicalId.value))
        assertEquals(GameSource.STEAM, updated.primaryMetadataSource)
        assertEquals("Validated title", updated.displayName)
        assertEquals(2020, updated.releaseYear)
        assertEquals("validated studio", updated.developerKey)
        assertEquals(listOf(MetadataFacet(1, "Action")), facetRepository.lastGenres)
        assertEquals(listOf(MetadataFacet(2, "Single-player")), facetRepository.lastFeatures)
        assertEquals(NOW, decode(snapshotDao.current.value).fetchedAtEpochMs)
        assertEquals(1, facetRepository.writeCount)
    }

    @Test
    fun servesFreshCacheImmediatelyWithoutNetworkForOfflineUse() = runTest {
        val cached = metadata("Cached title", NOW - 1_000L)
        val snapshotDao = FakeSnapshotDao(snapshot(cached))
        val provider = FakeProvider(failure = IOException("offline"))
        val repository = repository(
            FakeCanonicalGameDao(canonical(TRUSTED_APP_ID)),
            snapshotDao,
            provider,
        )

        val state = repository.observe(canonicalId()).first()

        val content = state as GameDetailState.Content
        assertEquals("Cached title", content.metadata.title)
        assertFalse(content.stale)
        assertFalse(content.refreshFailed)
        assertTrue(provider.requestedIds.isEmpty())
    }

    @Test
    fun emitsStaleCacheFirstThenRefreshesAfterSevenDays() = runTest {
        val stale = metadata("Last known good", NOW - GameMetadataRepository.CACHE_MAX_AGE_MS - 1L)
        val fresh = metadata("Fresh title", NOW)
        val snapshotDao = FakeSnapshotDao(snapshot(stale))
        val provider = FakeProvider(fresh)
        val repository = repository(
            FakeCanonicalGameDao(canonical(TRUSTED_APP_ID)),
            snapshotDao,
            provider,
        )

        val states = repository.observe(canonicalId())
            .filterIsInstance<GameDetailState.Content>()
            .take(2)
            .toList()

        assertEquals("Last known good", states[0].metadata.title)
        assertTrue(states[0].stale)
        assertEquals("Fresh title", states[1].metadata.title)
        assertFalse(states[1].stale)
        assertEquals(1, provider.requestedIds.size)
    }

    @Test
    fun failedStaleRefreshRetainsLastKnownGoodAndShowsFailure() = runTest {
        val stale = metadata("Last known good", NOW - GameMetadataRepository.CACHE_MAX_AGE_MS - 1L)
        val snapshotDao = FakeSnapshotDao(snapshot(stale))
        val facetRepository = FakeGameFacetRepository(snapshotDao).apply {
            lastGenres = listOf(MetadataFacet(1, "Action"))
        }
        val provider = FakeProvider(failure = IOException("offline"))
        val repository = repository(
            FakeCanonicalGameDao(canonical(TRUSTED_APP_ID)),
            snapshotDao,
            provider,
            facetRepository,
        )

        val states = repository.observe(canonicalId())
            .filterIsInstance<GameDetailState.Content>()
            .take(2)
            .toList()

        assertEquals("Last known good", states[0].metadata.title)
        assertEquals("Last known good", states[1].metadata.title)
        assertTrue(states[1].stale)
        assertTrue(states[1].refreshFailed)
        assertEquals("Last known good", decode(snapshotDao.current.value).title)
        assertEquals(listOf(MetadataFacet(1, "Action")), facetRepository.lastGenres)
        assertEquals(0, facetRepository.writeCount)
    }

    @Test
    fun failedInitialFetchPublishesFixedUnavailableState() = runTest {
        val repository = repository(
            FakeCanonicalGameDao(canonical(TRUSTED_APP_ID)),
            FakeSnapshotDao(),
            FakeProvider(failure = IOException("private provider message")),
        )

        val states = repository.observe(canonicalId()).take(2).toList()

        assertEquals(GameDetailState.Loading, states[0])
        assertEquals(GameDetailState.Unavailable(cached = null), states[1])
        assertFalse(states.joinToString().contains("private provider message"))
    }

    @Test
    fun missingTrustedSteamIdDoesNotGuessOrCallProvider() = runTest {
        val provider = FakeProvider(metadata("must not be used", NOW))
        val snapshotDao = FakeSnapshotDao()
        val facetRepository = FakeGameFacetRepository(snapshotDao)
        val repository = repository(
            FakeCanonicalGameDao(canonical(steamAppId = null)),
            snapshotDao,
            provider,
            facetRepository,
        )

        val states = repository.observe(canonicalId()).take(2).toList()

        assertEquals(GameDetailState.Loading, states[0])
        assertEquals(GameDetailState.Unavailable(cached = null), states[1])
        assertTrue(provider.requestedIds.isEmpty())
        assertEquals(0, facetRepository.writeCount)
    }

    @Test
    fun cancellationFromProviderEscapesRefresh() = runTest {
        val provider = object : SteamCatalogDataSource {
            override suspend fun fetch(
                trustedSteamAppId: Int,
                locale: MetadataLocale,
            ): CanonicalGameMetadata? {
                throw kotlinx.coroutines.CancellationException("cancel")
            }
        }
        val repository = repository(
            FakeCanonicalGameDao(canonical(TRUSTED_APP_ID)),
            FakeSnapshotDao(),
            provider,
        )

        try {
            repository.refresh(canonicalId())
            throw AssertionError("Expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) {
        }
    }

    private fun repository(
        gameDao: CanonicalGameDao,
        snapshotDao: GameDetailSnapshotDao,
        provider: SteamCatalogDataSource,
        facetRepository: GameFacetRepository = FakeGameFacetRepository(snapshotDao),
    ): RoomGameMetadataRepository = RoomGameMetadataRepository(
        canonicalGameDao = gameDao,
        snapshotDao = snapshotDao,
        gameFacetRepository = facetRepository,
        provider = provider,
        localeProvider = MetadataLocaleProvider { MetadataLocale("en-US", "us") },
        clock = MetadataClock { NOW },
        dispatcher = Dispatchers.Unconfined,
    )

    private fun canonical(steamAppId: Int?): CanonicalGameEntity = CanonicalGameEntity(
        canonicalId = canonicalId().value,
        steamAppId = steamAppId,
        displayName = "Canonical title",
        matchTitleKey = "canonical title",
        primaryMetadataSource = GameSource.STEAM,
        appType = CanonicalAppType.GAME,
        releaseYear = null,
        developerKey = "",
        classificationState = ClassificationState.CLASSIFIED,
        steamReviewCount = null,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun metadata(title: String, fetchedAt: Long): CanonicalGameMetadata =
        CanonicalGameMetadata(
            title = title,
            shortDescription = "Plain text",
            about = null,
            headerImageUrl = null,
            screenshots = emptyList(),
            movies = emptyList(),
            developers = emptyList(),
            publishers = emptyList(),
            releaseDate = null,
            platforms = emptySet(),
            languages = emptyList(),
            requirements = null,
            features = emptyList(),
            achievementCount = null,
            dlcCount = null,
            fetchedAtEpochMs = fetchedAt,
        )

    private fun snapshot(metadata: CanonicalGameMetadata): GameDetailSnapshotEntity =
        GameDetailSnapshotEntity(
            canonicalId = canonicalId().value,
            locale = "en-US",
            country = "US",
            payloadJson = JSON.encodeToString(metadata),
            provenanceJson = JSON.encodeToString(
                GameMetadataProvenance(
                    provider = MetadataProvider.STEAM_APPDETAILS,
                    fields = setOf(MetadataField.TITLE),
                ),
            ),
            fetchedAt = metadata.fetchedAtEpochMs,
            sourceRevision = "steam_appdetails_v1",
        )

    private fun decode(entity: GameDetailSnapshotEntity?): CanonicalGameMetadata =
        JSON.decodeFromString(requireNotNull(entity).payloadJson)

    private class FakeGameFacetRepository(
        private val snapshotDao: GameDetailSnapshotDao,
    ) : GameFacetRepository {
        var lastGenres: List<MetadataFacet> = emptyList()
        var lastFeatures: List<MetadataFacet> = emptyList()
        var writeCount: Int = 0

        override suspend fun upsertValidatedSteamMetadata(
            canonicalId: CanonicalGameId,
            trustedSteamAppId: Int,
            genres: List<MetadataFacet>,
            features: List<MetadataFacet>,
            snapshot: GameDetailSnapshotEntity,
        ): Boolean {
            writeCount += 1
            lastGenres = genres
            lastFeatures = features
            snapshotDao.upsert(snapshot)
            return true
        }

        override suspend fun upsertSteamGenresAndSnapshot(
            canonicalId: CanonicalGameId,
            genres: List<MetadataFacet>,
            snapshot: GameDetailSnapshotEntity,
        ) {
            writeCount += 1
            lastGenres = genres
            snapshotDao.upsert(snapshot)
        }

        override fun resolveGenres(
            keys: Set<String>,
            snapshots: List<GameDetailSnapshotEntity>,
        ): List<GameFacet> = emptyList()
    }

    private class FakeProvider(
        var metadata: CanonicalGameMetadata? = null,
        var failure: Throwable? = null,
    ) : SteamCatalogDataSource {
        val requestedIds = mutableListOf<Int>()

        override suspend fun fetch(
            trustedSteamAppId: Int,
            locale: MetadataLocale,
        ): CanonicalGameMetadata? {
            requestedIds += trustedSteamAppId
            failure?.let { throw it }
            return metadata
        }
    }

    private class FakeSnapshotDao(
        initial: GameDetailSnapshotEntity? = null,
    ) : GameDetailSnapshotDao {
        val current = MutableStateFlow(initial)

        override suspend fun get(
            canonicalId: String,
            locale: String,
            country: String,
        ): GameDetailSnapshotEntity? = current.value?.takeIf {
            it.canonicalId == canonicalId && it.locale == locale && it.country == country
        }

        override fun observe(
            canonicalId: String,
            locale: String,
            country: String,
        ): Flow<GameDetailSnapshotEntity?> = current

        override suspend fun getByCanonicalId(canonicalId: String): List<GameDetailSnapshotEntity> =
            listOfNotNull(current.value?.takeIf { it.canonicalId == canonicalId })

        override suspend fun upsert(entity: GameDetailSnapshotEntity) {
            current.value = entity
        }

        override suspend fun delete(canonicalId: String, locale: String, country: String) {
            current.value = null
        }

        override suspend fun deleteByCanonicalId(canonicalId: String) {
            current.value = null
        }
    }

    private class FakeCanonicalGameDao(
        private var entity: CanonicalGameEntity?,
    ) : CanonicalGameDao {
        override suspend fun get(canonicalId: String): CanonicalGameEntity? =
            entity?.takeIf { it.canonicalId == canonicalId }

        override suspend fun findBySteamAppId(steamAppId: Int): CanonicalGameEntity? =
            entity?.takeIf { it.steamAppId == steamAppId }

        override suspend fun findByTitleKey(titleKey: String): List<CanonicalGameEntity> = emptyList()
        override suspend fun getAll(): List<CanonicalGameEntity> = listOfNotNull(entity)
        override suspend fun updateSteamReviewCountIfMissing(
            canonicalId: String,
            steamAppId: Int,
            totalReviews: Long,
        ): Int = 0
        override suspend fun insert(entity: CanonicalGameEntity) {
            this.entity = entity
        }
        override suspend fun update(entity: CanonicalGameEntity) {
            this.entity = entity
        }
        override suspend fun delete(canonicalId: String) {
            entity = null
        }
    }

    private companion object {
        const val NOW = 2_000_000_000L
        const val TRUSTED_APP_ID = 424242
        val JSON = Json { encodeDefaults = true }

        fun canonicalId(): CanonicalGameId =
            CanonicalGameId.parse("11111111-1111-1111-1111-111111111111")
    }
}
