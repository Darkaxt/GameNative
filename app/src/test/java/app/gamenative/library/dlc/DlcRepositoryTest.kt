package app.gamenative.library.dlc

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DlcRepositoryTest {
    @Test
    fun exactNativeInventoryDistinguishesOwnedAndNotOwned() = runTest {
        val harness = Harness()
        val result = harness.repository.load(harness.key)
        assertTrue(result is DlcState.Content)
        result as DlcState.Content
        assertEquals(harness.key, result.key)
        assertEquals(listOf(DlcOwnership.OWNED, DlcOwnership.NOT_OWNED), result.items.map { it.ownership })
        assertTrue(result.entitlementsComplete)
        assertEquals(listOf(harness.reference), harness.catalogRequests)
        assertEquals(listOf(harness.reference), harness.entitlementRequests)
    }

    @Test
    fun partialInventoryCanProvePositiveButNeverNegative() = runTest {
        val harness = Harness().apply { inventory = DlcEntitlements(setOf("43"), complete = false) }
        val result = harness.repository.load(harness.key)
        assertTrue(result is DlcState.Content)
        result as DlcState.Content
        assertEquals(listOf(DlcOwnership.OWNED, DlcOwnership.UNKNOWN), result.items.map { it.ownership })
        assertFalse(result.entitlementsComplete)
    }

    @Test
    fun entitlementFailureKeepsCatalogWithUnknownOwnership() = runTest {
        val harness = Harness().apply { entitlementFailure = IllegalStateException("private arbitrary response") }
        val result = harness.repository.load(harness.key)
        assertTrue(result is DlcState.Content)
        result as DlcState.Content
        assertEquals(listOf(DlcOwnership.UNKNOWN, DlcOwnership.UNKNOWN), result.items.map { it.ownership })
        assertFalse(result.entitlementsComplete)
    }

    @Test
    fun gogCopyNeverRequestsMatchedSteamCatalogOrSteamLicense() = runTest {
        val harness = Harness()
        assertTrue(harness.repository.load(harness.key) is DlcState.Content)
        assertEquals(GameSource.GOG, harness.catalogRequests.single().key.source)
        assertEquals("42", (harness.entitlementRequests.single() as SourceOwnedCopyReference.Gog).gameId)
    }

    @Test
    fun changedAccountGenerationDiscardsCompletedAsyncOwnership() = runTest {
        val harness = Harness().apply { afterEntitlements = { generation++ } }
        assertTrue(harness.repository.load(harness.key) is DlcState.Unavailable)
    }

    @Test
    fun disappearedCopyDiscardsCompletedAsyncOwnership() = runTest {
        val harness = Harness().apply { afterEntitlements = { present = false } }
        assertTrue(harness.repository.load(harness.key) is DlcState.Unavailable)
    }

    @Test
    fun mismatchedRuntimeKeyIsRejectedBeforeProviderRequests() = runTest {
        val harness = Harness().apply { wrongKey = true }
        assertTrue(harness.repository.load(harness.key) is DlcState.Unavailable)
        assertTrue(harness.catalogRequests.isEmpty())
        assertTrue(harness.entitlementRequests.isEmpty())
    }

    @Test
    fun cancellationIsNotConvertedToAnUnknownSuccess() = runTest {
        val harness = Harness().apply { entitlementFailure = CancellationException("cancelled") }
        var cancelled = false
        try { harness.repository.load(harness.key) } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }

    @Test
    fun unavailableCopyDoesNotReadAnyNativeAccountInventory() = runTest {
        val harness = Harness().apply { present = false }
        assertTrue(harness.repository.load(harness.key) is DlcState.Unavailable)
        assertTrue(harness.catalogRequests.isEmpty())
        assertTrue(harness.entitlementRequests.isEmpty())
    }

    @Test
    fun contradictoryParentAssociationCannotApplyEvenARealNativeLicense() = runTest {
        val harness = Harness().apply {
            catalog = DlcCatalog(listOf(DlcCatalogItem("43", "Conflicting parent", associationVerified = false)), false)
        }
        val result = harness.repository.load(harness.key)
        assertTrue(result is DlcState.Content)
        assertEquals(DlcOwnership.UNKNOWN, (result as DlcState.Content).items.single().ownership)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun boundedEntitlementRetrievalKeepsCatalogWithUnknownStatuses() = runTest {
        val harness = Harness().apply { blockEntitlements = true }
        val result = async { harness.repository.load(harness.key) }
        try {
            runCurrent()
            advanceTimeBy(25_001)
            runCurrent()
            assertTrue("Native inventory must not keep the catalog in a perpetual loading state", result.isCompleted)
            val content = result.await() as DlcState.Content
            assertEquals(listOf(DlcOwnership.UNKNOWN, DlcOwnership.UNKNOWN), content.items.map { it.ownership })
            assertFalse(content.entitlementsComplete)
        } finally { result.cancel() }
    }

    private class Harness {
        val key = OwnedCopyKey(AccountScope.parse("a".repeat(64)), GameSource.GOG, "42")
        val reference = SourceOwnedCopyReference.Gog(key, "42")
        var generation = 1L
        var present = true
        var wrongKey = false
        var inventory = DlcEntitlements(setOf("43"), complete = true)
        var catalog = DlcCatalog(listOf(DlcCatalogItem("43", "Expansion A"), DlcCatalogItem("44", "Expansion B")), complete = true)
        var entitlementFailure: Exception? = null
        var blockEntitlements = false
        var afterEntitlements: () -> Unit = {}
        val catalogRequests = mutableListOf<SourceOwnedCopyReference>()
        val entitlementRequests = mutableListOf<SourceOwnedCopyReference>()
        val repository = DlcRepository(
            authority = object : DlcCopyAuthority {
                override suspend fun current(key: OwnedCopyKey): DlcCopyProof? = if (!present) null else {
                    val ref = if (wrongKey) reference.copy(key = key.copy(stableSourceId = "99")) else reference
                    DlcCopyProof(ref, generation)
                }
            },
            source = object : DlcSource {
                override suspend fun catalog(reference: SourceOwnedCopyReference): DlcCatalog {
                    catalogRequests += reference
                    return catalog
                }
                override suspend fun entitlements(reference: SourceOwnedCopyReference): DlcEntitlements {
                    entitlementRequests += reference
                    if (blockEntitlements) awaitCancellation()
                    entitlementFailure?.let { throw it }
                    afterEntitlements()
                    return inventory
                }
            },
        )
    }
}
