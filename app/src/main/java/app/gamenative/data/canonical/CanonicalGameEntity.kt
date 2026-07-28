package app.gamenative.data.canonical

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.gamenative.data.GameSource

@Entity(
    tableName = "canonical_game",
    indices = [
        Index(value = ["steam_app_id"], unique = true),
        Index(value = ["match_title_key"]),
    ],
)
data class CanonicalGameEntity(
    @PrimaryKey
    @ColumnInfo("canonical_id")
    val canonicalId: String,
    @ColumnInfo("steam_app_id")
    val steamAppId: Int?,
    @ColumnInfo("display_name")
    val displayName: String,
    @ColumnInfo("match_title_key")
    val matchTitleKey: String,
    @ColumnInfo("primary_metadata_source")
    val primaryMetadataSource: GameSource,
    @ColumnInfo("app_type")
    val appType: CanonicalAppType,
    @ColumnInfo("release_year")
    val releaseYear: Int?,
    @ColumnInfo("developer_key")
    val developerKey: String,
    @ColumnInfo("classification_state")
    val classificationState: ClassificationState,
    @ColumnInfo("steam_review_count")
    val steamReviewCount: Long?,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
