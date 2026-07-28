package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.gamenative.data.canonical.CanonicalGameFeatureCrossRef
import app.gamenative.data.canonical.CanonicalGameGenreCrossRef
import app.gamenative.data.canonical.CanonicalGameTagCrossRef
import app.gamenative.data.canonical.SteamTagDictionaryEntity

@Dao
interface CanonicalFacetDao {
    @Query("SELECT * FROM canonical_game_genre WHERE canonical_id = :canonicalId ORDER BY genre_key")
    suspend fun getGenres(canonicalId: String): List<CanonicalGameGenreCrossRef>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsertGenres(entities: List<CanonicalGameGenreCrossRef>)

    @Query("DELETE FROM canonical_game_genre WHERE canonical_id = :canonicalId")
    suspend fun deleteGenres(canonicalId: String)

    @Query("SELECT * FROM canonical_game_tag WHERE canonical_id = :canonicalId ORDER BY tag_id")
    suspend fun getTags(canonicalId: String): List<CanonicalGameTagCrossRef>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsertTags(entities: List<CanonicalGameTagCrossRef>)

    @Query("DELETE FROM canonical_game_tag WHERE canonical_id = :canonicalId")
    suspend fun deleteTags(canonicalId: String)

    @Query("SELECT * FROM canonical_game_feature WHERE canonical_id = :canonicalId ORDER BY feature_key")
    suspend fun getFeatures(canonicalId: String): List<CanonicalGameFeatureCrossRef>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsertFeatures(entities: List<CanonicalGameFeatureCrossRef>)

    @Query("DELETE FROM canonical_game_feature WHERE canonical_id = :canonicalId")
    suspend fun deleteFeatures(canonicalId: String)

    @Query("SELECT * FROM steam_tag_dictionary WHERE tag_id = :tagId AND locale = :locale")
    suspend fun getSteamTag(tagId: Int, locale: String): SteamTagDictionaryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSteamTag(entity: SteamTagDictionaryEntity)

    @Query("DELETE FROM steam_tag_dictionary WHERE tag_id = :tagId AND locale = :locale")
    suspend fun deleteSteamTag(tagId: Int, locale: String)
}
