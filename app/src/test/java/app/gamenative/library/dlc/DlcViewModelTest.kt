package app.gamenative.library.dlc

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import app.gamenative.ui.model.DlcViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DlcViewModelTest {
    @Test
    fun singleExactCopyLoadsItsNativeDlc() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val h = Harness()
            h.vm.bindCopies(listOf(h.first), offline = false)
            assertEquals(h.first, h.vm.state.value.selectedCopy)
            assertTrue(h.vm.state.value.detail is DlcState.Content)
            assertEquals(listOf(h.first), h.requests)
            h.vm.clear()
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun multipleEditionsRequireSelectionAndNeverMutateAnActionPreference() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val h = Harness()
            h.vm.bindCopies(listOf(h.first, h.second), false)
            assertTrue(h.requests.isEmpty())
            h.vm.select(h.second)
            assertEquals(h.second, h.vm.state.value.selectedCopy)
            assertEquals(listOf(h.second), h.requests)
            assertEquals(h.second, (h.vm.state.value.detail as DlcState.Content).key)
            h.vm.clear()
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun changedSelectionCancelsOldLoadAndCannotPublishItsLateOwnership() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val h = Harness().apply { blockFirst = true }
            h.vm.bindCopies(listOf(h.first, h.second), false)
            h.vm.select(h.first)
            h.vm.select(h.second)
            assertEquals(1, h.cancelled)
            assertEquals(h.second, (h.vm.state.value.detail as DlcState.Content).key)
            h.vm.clear()
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun removedCopyAndDetailCloseClearEphemeralEntitlementState() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val h = Harness()
            h.vm.bindCopies(listOf(h.first), false)
            assertTrue(h.vm.state.value.detail is DlcState.Content)
            h.vm.bindCopies(emptyList(), false)
            assertNull(h.vm.state.value.selectedCopy)
            assertEquals(DlcState.Idle, h.vm.state.value.detail)
            h.vm.bindCopies(listOf(h.second), false)
            assertEquals(h.second, h.vm.state.value.selectedCopy)
            h.vm.clear()
            assertNull(h.vm.state.value.selectedCopy)
            assertEquals(DlcState.Idle, h.vm.state.value.detail)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun runtimeInvalidationRevalidatesAndCannotKeepRevokedOwnedRows() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val h = Harness()
            h.vm.bindCopies(listOf(h.first), false)
            assertTrue(h.vm.state.value.detail is DlcState.Content)
            h.present = false
            h.invalidations.emit(Unit)
            assertEquals(DlcState.Unavailable, h.vm.state.value.detail)
            h.vm.clear()
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun offlineAndUnknownKeysDoNotReadNativeAccountInventory() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val h = Harness()
            h.vm.bindCopies(listOf(h.first), true)
            h.vm.retry()
            h.vm.select(h.second)
            assertTrue(h.requests.isEmpty())
            h.vm.bindCopies(listOf(h.first), false)
            assertEquals(listOf(h.first), h.requests)
            h.vm.clear()
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun boundedLoadCannotRemainLoadingForeverOrPublishLateOwnership() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val h = Harness().apply { blockFirst = true }
        try {
            h.vm.bindCopies(listOf(h.first), false)
            assertEquals(DlcState.Loading, h.vm.state.value.detail)
            advanceTimeBy(60_001)
            runCurrent()
            assertEquals(DlcState.Unavailable, h.vm.state.value.detail)
            assertEquals(1, h.cancelled)
            assertEquals(listOf(h.first), h.requests)
        } finally {
            h.vm.clear()
            Dispatchers.resetMain()
        }
    }

    private class Harness {
        val first = OwnedCopyKey(AccountScope.parse("a".repeat(64)), GameSource.GOG, "42")
        val second = first.copy(stableSourceId = "99")
        val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
        val requests = mutableListOf<OwnedCopyKey>()
        var present = true
        var blockFirst = false
        var cancelled = 0
        val vm = DlcViewModel(DlcRepository(
            authority = DlcCopyAuthority { key -> if (present) DlcCopyProof(SourceOwnedCopyReference.Gog(key, key.stableSourceId), 1) else null },
            source = object : DlcSource {
                override suspend fun catalog(reference: SourceOwnedCopyReference): DlcCatalog {
                    requests += reference.key
                    if (blockFirst && reference.key == first) try { awaitCancellation() } finally { cancelled++ }
                    return DlcCatalog(listOf(DlcCatalogItem("43", "Expansion")), true)
                }
                override suspend fun entitlements(reference: SourceOwnedCopyReference) = DlcEntitlements(setOf("43"), true)
            },
        ), invalidations)
    }
}
