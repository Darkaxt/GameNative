package app.gamenative.ui.screen.library

import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToString
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.library.canonical.CanonicalCardKey
import app.gamenative.library.canonical.CanonicalDiagnosticSink
import app.gamenative.library.canonical.CanonicalLibraryDiagnosticSink
import app.gamenative.library.canonical.CanonicalPublicLibraryGate
import app.gamenative.library.canonical.CanonicalLibraryCard
import app.gamenative.library.canonical.CopyUnavailableReason
import app.gamenative.library.canonical.OwnedCopyOperation
import app.gamenative.library.canonical.OwnedCopySummary
import app.gamenative.library.canonical.action.ActionFailureReason
import app.gamenative.library.canonical.action.ActionSelectionPolicy
import app.gamenative.library.canonical.action.OwnedCopyActionGuard
import app.gamenative.library.canonical.action.OwnedCopyRouteResult
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeAdapter
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeRegistry
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeResult
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import app.gamenative.ui.data.LibraryCard
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.model.CanonicalCopyChangeResult
import app.gamenative.ui.screen.library.components.AppItem
import app.gamenative.ui.screen.library.components.CanonicalCopiesFeedback
import app.gamenative.ui.screen.library.components.CanonicalCopiesSheet
import app.gamenative.ui.screen.library.components.OwnedSourceBadges
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.SnackbarManager
import java.lang.reflect.Proxy
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class CanonicalLibraryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun canonicalCardShowsDeterministicAccessibleBadgesAndCopiesAction() {
        val card = canonicalCard()
        var copiesOpened = false

        composeRule.setContent {
            PluviaTheme {
                AppItem(
                    card = LibraryCard.canonical(
                        key = card.key,
                        index = 0,
                        name = card.displayName,
                        ownedSources = card.ownedSources,
                    ),
                    onClick = {},
                    onCopies = { copiesOpened = true },
                    paneType = PaneType.LIST,
                )
            }
        }

        composeRule.onAllNodesWithTag("canonical-card").assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("Owned on Steam").assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("Owned on GOG").assertCountEquals(1)
        val copiesBadges = composeRule.onNode(
            hasTestTag("copies-action") and
                hasAnyDescendant(hasTestTag("owned-source-badges")),
            useUnmergedTree = true,
        )
        copiesBadges.assertHasClickAction().performClick()
        composeRule.runOnIdle { assertTrue(copiesOpened) }
    }

    @Test
    fun badgesUseFixedSteamGogEpicAmazonCustomOrder() {
        composeRule.setContent {
            PluviaTheme {
                OwnedSourceBadges(
                    sources = listOf(
                        GameSource.CUSTOM_GAME,
                        GameSource.AMAZON,
                        GameSource.EPIC,
                        GameSource.GOG,
                        GameSource.STEAM,
                    ),
                )
            }
        }

        val leftEdges = listOf("STEAM", "GOG", "EPIC", "AMAZON", "CUSTOM_GAME").map { source ->
            composeRule.onNodeWithTag("owned-source-badge:$source").fetchSemanticsNode().boundsInRoot.left
        }
        assertEquals(leftEdges.sorted(), leftEdges)
        composeRule.onNodeWithTag("owned-source-badges").assertIsDisplayed()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun copiesSheetShowsCapabilitiesUnavailableRowsPreferenceAndSafeSeparateControls() {
        val candidateMarker = 987654321
        val resolverMarker = 246813579
        val initialCard = canonicalCard()
        val card = initialCard.copy(
            copies = initialCard.copies.mapIndexed { index, copy ->
                if (index == 0) {
                    copy.copy(
                        decisionCandidateSteamAppId = candidateMarker,
                        decisionResolverVersion = resolverMarker,
                    )
                } else {
                    copy
                }
            },
        )
        val operations = mutableListOf<Triple<GameSource, OwnedCopyOperation, Boolean>>()
        var automaticSelections = 0
        var separateSelections = 0
        var fixSelections = 0

        composeRule.setContent {
            PluviaTheme {
                CanonicalCopiesSheet(
                    card = card,
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    onDismissRequest = {},
                    onOperation = { copy, operation, rememberChoice ->
                        operations += Triple(copy.source, operation, rememberChoice)
                    },
                    onUseAutomaticSelection = { automaticSelections += 1 },
                    onSeparateCopy = { separateSelections += 1 },
                    onResetDecision = {},
                    onFixSteamMatch = { fixSelections += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("copies-sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("copy-row:STEAM").assertIsDisplayed()
        composeRule.onNodeWithTag("copy-row:GOG").assertIsDisplayed()
        composeRule.onNodeWithTag("preferred-copy").assertIsDisplayed()
        composeRule.onNodeWithTag("copy-operation:STEAM:PLAY").assertIsEnabled()
        composeRule.onNodeWithTag("copy-operation:STEAM:INSTALL").assertDoesNotExist()
        composeRule.onNodeWithTag("copy-operation:GOG:PLAY", useUnmergedTree = true).assertIsNotEnabled()
        composeRule.onNodeWithTag("fix-steam-match:STEAM").assertDoesNotExist()
        composeRule.onNodeWithTag("fix-steam-match:GOG").assertHasClickAction().performClick()
        composeRule.onNodeWithText("Steam-owned match").assertIsDisplayed()
        composeRule.onNodeWithTag("separate-copy").assertDoesNotExist()

        val rememberToggle = composeRule.onNode(
            hasTestTag("remember-copy:STEAM") and hasText("Always use this copy"),
        )
        rememberToggle.assertHasClickAction().assertIsOff().performClick().assertIsOn()
        composeRule.onNodeWithTag("copy-operation:STEAM:PLAY").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(Triple(GameSource.STEAM, OwnedCopyOperation.PLAY, true)), operations)
        }

        composeRule.onNodeWithText("Use automatic selection").performClick()
        composeRule.runOnIdle { assertEquals(1, automaticSelections) }

        val sheetSemantics = composeRule.onNodeWithTag("copies-sheet").printToString()
        assertFalse(sheetSemantics.contains(accountScope.value))
        assertFalse(sheetSemantics.contains(steamKey.stableSourceId))
        assertFalse(sheetSemantics.contains(candidateMarker.toString()))
        assertFalse(sheetSemantics.contains(resolverMarker.toString()))
        assertEquals(0, separateSelections)
        assertEquals(1, fixSelections)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun legacyBridgeUnsupportedCopyStillOffersSeparateWhenMatchAuthorityAllowsIt() {
        val unsupported = copy(
            key = epicKey,
            capabilities = emptySet(),
            unavailable = CopyUnavailableReason.LEGACY_BRIDGE_UNSUPPORTED,
            canSeparate = true,
        )
        val card = canonicalCard().copy(
            copies = listOf(copy(steamKey), unsupported),
            ownedSources = setOf(GameSource.STEAM, GameSource.EPIC),
            preferredCopy = null,
        )

        composeRule.setContent {
            PluviaTheme {
                CanonicalCopiesSheet(
                    card = card,
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    onDismissRequest = {},
                    onOperation = { _, _, _ -> },
                    onUseAutomaticSelection = {},
                    onSeparateCopy = {},
                    onResetDecision = {},
                )
            }
        }

        composeRule.onNodeWithTag("copy-row:EPIC").assertIsDisplayed()
        composeRule.onNodeWithTag("separate-copy").assertHasClickAction()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun staleSourceReadFailureDoesNotOfferSeparateEvenIfMatchWasPreviouslySeparable() {
        val stale = copy(
            key = epicKey,
            capabilities = emptySet(),
            unavailable = CopyUnavailableReason.SOURCE_READ_FAILED,
            canSeparate = true,
        )
        val card = canonicalCard().copy(
            copies = listOf(copy(steamKey), stale),
            ownedSources = setOf(GameSource.STEAM, GameSource.EPIC),
            preferredCopy = null,
        )

        composeRule.setContent {
            PluviaTheme {
                CanonicalCopiesSheet(
                    card = card,
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    onDismissRequest = {},
                    onOperation = { _, _, _ -> },
                    onUseAutomaticSelection = {},
                    onSeparateCopy = {},
                    onResetDecision = {},
                )
            }
        }

        composeRule.onNodeWithTag("copy-row:EPIC").assertIsDisplayed()
        composeRule.onNodeWithTag("separate-copy").assertDoesNotExist()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun availableNonSteamGroupedCopyCanBeSeparatedAndFixedFailureKeepsSheetVisible() {
        val separable = copy(
            key = epicKey,
            capabilities = setOf(OwnedCopyOperation.OPEN_SOURCE_DETAILS),
            canSeparate = true,
        )
        val card = canonicalCard().copy(
            copies = listOf(copy(steamKey), separable),
            ownedSources = setOf(GameSource.STEAM, GameSource.EPIC),
            preferredCopy = null,
        )
        var selectedKey: OwnedCopyKey? = null

        composeRule.setContent {
            PluviaTheme {
                CanonicalCopiesSheet(
                    card = card,
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    onDismissRequest = {},
                    onOperation = { _, _, _ -> },
                    onUseAutomaticSelection = {},
                    onSeparateCopy = { selectedKey = it.key },
                    onResetDecision = {},
                    feedback = CanonicalCopiesFeedback.MUTATION_FAILED,
                )
            }
        }

        composeRule.onNodeWithTag("separate-copy").performClick()
        composeRule.onNodeWithText("Separate copy?").assertIsDisplayed()
        composeRule.onNodeWithText("Separate").performClick()
        composeRule.runOnIdle { assertEquals(epicKey, selectedKey) }
        composeRule.onNodeWithTag("copies-sheet").assertIsDisplayed()
        composeRule.onNodeWithText("Could not change copy grouping. Try again.").assertIsDisplayed()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun canonicalSelectionOpensNativeDetailBeforeSourceRouter() {
        val soleKey = OwnedCopyKey(accountScope, GameSource.STEAM, "42")
        val canonical = canonicalCard(
            id = CanonicalGameId.parse("77777777-7777-7777-7777-777777777777"),
            title = "Sole routed card",
            keys = listOf(soleKey),
        )
        val captured = mutableListOf<List<Any?>>()
        val guard = actionGuard(soleKey)
        setLibraryScreen(
            state = LibraryState(
                cards = listOf(presentation(canonical, 0)),
                canonicalSnapshotRevision = 1L,
            ),
            canonicalCards = mapOf(canonical.key to canonical),
            onRoute = { key, operation, explicitKey, rememberChoice ->
                captured += listOf(key, operation, explicitKey, rememberChoice)
                OwnedCopyRouteResult.Ready(guard, ActionSelectionPolicy.SOLE_COPY)
            },
        )

        composeRule.onNodeWithTag("canonical-card").performClick()
        composeRule.onNodeWithTag("canonical-detail-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("canonical-detail-source-details").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(captured.isEmpty()) }

        composeRule.onNodeWithTag("canonical-detail-source-details").performClick()
        composeRule.onNodeWithTag("copies-action-detail").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    listOf(
                        canonical.key,
                        OwnedCopyOperation.OPEN_SOURCE_DETAILS,
                        null,
                        false,
                    ),
                ),
                captured,
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun openCopiesSheetReactsToAuthoritativeRevisionButNotFilteredPresentation() {
        val initial = canonicalCard()
        var screenState by mutableStateOf(
            LibraryState(
                cards = listOf(presentation(initial, 0)),
                canonicalSnapshotRevision = 1L,
            ),
        )
        var canonicalCards by mutableStateOf(mapOf(initial.key to initial))
        setLibraryScreen(
            state = { screenState },
            canonicalCards = { canonicalCards },
            onRoute = { _, _, _, _ ->
                OwnedCopyRouteResult.Unavailable(ActionFailureReason.COPY_UNAVAILABLE)
            },
        )

        composeRule.onNodeWithTag("copies-action").performClick()
        composeRule.onNodeWithTag("copies-sheet").assertIsDisplayed()

        composeRule.runOnIdle {
            screenState = screenState.copy(cards = emptyList())
        }
        composeRule.onNodeWithTag("copies-sheet").assertIsDisplayed()

        composeRule.runOnIdle {
            val refreshed = initial.copy(
                copies = initial.copies.map { copy ->
                    if (copy.key == steamKey) {
                        copy.copy(branchOrVersion = "fresh-runtime-version")
                    } else {
                        copy
                    }
                },
            )
            canonicalCards = mapOf(refreshed.key to refreshed)
            screenState = screenState.copy(canonicalSnapshotRevision = 2L)
        }
        composeRule.onNodeWithText("Branch or version: fresh-runtime-version").fetchSemanticsNode()

        composeRule.runOnIdle {
            canonicalCards = emptyMap()
            screenState = screenState.copy(canonicalSnapshotRevision = 3L)
        }
        composeRule.onNodeWithTag("copies-sheet").assertDoesNotExist()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun closingCopiesAfterReorderRestoresFocusByCardIdentity() {
        val first = canonicalCard(
            id = CanonicalGameId.parse("55555555-5555-5555-5555-555555555555"),
            title = "First focus card",
            keys = listOf(steamKey, gogKey),
        )
        val second = canonicalCard(
            id = CanonicalGameId.parse("66666666-6666-6666-6666-666666666666"),
            title = "Second focus card",
            keys = listOf(epicKey, steamKey),
        )
        var screenState by mutableStateOf(
            LibraryState(
                cards = listOf(presentation(first, 0), presentation(second, 1)),
                canonicalSnapshotRevision = 1L,
            ),
        )
        val cards = mapOf(first.key to first, second.key to second)
        setLibraryScreen(
            state = { screenState },
            canonicalCards = { cards },
            onRoute = { _, _, _, _ ->
                OwnedCopyRouteResult.Unavailable(ActionFailureReason.COPY_UNAVAILABLE)
            },
        )

        val originCopies = composeRule.onAllNodesWithTag("copies-action")[1]
        originCopies.performSemanticsAction(SemanticsActions.RequestFocus)
        originCopies.assertIsFocused().performKeyInput { pressKey(Key.ButtonA) }
        composeRule.onNodeWithTag("copies-sheet").assertIsDisplayed()
        composeRule.runOnIdle {
            screenState = screenState.copy(
                cards = listOf(presentation(second, 0), presentation(first, 1)),
            )
        }
        pressBack()
        composeRule.onNodeWithTag("copies-sheet").assertDoesNotExist()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runCatching {
                composeRule.onAllNodesWithTag("copies-action")[0]
                    .fetchSemanticsNode().config[
                        androidx.compose.ui.semantics.SemanticsProperties.Focused
                    ]
            }.getOrDefault(false)
        }
        composeRule.onAllNodesWithTag("copies-action")[0].assertIsFocused()
        composeRule.onNodeWithText("Second focus card").assertIsDisplayed()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun secondCanonicalSourceRequestSupersedesBlockedFirstRoute() {
        val first = canonicalCard(
            id = CanonicalGameId.parse("33333333-3333-3333-3333-333333333333"),
            title = "Blocked card",
            keys = listOf(steamKey, gogKey),
        )
        val second = canonicalCard(
            id = CanonicalGameId.parse("44444444-4444-4444-4444-444444444444"),
            title = "Winning card",
            keys = listOf(epicKey, steamKey),
        )
        val routeStarted = CompletableDeferred<Unit>()
        val releaseRoute = CompletableDeferred<Unit>()
        val cards = mapOf(first.key to first, second.key to second)
        setLibraryScreen(
            state = LibraryState(
                cards = listOf(presentation(first, 0), presentation(second, 1)),
                canonicalSnapshotRevision = 1L,
            ),
            canonicalCards = cards,
            onRoute = { key, _, _, _ ->
                if (key == first.key) {
                    routeStarted.complete(Unit)
                    releaseRoute.await()
                }
                OwnedCopyRouteResult.NeedsChooser(
                    canonicalCard(key, cards).copies.map(OwnedCopySummary::key),
                )
            },
        )

        composeRule.onAllNodesWithTag("canonical-card")[0].performClick()
        composeRule.onNodeWithTag("canonical-detail-source-details").performClick()
        composeRule.waitUntil { routeStarted.isCompleted }
        pressBack()
        composeRule.onAllNodesWithTag("canonical-card")[1].performClick()
        composeRule.onNodeWithTag("canonical-detail-source-details").performClick()
        composeRule.onNode(
            hasTestTag("copies-sheet") and hasAnyDescendant(hasText("Winning card")),
            useUnmergedTree = true,
        ).assertIsDisplayed()

        composeRule.runOnIdle { releaseRoute.complete(Unit) }
        composeRule.waitForIdle()
        composeRule.onNode(
            hasTestTag("copies-sheet") and hasAnyDescendant(hasText("Winning card")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun backCancelsBlockedSourceRouteBeforeOpeningOtherCopies() {
        val firstId = CanonicalGameId.parse("11111111-1111-1111-1111-111111111111")
        val secondId = CanonicalGameId.parse("22222222-2222-2222-2222-222222222222")
        val first = canonicalCard(
            id = firstId,
            title = "First card",
            keys = listOf(steamKey, gogKey),
        )
        val second = canonicalCard(
            id = secondId,
            title = "Second card",
            keys = listOf(epicKey, steamKey),
        )
        val routeStarted = CompletableDeferred<Unit>()
        val routeCancelled = CompletableDeferred<Unit>()
        val releaseRoute = CompletableDeferred<Unit>()
        val routeKeys = mutableListOf<CanonicalCardKey>()
        setLibraryScreen(
            state = LibraryState(
                cards = listOf(
                    presentation(first, 0),
                    presentation(second, 1),
                ),
                canonicalSnapshotRevision = 1L,
            ),
            canonicalCards = mapOf(first.key to first, second.key to second),
            onRoute = { key, _, _, _ ->
                routeKeys += key
                if (key == first.key) {
                    routeStarted.complete(Unit)
                    try {
                        releaseRoute.await()
                    } finally {
                        routeCancelled.complete(Unit)
                    }
                }
                OwnedCopyRouteResult.NeedsChooser(
                    canonicalCard(key = key, cards = mapOf(first.key to first, second.key to second))
                        .copies.map(OwnedCopySummary::key),
                )
            },
        )

        composeRule.onAllNodesWithTag("canonical-card")[0].performClick()
        composeRule.onNodeWithTag("canonical-detail-source-details").performClick()
        composeRule.waitUntil { routeStarted.isCompleted }
        pressBack()
        composeRule.onAllNodesWithTag("copies-action")[1].performClick()
        composeRule.waitUntil { routeCancelled.isCompleted }

        composeRule.onNode(
            hasTestTag("copies-sheet") and hasAnyDescendant(hasText("Second card")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(listOf(first.key), routeKeys)
            releaseRoute.complete(Unit)
        }
        composeRule.waitForIdle()
        composeRule.onNode(
            hasTestTag("copies-sheet") and hasAnyDescendant(hasText("Second card")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun gamepadTraversesCopiesOperationsAndBackRestoresReorderedCardIdentity() {
        val first = canonicalCard(
            id = CanonicalGameId.parse("88888888-8888-8888-8888-888888888888"),
            title = "First gamepad card",
            keys = listOf(gogKey, steamKey),
        )
        val second = canonicalCard(
            id = CanonicalGameId.parse("99999999-9999-9999-9999-999999999999"),
            title = "Origin gamepad card",
            keys = listOf(steamKey, epicKey),
        ).withPlayableSteamCopy()
        var screenState by mutableStateOf(
            LibraryState(
                cards = listOf(presentation(first, 0), presentation(second, 1)),
                canonicalSnapshotRevision = 1L,
            ),
        )
        val cards = mapOf(first.key to first, second.key to second)
        setLibraryScreen(
            state = { screenState },
            canonicalCards = { cards },
            onRoute = { _, _, _, _ ->
                OwnedCopyRouteResult.Unavailable(ActionFailureReason.COPY_UNAVAILABLE)
            },
        )

        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        val originCard = composeRule.onAllNodesWithTag("canonical-card")[1].onChild()
        originCard.assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        val originCopies = composeRule.onAllNodesWithTag("copies-action")[1]
        originCopies.assertIsFocused().performKeyInput { pressKey(Key.ButtonA) }

        val copiesSheet = composeRule.onNodeWithTag("copies-sheet").assertIsDisplayed()
        val firstOperation = composeRule.onNodeWithTag("copy-operation:STEAM:PLAY")
        fun firstOperationIsFocused(): Boolean = runCatching {
            firstOperation.fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.Focused
            ]
        }.getOrDefault(false)
        repeat(6) {
            if (!firstOperationIsFocused()) {
                copiesSheet.performKeyInput { pressKey(Key.DirectionDown) }
            }
        }
        firstOperation.assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("copy-operation:STEAM:OPEN_SOURCE_DETAILS").assertIsFocused()

        composeRule.runOnIdle {
            screenState = screenState.copy(
                cards = listOf(presentation(second, 0), presentation(first, 1)),
            )
        }
        pressBack()
        composeRule.onNodeWithTag("copies-sheet").assertDoesNotExist()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runCatching {
                composeRule.onAllNodesWithTag("copies-action")[0]
                    .fetchSemanticsNode().config[
                        androidx.compose.ui.semantics.SemanticsProperties.Focused
                    ]
            }.getOrDefault(false)
        }
        composeRule.onAllNodesWithTag("copies-action")[0].assertIsFocused()
        composeRule.onNodeWithText("Origin gamepad card").assertIsDisplayed()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun gridHeroGamepadEntersCopiesAndReturnsToOrigin() {
        assertAlternateLayoutGamepadCopiesRoute(PaneType.GRID_HERO)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun gridCapsuleGamepadEntersCopiesAndReturnsToOrigin() {
        assertAlternateLayoutGamepadCopiesRoute(PaneType.GRID_CAPSULE)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun carouselGamepadEntersCopiesWithoutReplacingHorizontalNavigation() {
        assertAlternateLayoutGamepadCopiesRoute(PaneType.CAROUSEL)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun assertAlternateLayoutGamepadCopiesRoute(paneType: PaneType) {
        val origin = canonicalCard(
            id = CanonicalGameId.parse("dddddddd-dddd-dddd-dddd-dddddddddddd"),
            title = "Alternate layout origin",
            keys = listOf(steamKey, epicKey),
        ).withPlayableSteamCopy()
        val neighbor = canonicalCard(
            id = CanonicalGameId.parse("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"),
            title = "Alternate layout neighbor",
            keys = listOf(gogKey, steamKey),
        )
        val cards = mapOf(origin.key to origin, neighbor.key to neighbor)
        setLibraryScreen(
            state = {
                LibraryState(
                    cards = listOf(presentation(origin, 0), presentation(neighbor, 1)),
                    canonicalSnapshotRevision = 1L,
                )
            },
            canonicalCards = { cards },
            onRoute = { _, _, _, _ ->
                OwnedCopyRouteResult.Unavailable(ActionFailureReason.COPY_UNAVAILABLE)
            },
            paneType = paneType,
        )

        val originCard = composeRule.onNode(
            hasTestTag("canonical-card") and hasAnyDescendant(hasText("Alternate layout origin")),
        ).onChild()
        originCard.performSemanticsAction(SemanticsActions.RequestFocus)
        originCard.assertIsFocused()
        if (paneType == PaneType.CAROUSEL) {
            val neighborCard = composeRule.onNode(
                hasTestTag("canonical-card") and hasAnyDescendant(hasText("Alternate layout neighbor")),
            ).onChild()
            val rootCenterX = nodeCenterX(composeRule.onRoot())
            val originStartX = nodeCenterX(originCard)
            val neighborDistanceBefore = abs(nodeCenterX(neighborCard) - rootCenterX)

            originCard.performKeyInput { pressKey(Key.DirectionRight) }
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                abs(nodeCenterX(neighborCard) - rootCenterX) < neighborDistanceBefore
            }
            neighborCard.performSemanticsAction(SemanticsActions.RequestFocus)
            neighborCard.assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                abs(nodeCenterX(originCard) - originStartX) < 2f
            }
            originCard.performSemanticsAction(SemanticsActions.RequestFocus)
        }

        originCard.assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        val originCopies = composeRule.onNode(hasTestTag("copies-action") and isFocused())
        originCopies.assertIsFocused().performKeyInput { pressKey(Key.ButtonA) }

        val copiesSheet = composeRule.onNodeWithTag("copies-sheet").assertIsDisplayed()
        val firstOperation = composeRule.onNodeWithTag("copy-operation:STEAM:PLAY")
        repeat(6) {
            if (!nodeIsFocused(firstOperation)) {
                copiesSheet.performKeyInput { pressKey(Key.DirectionDown) }
            }
        }
        firstOperation.assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("copy-operation:STEAM:OPEN_SOURCE_DETAILS").assertIsFocused()

        pressBack()
        composeRule.onNodeWithTag("copies-sheet").assertDoesNotExist()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runCatching {
                composeRule.onNode(hasTestTag("copies-action") and isFocused())
                    .fetchSemanticsNode()
            }.isSuccess
        }
        val restoredCopies = composeRule.onNode(hasTestTag("copies-action") and isFocused())
        restoredCopies.assertIsFocused().performKeyInput { pressKey(Key.ButtonA) }
        composeRule.onNode(
            hasTestTag("copies-sheet") and hasAnyDescendant(hasText("Alternate layout origin")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }

    private fun nodeCenterX(node: androidx.compose.ui.test.SemanticsNodeInteraction): Float {
        val bounds = node.fetchSemanticsNode().boundsInRoot
        return (bounds.left + bounds.right) / 2f
    }

    private fun nodeIsFocused(node: androidx.compose.ui.test.SemanticsNodeInteraction): Boolean =
        runCatching {
            node.fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.Focused
            ]
        }.getOrDefault(false)

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun resetTransactionFailureKeepsProductionSheetOpenWithFixedFeedback() {
        val rejectedCopy = copy(gogKey).copy(
            matchMethod = MatchMethod.MANUAL,
            confidence = MatchConfidence.REJECTED,
            decisionSource = MatchDecisionSource.USER,
            decisionRevision = 321L,
        )
        val grouped = canonicalCard().copy(
            copies = listOf(rejectedCopy),
            ownedSources = setOf(GameSource.GOG),
            preferredCopy = null,
        )
        setLibraryScreen(
            state = {
                LibraryState(
                    cards = listOf(presentation(grouped, 0)),
                    canonicalSnapshotRevision = 1L,
                )
            },
            canonicalCards = { mapOf(grouped.key to grouped) },
            onRoute = { _, _, _, _ ->
                OwnedCopyRouteResult.Unavailable(ActionFailureReason.COPY_UNAVAILABLE)
            },
            onResetCanonicalDecision = { _, _ ->
                CanonicalCopyChangeResult.TRANSACTION_FAILED
            },
        )

        composeRule.onNodeWithTag("copies-action").performClick()
        composeRule.onNodeWithTag("reset-match-decision").performClick()

        composeRule.onNodeWithTag("copies-sheet").assertIsDisplayed()
        composeRule.onNodeWithText("Could not change copy grouping. Try again.").assertIsDisplayed()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun routeFailureWithAuthoritativeRemovalClearsDetailAndSheetAndEmitsFixedFeedback() {
        val numericSteamKey = OwnedCopyKey(accountScope, GameSource.STEAM, "42")
        val card = canonicalCard(
            id = CanonicalGameId.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            title = "Removed during route",
            keys = listOf(numericSteamKey, gogKey),
        ).withPlayableSteamCopy()
        var screenState by mutableStateOf(
            LibraryState(
                cards = listOf(presentation(card, 0)),
                canonicalSnapshotRevision = 1L,
            ),
        )
        var canonicalCards by mutableStateOf(mapOf(card.key to card))
        val feedbackMessages = mutableListOf<String>()
        setLibraryScreen(
            state = { screenState },
            canonicalCards = { canonicalCards },
            onRoute = { _, _, _, _ ->
                canonicalCards = emptyMap()
                screenState = screenState.copy(canonicalSnapshotRevision = 2L)
                OwnedCopyRouteResult.Unavailable(ActionFailureReason.COPY_UNAVAILABLE)
            },
            onLibraryFeedback = feedbackMessages::add,
        )

        composeRule.onNodeWithTag("canonical-card").performClick()
        composeRule.onNodeWithTag("canonical-detail-copies").performClick()
        composeRule.onNodeWithTag("copy-operation:STEAM:PLAY").performClick()

        composeRule.onNodeWithTag("copies-sheet").assertDoesNotExist()
        composeRule.onNodeWithTag("canonical-detail-screen").assertDoesNotExist()
        composeRule.onNodeWithTag("canonical-card").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            "This copy changed or is no longer available. Refresh and try again." in feedbackMessages
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun selectedDetailRemovalClearsCapturedActionStateAndReturnsToLibrary() {
        val numericSteamKey = OwnedCopyKey(accountScope, GameSource.STEAM, "43")
        val card = canonicalCard(
            id = CanonicalGameId.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
            title = "Removed selected detail",
            keys = listOf(numericSteamKey, gogKey),
        ).withPlayableSteamCopy()
        var screenState by mutableStateOf(
            LibraryState(
                cards = listOf(presentation(card, 0)),
                canonicalSnapshotRevision = 1L,
            ),
        )
        var canonicalCards by mutableStateOf(mapOf(card.key to card))
        setLibraryScreen(
            state = { screenState },
            canonicalCards = { canonicalCards },
            onRoute = { _, operation, _, _ ->
                OwnedCopyRouteResult.Ready(
                    actionGuard(numericSteamKey),
                    if (operation == OwnedCopyOperation.PLAY) {
                        ActionSelectionPolicy.EXPLICIT
                    } else {
                        ActionSelectionPolicy.PREFERRED
                    },
                )
            },
        )

        composeRule.onNodeWithTag("copies-action").performClick()
        composeRule.onNodeWithTag("copy-operation:STEAM:PLAY").performClick()
        composeRule.onNodeWithTag("copies-action-detail").assertIsDisplayed()

        composeRule.runOnIdle {
            canonicalCards = emptyMap()
            screenState = screenState.copy(canonicalSnapshotRevision = 2L)
        }

        composeRule.onNodeWithTag("copies-action-detail").assertDoesNotExist()
        composeRule.onNodeWithTag("copies-sheet").assertDoesNotExist()
        composeRule.onNodeWithTag("canonical-card").assertIsDisplayed()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun backOverCanonicalDetailAndCopiesSheetClosesOnlySheetFirst() {
        val numericSteamKey = OwnedCopyKey(accountScope, GameSource.STEAM, "44")
        val card = canonicalCard(
            id = CanonicalGameId.parse("cccccccc-cccc-cccc-cccc-cccccccccccc"),
            title = "Detail under sheet",
            keys = listOf(numericSteamKey, gogKey),
        )
        setLibraryScreen(
            state = LibraryState(
                cards = listOf(presentation(card, 0)),
                canonicalSnapshotRevision = 1L,
            ),
            canonicalCards = mapOf(card.key to card),
            onRoute = { _, _, _, _ ->
                OwnedCopyRouteResult.Ready(
                    actionGuard(numericSteamKey),
                    ActionSelectionPolicy.PREFERRED,
                )
            },
        )

        composeRule.onNodeWithTag("canonical-card").performClick()
        composeRule.onNodeWithTag("canonical-detail-copies").performClick()
        composeRule.onNodeWithTag("copies-sheet").assertIsDisplayed()

        pressBack()

        composeRule.onNodeWithTag("copies-sheet").assertDoesNotExist()
        composeRule.onNodeWithTag("canonical-detail-screen").assertIsDisplayed()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun setLibraryScreen(
        state: LibraryState,
        canonicalCards: Map<CanonicalCardKey, CanonicalLibraryCard>,
        onRoute: suspend (
            CanonicalCardKey,
            OwnedCopyOperation,
            OwnedCopyKey?,
            Boolean,
        ) -> OwnedCopyRouteResult,
    ) = setLibraryScreen(
        state = { state },
        canonicalCards = { canonicalCards },
        onRoute = onRoute,
    )

    @OptIn(ExperimentalMaterial3Api::class)
    private fun setLibraryScreen(
        state: () -> LibraryState,
        canonicalCards: () -> Map<CanonicalCardKey, CanonicalLibraryCard>,
        onRoute: suspend (
            CanonicalCardKey,
            OwnedCopyOperation,
            OwnedCopyKey?,
            Boolean,
        ) -> OwnedCopyRouteResult,
        onUseAutomaticCopySelection: suspend (CanonicalCardKey) -> CanonicalCopyChangeResult = {
            CanonicalCopyChangeResult.INVALID_REQUEST
        },
        onSeparateCanonicalCopy: suspend (
            CanonicalCardKey,
            OwnedCopyKey,
        ) -> CanonicalCopyChangeResult = { _, _ -> CanonicalCopyChangeResult.INVALID_REQUEST },
        onResetCanonicalDecision: suspend (
            CanonicalCardKey,
            OwnedCopyKey,
        ) -> CanonicalCopyChangeResult = { _, _ -> CanonicalCopyChangeResult.INVALID_REQUEST },
        onLibraryFeedback: (String) -> Unit = {},
        paneType: PaneType = PaneType.LIST,
    ) {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        PrefManager.init(targetContext)
        PrefManager.libraryLayout = paneType
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            PrefManager.libraryLayout == paneType
        }
        composeRule.setContent {
            val inputModeManager = LocalInputModeManager.current
            LaunchedEffect(Unit) {
                inputModeManager.requestInputMode(InputMode.Keyboard)
                SnackbarManager.messages.collect { message -> onLibraryFeedback(message) }
            }
            PluviaTheme {
                LibraryScreenContent(
                    state = state(),
                    listState = rememberLazyGridState(),
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    onFilterChanged = {},
                    onPageChange = {},
                    onModalBottomSheet = {},
                    onIsSearching = {},
                    onSearchQuery = {},
                    onClickPlay = { _, _ -> },
                    onTestGraphics = {},
                    onPlayWithDiagnostics = {},
                    onRefresh = {},
                    onNavigateRoute = {},
                    onLogout = {},
                    onGoOnline = {},
                    onSourceToggle = {},
                    onAddCustomGameFolder = {},
                    onSortOptionChanged = {},
                    onSteamCollectionToggle = {},
                    onClearSteamCollections = {},
                    onOptionsPanelToggle = {},
                    onTabChanged = {},
                    onPreviousTab = {},
                    onNextTab = {},
                    canonicalCard = { key -> canonicalCards()[key] },
                    onRouteCanonicalAction = onRoute,
                    onUseAutomaticCopySelection = onUseAutomaticCopySelection,
                    onSeparateCanonicalCopy = onSeparateCanonicalCopy,
                    onResetCanonicalDecision = onResetCanonicalDecision,
                )
            }
        }
    }

    private fun actionGuard(key: OwnedCopyKey): OwnedCopyActionGuard {
        val adapters = GameSource.entries.mapTo(linkedSetOf()) { source ->
            object : OwnedCopyRuntimeAdapter {
                override val source: GameSource = source

                override fun invalidations(): Flow<Unit> = emptyFlow()

                override suspend fun resolve(key: OwnedCopyKey): OwnedCopyRuntimeResult =
                    OwnedCopyRuntimeResult.Hidden

                override suspend fun resolveAll(
                    keys: Set<OwnedCopyKey>,
                ): Map<OwnedCopyKey, OwnedCopyRuntimeResult> =
                    keys.associateWith { OwnedCopyRuntimeResult.Hidden }
            }
        }
        val playHistoryDao = Proxy.newProxyInstance(
            LibraryPlayHistoryDao::class.java.classLoader,
            arrayOf(LibraryPlayHistoryDao::class.java),
        ) { _, method, _ ->
            if (method.name == "getAll") emptyFlow<Any>() else null
        } as LibraryPlayHistoryDao
        val diagnostics = Proxy.newProxyInstance(
            CanonicalDiagnosticSink::class.java.classLoader,
            arrayOf(CanonicalDiagnosticSink::class.java),
        ) { _, _, _ -> null } as CanonicalDiagnosticSink
        val libraryDiagnostics = Proxy.newProxyInstance(
            CanonicalLibraryDiagnosticSink::class.java.classLoader,
            arrayOf(CanonicalLibraryDiagnosticSink::class.java),
        ) { _, _, _ -> null } as CanonicalLibraryDiagnosticSink
        val registry = OwnedCopyRuntimeRegistry(adapters, playHistoryDao, diagnostics)
        val appId = "${key.source.name}_${key.stableSourceId}"
        return OwnedCopyActionGuard(
            key = key,
            capturedReference = SourceOwnedCopyReference.Steam(key, key.stableSourceId.toInt()),
            initialLibraryItem = LibraryItem(
                appId = appId,
                name = "Captured source copy",
                gameSource = key.source,
            ),
            runtimeRegistry = registry,
            publicGate = CanonicalPublicLibraryGate { true },
            diagnostics = libraryDiagnostics,
        )
    }

    private fun CanonicalLibraryCard.withPlayableSteamCopy(): CanonicalLibraryCard = copy(
        copies = copies.map { copy ->
            if (copy.source == GameSource.STEAM) {
                copy.copy(
                    capabilities = setOf(
                        OwnedCopyOperation.PLAY,
                        OwnedCopyOperation.OPEN_SOURCE_DETAILS,
                    ),
                )
            } else {
                copy
            }
        },
    )

    private fun presentation(card: CanonicalLibraryCard, index: Int): LibraryCard =
        LibraryCard.canonical(
            key = card.key,
            index = index,
            name = card.displayName,
            ownedSources = card.ownedSources,
        )

    private fun canonicalCard(
        key: CanonicalCardKey,
        cards: Map<CanonicalCardKey, CanonicalLibraryCard>,
    ): CanonicalLibraryCard = requireNotNull(cards[key])

    private fun canonicalCard(
        id: CanonicalGameId,
        title: String,
        keys: List<OwnedCopyKey>,
    ): CanonicalLibraryCard = canonicalCard().copy(
        key = CanonicalCardKey.Grouped(id),
        canonicalId = id,
        displayName = title,
        copies = keys.map(::copy),
        ownedSources = keys.mapTo(linkedSetOf(), OwnedCopyKey::source),
        preferredCopy = null,
        steamCollectionAppIds = emptySet(),
    )

    private fun canonicalCard(): CanonicalLibraryCard = CanonicalLibraryCard(
        key = CanonicalCardKey.Grouped(canonicalId),
        canonicalId = canonicalId,
        displayName = "Visible title",
        appType = CanonicalAppType.GAME,
        iconUrl = "",
        capsuleImageUrl = "",
        headerImageUrl = "",
        heroImageUrl = "",
        gridHeroImageScale = 1f,
        aliases = emptySet(),
        ownedSources = setOf(GameSource.STEAM, GameSource.GOG),
        copies = listOf(
            copy(
                key = steamKey,
                capabilities = setOf(OwnedCopyOperation.PLAY, OwnedCopyOperation.OPEN_SOURCE_DETAILS),
                preferred = true,
            ),
            copy(
                key = gogKey,
                unavailable = CopyUnavailableReason.SOURCE_READ_FAILED,
                capabilities = setOf(OwnedCopyOperation.PLAY),
            ),
        ),
        preferredCopy = steamKey,
        steamCollectionAppIds = setOf(10),
        isShared = false,
    )

    private fun copy(
        key: OwnedCopyKey,
        capabilities: Set<OwnedCopyOperation> = setOf(OwnedCopyOperation.OPEN_SOURCE_DETAILS),
        unavailable: CopyUnavailableReason? = null,
        canSeparate: Boolean = unavailable == null,
        preferred: Boolean = false,
    ): OwnedCopySummary = OwnedCopySummary(
        key = key,
        source = key.source,
        nativeTitle = if (preferred) "Steam title" else "Source title",
        installPath = if (unavailable == null) "/private/runtime/path" else null,
        installedSizeBytes = if (unavailable == null) 1024L else null,
        branchOrVersion = if (unavailable == null) "runtime-version" else null,
        isInstalled = unavailable == null,
        isDownloading = false,
        hasPartialDownload = false,
        updateAvailable = false,
        isShared = false,
        lastPlayedEpochMs = null,
        playtimeMinutes = 30,
        capabilities = capabilities,
        unavailableReason = unavailable,
        canSeparateMatch = canSeparate,
        matchMethod = MatchMethod.DIRECT_STEAM,
        confidence = MatchConfidence.VERIFIED,
        decisionSource = MatchDecisionSource.AUTOMATIC,
        decisionCandidateSteamAppId = null,
        decisionResolverVersion = 1,
        decisionRevision = 100L,
    )

    private companion object {
        val accountScope = AccountScope("f".repeat(64))
        val canonicalId = CanonicalGameId.parse("11111111-1111-1111-1111-111111111111")
        val steamKey = OwnedCopyKey(accountScope, GameSource.STEAM, "raw-steam-id")
        val gogKey = OwnedCopyKey(accountScope, GameSource.GOG, "raw-gog-id")
        val epicKey = OwnedCopyKey(accountScope, GameSource.EPIC, "raw-epic-id")
    }
}
