package app.gamenative.service.amazon

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmazonUpdateProbeTest {
    @Test
    fun batchUsesOneTokenAndOneApiCallWhilePreservingPartialResults() = runTest {
        val tokenCalls = AtomicInteger()
        val apiCalls = AtomicInteger()
        val requests = listOf(
            AmazonUpdateVersionRequest("product-a", "stored-a"),
            AmazonUpdateVersionRequest("product-b", "stored-b"),
        )

        val results = AmazonService.probeUpdateVersions(
            requests = requests,
            tokenProvider = {
                tokenCalls.incrementAndGet()
                "token"
            },
            liveVersionFetcher = { productIds, token ->
                apiCalls.incrementAndGet()
                assertEquals(listOf("product-a", "product-b"), productIds)
                assertEquals("token", token)
                mapOf("product-a" to "live-a")
            },
        )

        assertEquals(1, tokenCalls.get())
        assertEquals(1, apiCalls.get())
        assertEquals(
            AmazonUpdateVersionResult.Observed(updateAvailable = true),
            results["product-a"],
        )
        assertEquals(
            AmazonUpdateVersionResult.Failed(AmazonLiveVersionMissingException::class),
            results["product-b"],
        )
    }

    @Test
    fun blankStoredAndLiveVersionsRemainTypedFailures() = runTest {
        val requests = listOf(
            AmazonUpdateVersionRequest("missing-stored", "   "),
            AmazonUpdateVersionRequest("missing-live", "stored"),
            AmazonUpdateVersionRequest("healthy", "stored"),
        )

        val results = AmazonService.probeUpdateVersions(
            requests = requests,
            tokenProvider = { "token" },
            liveVersionFetcher = { productIds, _ ->
                assertEquals(listOf("missing-live", "healthy"), productIds)
                mapOf(
                    "missing-live" to "   ",
                    "healthy" to "stored",
                )
            },
        )

        assertEquals(
            AmazonUpdateVersionResult.Failed(AmazonStoredVersionUnavailableException::class),
            results["missing-stored"],
        )
        assertEquals(
            AmazonUpdateVersionResult.Failed(AmazonLiveVersionMissingException::class),
            results["missing-live"],
        )
        assertEquals(
            AmazonUpdateVersionResult.Observed(updateAvailable = false),
            results["healthy"],
        )
    }

    @Test
    fun missingTokenKeepsEveryRequestedVersionUnknownWithoutApiCall() = runTest {
        val apiCalls = AtomicInteger()
        val requests = listOf(
            AmazonUpdateVersionRequest("product-a", "stored-a"),
            AmazonUpdateVersionRequest("product-b", "stored-b"),
        )

        val results = AmazonService.probeUpdateVersions(
            requests = requests,
            tokenProvider = { null },
            liveVersionFetcher = { _, _ ->
                apiCalls.incrementAndGet()
                emptyMap()
            },
        )

        assertEquals(0, apiCalls.get())
        results.values.forEach { result ->
            assertTrue(result is AmazonUpdateVersionResult.Failed)
            assertEquals(
                AmazonTokenUnavailableException::class,
                (result as AmazonUpdateVersionResult.Failed).errorClass,
            )
        }
    }

    @Test
    fun apiFailureKeepsEveryRequestedVersionUnknown() = runTest {
        val requests = listOf(
            AmazonUpdateVersionRequest("product-a", "stored-a"),
            AmazonUpdateVersionRequest("product-b", "stored-b"),
        )

        val results = AmazonService.probeUpdateVersions(
            requests = requests,
            tokenProvider = { "token" },
            liveVersionFetcher = { _, _ -> null },
        )

        assertEquals(
            setOf(AmazonLiveVersionsUnavailableException::class),
            results.values.map { (it as AmazonUpdateVersionResult.Failed).errorClass }.toSet(),
        )
    }

    @Test
    fun accountSwitchDuringTokenAcquisitionAbortsBeforeHttpDispatch() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val tokenStarted = CompletableDeferred<Unit>()
            val releaseToken = CompletableDeferred<Unit>()
            var ownerCurrent = true
            val request = AmazonUpdateVersionRequest("old-product", "stored")

            val result = async {
                AmazonService.probeUpdateVersions(
                    requests = listOf(request),
                    tokenProvider = {
                        tokenStarted.complete(Unit)
                        releaseToken.await()
                        "new-account-token"
                    },
                    expectedOwnerIsCurrent = { ownerCurrent },
                    liveVersionFetcher = { productIds, token ->
                        AmazonApiClient.fetchLiveVersionIdsAt(
                            url = server.url("/versions").toString(),
                            adgProductIds = productIds,
                            bearerToken = token,
                            client = OkHttpClient(),
                        )
                    },
                )
            }

            tokenStarted.await()
            ownerCurrent = false
            releaseToken.complete(Unit)

            assertEquals(
                AmazonUpdateVersionResult.Failed(AmazonUpdateOwnerChangedException::class),
                result.await()[request.productId],
            )
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun cancellingDelayedLiveVersionRequestCancelsActualOkHttpCall() = runBlocking {
        val server = MockWebServer()
        val cancelled = CountDownLatch(1)
        val client = OkHttpClient.Builder()
            .eventListener(
                object : EventListener() {
                    override fun canceled(call: Call) {
                        cancelled.countDown()
                    }
                },
            )
            .build()
        server.enqueue(
            MockResponse()
                .setBodyDelay(30L, TimeUnit.SECONDS)
                .setBody("{\"adgProductIdToVersionIdMap\":{\"product\":\"live\"}}"),
        )
        server.start()
        try {
            val request = launch(Dispatchers.IO) {
                AmazonApiClient.fetchLiveVersionIdsAt(
                    url = server.url("/versions").toString(),
                    adgProductIds = listOf("product"),
                    bearerToken = "token",
                    client = client,
                )
            }

            assertNotNull(server.takeRequest(5L, TimeUnit.SECONDS))
            request.cancelAndJoin()

            assertTrue(request.isCancelled)
            assertTrue(cancelled.await(5L, TimeUnit.SECONDS))
        } finally {
            server.shutdown()
        }
    }
}
