package app.gamenative.ui.screen.library.components

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.data.CommunityCompatibilitySummary
import app.gamenative.data.CommunityCompatibilityVerdict
import app.gamenative.data.CommunityEvidenceTier
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.library.canonical.CanonicalCardKey
import app.gamenative.ui.data.LibraryCard
import app.gamenative.ui.data.LibraryCardIdentity
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.theme.PluviaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryGridBadgeLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sourceBadgesMirrorCompatibilityInsetsAndKeepCopiesActivation() {
        var pane by mutableStateOf(PaneType.GRID_HERO)
        var sources by mutableStateOf(setOf(GameSource.GOG, GameSource.EPIC))
        var width by mutableStateOf(300.dp)
        var legacy by mutableStateOf(false)
        lateinit var inputModeManager: InputModeManager
        var opens = 0
        compose.setContent {
            inputModeManager = LocalInputModeManager.current
            val context = LocalContext.current
            PrefManager.init(context)
            PluviaTheme {
                GridViewCard(
                    modifier = Modifier.width(width).testTag("grid"),
                    card = LibraryCard.canonical(
                        key = CanonicalCardKey.Grouped(CanonicalGameId.parse("00000000-0000-0000-0000-000000000001")),
                        index = 0, name = "Fixture", ownedSources = sources,
                    ).copy(communityCompatibility = CommunityCompatibilitySummary(
                        verdict = CommunityCompatibilityVerdict.WORKS,
                        evidenceTier = CommunityEvidenceTier.NONE, verdictLoaded = true,
                    )).let { card ->
                        if (legacy) card.copy(identity = LibraryCardIdentity.SourceCopy(
                            LibraryItem(appId = "GOG_fixture", name = "Fixture", gameSource = GameSource.GOG))) else card
                    },
                    onClick = {}, onCopies = { opens++ },
                    cardFocusModifier = Modifier, copiesActionModifier = Modifier,
                    onFocus = {}, isFocused = false, onFocusChanged = {}, scale = 1f,
                    paneType = pane, imageRefreshCounter = 0L, hideText = false, imageAlpha = 1f,
                    onImageLoadFailed = {}, showFocusGlow = false, context = context, animateStats = false,
                )
            }
        }
        for (layout in listOf(PaneType.GRID_HERO, PaneType.GRID_CAPSULE)) {
            for (owned in listOf(setOf(GameSource.GOG), setOf(GameSource.GOG, GameSource.EPIC), GameSource.entries.toSet())) {
                for (cardWidth in listOf(300.dp, 160.dp)) {
                    compose.runOnIdle { pane = layout; sources = owned; width = cardWidth }
                    val card = compose.onNodeWithTag("grid", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                    val compatibility = compose.onNodeWithContentDescription("Plays well", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                    val badges = compose.onNodeWithTag("owned-source-badges", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                    assertEquals("Visible top edges must mirror", compatibility.top, badges.top, 1f)
                    assertEquals("Visible outer side insets must mirror", compatibility.left - card.left, card.right - badges.right, 1f)
                    assertTrue("Opposite badges must not overlap", compatibility.right <= badges.left)
                    val action = compose.onNodeWithTag("copies-action", useUnmergedTree = true)
                    val actionBounds = action.fetchSemanticsNode().boundsInRoot
                    assertTrue(actionBounds.width >= with(compose.density) { 48.dp.toPx() })
                    assertTrue(actionBounds.height >= with(compose.density) { 48.dp.toPx() })
                    owned.forEach { compose.onNodeWithTag("owned-source-badge:${it.name}", useUnmergedTree = true).assertIsDisplayed() }
                    compose.runOnIdle { inputModeManager.requestInputMode(InputMode.Keyboard) }
                    action.assertHasClickAction().performSemanticsAction(SemanticsActions.RequestFocus)
                    action.assertIsFocused().performClick()
                }
            }
        }
        compose.runOnIdle { assertEquals(12, opens) }
        for (layout in listOf(PaneType.GRID_HERO, PaneType.GRID_CAPSULE)) {
            compose.runOnIdle { legacy = true; pane = layout; sources = setOf(GameSource.GOG); width = 160.dp }
            val card = compose.onNodeWithTag("grid", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val compatibility = compose.onNodeWithContentDescription("Plays well", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val icon = compose.onNodeWithContentDescription("Gog", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(compatibility.top, icon.top, 1f)
            assertEquals(compatibility.left - card.left, card.right - icon.right, 1f)
            assertTrue(compatibility.right <= icon.left)
            compose.onNodeWithTag("copies-action").assertDoesNotExist()
        }
    }
}
