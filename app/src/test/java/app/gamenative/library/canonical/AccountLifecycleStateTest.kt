package app.gamenative.library.canonical

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GameSource
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class AccountLifecycleStateTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SharedPreferencesAccountLifecycleState.clearForTest(context)
    }

    @After
    fun tearDown() {
        SharedPreferencesAccountLifecycleState.clearForTest(context)
    }

    @Test
    fun generationsPersistAndRemainSourceSpecific() {
        val first = SharedPreferencesAccountLifecycleState(context)

        assertEquals(1L, first.advanceGeneration(GameSource.GOG))
        assertEquals(2L, first.advanceGeneration(GameSource.GOG))
        assertEquals(1L, first.advanceGeneration(GameSource.EPIC))

        val restored = SharedPreferencesAccountLifecycleState(context)
        assertEquals(2L, restored.generation(GameSource.GOG))
        assertEquals(1L, restored.generation(GameSource.EPIC))
        assertEquals(0L, restored.generation(GameSource.AMAZON))
    }

    @Test
    fun readinessPersistsAndGenerationAdvanceClearsIt() {
        val first = SharedPreferencesAccountLifecycleState(context)

        assertTrue(first.markReady(GameSource.STEAM, 0L))
        assertEquals(0L, first.readyGeneration(GameSource.STEAM))
        assertEquals(0L, SharedPreferencesAccountLifecycleState(context).readyGeneration(GameSource.STEAM))

        assertEquals(1L, first.advanceGeneration(GameSource.STEAM))
        assertNull(first.readyGeneration(GameSource.STEAM))
        assertFalse(first.markReady(GameSource.STEAM, 0L))
        assertTrue(first.markReady(GameSource.STEAM, 1L))

        val restored = SharedPreferencesAccountLifecycleState(context)
        assertEquals(1L, restored.generation(GameSource.STEAM))
        assertEquals(1L, restored.readyGeneration(GameSource.STEAM))
    }

    @Test
    fun concurrentAdvancesAreMonotonic() = runTest {
        val state = SharedPreferencesAccountLifecycleState(context)
        val observed = ConcurrentLinkedQueue<Long>()

        (1..32).map {
            async(Dispatchers.Default) {
                observed += state.advanceGeneration(GameSource.GOG)
            }
        }.awaitAll()

        assertEquals((1L..32L).toList(), observed.sorted())
        assertEquals(32L, state.generation(GameSource.GOG))
    }

    @Test
    fun failedSynchronousPersistenceDoesNotAdvanceGeneration() {
        val preferences = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { preferences.getLong(any(), any()) } returns 0L
        every { preferences.edit() } returns editor
        every { editor.putLong(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.commit() } returns false
        val state = SharedPreferencesAccountLifecycleState.createForTest(preferences)

        val failure = runCatching { state.advanceGeneration(GameSource.GOG) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("ACCOUNT_LIFECYCLE_PERSIST_FAILED", failure?.message)
        assertEquals(0L, state.generation(GameSource.GOG))
    }
}
