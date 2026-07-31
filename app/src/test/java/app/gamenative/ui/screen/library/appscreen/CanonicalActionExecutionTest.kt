package app.gamenative.ui.screen.library.appscreen

import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.library.canonical.CanonicalDiagnosticSink
import app.gamenative.library.canonical.CanonicalPublicLibraryGate
import app.gamenative.library.canonical.OwnedCopyOperation
import app.gamenative.library.canonical.action.ActionFailureReason
import app.gamenative.library.canonical.action.OwnedCopyActionGuard
import app.gamenative.library.canonical.runtime.OwnedCopyRuntime
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeAdapter
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeRegistry
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeResult
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
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
            var pickerLaunches = 0
            var transferCalls = 0
            val unavailable = mutableListOf<ActionFailureReason>()

            executeGuardedAction(
                libraryItem = fixture.initial,
                actionGuard = fixture.guard,
                operation = operation,
                onUnavailable = unavailable::add,
            ) { pickerLaunches += 1 }

            fixture.adapter.result = OwnedCopyRuntimeResult.Hidden
            executeGuardedAction(
                libraryItem = fixture.initial,
                actionGuard = fixture.guard,
                operation = operation,
                onUnavailable = unavailable::add,
            ) { transferCalls += 1 }

            assertEquals(operation.name, 1, pickerLaunches)
            assertEquals(operation.name, 0, transferCalls)
            assertEquals(operation.name, listOf(ActionFailureReason.COPY_UNAVAILABLE), unavailable)
        }
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
        val events = mutableListOf<String>()
        var executions = 0

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
            executions += 1
        }
        consumeInitialOperation(
            actionGuard = fixture.guard,
            initialOperation = pending,
            onConsumed = { error("consumed state must not replay") },
            onUnavailable = { error("consumed state must not fail") },
        ) { executions += 1 }

        assertEquals(listOf("consumed", "execute:INSTALL"), events)
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
