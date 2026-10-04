package app.gamenative.library.canonical.catalog

import android.app.Application
import android.content.Context
import dagger.hilt.internal.GeneratedComponentManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class SteamCatalogResolutionWorkerTest {
    @Test
    fun `WorkManager can locate the public resume worker constructor`() {
        val worker = runCatching {
            Class.forName("app.gamenative.library.canonical.catalog.SteamCatalogResolutionWorker")
        }.getOrNull()
        assertNotNull("The durable resume job needs a runtime-instantiable worker", worker)
        assertTrue(CoroutineWorker::class.java.isAssignableFrom(worker))
        assertNotNull(worker!!.getConstructor(Context::class.java, WorkerParameters::class.java))
    }
}

class ResolutionWorkerTestApplication : Application(), GeneratedComponentManager<Any> {
    lateinit var component: Any
    override fun generatedComponent(): Any = component
}
