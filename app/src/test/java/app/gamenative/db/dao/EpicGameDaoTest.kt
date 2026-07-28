package app.gamenative.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.EpicGame
import app.gamenative.db.PluviaDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class EpicGameDaoTest {
    private lateinit var database: PluviaDatabase
    private lateinit var dao: EpicGameDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.epicGameDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun upsertPreservesInstallStateOnlyForTheSameProviderIdentity() = runTest {
        dao.insert(
            EpicGame(
                id = 41,
                namespace = "namespace-a",
                catalogId = "shared-catalog-id",
                title = "Installed A",
                isInstalled = true,
                installPath = "installed-path",
            ),
        )

        dao.upsertPreservingInstallStatus(
            listOf(
                EpicGame(
                    namespace = "namespace-b",
                    catalogId = "shared-catalog-id",
                    title = "New B",
                ),
            ),
        )

        val existingA = dao.getByProviderIdentity("namespace-a", "shared-catalog-id")
        val newB = dao.getByProviderIdentity("namespace-b", "shared-catalog-id")
        assertEquals(41, existingA?.id)
        assertEquals(true, existingA?.isInstalled)
        assertNotNull(newB)
        assertFalse(requireNotNull(newB).isInstalled)
        assertEquals("", newB.installPath)
    }
}
