package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.OwnedCopyPresenceEntity
import app.gamenative.data.canonical.OwnedCopySyncEntity
import kotlinx.coroutines.flow.Flow

data class CompletedOwnedCopySnapshot(
    val completedAt: Long,
    val lifecycleGeneration: Long,
    val stableSourceIds: List<String>,
)

@Dao
interface OwnedCopyLedgerDao {
    @Query(
        "SELECT * FROM owned_copy_sync " +
            "WHERE account_scope = :accountScope AND source = :source LIMIT 1",
    )
    suspend fun getCompletedHeader(accountScope: String, source: GameSource): OwnedCopySyncEntity?

    @Query(
        "SELECT * FROM owned_copy_sync " +
            "WHERE account_scope = :accountScope AND source = :source " +
            "AND lifecycle_generation = :lifecycleGeneration LIMIT 1",
    )
    suspend fun getCompletedHeaderForLifecycle(
        accountScope: String,
        source: GameSource,
        lifecycleGeneration: Long,
    ): OwnedCopySyncEntity?

    @Query(
        "SELECT stable_source_id FROM owned_copy_presence " +
            "WHERE account_scope = :accountScope AND source = :source ORDER BY stable_source_id",
    )
    suspend fun getCompletedStableSourceIds(accountScope: String, source: GameSource): List<String>

    @Query(
        "SELECT * FROM owned_copy_presence " +
            "WHERE account_scope = :accountScope AND source = :source AND stable_source_id = :stableSourceId LIMIT 1",
    )
    suspend fun getPresence(
        accountScope: String,
        source: GameSource,
        stableSourceId: String,
    ): OwnedCopyPresenceEntity?

    @Query(
        """
        SELECT presence.*
        FROM owned_copy_presence AS presence
        INNER JOIN owned_copy_sync AS sync
            ON sync.account_scope = presence.account_scope
            AND sync.source = presence.source
        WHERE presence.account_scope = :accountScope
            AND presence.source = :source
            AND presence.stable_source_id = :stableSourceId
            AND sync.lifecycle_generation = :lifecycleGeneration
        LIMIT 1
        """,
    )
    suspend fun getPresenceForLifecycle(
        accountScope: String,
        source: GameSource,
        stableSourceId: String,
        lifecycleGeneration: Long,
    ): OwnedCopyPresenceEntity?

    @Query(
        "SELECT EXISTS(SELECT 1 FROM owned_copy_presence " +
            "WHERE account_scope = :accountScope AND source = :source AND stable_source_id = :stableSourceId)",
    )
    suspend fun isPresent(accountScope: String, source: GameSource, stableSourceId: String): Boolean

    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM owned_copy_presence AS presence
            INNER JOIN owned_copy_sync AS sync
                ON sync.account_scope = presence.account_scope
                AND sync.source = presence.source
            WHERE presence.account_scope = :accountScope
                AND presence.source = :source
                AND presence.stable_source_id = :stableSourceId
                AND sync.lifecycle_generation = :lifecycleGeneration
        )
        """,
    )
    suspend fun isPresentForLifecycle(
        accountScope: String,
        source: GameSource,
        stableSourceId: String,
        lifecycleGeneration: Long,
    ): Boolean

    @Query("SELECT * FROM owned_copy_sync WHERE source = :source ORDER BY account_scope")
    fun observeSourceHeaders(source: GameSource): Flow<List<OwnedCopySyncEntity>>

    @Upsert
    suspend fun upsertCompletedHeader(header: OwnedCopySyncEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPresenceRows(rows: List<OwnedCopyPresenceEntity>)

    @Query("DELETE FROM owned_copy_presence WHERE account_scope = :accountScope AND source = :source")
    suspend fun deletePresence(accountScope: String, source: GameSource)

    @Transaction
    suspend fun getCompletedSnapshot(accountScope: String, source: GameSource): CompletedOwnedCopySnapshot? {
        val header = getCompletedHeader(accountScope, source) ?: return null
        return header.toSnapshot(getCompletedStableSourceIds(accountScope, source))
    }

    @Transaction
    suspend fun getCompletedSnapshotForLifecycle(
        accountScope: String,
        source: GameSource,
        lifecycleGeneration: Long,
    ): CompletedOwnedCopySnapshot? {
        val header = getCompletedHeaderForLifecycle(
            accountScope = accountScope,
            source = source,
            lifecycleGeneration = lifecycleGeneration,
        ) ?: return null
        return header.toSnapshot(getCompletedStableSourceIds(accountScope, source))
    }

    @Transaction
    suspend fun replaceCompletedSnapshot(
        accountScope: String,
        source: GameSource,
        stableSourceIds: Collection<String>,
        completedAt: Long,
        lifecycleGeneration: Long,
        resolvedSourceIds: Map<String, String> = emptyMap(),
    ): Boolean {
        AccountScope.parse(accountScope)
        require(lifecycleGeneration >= 0)
        require(stableSourceIds.all { it.isNotBlank() && it == it.trim() })
        val normalizedIds = stableSourceIds.distinct().sorted()
        require(resolvedSourceIds.keys.all(normalizedIds::contains))
        require(resolvedSourceIds.values.all { it.isNotBlank() && it == it.trim() })

        val existingHeader = getCompletedHeader(accountScope, source)
        if (existingHeader != null && existingHeader.lifecycleGeneration > lifecycleGeneration) {
            return false
        }

        deletePresence(accountScope, source)
        upsertCompletedHeader(
            OwnedCopySyncEntity(
                accountScope = accountScope,
                source = source,
                completedAt = completedAt,
                lifecycleGeneration = lifecycleGeneration,
            ),
        )
        insertPresenceRows(
            normalizedIds.map { stableSourceId ->
                OwnedCopyPresenceEntity(
                    accountScope = accountScope,
                    source = source,
                    stableSourceId = stableSourceId,
                    resolvedSourceId = resolvedSourceIds[stableSourceId],
                )
            },
        )
        return true
    }

    private fun OwnedCopySyncEntity.toSnapshot(
        stableSourceIds: List<String>,
    ): CompletedOwnedCopySnapshot = CompletedOwnedCopySnapshot(
        completedAt = completedAt,
        lifecycleGeneration = lifecycleGeneration,
        stableSourceIds = stableSourceIds,
    )
}
