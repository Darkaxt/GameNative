package app.gamenative.service.epic

import app.gamenative.utils.Net
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class EpicAssetBuildVersionTest {
    private val client = mockk<OkHttpClient>()
    private val call = mockk<Call>()
    private lateinit var manager: EpicManager

    @Before
    fun setUp() {
        mockkObject(Net)
        every { Net.http } returns client
        every { client.newCall(any()) } returns call
        manager = EpicManager(mockk(), mockk())
    }

    @After
    fun tearDown() = unmockkObject(Net)

    @Test
    fun cancellationPropagatesInsteadOfPublishingAnEmptyVersionMap() = runTest {
        val cancellation = CancellationException("SYNTHETIC_CANCELLATION")
        every { call.execute() } throws cancellation
        try {
            manager.fetchAssetBuildVersions("synthetic")
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertEquals(cancellation.message, actual.message)
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === cancellation })
        }
    }

    @Test
    fun transportFailureKeepsEnrichmentOptional() = runTest {
        every { call.execute() } throws IOException("SYNTHETIC_FAILURE")
        assertEquals(emptyMap<String, String>(), manager.fetchAssetBuildVersions("synthetic"))
    }

    @Test
    fun unsuccessfulResponseDoesNotInventVersions() = runTest {
        every { call.execute() } returns response(503, "")
        assertEquals(emptyMap<String, String>(), manager.fetchAssetBuildVersions("synthetic"))
    }

    @Test
    fun completeResponseUsesOnlyNonemptyAssetIdentitiesAndVersions() = runTest {
        every { call.execute() } returns response(200, """[{"appName":"game","buildVersion":"v2"},{"appName":"","buildVersion":"v3"},{"appName":"empty","buildVersion":""}]""")
        assertEquals(mapOf("game" to "v2"), manager.fetchAssetBuildVersions("synthetic"))
    }

    private fun response(code: Int, body: String) = Response.Builder()
        .request(Request.Builder().url("https://example.invalid/assets").build())
        .protocol(Protocol.HTTP_1_1).code(code).message("Synthetic")
        .body(body.toResponseBody()).build()
}
