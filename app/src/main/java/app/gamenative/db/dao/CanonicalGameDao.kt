package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.gamenative.data.canonical.CanonicalGameEntity

@Dao
interface CanonicalGameDao {
    @Query("SELECT * FROM canonical_game WHERE canonical_id = :canonicalId")
    suspend fun get(canonicalId: String): CanonicalGameEntity?

    @Query("SELECT * FROM canonical_game WHERE steam_app_id = :steamAppId")
    suspend fun findBySteamAppId(steamAppId: Int): CanonicalGameEntity?

    @Query("SELECT * FROM canonical_game WHERE match_title_key = :titleKey ORDER BY created_at, canonical_id")
    suspend fun findByTitleKey(titleKey: String): List<CanonicalGameEntity>

    @Query("SELECT * FROM canonical_game ORDER BY canonical_id")
    suspend fun getAll(): List<CanonicalGameEntity>

    @Query(
        """
        UPDATE canonical_game
        SET steam_review_count = :totalReviews
        WHERE canonical_id = :canonicalId
          AND steam_app_id = :steamAppId
          AND steam_review_count IS NULL
          AND :totalReviews >= 0
        """,
    )
    suspend fun updateSteamReviewCountIfMissing(
        canonicalId: String,
        steamAppId: Int,
        totalReviews: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: CanonicalGameEntity)

    @Update
    suspend fun update(entity: CanonicalGameEntity)

    @Query("DELETE FROM canonical_game WHERE canonical_id = :canonicalId")
    suspend fun delete(canonicalId: String)
}
