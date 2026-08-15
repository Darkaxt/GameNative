package app.gamenative.data.canonical

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import app.gamenative.data.GameSource

@Entity(
    tableName = "store_match",
    primaryKeys = ["account_scope", "source", "stable_source_id"],
    foreignKeys = [
        ForeignKey(
            entity = CanonicalGameEntity::class,
            parentColumns = ["canonical_id"],
            childColumns = ["canonical_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["canonical_id"]),
        Index(value = ["candidate_steam_app_id"]),
        Index(value = ["source", "stable_source_id"]),
    ],
)
data class StoreMatchEntity(
    @ColumnInfo("account_scope")
    val accountScope: String,
    @ColumnInfo("source")
    val source: GameSource,
    @ColumnInfo("stable_source_id")
    val stableSourceId: String,
    @ColumnInfo("canonical_id")
    val canonicalId: String,
    @ColumnInfo("candidate_steam_app_id")
    val candidateSteamAppId: Int?,
    @ColumnInfo("match_method")
    val matchMethod: MatchMethod,
    @ColumnInfo("confidence")
    val confidence: MatchConfidence,
    @ColumnInfo("decision_source")
    val decisionSource: MatchDecisionSource,
    @ColumnInfo("resolver_version")
    val resolverVersion: Int,
    @ColumnInfo("matched_at")
    val matchedAt: Long,
    @ColumnInfo("is_present")
    val isPresent: Boolean,
    @ColumnInfo("evidence_display_name")
    val evidenceDisplayName: String,
    @ColumnInfo("evidence_title_key")
    val evidenceTitleKey: String,
    @ColumnInfo("evidence_developer_key")
    val evidenceDeveloperKey: String,
    @ColumnInfo("evidence_release_year")
    val evidenceReleaseYear: Int?,
    @ColumnInfo("evidence_app_type")
    val evidenceAppType: CanonicalAppType,
) {
    fun ownedCopyKeyOrNull(): OwnedCopyKey? = OwnedCopyKey.fromPersistedOrNull(
        accountScope = accountScope,
        source = source,
        stableSourceId = stableSourceId,
    )
}
