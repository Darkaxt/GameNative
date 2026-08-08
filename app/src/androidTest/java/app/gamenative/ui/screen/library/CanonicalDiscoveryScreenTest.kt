package app.gamenative.ui.screen.library

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.gamenative.library.discovery.GameFacet
import app.gamenative.ui.enums.AppFilter
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.enums.SortOption
import app.gamenative.ui.model.SteamMatchUiState
import app.gamenative.ui.model.SteamResolutionCoverage
import app.gamenative.ui.screen.library.components.LibraryOptionsPanel
import app.gamenative.ui.theme.PluviaTheme
import java.util.EnumSet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CanonicalDiscoveryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun genresAreSearchableSelectableAndRemovableWithVisibleCounts() {
        var selected by mutableStateOf(emptySet<String>())

        composeRule.setContent {
            PluviaTheme {
                Surface {
                    LibraryOptionsPanel(
                        isOpen = true,
                        onDismiss = {},
                        selectedFilters = EnumSet.of(AppFilter.GAME),
                        onFilterChanged = {},
                        currentSortOption = SortOption.INSTALLED_FIRST,
                        onSortOptionChanged = {},
                        currentView = PaneType.GRID_HERO,
                        onViewChanged = {},
                        steamCollections = emptyList(),
                        selectedSteamCollectionIds = emptySet(),
                        steamCollectionCounts = emptyMap(),
                        skippedDynamicCollections = false,
                        isSteamConnected = false,
                        isOffline = false,
                        onSteamCollectionToggle = {},
                        onClearSteamCollections = {},
                        genreFacets = listOf(
                            GameFacet("steam:1", "Action"),
                            GameFacet("steam:2", "Strategy"),
                        ),
                        selectedGenreKeys = selected,
                        genreClassifiedCount = 1,
                        genreTotalCount = 2,
                        resultCount = if (selected.isEmpty()) 2 else 1,
                        onGenreToggle = { key ->
                            selected = selected.toMutableSet().apply {
                                if (!add(key)) remove(key)
                            }
                        },
                        onClearGenres = { selected = emptySet() },
                        steamMatchState = SteamMatchUiState(
                            coverage = SteamResolutionCoverage(resolved = 7, eligible = 10),
                        ),
                    )
                }
            }
        }

        composeRule.onNodeWithText("7 / 10 matched").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("genre-search").performScrollTo().performTextInput("strat")
        composeRule.onNodeWithText("Action").assertDoesNotExist()
        composeRule.onNodeWithText("Strategy").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithText("1 result").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("1 / 2 classified").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("genre-chip:steam:2").performScrollTo().assertIsDisplayed().performClick()

        composeRule.runOnIdle { assertEquals(emptySet<String>(), selected) }
    }
}
