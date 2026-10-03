package app.gamenative.data.canonical

import androidx.room.ColumnInfo
import androidx.room.Entity
import app.gamenative.data.GameSource

@Entity(
    tableName = "rejected_steam_candidate",
    primaryKeys = ["account_scope", "source", "stable_source_id", "steam_app_id"],
)
data class RejectedSteamCandidateEntity(
    @ColumnInfo(name = "account_scope") val accountScope: String,
    @ColumnInfo(name = "source") val source: GameSource,
    @ColumnInfo(name = "stable_source_id") val stableSourceId: String,
    @ColumnInfo(name = "steam_app_id") val steamAppId: Int,
    @ColumnInfo(name = "rejected_at") val rejectedAt: Long,
)
