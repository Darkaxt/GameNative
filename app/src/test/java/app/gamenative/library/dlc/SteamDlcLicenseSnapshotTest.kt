package app.gamenative.library.dlc

import `in`.dragonbra.javasteam.enums.ELicenseType
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserver
import `in`.dragonbra.javasteam.steam.handlers.steamapps.License
import org.junit.Assert.*
import org.junit.Test

class SteamDlcLicenseSnapshotTest {
    @Test
    fun sameAccountReconnectCannotReuseThePreviousSessionLicenseList() {
        val snapshots = SteamDlcLicenseSnapshot()
        val firstSession = Session(7, 1)
        val nextSession = Session(7, 1)
        snapshots.commit(snapshots.pending(firstSession), listOf(license(10)))
        assertNotNull(snapshots.current(firstSession))
        assertNull(snapshots.current(nextSession))
    }

    @Test
    fun incomingCallbackRemovesOldAuthorityBeforeAsynchronousDatabaseCommit() {
        val snapshots = SteamDlcLicenseSnapshot()
        val session = Session(7, 1)
        snapshots.commit(snapshots.pending(session), listOf(license(10)))
        assertNotNull(snapshots.current(session))
        snapshots.pending(session)
        assertNull(snapshots.current(session))
    }

    @Test
    fun supersededCommitCannotReplaceNewerCallbackAuthority() {
        val snapshots = SteamDlcLicenseSnapshot()
        val session = Session(7, 1)
        val old = snapshots.pending(session)
        val fresh = snapshots.pending(session)
        snapshots.commit(fresh, listOf(license(20)))
        snapshots.commit(old, listOf(license(10)))
        assertNotNull(snapshots.current(session))
        assertEquals(listOf(20), snapshots.current(session)!!.licenses.map { it.packageID })
        assertEquals(fresh.revision, snapshots.current(session)!!.revision)
    }

    @Test
    fun admittedSnapshotCopiesTheCallbackListAndClearRevokesIt() {
        val snapshots = SteamDlcLicenseSnapshot()
        val session = Session(7, 1)
        val callback = mutableListOf(license(10))
        snapshots.commit(snapshots.pending(session), callback)
        callback.clear()
        assertNotNull(snapshots.current(session))
        assertEquals(listOf(10), snapshots.current(session)!!.licenses.map { it.packageID })
        snapshots.clear()
        assertNull(snapshots.current(session))
    }

    @Test
    fun noCallbackAndUncommittedCallbackCannotEstablishCompleteEmptyAuthority() {
        val snapshots = SteamDlcLicenseSnapshot()
        val session = Session(7, 1)
        assertNull(snapshots.current(session))
        snapshots.pending(session)
        assertNull(snapshots.current(session))
    }

    @Test
    fun identicalPackageDataAcrossReconnectStillChangesTheEntitlementProof() {
        val snapshots = SteamDlcLicenseSnapshot()
        val first = Session(7, 1)
        val next = Session(7, 1)
        snapshots.commit(snapshots.pending(first), listOf(license(10)))
        val captured = snapshots.licenseProof(first, 7)
        assertNotNull(captured)
        snapshots.commit(snapshots.pending(next), listOf(license(10)))
        val refreshed = snapshots.licenseProof(next, 7)
        assertNotNull(refreshed)
        assertNotEquals(captured, refreshed)
        assertNull(snapshots.licenseProof(first, 7))
    }

    @Test
    fun restartedServiceCannotReuseAnEarlierAdmissionWithTheSamePackageData() {
        val firstService = SteamDlcLicenseSnapshot()
        val nextService = SteamDlcLicenseSnapshot()
        val first = Session(7, 1)
        val next = Session(7, 1)
        firstService.commit(firstService.pending(first), listOf(license(10)))
        nextService.commit(nextService.pending(next), listOf(license(10)))
        assertNotNull(firstService.licenseProof(first, 7))
        assertNotNull(nextService.licenseProof(next, 7))
        assertNotEquals(firstService.licenseProof(first, 7), nextService.licenseProof(next, 7))
    }

    private data class Session(val accountId: Int, val lifecycleGeneration: Long)
    private fun license(id: Int) = License(SteammessagesClientserver.CMsgClientLicenseList.License.newBuilder()
        .setPackageId(id).setOwnerId(7).setLicenseType(ELicenseType.SinglePurchase.code()).build())
}
