package app.gamenative.library.discovery

import androidx.room.withTransaction
import app.gamenative.data.canonical.CanonicalGameFeatureCrossRef
import app.gamenative.data.canonical.CanonicalGameEntity
import app.gamenative.data.canonical.CanonicalGameGenreCrossRef
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.CanonicalGameTagCrossRef
import app.gamenative.data.canonical.ClassificationState
import app.gamenative.data.canonical.GameDetailSnapshotEntity
import app.gamenative.db.PluviaDatabase
import app.gamenative.db.dao.CanonicalFacetDao
import app.gamenative.db.dao.GameDetailSnapshotDao
import app.gamenative.library.canonical.catalog.SteamPublicPicsFacets
import app.gamenative.library.metadata.CanonicalGameMetadata
import app.gamenative.library.metadata.MetadataFacet
import app.gamenative.library.metadata.MetadataLocale
import app.gamenative.library.metadata.MetadataLocaleProvider
import app.gamenative.library.metadata.sanitizeSteamText
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

interface GameFacetRepository {
    suspend fun upsertSteamGenresAndSnapshot(
        canonicalId: CanonicalGameId,
        genres: List<MetadataFacet>,
        snapshot: GameDetailSnapshotEntity,
    )

    suspend fun upsertValidatedSteamMetadata(
        canonicalId: CanonicalGameId,
        trustedSteamAppId: Int,
        genres: List<MetadataFacet>,
        features: List<MetadataFacet>,
        snapshot: GameDetailSnapshotEntity,
    ): Boolean {
        upsertSteamGenresAndSnapshot(canonicalId, genres, snapshot)
        return true
    }

    suspend fun upsertSteamPicsFacets(
        canonicalId: CanonicalGameId,
        trustedSteamAppId: Int,
        facets: SteamPublicPicsFacets,
    ): Boolean = false

    fun observeSteamTags(): Flow<List<SteamTagFacet>> = emptyFlow()

    suspend fun refreshSteamTags(): SteamTagDictionaryRefreshResult =
        SteamTagDictionaryRefreshResult.Failed

    fun resolveGenres(
        keys: Set<String>,
        snapshots: List<GameDetailSnapshotEntity>,
    ): List<GameFacet>
}

@Singleton
class RoomGameFacetRepository private constructor(
    private val database: PluviaDatabase,
    private val facetDao: CanonicalFacetDao,
    private val snapshotDao: GameDetailSnapshotDao,
    private val tagDictionary: TagDictionaryDependencies,
) : GameFacetRepository {
    private val tagDictionaryProvider get() = tagDictionary.provider
    private val localeProvider get() = tagDictionary.localeProvider

    @Inject
    constructor(
        database: PluviaDatabase,
        facetDao: CanonicalFacetDao,
        snapshotDao: GameDetailSnapshotDao,
        tagDictionaryProvider: SteamTagDictionaryProvider,
        localeProvider: MetadataLocaleProvider,
    ) : this(
        database,
        facetDao,
        snapshotDao,
        TagDictionaryDependencies(tagDictionaryProvider, localeProvider),
    )

    internal constructor(
        database: PluviaDatabase,
        facetDao: CanonicalFacetDao,
        snapshotDao: GameDetailSnapshotDao,
    ) : this(
        database,
        facetDao,
        snapshotDao,
        TagDictionaryDependencies(
            provider = null,
            localeProvider = MetadataLocaleProvider { MetadataLocale("en-US", "US") },
        ),
    )
    override suspend fun upsertSteamGenresAndSnapshot(
        canonicalId: CanonicalGameId,
        genres: List<MetadataFacet>,
        snapshot: GameDetailSnapshotEntity,
    ) {
        require(snapshot.canonicalId == canonicalId.value)
        val sanitized = sanitizeGenres(genres)
        database.withTransaction {
            if (sanitized.isNotEmpty()) {
                facetDao.upsertGenres(
                    sanitized.map { facet ->
                        CanonicalGameGenreCrossRef(canonicalId.value, steamFacetKey(requireNotNull(facet.id)))
                    },
                )
            }
            snapshotDao.upsert(snapshot)
        }
    }

    override suspend fun upsertValidatedSteamMetadata(
        canonicalId: CanonicalGameId,
        trustedSteamAppId: Int,
        genres: List<MetadataFacet>,
        features: List<MetadataFacet>,
        snapshot: GameDetailSnapshotEntity,
    ): Boolean {
        require(trustedSteamAppId > 0)
        require(snapshot.canonicalId == canonicalId.value)
        val sanitizedGenres = sanitizeGenres(genres)
        val sanitizedFeatures = sanitizeFeatures(features)
        return database.withTransaction {
            val canonical = database.canonicalGameDao().get(canonicalId.value)
            if (canonical?.steamAppId != trustedSteamAppId) {
                return@withTransaction false
            }
            facetDao.upsertGenres(
                sanitizedGenres.map { facet ->
                    CanonicalGameGenreCrossRef(canonicalId.value, steamFacetKey(requireNotNull(facet.id)))
                },
            )
            facetDao.upsertFeatures(
                sanitizedFeatures.map { facet ->
                    CanonicalGameFeatureCrossRef(canonicalId.value, steamFacetKey(requireNotNull(facet.id)))
                },
            )
            snapshotDao.upsert(snapshot)
            updateClassification(canonical)
            true
        }
    }

    override suspend fun upsertSteamPicsFacets(
        canonicalId: CanonicalGameId,
        trustedSteamAppId: Int,
        facets: SteamPublicPicsFacets,
    ): Boolean {
        require(trustedSteamAppId > 0)
        return database.withTransaction {
            val canonical = database.canonicalGameDao().get(canonicalId.value)
            if (canonical?.steamAppId != trustedSteamAppId) {
                return@withTransaction false
            }
            facetDao.upsertGenres(
                facets.genreIds.sorted().map { genreId ->
                    CanonicalGameGenreCrossRef(canonicalId.value, steamFacetKey(genreId))
                },
            )
            facetDao.upsertFeatures(
                facets.categoryIds.sorted().map { categoryId ->
                    CanonicalGameFeatureCrossRef(canonicalId.value, steamFacetKey(categoryId))
                },
            )
            facetDao.upsertTags(
                facets.storeTagIds.sorted().map { tagId ->
                    CanonicalGameTagCrossRef(canonicalId.value, tagId)
                },
            )
            updateClassification(canonical)
            true
        }
    }

    override fun observeSteamTags(): Flow<List<SteamTagFacet>> {
        val locale = localeProvider.current().normalizedLocale
        return facetDao.observeSteamTags(locale).map { entities ->
            entities.mapNotNull { entity ->
                val tagId = entity.tagId.takeIf { it > 0 } ?: return@mapNotNull null
                val label = sanitizeSteamText(entity.label)
                    ?.take(MAX_TAG_LABEL_LENGTH)
                    ?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                SteamTagFacet(tagId, label)
            }.distinctBy(SteamTagFacet::tagId)
                .sortedWith(
                    compareBy<SteamTagFacet> { it.label.lowercase() }
                        .thenBy(SteamTagFacet::label)
                        .thenBy(SteamTagFacet::tagId),
                )
        }
    }

    override suspend fun refreshSteamTags(): SteamTagDictionaryRefreshResult =
        tagDictionaryProvider?.refresh(localeProvider.current())
            ?: SteamTagDictionaryRefreshResult.Failed

    override fun resolveGenres(
        keys: Set<String>,
        snapshots: List<GameDetailSnapshotEntity>,
    ): List<GameFacet> {
        val idsByKey = keys.mapNotNull { key ->
            parseSteamGenreId(key)?.let { id -> key to id }
        }.toMap()
        if (idsByKey.isEmpty()) return emptyList()

        val labelsById = linkedMapOf<Int, String>()
        snapshots.asSequence()
            .sortedWith(
                compareByDescending<GameDetailSnapshotEntity> { it.fetchedAt }
                    .thenBy { it.locale }
                    .thenBy { it.country },
            )
            .mapNotNull(::decodeMetadata)
            .flatMap { metadata -> sanitizeGenres(metadata.genres).asSequence() }
            .forEach { facet -> labelsById.putIfAbsent(requireNotNull(facet.id), facet.label) }

        return idsByKey.map { (key, id) ->
            GameFacet(
                key = key,
                label = labelsById[id] ?: STEAM_GENRE_LABELS[id] ?: "Genre $id",
            )
        }.sortedWith(compareBy<GameFacet> { it.label.lowercase() }.thenBy(GameFacet::label).thenBy(GameFacet::key))
    }

    private suspend fun updateClassification(canonical: CanonicalGameEntity) {
        val hasGenres = facetDao.getGenres(canonical.canonicalId).isNotEmpty()
        val hasTagsOrFeatures = facetDao.getTags(canonical.canonicalId).isNotEmpty() ||
            facetDao.getFeatures(canonical.canonicalId).isNotEmpty()
        val classification = when {
            hasGenres && hasTagsOrFeatures -> ClassificationState.CLASSIFIED
            hasGenres || hasTagsOrFeatures -> ClassificationState.PARTIALLY_CLASSIFIED
            else -> ClassificationState.UNCLASSIFIED
        }
        if (canonical.classificationState != classification) {
            database.canonicalGameDao().update(canonical.copy(classificationState = classification))
        }
    }

    private fun decodeMetadata(snapshot: GameDetailSnapshotEntity): CanonicalGameMetadata? {
        if (snapshot.sourceRevision != STEAM_APPDETAILS_SOURCE_REVISION) return null
        return try {
            JSON.decodeFromString<CanonicalGameMetadata>(snapshot.payloadJson)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private companion object {
        const val STEAM_APPDETAILS_SOURCE_REVISION = "steam_appdetails_v1"
        val JSON = Json { ignoreUnknownKeys = true }
        val STEAM_GENRE_LABELS = mapOf(
            1 to "Action",
            2 to "Strategy",
            3 to "RPG",
            4 to "Casual",
            5 to "Racing",
            18 to "Sports",
            23 to "Indie",
            25 to "Adventure",
            28 to "Simulation",
            29 to "Massively Multiplayer",
            37 to "Free to Play",
            51 to "Animation & Modeling",
            52 to "Audio Production",
            53 to "Design & Illustration",
            54 to "Education",
            55 to "Photo Editing",
            56 to "Software Training",
            57 to "Utilities",
            58 to "Video Production",
            59 to "Web Publishing",
            60 to "Game Development",
            70 to "Early Access",
        )
    }
}

private data class TagDictionaryDependencies(
    val provider: SteamTagDictionaryProvider?,
    val localeProvider: MetadataLocaleProvider,
)

private fun sanitizeGenres(genres: List<MetadataFacet>): List<MetadataFacet> = genres.mapNotNull { facet ->
    val id = facet.id?.takeIf { it > 0 } ?: return@mapNotNull null
    val label = sanitizeSteamText(facet.label)?.take(MAX_GENRE_LABEL_LENGTH)?.takeIf(String::isNotBlank)
        ?: return@mapNotNull null
    MetadataFacet(id, label)
}.distinctBy { facet -> facet.id }

private fun sanitizeFeatures(features: List<MetadataFacet>): List<MetadataFacet> = features.mapNotNull { facet ->
    val id = facet.id?.takeIf { it > 0 } ?: return@mapNotNull null
    val label = sanitizeSteamText(facet.label)?.take(MAX_FEATURE_LABEL_LENGTH)?.takeIf(String::isNotBlank)
        ?: return@mapNotNull null
    MetadataFacet(id, label)
}.distinctBy { facet -> facet.id }

private fun steamFacetKey(id: Int): String = "steam:$id"

private fun parseSteamGenreId(key: String): Int? = STEAM_GENRE_KEY.matchEntire(key)
    ?.groupValues
    ?.get(1)
    ?.toIntOrNull()
    ?.takeIf { it > 0 }

private const val MAX_GENRE_LABEL_LENGTH = 80
private const val MAX_FEATURE_LABEL_LENGTH = 80
private const val MAX_TAG_LABEL_LENGTH = 80
private val STEAM_GENRE_KEY = Regex("steam:([1-9][0-9]*)")
