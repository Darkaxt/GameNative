package app.gamenative.ui.screen.library

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.data.DownloadDisplayDetails
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.ui.theme.PluviaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], qualifiers = "w1200dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
class CanonicalSourceInformationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun sourceInformationRetainsNativePreferredCopyControl() {
        var requests = 0
        show(info().copy(showChangePreferredCopy = true, onChangePreferredCopy = { requests++ }))
        composeRule.onNodeWithText(text(R.string.change_preferred_copy)).performClick()
        assertEquals(1, requests)
        composeRule.onNodeWithText("Synthetic native title").assertIsDisplayed()
    }

    @Test
    fun sourceInformationShowsPreferredCopyLoading() {
        show(info().copy(isLoadingPreferredCopy = true))
        composeRule.onNodeWithText(text(R.string.loading_preferred_copy)).assertIsDisplayed()
    }

    private fun show(info: GameDisplayInfo) {
        PrefManager.init(ApplicationProvider.getApplicationContext<Application>())
        val source = OwnedSourceDetailPresentation(
            displayInfo = info,
            downloadDetails = DownloadDisplayDetails(true, false, false, 0f, false, false),
            options = emptyList(), dialogOpen = false, onOperation = {}, supplementalContent = {},
        )
        composeRule.setContent { PluviaTheme { CanonicalSourceInformation(source) } }
    }

    private fun info() = GameDisplayInfo(
        name = "Synthetic native title", developer = "", releaseDate = 0L,
        heroImageUrl = null, iconUrl = null, gameId = 42, appId = "STEAM_42",
    )

    private fun text(id: Int) = ApplicationProvider.getApplicationContext<Application>().getString(id)
}
