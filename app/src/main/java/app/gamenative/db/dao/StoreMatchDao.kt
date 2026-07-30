package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.StoreMatchEntity

@Dao
interface StoreMatchDao {
    @Query(
        """
        SELECT * FROM store_match
        WHERE account_scope = :accountScope
          AND source = :source
          AND stable_source_id = :stableSourceId
        """,
    )
    suspend fun get(
        accountScope: String,
        source: GameSource,
        stableSourceId: String,
    ): StoreMatchEntity?

    @Query(
        """
        SELECT * FROM store_match
        WHERE account_scope = :accountScope
          AND source = :source
          AND stable_source_id = :stableSourceId
          AND is_present = 1
        """,
    )
    suspend fun getPresent(
        accountScope: String,
        source: GameSource,
        stableSourceId: String,
    ): StoreMatchEntity?

    @Query(
        """
        SELECT * FROM store_match
        WHERE canonical_id = :canonicalId
        ORDER BY account_scope, source, stable_source_id
        """,
    )
    suspend fun getByCanonicalId(canonicalId: String): List<StoreMatchEntity>

    @Query("SELECT * FROM store_match ORDER BY account_scope, source, stable_source_id")
    suspend fun getAll(): List<StoreMatchEntity>

    @Upsert
    suspend fun upsert(entity: StoreMatchEntity)

    @Query(
        """
        UPDATE store_match
        SET is_present = 0
        WHERE account_scope = :accountScope
          AND source = :source
          AND is_present = 1
        """,
    )
    suspend fun markAbsentForCompleteSnapshot(
        accountScope: String,
        source: GameSource,
    )

    @Query(
        """
        UPDATE store_match
        SET is_present = 0
        WHERE source = :source
          AND is_present = 1
        """,
    )
    suspend fun markAbsentForSource(source: GameSource)

    @Query(
        """
        UPDATE store_match
        SET is_present = 0
        WHERE source = :source
          AND account_scope != :accountScope
          AND is_present = 1
        """,
    )
    suspend fun markOtherAccountsAbsent(accountScope: String, source: GameSource)

    @Query(
        """
        UPDATE store_match
        SET canonical_id = :toCanonicalId
        WHERE canonical_id = :fromCanonicalId
        """,
    )
    suspend fun repoint(fromCanonicalId: String, toCanonicalId: String)

    @Query("SELECT COUNT(*) FROM store_match WHERE canonical_id = :canonicalId AND is_present = 1")
    suspend fun countPresentReferences(canonicalId: String): Int

    @Query("SELECT COUNT(*) FROM store_match WHERE canonical_id = :canonicalId")
    suspend fun countAllReferences(canonicalId: String): Int
}
