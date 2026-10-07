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
        app.gamenative.PrefManager.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        app.gamenative.PrefManager.discordMergePending = false
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
    fun fixRequestFailureDoesNotLogServerReasonPayload() = runTest {
        coEvery { AccountApi.sendAuthorized<Any>(any(), any()) } coAnswers {
            secondArg<(Response) -> Any>().invoke(response(503, """{"error":"SYNTHETIC_PRIVATE_FIX_PAYLOAD"}"""))
        }
        assertEquals(SupportApi.FixRequestResult.Failed("SYNTHETIC_PRIVATE_FIX_PAYLOAD"), SupportApi.fixRequest("synthetic"))
        assertFalse(messages.joinToString().contains("SYNTHETIC_PRIVATE_FIX_PAYLOAD"))
    }

    @Test
    fun anonymousLinkFailureDoesNotLogServerReasonPayload() = runTest {
        val client = io.mockk.mockk<okhttp3.OkHttpClient>()
        val call = io.mockk.mockk<okhttp3.Call>()
        mockkObject(GameNativeApi)
        try {
            io.mockk.every { GameNativeApi.httpClient } returns client
            io.mockk.every { client.newCall(any()) } returns call
            io.mockk.every { call.execute() } returns response(503, """{"error":"SYNTHETIC_PRIVATE_LINK_PAYLOAD"}""")
            assertEquals(ApiResult.HttpError(503, "SYNTHETIC_PRIVATE_LINK_PAYLOAD"), SupportApi.createDiscordLinkCode("synthetic", false))
            assertFalse(messages.joinToString().contains("SYNTHETIC_PRIVATE_LINK_PAYLOAD"))
        } finally {
            unmockkObject(GameNativeApi)
        }
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
