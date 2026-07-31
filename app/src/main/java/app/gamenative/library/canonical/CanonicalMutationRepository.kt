package app.gamenative.library.canonical

import androidx.room.withTransaction
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.CanonicalGameEntity
import app.gamenative.data.canonical.CanonicalGameFeatureCrossRef
import app.gamenative.data.canonical.CanonicalGameGenreCrossRef
import app.gamenative.data.canonical.CanonicalGamePreferenceEntity
import app.gamenative.data.canonical.CanonicalGameTagCrossRef
import app.gamenative.data.canonical.CanonicalIdGenerator
import app.gamenative.data.canonical.CanonicalNormalization
import app.gamenative.data.canonical.ClassificationState
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.data.canonical.StoreMatchEntity
import app.gamenative.db.PluviaDatabase
import app.gamenative.library.canonical.source.OwnedCopyProjection
import javax.inject.Inject
import javax.inject.Singleton

enum class CanonicalGuardedMutationResult {
    APPLIED,
    EXPECTED_STATE_CHANGED,
}

interface CanonicalMutationRepository {
    suspend fun confirmSteamMatch(
        key: OwnedCopyKey,
        steamAppId: Int,
        nowEpochMs: Long,
    ): String

    suspend fun rejectSteamCandidate(
        key: OwnedCopyKey,
        steamAppId: Int,
        nowEpochMs: Long,
    )

    suspend fun resetDecision(
        key: OwnedCopyKey,
        nowEpochMs: Long,
    )

    suspend fun guardedResetDecision(
        key: OwnedCopyKey,
        expectedCanonicalId: String,
        expectedMatchMethod: MatchMethod,
        nowEpochMs: Long,
    ): CanonicalGuardedMutationResult

    suspend fun unmergeCopy(
        key: OwnedCopyKey,
        current: OwnedCopyProjection,
        nowEpochMs: Long,
    ): String

    suspend fun guardedUnmergeCopy(
        key: OwnedCopyKey,
        current: OwnedCopyProjection,
        expectedCanonicalId: String,
        nowEpochMs: Long,
    ): CanonicalGuardedMutationResult

    suspend fun markCopyAbsent(key: OwnedCopyKey)
}

@Singleton
class RoomCanonicalMutationRepository @Inject constructor(
    private val db: PluviaDatabase,
    private val idGenerator: CanonicalIdGenerator,
) : CanonicalMutationRepository {
    private val canonicalGameDao = db.canonicalGameDao()
    private val storeMatchDao = db.storeMatchDao()
    private val preferenceDao = db.canonicalPreferenceDao()
    private val facetDao = db.canonicalFacetDao()
    private val snapshotDao = db.gameDetailSnapshotDao()

    override suspend fun confirmSteamMatch(
        key: OwnedCopyKey,
        steamAppId: Int,
        nowEpochMs: Long,
    ): String = db.withTransaction {
        requireMutableMatch(key)
        require(steamAppId > 0) { "Confirmed Steam AppID must be positive" }
        val selectedMatch = requireMatch(key)
        val currentCanonical = requireCanonical(selectedMatch.canonicalId)
        val requestedTarget = canonicalGameDao.findBySteamAppId(steamAppId)
        val siblingRejectedRequestedIdentity = storeMatchDao
            .getByCanonicalId(currentCanonical.canonicalId)
            .any { match ->
                !match.hasKey(key) &&
                    match.decisionSource == MatchDecisionSource.USER &&
                    match.confidence == MatchConfidence.REJECTED &&
                    match.candidateSteamAppId == steamAppId
            }

        when {
            currentCanonical.steamAppId == steamAppId -> {
                storeMatchDao.upsert(
                    selectedMatch.asManualDecision(
                        canonicalId = currentCanonical.canonicalId,
                        steamAppId = steamAppId,
                        confidence = MatchConfidence.VERIFIED,
                        nowEpochMs = nowEpochMs,
                    ),
                )
                currentCanonical.canonicalId
            }

            currentCanonical.steamAppId != null -> {
                val relationshipCount = storeMatchDao.countAllReferences(currentCanonical.canonicalId)
                if (requestedTarget == null && relationshipCount == 1) {
                    assignSteamIdentity(
                        canonical = currentCanonical,
                        selectedMatch = selectedMatch,
                        steamAppId = steamAppId,
                        nowEpochMs = nowEpochMs,
                    )
                } else {
                    detachToSteamTarget(
                        key = key,
                        currentCanonical = currentCanonical,
                        selectedMatch = selectedMatch,
                        requestedTarget = requestedTarget,
                        steamAppId = steamAppId,
                        nowEpochMs = nowEpochMs,
                    )
                }
            }

            siblingRejectedRequestedIdentity -> detachToSteamTarget(
                key = key,
                currentCanonical = currentCanonical,
                selectedMatch = selectedMatch,
                requestedTarget = requestedTarget,
                steamAppId = steamAppId,
                nowEpochMs = nowEpochMs,
            )

            requestedTarget == null -> assignSteamIdentity(
                canonical = currentCanonical,
                selectedMatch = selectedMatch,
                steamAppId = steamAppId,
                nowEpochMs = nowEpochMs,
            )

            else -> {
                val survivor = mergeCanonicals(
                    first = currentCanonical,
                    second = requestedTarget,
                    confirmedSteamAppId = steamAppId,
                    nowEpochMs = nowEpochMs,
                )
                storeMatchDao.upsert(
                    selectedMatch.asManualDecision(
                        canonicalId = survivor.canonicalId,
                        steamAppId = steamAppId,
                        confidence = MatchConfidence.VERIFIED,
                        nowEpochMs = nowEpochMs,
                    ),
                )
                survivor.canonicalId
            }
        }
    }

    override suspend fun rejectSteamCandidate(
        key: OwnedCopyKey,
        steamAppId: Int,
        nowEpochMs: Long,
    ) {
        db.withTransaction {
            requireMutableMatch(key)
            require(steamAppId > 0) { "Rejected Steam AppID must be positive" }
            val selectedMatch = requireMatch(key)
            val currentCanonical = requireCanonical(selectedMatch.canonicalId)
            val targetCanonicalId = if (currentCanonical.steamAppId == steamAppId) {
                rejectCurrentSteamIdentity(
                    key = key,
                    currentCanonical = currentCanonical,
                    selectedMatch = selectedMatch,
                    nowEpochMs = nowEpochMs,
                )
            } else {
                currentCanonical.canonicalId
            }
            storeMatchDao.upsert(
                selectedMatch.asManualDecision(
                    canonicalId = targetCanonicalId,
                    steamAppId = steamAppId,
                    confidence = MatchConfidence.REJECTED,
                    nowEpochMs = nowEpochMs,
                ),
            )
        }
    }

    override suspend fun resetDecision(
        key: OwnedCopyKey,
        nowEpochMs: Long,
    ) {
        db.withTransaction {
            requireMutableMatch(key)
            resetDecision(requireMatch(key), nowEpochMs)
        }
    }

    override suspend fun guardedResetDecision(
        key: OwnedCopyKey,
        expectedCanonicalId: String,
        expectedMatchMethod: MatchMethod,
        nowEpochMs: Long,
    ): CanonicalGuardedMutationResult = db.withTransaction {
        requireMutableMatch(key)
        val match = storeMatchDao.get(
            accountScope = key.accountScope.value,
            source = key.source,
            stableSourceId = key.stableSourceId,
        )
        val remainsExpectedIndependentRejection = match != null &&
            match.canonicalId == expectedCanonicalId &&
            match.isPresent &&
            match.confidence == MatchConfidence.REJECTED &&
            match.decisionSource == MatchDecisionSource.USER &&
            match.matchMethod == expectedMatchMethod &&
            storeMatchDao.countPresentReferences(expectedCanonicalId) == 1
        if (!remainsExpectedIndependentRejection) {
            return@withTransaction CanonicalGuardedMutationResult.EXPECTED_STATE_CHANGED
        }
        resetDecision(match, nowEpochMs)
        CanonicalGuardedMutationResult.APPLIED
    }

    override suspend fun unmergeCopy(
        key: OwnedCopyKey,
        current: OwnedCopyProjection,
        nowEpochMs: Long,
    ): String {
        val snapshot = immutableUnmergeSnapshot(key, current)
        return db.withTransaction {
            requireMutableMatch(key)
            val selectedMatch = requireMatch(key)
            val originalCanonical = requireCanonical(selectedMatch.canonicalId)
            unmergeCopy(snapshot, selectedMatch, originalCanonical, nowEpochMs)
        }
    }

    override suspend fun guardedUnmergeCopy(
        key: OwnedCopyKey,
        current: OwnedCopyProjection,
        expectedCanonicalId: String,
        nowEpochMs: Long,
    ): CanonicalGuardedMutationResult {
        val snapshot = immutableUnmergeSnapshot(key, current)
        return db.withTransaction {
            requireMutableMatch(key)
            val selectedMatch = storeMatchDao.get(
                accountScope = key.accountScope.value,
                source = key.source,
                stableSourceId = key.stableSourceId,
            )
            val expectedMatches = storeMatchDao.getByCanonicalId(expectedCanonicalId)
            val remainsExpectedGroupedCopy = selectedMatch != null &&
                selectedMatch.canonicalId == expectedCanonicalId &&
                selectedMatch.isPresent &&
                selectedMatch.confidence.isCollapsibleForGuard() &&
                selectedMatch.source != GameSource.STEAM &&
                expectedMatches.count { match ->
                    match.isPresent && match.confidence.isCollapsibleForGuard()
                } >= 2
            if (!remainsExpectedGroupedCopy) {
                return@withTransaction CanonicalGuardedMutationResult.EXPECTED_STATE_CHANGED
            }
            val originalCanonical = canonicalGameDao.get(expectedCanonicalId)
                ?: return@withTransaction CanonicalGuardedMutationResult.EXPECTED_STATE_CHANGED
            unmergeCopy(snapshot, selectedMatch, originalCanonical, nowEpochMs)
            CanonicalGuardedMutationResult.APPLIED
        }
    }

    private fun immutableUnmergeSnapshot(
        key: OwnedCopyKey,
        current: OwnedCopyProjection,
    ): OwnedCopyProjection {
        val snapshot = current.copy(
            genreKeys = current.genreKeys.toSet(),
            tagIds = current.tagIds.toSet(),
            featureKeys = current.featureKeys.toSet(),
        )
        require(snapshot.key == key) { "Unmerge snapshot does not match its owned copy key" }
        return snapshot
    }

    private suspend fun unmergeCopy(
        snapshot: OwnedCopyProjection,
        selectedMatch: StoreMatchEntity,
        originalCanonical: CanonicalGameEntity,
        nowEpochMs: Long,
    ): String {
        val canonical = createStandaloneCanonical(snapshot, nowEpochMs)
        storeMatchDao.upsert(
            selectedMatch.asManualDecision(
                canonicalId = canonical.canonicalId,
                steamAppId = originalCanonical.steamAppId,
                confidence = MatchConfidence.REJECTED,
                nowEpochMs = nowEpochMs,
            ).copy(
                evidenceDisplayName = CanonicalNormalization.displayName(snapshot.displayName),
                evidenceTitleKey = CanonicalNormalization.titleKey(snapshot.displayName),
                evidenceDeveloperKey = CanonicalNormalization.developerKey(snapshot.developer),
                evidenceReleaseYear = snapshot.releaseYear,
                evidenceAppType = snapshot.appType,
            ),
        )
        insertFacets(canonical.canonicalId, snapshot)
        clearPreferredCopy(originalCanonical.canonicalId, snapshot.key, nowEpochMs)
        return canonical.canonicalId
    }

    private suspend fun resetDecision(match: StoreMatchEntity, nowEpochMs: Long) {
        storeMatchDao.upsert(
            match.copy(
                candidateSteamAppId = null,
                matchMethod = MatchMethod.UNMATCHED,
                confidence = MatchConfidence.UNMATCHED,
                decisionSource = MatchDecisionSource.AUTOMATIC,
                resolverVersion = 0,
                matchedAt = nowEpochMs,
            ),
        )
    }

    private fun MatchConfidence.isCollapsibleForGuard(): Boolean =
        this == MatchConfidence.VERIFIED || this == MatchConfidence.HIGH

    override suspend fun markCopyAbsent(key: OwnedCopyKey) {
        db.withTransaction {
            val match = requireMatch(key)
            if (match.isPresent) {
                storeMatchDao.upsert(match.copy(isPresent = false))
            }
        }
    }

    private suspend fun assignSteamIdentity(
        canonical: CanonicalGameEntity,
        selectedMatch: StoreMatchEntity,
        steamAppId: Int,
        nowEpochMs: Long,
    ): String {
        canonicalGameDao.update(
            canonical.copy(
                steamAppId = steamAppId,
                updatedAt = nowEpochMs,
            ),
        )
        storeMatchDao.upsert(
            selectedMatch.asManualDecision(
                canonicalId = canonical.canonicalId,
                steamAppId = steamAppId,
                confidence = MatchConfidence.VERIFIED,
                nowEpochMs = nowEpochMs,
            ),
        )
        return canonical.canonicalId
    }

    private suspend fun detachToSteamTarget(
        key: OwnedCopyKey,
        currentCanonical: CanonicalGameEntity,
        selectedMatch: StoreMatchEntity,
        requestedTarget: CanonicalGameEntity?,
        steamAppId: Int,
        nowEpochMs: Long,
    ): String {
        val target = requestedTarget ?: createCanonicalFromMatch(
            match = selectedMatch,
            steamAppId = steamAppId,
            nowEpochMs = nowEpochMs,
        )
        storeMatchDao.upsert(
            selectedMatch.asManualDecision(
                canonicalId = target.canonicalId,
                steamAppId = steamAppId,
                confidence = MatchConfidence.VERIFIED,
                nowEpochMs = nowEpochMs,
            ),
        )
        clearPreferredCopy(currentCanonical.canonicalId, key, nowEpochMs)
        return target.canonicalId
    }

    private suspend fun rejectCurrentSteamIdentity(
        key: OwnedCopyKey,
        currentCanonical: CanonicalGameEntity,
        selectedMatch: StoreMatchEntity,
        nowEpochMs: Long,
    ): String {
        if (storeMatchDao.countAllReferences(currentCanonical.canonicalId) == 1) {
            clearSteamIdentity(currentCanonical, selectedMatch, nowEpochMs)
            return currentCanonical.canonicalId
        }

        val standalone = createCanonicalFromMatch(
            match = selectedMatch,
            steamAppId = null,
            nowEpochMs = nowEpochMs,
        )
        clearPreferredCopy(currentCanonical.canonicalId, key, nowEpochMs)
        return standalone.canonicalId
    }

    private suspend fun clearSteamIdentity(
        canonical: CanonicalGameEntity,
        selectedMatch: StoreMatchEntity,
        nowEpochMs: Long,
    ) {
        facetDao.deleteGenres(canonical.canonicalId)
        facetDao.deleteTags(canonical.canonicalId)
        facetDao.deleteFeatures(canonical.canonicalId)
        snapshotDao.deleteByCanonicalId(canonical.canonicalId)
        canonicalGameDao.update(
            canonical.copy(
                steamAppId = null,
                displayName = CanonicalNormalization.displayName(selectedMatch.evidenceDisplayName),
                matchTitleKey = selectedMatch.evidenceTitleKey.ifBlank {
                    CanonicalNormalization.titleKey(selectedMatch.evidenceDisplayName)
                },
                primaryMetadataSource = selectedMatch.source,
                appType = selectedMatch.evidenceAppType,
                releaseYear = selectedMatch.evidenceReleaseYear,
                developerKey = selectedMatch.evidenceDeveloperKey,
                classificationState = ClassificationState.UNCLASSIFIED,
                steamReviewCount = null,
                updatedAt = nowEpochMs,
            ),
        )
    }

    private suspend fun mergeCanonicals(
        first: CanonicalGameEntity,
        second: CanonicalGameEntity,
        confirmedSteamAppId: Int?,
        nowEpochMs: Long,
    ): CanonicalGameEntity {
        check(first.canonicalId != second.canonicalId) {
            "Cannot merge a canonical game into itself"
        }
        val nonNullSteamIds = listOfNotNull(first.steamAppId, second.steamAppId).distinct()
        check(nonNullSteamIds.size <= 1) {
            "Cannot merge canonical games with conflicting Steam identities"
        }
        if (confirmedSteamAppId != null) {
            check(confirmedSteamAppId > 0)
            check(nonNullSteamIds.all { steamAppId -> steamAppId == confirmedSteamAppId }) {
                "Confirmed Steam identity conflicts with canonical association"
            }
        }

        val survivor = selectCanonicalSurvivor(first, second, confirmedSteamAppId)
        val loser = if (survivor.canonicalId == first.canonicalId) second else first
        unionFacets(survivor.canonicalId, loser.canonicalId)
        mergeSnapshots(survivor.canonicalId, loser.canonicalId)
        mergePreferences(survivor.canonicalId, loser.canonicalId, nowEpochMs)
        storeMatchDao.repoint(loser.canonicalId, survivor.canonicalId)

        val classificationState = classificationState(survivor.canonicalId)
        val updatedSurvivor = survivor.copy(
            steamAppId = confirmedSteamAppId ?: survivor.steamAppId ?: loser.steamAppId,
            classificationState = classificationState,
            steamReviewCount = survivor.steamReviewCount ?: loser.steamReviewCount,
            updatedAt = nowEpochMs,
        )
        canonicalGameDao.update(updatedSurvivor)
        canonicalGameDao.delete(loser.canonicalId)
        return updatedSurvivor
    }

    private suspend fun unionFacets(
        survivorCanonicalId: String,
        loserCanonicalId: String,
    ) {
        facetDao.upsertGenres(
            facetDao.getGenres(loserCanonicalId).map { crossRef ->
                crossRef.copy(canonicalId = survivorCanonicalId)
            },
        )
        facetDao.upsertTags(
            facetDao.getTags(loserCanonicalId).map { crossRef ->
                crossRef.copy(canonicalId = survivorCanonicalId)
            },
        )
        facetDao.upsertFeatures(
            facetDao.getFeatures(loserCanonicalId).map { crossRef ->
                crossRef.copy(canonicalId = survivorCanonicalId)
            },
        )
    }

    private suspend fun mergeSnapshots(
        survivorCanonicalId: String,
        loserCanonicalId: String,
    ) {
        val survivorKeys = snapshotDao.getByCanonicalId(survivorCanonicalId)
            .map { snapshot -> snapshot.locale to snapshot.country }
            .toMutableSet()
        snapshotDao.getByCanonicalId(loserCanonicalId).forEach { snapshot ->
            if (survivorKeys.add(snapshot.locale to snapshot.country)) {
                snapshotDao.upsert(snapshot.copy(canonicalId = survivorCanonicalId))
            }
        }
    }

    private suspend fun mergePreferences(
        survivorCanonicalId: String,
        loserCanonicalId: String,
        nowEpochMs: Long,
    ) {
        val survivor = preferenceDao.get(survivorCanonicalId)
        val loser = preferenceDao.get(loserCanonicalId)
        if (survivor == null && loser == null) return

        val preferredCopy = survivor?.preferredCopyKeyOrNull() ?: loser?.preferredCopyKeyOrNull()
        val titleOverride = survivor?.titleOverride ?: loser?.titleOverride
        val artworkOverride = survivor?.artworkOverrideJson ?: loser?.artworkOverrideJson
        if (
            survivor == null &&
            preferredCopy == null &&
            titleOverride == null &&
            artworkOverride == null
        ) {
            return
        }

        val merged = CanonicalGamePreferenceEntity(
            canonicalId = survivorCanonicalId,
            preferredAccountScope = preferredCopy?.accountScope?.value,
            preferredSource = preferredCopy?.source,
            preferredStableSourceId = preferredCopy?.stableSourceId,
            titleOverride = titleOverride,
            artworkOverrideJson = artworkOverride,
            updatedAt = nowEpochMs,
        )
        val unchanged = survivor != null &&
            survivor.preferredAccountScope == merged.preferredAccountScope &&
            survivor.preferredSource == merged.preferredSource &&
            survivor.preferredStableSourceId == merged.preferredStableSourceId &&
            survivor.titleOverride == merged.titleOverride &&
            survivor.artworkOverrideJson == merged.artworkOverrideJson
        if (!unchanged) {
            preferenceDao.upsert(merged)
        }
    }

    private suspend fun clearPreferredCopy(
        canonicalId: String,
        key: OwnedCopyKey,
        nowEpochMs: Long,
    ) {
        val preference = preferenceDao.get(canonicalId) ?: return
        if (preference.preferredCopyKeyOrNull() == key) {
            preferenceDao.upsert(
                preference.copy(
                    preferredAccountScope = null,
                    preferredSource = null,
                    preferredStableSourceId = null,
                    updatedAt = nowEpochMs,
                ),
            )
        }
    }

    private suspend fun createCanonicalFromMatch(
        match: StoreMatchEntity,
        steamAppId: Int?,
        nowEpochMs: Long,
    ): CanonicalGameEntity {
        val canonical = CanonicalGameEntity(
            canonicalId = idGenerator.generate().value,
            steamAppId = steamAppId,
            displayName = CanonicalNormalization.displayName(match.evidenceDisplayName),
            matchTitleKey = match.evidenceTitleKey.ifBlank {
                CanonicalNormalization.titleKey(match.evidenceDisplayName)
            },
            primaryMetadataSource = match.source,
            appType = match.evidenceAppType,
            releaseYear = match.evidenceReleaseYear,
            developerKey = match.evidenceDeveloperKey,
            classificationState = ClassificationState.UNCLASSIFIED,
            steamReviewCount = null,
            createdAt = nowEpochMs,
            updatedAt = nowEpochMs,
        )
        canonicalGameDao.insert(canonical)
        return canonical
    }

    private suspend fun createStandaloneCanonical(
        current: OwnedCopyProjection,
        nowEpochMs: Long,
    ): CanonicalGameEntity {
        val canonical = CanonicalGameEntity(
            canonicalId = idGenerator.generate().value,
            steamAppId = null,
            displayName = CanonicalNormalization.displayName(current.displayName),
            matchTitleKey = CanonicalNormalization.titleKey(current.displayName),
            primaryMetadataSource = current.key.source,
            appType = current.appType,
            releaseYear = current.releaseYear,
            developerKey = CanonicalNormalization.developerKey(current.developer),
            classificationState = classificationState(
                hasGenres = current.genreKeys.isNotEmpty(),
                hasTagsOrFeatures = current.tagIds.isNotEmpty() || current.featureKeys.isNotEmpty(),
            ),
            steamReviewCount = null,
            createdAt = nowEpochMs,
            updatedAt = nowEpochMs,
        )
        canonicalGameDao.insert(canonical)
        return canonical
    }

    private suspend fun insertFacets(
        canonicalId: String,
        current: OwnedCopyProjection,
    ) {
        facetDao.upsertGenres(
            current.genreKeys.sorted().map { genreKey ->
                CanonicalGameGenreCrossRef(canonicalId, genreKey)
            },
        )
        facetDao.upsertTags(
            current.tagIds.sorted().map { tagId ->
                CanonicalGameTagCrossRef(canonicalId, tagId)
            },
        )
        facetDao.upsertFeatures(
            current.featureKeys.sorted().map { featureKey ->
                CanonicalGameFeatureCrossRef(canonicalId, featureKey)
            },
        )
    }

    private suspend fun classificationState(canonicalId: String): ClassificationState =
        classificationState(
            hasGenres = facetDao.getGenres(canonicalId).isNotEmpty(),
            hasTagsOrFeatures =
                facetDao.getTags(canonicalId).isNotEmpty() ||
                    facetDao.getFeatures(canonicalId).isNotEmpty(),
        )

    private fun classificationState(
        hasGenres: Boolean,
        hasTagsOrFeatures: Boolean,
    ): ClassificationState = when {
        hasGenres && hasTagsOrFeatures -> ClassificationState.CLASSIFIED
        hasGenres || hasTagsOrFeatures -> ClassificationState.PARTIALLY_CLASSIFIED
        else -> ClassificationState.UNCLASSIFIED
    }

    private suspend fun requireMatch(key: OwnedCopyKey): StoreMatchEntity = requireNotNull(
        storeMatchDao.get(
            accountScope = key.accountScope.value,
            source = key.source,
            stableSourceId = key.stableSourceId,
        ),
    ) { "Owned copy does not have a canonical relationship" }

    private suspend fun requireCanonical(canonicalId: String): CanonicalGameEntity = requireNotNull(
        canonicalGameDao.get(canonicalId),
    ) { "Canonical relationship references a missing game" }

    private fun requireMutableMatch(key: OwnedCopyKey) {
        require(key.source != GameSource.STEAM) {
            "Direct Steam identity cannot be manually reassigned"
        }
    }

    private fun StoreMatchEntity.hasKey(key: OwnedCopyKey): Boolean =
        accountScope == key.accountScope.value &&
            source == key.source &&
            stableSourceId == key.stableSourceId

    private fun StoreMatchEntity.asManualDecision(
        canonicalId: String,
        steamAppId: Int?,
        confidence: MatchConfidence,
        nowEpochMs: Long,
    ): StoreMatchEntity = copy(
        canonicalId = canonicalId,
        candidateSteamAppId = steamAppId,
        matchMethod = MatchMethod.MANUAL,
        confidence = confidence,
        decisionSource = MatchDecisionSource.USER,
        resolverVersion = CURRENT_RESOLVER_VERSION,
        matchedAt = nowEpochMs,
    )
}

internal fun selectCanonicalSurvivor(
    first: CanonicalGameEntity,
    second: CanonicalGameEntity,
    confirmedSteamAppId: Int?,
): CanonicalGameEntity {
    check(first.canonicalId != second.canonicalId) {
        "Cannot select a survivor from the same canonical game"
    }
    val canonicalGames = listOf(first, second)
    val steamIds = canonicalGames.mapNotNull { canonical -> canonical.steamAppId }.distinct()
    check(steamIds.size <= 1) {
        "Cannot select between conflicting Steam identities"
    }

    if (confirmedSteamAppId != null) {
        canonicalGames.singleOrNull { canonical ->
            canonical.steamAppId == confirmedSteamAppId
        }?.let { confirmed -> return confirmed }
    }
    canonicalGames.singleOrNull { canonical -> canonical.steamAppId != null }
        ?.let { steamAssociated -> return steamAssociated }
    return canonicalGames.minWith(
        compareBy(CanonicalGameEntity::createdAt, CanonicalGameEntity::canonicalId),
    )
}
