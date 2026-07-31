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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PrefManager.init(context)
        mockkObject(DeviceGameStatsService)
        DeviceGameStatsCache.clear()
        GpuGameStatsCache.clear()
        assertTrue(PrefManager.deviceGameStatsCache == "{}" && PrefManager.gpuGameStatsCache == "{}")
    }

    @After
    fun tearDown() = runBlocking {
        unmockkAll()
        DeviceGameStatsCache.clear()
        GpuGameStatsCache.clear()
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

    @Test
    fun `device persistence finishes before a newer clear wins memory and storage`() = runBlocking {
        val persistenceStarted = CompletableDeferred<Unit>()
        val releasePersistence = CompletableDeferred<Unit>()
        val persisted = AtomicReference("{}")
        val older = statsMap("Persisting Device", 5)
        mockkObject(PrefManager)
        coEvery { PrefManager.writeDeviceGameStatsCache(any()) } coAnswers {
            val payload = firstArg<String>()
            if (payload.contains("Persisting Device")) {
                persistenceStarted.complete(Unit)
                releasePersistence.await()
            }
            persisted.set(payload)
        }
        coEvery { DeviceGameStatsService.fetchForDevice(any(), any(), any()) } returns older

        val olderCommit = async {
            DeviceGameStatsCache.refreshIfStale("device", "gpu", modernBuild = false)
        }
        persistenceStarted.await()
        val newerClear = async { DeviceGameStatsCache.clear() }
        yield()
        assertFalse("clear bypassed the in-progress acknowledged write", newerClear.isCompleted)

        releasePersistence.complete(Unit)
        assertTrue(olderCommit.await())
        newerClear.await()

        assertEquals("{}", persisted.get())
        assertTrue(DeviceGameStatsCache.getAll().isEmpty())
    }

    @Test
    fun `gpu persistence finishes before a newer clear wins memory and storage`() = runBlocking {
        val persistenceStarted = CompletableDeferred<Unit>()
        val releasePersistence = CompletableDeferred<Unit>()
        val persisted = AtomicReference("{}")
        val older = statsMap("Persisting GPU", 6)
        mockkObject(PrefManager)
        coEvery { PrefManager.writeGpuGameStatsCache(any()) } coAnswers {
            val payload = firstArg<String>()
            if (payload.contains("Persisting GPU")) {
                persistenceStarted.complete(Unit)
                releasePersistence.await()
            }
            persisted.set(payload)
        }
        coEvery { DeviceGameStatsService.fetchForGpu(any(), any()) } returns older

        val olderCommit = async {
            GpuGameStatsCache.refreshIfStale("gpu", modernBuild = false)
        }
        persistenceStarted.await()
        val newerClear = async { GpuGameStatsCache.clear() }
        yield()
        assertFalse("clear bypassed the in-progress acknowledged write", newerClear.isCompleted)

        releasePersistence.complete(Unit)
        assertTrue(olderCommit.await())
        newerClear.await()

        assertEquals("{}", persisted.get())
        assertTrue(GpuGameStatsCache.getAll().isEmpty())
    }

    @Test
    fun `device write failure is reported without publishing unpersisted memory`() = runBlocking {
        val fetched = statsMap("Unpersisted Device", 7)
        mockkObject(PrefManager)
        coEvery { PrefManager.writeDeviceGameStatsCache(any()) } throws
            IllegalStateException("private persistence failure")
        coEvery { DeviceGameStatsService.fetchForDevice(any(), any(), any()) } returns fetched

        assertFalse(DeviceGameStatsCache.refreshIfStale("device", "gpu", modernBuild = false))
        assertTrue(DeviceGameStatsCache.getAll().isEmpty())
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
