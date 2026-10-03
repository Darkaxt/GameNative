package app.gamenative.api

import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class SupportApiBoundaryTest {
    private val messages = mutableListOf<String>()
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            messages += message
        }
    }

    @Before
    fun setUp() {
        mockkObject(AccountApi)
        Timber.plant(tree)
    }

    @After
    fun tearDown() {
        Timber.uproot(tree)
        unmockkObject(AccountApi)
    }

    @Test
    fun supportFailureDoesNotLogServerReasonPayload() = runTest {
        coEvery { AccountApi.sendAuthorized<Any>(any(), any()) } coAnswers {
            secondArg<(Response) -> Any>().invoke(response(503, """{"error":"SYNTHETIC_PRIVATE_PAYLOAD"}"""))
        }
        assertEquals(ApiResult.HttpError(503, "SYNTHETIC_PRIVATE_PAYLOAD"), SupportApi.listConversations())
        assertFalse(messages.joinToString().contains("SYNTHETIC_PRIVATE_PAYLOAD"))
    }

    @Test
    fun signedOutSupportRequestRemainsUnavailable() = runTest {
        coEvery { AccountApi.sendAuthorized<Any>(any(), any()) } returns null
        assertEquals(ApiResult.HttpError(401, SupportApi.NOT_SIGNED_IN), SupportApi.listConversations())
    }

    @Test
    fun cancellationPropagatesInsteadOfBecomingNetworkFailure() = runTest {
        coEvery { AccountApi.sendAuthorized<Any>(any(), any()) } throws CancellationException("SYNTHETIC_CANCELLATION")
        try {
            SupportApi.listConversations()
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertEquals("SYNTHETIC_CANCELLATION", actual.message)
        }
    }

    @Test
    fun completeConversationListIsParsedWithoutInventingEntries() = runTest {
        coEvery { AccountApi.sendAuthorized<Any>(any(), any()) } coAnswers {
            secondArg<(Response) -> Any>().invoke(response(200, """{"conversations":[]}"""))
        }
        assertEquals(ApiResult.Success(emptyList<SupportApi.Conversation>()), SupportApi.listConversations())
    }

    private fun response(code: Int, body: String) = Response.Builder()
        .request(Request.Builder().url("https://example.invalid/support").build())
        .protocol(Protocol.HTTP_1_1).code(code).message("Synthetic")
        .body(body.toResponseBody()).build()
}
