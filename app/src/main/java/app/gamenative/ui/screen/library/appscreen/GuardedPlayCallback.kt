package app.gamenative.ui.screen.library.appscreen

import app.gamenative.data.LibraryItem
import app.gamenative.library.canonical.OwnedCopyOperation

internal fun guardedPlayCallback(
    guardedAction: (OwnedCopyOperation, (LibraryItem) -> Unit) -> Unit,
    action: (LibraryItem) -> Unit,
): () -> Unit = { guardedAction(OwnedCopyOperation.PLAY, action) }
