package app.gamenative.library.canonical.catalog

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.gamenative.library.canonical.CanonicalProjectionReadiness
import app.gamenative.library.canonical.CanonicalPublicLibraryGate
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import timber.log.Timber

class SteamCatalogResolutionWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val dependencies = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
        when {
            !dependencies.publicLibraryGate().isEnabled() -> Result.success()
            !dependencies.projectionReadiness().isReady.value -> Result.retry()
            dependencies.resolutionRepository().resumeAutomatically() -> Result.success()
            else -> Result.retry()
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Timber.tag("SteamCatalogResume").w("Resume failed; errorType=%s", error.javaClass.simpleName)
        Result.retry()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun resolutionRepository(): SteamCatalogResolutionRepository
        fun projectionReadiness(): CanonicalProjectionReadiness
        fun publicLibraryGate(): CanonicalPublicLibraryGate
    }
}
