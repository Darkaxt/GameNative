package app.gamenative.library.metadata

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import okhttp3.Response

class SteamRateLimitExhaustedException internal constructor() :
    IOException("Steam rate limit exhausted")

internal class SteamHttpRetryExecutor(
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun execute(request: suspend () -> Response): Response {
        repeat(MAX_ATTEMPTS) { attempt ->
            val response = request()
            if (response.code != HTTP_TOO_MANY_REQUESTS) return response

            val retryAfter = response.header("Retry-After")
            response.close()
            if (attempt == MAX_ATTEMPTS - 1) {
                throw SteamRateLimitExhaustedException()
            }
            sleep(retryDelayMs(retryAfter, attempt))
        }
        error("Unreachable retry state")
    }

    private fun retryDelayMs(retryAfter: String?, attempt: Int): Long {
        val numericDelay = retryAfter
            ?.toLongOrNull()
            ?.takeIf { it >= 0L }
            ?.let { seconds -> seconds.coerceAtMost(MAX_DELAY_SECONDS) * 1_000L }
        if (numericDelay != null) return numericDelay

        val dateDelay = retryAfter
            ?.let { value ->
                runCatching {
                    ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant()
                        .toEpochMilli()
                }.getOrNull()
            }
            ?.let { retryAt ->
                (retryAt - nowEpochMs()).coerceIn(0L, MAX_DELAY_MILLIS)
            }
        return dateDelay ?: FALLBACK_DELAYS_MILLIS[attempt]
    }

    private companion object {
        const val MAX_ATTEMPTS = 4
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val MAX_DELAY_SECONDS = 30L
        const val MAX_DELAY_MILLIS = MAX_DELAY_SECONDS * 1_000L
        val FALLBACK_DELAYS_MILLIS = longArrayOf(1_000L, 2_000L, 4_000L)
    }
}
