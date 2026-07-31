package app.gamenative.library.canonical.action

import android.os.Parcelable
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.CanonicalAppType
import app.gamenative.data.canonical.EpicStableSourceId
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.library.canonical.CanonicalDiagnosticSink
import app.gamenative.library.canonical.CanonicalLibraryDiagnosticSink
import app.gamenative.library.canonical.CanonicalPublicLibraryGate
import app.gamenative.library.canonical.CopyUnavailableReason
import app.gamenative.library.canonical.OwnedCopyOperation
import app.gamenative.library.canonical.runtime.OwnedCopyRuntime
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeAdapter
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeRegistry
import app.gamenative.library.canonical.runtime.OwnedCopyRuntimeResult
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.Serializable
import java.lang.reflect.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedCopyActionGuardTest {
    private val scope = AccountScope.parse("a".repeat(64))

    @Test
    fun everyCapturedTargetMutationThatRetainsKeyIdentityFailsWithoutSiblingFallback() = runTest {
        val mutations = validReferenceMutations()

        mutations.forEach { mutation ->
            val fixture = fixture(mutation.key)
            fixture.selected.handler = {
                OwnedCopyRuntimeResult.Available(
                    runtime(
                        mutation.key,
                        mutation.changed,
                        capabilities = setOf(OwnedCopyOperation.PLAY),
                    ),
                )
            }
            val guard = guard(
                key = mutation.key,
                capturedReference = mutation.captured,
                registry = fixture.registry,
                gate = fixture.gate,
            )

            val result = guard.revalidate(OwnedCopyOperation.PLAY)

            assertEquals(
                mutation.name,
                ActionRevalidationResult.Unavailable(ActionFailureReason.TARGET_CHANGED),
                result,
            )
            assertEquals(mutation.name, 1, fixture.selected.resolveCalls)
            assertEquals(mutation.name, 0, fixture.siblingCalls())
        }
    }

    @Test
    fun everySourceRevalidationRejectsWrongProviderReferenceWithoutSiblingFallback() = runTest {
        exactIdentityCases().forEach { identity ->
            val fixture = fixture(identity.key)
            fixture.selected.handler = {
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
            val guard = guard(
                key = identity.key,
                capturedReference = identity.validReference,
                registry = fixture.registry,
                gate = fixture.gate,
                initialLibraryItem = libraryItem(
                    identity.key.source,
                    identity.validLibraryItemId,
                ),
            )

            assertSuspendThrows(IllegalStateException::class.java) {
                guard.revalidate(OwnedCopyOperation.PLAY)
            }
            assertEquals(identity.name, 1, fixture.selected.resolveCalls)
            assertEquals(identity.name, 0, fixture.siblingCalls())
        }
    }

    @Test
    fun everySourceRevalidationRejectsWrongSameSourceExecutableIdWithoutSiblingFallback() = runTest {
        exactIdentityCases().forEach { identity ->
            val fixture = fixture(identity.key)
            fixture.selected.handler = {
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
            val guard = guard(
                key = identity.key,
                capturedReference = identity.validReference,
                registry = fixture.registry,
                gate = fixture.gate,
                initialLibraryItem = libraryItem(
                    identity.key.source,
                    identity.validLibraryItemId,
                ),
            )

            assertSuspendThrows(IllegalStateException::class.java) {
                guard.revalidate(OwnedCopyOperation.PLAY)
            }
            assertEquals(identity.name, 1, fixture.selected.resolveCalls)
            assertEquals(identity.name, 0, fixture.siblingCalls())
        }
    }

    @Test
    fun hiddenAccountLifecycleOrEntitlementLossAndTypedUnavailableFailClosed() = runTest {
        val key = key(GameSource.GOG, "123")
        val reference = SourceOwnedCopyReference.Gog(key, "123")
        val results = listOf(
            "account scope changed" to OwnedCopyRuntimeResult.Hidden,
            "lifecycle no longer ready" to OwnedCopyRuntimeResult.Hidden,
            "entitlement removed" to OwnedCopyRuntimeResult.Hidden,
            "source row changed" to OwnedCopyRuntimeResult.Unavailable(
                key,
                CopyUnavailableReason.SOURCE_ROW_CHANGED,
            ),
            "source read failed" to OwnedCopyRuntimeResult.Unavailable(
                key,
                CopyUnavailableReason.SOURCE_READ_FAILED,
            ),
        )

        results.forEach { (name, runtimeResult) ->
            val fixture = fixture(key)
            fixture.selected.handler = { runtimeResult }
            val guard = guard(key, reference, fixture.registry, fixture.gate)

            assertEquals(
                name,
                ActionRevalidationResult.Unavailable(ActionFailureReason.COPY_UNAVAILABLE),
                guard.revalidate(OwnedCopyOperation.PLAY),
            )
            assertEquals(name, 1, fixture.selected.resolveCalls)
            assertEquals(name, 0, fixture.siblingCalls())
        }
    }

    @Test
    fun everyRequestedOperationFailsWhenItsCurrentCapabilityChanges() = runTest {
        val key = key(GameSource.STEAM, "42")
        val reference = SourceOwnedCopyReference.Steam(key, 42)

        OwnedCopyOperation.entries.forEach { operation ->
            val fixture = fixture(key)
            fixture.selected.handler = {
                OwnedCopyRuntimeResult.Available(
                    runtime(key, reference, capabilities = emptySet()),
                )
            }
            val guard = guard(key, reference, fixture.registry, fixture.gate)

            assertEquals(
                operation.name,
                ActionRevalidationResult.Unavailable(ActionFailureReason.CAPABILITY_CHANGED),
                guard.revalidate(operation),
            )
            assertEquals(operation.name, 1, fixture.selected.resolveCalls)
            assertEquals(operation.name, 0, fixture.siblingCalls())
        }
    }

    @Test
    fun capabilityLossCoversInstalledDownloadAndUpdateStateMutations() = runTest {
        val key = key(GameSource.AMAZON, "product")
        val reference = SourceOwnedCopyReference.Amazon(
            key,
            localRowId = 8,
            productId = "product",
            entitlementId = "entitlement",
        )
        val stateOperations = listOf(
            "installed changed before play" to OwnedCopyOperation.PLAY,
            "installation appeared before install" to OwnedCopyOperation.INSTALL,
            "download ended before pause resume" to OwnedCopyOperation.PAUSE_RESUME_DOWNLOAD,
            "partial download ended before cancel" to OwnedCopyOperation.CANCEL_DOWNLOAD,
            "update no longer pending" to OwnedCopyOperation.UPDATE,
            "install disappeared before uninstall" to OwnedCopyOperation.UNINSTALL,
        )

        stateOperations.forEach { (name, operation) ->
            val fixture = fixture(key)
            fixture.selected.handler = {
                OwnedCopyRuntimeResult.Available(runtime(key, reference, capabilities = emptySet()))
            }
            val guard = guard(key, reference, fixture.registry, fixture.gate)

            assertEquals(
                name,
                ActionRevalidationResult.Unavailable(ActionFailureReason.CAPABILITY_CHANGED),
                guard.revalidate(operation),
            )
        }
    }

    @Test
    fun bridgeableCurrentNullExecutableIsRejectedBeforeCapabilityHandoff() = runTest {
        val key = key(GameSource.GOG, "123")
        val reference = SourceOwnedCopyReference.Gog(key, "123")
        val fixture = fixture(key)
        fixture.selected.handler = {
            OwnedCopyRuntimeResult.Available(
                runtime(
                    key = key,
                    reference = reference,
                    libraryItem = null,
                    capabilities = setOf(OwnedCopyOperation.OPEN_SOURCE_DETAILS),
                ),
            )
        }
        val guard = guard(key, reference, fixture.registry, fixture.gate)

        assertSuspendThrows(IllegalStateException::class.java) {
            guard.revalidate(OwnedCopyOperation.OPEN_SOURCE_DETAILS)
        }
        assertEquals(1, fixture.selected.resolveCalls)
        assertEquals(0, fixture.siblingCalls())
    }

    @Test
    fun disablingPublicGateAfterCaptureFailsBeforeAnyRuntimeAccess() = runTest {
        val key = key(GameSource.STEAM, "42")
        val reference = SourceOwnedCopyReference.Steam(key, 42)
        val fixture = fixture(key)
        fixture.gate.enabled = false
        fixture.selected.handler = { error("runtime must not be inspected") }
        val guard = guard(key, reference, fixture.registry, fixture.gate)

        val result = guard.revalidate(OwnedCopyOperation.PLAY)

        assertEquals(
            ActionRevalidationResult.Unavailable(ActionFailureReason.PUBLIC_FEATURE_DISABLED),
            result,
        )
        assertEquals(1, fixture.gate.calls)
        assertEquals(0, fixture.totalResolveCalls())
    }

    @Test
    fun disablingPublicGateWhileRuntimeResolveIsSuspendedFailsBeforeReady() = runTest {
        val key = key(GameSource.STEAM, "42")
        val reference = SourceOwnedCopyReference.Steam(key, 42)
        val resolveStarted = CompletableDeferred<Unit>()
        val releaseResolve = CompletableDeferred<Unit>()
        val fixture = fixture(key)
        fixture.selected.handler = {
            resolveStarted.complete(Unit)
            releaseResolve.await()
            OwnedCopyRuntimeResult.Available(
                runtime(key, reference, capabilities = setOf(OwnedCopyOperation.PLAY)),
            )
        }
        val guard = guard(key, reference, fixture.registry, fixture.gate)

        val result = async { guard.revalidate(OwnedCopyOperation.PLAY) }
        resolveStarted.await()
        fixture.gate.enabled = false
        releaseResolve.complete(Unit)

        assertEquals(
            ActionRevalidationResult.Unavailable(ActionFailureReason.PUBLIC_FEATURE_DISABLED),
            result.await(),
        )
        assertEquals(2, fixture.gate.calls)
        assertEquals(1, fixture.selected.resolveCalls)
        assertEquals(0, fixture.siblingCalls())
    }

    @Test
    fun publicGateIsCheckedImmediatelyBeforeReadyHandoff() = runTest {
        val key = key(GameSource.STEAM, "42")
        val reference = SourceOwnedCopyReference.Steam(key, 42)
        val fixture = fixture(key)
        fixture.gate.queueResults(true, true, false)
        fixture.selected.handler = {
            OwnedCopyRuntimeResult.Available(
                runtime(key, reference, capabilities = setOf(OwnedCopyOperation.PLAY)),
            )
        }
        val guard = guard(key, reference, fixture.registry, fixture.gate)

        assertEquals(
            ActionRevalidationResult.Unavailable(ActionFailureReason.PUBLIC_FEATURE_DISABLED),
            guard.revalidate(OwnedCopyOperation.PLAY),
        )
        assertEquals(3, fixture.gate.calls)
        assertEquals(1, fixture.selected.resolveCalls)
        assertEquals(0, fixture.siblingCalls())
    }

    @Test
    fun unchangedReferenceReturnsCurrentReplacementLibraryItemNotCapturedItem() = runTest {
        val key = key(
            GameSource.EPIC,
            EpicStableSourceId.encode("namespace", "catalog"),
        )
        val reference = SourceOwnedCopyReference.Epic(key, 7, "namespace", "catalog")
        val initial = LibraryItem(appId = "EPIC_7", name = "Initial", gameSource = GameSource.EPIC)
        val replacement = LibraryItem(appId = "EPIC_7", name = "Current", gameSource = GameSource.EPIC)
        val fixture = fixture(key)
        fixture.selected.handler = {
            OwnedCopyRuntimeResult.Available(
                runtime(
                    key,
                    reference,
                    libraryItem = replacement,
                    capabilities = setOf(OwnedCopyOperation.PLAY),
                ),
            )
        }
        val guard = guard(key, reference, fixture.registry, fixture.gate, initial)

        val result = guard.revalidate(OwnedCopyOperation.PLAY) as ActionRevalidationResult.Ready

        assertSame(initial, guard.initialLibraryItem)
        assertSame(replacement, result.libraryItem)
        assertEquals(key, guard.key)
        assertEquals(1, fixture.selected.resolveCalls)
        assertEquals(0, fixture.siblingCalls())
    }

    @Test
    fun eachRevalidationResolvesTheExactKeyOnceAndNeverCallsSiblingAdapters() = runTest {
        val key = key(GameSource.AMAZON, "product")
        val reference = SourceOwnedCopyReference.Amazon(key, 8, "product", "entitlement")
        val fixture = fixture(key)
        fixture.selected.handler = { requested ->
            assertEquals(key, requested)
            OwnedCopyRuntimeResult.Available(
                runtime(requested, reference, capabilities = setOf(OwnedCopyOperation.UPDATE)),
            )
        }
        val guard = guard(key, reference, fixture.registry, fixture.gate)

        assertTrue(guard.revalidate(OwnedCopyOperation.UPDATE) is ActionRevalidationResult.Ready)
        assertTrue(guard.revalidate(OwnedCopyOperation.UPDATE) is ActionRevalidationResult.Ready)

        assertEquals(2, fixture.selected.resolveCalls)
        assertEquals(0, fixture.siblingCalls())
    }

    @Test
    fun revalidationDiagnosticsRecordFixedFailureAndSuccessfulSourceHandoff() = runTest {
        val key = key(GameSource.STEAM, "42")
        val reference = SourceOwnedCopyReference.Steam(key, 42)
        val diagnostics = mockk<CanonicalLibraryDiagnosticSink>(relaxed = true)

        val failed = fixture(key, diagnostics = diagnostics)
        failed.selected.handler = { OwnedCopyRuntimeResult.Hidden }
        assertEquals(
            ActionRevalidationResult.Unavailable(ActionFailureReason.COPY_UNAVAILABLE),
            guard(key, reference, failed.registry, failed.gate, diagnostics = diagnostics)
                .revalidate(OwnedCopyOperation.PLAY),
        )
        verify(exactly = 1) {
            diagnostics.revalidationFailed(
                GameSource.STEAM,
                OwnedCopyOperation.PLAY,
                ActionFailureReason.COPY_UNAVAILABLE,
            )
        }

        val ready = fixture(key, diagnostics = diagnostics)
        ready.selected.handler = {
            OwnedCopyRuntimeResult.Available(
                runtime(key, reference, capabilities = setOf(OwnedCopyOperation.PLAY)),
            )
        }
        assertTrue(
            guard(key, reference, ready.registry, ready.gate, diagnostics = diagnostics)
                .revalidate(OwnedCopyOperation.PLAY) is ActionRevalidationResult.Ready,
        )
        verify(exactly = 1) {
            diagnostics.routeSucceeded(GameSource.STEAM, OwnedCopyOperation.PLAY)
        }
    }

    @Test
    fun revalidationDiagnosticSinkFailureDoesNotChangeResult() = runTest {
        val key = key(GameSource.STEAM, "42")
        val reference = SourceOwnedCopyReference.Steam(key, 42)
        val diagnostics = mockk<CanonicalLibraryDiagnosticSink>(relaxed = true)
        every {
            diagnostics.revalidationFailed(any(), any(), any())
        } throws IllegalStateException("private diagnostic failure")
        val fixture = fixture(key, diagnostics = diagnostics)
        fixture.selected.handler = { OwnedCopyRuntimeResult.Hidden }

        val result = guard(
            key,
            reference,
            fixture.registry,
            fixture.gate,
            diagnostics = diagnostics,
        ).revalidate(OwnedCopyOperation.PLAY)

        assertEquals(
            ActionRevalidationResult.Unavailable(ActionFailureReason.COPY_UNAVAILABLE),
            result,
        )
    }

    @Test
    fun runtimeAndGateCancellationPropagateWithoutFallback() = runTest {
        val key = key(GameSource.CUSTOM_GAME, "5")
        val reference = SourceOwnedCopyReference.Custom(key, 5)
        val runtimeCancelled = fixture(key)
        runtimeCancelled.selected.handler = { throw CancellationException("runtime cancelled") }
        val runtimeGuard = guard(
            key,
            reference,
            runtimeCancelled.registry,
            runtimeCancelled.gate,
            diagnostics = runtimeCancelled.diagnostics,
        )
        assertSuspendThrows(CancellationException::class.java) {
            runtimeGuard.revalidate(OwnedCopyOperation.PLAY)
        }
        assertEquals(0, runtimeCancelled.siblingCalls())
        verify(exactly = 0) {
            runtimeCancelled.diagnostics.revalidationFailed(any(), any(), any())
        }
        verify(exactly = 0) {
            runtimeCancelled.diagnostics.routeSucceeded(any(), any())
        }

        val gateCancelled = fixture(key, MutableGate(failure = CancellationException("gate cancelled")))
        val gateGuard = guard(
            key,
            reference,
            gateCancelled.registry,
            gateCancelled.gate,
            diagnostics = gateCancelled.diagnostics,
        )
        assertSuspendThrows(CancellationException::class.java) {
            gateGuard.revalidate(OwnedCopyOperation.PLAY)
        }
        assertEquals(0, gateCancelled.totalResolveCalls())
        verify(exactly = 0) {
            gateCancelled.diagnostics.revalidationFailed(any(), any(), any())
        }
        verify(exactly = 0) {
            gateCancelled.diagnostics.routeSucceeded(any(), any())
        }
    }

    @Test
    fun currentRuntimeIdentityMismatchAndFatalFailuresRemainProgrammerFailures() = runTest {
        val key = key(GameSource.STEAM, "42")
        val wrong = key(GameSource.STEAM, "43")
        val reference = SourceOwnedCopyReference.Steam(key, 42)

        val wrongCopy = fixture(key)
        wrongCopy.selected.handler = {
            OwnedCopyRuntimeResult.Available(
                runtime(
                    wrong,
                    SourceOwnedCopyReference.Steam(wrong, 43),
                    capabilities = setOf(OwnedCopyOperation.PLAY),
                ),
            )
        }
        assertSuspendThrows(IllegalStateException::class.java) {
            guard(key, reference, wrongCopy.registry, wrongCopy.gate)
                .revalidate(OwnedCopyOperation.PLAY)
        }

        val wrongReference = fixture(key)
        wrongReference.selected.handler = {
            OwnedCopyRuntimeResult.Available(
                runtime(
                    key,
                    SourceOwnedCopyReference.Steam(wrong, 42),
                    capabilities = setOf(OwnedCopyOperation.PLAY),
                ),
            )
        }
        assertSuspendThrows(IllegalStateException::class.java) {
            guard(key, reference, wrongReference.registry, wrongReference.gate)
                .revalidate(OwnedCopyOperation.PLAY)
        }

        val fatal = fixture(key)
        fatal.selected.handler = { throw OutOfMemoryError("fatal") }
        assertSuspendThrows(OutOfMemoryError::class.java) {
            guard(key, reference, fatal.registry, fatal.gate)
                .revalidate(OwnedCopyOperation.PLAY)
        }
    }

    @Test
    fun guardRejectsMismatchedCapturedReferenceAtConstruction() {
        val key = key(GameSource.STEAM, "42")
        val wrong = key(GameSource.STEAM, "43")
        val fixture = fixture(key)

        assertThrows(IllegalStateException::class.java) {
            guard(
                key,
                SourceOwnedCopyReference.Steam(wrong, 42),
                fixture.registry,
                fixture.gate,
            )
        }
    }

    @Test
    fun guardIsMemoryOnlyNonSerializableAndKeepsCapturedStateInFinalFields() {
        val key = key(GameSource.STEAM, "42")
        val reference = SourceOwnedCopyReference.Steam(key, 42)
        val initial = LibraryItem(appId = "STEAM_42", name = "Initial", gameSource = GameSource.STEAM)
        val fixture = fixture(key)
        val guard = guard(key, reference, fixture.registry, fixture.gate, initial)

        assertFalse((guard as Any) is Serializable)
        assertFalse((guard as Any) is Parcelable)
        val annotationNames = guard::class.java.annotations.map { it.annotationClass.qualifiedName.orEmpty() }
        assertTrue(annotationNames.none { name ->
            name.contains("Parcel") ||
                name.contains("Serializable") ||
                name.contains("Entity") ||
                name.contains("SavedState") ||
                name.contains("Nav")
        })
        listOf("key", "capturedReference", "initialLibraryItem").forEach { fieldName ->
            val field = guard::class.java.getDeclaredField(fieldName)
            assertTrue(fieldName, Modifier.isFinal(field.modifiers))
        }
        assertSame(initial, guard.initialLibraryItem)
    }

    private fun validReferenceMutations(): List<ReferenceMutation> {
        val epic = key(
            GameSource.EPIC,
            EpicStableSourceId.encode("namespace", "catalog"),
        )
        val amazon = key(GameSource.AMAZON, "product")
        return listOf(
            ReferenceMutation(
                "Epic local row ID",
                epic,
                SourceOwnedCopyReference.Epic(epic, 7, "namespace", "catalog"),
                SourceOwnedCopyReference.Epic(epic, 8, "namespace", "catalog"),
            ),
            ReferenceMutation(
                "Amazon local row ID",
                amazon,
                SourceOwnedCopyReference.Amazon(amazon, 8, "product", "entitlement"),
                SourceOwnedCopyReference.Amazon(amazon, 9, "product", "entitlement"),
            ),
            ReferenceMutation(
                "Amazon entitlement ID",
                amazon,
                SourceOwnedCopyReference.Amazon(amazon, 8, "product", "entitlement"),
                SourceOwnedCopyReference.Amazon(amazon, 8, "product", "other-entitlement"),
            ),
        )
    }

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
        name = "Current",
        gameSource = source,
    )

    private fun executableAppId(reference: SourceOwnedCopyReference): String = when (reference) {
        is SourceOwnedCopyReference.Steam -> "STEAM_${reference.appId}"
        is SourceOwnedCopyReference.Gog -> "GOG_${reference.gameId}"
        is SourceOwnedCopyReference.Epic -> "EPIC_${reference.localRowId}"
        is SourceOwnedCopyReference.Amazon -> "AMAZON_${reference.localRowId}"
        is SourceOwnedCopyReference.Custom -> "CUSTOM_GAME_${reference.appId}"
    }

    private fun fixture(
        selectedKey: OwnedCopyKey,
        gate: MutableGate = MutableGate(),
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
        return Fixture(registry, adapters.getValue(selectedKey.source), adapters, gate, diagnostics)
    }

    private fun guard(
        key: OwnedCopyKey,
        capturedReference: SourceOwnedCopyReference,
        registry: OwnedCopyRuntimeRegistry,
        gate: CanonicalPublicLibraryGate,
        initialLibraryItem: LibraryItem = libraryItem(
            key.source,
            executableAppId(capturedReference),
        ),
        diagnostics: CanonicalLibraryDiagnosticSink = mockk(relaxed = true),
    ): OwnedCopyActionGuard = OwnedCopyActionGuard(
        key = key,
        capturedReference = capturedReference,
        initialLibraryItem = initialLibraryItem,
        runtimeRegistry = registry,
        publicGate = gate,
        diagnostics = diagnostics,
    )

    private fun key(source: GameSource, stableSourceId: String): OwnedCopyKey =
        OwnedCopyKey(scope, source, stableSourceId)

    private fun runtime(
        key: OwnedCopyKey,
        reference: SourceOwnedCopyReference,
        capabilities: Set<OwnedCopyOperation>,
        libraryItem: LibraryItem? = libraryItem(
            key.source,
            executableAppId(reference),
        ),
    ): OwnedCopyRuntime = OwnedCopyRuntime(
        key = key,
        reference = reference,
        libraryItem = libraryItem,
        nativeTitle = "Current",
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
    )

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

    private class RecordingAdapter(
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
            for (key in keys) put(key, handler(key))
        }
    }

    private data class Fixture(
        val registry: OwnedCopyRuntimeRegistry,
        val selected: RecordingAdapter,
        val adapters: Map<GameSource, RecordingAdapter>,
        val gate: MutableGate,
        val diagnostics: CanonicalLibraryDiagnosticSink,
    ) {
        fun totalResolveCalls(): Int = adapters.values.sumOf(RecordingAdapter::resolveCalls)
        fun siblingCalls(): Int = totalResolveCalls() - selected.resolveCalls
    }

    private class MutableGate(
        var enabled: Boolean = true,
        private val failure: Throwable? = null,
    ) : CanonicalPublicLibraryGate {
        var calls = 0
        private val queuedResults = ArrayDeque<Boolean>()

        fun queueResults(vararg results: Boolean) {
            queuedResults.addAll(results.toList())
        }

        override fun isEnabled(): Boolean {
            calls += 1
            failure?.let { throw it }
            return queuedResults.removeFirstOrNull() ?: enabled
        }
    }

    private data class ExactIdentityCase(
        val name: String,
        val key: OwnedCopyKey,
        val validReference: SourceOwnedCopyReference,
        val wrongReference: SourceOwnedCopyReference,
        val validLibraryItemId: String,
        val wrongLibraryItemId: String,
    )

    private data class ReferenceMutation(
        val name: String,
        val key: OwnedCopyKey,
        val captured: SourceOwnedCopyReference,
        val changed: SourceOwnedCopyReference,
    )
}
