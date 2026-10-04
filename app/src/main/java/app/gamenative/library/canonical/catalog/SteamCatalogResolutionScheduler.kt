package app.gamenative.library.canonical.catalog

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

fun interface SteamCatalogResumeScheduler {
    suspend fun enqueue()
}

@Singleton
class SteamCatalogResolutionScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : SteamCatalogResumeScheduler {
    override suspend fun enqueue() {
        val request = OneTimeWorkRequestBuilder<SteamCatalogResolutionWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // Append rather than dropping a new wakeup while an earlier worker is finishing.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            .await()
    }

    companion object {
        const val WORK_NAME = "steam-catalog-resolution-resume"
    }
}
