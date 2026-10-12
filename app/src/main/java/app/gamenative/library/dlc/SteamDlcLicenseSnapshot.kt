package app.gamenative.library.dlc

import `in`.dragonbra.javasteam.steam.handlers.steamapps.License

internal class SteamDlcLicenseSnapshot {
    internal class Pending(val session: Any, val revision: Long)
    internal data class Admitted(val revision: Long, val licenses: List<License>)
    private var revision = 0L
    private var pending: Pending? = null
    private var admitted: Admitted? = null

    @Synchronized
    fun pending(session: Any): Pending {
        admitted = null
        return Pending(session, ++revision).also { pending = it }
    }

    @Synchronized
    fun commit(pending: Pending, licenses: List<License>) {
        if (this.pending !== pending) return
        admitted = Admitted(pending.revision, licenses.toList())
    }

    @Synchronized
    fun current(session: Any): Admitted? = admitted?.takeIf { pending?.session === session }

    @Synchronized
    fun licenseProof(session: Any, accountId: Int): List<SteamDlcLicense>? {
        val snapshot = current(session) ?: return null
        return projectSteamDlcLicenses(snapshot.licenses, accountId)?.map { it.copy(admission = pending) }
    }

    @Synchronized
    fun clear() {
        revision++
        pending = null
        admitted = null
    }
}
