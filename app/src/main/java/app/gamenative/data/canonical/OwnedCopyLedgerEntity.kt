package app.gamenative.data.canonical

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import app.gamenative.data.GameSource

@Entity(
    tableName = "owned_copy_sync",
    primaryKeys = ["account_scope", "source"],
)
data class OwnedCopySyncEntity(
    @ColumnInfo("account_scope")
    val accountScope: String,
    @ColumnInfo("source")
    val source: GameSource,
    @ColumnInfo("completed_at")
    val completedAt: Long,
)

@Entity(
    tableName = "owned_copy_presence",
    primaryKeys = ["account_scope", "source", "stable_source_id"],
    foreignKeys = [
        ForeignKey(
            entity = OwnedCopySyncEntity::class,
            parentColumns = ["account_scope", "source"],
            childColumns = ["account_scope", "source"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["account_scope", "source"]),
    ],
)
data class OwnedCopyPresenceEntity(
    @ColumnInfo("account_scope")
    val accountScope: String,
    @ColumnInfo("source")
    val source: GameSource,
    @ColumnInfo("stable_source_id")
    val stableSourceId: String,
    @ColumnInfo("resolved_source_id")
    val resolvedSourceId: String? = null,
)
