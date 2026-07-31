package app.gamenative.library.canonical

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.OwnedCopyKey
import java.security.MessageDigest

sealed interface CanonicalCardKey {
    data class Grouped(val canonicalId: CanonicalGameId) : CanonicalCardKey
    data class Independent(val copyKey: OwnedCopyKey) : CanonicalCardKey
}

internal fun CanonicalCardKey.stableComposeKey(): String = when (this) {
    is CanonicalCardKey.Grouped -> "group:${canonicalId.value}"
    is CanonicalCardKey.Independent -> {
        val raw = listOf(
            copyKey.accountScope.value,
            copyKey.source.name,
            copyKey.stableSourceId,
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { byte -> "%02x".format(byte) }
        "copy:${copyKey.source.name}:$digest"
    }
}

enum class OwnedCopyOperation {
    INSTALL,
    PLAY,
    UPDATE,
    UNINSTALL,
    PAUSE_RESUME_DOWNLOAD,
    CANCEL_DOWNLOAD,
    EXPORT_SAVES,
    IMPORT_SAVES,
    OPEN_SOURCE_DETAILS,
}

enum class CopyUnavailableReason {
    SOURCE_READ_FAILED,
    SOURCE_ROW_CHANGED,
    LEGACY_BRIDGE_UNSUPPORTED,
}

enum class CanonicalPublicFailure {
    MISSING_PROJECTION_PREREQUISITE,
    ASSEMBLY_FAILED,
    INVALID_CARD_STATE,
    UNSUPPORTED_LEGACY_CONTEXT,
}

data class OwnedCopySummary(
    val key: OwnedCopyKey,
    val source: GameSource,
    val nativeTitle: String,
    val installPath: String?,
    val installedSizeBytes: Long?,
    val branchOrVersion: String?,
    val isInstalled: Boolean,
    val isDownloading: Boolean,
    val hasPartialDownload: Boolean,
    val updateAvailable: Boolean,
    val isShared: Boolean,
    val lastPlayedEpochMs: Long?,
    val playtimeMinutes: Long?,
    val capabilities: Set<OwnedCopyOperation>,
    val unavailableReason: CopyUnavailableReason?,
    val canSeparateMatch: Boolean,
    val matchMethod: MatchMethod,
    val confidence: MatchConfidence,
    val decisionSource: MatchDecisionSource,
    val decisionRevision: Long,
)

data class CanonicalLibraryCard(
    val key: CanonicalCardKey,
    val canonicalId: CanonicalGameId,
    val displayName: String,
    val appType: CanonicalAppType,
    val iconUrl: String,
    val capsuleImageUrl: String,
    val headerImageUrl: String,
    val heroImageUrl: String,
    val gridHeroImageScale: Float,
    val aliases: Set<String>,
    val ownedSources: Set<GameSource>,
    val copies: List<OwnedCopySummary>,
    val preferredCopy: OwnedCopyKey?,
    val steamCollectionAppIds: Set<Int>,
    val isShared: Boolean,
) {
    val isInstalled: Boolean get() = copies.any(OwnedCopySummary::isInstalled)
    val lastPlayedEpochMs: Long? get() = copies.mapNotNull { it.lastPlayedEpochMs }.maxOrNull()
}
