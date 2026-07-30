package app.gamenative.library.canonical

import androidx.room.withTransaction
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.CanonicalGamePreferenceEntity
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.db.PluviaDatabase
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreferredCopyRepository @Inject constructor(
    private val db: PluviaDatabase,
) {
    suspend fun setPreferredCopy(
        canonicalId: CanonicalGameId,
        key: OwnedCopyKey,
        nowEpochMs: Long,
    ) = db.withTransaction {
        val match = db.storeMatchDao().getPresent(
            accountScope = key.accountScope.value,
            source = key.source,
            stableSourceId = key.stableSourceId,
        )
        require(match?.canonicalId == canonicalId.value) {
            "Preferred copy is not a present member of the canonical game"
        }

        val existing = db.canonicalPreferenceDao().get(canonicalId.value)
        db.canonicalPreferenceDao().upsert(
            (existing ?: CanonicalGamePreferenceEntity(
                canonicalId = canonicalId.value,
                preferredAccountScope = null,
                preferredSource = null,
                preferredStableSourceId = null,
                titleOverride = null,
                artworkOverrideJson = null,
                updatedAt = nowEpochMs,
            )).copy(
                preferredAccountScope = key.accountScope.value,
                preferredSource = key.source,
                preferredStableSourceId = key.stableSourceId,
                updatedAt = nowEpochMs,
            ),
        )
    }

    suspend fun clearPreferredCopy(
        canonicalId: CanonicalGameId,
        nowEpochMs: Long,
    ) = db.withTransaction {
        val existing = db.canonicalPreferenceDao().get(canonicalId.value)
            ?: return@withTransaction
        db.canonicalPreferenceDao().upsert(
            existing.copy(
                preferredAccountScope = null,
                preferredSource = null,
                preferredStableSourceId = null,
                updatedAt = nowEpochMs,
            ),
        )
    }
}
