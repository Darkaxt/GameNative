package app.gamenative.data.canonical

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import app.gamenative.data.GameSource

@Entity(
    tableName = "canonical_game_preference",
    foreignKeys = [
        ForeignKey(
            entity = CanonicalGameEntity::class,
            parentColumns = ["canonical_id"],
            childColumns = ["canonical_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CanonicalGamePreferenceEntity(
    @PrimaryKey
    @ColumnInfo("canonical_id")
    val canonicalId: String,
    @ColumnInfo("preferred_account_scope")
    val preferredAccountScope: String?,
    @ColumnInfo("preferred_source")
    val preferredSource: GameSource?,
    @ColumnInfo("preferred_stable_source_id")
    val preferredStableSourceId: String?,
    @ColumnInfo("title_override")
    val titleOverride: String?,
    @ColumnInfo("artwork_override_json")
    val artworkOverrideJson: String?,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
) {
    fun preferredCopyKeyOrNull(): OwnedCopyKey? {
        val accountScope = preferredAccountScope ?: return null
        val source = preferredSource ?: return null
        val stableSourceId = preferredStableSourceId ?: return null
        return runCatching {
            OwnedCopyKey(
                accountScope = AccountScope.parse(accountScope),
                source = source,
                stableSourceId = stableSourceId,
            )
        }.getOrNull()
    }
}
