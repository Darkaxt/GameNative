package app.gamenative.library.discovery

import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.db.dao.CanonicalGameDao
import io.mockk.coEvery
import io.mockk.mockk
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamPopularityEnricherTest {
    @Test
    fun usesExactlyFourWorkersAndStartsVisibleNullTargetsFirst() = runTest {
        val gate = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val starts = CopyOnWriteArrayList<Int>()
        val source = SteamReviewSummarySource { appId ->
            starts += appId
            val now = active.incrementAndGet()
            maximum.updateAndGet { previous -> maxOf(previous, now) }
            try {
                gate.await()
                SteamReviewSummary(appId)
            } finally {
                active.decrementAndGet()
            }
        }
        val persisted = ConcurrentHashMap<String, Int>()
        val enricher = enricher(source, persisted)
        val all = (1..8).map(::target)
        val progress = CopyOnWriteArrayList<SteamPopularityEnrichmentProgress>()

        val job = launch { enricher.enrich(all.take(3), all, progress::add) }
        runCurrent()

        assertEquals(4, active.get())
        assertEquals(listOf(1, 2, 3, 4), starts.toList())
        gate.complete(Unit)
        job.join()

        assertEquals(4, maximum.get())
        assertEquals((1..8).toSet(), persisted.values.toSet())
        assertEquals(SteamPopularityEnrichmentProgress(8, 8, 0, false), progress.last())
    }

    @Test
    fun cancellationPersistsFinishedWorkAndLaterInvocationResumesOnlyNullValues() = runTest {
        val firstFinished = CompletableDeferred<Unit>()
        val firstCalls = CopyOnWriteArrayList<Int>()
        val persisted = ConcurrentHashMap<String, Int>()
        val firstSource = SteamReviewSummarySource { appId ->
            firstCalls += appId
            if (appId == 1) {
                SteamReviewSummary(101).also { firstFinished.complete(Unit) }
            } else {
                awaitCancellation()
            }
        }
        val all = (1..6).map(::target)
        val firstJob: Job = launch { enricher(firstSource, persisted).enrich(all, all) }
        firstFinished.await()
        runCurrent()

        firstJob.cancelAndJoin()
        assertEquals(101, persisted[canonicalId(1).value])

        val resumed = all.map { item ->
            item.copy(steamReviewCount = persisted[item.canonicalId.value])
        }
        val secondCalls = Collections.synchronizedList(mutableListOf<Int>())
        val finalProgress = enricher(
            source = SteamReviewSummarySource { appId ->
                secondCalls += appId
                SteamReviewSummary(appId + 100)
            },
            persisted = persisted,
        ).enrich(resumed, resumed)

        assertFalse(1 in secondCalls)
        assertEquals(setOf(2, 3, 4, 5, 6), secondCalls.toSet())
        assertEquals(6, persisted.size)
        assertEquals(SteamPopularityEnrichmentProgress(5, 5, 0, false), finalProgress)
    }

    @Test
    fun providerFailuresRetainCachedCountsAndReportCompletedTotalFailedProgress() = runTest {
        val cached = target(1).copy(steamReviewCount = 777)
        val missing = target(2)
        val persisted = ConcurrentHashMap(mapOf(cached.canonicalId.value to 777))
        val progress = CopyOnWriteArrayList<SteamPopularityEnrichmentProgress>()
        val enricher = enricher(
            source = SteamReviewSummarySource { throw IOException("fixed failure") },
            persisted = persisted,
        )

        val result = enricher.enrich(
            visibleTargets = listOf(cached, missing),
            allTargets = listOf(cached, missing),
            onProgress = progress::add,
        )

        assertEquals(777, persisted[cached.canonicalId.value])
        assertEquals(1, persisted.size)
        assertEquals(SteamPopularityEnrichmentProgress(1, 1, 1, false), result)
        assertEquals(SteamPopularityEnrichmentProgress(1, 0, 0, true), progress.first())
        assertEquals(result, progress.last())
    }

    @Test
    fun negativeAggregateIsNeverPersisted() = runTest {
        val persisted = ConcurrentHashMap<String, Int>()
        val result = enricher(
            source = SteamReviewSummarySource { SteamReviewSummary(-1) },
            persisted = persisted,
        ).enrich(listOf(target(1)), listOf(target(1)))

        assertTrue(persisted.isEmpty())
        assertEquals(SteamPopularityEnrichmentProgress(1, 1, 1, false), result)
    }

    @Test(timeout = 5_000L)
    fun nineHundredTargetsCompleteWithBoundedWorkers() = runTest {
        val persisted = ConcurrentHashMap<String, Int>()
        val all = (1..900).map(::target)

        val result = enricher(
            source = SteamReviewSummarySource { SteamReviewSummary(it) },
            persisted = persisted,
        ).enrich(all.take(50), all)

        assertEquals(900, persisted.size)
        assertEquals(SteamPopularityEnrichmentProgress(900, 900, 0, false), result)
    }

    private fun enricher(
        source: SteamReviewSummarySource,
        persisted: ConcurrentHashMap<String, Int>,
    ): SteamPopularityEnricher {
        val dao = mockk<CanonicalGameDao>()
        coEvery {
            dao.updateSteamReviewCountIfMissing(any(), any(), any())
        } answers {
            val canonicalId = firstArg<String>()
            val appId = secondArg<Int>()
            val count = thirdArg<Long>()
            if (appId <= 0 || count !in 0..Int.MAX_VALUE.toLong()) {
                0
            } else {
                persisted.putIfAbsent(canonicalId, count.toInt())
                1
            }
        }
        return SteamPopularityEnricher(source, dao)
    }

    private fun target(number: Int) = SteamPopularityTarget(
        canonicalId = canonicalId(number),
        steamAppId = number,
        steamReviewCount = null,
    )

    private fun canonicalId(number: Int): CanonicalGameId = CanonicalGameId.parse(
        "00000000-0000-0000-0000-${number.toString().padStart(12, '0')}",
    )
}
