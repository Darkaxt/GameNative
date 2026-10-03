package app.gamenative.service.gog

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.library.canonical.AccountScopeInvalidations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GOGHiddenRefreshTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private val context = mockk<Context>()
    private val dao = mockk<GOGGameDao>(relaxed = true)
    private lateinit var manager: GOGManager
    private var generation = 17L

    @Before
    fun setUp() {
        every { context.filesDir } returns temporaryFolder.root
        mockkObject(GOGAuthManager, GOGApiClient, AccountScopeInvalidations)
        every { AccountScopeInvalidations.generation(GameSource.GOG) } answers { generation }
        coEvery { GOGAuthManager.hasStoredCredentials(context) } returns true
        coEvery { GOGApiClient.getHiddenGameIds(context) } returns Result.success(setOf("42"))
        manager = GOGManager(dao, mockk(), context)
    }

    @After
    fun tearDown() = unmockkObject(GOGAuthManager, GOGApiClient, AccountScopeInvalidations)

    @Test
    fun currentAccountAppliesCompleteHiddenSet() = runTest {
        assertEquals(setOf("42"), manager.refreshHiddenIds())
        coVerify(exactly = 1) { dao.applyHiddenFlags(setOf("42")) }
    }

    @Test
    fun successfulEmptySetClearsOldFlags() = runTest {
        coEvery { GOGApiClient.getHiddenGameIds(context) } returns Result.success(emptySet())
        assertEquals(emptySet<String>(), manager.refreshHiddenIds())
        coVerify(exactly = 1) { dao.applyHiddenFlags(emptySet()) }
    }

    @Test
    fun failedFetchRetainsStoredFlags() = runTest {
        coEvery { GOGApiClient.getHiddenGameIds(context) } returns Result.failure(IllegalStateException("SYNTHETIC_FAILURE"))
        assertNull(manager.refreshHiddenIds())
        coVerify(exactly = 0) { dao.applyHiddenFlags(any()) }
    }

    @Test
    fun accountChangeDuringFetchCannotOverwriteCurrentAccountFlags() = runTest {
        coEvery { GOGApiClient.getHiddenGameIds(context) } coAnswers {
            generation += 1
            Result.success(setOf("42"))
        }
        assertNull(manager.refreshHiddenIds())
        coVerify(exactly = 0) { dao.applyHiddenFlags(any()) }
    }

    @Test
    fun unavailableAccountDoesNotFetchOrPersistFlags() = runTest {
        coEvery { GOGAuthManager.hasStoredCredentials(context) } returns false
        assertNull(manager.refreshHiddenIds())
        coVerify(exactly = 0) { GOGApiClient.getHiddenGameIds(any()) }
        coVerify(exactly = 0) { dao.applyHiddenFlags(any()) }
    }

    @Test
    fun returnedCancellationPropagatesInsteadOfBecomingOptionalMetadataFailure() = runTest {
        val cancellation = CancellationException("SYNTHETIC_CANCELLATION")
        coEvery { GOGApiClient.getHiddenGameIds(context) } returns Result.failure(cancellation)
        try {
            manager.refreshHiddenIds()
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        coVerify(exactly = 0) { dao.applyHiddenFlags(any()) }
    }
}
