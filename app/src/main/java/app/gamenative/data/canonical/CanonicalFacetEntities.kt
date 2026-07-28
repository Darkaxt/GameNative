package app.gamenative.data.canonical

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "canonical_game_genre",
    primaryKeys = ["canonical_id", "genre_key"],
    foreignKeys = [
        ForeignKey(
            entity = CanonicalGameEntity::class,
            parentColumns = ["canonical_id"],
            childColumns = ["canonical_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["genre_key", "canonical_id"])],
)
data class CanonicalGameGenreCrossRef(
    @ColumnInfo("canonical_id")
    val canonicalId: String,
    @ColumnInfo("genre_key")
    val genreKey: String,
)

@Entity(
    tableName = "canonical_game_tag",
    primaryKeys = ["canonical_id", "tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = CanonicalGameEntity::class,
            parentColumns = ["canonical_id"],
            childColumns = ["canonical_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tag_id", "canonical_id"])],
)
data class CanonicalGameTagCrossRef(
    @ColumnInfo("canonical_id")
    val canonicalId: String,
    @ColumnInfo("tag_id")
    val tagId: Int,
)

@Entity(
    tableName = "canonical_game_feature",
    primaryKeys = ["canonical_id", "feature_key"],
    foreignKeys = [
        ForeignKey(
            entity = CanonicalGameEntity::class,
            parentColumns = ["canonical_id"],
            childColumns = ["canonical_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["feature_key", "canonical_id"])],
)
data class CanonicalGameFeatureCrossRef(
    @ColumnInfo("canonical_id")
    val canonicalId: String,
    @ColumnInfo("feature_key")
    val featureKey: String,
)

@Entity(
    tableName = "steam_tag_dictionary",
    primaryKeys = ["tag_id", "locale"],
)
data class SteamTagDictionaryEntity(
    @ColumnInfo("tag_id")
    val tagId: Int,
    @ColumnInfo("locale")
    val locale: String,
    @ColumnInfo("label")
    val label: String,
    @ColumnInfo("fetched_at")
    val fetchedAt: Long,
)
