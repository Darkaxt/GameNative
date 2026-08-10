package app.gamenative.library.metadata

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.CanonicalNormalization
import app.gamenative.data.canonical.GameDetailSnapshotEntity
import app.gamenative.db.dao.CanonicalGameDao
import app.gamenative.db.dao.GameDetailSnapshotDao
import app.gamenative.library.canonical.runtime.CanonicalIoDispatcher
import app.gamenative.library.discovery.GameFacetRepository
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

sealed interface GameDetailState {
    data object Loading : GameDetailState

    data class Content(
        val metadata: CanonicalGameMetadata,
        val provider: MetadataProvider = MetadataProvider.STEAM_APPDETAILS,
        val stale: Boolean,
        val refreshFailed: Boolean = false,
    ) : GameDetailState

    data class Unavailable(
        val cached: CanonicalGameMetadata?,
    ) : GameDetailState
}

sealed interface MetadataRefreshResult {
    data object Refreshed : MetadataRefreshResult
    data object NoTrustedSteamId : MetadataRefreshResult
    data object Failed : MetadataRefreshResult
}

sealed interface MetadataPersistenceResult {
    data object Persisted : MetadataPersistenceResult
    data object StaleIdentity : MetadataPersistenceResult
    data object Failed : MetadataPersistenceResult
}

interface GameMetadataRepository {
    fun observe(canonicalId: CanonicalGameId): Flow<GameDetailState>
    suspend fun refresh(canonicalId: CanonicalGameId): MetadataRefreshResult

    suspend fun persistValidatedSteamRecord(
        canonicalId: CanonicalGameId,
        trustedSteamAppId: Int,
        locale: MetadataLocale,
        record: SteamCatalogRecord,
    ): MetadataPersistenceResult = MetadataPersistenceResult.Failed

    companion object {
        const val CACHE_MAX_AGE_MS: Long = 7L * 24L * 60L * 60L * 1_000L
    }
}

@Singleton
class RoomGameMetadataRepository @Inject constructor(
    private val canonicalGameDao: CanonicalGameDao,
    private val snapshotDao: GameDetailSnapshotDao,
    private val gameFacetRepository: GameFacetRepository,
    private val provider: SteamCatalogDataSource,
    private val localeProvider: MetadataLocaleProvider,
    private val clock: MetadataClock,
    @CanonicalIoDispatcher private val dispatcher: CoroutineDispatcher,
) : GameMetadataRepository {
    override fun observe(canonicalId: CanonicalGameId): Flow<GameDetailState> = flow {
        val locale = localeProvider.current()
        val initialEntity = snapshotDao.get(
            canonicalId = canonicalId.value,
            locale = locale.normalizedLocale,
            country = locale.normalizedCountry,
        )
        val initial = initialEntity?.decodeSnapshot()
        val initialStale = initialEntity?.isStale(clock.nowEpochMs()) ?: true
        if (initial != null) {
            emit(
                GameDetailState.Content(
                    metadata = initial.metadata,
                    provider = initial.provider,
                    stale = initialStale,
                ),
            )
        } else {
            emit(GameDetailState.Loading)
        }

        var refreshFailed = false
        if (initial == null || initialStale) {
            when (refresh(canonicalId)) {
                MetadataRefreshResult.Refreshed -> Unit
                MetadataRefreshResult.NoTrustedSteamId,
                MetadataRefreshResult.Failed,
                -> {
                    refreshFailed = true
                    if (initial != null) {
                        emit(
                            GameDetailState.Content(
                                metadata = initial.metadata,
                                provider = initial.provider,
                                stale = true,
                                refreshFailed = true,
                            ),
                        )
                    } else {
                        emit(GameDetailState.Unavailable(cached = null))
                    }
                }
            }
        }

        snapshotDao.observe(
            canonicalId = canonicalId.value,
            locale = locale.normalizedLocale,
            country = locale.normalizedCountry,
        ).collect { entity ->
            val snapshot = entity?.decodeSnapshot() ?: return@collect
            val isNewerThanInitial = initialEntity == null || entity.fetchedAt > initialEntity.fetchedAt
            emit(
                GameDetailState.Content(
                    metadata = snapshot.metadata,
                    provider = snapshot.provider,
                    stale = entity.isStale(clock.nowEpochMs()),
                    refreshFailed = refreshFailed && !isNewerThanInitial,
                ),
            )
        }
    }.distinctUntilChanged().flowOn(dispatcher)

    override suspend fun refresh(canonicalId: CanonicalGameId): MetadataRefreshResult {
        val trustedSteamId = canonicalGameDao.get(canonicalId.value)
            ?.steamAppId
            ?.takeIf { it > 0 }
            ?: return MetadataRefreshResult.NoTrustedSteamId
        val locale = localeProvider.current()
        return try {
            val fetched = provider.fetch(trustedSteamId, locale)
                ?: return MetadataRefreshResult.Failed
            val metadata = fetched
                .copy(fetchedAtEpochMs = clock.nowEpochMs())
                .sanitizedForPersistence()
            if (metadata.title.isBlank()) return MetadataRefreshResult.Failed
            val persisted = gameFacetRepository.upsertValidatedSteamMetadata(
                canonicalId = canonicalId,
                trustedSteamAppId = trustedSteamId,
                genres = metadata.genres,
                features = metadata.features,
                snapshot = GameDetailSnapshotEntity(
                    canonicalId = canonicalId.value,
                    locale = locale.normalizedLocale,
                    country = locale.normalizedCountry,
                    payloadJson = JSON.encodeToString(metadata),
                    provenanceJson = JSON.encodeToString(metadata.provenance()),
                    fetchedAt = metadata.fetchedAtEpochMs,
                    sourceRevision = SOURCE_REVISION,
                ),
            )
            if (!persisted) return MetadataRefreshResult.NoTrustedSteamId
            MetadataRefreshResult.Refreshed
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            MetadataRefreshResult.Failed
        }
    }

    override suspend fun persistValidatedSteamRecord(
        canonicalId: CanonicalGameId,
        trustedSteamAppId: Int,
        locale: MetadataLocale,
        record: SteamCatalogRecord,
    ): MetadataPersistenceResult {
        if (trustedSteamAppId <= 0 || record.steamAppId != trustedSteamAppId) {
            return MetadataPersistenceResult.StaleIdentity
        }
        return try {
            val canonical = canonicalGameDao.get(canonicalId.value)
                ?.takeIf { it.steamAppId == trustedSteamAppId }
                ?: return MetadataPersistenceResult.StaleIdentity
            val metadata = record.metadata
                .copy(fetchedAtEpochMs = clock.nowEpochMs())
                .sanitizedForPersistence()
            if (metadata.title.isBlank()) return MetadataPersistenceResult.Failed
            val developerKey = metadata.developers.firstOrNull()
                ?.let(CanonicalNormalization::developerKey)
                .orEmpty()
            val presentation = canonical.copy(
                displayName = metadata.title,
                matchTitleKey = CanonicalNormalization.titleKey(metadata.title),
                primaryMetadataSource = GameSource.STEAM,
                appType = record.appType.takeUnless { it == CanonicalAppType.UNKNOWN }
                    ?: canonical.appType,
                releaseYear = record.releaseYear ?: canonical.releaseYear,
                developerKey = developerKey.ifBlank { canonical.developerKey },
                updatedAt = metadata.fetchedAtEpochMs,
            )
            val persisted = gameFacetRepository.upsertValidatedSteamPresentation(
                canonicalId = canonicalId,
                trustedSteamAppId = trustedSteamAppId,
                presentation = presentation,
                genres = metadata.genres,
                features = metadata.features,
                snapshot = GameDetailSnapshotEntity(
                    canonicalId = canonicalId.value,
                    locale = locale.normalizedLocale,
                    country = locale.normalizedCountry,
                    payloadJson = JSON.encodeToString(metadata),
                    provenanceJson = JSON.encodeToString(metadata.provenance()),
                    fetchedAt = metadata.fetchedAtEpochMs,
                    sourceRevision = SOURCE_REVISION,
                ),
            )
            if (persisted) {
                MetadataPersistenceResult.Persisted
            } else {
                MetadataPersistenceResult.StaleIdentity
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            MetadataPersistenceResult.Failed
        }
    }

    private fun GameDetailSnapshotEntity.decodeSnapshot(): DecodedMetadataSnapshot? {
        return try {
            val provenance = JSON.decodeFromString<GameMetadataProvenance>(provenanceJson)
            if (!provenance.matchesSourceRevision(sourceRevision)) return null
            val metadata = JSON.decodeFromString<CanonicalGameMetadata>(payloadJson)
                .sanitizedForPersistence()
                .takeIf { it.title.isNotBlank() }
                ?: return null
            DecodedMetadataSnapshot(
                metadata = metadata,
                provider = provenance.provider,
            )
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun GameMetadataProvenance.matchesSourceRevision(sourceRevision: String): Boolean =
        when (sourceRevision) {
            SOURCE_REVISION -> provider == MetadataProvider.STEAM_APPDETAILS
            EPIC_CMS_SOURCE_REVISION ->
                provider == MetadataProvider.EPIC_CMS &&
                    source == GameSource.EPIC.name &&
                    !stableSourceId.isNullOrBlank() &&
                    !namespace.isNullOrBlank() &&
                    !catalogId.isNullOrBlank() &&
                    !slug.isNullOrBlank() &&
                    !offerId.isNullOrBlank()
            else -> false
        }

    private fun GameDetailSnapshotEntity.isStale(nowEpochMs: Long): Boolean =
        nowEpochMs - fetchedAt >= GameMetadataRepository.CACHE_MAX_AGE_MS

    private fun CanonicalGameMetadata.provenance(): GameMetadataProvenance {
        val fields = buildSet {
            add(MetadataField.TITLE)
            if (shortDescription != null) add(MetadataField.SHORT_DESCRIPTION)
            if (about != null) add(MetadataField.ABOUT)
            if (headerImageUrl != null) add(MetadataField.HEADER_IMAGE)
            if (screenshots.isNotEmpty()) add(MetadataField.SCREENSHOTS)
            if (movies.isNotEmpty()) add(MetadataField.MOVIES)
            if (developers.isNotEmpty()) add(MetadataField.DEVELOPERS)
            if (publishers.isNotEmpty()) add(MetadataField.PUBLISHERS)
            if (releaseDate != null) add(MetadataField.RELEASE_DATE)
            if (platforms.isNotEmpty()) add(MetadataField.PLATFORMS)
            if (languages.isNotEmpty()) add(MetadataField.LANGUAGES)
            if (requirements != null) add(MetadataField.REQUIREMENTS)
            if (genres.isNotEmpty()) add(MetadataField.GENRES)
            if (features.isNotEmpty()) add(MetadataField.FEATURES)
            if (achievementCount != null) add(MetadataField.ACHIEVEMENT_COUNT)
            if (dlcCount != null) add(MetadataField.DLC_COUNT)
        }
        return GameMetadataProvenance(
            provider = MetadataProvider.STEAM_APPDETAILS,
            fields = fields,
        )
    }

    private data class DecodedMetadataSnapshot(
        val metadata: CanonicalGameMetadata,
        val provider: MetadataProvider,
    )

    private companion object {
        const val SOURCE_REVISION = "steam_appdetails_v2"
        const val EPIC_CMS_SOURCE_REVISION = "epic_cms_v1"
        val JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

@Singleton
class SystemMetadataLocaleProvider @Inject constructor() : MetadataLocaleProvider {
    override fun current(): MetadataLocale {
        val locale = Locale.getDefault()
        val languageTag = locale.toLanguageTag().takeIf { it != "und" } ?: "en-US"
        val country = locale.country.takeIf { it.length == 2 } ?: "US"
        return MetadataLocale(languageTag, country)
    }
}

@Singleton
class SystemMetadataClock @Inject constructor() : MetadataClock {
    override fun nowEpochMs(): Long = System.currentTimeMillis()
}
