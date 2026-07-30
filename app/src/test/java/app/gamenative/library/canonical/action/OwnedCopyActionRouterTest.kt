package app.gamenative.library.canonical.action

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import java.util.AbstractList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class OwnedCopyActionRouterTest {
    private val scope = AccountScope.parse("a".repeat(64))
    private val canonicalId = CanonicalGameId.parse("11111111-1111-1111-1111-111111111111")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PrefManager.init(context)
    }

    @After
    fun restoreProjectionPreference() {
        PrefManager.canonicalProjectionEnabled = true
    }

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
        val selectedFailures = listOf<(OwnedCopyKey) -> OwnedCopyRuntimeResult>(
            { OwnedCopyRuntimeResult.Hidden },
            { OwnedCopyRuntimeResult.Unavailable(it, CopyUnavailableReason.SOURCE_READ_FAILED) },
            {
                OwnedCopyRuntimeResult.Available(
                    runtime(it, capabilities = setOf(operation), libraryItem = null),
                )
            },
            {
                OwnedCopyRuntimeResult.Available(
                    runtime(it, capabilities = emptySet()),
                )
            },
        )

        selectedFailures.forEachIndexed { index, failure ->
            val fixture = fixture()
            fixture.adapters.getValue(GameSource.STEAM).handler = failure
            fixture.available(gog, setOf(operation))

            val result = fixture.router.route(card, operation, explicitKey = steam)

            val expectedReason = if (index == selectedFailures.lastIndex) {
                ActionFailureReason.CAPABILITY_CHANGED
            } else {
                ActionFailureReason.COPY_UNAVAILABLE
            }
            assertEquals(OwnedCopyRouteResult.Unavailable(expectedReason), result)
            assertEquals(1, fixture.adapters.getValue(GameSource.STEAM).resolveCalls)
            assertEquals(0, fixture.adapters.getValue(GameSource.GOG).resolveCalls)
        }
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
    fun publicGateIsIndependentDefaultOffAndRequiresProjectionAndPublicPreferences() {
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
        )
        return Fixture(router, adapters, preferences)
    }

    private fun key(source: GameSource, stableSourceId: String): OwnedCopyKey =
        OwnedCopyKey(scope, source, stableSourceId)

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
        var handler: (OwnedCopyKey) -> OwnedCopyRuntimeResult = { OwnedCopyRuntimeResult.Hidden }

        override fun invalidations(): Flow<Unit> = emptyFlow()

        override suspend fun resolve(key: OwnedCopyKey): OwnedCopyRuntimeResult {
            resolveCalls += 1
            return handler(key)
        }

        override suspend fun resolveAll(
            keys: Set<OwnedCopyKey>,
        ): Map<OwnedCopyKey, OwnedCopyRuntimeResult> = keys.associateWith(handler)
    }

    private inner class Fixture(
        val router: OwnedCopyActionRouter,
        val adapters: Map<GameSource, RecordingAdapter>,
        val preferences: PreferredCopyRepository,
    ) {
        fun available(key: OwnedCopyKey, capabilities: Set<OwnedCopyOperation>) {
            adapters.getValue(key.source).handler = {
                OwnedCopyRuntimeResult.Available(runtime(it, capabilities = capabilities))
            }
        }

        fun totalResolveCalls(): Int = adapters.values.sumOf(RecordingAdapter::resolveCalls)
    }

    private class MutableGate(
        var enabled: Boolean = true,
        private val failure: Throwable? = null,
    ) : CanonicalPublicLibraryGate {
        var calls: Int = 0

        override fun isEnabled(): Boolean {
            calls += 1
            failure?.let { throw it }
            return enabled
        }
    }

    private class SensitiveFailure : IllegalStateException(SENSITIVE_MESSAGE)

    private companion object {
        const val SENSITIVE_MESSAGE =
            "private title account source-id path URL token username and exception text"
    }
}
