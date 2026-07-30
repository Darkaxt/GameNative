package app.gamenative.service.amazon

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
