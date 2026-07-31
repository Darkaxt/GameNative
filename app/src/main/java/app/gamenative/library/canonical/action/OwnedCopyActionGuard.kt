package app.gamenative.library.canonical.action

import app.gamenative.data.LibraryItem
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.canonical.CanonicalLibraryDiagnosticSink
import app.gamenative.library.canonical.CanonicalPublicLibraryGate
import app.gamenative.library.canonical.OwnedCopyOperation
import app.gamenative.library.canonical.recordSafely
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
    private val diagnostics: CanonicalLibraryDiagnosticSink,
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
            return unavailable(operation, ActionFailureReason.PUBLIC_FEATURE_DISABLED)
        }
        val current = runtimeRegistry.resolve(key)
        if (!publicGate.isEnabled()) {
            return unavailable(operation, ActionFailureReason.PUBLIC_FEATURE_DISABLED)
        }
        return when (current) {
            is OwnedCopyRuntimeResult.Available -> {
                val copy = current.copy
                copy.requireIdentity(key)
                when {
                    copy.reference != capturedReference ->
                        unavailable(operation, ActionFailureReason.TARGET_CHANGED)
                    copy.libraryItem == null ->
                        unavailable(operation, ActionFailureReason.COPY_UNAVAILABLE)
                    operation !in copy.capabilities ->
                        unavailable(operation, ActionFailureReason.CAPABILITY_CHANGED)
                    !publicGate.isEnabled() ->
                        unavailable(operation, ActionFailureReason.PUBLIC_FEATURE_DISABLED)
                    else -> {
                        diagnostics.recordSafely {
                            routeSucceeded(key.source, operation)
                        }
                        ActionRevalidationResult.Ready(copy.libraryItem)
                    }
                }
            }
            OwnedCopyRuntimeResult.Hidden,
            is OwnedCopyRuntimeResult.Unavailable,
            -> unavailable(operation, ActionFailureReason.COPY_UNAVAILABLE)
        }
    }

    private fun unavailable(
        operation: OwnedCopyOperation,
        reason: ActionFailureReason,
    ): ActionRevalidationResult.Unavailable {
        diagnostics.recordSafely {
            revalidationFailed(key.source, operation, reason)
        }
        return ActionRevalidationResult.Unavailable(reason)
    }
}
