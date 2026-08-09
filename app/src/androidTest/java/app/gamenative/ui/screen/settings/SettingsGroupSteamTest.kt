package app.gamenative.ui.screen.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.gamenative.ui.model.SteamWebApiKeySettingsState
import app.gamenative.ui.model.SteamWebApiKeyValidationState
import app.gamenative.ui.theme.PluviaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsGroupSteamTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun testMustSucceedBeforeTheEnteredKeyCanBeSaved() {
        var state by mutableStateOf(SteamWebApiKeySettingsState())
        var testedKey: String? = null
        var savedKey: String? = null
        composeRule.setContent {
            PluviaTheme {
                SettingsGroupSteam(
                    state = state,
                    onTest = { key ->
                        testedKey = key
                        state = state.copy(validation = SteamWebApiKeyValidationState.VALID)
                    },
                    onSave = { savedKey = it },
                    onDelete = {},
                    onClearFeedback = {
                        state = state.copy(validation = SteamWebApiKeyValidationState.UNTESTED)
                    },
                )
            }
        }

        composeRule.onNodeWithText("Steam Web API key").performClick()
        composeRule.onNodeWithTag("steam-web-api-key-input").performTextInput(KEY)
        composeRule.onNodeWithTag("steam-web-api-key-save").assertIsNotEnabled()
        composeRule.onNodeWithTag("steam-web-api-key-test").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("steam-web-api-key-save").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertEquals(KEY, testedKey)
            assertEquals(KEY, savedKey)
        }
    }

    @Test
    fun rejectedAndUnavailableValidationStatesAreVisibleAndCannotBeSaved() {
        var state by mutableStateOf(SteamWebApiKeySettingsState())
        composeRule.setContent {
            PluviaTheme {
                SettingsGroupSteam(
                    state = state,
                    onTest = {
                        state = state.copy(validation = SteamWebApiKeyValidationState.INVALID)
                    },
                    onSave = {},
                    onDelete = {},
                    onClearFeedback = {
                        state = state.copy(validation = SteamWebApiKeyValidationState.UNTESTED)
                    },
                )
            }
        }

        composeRule.onNodeWithText("Steam Web API key").performClick()
        composeRule.onNodeWithTag("steam-web-api-key-input").performTextInput(KEY)
        composeRule.onNodeWithTag("steam-web-api-key-test").performClick()

        composeRule.onNodeWithText("Steam rejected this key.").assertIsDisplayed()
        composeRule.onNodeWithTag("steam-web-api-key-save").assertIsNotEnabled()
        composeRule.runOnIdle {
            state = state.copy(validation = SteamWebApiKeyValidationState.UNAVAILABLE)
        }
        composeRule.onNodeWithText(
            "Could not test the key. Check the connection and try again.",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("steam-web-api-key-save").assertIsNotEnabled()
    }

    @Test
    fun failedStorageKeepsTheValidatedKeyEditorOpenForRetry() {
        var state by mutableStateOf(SteamWebApiKeySettingsState())
        composeRule.setContent {
            PluviaTheme {
                SettingsGroupSteam(
                    state = state,
                    onTest = {
                        state = state.copy(validation = SteamWebApiKeyValidationState.VALID)
                    },
                    onSave = {
                        state = state.copy(operationFailed = true)
                    },
                    onDelete = {},
                    onClearFeedback = {
                        state = state.copy(
                            validation = SteamWebApiKeyValidationState.UNTESTED,
                            operationFailed = false,
                        )
                    },
                )
            }
        }

        composeRule.onNodeWithText("Steam Web API key").performClick()
        composeRule.onNodeWithTag("steam-web-api-key-input").performTextInput(KEY)
        composeRule.onNodeWithTag("steam-web-api-key-test").performClick()
        composeRule.onNodeWithTag("steam-web-api-key-save").performClick()

        composeRule.onNodeWithTag("steam-web-api-key-input").assertIsDisplayed()
        composeRule.onNodeWithText("The key could not be stored.").assertIsDisplayed()
    }

    private companion object {
        const val KEY = "0123456789abcdef0123456789ABCDEF"
    }
}
