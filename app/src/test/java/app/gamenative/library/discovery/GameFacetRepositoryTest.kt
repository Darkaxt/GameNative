package app.gamenative.library.discovery

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameEntity
import app.gamenative.data.canonical.CanonicalGameGenreCrossRef
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.ClassificationState
import app.gamenative.data.canonical.GameDetailSnapshotEntity
import app.gamenative.data.canonical.SteamTagDictionaryEntity
import app.gamenative.db.PluviaDatabase
import app.gamenative.db.dao.GameDetailSnapshotDao
import app.gamenative.library.metadata.CanonicalGameMetadata
import app.gamenative.library.metadata.GameMetadataProvenance
import app.gamenative.library.metadata.MetadataFacet
import app.gamenative.library.metadata.MetadataField
import app.gamenative.library.metadata.MetadataProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class GameFacetRepositoryTest {
    private lateinit var database: PluviaDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun steamTagDictionaryBulkUpsertPublishesLocalizedFacetsReactively() = runTest {
        val repository = RoomGameFacetRepository(
            database = database,
            facetDao = database.canonicalFacetDao(),
            snapshotDao = database.gameDetailSnapshotDao(),
        )
        val emission = async {
            repository.observeSteamTags().first { facets -> facets.size == 2 }
        }

        database.canonicalFacetDao().upsertSteamTags(
            listOf(
                SteamTagDictionaryEntity(492, "en-US", "Indie", 1L),
                SteamTagDictionaryEntity(19, "en-US", "Action", 1L),
            ),
        )

        assertEquals(
            listOf(SteamTagFacet(19, "Action"), SteamTagFacet(492, "Indie")),
            emission.await(),
        )
        assertEquals(
            listOf(19, 492),
            database.canonicalFacetDao().getSteamTags("en-US").map { it.tagId }.sorted(),
        )
    }

    @Test
    fun trustedSteamRefreshUpsertsGenresWithoutReplacingExistingFacetsAndRetainsPresentationLabel() = runTest {
        database.canonicalGameDao().insert(canonical())
        database.canonicalFacetDao().upsertGenres(
            listOf(
                CanonicalGameGenreCrossRef(CANONICAL_ID.value, "steam:1"),
                CanonicalGameGenreCrossRef(CANONICAL_ID.value, "gog:adventure"),
            ),
        )
        val metadata = metadata(listOf(MetadataFacet(2, "Strategy")))
        val snapshot = snapshot(metadata)
        val repository = RoomGameFacetRepository(
            database = database,
            facetDao = database.canonicalFacetDao(),
            snapshotDao = database.gameDetailSnapshotDao(),
        )

        repository.upsertSteamGenresAndSnapshot(CANONICAL_ID, metadata.genres, snapshot)

        assertEquals(
            listOf("gog:adventure", "steam:1", "steam:2"),
            database.canonicalFacetDao().getGenres(CANONICAL_ID.value).map { it.genreKey }.sorted(),
        )
        assertEquals(snapshot, database.gameDetailSnapshotDao().get(CANONICAL_ID.value, "en-US", "US"))
        assertEquals(
            listOf(GameFacet("steam:2", "Strategy")),
            repository.resolveGenres(
                keys = setOf("gog:adventure", "steam:2"),
                snapshots = listOf(snapshot),
            ),
        )
    }

    @Test
    fun failedSnapshotWriteRollsBackSteamFacetUpsertAndPreservesPreviousFacets() = runTest {
        database.canonicalGameDao().insert(canonical())
        database.canonicalFacetDao().upsertGenres(
            listOf(
                CanonicalGameGenreCrossRef(CANONICAL_ID.value, "steam:1"),
                CanonicalGameGenreCrossRef(CANONICAL_ID.value, "gog:adventure"),
            ),
        )
        val repository = RoomGameFacetRepository(
            database = database,
            facetDao = database.canonicalFacetDao(),
            snapshotDao = ThrowingSnapshotDao(),
        )

        try {
            repository.upsertSteamGenresAndSnapshot(
                CANONICAL_ID,
                listOf(MetadataFacet(2, "Strategy")),
                snapshot(metadata(listOf(MetadataFacet(2, "Strategy")))),
            )
            throw AssertionError("Expected snapshot failure")
        } catch (_: ExpectedWriteFailure) {
        }

        assertEquals(
            listOf("gog:adventure", "steam:1"),
            database.canonicalFacetDao().getGenres(CANONICAL_ID.value).map { it.genreKey }.sorted(),
        )
    }

    @Test
    fun malformedIdsAreIgnoredAndLabelsAreSanitized() = runTest {
        database.canonicalGameDao().insert(canonical())
        val metadata = metadata(
            listOf(
                MetadataFacet(null, "Missing"),
                MetadataFacet(0, "Zero"),
                MetadataFacet(1, "<script>bad()</script> Action"),
            ),
        )
        val repository = RoomGameFacetRepository(
            database = database,
            facetDao = database.canonicalFacetDao(),
            snapshotDao = database.gameDetailSnapshotDao(),
        )

        repository.upsertSteamGenresAndSnapshot(CANONICAL_ID, metadata.genres, snapshot(metadata))

        assertEquals(
            listOf("steam:1"),
            database.canonicalFacetDao().getGenres(CANONICAL_ID.value).map { it.genreKey },
        )
        assertEquals(
            listOf(GameFacet("steam:1", "Action")),
            repository.resolveGenres(setOf("steam:1"), listOf(snapshot(metadata))),
        )
    }

    @Test
    fun unrelatedSnapshotCannotSupplySteamGenrePresentationLabels() {
        val unrelatedMetadata = metadata(listOf(MetadataFacet(1, "Cross-store label")))
        val unrelatedSnapshot = snapshot(unrelatedMetadata).copy(sourceRevision = "other_provider_v1")
        val repository = RoomGameFacetRepository(
            database = database,
            facetDao = database.canonicalFacetDao(),
            snapshotDao = database.gameDetailSnapshotDao(),
        )

        assertEquals(
            listOf(GameFacet("steam:1", "Action")),
            repository.resolveGenres(setOf("steam:1"), listOf(unrelatedSnapshot)),
        )
    }

    private fun canonical() = CanonicalGameEntity(
        canonicalId = CANONICAL_ID.value,
        steamAppId = 42,
        displayName = "Canonical",
        matchTitleKey = "canonical",
        primaryMetadataSource = GameSource.STEAM,
        appType = CanonicalAppType.GAME,
        releaseYear = null,
        developerKey = "",
        classificationState = ClassificationState.CLASSIFIED,
        steamReviewCount = null,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun metadata(genres: List<MetadataFacet>) = CanonicalGameMetadata(
        title = "Canonical",
        shortDescription = null,
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
        genres = genres,
        features = emptyList(),
        achievementCount = null,
        dlcCount = null,
        fetchedAtEpochMs = 10L,
    )

    private fun snapshot(metadata: CanonicalGameMetadata) = GameDetailSnapshotEntity(
        canonicalId = CANONICAL_ID.value,
        locale = "en-US",
        country = "US",
        payloadJson = JSON.encodeToString(metadata),
        provenanceJson = JSON.encodeToString(
            GameMetadataProvenance(MetadataProvider.STEAM_APPDETAILS, setOf(MetadataField.TITLE, MetadataField.GENRES)),
        ),
        fetchedAt = metadata.fetchedAtEpochMs,
        sourceRevision = "steam_appdetails_v1",
    )

    private class ThrowingSnapshotDao : GameDetailSnapshotDao {
        override suspend fun get(canonicalId: String, locale: String, country: String): GameDetailSnapshotEntity? = null
        override fun observe(canonicalId: String, locale: String, country: String): Flow<GameDetailSnapshotEntity?> = emptyFlow()
        override suspend fun getByCanonicalId(canonicalId: String): List<GameDetailSnapshotEntity> = emptyList()
        override suspend fun upsert(entity: GameDetailSnapshotEntity) {
            throw ExpectedWriteFailure()
        }
        override suspend fun delete(canonicalId: String, locale: String, country: String) = Unit
        override suspend fun deleteByCanonicalId(canonicalId: String) = Unit
    }

    private class ExpectedWriteFailure : RuntimeException()

    private companion object {
        val CANONICAL_ID: CanonicalGameId = CanonicalGameId.parse("11111111-1111-1111-1111-111111111111")
        val JSON = Json { encodeDefaults = true }
    }
}
