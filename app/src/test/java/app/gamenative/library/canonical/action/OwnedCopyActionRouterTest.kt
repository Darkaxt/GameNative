package app.gamenative.library.canonical.action

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.EpicStableSourceId
import app.gamenative.data.canonical.MatchConfidence
import app.gamenative.data.canonical.MatchDecisionSource
import app.gamenative.data.canonical.MatchMethod
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.library.canonical.CanonicalCardKey
import app.gamenative.library.canonical.CanonicalDiagnosticSink
import app.gamenative.library.canonical.CanonicalLibraryDiagnosticSink
import app.gamenative.library.canonical.CanonicalLibraryCard
import app.gamenative.library.canonical.CanonicalProjectionClock
import app.gamenative.library.canonical.CanonicalPublicLibraryGate
import app.gamenative.library.canonical.CopyUnavailableReason
import app.gamenative.library.canonical.OwnedCopyOperation
import app.gamenative.library.canonical.OwnedCopySummary
import app.gamenative.library.canonical.PreferredCopyRepository
import app.gamenative.library.canonical.PrefManagerCanonicalPublicLibraryGate
import app.gamenative.library.canonical.runtime.OwnedCopyRuntime
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeAdapter
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeRegistry
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeResult
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.AbstractList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class OwnedCopyActionRouterTest {
    private val scope = AccountScope.parse("a".repeat(64))
    private val canonicalId = CanonicalGameId.parse("11111111-1111-1111-1111-111111111111")

    @Test
    fun explicitSelectionWinsOverPreferredAndMostRecentAcrossThreeCopies() = runTest {
        val fixture = fixture()
        val steam = key(GameSource.STEAM, "1")
        val gog = key(GameSource.GOG, "2")
        val epic = key(GameSource.EPIC, "epic")
        fixture.available(steam, setOf(OwnedCopyOperation.PLAY))
        fixture.available(gog, setOf(OwnedCopyOperation.PLAY))
        fixture.available(epic, setOf(OwnedCopyOperation.PLAY))
        val card = card(
            copies = listOf(
                summary(steam, setOf(OwnedCopyOperation.PLAY), installed = true, lastPlayed = 100L),
                summary(gog, setOf(OwnedCopyOperation.PLAY), installed = true, lastPlayed = 200L),
                summary(epic, setOf(OwnedCopyOperation.PLAY), installed = true, lastPlayed = 300L),
            ),
            preferredCopy = gog,
        )

        val result = fixture.router.route(
            card = card,
            operation = OwnedCopyOperation.PLAY,
            explicitKey = steam,
        ) as OwnedCopyRouteResult.Ready

        assertEquals(steam, result.guard.key)
        assertEquals(ActionSelectionPolicy.EXPLICIT, result.policy)
        assertEquals(1, fixture.adapters.getValue(GameSource.STEAM).resolveCalls)
        assertEquals(0, fixture.adapters.getValue(GameSource.GOG).resolveCalls)
        assertEquals(0, fixture.adapters.getValue(GameSource.EPIC).resolveCalls)
    }

    @Test
    fun validCapablePreferredCopyWinsOverMoreRecentCopy() = runTest {
        val fixture = fixture()
        val steam = key(GameSource.STEAM, "1")
        val gog = key(GameSource.GOG, "2")
        fixture.available(steam, setOf(OwnedCopyOperation.PLAY))
        fixture.available(gog, setOf(OwnedCopyOperation.PLAY))
        val card = card(
            copies = listOf(
                summary(steam, setOf(OwnedCopyOperation.PLAY), installed = true, lastPlayed = 900L),
                summary(gog, setOf(OwnedCopyOperation.PLAY), installed = true, lastPlayed = 10L),
            ),
            preferredCopy = gog,
        )

        val result = fixture.router.route(card, OwnedCopyOperation.PLAY) as OwnedCopyRouteResult.Ready

        assertEquals(gog, result.guard.key)
        assertEquals(ActionSelectionPolicy.PREFERRED, result.policy)
    }

    @Test
    fun staleOrIncapablePreferredCopyIsRememberedButIgnoredForRouting() = runTest {
        val fixture = fixture()
        val steam = key(GameSource.STEAM, "1")
        val gog = key(GameSource.GOG, "2")
        fixture.available(steam, setOf(OwnedCopyOperation.INSTALL))
        val card = card(
            copies = listOf(
                summary(steam, setOf(OwnedCopyOperation.INSTALL)),
                summary(gog, emptySet()),
            ),
            preferredCopy = gog,
        )

        val result = fixture.router.route(card, OwnedCopyOperation.INSTALL) as OwnedCopyRouteResult.Ready

        assertEquals(gog, card.preferredCopy)
        assertEquals(steam, result.guard.key)
        assertEquals(ActionSelectionPolicy.SOLE_COPY, result.policy)
        assertEquals(0, fixture.adapters.getValue(GameSource.GOG).resolveCalls)
    }

    @Test
    fun playUsesOnlyUniquePositiveMaximumAmongInstalledCapableCopies() = runTest {
        val fixture = fixture()
        val steam = key(GameSource.STEAM, "1")
        val gog = key(GameSource.GOG, "2")
        val epic = key(GameSource.EPIC, "epic")
        listOf(steam, gog, epic).forEach {
            fixture.available(it, setOf(OwnedCopyOperation.PLAY))
        }
        val card = card(
            copies = listOf(
                summary(steam, setOf(OwnedCopyOperation.PLAY), installed = true, lastPlayed = 100L),
                summary(gog, setOf(OwnedCopyOperation.PLAY), installed = true, lastPlayed = 200L),
                summary(epic, setOf(OwnedCopyOperation.PLAY), installed = false, lastPlayed = 999L),
            ),
        )

        val result = fixture.router.route(card, OwnedCopyOperation.PLAY) as OwnedCopyRouteResult.Ready

        assertEquals(gog, result.guard.key)
        assertEquals(ActionSelectionPolicy.MOST_RECENT_PLAY, result.policy)
        assertEquals(0, fixture.adapters.getValue(GameSource.EPIC).resolveCalls)
    }

    @Test
    fun customPlayCopyParticipatesInRecencyOnlyWhenSummaryIsInstalled() = runTest {
        val steam = key(GameSource.STEAM, "1")
        val custom = key(GameSource.CUSTOM_GAME, "9")

        val installedFixture = fixture()
        installedFixture.available(steam, setOf(OwnedCopyOperation.PLAY))
        installedFixture.available(custom, setOf(OwnedCopyOperation.PLAY))
        val installedResult = installedFixture.router.route(
            card(
                listOf(
                    summary(steam, setOf(OwnedCopyOperation.PLAY), installed = true, lastPlayed = 10L),
                    summary(custom, setOf(OwnedCopyOperation.PLAY), installed = true, lastPlayed = 20L),
                ),
            ),
            OwnedCopyOperation.PLAY,
        ) as OwnedCopyRouteResult.Ready
        assertEquals(custom, installedResult.guard.key)
        assertEquals(ActionSelectionPolicy.MOST_RECENT_PLAY, installedResult.policy)

        val staleFixture = fixture()
        staleFixture.available(steam, setOf(OwnedCopyOperation.PLAY))
        staleFixture.available(custom, setOf(OwnedCopyOperation.PLAY))
        val staleResult = staleFixture.router.route(
            card(
                listOf(
                    summary(steam, setOf(OwnedCopyOperation.PLAY), installed = true, lastPlayed = 10L),
                    summary(custom, setOf(OwnedCopyOperation.PLAY), installed = false, lastPlayed = 999L),
                ),
            ),
            OwnedCopyOperation.PLAY,
        ) as OwnedCopyRouteResult.Ready
        assertEquals(steam, staleResult.guard.key)
        assertEquals(ActionSelectionPolicy.MOST_RECENT_PLAY, staleResult.policy)
    }

    @Test
    fun tiedOrNonPositivePlayHistoryNeverChoosesAndRequiresChooser() = runTest {
        val steam = key(GameSource.STEAM, "1")
        val gog = key(GameSource.GOG, "2")
        val cases = listOf(
            listOf(100L, 100L),
            listOf(0L, 0L),
            listOf(null, -1L),
        )

        cases.forEach { timestamps ->
            val fixture = fixture()
            val result = fixture.router.route(
                card(
                    listOf(
                        summary(
                            steam,
                            setOf(OwnedCopyOperation.PLAY),
                            installed = true,
                            lastPlayed = timestamps[0],
                        ),
                        summary(
                            gog,
                            setOf(OwnedCopyOperation.PLAY),
                            installed = true,
                            lastPlayed = timestamps[1],
                        ),
                    ),
                ),
                OwnedCopyOperation.PLAY,
            )

            assertEquals(OwnedCopyRouteResult.NeedsChooser(listOf(steam, gog)), result)
            assertEquals(0, fixture.totalResolveCalls())
        }
    }

    @Test
    fun mostRecentRuleIsPlayOnlyAndMultipleInstallCopiesRequireChooser() = runTest {
        val fixture = fixture()
        val steam = key(GameSource.STEAM, "1")
        val gog = key(GameSource.GOG, "2")
        val result = fixture.router.route(
            card(
                listOf(
                    summary(steam, setOf(OwnedCopyOperation.INSTALL), lastPlayed = 200L),
                    summary(gog, setOf(OwnedCopyOperation.INSTALL), lastPlayed = 100L),
                ),
            ),
            OwnedCopyOperation.INSTALL,
        )

        assertEquals(OwnedCopyRouteResult.NeedsChooser(listOf(steam, gog)), result)
        assertEquals(0, fixture.totalResolveCalls())
    }

    @Test
    fun soleCapableCopyRoutesDirectlyAndNoCapableCopyFails() = runTest {
        val steam = key(GameSource.STEAM, "1")
        val gog = key(GameSource.GOG, "2")
        val fixture = fixture()
        fixture.available(gog, setOf(OwnedCopyOperation.UPDATE))
        val card = card(
            listOf(
                summary(steam, emptySet()),
                summary(gog, setOf(OwnedCopyOperation.UPDATE), installed = true),
            ),
        )

        val sole = fixture.router.route(card, OwnedCopyOperation.UPDATE) as OwnedCopyRouteResult.Ready
        assertEquals(gog, sole.guard.key)
        assertEquals(ActionSelectionPolicy.SOLE_COPY, sole.policy)

        val none = fixture.router.route(card, OwnedCopyOperation.EXPORT_SAVES)
        assertEquals(
            OwnedCopyRouteResult.Unavailable(ActionFailureReason.NO_CAPABLE_COPY),
            none,
        )
    }

    @Test
    fun chooserUsesFixedSourceOrderForDisplayWithoutSelecting() = runTest {
        val custom = key(GameSource.CUSTOM_GAME, "5")
        val amazon = key(GameSource.AMAZON, "4")
        val steam = key(GameSource.STEAM, "1")
        val epic = key(GameSource.EPIC, "3")
        val gog = key(GameSource.GOG, "2")
        val fixture = fixture()
        val operation = OwnedCopyOperation.OPEN_SOURCE_DETAILS
        val result = fixture.router.route(
            card(
                listOf(custom, amazon, steam, epic, gog).map { summary(it, setOf(operation)) },
            ),
            operation,
        )

        assertEquals(
            OwnedCopyRouteResult.NeedsChooser(listOf(steam, gog, epic, amazon, custom)),
            result,
        )
        assertEquals(0, fixture.totalResolveCalls())
    }

    @Test
    fun explicitNonmemberAndExplicitIncapableMemberFailWithoutFallback() = runTest {
        val steam = key(GameSource.STEAM, "1")
        val gog = key(GameSource.GOG, "2")
        val outsider = key(GameSource.EPIC, "outside")
        val fixture = fixture()
        fixture.available(steam, setOf(OwnedCopyOperation.PLAY))
        val card = card(
            listOf(
                summary(steam, setOf(OwnedCopyOperation.PLAY), installed = true),
                summary(gog, emptySet()),
            ),
            preferredCopy = steam,
        )

        assertEquals(
            OwnedCopyRouteResult.Unavailable(ActionFailureReason.INVALID_EXPLICIT_COPY),
            fixture.router.route(card, OwnedCopyOperation.PLAY, outsider),
        )
        assertEquals(
            OwnedCopyRouteResult.Unavailable(ActionFailureReason.NO_CAPABLE_COPY),
            fixture.router.route(card, OwnedCopyOperation.PLAY, gog),
        )
        assertEquals(0, fixture.totalResolveCalls())
    }

    @Test
    fun selectedTargetCaptureFailuresNeverTryAReadySibling() = runTest {
        val steam = key(GameSource.STEAM, "1")
        val gog = key(GameSource.GOG, "2")
        val operation = OwnedCopyOperation.PLAY
        val card = card(
            listOf(
                summary(steam, setOf(operation), installed = true),
                summary(gog, setOf(operation), installed = true),
            ),
        )
        val selectedFailures = listOf(
            { key: OwnedCopyKey ->
                OwnedCopyRuntimeResult.Hidden to ActionFailureReason.COPY_UNAVAILABLE
            },
            { key: OwnedCopyKey ->
                OwnedCopyRuntimeResult.Unavailable(
                    key,
                    CopyUnavailableReason.SOURCE_READ_FAILED,
                ) to ActionFailureReason.COPY_UNAVAILABLE
            },
            { key: OwnedCopyKey ->
                OwnedCopyRuntimeResult.Available(
                    runtime(key, capabilities = emptySet()),
                ) to ActionFailureReason.CAPABILITY_CHANGED
            },
        )

        selectedFailures.forEach { failure ->
            val fixture = fixture()
            val (runtimeResult, expectedReason) = failure(steam)
            fixture.adapters.getValue(GameSource.STEAM).handler = { runtimeResult }
            fixture.available(gog, setOf(operation))

            val result = fixture.router.route(card, operation, explicitKey = steam)

            assertEquals(OwnedCopyRouteResult.Unavailable(expectedReason), result)
            assertEquals(1, fixture.adapters.getValue(GameSource.STEAM).resolveCalls)
            assertEquals(0, fixture.adapters.getValue(GameSource.GOG).resolveCalls)
        }

        val unbridgeableGog = key(GameSource.GOG, "2147483648")
        val nullBridge = fixture()
        nullBridge.adapters.getValue(GameSource.GOG).handler = {
            OwnedCopyRuntimeResult.Available(
                runtime(
                    key = unbridgeableGog,
                    reference = SourceOwnedCopyReference.Gog(
                        unbridgeableGog,
                        unbridgeableGog.stableSourceId,
                    ),
                    capabilities = setOf(operation),
                    libraryItem = null,
                ),
            )
        }
        nullBridge.available(steam, setOf(operation))

        assertEquals(
            OwnedCopyRouteResult.Unavailable(ActionFailureReason.COPY_UNAVAILABLE),
            nullBridge.router.route(
                card = card(
                    listOf(
                        summary(unbridgeableGog, setOf(operation), installed = true),
                        summary(steam, setOf(operation), installed = true),
                    ),
                ),
                operation = operation,
                explicitKey = unbridgeableGog,
            ),
        )
        assertEquals(1, nullBridge.adapters.getValue(GameSource.GOG).resolveCalls)
        assertEquals(0, nullBridge.adapters.getValue(GameSource.STEAM).resolveCalls)
    }

    @Test
    fun disabledGateReturnsBeforeCardInspectionRuntimeClockOrPreferenceAccess() = runTest {
        val gate = MutableGate(enabled = false)
        val fixture = fixture(gate)
        fixture.adapters.values.forEach { adapter ->
            adapter.handler = { error("runtime dependency accessed") }
        }
        val throwingCopies = object : AbstractList<OwnedCopySummary>() {
            override val size: Int get() = error("card inspected")
            override fun get(index: Int): OwnedCopySummary = error("card inspected")
        }
        val explicitKey = key(GameSource.STEAM, "1")
        val card = card(throwingCopies, ownedSources = emptySet())

        val result = fixture.router.route(
            card = card,
            operation = OwnedCopyOperation.PLAY,
            explicitKey = explicitKey,
            rememberChoice = true,
        )

        assertEquals(
            OwnedCopyRouteResult.Unavailable(ActionFailureReason.PUBLIC_FEATURE_DISABLED),
            result,
        )
        assertEquals(1, gate.calls)
        assertEquals(0, fixture.totalResolveCalls())
        coVerify(exactly = 0) {
            fixture.preferences.setPreferredCopy(canonicalId, explicitKey, 1000L)
        }
    }

    @Test
    fun gateDisabledDuringRuntimeResolutionReturnsDisabledWithoutPreferenceWrite() = runTest {
        val gate = MutableGate()
        val fixture = fixture(gate)
        val steam = key(GameSource.STEAM, "1")
        val resolutionStarted = CompletableDeferred<Unit>()
        val releaseResolution = CompletableDeferred<Unit>()
        fixture.adapters.getValue(GameSource.STEAM).handler = {
            resolutionStarted.complete(Unit)
            releaseResolution.await()
            OwnedCopyRuntimeResult.Available(
                runtime(it, capabilities = setOf(OwnedCopyOperation.INSTALL)),
            )
        }

        val request = async {
            fixture.router.route(
                card = card(listOf(summary(steam, setOf(OwnedCopyOperation.INSTALL)))),
                operation = OwnedCopyOperation.INSTALL,
                explicitKey = steam,
                rememberChoice = true,
            )
        }
        resolutionStarted.await()
        gate.enabled = false
        releaseResolution.complete(Unit)

        assertEquals(
            OwnedCopyRouteResult.Unavailable(ActionFailureReason.PUBLIC_FEATURE_DISABLED),
            request.await(),
        )
        coVerify(exactly = 0) {
            fixture.preferences.setPreferredCopy(canonicalId, steam, 1000L)
        }
    }

    @Test
    fun gateIsRecheckedImmediatelyBeforePreferencePersistence() = runTest {
        val gate = MutableGate(check = { call -> call < 3 })
        val fixture = fixture(gate)
        val steam = key(GameSource.STEAM, "1")
        fixture.available(steam, setOf(OwnedCopyOperation.INSTALL))

        val result = fixture.router.route(
            card = card(listOf(summary(steam, setOf(OwnedCopyOperation.INSTALL)))),
            operation = OwnedCopyOperation.INSTALL,
            explicitKey = steam,
            rememberChoice = true,
        )

        assertEquals(
            OwnedCopyRouteResult.Unavailable(ActionFailureReason.PUBLIC_FEATURE_DISABLED),
            result,
        )
        coVerify(exactly = 0) {
            fixture.preferences.setPreferredCopy(canonicalId, steam, 1000L)
        }
    }

    @Test
    fun gateDisabledDuringPreferencePersistenceNeverReturnsReady() = runTest {
        val gate = MutableGate()
        val fixture = fixture(gate)
        val steam = key(GameSource.STEAM, "1")
        fixture.available(steam, setOf(OwnedCopyOperation.INSTALL))
        coEvery {
            fixture.preferences.setPreferredCopy(canonicalId, steam, 1000L)
        } coAnswers {
            gate.enabled = false
        }

        val result = fixture.router.route(
            card = card(listOf(summary(steam, setOf(OwnedCopyOperation.INSTALL)))),
            operation = OwnedCopyOperation.INSTALL,
            explicitKey = steam,
            rememberChoice = true,
        )

        assertEquals(
            OwnedCopyRouteResult.Unavailable(ActionFailureReason.PUBLIC_FEATURE_DISABLED),
            result,
        )
        coVerify(exactly = 1) {
            fixture.preferences.setPreferredCopy(canonicalId, steam, 1000L)
        }
    }

    @Test
    fun publicGateIsIndependentDefaultOffAndRequiresProjectionAndPublicPreferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PrefManager.init(context)
        PrefManager.clearPreferences()
        awaitPreference {
            PrefManager.canonicalProjectionEnabled && !PrefManager.canonicalPublicLibraryEnabled
        }
        val gate = PrefManagerCanonicalPublicLibraryGate()
        assertFalse(PrefManager.canonicalPublicLibraryEnabled)
        assertFalse(gate.isEnabled())

        PrefManager.canonicalPublicLibraryEnabled = true
        awaitPreference { PrefManager.canonicalPublicLibraryEnabled }
        assertTrue(gate.isEnabled())

        PrefManager.canonicalProjectionEnabled = false
        awaitPreference { !PrefManager.canonicalProjectionEnabled }
        assertFalse(gate.isEnabled())

        PrefManager.canonicalPublicLibraryEnabled = false
        PrefManager.canonicalProjectionEnabled = true
        awaitPreference {
            !PrefManager.canonicalPublicLibraryEnabled && PrefManager.canonicalProjectionEnabled
        }
        assertFalse(gate.isEnabled())
    }

    @Test
    fun preferenceWritesOnlyAfterSuccessfulRememberedExplicitCapture() = runTest {
        val fixture = fixture(nowEpochMs = 1234L)
        val steam = key(GameSource.STEAM, "1")
        fixture.available(steam, setOf(OwnedCopyOperation.INSTALL))
        val card = card(listOf(summary(steam, setOf(OwnedCopyOperation.INSTALL))))

        val result = fixture.router.route(
            card,
            OwnedCopyOperation.INSTALL,
            explicitKey = steam,
            rememberChoice = true,
        ) as OwnedCopyRouteResult.Ready

        assertEquals(ActionSelectionPolicy.EXPLICIT, result.policy)
        assertEquals(null, result.warning)
        coVerify(exactly = 1) {
            fixture.preferences.setPreferredCopy(canonicalId, steam, 1234L)
        }
    }

    @Test
    fun independentReviewRejectedAndUnmatchedCardsNeverOverwriteGroupedPreference() = runTest {
        val steam = key(GameSource.STEAM, "1")
        val confidenceStates = listOf(
            MatchConfidence.REVIEW_REQUIRED,
            MatchConfidence.REJECTED,
            MatchConfidence.UNMATCHED,
        )
        val fixture = fixture()
        fixture.available(steam, setOf(OwnedCopyOperation.INSTALL))

        confidenceStates.forEach { confidence ->
            val independent = card(
                copies = listOf(
                    summary(steam, setOf(OwnedCopyOperation.INSTALL)).copy(
                        confidence = confidence,
                        matchMethod = MatchMethod.UNMATCHED,
                    ),
                ),
                preferredCopy = steam,
            ).copy(key = CanonicalCardKey.Independent(steam))

            val result = fixture.router.route(
                card = independent,
                operation = OwnedCopyOperation.INSTALL,
                explicitKey = steam,
                rememberChoice = true,
            ) as OwnedCopyRouteResult.Ready

            assertEquals(confidence.name, ActionSelectionPolicy.EXPLICIT, result.policy)
            assertEquals(confidence.name, null, result.warning)
        }

        coVerify(exactly = 0) {
            fixture.preferences.setPreferredCopy(canonicalId, steam, 1000L)
        }
    }

    @Test
    fun groupedKeyCanonicalIdMismatchNeverWritesButKeepsSafeExplicitCaptureReady() = runTest {
        val fixture = fixture()
        val steam = key(GameSource.STEAM, "1")
        val mismatchedCanonicalId = CanonicalGameId.parse(
            "22222222-2222-2222-2222-222222222222",
        )
        fixture.available(steam, setOf(OwnedCopyOperation.INSTALL))
        val malformed = card(
            listOf(summary(steam, setOf(OwnedCopyOperation.INSTALL))),
        ).copy(canonicalId = mismatchedCanonicalId)

        val result = fixture.router.route(
            card = malformed,
            operation = OwnedCopyOperation.INSTALL,
            explicitKey = steam,
            rememberChoice = true,
        ) as OwnedCopyRouteResult.Ready

        assertEquals(ActionSelectionPolicy.EXPLICIT, result.policy)
        assertEquals(null, result.warning)
        coVerify(exactly = 0) {
            fixture.preferences.setPreferredCopy(canonicalId, steam, 1000L)
        }
        coVerify(exactly = 0) {
            fixture.preferences.setPreferredCopy(mismatchedCanonicalId, steam, 1000L)
        }
    }

    @Test
    fun preferenceFailureKeepsReadyGuardWithFixedWarningAndNoPrivateDetail() = runTest {
        val fixture = fixture()
        val steam = key(GameSource.STEAM, "1")
        fixture.available(steam, setOf(OwnedCopyOperation.INSTALL))
        coEvery {
            fixture.preferences.setPreferredCopy(canonicalId, steam, 1000L)
        } throws SensitiveFailure()

        val result = fixture.router.route(
            card(listOf(summary(steam, setOf(OwnedCopyOperation.INSTALL)))),
            OwnedCopyOperation.INSTALL,
            explicitKey = steam,
            rememberChoice = true,
        ) as OwnedCopyRouteResult.Ready

        assertEquals(steam, result.guard.key)
        assertEquals(ActionSelectionPolicy.EXPLICIT, result.policy)
        assertEquals(ActionFailureReason.PREFERENCE_WRITE_FAILED, result.warning)
        assertFalse(result.toString().contains(SENSITIVE_MESSAGE))
    }

    @Test
    fun preferenceIsNotWrittenForExplicitRememberFalseOrAutomaticPolicies() = runTest {
        val steam = key(GameSource.STEAM, "1")
        val gog = key(GameSource.GOG, "2")

        val explicit = fixture()
        explicit.available(steam, setOf(OwnedCopyOperation.INSTALL))
        explicit.router.route(
            card(listOf(summary(steam, setOf(OwnedCopyOperation.INSTALL)))),
            OwnedCopyOperation.INSTALL,
            explicitKey = steam,
            rememberChoice = false,
        )
        coVerify(exactly = 0) {
            explicit.preferences.setPreferredCopy(canonicalId, steam, 1000L)
        }

        val preferred = fixture()
        preferred.available(steam, setOf(OwnedCopyOperation.PLAY))
        preferred.router.route(
            card(
                listOf(
                    summary(steam, setOf(OwnedCopyOperation.PLAY), installed = true),
                    summary(gog, setOf(OwnedCopyOperation.PLAY), installed = true),
                ),
                preferredCopy = steam,
            ),
            OwnedCopyOperation.PLAY,
            rememberChoice = true,
        )
        coVerify(exactly = 0) {
            preferred.preferences.setPreferredCopy(canonicalId, steam, 1000L)
        }

        val recent = fixture()
        recent.available(steam, setOf(OwnedCopyOperation.PLAY))
        recent.router.route(
            card(
                listOf(
                    summary(steam, setOf(OwnedCopyOperation.PLAY), true, 20L),
                    summary(gog, setOf(OwnedCopyOperation.PLAY), true, 10L),
                ),
            ),
            OwnedCopyOperation.PLAY,
            rememberChoice = true,
        )
        coVerify(exactly = 0) {
            recent.preferences.setPreferredCopy(canonicalId, steam, 1000L)
        }

        val sole = fixture()
        sole.available(steam, setOf(OwnedCopyOperation.INSTALL))
        sole.router.route(
            card(listOf(summary(steam, setOf(OwnedCopyOperation.INSTALL)))),
            OwnedCopyOperation.INSTALL,
            rememberChoice = true,
        )
        coVerify(exactly = 0) {
            sole.preferences.setPreferredCopy(canonicalId, steam, 1000L)
        }
    }

    @Test
    fun everySourceCaptureRejectsWrongProviderReferenceWithoutPreferenceOrSiblingFallback() = runTest {
        exactIdentityCases().forEach { identity ->
            val fixture = fixture()
            fixture.adapters.getValue(identity.key.source).handler = {
                OwnedCopyRuntimeResult.Available(
                    runtime(
                        key = identity.key,
                        reference = identity.wrongReference,
                        capabilities = setOf(OwnedCopyOperation.PLAY),
                        libraryItem = libraryItem(
                            identity.key.source,
                            identity.validLibraryItemId,
                        ),
                    ),
                )
            }

            assertSuspendThrows(IllegalStateException::class.java) {
                fixture.router.route(
                    card = card(
                        listOf(
                            summary(
                                identity.key,
                                setOf(OwnedCopyOperation.PLAY),
                                installed = true,
                            ),
                        ),
                    ),
                    operation = OwnedCopyOperation.PLAY,
                    explicitKey = identity.key,
                    rememberChoice = true,
                )
            }

            assertEquals(identity.name, 1, fixture.adapters.getValue(identity.key.source).resolveCalls)
            assertEquals(identity.name, 0, fixture.siblingCalls(identity.key.source))
            coVerify(exactly = 0) {
                fixture.preferences.setPreferredCopy(canonicalId, identity.key, 1000L)
            }
        }
    }

    @Test
    fun everySourceCaptureRejectsWrongSameSourceExecutableIdWithoutPreferenceOrSiblingFallback() = runTest {
        exactIdentityCases().forEach { identity ->
            val fixture = fixture()
            fixture.adapters.getValue(identity.key.source).handler = {
                OwnedCopyRuntimeResult.Available(
                    runtime(
                        key = identity.key,
                        reference = identity.validReference,
                        capabilities = setOf(OwnedCopyOperation.PLAY),
                        libraryItem = libraryItem(
                            identity.key.source,
                            identity.wrongLibraryItemId,
                        ),
                    ),
                )
            }

            assertSuspendThrows(IllegalStateException::class.java) {
                fixture.router.route(
                    card = card(
                        listOf(
                            summary(
                                identity.key,
                                setOf(OwnedCopyOperation.PLAY),
                                installed = true,
                            ),
                        ),
                    ),
                    operation = OwnedCopyOperation.PLAY,
                    explicitKey = identity.key,
                    rememberChoice = true,
                )
            }

            assertEquals(identity.name, 1, fixture.adapters.getValue(identity.key.source).resolveCalls)
            assertEquals(identity.name, 0, fixture.siblingCalls(identity.key.source))
            coVerify(exactly = 0) {
                fixture.preferences.setPreferredCopy(canonicalId, identity.key, 1000L)
            }
        }
    }

    @Test
    fun failedExplicitCaptureNeverWritesPreference() = runTest {
        val fixture = fixture()
        val steam = key(GameSource.STEAM, "1")
        val card = card(listOf(summary(steam, setOf(OwnedCopyOperation.INSTALL))))

        fixture.router.route(
            card,
            OwnedCopyOperation.INSTALL,
            explicitKey = steam,
            rememberChoice = true,
        )

        coVerify(exactly = 0) {
            fixture.preferences.setPreferredCopy(canonicalId, steam, 1000L)
        }
    }

    @Test
    fun actionDiagnosticsRecordSelectionChooserCaptureAndPreferenceFailureOnlyAtGestures() = runTest {
        val diagnostics = mockk<CanonicalLibraryDiagnosticSink>(relaxed = true)
        val steam = key(GameSource.STEAM, "1")
        val gog = key(GameSource.GOG, "2")

        val selected = fixture(diagnostics = diagnostics)
        selected.available(steam, setOf(OwnedCopyOperation.INSTALL))
        assertTrue(
            selected.router.route(
                card(listOf(summary(steam, setOf(OwnedCopyOperation.INSTALL)))),
                OwnedCopyOperation.INSTALL,
                explicitKey = steam,
            ) is OwnedCopyRouteResult.Ready,
        )
        verify(exactly = 1) {
            diagnostics.routeSelected(
                GameSource.STEAM,
                OwnedCopyOperation.INSTALL,
                ActionSelectionPolicy.EXPLICIT,
                1,
            )
        }

        val chooser = fixture(diagnostics = diagnostics)
        val chooserResult = chooser.router.route(
            card(
                listOf(
                    summary(steam, setOf(OwnedCopyOperation.PLAY), installed = true),
                    summary(gog, setOf(OwnedCopyOperation.PLAY), installed = true),
                ),
            ),
            OwnedCopyOperation.PLAY,
        )
        assertTrue(chooserResult is OwnedCopyRouteResult.NeedsChooser)
        verify(exactly = 1) {
            diagnostics.chooserRequired(OwnedCopyOperation.PLAY, 2)
        }

        val unavailable = fixture(diagnostics = diagnostics)
        unavailable.adapters.getValue(GameSource.STEAM).handler = { OwnedCopyRuntimeResult.Hidden }
        assertEquals(
            OwnedCopyRouteResult.Unavailable(ActionFailureReason.COPY_UNAVAILABLE),
            unavailable.router.route(
                card(listOf(summary(steam, setOf(OwnedCopyOperation.INSTALL)))),
                OwnedCopyOperation.INSTALL,
                explicitKey = steam,
            ),
        )
        verify(exactly = 1) {
            diagnostics.routeFailed(
                GameSource.STEAM,
                OwnedCopyOperation.INSTALL,
                ActionFailureReason.COPY_UNAVAILABLE,
                null,
            )
        }

        val preference = fixture(diagnostics = diagnostics)
        preference.available(steam, setOf(OwnedCopyOperation.INSTALL))
        coEvery {
            preference.preferences.setPreferredCopy(canonicalId, steam, 1000L)
        } throws SensitiveFailure()
        val preferenceResult = preference.router.route(
            card(listOf(summary(steam, setOf(OwnedCopyOperation.INSTALL)))),
            OwnedCopyOperation.INSTALL,
            explicitKey = steam,
            rememberChoice = true,
        ) as OwnedCopyRouteResult.Ready
        assertEquals(ActionFailureReason.PREFERENCE_WRITE_FAILED, preferenceResult.warning)
        verify(exactly = 1) {
            diagnostics.routeFailed(
                GameSource.STEAM,
                OwnedCopyOperation.INSTALL,
                ActionFailureReason.PREFERENCE_WRITE_FAILED,
                SensitiveFailure::class,
            )
        }
        verify(exactly = 2) {
            diagnostics.routeSelected(
                GameSource.STEAM,
                OwnedCopyOperation.INSTALL,
                ActionSelectionPolicy.EXPLICIT,
                1,
            )
        }
    }

    @Test
    fun actionDiagnosticSinkFailureDoesNotChangeReadyResult() = runTest {
        val diagnostics = mockk<CanonicalLibraryDiagnosticSink>(relaxed = true)
        every {
            diagnostics.routeSelected(any(), any(), any(), any())
        } throws IllegalStateException("private diagnostic failure")
        val fixture = fixture(diagnostics = diagnostics)
        val steam = key(GameSource.STEAM, "1")
        fixture.available(steam, setOf(OwnedCopyOperation.INSTALL))

        val result = fixture.router.route(
            card(listOf(summary(steam, setOf(OwnedCopyOperation.INSTALL)))),
            OwnedCopyOperation.INSTALL,
            explicitKey = steam,
        )

        assertTrue(result is OwnedCopyRouteResult.Ready)
    }

    @Test
    fun runtimeAndPreferenceCancellationPropagateWithoutFallbackOrWarning() = runTest {
        val steam = key(GameSource.STEAM, "1")
        val card = card(listOf(summary(steam, setOf(OwnedCopyOperation.INSTALL))))
        val runtimeCancelled = fixture()
        runtimeCancelled.adapters.getValue(GameSource.STEAM).handler = {
            throw CancellationException("cancel runtime")
        }
        assertSuspendThrows(CancellationException::class.java) {
            runtimeCancelled.router.route(card, OwnedCopyOperation.INSTALL)
        }
        verify(exactly = 0) {
            runtimeCancelled.diagnostics.routeFailed(any(), any(), any(), any())
        }
        verify(exactly = 0) {
            runtimeCancelled.diagnostics.routeSelected(any(), any(), any(), any())
        }

        val preferenceCancelled = fixture()
        preferenceCancelled.available(steam, setOf(OwnedCopyOperation.INSTALL))
        coEvery {
            preferenceCancelled.preferences.setPreferredCopy(canonicalId, steam, 1000L)
        } throws CancellationException("cancel preference")
        assertSuspendThrows(CancellationException::class.java) {
            preferenceCancelled.router.route(
                card,
                OwnedCopyOperation.INSTALL,
                explicitKey = steam,
                rememberChoice = true,
            )
        }
        verify(exactly = 0) {
            preferenceCancelled.diagnostics.routeFailed(any(), any(), any(), any())
        }
        verify(exactly = 0) {
            preferenceCancelled.diagnostics.routeSelected(any(), any(), any(), any())
        }
    }

    @Test
    fun runtimeGateIdentityAndFatalProgrammerFailuresAreNotConvertedToUserFailures() = runTest {
        val steam = key(GameSource.STEAM, "1")
        val wrong = key(GameSource.STEAM, "2")
        val card = card(listOf(summary(steam, setOf(OwnedCopyOperation.PLAY), installed = true)))

        val identityMismatch = fixture()
        identityMismatch.adapters.getValue(GameSource.STEAM).handler = {
            OwnedCopyRuntimeResult.Available(
                runtime(
                    key = steam,
                    reference = SourceOwnedCopyReference.Steam(wrong, 1),
                    capabilities = setOf(OwnedCopyOperation.PLAY),
                ),
            )
        }
        assertSuspendThrows(IllegalStateException::class.java) {
            identityMismatch.router.route(card, OwnedCopyOperation.PLAY)
        }

        val gateFailure = fixture(MutableGate(failure = IllegalStateException("gate contract")))
        assertSuspendThrows(IllegalStateException::class.java) {
            gateFailure.router.route(card, OwnedCopyOperation.PLAY)
        }
        assertEquals(0, gateFailure.totalResolveCalls())

        val fatalPreference = fixture()
        fatalPreference.available(steam, setOf(OwnedCopyOperation.PLAY))
        coEvery {
            fatalPreference.preferences.setPreferredCopy(canonicalId, steam, 1000L)
        } throws OutOfMemoryError("fatal")
        assertSuspendThrows(OutOfMemoryError::class.java) {
            fatalPreference.router.route(
                card,
                OwnedCopyOperation.PLAY,
                explicitKey = steam,
                rememberChoice = true,
            )
        }
    }

    private fun fixture(
        gate: MutableGate = MutableGate(),
        nowEpochMs: Long = 1000L,
        diagnostics: CanonicalLibraryDiagnosticSink = mockk(relaxed = true),
    ): Fixture {
        val adapters = GameSource.entries.associateWith(::RecordingAdapter)
        val history = mockk<LibraryPlayHistoryDao>()
        every { history.getAll() } returns emptyFlow()
        val registry = OwnedCopyRuntimeRegistry(
            adapters.values.toSet(),
            history,
            mockk<CanonicalDiagnosticSink>(relaxed = true),
        )
        val preferences = mockk<PreferredCopyRepository>(relaxed = true)
        val router = OwnedCopyActionRouter(
            runtimeRegistry = registry,
            preferredCopyRepository = preferences,
            publicGate = gate,
            clock = CanonicalProjectionClock { nowEpochMs },
            diagnostics = diagnostics,
        )
        return Fixture(router, adapters, preferences, diagnostics)
    }

    private fun key(source: GameSource, stableSourceId: String): OwnedCopyKey =
        OwnedCopyKey(scope, source, stableSourceId)

    private fun exactIdentityCases(): List<ExactIdentityCase> {
        val steam = key(GameSource.STEAM, "42")
        val gog = key(GameSource.GOG, "123")
        val epic = key(
            GameSource.EPIC,
            EpicStableSourceId.encode("namespace", "catalog"),
        )
        val amazon = key(GameSource.AMAZON, "product")
        val custom = key(GameSource.CUSTOM_GAME, "5")
        return listOf(
            ExactIdentityCase(
                "Steam",
                steam,
                SourceOwnedCopyReference.Steam(steam, 42),
                SourceOwnedCopyReference.Steam(steam, 43),
                "STEAM_42",
                "STEAM_43",
            ),
            ExactIdentityCase(
                "GOG",
                gog,
                SourceOwnedCopyReference.Gog(gog, "123"),
                SourceOwnedCopyReference.Gog(gog, "0123"),
                "GOG_123",
                "GOG_124",
            ),
            ExactIdentityCase(
                "Epic",
                epic,
                SourceOwnedCopyReference.Epic(epic, 7, "namespace", "catalog"),
                SourceOwnedCopyReference.Epic(epic, 7, "other-namespace", "catalog"),
                "EPIC_7",
                "EPIC_8",
            ),
            ExactIdentityCase(
                "Amazon",
                amazon,
                SourceOwnedCopyReference.Amazon(amazon, 8, "product", "entitlement"),
                SourceOwnedCopyReference.Amazon(amazon, 8, "other-product", "entitlement"),
                "AMAZON_8",
                "AMAZON_9",
            ),
            ExactIdentityCase(
                "Custom",
                custom,
                SourceOwnedCopyReference.Custom(custom, 5),
                SourceOwnedCopyReference.Custom(custom, 6),
                "CUSTOM_GAME_5",
                "CUSTOM_GAME_6",
            ),
        )
    }

    private fun libraryItem(source: GameSource, appId: String): LibraryItem = LibraryItem(
        appId = appId,
        name = "Runtime",
        gameSource = source,
    )

    private fun card(
        copies: List<OwnedCopySummary>,
        preferredCopy: OwnedCopyKey? = null,
        ownedSources: Set<GameSource> = copies.mapTo(linkedSetOf(), OwnedCopySummary::source),
    ): CanonicalLibraryCard = CanonicalLibraryCard(
        key = CanonicalCardKey.Grouped(canonicalId),
        canonicalId = canonicalId,
        displayName = "Card",
        appType = CanonicalAppType.GAME,
        iconUrl = "",
        capsuleImageUrl = "",
        headerImageUrl = "",
        heroImageUrl = "",
        gridHeroImageScale = 1f,
        aliases = emptySet(),
        ownedSources = ownedSources,
        copies = copies,
        preferredCopy = preferredCopy,
        steamCollectionAppIds = emptySet(),
        isShared = false,
    )

    private fun summary(
        key: OwnedCopyKey,
        capabilities: Set<OwnedCopyOperation>,
        installed: Boolean = false,
        lastPlayed: Long? = null,
    ): OwnedCopySummary = OwnedCopySummary(
        key = key,
        source = key.source,
        nativeTitle = key.source.name,
        installPath = null,
        installedSizeBytes = null,
        branchOrVersion = null,
        isInstalled = installed,
        isDownloading = false,
        hasPartialDownload = false,
        updateAvailable = false,
        isShared = false,
        lastPlayedEpochMs = lastPlayed,
        playtimeMinutes = null,
        capabilities = capabilities,
        unavailableReason = null,
        canSeparateMatch = true,
        matchMethod = MatchMethod.EXACT_METADATA,
        confidence = MatchConfidence.HIGH,
        decisionSource = MatchDecisionSource.AUTOMATIC,
        decisionCandidateSteamAppId = null,
        decisionResolverVersion = 1,
        decisionRevision = 100L,
    )

    private fun runtime(
        key: OwnedCopyKey,
        reference: SourceOwnedCopyReference = reference(key),
        capabilities: Set<OwnedCopyOperation>,
        libraryItem: LibraryItem? = LibraryItem(
            appId = "${key.source.name}_${key.stableSourceId}",
            name = "Runtime",
            gameSource = key.source,
        ),
    ): OwnedCopyRuntime = OwnedCopyRuntime(
        key = key,
        reference = reference,
        libraryItem = libraryItem,
        nativeTitle = "Runtime",
        aliases = emptySet(),
        developerKey = "",
        releaseYear = null,
        appType = CanonicalAppType.GAME,
        genreKeys = emptySet(),
        tagIds = emptySet(),
        featureKeys = emptySet(),
        iconUrl = "",
        capsuleImageUrl = "",
        headerImageUrl = "",
        heroImageUrl = "",
        gridHeroImageScale = 1f,
        installPath = null,
        installedSizeBytes = null,
        branchOrVersion = null,
        isInstalled = OwnedCopyOperation.PLAY in capabilities,
        isDownloading = false,
        hasPartialDownload = false,
        updateAvailable = OwnedCopyOperation.UPDATE in capabilities,
        isShared = false,
        lastPlayedEpochMs = null,
        playtimeMinutes = null,
        capabilities = capabilities,
    )

    private fun reference(key: OwnedCopyKey): SourceOwnedCopyReference = when (key.source) {
        GameSource.STEAM -> SourceOwnedCopyReference.Steam(key, key.stableSourceId.toIntOrNull() ?: 1)
        GameSource.GOG -> SourceOwnedCopyReference.Gog(key, key.stableSourceId)
        GameSource.EPIC -> SourceOwnedCopyReference.Epic(key, 3, "namespace", "catalog")
        GameSource.AMAZON -> SourceOwnedCopyReference.Amazon(key, 4, key.stableSourceId, "entitlement")
        GameSource.CUSTOM_GAME -> SourceOwnedCopyReference.Custom(key, key.stableSourceId.toIntOrNull() ?: 5)
    }

    private fun awaitPreference(condition: () -> Boolean) {
        repeat(200) {
            if (condition()) return
            Thread.sleep(10L)
        }
        assertTrue("Preference update did not settle", condition())
    }

    private suspend fun <T : Throwable> assertSuspendThrows(
        expected: Class<T>,
        block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (error: Throwable) {
            if (expected.isInstance(error)) return expected.cast(error)
            throw AssertionError(
                "Expected ${expected.simpleName}, got ${error::class.java.simpleName}",
                error,
            )
        }
        throw AssertionError("Expected ${expected.simpleName}")
    }

    private inner class RecordingAdapter(
        override val source: GameSource,
    ) : OwnedCopyRuntimeAdapter {
        var resolveCalls = 0
        var handler: suspend (OwnedCopyKey) -> OwnedCopyRuntimeResult = {
            OwnedCopyRuntimeResult.Hidden
        }

        override fun invalidations(): Flow<Unit> = emptyFlow()

        override suspend fun resolve(key: OwnedCopyKey): OwnedCopyRuntimeResult {
            resolveCalls += 1
            return handler(key)
        }

        override suspend fun resolveAll(
            keys: Set<OwnedCopyKey>,
        ): Map<OwnedCopyKey, OwnedCopyRuntimeResult> = buildMap {
            keys.forEach { key -> put(key, handler(key)) }
        }
    }

    private inner class Fixture(
        val router: OwnedCopyActionRouter,
        val adapters: Map<GameSource, RecordingAdapter>,
        val preferences: PreferredCopyRepository,
        val diagnostics: CanonicalLibraryDiagnosticSink,
    ) {
        fun available(key: OwnedCopyKey, capabilities: Set<OwnedCopyOperation>) {
            adapters.getValue(key.source).handler = {
                OwnedCopyRuntimeResult.Available(runtime(it, capabilities = capabilities))
            }
        }

        fun totalResolveCalls(): Int = adapters.values.sumOf(RecordingAdapter::resolveCalls)

        fun siblingCalls(selectedSource: GameSource): Int = adapters
            .filterKeys { it != selectedSource }
            .values
            .sumOf(RecordingAdapter::resolveCalls)
    }

    private data class ExactIdentityCase(
        val name: String,
        val key: OwnedCopyKey,
        val validReference: SourceOwnedCopyReference,
        val wrongReference: SourceOwnedCopyReference,
        val validLibraryItemId: String,
        val wrongLibraryItemId: String,
    )

    private class MutableGate(
        var enabled: Boolean = true,
        private val failure: Throwable? = null,
        private val check: ((Int) -> Boolean)? = null,
    ) : CanonicalPublicLibraryGate {
        var calls: Int = 0

        override fun isEnabled(): Boolean {
            calls += 1
            failure?.let { throw it }
            return check?.invoke(calls) ?: enabled
        }
    }

    private class SensitiveFailure : IllegalStateException(SENSITIVE_MESSAGE)

    private companion object {
        const val SENSITIVE_MESSAGE =
            "private title account source-id path URL token username and exception text"
    }
}
