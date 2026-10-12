package app.gamenative.ui.screen.library

import android.app.Application
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.dlc.DlcItem
import app.gamenative.library.dlc.DlcOwnership
import app.gamenative.library.dlc.DlcState
import app.gamenative.ui.theme.PluviaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], qualifiers = "w420dp-h900dp")
@LooperMode(LooperMode.Mode.PAUSED)
class CanonicalDlcSectionTest {
    @get:Rule val composeRule = createComposeRule()
    private val first = key("42")
    private val second = key("99")
    private val copies = listOf(DlcCopyOption(first, "Standard", "GOG"), DlcCopyOption(second, "Deluxe", "GOG"))

    @Test
    fun individualRowsDistinguishOwnedNotOwnedAndUnknown() {
        show(DlcState.Content(first, listOf(DlcItem("43", "Expansion A", DlcOwnership.OWNED), DlcItem("44", "Expansion B", DlcOwnership.NOT_OWNED), DlcItem("45", "Expansion C", DlcOwnership.UNKNOWN)), true, false))
        listOf("Expansion A", "Expansion B", "Expansion C", "Owned", "Not owned", "Unknown").forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun sameStoreEditionSelectionCapturesTheExactCopyWithoutAnActionPreference() {
        var selected: OwnedCopyKey? = null
        show(DlcState.Idle, onSelect = { selected = it })
        composeRule.onNodeWithTag("canonical-dlc-copy:0").assertIsSelected()
        composeRule.onNodeWithTag("canonical-dlc-copy:1").performClick()
        assertEquals(second, selected)
    }

    @Test
    fun staleOtherCopyContentIsNotRenderedUnderCurrentSelection() {
        show(DlcState.Content(second, listOf(DlcItem("43", "Wrong copy expansion", DlcOwnership.OWNED)), true, true))
        composeRule.onNodeWithText("Wrong copy expansion").assertDoesNotExist()
        composeRule.onNodeWithText("Owned").assertDoesNotExist()
    }

    @Test
    fun emptyCompleteCatalogHasAnHonestExplicitEmptyState() {
        show(DlcState.Content(first, emptyList(), true, true))
        composeRule.onNodeWithText("No DLC listed for this copy.").assertIsDisplayed()
    }

    @Test
    fun partialCatalogRetainsUnresolvedProductAndVisibleWarning() {
        show(DlcState.Content(first, listOf(DlcItem("43", null, DlcOwnership.UNKNOWN)), false, false))
        composeRule.onNodeWithText("Product 43").assertIsDisplayed()
        composeRule.onNodeWithText("Some DLC details could not be loaded.").assertIsDisplayed()
        composeRule.onNodeWithText("Ownership information is incomplete; missing licenses remain unknown.").assertIsDisplayed()
    }

    @Test
    fun unavailableSectionOffersRetryWithoutChangingCopies() {
        var requests = 0
        show(DlcState.Unavailable, onRetry = { requests++ })
        composeRule.onNodeWithTag("canonical-dlc-retry").performClick()
        assertEquals(1, requests)
        composeRule.onNodeWithText("DLC information is unavailable for this copy.").assertIsDisplayed()
    }

    @Test
    fun copySelectionHasAVisibleRadioIndicatorAndUsableTouchTarget() {
        show(DlcState.Idle)
        composeRule.onNodeWithTag("canonical-dlc-copy:0").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("canonical-dlc-copy-radio:0", useUnmergedTree = true).assertExists()
    }

    private fun show(state: DlcState, onSelect: (OwnedCopyKey) -> Unit = {}, onRetry: () -> Unit = {}) {
        composeRule.setContent {
            PluviaTheme { CanonicalDlcSection(state, copies, first, onSelect, onRetry) }
        }
    }
    private fun key(id: String) = OwnedCopyKey(AccountScope.parse("a".repeat(64)), GameSource.GOG, id)
}
