package app.gamenative.ui.screen.library.appscreen

import android.content.Context
import android.net.Uri
import app.gamenative.PrefManager
import app.gamenative.data.AmazonGame
import app.gamenative.data.GameSource
import app.gamenative.data.DownloadInfo
import app.gamenative.data.LibraryItem
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.library.canonical.CanonicalDiagnosticSink
import app.gamenative.library.canonical.CanonicalLibraryDiagnosticSink
import app.gamenative.library.canonical.CanonicalPublicLibraryGate
import app.gamenative.library.canonical.OwnedCopyOperation
import app.gamenative.library.canonical.action.ActionFailureReason
import app.gamenative.library.canonical.action.OwnedCopyActionGuard
import app.gamenative.library.canonical.runtime.OwnedCopyRuntime
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeAdapter
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeRegistry
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeResult
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGConstants
import app.gamenative.service.gog.GOGService
import app.gamenative.service.SteamService
import app.gamenative.ui.component.dialog.state.MessageDialogState
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.enums.AppOptionMenuType
import app.gamenative.ui.enums.DialogType
import app.gamenative.utils.StorageUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CanonicalActionExecutionTest {
    private val coreOperations = listOf(
        OwnedCopyOperation.PLAY,
        OwnedCopyOperation.INSTALL,
        OwnedCopyOperation.UPDATE,
        OwnedCopyOperation.UNINSTALL,
        OwnedCopyOperation.PAUSE_RESUME_DOWNLOAD,
        OwnedCopyOperation.CANCEL_DOWNLOAD,
        OwnedCopyOperation.EXPORT_SAVES,
        OwnedCopyOperation.IMPORT_SAVES,
    )

    @Test
    fun legacyBoundaryExecutesEveryExistingActionExactlyOnceWithTheOriginalItem() = runTest {
        val original = libraryItem("Original")

        coreOperations.forEach { operation ->
            var calls = 0
            var received: LibraryItem? = null

            executeGuardedAction(
                libraryItem = original,
                actionGuard = null,
                operation = operation,
                onUnavailable = { error("legacy action must not report unavailable") },
            ) { currentItem ->
                calls += 1
                received = currentItem
            }

            assertEquals(operation.name, 1, calls)
            assertSame(operation.name, original, received)
        }
    }

    @Test
    fun canonicalBoundaryRevalidatesEveryCoreOperationAndUsesOnlyTheCurrentItem() = runTest {
        coreOperations.forEach { operation ->
            val fixture = fixture(setOf(operation))
            var received: LibraryItem? = null

            executeGuardedAction(
                libraryItem = fixture.initial,
                actionGuard = fixture.guard,
                operation = operation,
                onUnavailable = { error("unchanged copy must remain available") },
            ) { currentItem ->
                received = currentItem
            }

            assertSame(operation.name, fixture.current, received)
            assertEquals(operation.name, 1, fixture.adapter.resolveCalls)
            assertEquals(operation.name, 0, fixture.siblingCalls())
        }
    }

    @Test
    fun canonicalSourceBoundaryDispatchesTheExactRevalidatedOperationNotChangedScreenState() = runTest {
        val fixture = fixture(setOf(OwnedCopyOperation.INSTALL))
        var sourceState = OwnedCopyOperation.CANCEL_DOWNLOAD
        val calls = mutableListOf<Pair<OwnedCopyOperation, LibraryItem>>()

        executeGuardedSourceAction(
            libraryItem = fixture.initial,
            actionGuard = fixture.guard,
            operation = OwnedCopyOperation.INSTALL,
            onUnavailable = { error("typed source dispatch must remain available") },
            legacyAction = { error("canonical execution must not use the legacy callback") },
        ) { currentItem, validatedOperation ->
            when (validatedOperation) {
                OwnedCopyOperation.INSTALL -> calls += validatedOperation to currentItem
                sourceState -> error("changed source state must not select a different category")
                else -> error("unexpected operation $validatedOperation")
            }
            true
        }

        assertEquals(listOf(OwnedCopyOperation.INSTALL to fixture.current), calls)
        assertEquals(1, fixture.adapter.resolveCalls)
        assertEquals(0, fixture.siblingCalls())
    }

    @Test
    fun changedStateAtTypedSourceBoundaryFailsFixedWithoutAnyCategoryExecutor() = runTest {
        val fixture = fixture(setOf(OwnedCopyOperation.PAUSE_RESUME_DOWNLOAD))
        val unavailable = mutableListOf<ActionFailureReason>()
        val serviceCalls = mutableListOf<OwnedCopyOperation>()

        executeGuardedSourceAction(
            libraryItem = fixture.initial,
            actionGuard = fixture.guard,
            operation = OwnedCopyOperation.PAUSE_RESUME_DOWNLOAD,
            onUnavailable = unavailable::add,
            legacyAction = { error("canonical execution must not use the legacy callback") },
        ) { currentItem, validatedOperation ->
            assertSame(fixture.current, currentItem)
            assertEquals(OwnedCopyOperation.PAUSE_RESUME_DOWNLOAD, validatedOperation)
            false
        }

        assertTrue(serviceCalls.isEmpty())
        assertEquals(listOf(ActionFailureReason.CAPABILITY_CHANGED), unavailable)
        assertEquals(1, fixture.adapter.resolveCalls)
        assertEquals(0, fixture.siblingCalls())
    }

    @Test
    fun typedSourceBoundaryPreservesLegacyCallbackExactlyOnce() = runTest {
        val original = libraryItem("Legacy")
        val sourceOperations = listOf(
            OwnedCopyOperation.PLAY,
            OwnedCopyOperation.INSTALL,
            OwnedCopyOperation.UPDATE,
            OwnedCopyOperation.UNINSTALL,
            OwnedCopyOperation.PAUSE_RESUME_DOWNLOAD,
            OwnedCopyOperation.CANCEL_DOWNLOAD,
        )

        sourceOperations.forEach { operation ->
            var legacyCalls = 0
            var canonicalCalls = 0
            executeGuardedSourceAction(
                libraryItem = original,
                actionGuard = null,
                operation = operation,
                onUnavailable = { error("legacy execution must not fail canonical recovery") },
                legacyAction = { currentItem ->
                    assertSame(operation.name, original, currentItem)
                    legacyCalls += 1
                },
            ) { _, _ ->
                canonicalCalls += 1
                true
            }

            assertEquals(operation.name, 1, legacyCalls)
            assertEquals(operation.name, 0, canonicalCalls)
        }
    }

    @Test
    fun guardedBoundaryHandsTheRevalidatedItemAndOperationToTheActualSteamScreen() = runTest {
        val fixture = fixture(setOf(OwnedCopyOperation.INSTALL))
        val context = mockk<Context>(relaxed = true)
        SteamAppScreen.hideGameManagerDialog(42)
        var legacyCalls = 0

        executeGuardedSourceAction(
            libraryItem = fixture.initial,
            actionGuard = fixture.guard,
            operation = OwnedCopyOperation.INSTALL,
            onUnavailable = { error("current Steam install must remain available") },
            legacyAction = { legacyCalls += 1 },
        ) { currentItem, validatedOperation ->
            SteamAppScreen().onCanonicalOwnedCopyOperation(
                context,
                currentItem,
                validatedOperation,
            ) { _, _ -> error("install must not play") }
        }

        assertEquals(0, legacyCalls)
        assertTrue(SteamAppScreen.getGameManagerDialogState(42)?.visible == true)
        assertEquals(1, fixture.adapter.resolveCalls)
        assertEquals(0, fixture.siblingCalls())
        SteamAppScreen.hideGameManagerDialog(42)
    }

    @Test
    fun everySourceCanonicalPlayDispatchUsesTheExactSourcePrefixedItem() {
        val context = mockk<Context>(relaxed = true)
        val cases = listOf(
            SteamAppScreen() to LibraryItem(appId = "STEAM_42", name = "Steam", gameSource = GameSource.STEAM),
            GOGAppScreen() to LibraryItem(appId = "GOG_42", name = "GOG", gameSource = GameSource.GOG),
            EpicAppScreen() to LibraryItem(appId = "EPIC_42", name = "Epic", gameSource = GameSource.EPIC),
            AmazonAppScreen() to LibraryItem(appId = "AMAZON_42", name = "Amazon", gameSource = GameSource.AMAZON),
            CustomGameAppScreen() to LibraryItem(
                appId = "CUSTOM_GAME_42",
                name = "Custom",
                gameSource = GameSource.CUSTOM_GAME,
            ),
        )

        cases.forEach { (screen, currentItem) ->
            var received: LibraryItem? = null
            val dispatched = screen.onCanonicalOwnedCopyOperation(
                context = context,
                libraryItem = currentItem,
                operation = OwnedCopyOperation.PLAY,
                onClickPlay = { item, confirm ->
                    assertFalse(confirm)
                    received = item
                },
            )

            assertTrue(currentItem.gameSource.name, dispatched)
            assertSame(currentItem.gameSource.name, currentItem, received)
        }
    }

    @Test
    fun everyDownloadSourceCanonicalCancelAndUninstallOpenOnlyTheMatchingConfirmation() {
        val context = mockk<Context>(relaxed = true)
        val cases = listOf(
            CanonicalDialogCase(
                name = "Steam",
                screen = SteamAppScreen(),
                item = LibraryItem(appId = "STEAM_42", gameSource = GameSource.STEAM),
                clearCancel = { SteamAppScreen.hideInstallDialog(42) },
                cancelDialog = { SteamAppScreen.getInstallDialogState(42) },
                clearUninstall = { SteamAppScreen.hideUninstallDialog("STEAM_42") },
                uninstallVisible = { SteamAppScreen.shouldShowUninstallDialog("STEAM_42") },
            ),
            CanonicalDialogCase(
                name = "GOG",
                screen = GOGAppScreen(),
                item = LibraryItem(appId = "GOG_42", gameSource = GameSource.GOG),
                clearCancel = { BaseAppScreen.hideInstallDialog("GOG_42") },
                cancelDialog = { BaseAppScreen.getInstallDialogState("GOG_42") },
                clearUninstall = { GOGAppScreen.hideUninstallDialog("GOG_42") },
                uninstallVisible = { GOGAppScreen.shouldShowUninstallDialog("GOG_42") },
            ),
            CanonicalDialogCase(
                name = "Epic",
                screen = EpicAppScreen(),
                item = LibraryItem(appId = "EPIC_42", gameSource = GameSource.EPIC),
                clearCancel = { BaseAppScreen.hideInstallDialog("EPIC_42") },
                cancelDialog = { BaseAppScreen.getInstallDialogState("EPIC_42") },
                clearUninstall = { EpicAppScreen.hideUninstallDialog("EPIC_42") },
                uninstallVisible = { EpicAppScreen.shouldShowUninstallDialog("EPIC_42") },
            ),
            CanonicalDialogCase(
                name = "Amazon",
                screen = AmazonAppScreen(),
                item = LibraryItem(appId = "AMAZON_42", gameSource = GameSource.AMAZON),
                clearCancel = { BaseAppScreen.hideInstallDialog("AMAZON_42") },
                cancelDialog = { BaseAppScreen.getInstallDialogState("AMAZON_42") },
                clearUninstall = { AmazonAppScreen.hideUninstallDialog("AMAZON_42") },
                uninstallVisible = { AmazonAppScreen.shouldShowUninstallDialog("AMAZON_42") },
            ),
        )

        cases.forEach { case ->
            case.clearCancel()
            case.clearUninstall()
            assertTrue(
                case.name,
                case.screen.onCanonicalOwnedCopyOperation(
                    context,
                    case.item,
                    OwnedCopyOperation.CANCEL_DOWNLOAD,
                ) { _, _ -> error("cancel must not play") },
            )
            assertEquals(case.name, DialogType.CANCEL_APP_DOWNLOAD, case.cancelDialog()?.type)
            assertFalse(case.name, case.uninstallVisible())

            case.clearCancel()
            assertTrue(
                case.name,
                case.screen.onCanonicalOwnedCopyOperation(
                    context,
                    case.item,
                    OwnedCopyOperation.UNINSTALL,
                ) { _, _ -> error("uninstall must not play") },
            )
            assertEquals(case.name, null, case.cancelDialog())
            assertTrue(case.name, case.uninstallVisible())

            case.clearCancel()
            case.clearUninstall()
        }
    }

    @Test
    fun canonicalInstallUsesOnlyTheInstallFlowAndUnsupportedUpdatesStayUnavailable() {
        val context = mockk<Context>(relaxed = true)
        val steamItem = LibraryItem(appId = "STEAM_42", gameSource = GameSource.STEAM)
        val epicItem = LibraryItem(appId = "EPIC_42", gameSource = GameSource.EPIC)
        SteamAppScreen.hideGameManagerDialog(42)
        SteamAppScreen.hideInstallDialog(42)
        SteamAppScreen.hideUninstallDialog(steamItem.appId)
        EpicAppScreen.hideGameManagerDialog(42)
        EpicAppScreen.hideUninstallDialog(epicItem.appId)

        assertTrue(
            SteamAppScreen().onCanonicalOwnedCopyOperation(
                context,
                steamItem,
                OwnedCopyOperation.INSTALL,
            ) { _, _ -> error("install must not play") },
        )
        assertTrue(SteamAppScreen.getGameManagerDialogState(42)?.visible == true)
        assertEquals(null, SteamAppScreen.getInstallDialogState(42))
        assertFalse(SteamAppScreen.shouldShowUninstallDialog(steamItem.appId))

        assertTrue(
            EpicAppScreen().onCanonicalOwnedCopyOperation(
                context,
                epicItem,
                OwnedCopyOperation.INSTALL,
            ) { _, _ -> error("install must not play") },
        )
        assertTrue(EpicAppScreen.getGameManagerDialogState(42)?.visible == true)
        assertEquals(OwnedCopyOperation.INSTALL, EpicAppScreen.getGameManagerOperation(42))
        assertEquals(null, BaseAppScreen.getInstallDialogState(epicItem.appId))
        assertFalse(EpicAppScreen.shouldShowUninstallDialog(epicItem.appId))

        assertFalse(
            GOGAppScreen().onCanonicalOwnedCopyOperation(
                context,
                LibraryItem(appId = "GOG_42", gameSource = GameSource.GOG),
                OwnedCopyOperation.UPDATE,
            ) { _, _ -> error("unsupported update must not play") },
        )
        assertFalse(
            EpicAppScreen().onCanonicalOwnedCopyOperation(
                context,
                epicItem,
                OwnedCopyOperation.UPDATE,
            ) { _, _ -> error("unsupported update must not play") },
        )
        OwnedCopyOperation.entries
            .filter { it != OwnedCopyOperation.PLAY }
            .forEach { operation ->
                assertFalse(
                    operation.name,
                    CustomGameAppScreen().onCanonicalOwnedCopyOperation(
                        context,
                        LibraryItem(
                            appId = "CUSTOM_GAME_42",
                            gameSource = GameSource.CUSTOM_GAME,
                        ),
                        operation,
                    ) { _, _ -> error("unsupported custom operation must not play") },
                )
            }

        SteamAppScreen.hideGameManagerDialog(42)
        EpicAppScreen.hideGameManagerDialog(42)
    }

    @Test
    fun gogCanonicalInstallOpensInstallConfirmationInsteadOfResumingOrPlaying() {
        val context = mockk<Context>(relaxed = true)
        val dataDir = File(checkNotNull(System.getProperty("java.io.tmpdir")), "canonical-gog-install-test")
            .apply { mkdirs() }
        every { context.applicationContext } returns context
        every { context.dataDir } returns dataDir
        GOGConstants.init(context)
        val item = LibraryItem(appId = "GOG_42", name = "GOG", gameSource = GameSource.GOG)
        BaseAppScreen.hideInstallDialog(item.appId)
        GOGAppScreen.hideUninstallDialog(item.appId)
        mockkObject(GOGService.Companion)
        mockkObject(StorageUtils)
        mockkObject(PrefManager)
        try {
            every { PrefManager.useExternalStorage } returns false
            every { GOGService.getGOGGameOf("42") } returns null
            every { StorageUtils.getAvailableSpace(any()) } returns 1024L
            every { StorageUtils.formatBinarySize(any(), any()) } returns "size"

            assertTrue(
                GOGAppScreen().onCanonicalOwnedCopyOperation(
                    context,
                    item,
                    OwnedCopyOperation.INSTALL,
                ) { _, _ -> error("install must not play") },
            )

            val dialog = awaitValue { BaseAppScreen.getInstallDialogState(item.appId) }
            assertEquals(DialogType.INSTALL_APP, dialog.type)
            assertFalse(GOGAppScreen.shouldShowUninstallDialog(item.appId))
        } finally {
            BaseAppScreen.hideInstallDialog(item.appId)
            GOGAppScreen.hideUninstallDialog(item.appId)
            unmockkObject(PrefManager)
            unmockkObject(StorageUtils)
            unmockkObject(GOGService.Companion)
        }
    }

    @Test
    fun amazonCanonicalInstallOpensInstallConfirmationInsteadOfResumingOrPlaying() {
        val context = mockk<Context>(relaxed = true)
        val dataDir = File(checkNotNull(System.getProperty("java.io.tmpdir")), "canonical-amazon-install-test")
            .apply { mkdirs() }
        every { context.dataDir } returns dataDir
        val item = LibraryItem(appId = "AMAZON_42", name = "Amazon", gameSource = GameSource.AMAZON)
        AmazonAppScreen.hideAmazonInstallDialog(item.appId)
        AmazonAppScreen.hideUninstallDialog(item.appId)
        mockkObject(AmazonService.Companion)
        mockkObject(StorageUtils)
        mockkObject(PrefManager)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            every { PrefManager.useExternalStorage } returns false
            every { AmazonService.getProductIdByAppId(42) } returns "product"
            every { AmazonService.getAmazonGameOf("product") } returns null
            coEvery { AmazonService.fetchDownloadSize("product") } returns null
            every { StorageUtils.getAvailableSpace(any()) } returns 1024L
            every { StorageUtils.formatBinarySize(any(), any()) } returns "size"

            assertTrue(
                AmazonAppScreen().onCanonicalOwnedCopyOperation(
                    context,
                    item,
                    OwnedCopyOperation.INSTALL,
                ) { _, _ -> error("install must not play") },
            )

            val dialog = awaitValue { AmazonAppScreen.getAmazonInstallDialogData(item.appId) }
            assertTrue(dialog.installEnabled)
            assertFalse(AmazonAppScreen.shouldShowUninstallDialog(item.appId))
            assertEquals(null, BaseAppScreen.getInstallDialogState(item.appId))
        } finally {
            AmazonAppScreen.hideAmazonInstallDialog(item.appId)
            AmazonAppScreen.hideUninstallDialog(item.appId)
            Dispatchers.resetMain()
            unmockkObject(PrefManager)
            unmockkObject(StorageUtils)
            unmockkObject(AmazonService.Companion)
        }
    }

    @Test
    fun everyDownloadSourceCanonicalPauseResumePausesWithoutOpeningAnotherCategory() {
        val context = mockk<Context>(relaxed = true)
        val steamInfo = downloadInfo(42)
        val gogInfo = downloadInfo(42)
        val epicInfo = downloadInfo(42)
        val amazonInfo = downloadInfo(42)
        mockkObject(SteamService.Companion)
        mockkObject(GOGService.Companion)
        mockkObject(EpicService.Companion)
        mockkObject(AmazonService.Companion)
        try {
            every { SteamService.getAppDownloadInfo(42) } returns steamInfo
            every { GOGService.getDownloadInfo("42") } returns gogInfo
            every { GOGService.cleanupDownload("42") } returns Unit
            every { EpicService.getDownloadInfo(42) } returns epicInfo
            coEvery { EpicService.cleanupDownload(context, 42) } returns Unit
            every { AmazonService.getDownloadInfoByAppId(42) } returns amazonInfo
            every { AmazonService.cancelDownloadByAppId(42) } returns true

            val cases = listOf(
                SteamAppScreen() to LibraryItem(appId = "STEAM_42", gameSource = GameSource.STEAM),
                GOGAppScreen() to LibraryItem(appId = "GOG_42", gameSource = GameSource.GOG),
                EpicAppScreen() to LibraryItem(appId = "EPIC_42", gameSource = GameSource.EPIC),
                AmazonAppScreen() to LibraryItem(appId = "AMAZON_42", gameSource = GameSource.AMAZON),
            )
            cases.forEach { (screen, item) ->
                assertTrue(
                    item.gameSource.name,
                    screen.onCanonicalOwnedCopyOperation(
                        context,
                        item,
                        OwnedCopyOperation.PAUSE_RESUME_DOWNLOAD,
                    ) { _, _ -> error("pause/resume must not play") },
                )
                assertEquals(item.gameSource.name, null, BaseAppScreen.getInstallDialogState(item.appId))
            }

            assertFalse(steamInfo.isActive())
            assertFalse(gogInfo.isActive())
            assertFalse(epicInfo.isActive())
            verify(exactly = 1) { GOGService.cleanupDownload("42") }
            verify(exactly = 1) { AmazonService.cancelDownloadByAppId(42) }
            coVerify(timeout = 2_000, exactly = 1) { EpicService.cleanupDownload(context, 42) }
        } finally {
            unmockkObject(AmazonService.Companion)
            unmockkObject(EpicService.Companion)
            unmockkObject(GOGService.Companion)
            unmockkObject(SteamService.Companion)
        }
    }

    @Test
    fun stalePauseResumeStateFailsWithoutMorphingIntoFreshInstall() {
        val context = mockk<Context>(relaxed = true)
        mockkObject(SteamService.Companion)
        mockkObject(GOGService.Companion)
        mockkObject(EpicService.Companion)
        mockkObject(AmazonService.Companion)
        try {
            SteamAppScreen.hideGameManagerDialog(42)
            EpicAppScreen.hideGameManagerDialog(42)
            AmazonAppScreen.hideAmazonInstallDialog("AMAZON_42")
            SteamService.workshopPausedApps.clear()
            every { SteamService.getAppDownloadInfo(42) } returns null
            every { SteamService.hasPartialDownload(42) } returns false
            every { GOGService.getDownloadInfo("42") } returns null
            every { GOGService.hasPartialDownload("42", any()) } returns false
            every { EpicService.getDownloadInfo(42) } returns null
            every { EpicService.hasPartialDownload(context, 42) } returns false
            every { AmazonService.getDownloadInfoByAppId(42) } returns null
            every { AmazonService.hasPartialDownloadByAppId(context, 42) } returns false

            val cases = listOf(
                SteamAppScreen() to LibraryItem(appId = "STEAM_42", gameSource = GameSource.STEAM),
                GOGAppScreen() to LibraryItem(appId = "GOG_42", gameSource = GameSource.GOG),
                EpicAppScreen() to LibraryItem(appId = "EPIC_42", gameSource = GameSource.EPIC),
                AmazonAppScreen() to LibraryItem(appId = "AMAZON_42", gameSource = GameSource.AMAZON),
            )
            cases.forEach { (screen, item) ->
                assertFalse(
                    item.gameSource.name,
                    screen.onCanonicalOwnedCopyOperation(
                        context,
                        item,
                        OwnedCopyOperation.PAUSE_RESUME_DOWNLOAD,
                    ) { _, _ -> error("stale pause/resume must not play") },
                )
                assertEquals(item.gameSource.name, null, BaseAppScreen.getInstallDialogState(item.appId))
            }
            assertEquals(null, SteamAppScreen.getGameManagerDialogState(42))
            assertEquals(null, EpicAppScreen.getGameManagerDialogState(42))
            assertEquals(null, AmazonAppScreen.getAmazonInstallDialogData("AMAZON_42"))
        } finally {
            unmockkObject(AmazonService.Companion)
            unmockkObject(EpicService.Companion)
            unmockkObject(GOGService.Companion)
            unmockkObject(SteamService.Companion)
        }
    }

    @Test
    fun steamAndAmazonCanonicalUpdateDispatchOnlyTheirUpdateExecutors() {
        val context = mockk<Context>(relaxed = true)
        val dataDir = File(checkNotNull(System.getProperty("java.io.tmpdir")), "canonical-update-test")
            .apply { mkdirs() }
        every { context.dataDir } returns dataDir
        val amazonGame = mockk<AmazonGame>(relaxed = true)
        every { amazonGame.title } returns "Amazon"
        mockkObject(SteamService.Companion)
        mockkObject(AmazonService.Companion)
        mockkObject(PrefManager)
        try {
            every { PrefManager.useExternalStorage } returns false
            every { SteamService.downloadApp(42) } returns null
            every { AmazonService.getProductIdByAppId(42) } returns "product"
            every { AmazonService.getAmazonGameOf("product") } returns amazonGame
            coEvery { AmazonService.downloadGame(context, "product", any()) } returns
                Result.success(downloadInfo(42))

            assertTrue(
                SteamAppScreen().onCanonicalOwnedCopyOperation(
                    context,
                    LibraryItem(appId = "STEAM_42", gameSource = GameSource.STEAM),
                    OwnedCopyOperation.UPDATE,
                ) { _, _ -> error("update must not play") },
            )
            assertTrue(
                AmazonAppScreen().onCanonicalOwnedCopyOperation(
                    context,
                    LibraryItem(appId = "AMAZON_42", gameSource = GameSource.AMAZON),
                    OwnedCopyOperation.UPDATE,
                ) { _, _ -> error("update must not play") },
            )

            verify(timeout = 2_000, exactly = 1) { SteamService.downloadApp(42) }
            coVerify(timeout = 2_000, exactly = 1) {
                AmazonService.downloadGame(context, "product", any())
            }
        } finally {
            unmockkObject(PrefManager)
            unmockkObject(AmazonService.Companion)
            unmockkObject(SteamService.Companion)
        }
    }

    @Test
    fun baseContentWiresGuardedOperationsToTheTypedSourceBoundary() {
        val baseSource = File(
            repositoryRoot(),
            "app/src/main/java/app/gamenative/ui/screen/library/appscreen/BaseAppScreen.kt",
        ).readText()

        assertTrue(
            Regex(
                """executeGuardedSourceAction\([\s\S]*?sourceAction = \{ currentItem, validatedOperation ->[\s\S]*?onCanonicalOwnedCopyOperation\(""",
            ).containsMatchIn(baseSource),
        )
        assertTrue(baseSource.contains("legacyAction(libraryItem)"))
    }

    @Test
    fun steamManageGameContentIsHiddenOnlyWhileCanonicalGuardIsActive() {
        val options = listOf(
            AppMenuOption(AppOptionMenuType.ManageGameContent) {},
            AppMenuOption(AppOptionMenuType.ManageWorkshop) {},
        )

        assertSame(options, optionsForActionGuard(null, options))
        assertEquals(
            listOf(AppOptionMenuType.ManageWorkshop),
            optionsForActionGuard(fixture(setOf(OwnedCopyOperation.PLAY)).guard, options)
                .map(AppMenuOption::optionType),
        )
    }

    @Test
    fun accountEntitlementAndCapabilityChangesBlockTheSourceActionWithFixedFeedback() = runTest {
        listOf(
            OwnedCopyRuntimeResult.Hidden,
            unavailableResult(),
            availableRuntime(capabilities = emptySet()),
        ).forEach { changedResult ->
            val fixture = fixture(setOf(OwnedCopyOperation.PLAY))
            fixture.adapter.result = changedResult
            val unavailable = mutableListOf<ActionFailureReason>()
            var sourceCalls = 0

            executeGuardedAction(
                libraryItem = fixture.initial,
                actionGuard = fixture.guard,
                operation = OwnedCopyOperation.PLAY,
                onUnavailable = unavailable::add,
            ) { sourceCalls += 1 }

            assertEquals(0, sourceCalls)
            assertEquals(1, unavailable.size)
            assertTrue(
                unavailable.single() in setOf(
                    ActionFailureReason.COPY_UNAVAILABLE,
                    ActionFailureReason.TARGET_CHANGED,
                    ActionFailureReason.CAPABILITY_CHANGED,
                ),
            )
            assertEquals(0, fixture.siblingCalls())
        }
    }

    @Test
    fun confirmationCommitRevalidatesAgainAfterTheDialogWasOpened() = runTest {
        val fixture = fixture(setOf(OwnedCopyOperation.UNINSTALL))
        var dialogOpenCalls = 0
        var uninstallCalls = 0
        val unavailable = mutableListOf<ActionFailureReason>()

        executeGuardedAction(
            libraryItem = fixture.initial,
            actionGuard = fixture.guard,
            operation = OwnedCopyOperation.UNINSTALL,
            onUnavailable = unavailable::add,
        ) { dialogOpenCalls += 1 }

        fixture.adapter.result = availableRuntime(capabilities = emptySet())
        executeGuardedAction(
            libraryItem = fixture.initial,
            actionGuard = fixture.guard,
            operation = OwnedCopyOperation.UNINSTALL,
            onUnavailable = unavailable::add,
        ) { uninstallCalls += 1 }

        assertEquals(1, dialogOpenCalls)
        assertEquals(0, uninstallCalls)
        assertEquals(listOf(ActionFailureReason.CAPABILITY_CHANGED), unavailable)
        assertEquals(2, fixture.adapter.resolveCalls)
    }

    @Test
    fun confirmationStateIsDismissedBeforeGuardCanSuspend() {
        val events = mutableListOf<String>()
        var pendingCommit: ((LibraryItem) -> Unit)? = null

        executeGuardedConfirmation(
            operation = OwnedCopyOperation.UNINSTALL,
            onDismiss = { events += "dismiss" },
            guardedAction = { operation, action ->
                events += "guard:$operation"
                pendingCommit = action
            },
        ) {
            events += "commit"
        }

        assertEquals(listOf("dismiss", "guard:UNINSTALL"), events)
        pendingCommit?.invoke(libraryItem("Current"))
        assertEquals(listOf("dismiss", "guard:UNINSTALL", "commit"), events)
    }

    @Test
    fun saveTransferRevalidatesAfterTheDocumentPickerReturns() = runTest {
        listOf(
            OwnedCopyOperation.EXPORT_SAVES,
            OwnedCopyOperation.IMPORT_SAVES,
        ).forEach { operation ->
            val fixture = fixture(setOf(operation))
            val uri = mockk<Uri>()
            var clearCalls = 0
            var transferCalls = 0
            val unavailable = mutableListOf<ActionFailureReason>()

            executeGuardedSavePickerResult(
                uri = uri,
                libraryItem = fixture.initial,
                actionGuard = fixture.guard,
                operation = operation,
                onUnavailable = unavailable::add,
                onCleared = { clearCalls += 1 },
            ) { currentItem, currentUri ->
                assertSame(fixture.current, currentItem)
                assertSame(uri, currentUri)
                transferCalls += 1
            }

            fixture.adapter.result = OwnedCopyRuntimeResult.Hidden
            executeGuardedSavePickerResult(
                uri = uri,
                libraryItem = fixture.initial,
                actionGuard = fixture.guard,
                operation = operation,
                onUnavailable = unavailable::add,
                onCleared = { clearCalls += 1 },
            ) { currentItem, currentUri ->
                assertSame(fixture.current, currentItem)
                assertSame(uri, currentUri)
                transferCalls += 1
            }

            assertEquals(operation.name, 1, transferCalls)
            assertEquals(operation.name, 2, clearCalls)
            assertEquals(operation.name, listOf(ActionFailureReason.COPY_UNAVAILABLE), unavailable)
            assertEquals(operation.name, 2, fixture.adapter.resolveCalls)
            assertEquals(operation.name, 0, fixture.siblingCalls())
        }
    }

    @Test
    fun savePickerCancellationPropagatesAndStillConsumesTheRequest() = runTest {
        val fixture = fixture(setOf(OwnedCopyOperation.EXPORT_SAVES))
        var clearCalls = 0
        var cancelled = false
        val unavailable = mutableListOf<ActionFailureReason>()

        try {
            executeGuardedSavePickerResult(
                uri = mockk<Uri>(),
                libraryItem = fixture.initial,
                actionGuard = fixture.guard,
                operation = OwnedCopyOperation.EXPORT_SAVES,
                onUnavailable = unavailable::add,
                onCleared = { clearCalls += 1 },
            ) { _, _ ->
                throw CancellationException("picker transfer cancelled")
            }
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(1, clearCalls)
        assertTrue(unavailable.isEmpty())
        assertEquals(1, fixture.adapter.resolveCalls)
        assertEquals(0, fixture.siblingCalls())
    }

    @Test
    fun dynamicPrimaryAndDeleteActionsMapToTheExactCurrentOperation() {
        assertEquals(
            OwnedCopyOperation.PLAY,
            primaryOwnedCopyOperation(isInstalled = true, isDownloading = false, hasPartialDownload = false),
        )
        assertEquals(
            OwnedCopyOperation.PAUSE_RESUME_DOWNLOAD,
            primaryOwnedCopyOperation(isInstalled = false, isDownloading = true, hasPartialDownload = false),
        )
        assertEquals(
            OwnedCopyOperation.PAUSE_RESUME_DOWNLOAD,
            primaryOwnedCopyOperation(isInstalled = false, isDownloading = false, hasPartialDownload = true),
        )
        assertEquals(
            OwnedCopyOperation.INSTALL,
            primaryOwnedCopyOperation(isInstalled = false, isDownloading = false, hasPartialDownload = false),
        )
        assertEquals(
            OwnedCopyOperation.CANCEL_DOWNLOAD,
            deleteOwnedCopyOperation(
                isInstalled = false,
                isDownloading = true,
                hasPartialDownload = false,
                hasLeftoverInstall = false,
            ),
        )
        assertEquals(
            OwnedCopyOperation.CANCEL_DOWNLOAD,
            deleteOwnedCopyOperation(
                isInstalled = true,
                isDownloading = false,
                hasPartialDownload = true,
                hasLeftoverInstall = false,
            ),
        )
        assertEquals(
            OwnedCopyOperation.UNINSTALL,
            deleteOwnedCopyOperation(
                isInstalled = true,
                isDownloading = false,
                hasPartialDownload = false,
                hasLeftoverInstall = false,
            ),
        )
        assertEquals(
            OwnedCopyOperation.UNINSTALL,
            deleteOwnedCopyOperation(
                isInstalled = false,
                isDownloading = false,
                hasPartialDownload = false,
                hasLeftoverInstall = true,
            ),
        )
    }

    @Test
    fun initialOperationConsumesParentStateBeforeSuspensionAndCannotReplay() = runTest {
        val fixture = fixture(setOf(OwnedCopyOperation.INSTALL))
        var pending: OwnedCopyOperation? = OwnedCopyOperation.INSTALL
        val executeStarted = CompletableDeferred<Unit>()
        val releaseExecute = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var executions = 0

        val first = async {
            consumeInitialOperation(
                actionGuard = fixture.guard,
                initialOperation = pending,
                onConsumed = {
                    events += "consumed"
                    pending = null
                },
                onUnavailable = { error("initial operation must remain available") },
            ) { operation ->
                events += "execute:$operation"
                executeStarted.complete(Unit)
                releaseExecute.await()
                executions += 1
            }
        }
        executeStarted.await()
        assertEquals(null, pending)
        assertEquals(listOf("consumed", "execute:INSTALL"), events)

        consumeInitialOperation(
            actionGuard = fixture.guard,
            initialOperation = pending,
            onConsumed = { error("consumed state must not replay") },
            onUnavailable = { error("consumed state must not fail") },
        ) { executions += 1 }
        releaseExecute.complete(Unit)
        first.await()

        assertEquals(1, executions)
    }

    @Test
    fun failedInitialRevalidationIsConsumedAndCannotReplay() = runTest {
        val fixture = fixture(setOf(OwnedCopyOperation.PLAY))
        fixture.adapter.result = OwnedCopyRuntimeResult.Hidden
        var pending: OwnedCopyOperation? = OwnedCopyOperation.PLAY
        var sourceCalls = 0
        val unavailable = mutableListOf<ActionFailureReason>()

        consumeInitialOperation(
            actionGuard = fixture.guard,
            initialOperation = pending,
            onConsumed = { pending = null },
            onUnavailable = unavailable::add,
        ) { operation ->
            executeGuardedAction(
                libraryItem = fixture.initial,
                actionGuard = fixture.guard,
                operation = operation,
                onUnavailable = unavailable::add,
            ) { sourceCalls += 1 }
        }
        consumeInitialOperation(
            actionGuard = fixture.guard,
            initialOperation = pending,
            onConsumed = { error("failed operation must already be consumed") },
            onUnavailable = unavailable::add,
        ) { sourceCalls += 1 }

        assertEquals(0, sourceCalls)
        assertEquals(listOf(ActionFailureReason.COPY_UNAVAILABLE), unavailable)
        assertEquals(1, fixture.adapter.resolveCalls)
        assertEquals(0, fixture.siblingCalls())
    }

    @Test
    fun processRecreationWithoutTheMemoryGuardFailsClosedInsteadOfExecutingLegacyAction() = runTest {
        var consumed = false
        var executions = 0
        val unavailable = mutableListOf<ActionFailureReason>()

        consumeInitialOperation(
            actionGuard = null,
            initialOperation = OwnedCopyOperation.PLAY,
            onConsumed = { consumed = true },
            onUnavailable = unavailable::add,
        ) { executions += 1 }

        assertTrue(consumed)
        assertEquals(0, executions)
        assertEquals(listOf(ActionFailureReason.COPY_UNAVAILABLE), unavailable)
    }

    @Test
    fun canonicalStateMutationRunsOnTheSuppliedMainDispatcher() = runTest {
        val fixture = fixture(setOf(OwnedCopyOperation.PLAY))
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "canonical-action-main")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            var actionThread = ""
            executeGuardedAction(
                libraryItem = fixture.initial,
                actionGuard = fixture.guard,
                operation = OwnedCopyOperation.PLAY,
                onUnavailable = { error("copy must remain available") },
                actionDispatcher = dispatcher,
            ) {
                actionThread = Thread.currentThread().name.orEmpty()
            }

            assertTrue(actionThread.contains("canonical-action-main"))
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun sourceScanKeepsRuntimeItemsPrefixedAndCanonicalIdentityOutOfLegacyExecutors() {
        val root = repositoryRoot()
        val runtime = File(
            root,
            "app/src/main/java/app/gamenative/library/canonical/runtime/OwnedCopyRuntimeAdapter.kt",
        ).readText()
        assertTrue(runtime.contains("internal fun sourceAppId(source: GameSource, id: Any): String = \"\${source.name}_\$id\""))

        listOf(
            "SteamOwnedCopyRuntimeAdapter.kt" to "STEAM",
            "GogOwnedCopyRuntimeAdapter.kt" to "GOG",
            "EpicOwnedCopyRuntimeAdapter.kt" to "EPIC",
            "AmazonOwnedCopyRuntimeAdapter.kt" to "AMAZON",
            "CustomOwnedCopyRuntimeAdapter.kt" to "CUSTOM_GAME",
        ).forEach { (fileName, sourceName) ->
            val source = File(
                root,
                "app/src/main/java/app/gamenative/library/canonical/runtime/$fileName",
            ).readText()
            assertTrue("$sourceName runtime must build LibraryItem through sourceAppId", source.contains("appId = sourceAppId(source,"))
            assertFalse("$sourceName runtime must not use canonical identity as appId", source.contains("appId = canonicalId"))
        }

        val sourceScreenOperations = mapOf(
            "SteamAppScreen.kt" to setOf(
                OwnedCopyOperation.INSTALL,
                OwnedCopyOperation.UPDATE,
                OwnedCopyOperation.UNINSTALL,
                OwnedCopyOperation.CANCEL_DOWNLOAD,
            ),
            "GOGAppScreen.kt" to setOf(
                OwnedCopyOperation.INSTALL,
                OwnedCopyOperation.UNINSTALL,
                OwnedCopyOperation.CANCEL_DOWNLOAD,
            ),
            "EpicAppScreen.kt" to setOf(
                OwnedCopyOperation.INSTALL,
                OwnedCopyOperation.UNINSTALL,
                OwnedCopyOperation.CANCEL_DOWNLOAD,
            ),
            "AmazonAppScreen.kt" to setOf(
                OwnedCopyOperation.INSTALL,
                OwnedCopyOperation.UNINSTALL,
                OwnedCopyOperation.CANCEL_DOWNLOAD,
            ),
        )
        sourceScreenOperations.forEach { (fileName, operations) ->
            val source = File(
                root,
                "app/src/main/java/app/gamenative/ui/screen/library/appscreen/$fileName",
            ).readText()
            operations.forEach { operation ->
                assertTrue(
                    "$fileName must guard ${operation.name} confirmation commits",
                    Regex(
                        """executeGuardedConfirmation\(\s*operation = OwnedCopyOperation\.${operation.name},""",
                    ).containsMatchIn(source),
                )
            }
        }
        val epicSource = File(
            root,
            "app/src/main/java/app/gamenative/ui/screen/library/appscreen/EpicAppScreen.kt",
        ).readText()
        assertTrue(epicSource.contains("gameManagerOperations[gameId] = operation"))
        assertTrue(epicSource.contains("OwnedCopyOperation.PAUSE_RESUME_DOWNLOAD"))
        assertTrue(epicSource.contains("operation = operation"))
        assertTrue(epicSource.contains("guardedAction = guardedAction"))

        listOf("GOGAppScreen.kt", "EpicAppScreen.kt").forEach { fileName ->
            val source = File(
                root,
                "app/src/main/java/app/gamenative/ui/screen/library/appscreen/$fileName",
            ).readText()
            assertFalse(
                "$fileName must not add canonical update execution",
                Regex(
                    """executeGuardedConfirmation\(\s*operation = OwnedCopyOperation\.UPDATE,""",
                ).containsMatchIn(source),
            )
        }
        val baseScreen = File(
            root,
            "app/src/main/java/app/gamenative/ui/screen/library/appscreen/BaseAppScreen.kt",
        ).readText()
        assertTrue(baseScreen.contains("LaunchedEffect(actionGuard, initialOperation)"))
        assertTrue(baseScreen.contains("operation = OwnedCopyOperation.EXPORT_SAVES"))
        assertTrue(baseScreen.contains("operation = OwnedCopyOperation.IMPORT_SAVES"))

        listOf(
            "app/src/main/java/app/gamenative/ui/PluviaMain.kt",
            "app/src/main/java/app/gamenative/sync/FrontendSyncManager.kt",
            "app/src/main/java/app/gamenative/data/LibraryItem.kt",
            "app/src/main/java/app/gamenative/utils/ContainerUtils.kt",
            "app/src/main/java/app/gamenative/utils/IntentLaunchManager.kt",
        ).forEach { relativePath ->
            val source = File(root, relativePath).readText()
            assertFalse(relativePath, Regex("CanonicalGameId|OwnedCopyKey|canonicalId").containsMatchIn(source))
        }
    }

    private fun fixture(capabilities: Set<OwnedCopyOperation>): Fixture {
        val selected = RecordingAdapter(GameSource.STEAM)
        val adapters = GameSource.entries.associateWith { source ->
            if (source == GameSource.STEAM) selected else RecordingAdapter(source)
        }
        val history = mockk<LibraryPlayHistoryDao>()
        every { history.getAll() } returns emptyFlow()
        val registry = OwnedCopyRuntimeRegistry(
            adapters.values.toSet(),
            history,
            mockk<CanonicalDiagnosticSink>(relaxed = true),
        )
        val initial = libraryItem("Initial")
        val current = libraryItem("Current")
        selected.result = availableRuntime(capabilities = capabilities, libraryItem = current)
        val guard = OwnedCopyActionGuard(
            key = key(),
            capturedReference = reference(),
            initialLibraryItem = initial,
            runtimeRegistry = registry,
            publicGate = CanonicalPublicLibraryGate { true },
            diagnostics = mockk<CanonicalLibraryDiagnosticSink>(relaxed = true),
        )
        return Fixture(initial, current, guard, selected, adapters)
    }

    private fun availableRuntime(
        reference: SourceOwnedCopyReference = reference(),
        capabilities: Set<OwnedCopyOperation> = setOf(OwnedCopyOperation.PLAY),
        libraryItem: LibraryItem = libraryItem("Current"),
    ): OwnedCopyRuntimeResult.Available = OwnedCopyRuntimeResult.Available(
        OwnedCopyRuntime(
            key = key(),
            reference = reference,
            libraryItem = libraryItem,
            nativeTitle = libraryItem.name,
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
            isDownloading = OwnedCopyOperation.PAUSE_RESUME_DOWNLOAD in capabilities,
            hasPartialDownload = OwnedCopyOperation.CANCEL_DOWNLOAD in capabilities,
            updateAvailable = OwnedCopyOperation.UPDATE in capabilities,
            isShared = false,
            lastPlayedEpochMs = null,
            playtimeMinutes = null,
            capabilities = capabilities,
        ),
    )

    private fun unavailableResult(): OwnedCopyRuntimeResult.Unavailable =
        OwnedCopyRuntimeResult.Unavailable(
            key(),
            app.gamenative.library.canonical.CopyUnavailableReason.SOURCE_ROW_CHANGED,
        )

    private fun key(): OwnedCopyKey = OwnedCopyKey(
        AccountScope.parse("a".repeat(64)),
        GameSource.STEAM,
        "42",
    )

    private fun reference(): SourceOwnedCopyReference.Steam =
        SourceOwnedCopyReference.Steam(key(), 42)

    private fun libraryItem(name: String): LibraryItem = LibraryItem(
        appId = "STEAM_42",
        name = name,
        gameSource = GameSource.STEAM,
    )

    private fun repositoryRoot(): File {
        val workingDirectory = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(workingDirectory) { it.parentFile }
            .first { File(it, "app/src/main/java").isDirectory }
    }

    private class RecordingAdapter(
        override val source: GameSource,
    ) : OwnedCopyRuntimeAdapter {
        var resolveCalls = 0
        var result: OwnedCopyRuntimeResult = OwnedCopyRuntimeResult.Hidden

        override fun invalidations(): Flow<Unit> = emptyFlow()

        override suspend fun resolve(key: OwnedCopyKey): OwnedCopyRuntimeResult {
            resolveCalls += 1
            return result
        }

        override suspend fun resolveAll(
            keys: Set<OwnedCopyKey>,
        ): Map<OwnedCopyKey, OwnedCopyRuntimeResult> = keys.associateWith { result }
    }

    private fun downloadInfo(gameId: Int): DownloadInfo = DownloadInfo(
        gameId = gameId,
        downloadingAppIds = CopyOnWriteArrayList(listOf(gameId)),
    )

    private fun <T : Any> awaitValue(value: () -> T?): T {
        repeat(200) {
            value()?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for source-screen state")
    }

    private data class CanonicalDialogCase(
        val name: String,
        val screen: BaseAppScreen,
        val item: LibraryItem,
        val clearCancel: () -> Unit,
        val cancelDialog: () -> MessageDialogState?,
        val clearUninstall: () -> Unit,
        val uninstallVisible: () -> Boolean,
    )

    private data class Fixture(
        val initial: LibraryItem,
        val current: LibraryItem,
        val guard: OwnedCopyActionGuard,
        val adapter: RecordingAdapter,
        val adapters: Map<GameSource, RecordingAdapter>,
    ) {
        fun siblingCalls(): Int = adapters
            .filterKeys { it != GameSource.STEAM }
            .values
            .sumOf(RecordingAdapter::resolveCalls)
    }
}
