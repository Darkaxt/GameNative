package app.gamenative.ui.screen.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import app.gamenative.data.LibraryItem
import app.gamenative.library.canonical.OwnedCopyOperation
import app.gamenative.library.canonical.action.ActionFailureReason
import app.gamenative.library.canonical.action.OwnedCopyActionGuard
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.data.DownloadDisplayDetails
import app.gamenative.ui.data.GameDisplayInfo

// Transient presentation only; executable identity stays in BaseAppScreen's guard.
data class OwnedSourceDetailPresentation(
    val displayInfo: GameDisplayInfo,
    val downloadDetails: DownloadDisplayDetails,
    val options: List<AppMenuOption>,
    val dialogOpen: Boolean,
    val onOperation: (OwnedCopyOperation) -> Unit,
    val supplementalContent: @Composable () -> Unit,
)

@Composable
internal fun CanonicalSourceDetailHost(
    libraryItem: LibraryItem?,
    actionGuard: OwnedCopyActionGuard?,
    initialOperation: OwnedCopyOperation?,
    onInitialOperationConsumed: () -> Unit,
    onCanonicalActionUnavailable: (ActionFailureReason) -> Unit,
    onClickPlay: (LibraryItem, Boolean) -> Unit,
    onTestGraphics: (LibraryItem) -> Unit,
    onPlayWithDiagnostics: (LibraryItem) -> Unit,
    onAiDebugRun: (LibraryItem) -> Unit,
    onBack: () -> Unit,
    content: @Composable (OwnedSourceDetailPresentation?) -> Unit,
) {
    val currentContent = rememberUpdatedState(content)
    // Selecting a source attaches its state/dialog owner without recreating the shop's
    // tab, media, scroll or focus state at a different composition location.
    val detail = remember {
        movableContentOf<OwnedSourceDetailPresentation?> { currentContent.value(it) }
    }
    if (libraryItem == null || actionGuard == null) {
        detail(null)
    } else {
        key(libraryItem.appId) {
            AppScreen(
                libraryItem = libraryItem,
                actionGuard = actionGuard,
                initialOperation = initialOperation,
                onInitialOperationConsumed = onInitialOperationConsumed,
                onCanonicalActionUnavailable = onCanonicalActionUnavailable,
                onClickPlay = onClickPlay,
                onTestGraphics = onTestGraphics,
                onPlayWithDiagnostics = onPlayWithDiagnostics,
                onAiDebugRun = onAiDebugRun,
                onBack = onBack,
                sourceDetailsContent = { detail(it) },
            )
        }
    }
}
