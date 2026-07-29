package app.gamenative.library.canonical

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameEntity
import app.gamenative.data.canonical.CanonicalIdGenerator
import app.gamenative.data.canonical.CanonicalNormalization
import app.gamenative.data.canonical.ClassificationState
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.StoreMatchEntity
import app.gamenative.db.dao.CanonicalGameDao
import app.gamenative.db.dao.StoreMatchDao
import app.gamenative.library.canonical.source.OwnedCopyProjection
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

const val CURRENT_RESOLVER_VERSION = 1
const val SUPPORTED_TRUSTED_MAP_VERSION = 1

data class TrustedSteamMapping(
    val steamAppId: Int,
    val mapVersion: Int,
    val validatedOneToOne: Boolean,
) {
    init {
        require(steamAppId > 0) { "Trusted mapping Steam AppID must be positive" }
        require(mapVersion > 0) { "Trusted mapping version must be positive" }
    }
}

fun interface TrustedSteamMappingProvider {
    suspend fun find(copy: OwnedCopyProjection): TrustedSteamMapping?
}

data class CanonicalResolution(
    val canonical: CanonicalGameEntity,
    val match: StoreMatchEntity,
    val createdCanonical: Boolean,
) {
    init {
        require(match.canonicalId == canonical.canonicalId) {
            "Resolution match must reference its canonical game"
        }
    }
}

interface CanonicalResolver {
    suspend fun resolve(
        copy: OwnedCopyProjection,
        nowEpochMs: Long,
    ): CanonicalResolution
}

@Singleton
class CanonicalGameResolver @Inject constructor(
    private val canonicalGameDao: CanonicalGameDao,
    private val storeMatchDao: StoreMatchDao,
    private val trustedSteamMappingProviders: Set<@JvmSuppressWildcards TrustedSteamMappingProvider>,
    private val idGenerator: CanonicalIdGenerator,
) : CanonicalResolver {
    override suspend fun resolve(
        copy: OwnedCopyProjection,
        nowEpochMs: Long,
    ): CanonicalResolution {
        val evidence = Evidence.from(copy)
        val existingMatch = storeMatchDao.get(
            accountScope = copy.key.accountScope.value,
            source = copy.key.source,
            stableSourceId = copy.key.stableSourceId,
        )

        if (copy.key.source == GameSource.STEAM) {
            return resolveDirectSteam(copy, evidence, existingMatch, nowEpochMs)
        }

        if (existingMatch?.decisionSource == MatchDecisionSource.USER) {
            require(
                existingMatch.confidence == MatchConfidence.VERIFIED ||
                    existingMatch.confidence == MatchConfidence.REJECTED,
            ) { "Malformed stored user decision" }
            return existingResolution(
                existingMatch = existingMatch,
                copy = copy,
                evidence = evidence,
                nowEpochMs = nowEpochMs,
            )
        }

        val existingStandaloneCanonical = existingMatch
            ?.takeIf { match ->
                match.resolverVersion != CURRENT_RESOLVER_VERSION ||
                    match.confidence == MatchConfidence.UNMATCHED ||
                    match.confidence == MatchConfidence.REVIEW_REQUIRED
            }
            ?.let { match ->
                canonicalGameDao.get(match.canonicalId)?.takeIf {
                    storeMatchDao.countAllReferences(match.canonicalId) == 1
                }
            }

        val resolution = run {
            val untrustedMappingCandidate = when (val evaluation = evaluateTrustedMappings(copy)) {
                MappingEvaluation.Conflict -> {
                    return@run independentResolution(
                        copy = copy,
                        evidence = evidence,
                        existingStandaloneCanonical = existingStandaloneCanonical,
                        method = MatchMethod.OPTIONAL_RESOLVER,
                        confidence = MatchConfidence.REVIEW_REQUIRED,
                        candidateSteamAppId = null,
                        nowEpochMs = nowEpochMs,
                    )
                }

                is MappingEvaluation.Candidate -> {
                    val mapping = evaluation.mapping
                    val target = canonicalGameDao.findBySteamAppId(mapping.steamAppId)
                    val mappingIsTrusted =
                        mapping.validatedOneToOne &&
                            mapping.mapVersion == SUPPORTED_TRUSTED_MAP_VERSION &&
                            copy.appType != CanonicalAppType.UNKNOWN

                    if (mappingIsTrusted) {
                        if (target == null) {
                            return@run newAcceptedMappingResolution(
                                copy = copy,
                                evidence = evidence,
                                steamAppId = mapping.steamAppId,
                                nowEpochMs = nowEpochMs,
                            )
                        }
                        if (areKnownTypesCompatible(copy.appType, target.appType)) {
                            return@run acceptedResolution(
                                copy = copy,
                                evidence = evidence,
                                canonical = target,
                                method = MatchMethod.TRUSTED_DIRECT_MAP,
                                confidence = MatchConfidence.VERIFIED,
                                candidateSteamAppId = mapping.steamAppId,
                                nowEpochMs = nowEpochMs,
                            )
                        }
                        return@run independentResolution(
                            copy = copy,
                            evidence = evidence,
                            existingStandaloneCanonical = existingStandaloneCanonical,
                            method = MatchMethod.OPTIONAL_RESOLVER,
                            confidence = MatchConfidence.REVIEW_REQUIRED,
                            candidateSteamAppId = mapping.steamAppId,
                            nowEpochMs = nowEpochMs,
                        )
                    }
                    mapping.steamAppId
                }

                MappingEvaluation.None -> null
            }

            val exactCandidates = if (evidence.titleKey.isEmpty()) {
                emptyList()
            } else {
                canonicalGameDao.findByTitleKey(evidence.titleKey)
                    .filterNot { candidate ->
                        candidate.canonicalId == existingStandaloneCanonical?.canonicalId
                    }
                    .sortedWith(compareBy(CanonicalGameEntity::createdAt, CanonicalGameEntity::canonicalId))
            }

            val compatibleCandidates = exactCandidates.filter { candidate ->
                isCompatibleExactMatch(evidence, candidate)
            }
            if (compatibleCandidates.size == 1) {
                val compatibleCandidate = compatibleCandidates.single()
                return@run acceptedResolution(
                    copy = copy,
                    evidence = evidence,
                    canonical = compatibleCandidate,
                    method = MatchMethod.EXACT_METADATA,
                    confidence = MatchConfidence.HIGH,
                    candidateSteamAppId = compatibleCandidate.steamAppId,
                    nowEpochMs = nowEpochMs,
                )
            }
            if (compatibleCandidates.size > 1) {
                return@run independentResolution(
                    copy = copy,
                    evidence = evidence,
                    existingStandaloneCanonical = existingStandaloneCanonical,
                    method = MatchMethod.EXACT_METADATA,
                    confidence = MatchConfidence.REVIEW_REQUIRED,
                    candidateSteamAppId = null,
                    nowEpochMs = nowEpochMs,
                )
            }

            val reviewableCandidates = exactCandidates.filter { candidate ->
                isReviewableExactCandidate(evidence.appType, candidate.appType)
            }
            if (reviewableCandidates.isNotEmpty()) {
                return@run independentResolution(
                    copy = copy,
                    evidence = evidence,
                    existingStandaloneCanonical = existingStandaloneCanonical,
                    method = MatchMethod.EXACT_METADATA,
                    confidence = MatchConfidence.REVIEW_REQUIRED,
                    candidateSteamAppId = reviewableCandidates
                        .singleOrNull()
                        ?.steamAppId,
                    nowEpochMs = nowEpochMs,
                )
            }

            if (untrustedMappingCandidate != null) {
                return@run independentResolution(
                    copy = copy,
                    evidence = evidence,
                    existingStandaloneCanonical = existingStandaloneCanonical,
                    method = MatchMethod.OPTIONAL_RESOLVER,
                    confidence = MatchConfidence.REVIEW_REQUIRED,
                    candidateSteamAppId = untrustedMappingCandidate,
                    nowEpochMs = nowEpochMs,
                )
            }

            independentResolution(
                copy = copy,
                evidence = evidence,
                existingStandaloneCanonical = existingStandaloneCanonical,
                method = MatchMethod.UNMATCHED,
                confidence = MatchConfidence.UNMATCHED,
                candidateSteamAppId = null,
                nowEpochMs = nowEpochMs,
            )
        }
        return preserveMatchedAtForUnchangedAutomaticDecision(existingMatch, resolution)
    }

    private suspend fun resolveDirectSteam(
        copy: OwnedCopyProjection,
        evidence: Evidence,
        existingMatch: StoreMatchEntity?,
        nowEpochMs: Long,
    ): CanonicalResolution {
        val steamAppId = requireNotNull(copy.directSteamAppId) {
            "Direct Steam copy is missing its AppID"
        }
        require(steamAppId > 0 && copy.key.stableSourceId == steamAppId.toString()) {
            "Direct Steam identity does not match its stable source ID"
        }

        val steamCanonical = canonicalGameDao.findBySteamAppId(steamAppId)
        val historicalCanonical = existingMatch
            ?.takeIf { match ->
                match.decisionSource == MatchDecisionSource.AUTOMATIC &&
                    match.matchMethod == MatchMethod.DIRECT_STEAM &&
                    match.confidence == MatchConfidence.VERIFIED &&
                    match.candidateSteamAppId == steamAppId
            }
            ?.let { match -> canonicalGameDao.get(match.canonicalId) }
            ?.takeIf { canonical -> canonical.steamAppId == null }
        val canonical = when {
            steamCanonical != null -> steamCanonical.withPrimaryMetadata(
                ownedCopy = copy,
                evidence = evidence,
                steamAppId = steamAppId,
                nowEpochMs = nowEpochMs,
            )

            historicalCanonical != null -> historicalCanonical.withPrimaryMetadata(
                ownedCopy = copy,
                evidence = evidence,
                steamAppId = steamAppId,
                nowEpochMs = nowEpochMs,
            )

            else -> newCanonical(
                copy = copy,
                evidence = evidence,
                steamAppId = steamAppId,
                nowEpochMs = nowEpochMs,
            )
        }

        val matchedAt = existingMatch
            ?.takeIf { match ->
                match.decisionSource == MatchDecisionSource.AUTOMATIC &&
                    match.matchMethod == MatchMethod.DIRECT_STEAM &&
                    match.confidence == MatchConfidence.VERIFIED &&
                    match.resolverVersion == CURRENT_RESOLVER_VERSION &&
                    match.canonicalId == canonical.canonicalId &&
                    match.candidateSteamAppId == steamAppId
            }
            ?.matchedAt
            ?: nowEpochMs

        return CanonicalResolution(
            canonical = canonical,
            match = newMatch(
                copy = copy,
                evidence = evidence,
                canonicalId = canonical.canonicalId,
                method = MatchMethod.DIRECT_STEAM,
                confidence = MatchConfidence.VERIFIED,
                candidateSteamAppId = steamAppId,
                nowEpochMs = matchedAt,
            ),
            createdCanonical = steamCanonical == null && historicalCanonical == null,
        )
    }

    private suspend fun existingResolution(
        existingMatch: StoreMatchEntity,
        copy: OwnedCopyProjection,
        evidence: Evidence,
        nowEpochMs: Long,
    ): CanonicalResolution {
        val storedCanonical = requireNotNull(canonicalGameDao.get(existingMatch.canonicalId)) {
            "Stored match references a missing canonical game"
        }
        val canonical = if (storedCanonical.primaryMetadataSource == copy.key.source) {
            storedCanonical.withPrimaryMetadata(
                ownedCopy = copy,
                evidence = evidence,
                steamAppId = storedCanonical.steamAppId,
                nowEpochMs = nowEpochMs,
            )
        } else {
            storedCanonical
        }
        return CanonicalResolution(
            canonical = canonical,
            match = existingMatch.copy(
                isPresent = true,
                evidenceDisplayName = evidence.displayName,
                evidenceTitleKey = evidence.titleKey,
                evidenceDeveloperKey = evidence.developerKey,
                evidenceReleaseYear = evidence.releaseYear,
                evidenceAppType = evidence.appType,
            ),
            createdCanonical = false,
        )
    }

    private fun preserveMatchedAtForUnchangedAutomaticDecision(
        existingMatch: StoreMatchEntity?,
        resolution: CanonicalResolution,
    ): CanonicalResolution {
        val resolvedMatch = resolution.match
        if (
            existingMatch == null ||
            existingMatch.decisionSource != MatchDecisionSource.AUTOMATIC ||
            existingMatch.resolverVersion != CURRENT_RESOLVER_VERSION ||
            resolvedMatch.decisionSource != MatchDecisionSource.AUTOMATIC ||
            resolvedMatch.resolverVersion != CURRENT_RESOLVER_VERSION ||
            existingMatch.canonicalId != resolvedMatch.canonicalId ||
            existingMatch.candidateSteamAppId != resolvedMatch.candidateSteamAppId ||
            existingMatch.matchMethod != resolvedMatch.matchMethod ||
            existingMatch.confidence != resolvedMatch.confidence
        ) {
            return resolution
        }
        return resolution.copy(
            match = resolvedMatch.copy(matchedAt = existingMatch.matchedAt),
        )
    }

    private suspend fun evaluateTrustedMappings(copy: OwnedCopyProjection): MappingEvaluation {
        val mappings = trustedSteamMappingProviders
            .mapNotNull { provider -> provider.find(copy) }
            .filter { it.steamAppId > 0 }
        if (mappings.isEmpty()) return MappingEvaluation.None

        val appIds = mappings.map(TrustedSteamMapping::steamAppId).distinct()
        if (appIds.size > 1) return MappingEvaluation.Conflict

        val selected = mappings.firstOrNull { mapping ->
            mapping.validatedOneToOne &&
                mapping.mapVersion == SUPPORTED_TRUSTED_MAP_VERSION
        } ?: mappings.sortedWith(
            compareByDescending<TrustedSteamMapping> { it.validatedOneToOne }
                .thenBy { it.mapVersion }
        ).first()
        return MappingEvaluation.Candidate(selected)
    }

    private fun newAcceptedMappingResolution(
        copy: OwnedCopyProjection,
        evidence: Evidence,
        steamAppId: Int,
        nowEpochMs: Long,
    ): CanonicalResolution {
        val canonical = newCanonical(
            copy = copy,
            evidence = evidence,
            steamAppId = steamAppId,
            nowEpochMs = nowEpochMs,
        )
        return CanonicalResolution(
            canonical = canonical,
            match = newMatch(
                copy = copy,
                evidence = evidence,
                canonicalId = canonical.canonicalId,
                method = MatchMethod.TRUSTED_DIRECT_MAP,
                confidence = MatchConfidence.VERIFIED,
                candidateSteamAppId = steamAppId,
                nowEpochMs = nowEpochMs,
            ),
            createdCanonical = true,
        )
    }

    private fun acceptedResolution(
        copy: OwnedCopyProjection,
        evidence: Evidence,
        canonical: CanonicalGameEntity,
        method: MatchMethod,
        confidence: MatchConfidence,
        candidateSteamAppId: Int?,
        nowEpochMs: Long,
    ): CanonicalResolution {
        val resolvedCanonical = if (canonical.primaryMetadataSource == copy.key.source) {
            canonical.withPrimaryMetadata(
                ownedCopy = copy,
                evidence = evidence,
                steamAppId = canonical.steamAppId,
                nowEpochMs = nowEpochMs,
            )
        } else {
            canonical
        }
        return CanonicalResolution(
            canonical = resolvedCanonical,
            match = newMatch(
                copy = copy,
                evidence = evidence,
                canonicalId = resolvedCanonical.canonicalId,
                method = method,
                confidence = confidence,
                candidateSteamAppId = candidateSteamAppId,
                nowEpochMs = nowEpochMs,
            ),
            createdCanonical = false,
        )
    }

    private fun independentResolution(
        copy: OwnedCopyProjection,
        evidence: Evidence,
        existingStandaloneCanonical: CanonicalGameEntity?,
        method: MatchMethod,
        confidence: MatchConfidence,
        candidateSteamAppId: Int?,
        nowEpochMs: Long,
    ): CanonicalResolution {
        val canonical = existingStandaloneCanonical?.withPrimaryMetadata(
            ownedCopy = copy,
            evidence = evidence,
            steamAppId = null,
            nowEpochMs = nowEpochMs,
        ) ?: newCanonical(
            copy = copy,
            evidence = evidence,
            steamAppId = null,
            nowEpochMs = nowEpochMs,
        )
        return CanonicalResolution(
            canonical = canonical,
            match = newMatch(
                copy = copy,
                evidence = evidence,
                canonicalId = canonical.canonicalId,
                method = method,
                confidence = confidence,
                candidateSteamAppId = candidateSteamAppId,
                nowEpochMs = nowEpochMs,
            ),
            createdCanonical = existingStandaloneCanonical == null,
        )
    }

    private fun newCanonical(
        copy: OwnedCopyProjection,
        evidence: Evidence,
        steamAppId: Int?,
        nowEpochMs: Long,
    ): CanonicalGameEntity = CanonicalGameEntity(
        canonicalId = idGenerator.generate().value,
        steamAppId = steamAppId,
        displayName = evidence.displayName,
        matchTitleKey = evidence.titleKey,
        primaryMetadataSource = copy.key.source,
        appType = evidence.appType,
        releaseYear = evidence.releaseYear,
        developerKey = evidence.developerKey,
        classificationState = ClassificationState.UNCLASSIFIED,
        steamReviewCount = null,
        createdAt = nowEpochMs,
        updatedAt = nowEpochMs,
    )

    private fun newMatch(
        copy: OwnedCopyProjection,
        evidence: Evidence,
        canonicalId: String,
        method: MatchMethod,
        confidence: MatchConfidence,
        candidateSteamAppId: Int?,
        nowEpochMs: Long,
    ): StoreMatchEntity = StoreMatchEntity(
        accountScope = copy.key.accountScope.value,
        source = copy.key.source,
        stableSourceId = copy.key.stableSourceId,
        canonicalId = canonicalId,
        candidateSteamAppId = candidateSteamAppId,
        matchMethod = method,
        confidence = confidence,
        decisionSource = MatchDecisionSource.AUTOMATIC,
        resolverVersion = CURRENT_RESOLVER_VERSION,
        matchedAt = nowEpochMs,
        isPresent = true,
        evidenceDisplayName = evidence.displayName,
        evidenceTitleKey = evidence.titleKey,
        evidenceDeveloperKey = evidence.developerKey,
        evidenceReleaseYear = evidence.releaseYear,
        evidenceAppType = evidence.appType,
    )

    private fun CanonicalGameEntity.withPrimaryMetadata(
        ownedCopy: OwnedCopyProjection,
        evidence: Evidence,
        steamAppId: Int?,
        nowEpochMs: Long,
    ): CanonicalGameEntity {
        val updated = copy(
            steamAppId = steamAppId,
            displayName = evidence.displayName,
            matchTitleKey = evidence.titleKey,
            primaryMetadataSource = ownedCopy.key.source,
            appType = evidence.appType,
            releaseYear = evidence.releaseYear,
            developerKey = evidence.developerKey,
        )
        return if (updated == this) this else updated.copy(updatedAt = nowEpochMs)
    }

    private fun isCompatibleExactMatch(
        evidence: Evidence,
        candidate: CanonicalGameEntity,
    ): Boolean {
        if (!areKnownTypesCompatible(evidence.appType, candidate.appType)) return false

        val developerConflict =
            evidence.developerKey.isNotEmpty() &&
                candidate.developerKey.isNotEmpty() &&
                evidence.developerKey != candidate.developerKey
        if (developerConflict) return false

        val yearConflict =
            evidence.releaseYear != null &&
                candidate.releaseYear != null &&
                abs(evidence.releaseYear - candidate.releaseYear) > 1
        if (yearConflict) return false

        val equalKnownDeveloper =
            evidence.developerKey.isNotEmpty() &&
                evidence.developerKey == candidate.developerKey
        val compatibleKnownYear =
            evidence.releaseYear != null &&
                candidate.releaseYear != null &&
                abs(evidence.releaseYear - candidate.releaseYear) <= 1
        return equalKnownDeveloper || compatibleKnownYear
    }

    private fun areKnownTypesCompatible(
        sourceType: CanonicalAppType,
        targetType: CanonicalAppType,
    ): Boolean =
        sourceType != CanonicalAppType.UNKNOWN &&
            targetType != CanonicalAppType.UNKNOWN &&
            sourceType == targetType

    private fun isReviewableExactCandidate(
        sourceType: CanonicalAppType,
        targetType: CanonicalAppType,
    ): Boolean =
        sourceType == CanonicalAppType.UNKNOWN ||
            targetType == CanonicalAppType.UNKNOWN ||
            sourceType == targetType

    private data class Evidence(
        val displayName: String,
        val titleKey: String,
        val developerKey: String,
        val releaseYear: Int?,
        val appType: CanonicalAppType,
    ) {
        companion object {
            fun from(copy: OwnedCopyProjection): Evidence = Evidence(
                displayName = CanonicalNormalization.displayName(copy.displayName),
                titleKey = CanonicalNormalization.titleKey(copy.displayName),
                developerKey = CanonicalNormalization.developerKey(copy.developer),
                releaseYear = copy.releaseYear,
                appType = copy.appType,
            )
        }
    }

    private sealed interface MappingEvaluation {
        data object None : MappingEvaluation
        data object Conflict : MappingEvaluation
        data class Candidate(val mapping: TrustedSteamMapping) : MappingEvaluation
    }
}
