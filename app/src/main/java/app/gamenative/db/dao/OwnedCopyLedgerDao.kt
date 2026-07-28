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
        "SELECT EXISTS(SELECT 1 FROM owned_copy_presence " +
            "WHERE account_scope = :accountScope AND source = :source AND stable_source_id = :stableSourceId)",
    )
    suspend fun isPresent(accountScope: String, source: GameSource, stableSourceId: String): Boolean

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
        return CompletedOwnedCopySnapshot(
            completedAt = header.completedAt,
            stableSourceIds = getCompletedStableSourceIds(accountScope, source),
        )
    }

    @Transaction
    suspend fun replaceCompletedSnapshot(
        accountScope: String,
        source: GameSource,
        stableSourceIds: Collection<String>,
        completedAt: Long,
        resolvedSourceIds: Map<String, String> = emptyMap(),
    ) {
        AccountScope.parse(accountScope)
        require(stableSourceIds.all { it.isNotBlank() && it == it.trim() })
        val normalizedIds = stableSourceIds.distinct().sorted()
        require(resolvedSourceIds.keys.all(normalizedIds::contains))
        require(resolvedSourceIds.values.all { it.isNotBlank() && it == it.trim() })

        deletePresence(accountScope, source)
        upsertCompletedHeader(
            OwnedCopySyncEntity(
                accountScope = accountScope,
                source = source,
                completedAt = completedAt,
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
    }
}
