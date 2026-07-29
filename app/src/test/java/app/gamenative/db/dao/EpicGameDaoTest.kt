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
    fun canonicalProjectionReadIncludesExcludedPhysicalRowsWithoutChangingLegacyRead() = runTest {
        val games = listOf(
            EpicGame(
                id = 1,
                namespace = "games",
                catalogId = "normal",
                title = "Normal",
            ),
            EpicGame(
                id = 2,
                namespace = "games",
                catalogId = "dlc",
                title = "DLC",
                isDLC = true,
            ),
            EpicGame(
                id = 3,
                namespace = "ue",
                catalogId = "marketplace",
                title = "Unreal Marketplace",
            ),
            EpicGame(
                id = 4,
                namespace = "89efe5924d3d467c839449ab6ab52e7f",
                catalogId = "engine",
                title = "Unreal Engine",
            ),
        )
        dao.insertAll(games)

        assertEquals(listOf("Normal"), dao.getAllAsList().map { it.title })
        assertEquals(
            games.map { it.namespace to it.catalogId }.toSet(),
            dao.getAllForCanonicalProjection().map { it.namespace to it.catalogId }.toSet(),
        )
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
