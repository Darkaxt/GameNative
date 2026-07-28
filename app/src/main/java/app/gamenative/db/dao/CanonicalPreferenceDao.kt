package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.gamenative.data.canonical.CanonicalGamePreferenceEntity

@Dao
interface CanonicalPreferenceDao {
    @Query("SELECT * FROM canonical_game_preference WHERE canonical_id = :canonicalId")
    suspend fun get(canonicalId: String): CanonicalGamePreferenceEntity?

    @Upsert
    suspend fun upsert(entity: CanonicalGamePreferenceEntity)

    @Query("DELETE FROM canonical_game_preference WHERE canonical_id = :canonicalId")
    suspend fun delete(canonicalId: String)
}
