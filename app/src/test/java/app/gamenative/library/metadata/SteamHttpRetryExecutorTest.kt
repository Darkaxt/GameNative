package app.gamenative.library.metadata

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SteamHttpRetryExecutorTest {
    @Test
    fun retriesRateLimitThenReturnsSuccessfulResponse() = runTest {
        val delays = mutableListOf<Long>()
        val responses = ArrayDeque(listOf(response(429), response(200)))
        val executor = executor(delays = delays)

        executor.execute { responses.removeFirst() }.use { result ->
            assertEquals(200, result.code)
        }

        assertEquals(listOf(1_000L), delays)
    }

    @Test
    fun fourRateLimitsThrowTypedExhaustionWithoutFourthDelay() = runTest {
        val delays = mutableListOf<Long>()
        var attempts = 0
        val executor = executor(delays = delays)

        assertThrows(SteamRateLimitExhaustedException::class.java) {
            kotlinx.coroutines.test.runTest {
                executor.execute {
                    attempts += 1
                    response(429)
                }
            }
        }

        assertEquals(4, attempts)
        assertEquals(listOf(1_000L, 2_000L, 4_000L), delays)
    }

    @Test
    fun numericRetryAfterIsCappedAtThirtySeconds() = runTest {
        val delays = mutableListOf<Long>()
        val responses = ArrayDeque(listOf(response(429, "120"), response(200)))
        val executor = executor(delays = delays)

        executor.execute { responses.removeFirst() }.close()

        assertEquals(listOf(30_000L), delays)
    }

    @Test
    fun httpDateRetryAfterUsesFixedClockAndCap() = runTest {
        val delays = mutableListOf<Long>()
        val now = Instant.parse("2026-08-10T12:00:00Z")
        val retryAt = DateTimeFormatter.RFC_1123_DATE_TIME.format(
            now.plusSeconds(5).atZone(ZoneOffset.UTC),
        )
        val responses = ArrayDeque(listOf(response(429, retryAt), response(200)))
        val executor = executor(delays = delays, nowEpochMs = now.toEpochMilli())

        executor.execute { responses.removeFirst() }.close()

        assertEquals(listOf(5_000L), delays)
    }

    @Test
    fun invalidRetryAfterUsesBoundedFallbackSequence() = runTest {
        val delays = mutableListOf<Long>()
        val responses = ArrayDeque(
            listOf(
                response(429, "invalid"),
                response(429, "-1"),
                response(429),
                response(200),
            ),
        )
        val executor = executor(delays = delays)

        executor.execute { responses.removeFirst() }.close()

        assertEquals(listOf(1_000L, 2_000L, 4_000L), delays)
    }

    @Test
    fun immediateSuccessDoesNotDelay() = runTest {
        val delays = mutableListOf<Long>()
        val executor = executor(delays = delays)

        executor.execute { response(200) }.close()

        assertEquals(emptyList<Long>(), delays)
    }

    @Test
    fun cancellationDuringDelayPropagates() = runTest {
        val executor = SteamHttpRetryExecutor(
            sleep = { throw CancellationException("cancelled") },
            nowEpochMs = { 0L },
        )

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.test.runTest {
                executor.execute { response(429) }
            }
        }
    }

    private fun executor(
        delays: MutableList<Long>,
        nowEpochMs: Long = 0L,
    ) = SteamHttpRetryExecutor(
        sleep = { delays += it },
        nowEpochMs = { nowEpochMs },
    )

    private fun response(code: Int, retryAfter: String? = null): Response = Response.Builder()
        .request(Request.Builder().url("https://store.steampowered.com/api/appdetails").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .body("".toResponseBody())
        .apply {
            retryAfter?.let { header("Retry-After", it) }
        }
        .build()
}
