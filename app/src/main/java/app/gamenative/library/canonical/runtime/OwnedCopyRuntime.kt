package app.gamenative.library.canonical.runtime

import app.gamenative.data.LibraryItem
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.canonical.CopyUnavailableReason
import app.gamenative.library.canonical.OwnedCopyOperation
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import kotlin.reflect.KClass

data class OwnedCopyRuntime(
    val key: OwnedCopyKey,
    val reference: SourceOwnedCopyReference,
    val libraryItem: LibraryItem?,
    val nativeTitle: String,
    val aliases: Set<String>,
    val developerKey: String,
    val releaseYear: Int?,
    val appType: CanonicalAppType,
    val genreKeys: Set<String>,
    val tagIds: Set<Int>,
    val featureKeys: Set<String>,
    val iconUrl: String,
    val capsuleImageUrl: String,
    val headerImageUrl: String,
    val heroImageUrl: String,
    val gridHeroImageScale: Float,
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
)

sealed interface OwnedCopyRuntimeResult {
    data class Available(val copy: OwnedCopyRuntime) : OwnedCopyRuntimeResult

    data class Unavailable(
        val key: OwnedCopyKey,
        val reason: CopyUnavailableReason,
        val errorClass: KClass<out Throwable>? = null,
    ) : OwnedCopyRuntimeResult

    data object Hidden : OwnedCopyRuntimeResult
}
