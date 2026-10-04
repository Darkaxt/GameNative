package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.RejectedSteamCandidateEntity
import app.gamenative.data.canonical.SteamCatalogResolutionAttemptEntity
import app.gamenative.data.canonical.SteamCatalogResolutionStatus

@Dao
interface SteamCatalogResolutionDao {
    @Query("SELECT * FROM steam_catalog_resolution_attempt WHERE canonical_id = :canonicalId")
    suspend fun getAttempt(canonicalId: String): SteamCatalogResolutionAttemptEntity?

    @Upsert
    suspend fun upsertAttempt(entity: SteamCatalogResolutionAttemptEntity)

    @Query(
        """
        UPDATE steam_catalog_resolution_attempt SET status = :status
        WHERE canonical_id = :canonicalId AND evidence_hash = :evidenceHash
          AND resolver_version = :resolverVersion AND attempted_at = :attemptedAt AND status = 'PENDING'
        """,
    )
    suspend fun completeAttempt(
        canonicalId: String,
        evidenceHash: String,
        resolverVersion: Int,
        attemptedAt: Long,
        status: SteamCatalogResolutionStatus,
    ): Int

    @Query(
        """
        SELECT steam_app_id FROM rejected_steam_candidate
        WHERE account_scope = :accountScope AND source = :source AND stable_source_id = :stableSourceId
        ORDER BY steam_app_id
        """,
    )
    suspend fun getRejectedSteamAppIds(accountScope: String, source: GameSource, stableSourceId: String): List<Int>

    @Upsert
    suspend fun upsertRejection(entity: RejectedSteamCandidateEntity)

    @Query(
        """
        DELETE FROM rejected_steam_candidate
        WHERE account_scope = :accountScope AND source = :source AND stable_source_id = :stableSourceId
        """,
    )
    suspend fun deleteRejections(accountScope: String, source: GameSource, stableSourceId: String)

    @Query("DELETE FROM steam_catalog_resolution_attempt WHERE canonical_id = :canonicalId")
    suspend fun deleteAttempt(canonicalId: String)
}
