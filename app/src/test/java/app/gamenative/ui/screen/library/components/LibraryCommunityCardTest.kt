package app.gamenative.ui.screen.library.components

import android.app.Application
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.GameCompatibilityStatus
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.library.canonical.CanonicalCardKey
import app.gamenative.ui.data.LibraryCard
import app.gamenative.ui.theme.PluviaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], qualifiers = "w1200dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
class LibraryCommunityCardTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun ordinaryCardDoesNotInventHardwareVerdictFromLegacyStatus() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        PrefManager.init(context)
        val card = LibraryCard.canonical(
            key = CanonicalCardKey.Grouped(CanonicalGameId.parse("11111111-1111-1111-1111-111111111111")),
            index = 0, name = "Synthetic game", ownedSources = setOf(GameSource.GOG),
            compatibilityStatus = GameCompatibilityStatus.COMPATIBLE,
        )
        composeRule.setContent {
            PluviaTheme {
                ListViewCard(
                    modifier = Modifier, card = card, onClick = {}, onCopies = {},
                    cardFocusModifier = Modifier, copiesActionModifier = Modifier,
                    onFocus = {}, isFocused = false, onFocusChanged = {},
                    isRefreshing = false, context = context,
                )
            }
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.community_compatibility_unavailable_short)).assertIsDisplayed()
    }
}
