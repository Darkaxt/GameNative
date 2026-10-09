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

    suspend fun setFamilyPreferredCopy(
        card: CanonicalLibraryCard,
        key: OwnedCopyKey,
        nowEpochMs: Long,
    ) = db.withTransaction {
        validateCapturedMembers(card)
        val selectedMember = requireNotNull(card.copyCanonicalIds[key]) { "Copy is not a captured family member" }
        card.memberSteamAppIds.keys.forEach { member ->
            val existing = db.canonicalPreferenceDao().get(member.value)
            val selected = key.takeIf { member == selectedMember }
            if (existing != null || selected != null) {
                db.canonicalPreferenceDao().upsert(preference(member, existing, nowEpochMs).copy(
                    preferredAccountScope = selected?.accountScope?.value,
                    preferredSource = selected?.source,
                    preferredStableSourceId = selected?.stableSourceId,
                ))
            }
        }
    }

    suspend fun clearFamilyPreferredCopy(
        card: CanonicalLibraryCard,
        nowEpochMs: Long,
    ) = db.withTransaction {
        validateCapturedMembers(card)
        card.memberSteamAppIds.keys.forEach { member -> clearPreferredCopy(member, nowEpochMs) }
    }

    suspend fun setFamilyGroupingSuppressed(
        card: CanonicalLibraryCard,
        key: OwnedCopyKey,
        suppressed: Boolean,
        nowEpochMs: Long,
    ) = db.withTransaction {
        require(if (suppressed) card.isPresentationFamily else card.familyGroupingSuppressed) {
            "Family separation intent changed"
        }
        validateCapturedMembers(card)
        val member = requireNotNull(card.copyCanonicalIds[key]) { "Copy is not a captured member" }
        val existing = db.canonicalPreferenceDao().get(member.value)
        db.canonicalPreferenceDao().upsert(preference(member, existing, nowEpochMs).copy(
            familyGroupingSuppressed = suppressed,
        ))
    }

    private suspend fun validateCapturedMembers(card: CanonicalLibraryCard) {
        val members = card.memberSteamAppIds
        require(members.isNotEmpty() && card.memberPreferences.keys == members.keys) { "Missing captured member preferences" }
        require(if (card.isPresentationFamily) card.hasValidFamilyBindings() else {
            card.key == CanonicalCardKey.Grouped(card.canonicalId) &&
                members == mapOf(card.canonicalId to card.steamAppId) &&
                card.copyCanonicalIds.keys == card.copies.map { it.key }.toSet() &&
                card.copyCanonicalIds.values.all { it == card.canonicalId }
        }) { "Invalid captured family bindings" }
        for ((member, steamAppId) in members) {
            val game = db.canonicalGameDao().get(member.value)
            require(game != null && game.steamAppId == steamAppId) { "Member catalog identity changed" }
            require(db.canonicalPreferenceDao().get(member.value) == card.memberPreferences[member]) {
                "Member preference changed"
            }
            val captured = card.copies.filter { card.copyCanonicalIds[it.key] == member }
            val present = db.storeMatchDao().getByCanonicalId(member.value).filter { it.isPresent }
            require(present.map { it.ownedCopyKeyOrNull() }.toSet() == captured.map { it.key }.toSet()) {
                "Member copy membership changed"
            }
            for (copy in captured) {
                val match = present.singleOrNull { it.ownedCopyKeyOrNull() == copy.key }
                require(match != null && match.matchMethod == copy.matchMethod && match.confidence == copy.confidence &&
                    match.decisionSource == copy.decisionSource && match.candidateSteamAppId == copy.decisionCandidateSteamAppId &&
                    match.resolverVersion == copy.decisionResolverVersion && match.matchedAt == copy.decisionRevision) {
                    "Member match decision changed"
                }
            }
        }
    }

    private fun preference(
        member: CanonicalGameId,
        existing: CanonicalGamePreferenceEntity?,
        nowEpochMs: Long,
    ): CanonicalGamePreferenceEntity = (existing ?: CanonicalGamePreferenceEntity(
        canonicalId = member.value,
        preferredAccountScope = null,
        preferredSource = null,
        preferredStableSourceId = null,
        titleOverride = null,
        artworkOverrideJson = null,
        updatedAt = nowEpochMs,
    )).copy(updatedAt = nowEpochMs)

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
