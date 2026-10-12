package app.gamenative.library.dlc

import app.gamenative.data.GameSource
import app.gamenative.data.canonical.AccountScope
import app.gamenative.data.canonical.OwnedCopyKey
import app.gamenative.library.canonical.AccountScopeProvider
import app.gamenative.library.canonical.InMemoryAccountLifecycleState
import app.gamenative.library.canonical.source.SourceOwnedCopyReference
import `in`.dragonbra.javasteam.enums.ELicenseFlags
import `in`.dragonbra.javasteam.enums.ELicenseType
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserver
import `in`.dragonbra.javasteam.steam.handlers.steamapps.License
import `in`.dragonbra.javasteam.types.KeyValue
import java.util.EnumSet
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DlcNativeAuthorityTest {
    @Test
    fun exactRuntimeCopyIsBoundToItsCurrentAccountGeneration() = runTest {
        val key = key()
        val lifecycle = InMemoryAccountLifecycleState()
        val authority = NativeDlcCopyAuthority(scopes { key.accountScope }, lifecycle) { SourceOwnedCopyReference.Gog(it, "42") }
        val proof = authority.current(key)
        assertNotNull(proof)
        assertEquals(key, proof!!.reference.key)
        assertEquals(lifecycle.generation(GameSource.GOG), proof.generation)
    }

    @Test
    fun accountSwitchDuringRuntimeResolutionCannotPublishOldProof() = runTest {
        val key = key()
        var scope = key.accountScope
        val lifecycle = InMemoryAccountLifecycleState()
        val authority = NativeDlcCopyAuthority(scopes { scope }, lifecycle) { scope = AccountScope.parse("b".repeat(64)); SourceOwnedCopyReference.Gog(it, "42") }
        assertNull(authority.current(key))
    }

    @Test
    fun generationSwitchDuringRuntimeResolutionCannotPublishOldProof() = runTest {
        val key = key()
        val lifecycle = InMemoryAccountLifecycleState()
        val authority = NativeDlcCopyAuthority(scopes { key.accountScope }, lifecycle) { lifecycle.advanceGeneration(GameSource.GOG); SourceOwnedCopyReference.Gog(it, "42") }
        assertNull(authority.current(key))
    }

    @Test
    fun wrongNativeRuntimeIdentityCannotEstablishEntitlementAuthority() = runTest {
        val key = key()
        val authority = NativeDlcCopyAuthority(scopes { key.accountScope }, InMemoryAccountLifecycleState()) { SourceOwnedCopyReference.Gog(it, "99") }
        assertNull(authority.current(key))
    }

    @Test
    fun originalSteamLicensesPreserveSelfOwnedGrantDespiteExpiredLenderDuplicate() {
        val result = projectSteamDlcLicenses(listOf(license(10, 7), license(10, 8, ELicenseFlags.Expired), license(20, 7, ELicenseFlags.Expired), license(30, 8)), 7)
        assertNotNull(result)
        assertEquals(listOf(10), result!!.map { it.packageId })
        assertEquals(123L, result.single().accessToken)
    }

    @Test
    fun cancelledOrPendingSteamLicenseCannotGrantOwnedDlc() {
        listOf(ELicenseFlags.CancelledByAdmin, ELicenseFlags.CancelledByUser, ELicenseFlags.Pending, ELicenseFlags.NotActivated).forEach { flag ->
            val result = projectSteamDlcLicenses(listOf(license(10, 7, flag)), 7)
            assertTrue(result == null || result.isEmpty())
        }
    }

    @Test
    fun cancelledSubscriptionCannotEstablishCompleteNegativeInventory() {
        assertNull(projectSteamDlcLicenses(listOf(license(10, 7, ELicenseFlags.CancelledByUser)), 7))
    }

    @Test
    fun explicitSteamPackageAppIdsAreAuthoritativeConstituents() {
        val root = KeyValue("package").apply { children = mutableListOf(KeyValue("appids").apply { children = mutableListOf(KeyValue("0", "43"), KeyValue("1", "44")) }) }
        assertEquals(setOf("43", "44"), readSteamDlcPackageApps(root))
    }

    @Test
    fun explicitlyEmptySteamPackageIsDistinctFromAbsentMembershipField() {
        val root = KeyValue("package").apply { children = mutableListOf(KeyValue("appids")) }
        assertEquals(emptySet<String>(), readSteamDlcPackageApps(root))
        assertNull(readSteamDlcPackageApps(KeyValue("package")))
    }

    @Test
    fun malformedSteamPackageValueCannotDefaultToZeroOrCompleteEmpty() {
        val root = KeyValue("package").apply { children = mutableListOf(KeyValue("appids").apply { children = mutableListOf(KeyValue("0", "not-an-app-id")) }) }
        assertNull(readSteamDlcPackageApps(root))
    }

    @Test
    fun unsetSteamLicenseOwnerCannotEstablishCompleteNegativeInventory() {
        assertNull(projectSteamDlcLicenses(listOf(license(10, 0)), 7))
    }

    @Test
    fun scalarSteamPackageMembershipCannotEstablishCompleteEmptyInventory() {
        val root = KeyValue("package").apply { children = mutableListOf(KeyValue("appids", "not-a-membership-container")) }
        assertNull(readSteamDlcPackageApps(root))
    }

    private fun scopes(value: suspend () -> AccountScope?) = object : AccountScopeProvider {
        override suspend fun current(source: GameSource) = value()
    }
    private fun key() = OwnedCopyKey(AccountScope.parse("a".repeat(64)), GameSource.GOG, "42")
    private fun license(packageId: Int, owner: Int, flag: ELicenseFlags? = null): License = License(
        SteammessagesClientserver.CMsgClientLicenseList.License.newBuilder().setPackageId(packageId)
            .setOwnerId(owner).setLicenseType(ELicenseType.SinglePurchase.code()).setAccessToken(123L).setChangeNumber(7)
            .setFlags(if (flag == null) 0 else ELicenseFlags.code(EnumSet.of(flag))).build(),
    )
}
