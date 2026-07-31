package app.gamenative.library.canonical.action

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.canonical.CanonicalCardKey
import app.gamenative.library.canonical.CanonicalLibraryCard
import app.gamenative.library.canonical.CanonicalLibraryDiagnosticSink
import app.gamenative.library.canonical.CanonicalProjectionClock
import app.gamenative.library.canonical.CanonicalPublicLibraryGate
import app.gamenative.library.canonical.OwnedCopyOperation
import app.gamenative.library.canonical.OwnedCopySummary
import app.gamenative.library.canonical.PreferredCopyRepository
import app.gamenative.library.canonical.recordSafely
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeRegistry
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeResult
import app.gamenative.library.canonical.runtime.requireIdentity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

enum class ActionSelectionPolicy {
    EXPLICIT,
    PREFERRED,
    MOST_RECENT_PLAY,
    SOLE_COPY,
}

enum class ActionFailureReason {
    INVALID_EXPLICIT_COPY,
    NO_CAPABLE_COPY,
    COPY_UNAVAILABLE,
    TARGET_CHANGED,
    CAPABILITY_CHANGED,
    PREFERENCE_WRITE_FAILED,
    PUBLIC_FEATURE_DISABLED,
}

sealed interface OwnedCopyRouteResult {
    data class Ready(
        val guard: OwnedCopyActionGuard,
        val policy: ActionSelectionPolicy,
        val warning: ActionFailureReason? = null,
    ) : OwnedCopyRouteResult

    data class NeedsChooser(val capableKeys: List<OwnedCopyKey>) : OwnedCopyRouteResult

    data class Unavailable(val reason: ActionFailureReason) : OwnedCopyRouteResult
}

@Singleton
class OwnedCopyActionRouter @Inject constructor(
    private val runtimeRegistry: OwnedCopyRuntimeRegistry,
    private val preferredCopyRepository: PreferredCopyRepository,
    private val publicGate: CanonicalPublicLibraryGate,
    private val clock: CanonicalProjectionClock,
    private val diagnostics: CanonicalLibraryDiagnosticSink,
) {
    suspend fun route(
        card: CanonicalLibraryCard,
        operation: OwnedCopyOperation,
        explicitKey: OwnedCopyKey? = null,
        rememberChoice: Boolean = false,
    ): OwnedCopyRouteResult {
        if (!publicGate.isEnabled()) {
            return unavailable(null, operation, ActionFailureReason.PUBLIC_FEATURE_DISABLED)
        }

        val capableCopies = card.copies.filter { operation in it.capabilities }
        val selection = if (explicitKey != null) {
            val explicitCopy = card.copies.firstOrNull { it.key == explicitKey }
                ?: return unavailable(
                    explicitKey.source,
                    operation,
                    ActionFailureReason.INVALID_EXPLICIT_COPY,
                )
            if (operation !in explicitCopy.capabilities) {
                return unavailable(
                    explicitKey.source,
                    operation,
                    ActionFailureReason.NO_CAPABLE_COPY,
                )
            }
            Selection(explicitKey, ActionSelectionPolicy.EXPLICIT)
        } else {
            automaticSelection(card, capableCopies, operation)
                ?: return when {
                    capableCopies.isEmpty() -> unavailable(
                        null,
                        operation,
                        ActionFailureReason.NO_CAPABLE_COPY,
                    )
                    else -> chooser(operation, capableCopies)
                }
        }

        val runtimeResult = runtimeRegistry.resolve(selection.key)
        if (!publicGate.isEnabled()) {
            return unavailable(
                selection.key.source,
                operation,
                ActionFailureReason.PUBLIC_FEATURE_DISABLED,
            )
        }
        val available = when (runtimeResult) {
            is OwnedCopyRuntimeResult.Available -> runtimeResult.copy
            OwnedCopyRuntimeResult.Hidden,
            is OwnedCopyRuntimeResult.Unavailable,
            -> return unavailable(
                selection.key.source,
                operation,
                ActionFailureReason.COPY_UNAVAILABLE,
            )
        }
        available.requireIdentity(selection.key)
        val libraryItem = available.libraryItem
            ?: return unavailable(
                selection.key.source,
                operation,
                ActionFailureReason.COPY_UNAVAILABLE,
            )
        if (operation !in available.capabilities) {
            return unavailable(
                selection.key.source,
                operation,
                ActionFailureReason.CAPABILITY_CHANGED,
            )
        }

        val guard = OwnedCopyActionGuard(
            key = selection.key,
            capturedReference = available.reference,
            initialLibraryItem = libraryItem,
            runtimeRegistry = runtimeRegistry,
            publicGate = publicGate,
            diagnostics = diagnostics,
        )
        if (!publicGate.isEnabled()) {
            return unavailable(
                selection.key.source,
                operation,
                ActionFailureReason.PUBLIC_FEATURE_DISABLED,
            )
        }
        val warning = rememberExplicitChoice(
            card = card,
            selection = selection,
            operation = operation,
            rememberChoice = rememberChoice,
        )
        if (!publicGate.isEnabled()) {
            return unavailable(
                selection.key.source,
                operation,
                ActionFailureReason.PUBLIC_FEATURE_DISABLED,
            )
        }
        diagnostics.recordSafely {
            routeSelected(
                source = selection.key.source,
                operation = operation,
                policy = selection.policy,
                capableCount = capableCopies.size,
            )
        }
        return OwnedCopyRouteResult.Ready(
            guard = guard,
            policy = selection.policy,
            warning = warning,
        )
    }

    private fun chooser(
        operation: OwnedCopyOperation,
        capableCopies: List<OwnedCopySummary>,
    ): OwnedCopyRouteResult.NeedsChooser {
        diagnostics.recordSafely {
            chooserRequired(operation, capableCopies.size)
        }
        return OwnedCopyRouteResult.NeedsChooser(
            capableCopies.sortedBy { sourceRank(it.source) }.map(OwnedCopySummary::key),
        )
    }

    private fun unavailable(
        source: GameSource?,
        operation: OwnedCopyOperation,
        reason: ActionFailureReason,
    ): OwnedCopyRouteResult.Unavailable {
        diagnostics.recordSafely {
            routeFailed(source, operation, reason)
        }
        return OwnedCopyRouteResult.Unavailable(reason)
    }

    private fun automaticSelection(
        card: CanonicalLibraryCard,
        capableCopies: List<OwnedCopySummary>,
        operation: OwnedCopyOperation,
    ): Selection? {
        val preferred = card.preferredCopy?.let { preferredKey ->
            capableCopies.firstOrNull { it.key == preferredKey }
        }
        if (preferred != null) {
            return Selection(preferred.key, ActionSelectionPolicy.PREFERRED)
        }

        if (operation == OwnedCopyOperation.PLAY) {
            val installed = capableCopies.filter(OwnedCopySummary::isInstalled)
            val maximum = installed.mapNotNull { it.lastPlayedEpochMs?.takeIf { value -> value > 0L } }
                .maxOrNull()
            if (maximum != null) {
                val mostRecent = installed.filter { it.lastPlayedEpochMs == maximum }
                if (mostRecent.size == 1) {
                    return Selection(
                        mostRecent.single().key,
                        ActionSelectionPolicy.MOST_RECENT_PLAY,
                    )
                }
            }
        }

        return capableCopies.singleOrNull()?.let { copy ->
            Selection(copy.key, ActionSelectionPolicy.SOLE_COPY)
        }
    }

    private suspend fun rememberExplicitChoice(
        card: CanonicalLibraryCard,
        selection: Selection,
        operation: OwnedCopyOperation,
        rememberChoice: Boolean,
    ): ActionFailureReason? {
        if (selection.policy != ActionSelectionPolicy.EXPLICIT || !rememberChoice) return null
        val groupedKey = card.key as? CanonicalCardKey.Grouped ?: return null
        if (groupedKey.canonicalId != card.canonicalId) return null
        return try {
            preferredCopyRepository.setPreferredCopy(
                canonicalId = groupedKey.canonicalId,
                key = selection.key,
                nowEpochMs = clock.nowEpochMs(),
            )
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            diagnostics.recordSafely {
                routeFailed(
                    source = selection.key.source,
                    operation = operation,
                    reason = ActionFailureReason.PREFERENCE_WRITE_FAILED,
                    errorClass = error::class,
                )
            }
            ActionFailureReason.PREFERENCE_WRITE_FAILED
        }
    }

    private data class Selection(
        val key: OwnedCopyKey,
        val policy: ActionSelectionPolicy,
    )

    private fun sourceRank(source: GameSource): Int = when (source) {
        GameSource.STEAM -> 0
        GameSource.GOG -> 1
        GameSource.EPIC -> 2
        GameSource.AMAZON -> 3
        GameSource.CUSTOM_GAME -> 4
    }
}
