package app.gamenative.library.canonical.action

import app.gamenative.data.LibraryItem
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.canonical.CanonicalPublicLibraryGate
import app.gamenative.library.canonical.OwnedCopyOperation
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeRegistry
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeResult
import app.gamenative.library.canonical.runtime.requireExactRuntimeIdentity
import app.gamenative.library.canonical.runtime.requireIdentity
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
        requireExactRuntimeIdentity(
            requestedKey = key,
            reference = capturedReference,
            libraryItem = initialLibraryItem,
        )
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
                copy.requireIdentity(key)
                when {
                    copy.reference != capturedReference ->
                        ActionRevalidationResult.Unavailable(ActionFailureReason.TARGET_CHANGED)
                    copy.libraryItem == null ->
                        ActionRevalidationResult.Unavailable(ActionFailureReason.COPY_UNAVAILABLE)
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
