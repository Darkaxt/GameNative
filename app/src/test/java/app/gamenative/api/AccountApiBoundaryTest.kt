package app.gamenative.api

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class AccountApiBoundaryTest {
    private val client = mockk<OkHttpClient>()
    private val call = mockk<Call>()
    private val messages = mutableListOf<String>()
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            messages += message
        }
    }

    @Before
    fun setUp() {
        mockkObject(GameNativeApi)
        every { GameNativeApi.httpClient } returns client
        every { client.newCall(any()) } returns call
        Timber.plant(tree)
    }

    @After
    fun tearDown() {
        Timber.uproot(tree)
        unmockkObject(GameNativeApi)
    }

    @Test
    fun deviceStartFailureDoesNotLogResponsePayload() = runTest {
        every { call.execute() } returns response(503, "SYNTHETIC_PRIVATE_PAYLOAD")
        assertTrue(AccountApi.startDeviceSignIn() is ApiResult.HttpError)
        assertFalse(messages.joinToString().contains("SYNTHETIC_PRIVATE_PAYLOAD"))
    }

    @Test
    fun devicePollFailureDoesNotLogResponsePayload() = runTest {
        every { call.execute() } returns response(503, "SYNTHETIC_PRIVATE_PAYLOAD")
        assertTrue(AccountApi.pollDeviceSignIn("synthetic-device") is AccountApi.PollResult.Failure)
        assertFalse(messages.joinToString().contains("SYNTHETIC_PRIVATE_PAYLOAD"))
    }

    @Test
    fun transportFailureLogsTypeWithoutPrivateExceptionMessage() = runTest {
        every { call.execute() } throws IOException("SYNTHETIC_PRIVATE_PAYLOAD")
        assertTrue(AccountApi.startDeviceSignIn() is ApiResult.NetworkError)
        assertFalse(messages.joinToString().contains("SYNTHETIC_PRIVATE_PAYLOAD"))
        assertTrue(messages.joinToString().contains("IOException"))
    }

    @Test
    fun deviceStartCancellationPropagates() = runTest {
        every { call.execute() } throws CancellationException("SYNTHETIC_CANCELLATION")
        try {
            AccountApi.startDeviceSignIn()
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertEquals("SYNTHETIC_CANCELLATION", actual.message)
        }
    }

    @Test
    fun devicePollCancellationPropagates() = runTest {
        every { call.execute() } throws CancellationException("SYNTHETIC_CANCELLATION")
        try {
            AccountApi.pollDeviceSignIn("synthetic-device")
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertEquals("SYNTHETIC_CANCELLATION", actual.message)
        }
    }

    @Test
    fun devicePollingPreservesPendingExpiredAndSlowDownStates() = runTest {
        every { call.execute() } returns response(202, "")
        assertEquals(AccountApi.PollResult.Pending, AccountApi.pollDeviceSignIn("synthetic"))
        every { call.execute() } returns response(410, "")
        assertEquals(AccountApi.PollResult.Expired, AccountApi.pollDeviceSignIn("synthetic"))
        every { call.execute() } returns response(429, "", "7")
        assertEquals(AccountApi.PollResult.SlowDown(7), AccountApi.pollDeviceSignIn("synthetic"))
    }

    private fun response(code: Int, body: String, retryAfter: String? = null): Response = Response.Builder()
        .request(Request.Builder().url("https://example.invalid/device").build())
        .protocol(Protocol.HTTP_1_1).code(code).message("Synthetic")
        .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
        .body(body.toResponseBody()).build()
}
