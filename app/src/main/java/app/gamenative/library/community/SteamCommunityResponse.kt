package app.gamenative.library.community

import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

internal suspend fun Call.awaitBufferedCommunityResponse(
    maxBodyBytes: Long,
    canReadBody: (Response) -> Boolean,
    bodyLimitFailure: () -> IOException,
): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }

            override fun onResponse(call: Call, response: Response) {
                val detached = try {
                    response.use {
                        val contentType = it.body.contentType()
                        val bytes = if (continuation.isActive && canReadBody(it)) {
                            if (it.body.contentLength() > maxBodyBytes) throw bodyLimitFailure()
                            val source = it.body.source()
                            source.request(maxBodyBytes + 1L)
                            if (source.buffer.size > maxBodyBytes) throw bodyLimitFailure()
                            source.readByteArray()
                        } else {
                            ByteArray(0)
                        }
                        it.newBuilder().body(bytes.toResponseBody(contentType)).build()
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                    return
                }
                continuation.resume(detached) { _, deliveredResponse, _ ->
                    deliveredResponse.close()
                }
            }
        },
    )
}
