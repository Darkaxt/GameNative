package app.gamenative.service.amazon

import android.content.Context
import app.gamenative.data.AmazonGame
import app.gamenative.data.GameSource
import app.gamenative.data.canonical.StableSourceIdValidation
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.library.canonical.AccountScopedOwnershipLedger
import app.gamenative.library.canonical.MaterializedOwnedCopySnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Amazon library manager and DB bridge. */
@Singleton
class AmazonManager @Inject constructor(
    private val amazonGameDao: AmazonGameDao,
    private val ownershipLedger: AccountScopedOwnershipLedger,
    @ApplicationContext private val context: Context,
) {

    /** Refresh the Amazon library from API and persist one complete ownership snapshot. */
    suspend fun refreshLibrary(): Result<Int> = withContext(Dispatchers.IO) {
        ownershipLedger.runCompleteSnapshot(GameSource.AMAZON) {
            val credentials = AmazonAuthManager.getStoredCredentials(context).getOrThrow()
            val games = AmazonApiClient.getEntitlements(
                bearerToken = credentials.accessToken,
                deviceSerial = credentials.deviceSerial,
            ).getOrThrow()
            val stableSourceIds = games.map { it.productId }
            StableSourceIdValidation.requireAllValid(GameSource.AMAZON, stableSourceIds)
            amazonGameDao.upsertPreservingInstallStatus(games)
            require(stableSourceIds.size == stableSourceIds.toSet().size)
            val persistedIds = amazonGameDao.getAllAsList().map { it.productId }.toSet()
            require(stableSourceIds.all(persistedIds::contains))
            MaterializedOwnedCopySnapshot(
                value = games.size,
                stableSourceIds = stableSourceIds,
                resolvedSourceIds = games.associate { it.productId to it.entitlementId },
            )
        }
    }

    /** Look up a game by product ID. */
    suspend fun getGameById(productId: String): AmazonGame? = withContext(Dispatchers.IO) {
        amazonGameDao.getByProductId(productId)
    }

    /** Look up a game by auto-generated appId. */
    suspend fun getGameByAppId(appId: Int): AmazonGame? = withContext(Dispatchers.IO) {
        amazonGameDao.getByAppId(appId)
    }

    /** Return all Amazon games from DB. */
    suspend fun getAllGames(): List<AmazonGame> = withContext(Dispatchers.IO) {
        amazonGameDao.getAllAsList()
    }

    /** Return non-installed Amazon games from DB. */
    suspend fun getNonInstalledGames(): List<AmazonGame> = withContext(Dispatchers.IO) {
        amazonGameDao.getNonInstalledGames()
    }

    /** Mark a game as installed and persist install metadata. */
    suspend fun markInstalled(productId: String, installPath: String, installSize: Long, versionId: String = "") =
        withContext(Dispatchers.IO) {
            amazonGameDao.markAsInstalled(productId, installPath, installSize, versionId)
            Timber.i("[Amazon] Marked installed (${installSize}B)")
        }

    /** Mark a game as not installed. */
    suspend fun markUninstalled(productId: String) = withContext(Dispatchers.IO) {
        amazonGameDao.markAsUninstalled(productId)
        Timber.i("[Amazon] Marked uninstalled")
    }

    /** Update cached download size for a game. */
    suspend fun updateDownloadSize(productId: String, size: Long) = withContext(Dispatchers.IO) {
        amazonGameDao.updateDownloadSize(productId, size)
        Timber.i("[Amazon] Updated download size: $size bytes")
    }

    /** Get the stored bearer token. */
    suspend fun getBearerToken(): String? = withContext(Dispatchers.IO) {
        AmazonAuthManager.getStoredCredentials(context).getOrNull()?.accessToken
    }

    /** Delete all non-installed Amazon games on logout. */
    suspend fun deleteAllNonInstalledGames() = withContext(Dispatchers.IO) {
        amazonGameDao.deleteAllNonInstalledGames()
        Timber.i("[Amazon] Deleted all non-installed games from DB")
    }
}
