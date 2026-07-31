package app.gamenative.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkAll
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GameCompatibilityCacheTest {

    @Before
    fun setUp() = runBlocking {
        PrefManager.init(ApplicationProvider.getApplicationContext<Context>())
        GameCompatibilityCache.clear()
        assertEquals("{}", PrefManager.gameCompatibilityCache)
    }

    @After
    fun tearDown() = runBlocking {
        unmockkAll()
        GameCompatibilityCache.clear()
        assertEquals("{}", PrefManager.gameCompatibilityCache)
    }

    @Test
    fun `valid old persistence finishes before newer clear persists empty state`() = runBlocking {
        val persisted = AtomicReference("{}")
        val oldWriteStarted = CountDownLatch(1)
        val releaseOldWrite = CountDownLatch(1)
        val callerDispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        mockkObject(PrefManager)
        coEvery { PrefManager.writeGameCompatibilityCache(any()) } coAnswers {
            val payload = firstArg<String>()
            if (payload.contains("Old Compatibility")) {
                oldWriteStarted.countDown()
                awaitUninterruptibly(releaseOldWrite)
            }
            persisted.set(payload)
        }
        val response = compatibility("Old Compatibility")
        val generation = GameCompatibilityCache.captureGeneration()

        try {
            val oldCommit = async(callerDispatcher) {
                GameCompatibilityCache.cacheAllIfCurrent(generation, mapOf(response.gameName to response))
            }
            assertTrue(oldWriteStarted.await(10L, TimeUnit.SECONDS))

            val clear = async(callerDispatcher) { GameCompatibilityCache.clear() }
            assertTrue(
                "clear did not invalidate the old generation before waiting",
                awaitCondition { GameCompatibilityCache.captureGeneration() != generation },
            )
            assertFalse("clear returned before the older persistent write completed", clear.isCompleted)

            releaseOldWrite.countDown()
            assertTrue(oldCommit.await())
            clear.await()

            assertEquals("{}", persisted.get())
            assertEquals(0, GameCompatibilityCache.size())
            assertNull(GameCompatibilityCache.getCached(response.gameName))
        } finally {
            releaseOldWrite.countDown()
            callerDispatcher.close()
        }
    }

    private fun compatibility(name: String) = GameCompatibilityService.GameCompatibilityResponse(
        gameName = name,
        totalPlayableCount = 1,
        gpuPlayableCount = 0,
        avgRating = 5f,
        hasBeenTried = true,
        isNotWorking = false,
    )

    private fun awaitCondition(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10L)
        }
        return condition()
    }

    private fun awaitUninterruptibly(latch: CountDownLatch) {
        while (latch.count > 0L) {
            try {
                latch.await(100L, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                // Model persistence that has already started and cannot be overtaken by clear.
            }
        }
    }
}
