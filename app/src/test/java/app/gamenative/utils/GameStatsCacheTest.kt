package app.gamenative.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.utils.DeviceGameStatsService.DeviceGameStats
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkAll
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GameStatsCacheTest {

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PrefManager.init(context)
        mockkObject(DeviceGameStatsService)
        DeviceGameStatsCache.clear()
        GpuGameStatsCache.clear()
        awaitPreference {
            PrefManager.deviceGameStatsCache == "{}" && PrefManager.gpuGameStatsCache == "{}"
        }
    }

    @After
    fun tearDown() {
        DeviceGameStatsCache.clear()
        GpuGameStatsCache.clear()
        unmockkAll()
    }

    @Test
    fun `newer device stats request owns memory and persistence after older fetch completes`() = runBlocking {
        val dispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val older = statsMap("Older Device", 1)
        val newer = statsMap("Newer Device", 2)
        coEvery { DeviceGameStatsService.fetchForDevice(any(), any(), any()) } coAnswers {
            if (calls.incrementAndGet() == 1) {
                firstStarted.countDown()
                awaitUninterruptibly(releaseFirst)
                older
            } else {
                newer
            }
        }

        try {
            val first = async(dispatcher) {
                DeviceGameStatsCache.refreshIfStale("device", "gpu", modernBuild = false)
            }
            assertTrue(firstStarted.await(10L, TimeUnit.SECONDS))
            val second = async(dispatcher) {
                DeviceGameStatsCache.refreshIfStale("device", "gpu", modernBuild = false)
            }
            assertTrue(second.await())
            releaseFirst.countDown()
            assertFalse(first.await())

            assertEquals(2, calls.get())
            assertEquals(newer, DeviceGameStatsCache.getAll())
            assertTrue(awaitPreference { PrefManager.deviceGameStatsCache.contains("Newer Device") })
            assertFalse(PrefManager.deviceGameStatsCache.contains("Older Device"))
        } finally {
            releaseFirst.countDown()
            dispatcher.close()
        }
    }

    @Test
    fun `newer gpu stats request owns memory and persistence after older fetch completes`() = runBlocking {
        val dispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val older = statsMap("Older GPU", 3)
        val newer = statsMap("Newer GPU", 4)
        coEvery { DeviceGameStatsService.fetchForGpu(any(), any()) } coAnswers {
            if (calls.incrementAndGet() == 1) {
                firstStarted.countDown()
                awaitUninterruptibly(releaseFirst)
                older
            } else {
                newer
            }
        }

        try {
            val first = async(dispatcher) {
                GpuGameStatsCache.refreshIfStale("gpu", modernBuild = false)
            }
            assertTrue(firstStarted.await(10L, TimeUnit.SECONDS))
            val second = async(dispatcher) {
                GpuGameStatsCache.refreshIfStale("gpu", modernBuild = false)
            }
            assertTrue(second.await())
            releaseFirst.countDown()
            assertFalse(first.await())

            assertEquals(2, calls.get())
            assertEquals(newer, GpuGameStatsCache.getAll())
            assertTrue(awaitPreference { PrefManager.gpuGameStatsCache.contains("Newer GPU") })
            assertFalse(PrefManager.gpuGameStatsCache.contains("Older GPU"))
        } finally {
            releaseFirst.countDown()
            dispatcher.close()
        }
    }

    private fun statsMap(name: String, value: Int): Map<GameSource, Map<String, DeviceGameStats>> = mapOf(
        GameSource.STEAM to mapOf(
            name to DeviceGameStats(
                successfulRuns = value,
                medianFps = value,
                fiveStarReviews = value,
                medianSessionSec = value,
            ),
        ),
    )

    private fun awaitPreference(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(25L)
        }
        return condition()
    }

    private fun awaitUninterruptibly(latch: CountDownLatch) {
        while (latch.count > 0L) {
            try {
                latch.await(100L, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                // Model a fetch that does not cooperate with cancellation.
            }
        }
    }
}
