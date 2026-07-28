package app.gamenative.data.canonical

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "game_detail_snapshot",
    primaryKeys = ["canonical_id", "locale", "country"],
    foreignKeys = [
        ForeignKey(
            entity = CanonicalGameEntity::class,
            parentColumns = ["canonical_id"],
            childColumns = ["canonical_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["canonical_id"])],
)
data class GameDetailSnapshotEntity(
    @ColumnInfo("canonical_id")
    val canonicalId: String,
    @ColumnInfo("locale")
    val locale: String,
    @ColumnInfo("country")
    val country: String,
    @ColumnInfo("payload_json")
    val payloadJson: String,
    @ColumnInfo("provenance_json")
    val provenanceJson: String,
    @ColumnInfo("fetched_at")
    val fetchedAt: Long,
    @ColumnInfo("source_revision")
    val sourceRevision: String,
)
