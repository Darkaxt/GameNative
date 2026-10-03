package app.gamenative.data.canonical

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SteamCatalogResolutionStatus {
    PENDING,
    AUTO_ACCEPTED,
    REVIEW_REQUIRED,
    UNMATCHED,
    FAILED,
}

@Entity(tableName = "steam_catalog_resolution_attempt")
data class SteamCatalogResolutionAttemptEntity(
    @PrimaryKey @ColumnInfo(name = "canonical_id") val canonicalId: String,
    @ColumnInfo(name = "evidence_hash") val evidenceHash: String,
    @ColumnInfo(name = "resolver_version") val resolverVersion: Int,
    @ColumnInfo(name = "status") val status: SteamCatalogResolutionStatus,
    @ColumnInfo(name = "attempted_at") val attemptedAt: Long,
)
