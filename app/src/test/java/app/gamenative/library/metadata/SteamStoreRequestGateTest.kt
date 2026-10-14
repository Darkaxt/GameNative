package app.gamenative.library.metadata

import android.content.Context
import app.gamenative.library.canonical.catalog.SteamStoreSearchProvider
import app.gamenative.library.discovery.SteamReviewSummaryProvider
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SteamStoreRequestGateTest {
    @Test fun independentExecutorsShareRequestStartCadence() = runTest {
        val gate = SteamStoreRequestGate(nowEpochMs = { testScheduler.currentTime },
            nowMonotonicMs = { testScheduler.currentTime })
        val starts = mutableListOf<Long>()
        repeat(3) {
            SteamHttpRetryExecutor(storeGate = gate).execute {
                starts += testScheduler.currentTime
                response(200)
            }.close()
        }
        assertEquals(listOf(0L, 2_000L, 4_000L), starts)
    }

    @Test fun firstThrottleStopsRetriesAndPersistsFullServerCooldownForRecreation() = runTest {
        var persisted = SteamStoreRequestState()
        val gate = SteamStoreRequestGate(readState = { persisted }, writeState = { persisted = it },
            nowEpochMs = { testScheduler.currentTime }, nowMonotonicMs = { testScheduler.currentTime })
        var requests = 0
        val error = runCatching { SteamHttpRetryExecutor(storeGate = gate).execute {
            requests++; response(429, "120")
        } }.exceptionOrNull()
        assertTrue(error is SteamRateLimitExhaustedException)
        assertEquals(1, requests)
        assertEquals(120_000L, persisted.blockedUntilMs)
        assertEquals(4_000L, persisted.intervalMs)
        val recreated = SteamStoreRequestGate(readState = { persisted }, nowEpochMs = { testScheduler.currentTime },
            nowMonotonicMs = { testScheduler.currentTime })
        assertTrue(runCatching { recreated.execute { requests++; response(200) } }.exceptionOrNull() is SteamRateLimitExhaustedException)
        assertEquals(1, requests)
        delay(120_000)
        recreated.execute { requests++; response(200) }.close()
        assertEquals(2, requests)
    }

    @Test fun injectedContextConstructorCommitsCooldownThatAnotherInstanceReads() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("steam_store_request_gate_v1", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().clear().commit())
        try {
            val first = SteamStoreRequestGate(context)
            val before = System.currentTimeMillis()
            assertTrue(runCatching { first.execute { response(429, "120") } }
                .exceptionOrNull() is SteamRateLimitExhaustedException)
            assertEquals(4_000L, preferences.getLong("interval_ms", 0L))
            assertTrue(preferences.getLong("blocked_until_ms", 0L) >= before + 120_000L)
            val recreated = SteamStoreRequestGate(context)
            var requests = 0
            val error = runCatching { recreated.execute { requests++; response(200) } }.exceptionOrNull()
            assertTrue(error is SteamRateLimitExhaustedException)
            assertTrue((error as SteamRateLimitExhaustedException).retryAfterMs > 60_000L)
            assertEquals(0, requests)
        } finally {
            assertTrue(preferences.edit().clear().commit())
        }
    }

    @Test fun missingOrShortRetryAfterUsesAtLeastOneMinute() = runTest {
        for (header in listOf(null, "1", "invalid", "-1")) {
            var persisted = SteamStoreRequestState()
            val gate = SteamStoreRequestGate(writeState = { persisted = it }, nowEpochMs = { 0L })
            assertTrue(runCatching { gate.execute { response(429, header) } }.exceptionOrNull() is SteamRateLimitExhaustedException)
            assertEquals(60_000L, persisted.blockedUntilMs)
        }
    }

    @Test fun httpDateRetryAfterIsNotCappedAtThirtySeconds() = runTest {
        val now = Instant.parse("2026-10-14T00:00:00Z")
        var persisted = SteamStoreRequestState()
        val gate = SteamStoreRequestGate(writeState = { persisted = it }, nowEpochMs = { now.toEpochMilli() })
        val header = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(300).atZone(ZoneOffset.UTC))
        assertTrue(runCatching { gate.execute { response(429, header) } }.exceptionOrNull() is SteamRateLimitExhaustedException)
        assertEquals(now.toEpochMilli() + 300_000L, persisted.blockedUntilMs)
    }

    @Test fun cancellationDuringPacingReleasesGateWithoutSendingAnotherRequest() = runTest {
        var requests = 0
        val gate = SteamStoreRequestGate(nowEpochMs = { 0L }, nowMonotonicMs = { 0L },
            sleep = { throw CancellationException("cancelled") })
        gate.execute { requests++; response(200) }.close()
        assertTrue(runCatching { gate.execute { requests++; response(200) } }.exceptionOrNull() is CancellationException)
        assertEquals(1, requests)
    }

    @Test fun repeatedThrottleIncreasesCadenceWithoutOverflow() = runTest {
        var persisted = SteamStoreRequestState()
        val gate = SteamStoreRequestGate(writeState = { persisted = it },
            nowEpochMs = { testScheduler.currentTime }, nowMonotonicMs = { testScheduler.currentTime })
        repeat(6) {
            assertTrue(runCatching { gate.execute { response(429) } }.exceptionOrNull() is SteamRateLimitExhaustedException)
            delay(60_000)
        }
        assertEquals(30_000L, persisted.intervalMs)
    }

    @Test fun simultaneousCallersStillStartAtLeastTwoSecondsApart() = runTest {
        val gate = SteamStoreRequestGate(nowEpochMs = { testScheduler.currentTime },
            nowMonotonicMs = { testScheduler.currentTime })
        val starts = mutableListOf<Long>()
        val jobs = List(4) { async {
            gate.execute { starts += testScheduler.currentTime; response(200) }.close()
        } }
        jobs.forEach { it.await() }
        assertEquals(listOf(0L, 2_000L, 4_000L, 6_000L), starts)
    }

    @Test fun forwardWallClockChangeDoesNotBypassRequestStartSpacing() = runTest {
        var wallClock = 0L
        val gate = SteamStoreRequestGate(nowEpochMs = { wallClock },
            nowMonotonicMs = { testScheduler.currentTime })
        val starts = mutableListOf<Long>()
        gate.execute { starts += testScheduler.currentTime; response(200) }.close()
        wallClock += 30_000L
        gate.execute { starts += testScheduler.currentTime; response(200) }.close()
        assertEquals(listOf(0L, 2_000L), starts)
    }

    @Test fun wallClockChangeDoesNotShortenServerCooldownWithinProcess() = runTest {
        var wallClock = 0L
        val gate = SteamStoreRequestGate(nowEpochMs = { wallClock },
            nowMonotonicMs = { testScheduler.currentTime })
        assertTrue(runCatching { gate.execute { response(429, "120") } }
            .exceptionOrNull() is SteamRateLimitExhaustedException)
        wallClock = 300_000L
        var requests = 0
        val blocked = runCatching { gate.execute { requests++; response(200) } }.exceptionOrNull()
        assertTrue(blocked is SteamRateLimitExhaustedException)
        assertEquals(120_000L, (blocked as SteamRateLimitExhaustedException).retryAfterMs)
        assertEquals(0, requests)
        delay(120_000L)
        gate.execute { requests++; response(200) }.close()
        assertEquals(1, requests)
    }

    @Test fun cancelledQueuedCallerDoesNotSendAndNextCallerCanUseGate() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val gate = SteamStoreRequestGate(nowEpochMs = { testScheduler.currentTime },
            nowMonotonicMs = { testScheduler.currentTime })
        var requests = 0
        val first = launch { gate.execute {
            requests++; firstStarted.complete(Unit); releaseFirst.await(); response(200)
        }.close() }
        firstStarted.await()
        val queued = launch { gate.execute { requests++; response(200) }.close() }
        runCurrent()
        queued.cancelAndJoin()
        releaseFirst.complete(Unit)
        first.join()
        gate.execute { requests++; response(200) }.close()
        assertEquals(2, requests)
    }

    @Test fun cancellationAfterPacingBeforeDispatchDoesNotSend() = runTest {
        var requests = 0
        val gate = SteamStoreRequestGate(nowEpochMs = { 0L }, nowMonotonicMs = { 0L },
            sleep = { currentCoroutineContext().job.cancel() })
        gate.execute { requests++; response(200) }.close()
        val pending = launch { gate.execute { requests++; response(200) }.close() }
        pending.join()
        assertTrue(pending.isCancelled)
        assertEquals(1, requests)
    }

    @Test fun cancelledRequestReturningSuccessClosesUndeliveredResponse() = runTest {
        val body = TrackingBody()
        val gate = SteamStoreRequestGate()
        val pending = launch { gate.execute {
            currentCoroutineContext().job.cancel()
            response(200).newBuilder().body(body).build()
        } }
        pending.join()
        assertTrue(pending.isCancelled)
        assertTrue(body.closed)
    }

    @Test fun cancelledThrottleStillClosesAndDurablyPersistsBeforeReleasingGate() = runTest {
        var persisted = SteamStoreRequestState()
        val body = TrackingBody()
        val gate = SteamStoreRequestGate(nowEpochMs = { 0L }, writeState = {
            delay(1)
            persisted = it
        })
        val pending = launch { gate.execute {
            currentCoroutineContext().job.cancel()
            response(429, "120").newBuilder().body(body).build()
        } }
        pending.join()
        assertTrue(pending.isCancelled)
        assertTrue(body.closed)
        assertEquals(120_000L, persisted.blockedUntilMs)
        assertEquals(4_000L, persisted.intervalMs)
    }

    @Test fun throttledResponseClosesBeforePersistenceAndOtherExecutorsCannotSend() = runTest {
        val body = TrackingBody()
        val gate = SteamStoreRequestGate(nowEpochMs = { 0L }, writeState = { assertTrue(body.closed) })
        var requests = 0
        val first = runCatching { SteamHttpRetryExecutor(storeGate = gate).execute {
            requests++; response(429).newBuilder().body(body).build()
        } }.exceptionOrNull()
        val second = runCatching { SteamHttpRetryExecutor(storeGate = gate).execute {
            requests++; response(200)
        } }.exceptionOrNull()
        assertTrue(first is SteamRateLimitExhaustedException)
        assertTrue(second is SteamRateLimitExhaustedException)
        assertEquals(1, requests)
    }

    @Test fun persistenceFailureKeepsTypedThrottleAndInMemoryCooldown() = runTest {
        val failure = java.io.IOException("storage unavailable")
        val gate = SteamStoreRequestGate(nowEpochMs = { 0L }, writeState = { throw failure })
        val error = runCatching { gate.execute { response(429) } }.exceptionOrNull()
        assertTrue(error is SteamRateLimitExhaustedException)
        assertTrue(error!!.suppressed.contains(failure))
        var requests = 0
        assertTrue(runCatching { gate.execute { requests++; response(200) } }
            .exceptionOrNull() is SteamRateLimitExhaustedException)
        assertEquals(0, requests)
    }

    @Test fun successfulRecoveryIsPersistedButNeverBelowTwoSeconds() = runTest {
        var persisted = SteamStoreRequestState(intervalMs = 8_000L)
        val gate = SteamStoreRequestGate(readState = { persisted }, writeState = { persisted = it },
            nowEpochMs = { testScheduler.currentTime }, nowMonotonicMs = { testScheduler.currentTime })
        repeat(10) { gate.execute { response(200) }.close() }
        assertEquals(4_000L, persisted.intervalMs)
        repeat(20) { gate.execute { response(200) }.close() }
        assertEquals(2_000L, persisted.intervalMs)
    }

    @Test fun callbackResponseCancelledBeforeDispatcherDeliveryIsClosed() = runTest {
        val call = CallbackCall()
        val body = TrackingBody()
        val pending = async { call.awaitSteamResponse() }
        runCurrent()
        call.callback.onResponse(call, response(200).newBuilder().body(body).build())
        pending.cancelAndJoin()
        assertTrue(call.cancelled)
        assertTrue(body.closed)
    }

    @Test fun callbackResponseArrivingAfterCancellationIsClosed() = runTest {
        val call = CallbackCall()
        val body = TrackingBody()
        val pending = async { call.awaitSteamResponse() }
        runCurrent()
        pending.cancelAndJoin()
        call.callback.onResponse(call, response(200).newBuilder().body(body).build())
        assertTrue(call.cancelled)
        assertTrue(body.closed)
    }

    @Test fun throttleFromAnyStoreProviderBlocksTheOtherTwoWithoutImmediateRetries() = runTest {
        repeat(3) { firstProvider ->
            var requests = 0
            val client = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
                requests++
                response(429, "120").newBuilder().request(chain.request()).build()
            }.build()
            try {
                val gate = SteamStoreRequestGate(nowEpochMs = { testScheduler.currentTime },
                    nowMonotonicMs = { testScheduler.currentTime })
                val fetches = storeProviderFetches(client, gate)
                val ordered = listOf(fetches[firstProvider]) + fetches.filterIndexed { index, _ -> index != firstProvider }
                ordered.forEach { fetch ->
                    val error = runCatching { fetch() }.exceptionOrNull()
                    assertTrue(error is SteamRateLimitExhaustedException)
                    assertEquals(120_000L, (error as SteamRateLimitExhaustedException).retryAfterMs)
                }
                assertEquals(1, requests)
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test fun allStoreProvidersAndValidatedRedirectsShareStartCadence() = runTest {
        val starts = mutableListOf<Long>()
        val visits = mutableMapOf<String, Int>()
        val client = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
            starts += testScheduler.currentTime
            val path = chain.request().url.encodedPath
            val visit = visits.getOrDefault(path, 0) + 1
            visits[path] = visit
            if (visit == 1) {
                response(302).newBuilder().request(chain.request())
                    .header("Location", chain.request().url.toString()).build()
            } else {
                val body = when (path) {
                    "/api/appdetails" -> """{"1":{"success":true,"data":{"name":"Example","type":"game"}}}"""
                    "/api/storesearch/" -> """{"total":1,"items":[{"type":"app","name":"Example","id":1}]}"""
                    else -> """{"query_summary":{"total_reviews":1}}"""
                }
                response(200).newBuilder().request(chain.request()).body(body.toResponseBody()).build()
            }
        }.build()
        try {
            val gate = SteamStoreRequestGate(nowEpochMs = { testScheduler.currentTime },
                nowMonotonicMs = { testScheduler.currentTime })
            storeProviderFetches(client, gate).forEach { it() }
            assertEquals(listOf(0L, 2_000L, 4_000L, 6_000L, 8_000L, 10_000L), starts)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun storeProviderFetches(client: OkHttpClient, gate: SteamStoreRequestGate): List<suspend () -> Unit> {
        val catalog = SteamCatalogProvider(client, "https://store.steampowered.com/api/appdetails".toHttpUrl(),
            SteamUrlPolicy(), MetadataClock { 0L }, SteamHttpRetryExecutor(storeGate = gate))
        val search = SteamStoreSearchProvider(client, "https://store.steampowered.com/api/storesearch/".toHttpUrl(),
            SteamUrlPolicy(), SteamHttpRetryExecutor(storeGate = gate))
        val reviews = SteamReviewSummaryProvider(client, "https://store.steampowered.com/appreviews".toHttpUrl(),
            setOf("store.steampowered.com"), true, setOf(443), SteamHttpRetryExecutor(storeGate = gate))
        val locale = MetadataLocale("en-US", "US")
        return listOf(
            { catalog.fetch(1, locale); Unit },
            { search.search("Example", locale); Unit },
            { reviews.fetch(1); Unit },
        )
    }

    private class TrackingBody : ResponseBody() {
        var closed = false
        private val trackedSource = object : ForwardingSource(Buffer()) {
            override fun close() { closed = true; super.close() }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = 0L
        override fun source(): BufferedSource = trackedSource
    }

    private class CallbackCall : Call {
        lateinit var callback: Callback
        var cancelled = false
        private var executed = false
        override fun request(): Request = Request.Builder().url("https://store.steampowered.com/api/appdetails").build()
        override fun execute(): Response = error("Only enqueue is supported")
        override fun enqueue(responseCallback: Callback) { executed = true; callback = responseCallback }
        override fun cancel() { cancelled = true }
        override fun isExecuted(): Boolean = executed
        override fun isCanceled(): Boolean = cancelled
        override fun timeout(): Timeout = Timeout()
        override fun clone(): Call = CallbackCall()
    }

    private fun response(code: Int, retryAfter: String? = null): Response = Response.Builder()
        .request(Request.Builder().url("https://store.steampowered.com/api/appdetails").build())
        .protocol(Protocol.HTTP_1_1).code(code).message("test").body("".toResponseBody())
        .apply { retryAfter?.let { header("Retry-After", it) } }.build()
}
