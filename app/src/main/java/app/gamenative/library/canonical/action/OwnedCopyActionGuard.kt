package app.gamenative.library.canonical.action

import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.canonical.CanonicalPublicLibraryGate
import app.gamenative.library.canonical.OwnedCopyOperation
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeRegistry
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeResult
import app.gamenative.library.canonical.source.SourceOwnedCopyReference

sealed interface ActionRevalidationResult {
    data class Ready(val libraryItem: LibraryItem) : ActionRevalidationResult

    data class Unavailable(val reason: ActionFailureReason) : ActionRevalidationResult
}

class OwnedCopyActionGuard internal constructor(
    val key: OwnedCopyKey,
    private val capturedReference: SourceOwnedCopyReference,
    val initialLibraryItem: LibraryItem,
    private val runtimeRegistry: OwnedCopyRuntimeRegistry,
    private val publicGate: CanonicalPublicLibraryGate,
) {
    init {
        check(capturedReference.key == key) {
            "Captured reference key differs from action key"
        }
        check(referenceMatchesSource(capturedReference, key.source)) {
            "Captured reference belongs to another source"
        }
        check(initialLibraryItem.gameSource == key.source) {
            "Initial library item belongs to another source"
        }
    }

    suspend fun revalidate(operation: OwnedCopyOperation): ActionRevalidationResult {
        if (!publicGate.isEnabled()) {
            return ActionRevalidationResult.Unavailable(
                ActionFailureReason.PUBLIC_FEATURE_DISABLED,
            )
        }
        return when (val current = runtimeRegistry.resolve(key)) {
            is OwnedCopyRuntimeResult.Available -> {
                val copy = current.copy
                check(copy.key == key) { "Current runtime key differs from action key" }
                check(copy.reference.key == key) {
                    "Current runtime reference key differs from action key"
                }
                check(referenceMatchesSource(copy.reference, key.source)) {
                    "Current runtime reference belongs to another source"
                }
                when {
                    copy.reference != capturedReference ->
                        ActionRevalidationResult.Unavailable(ActionFailureReason.TARGET_CHANGED)
                    copy.libraryItem == null ->
                        ActionRevalidationResult.Unavailable(ActionFailureReason.COPY_UNAVAILABLE)
                    copy.libraryItem.gameSource != key.source ->
                        error("Current library item belongs to another source")
                    operation !in copy.capabilities ->
                        ActionRevalidationResult.Unavailable(ActionFailureReason.CAPABILITY_CHANGED)
                    else -> ActionRevalidationResult.Ready(copy.libraryItem)
                }
            }
            OwnedCopyRuntimeResult.Hidden,
            is OwnedCopyRuntimeResult.Unavailable,
            -> ActionRevalidationResult.Unavailable(ActionFailureReason.COPY_UNAVAILABLE)
        }
    }
}

internal fun referenceMatchesSource(
    reference: SourceOwnedCopyReference,
    source: GameSource,
): Boolean = when (reference) {
    is SourceOwnedCopyReference.Steam -> source == GameSource.STEAM
    is SourceOwnedCopyReference.Gog -> source == GameSource.GOG
    is SourceOwnedCopyReference.Epic -> source == GameSource.EPIC
    is SourceOwnedCopyReference.Amazon -> source == GameSource.AMAZON
    is SourceOwnedCopyReference.Custom -> source == GameSource.CUSTOM_GAME
}
