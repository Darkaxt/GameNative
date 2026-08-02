package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.gamenative.data.canonical.GameDetailSnapshotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDetailSnapshotDao {
    @Query(
        """
        SELECT * FROM game_detail_snapshot
        WHERE canonical_id = :canonicalId
          AND locale = :locale
          AND country = :country
        """,
    )
    suspend fun get(
        canonicalId: String,
        locale: String,
        country: String,
    ): GameDetailSnapshotEntity?

    @Query(
        """
        SELECT * FROM game_detail_snapshot
        WHERE canonical_id = :canonicalId
          AND locale = :locale
          AND country = :country
        """,
    )
    fun observe(
        canonicalId: String,
        locale: String,
        country: String,
    ): Flow<GameDetailSnapshotEntity?>

    @Query(
        """
        SELECT * FROM game_detail_snapshot
        WHERE canonical_id = :canonicalId
        ORDER BY locale, country
        """,
    )
    suspend fun getByCanonicalId(canonicalId: String): List<GameDetailSnapshotEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: GameDetailSnapshotEntity)

    @Query(
        """
        DELETE FROM game_detail_snapshot
        WHERE canonical_id = :canonicalId
          AND locale = :locale
          AND country = :country
        """,
    )
    suspend fun delete(
        canonicalId: String,
        locale: String,
        country: String,
    )

    @Query("DELETE FROM game_detail_snapshot WHERE canonical_id = :canonicalId")
    suspend fun deleteByCanonicalId(canonicalId: String)
}
