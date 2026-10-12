package app.gamenative.library.dlc

import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class DlcHttpTransportTest {
    @Test
    fun allowedPublicCatalogIsDecodedWithoutAmbientBrowserCookies() = runTest {
        var sent: Request? = null
        var cookieWrites = 0
        val cookies = object : CookieJar {
            override fun loadForRequest(url: HttpUrl) = listOf(Cookie.Builder().name("private").value("synthetic-cookie").domain(url.host).build())
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { cookieWrites++ }
        }
        val client = client("{\"id\":42}", onRequest = { sent = it }).newBuilder().cookieJar(cookies).build()
        val result = DlcHttpTransport(client).get("https://api.gog.com/products/42?expand=expanded_dlcs".toHttpUrl())
        assertTrue(result is JsonObject)
        assertNull(sent!!.header("Cookie"))
        assertNull(sent!!.header("Authorization"))
        assertEquals(0, cookieWrites)
    }

    @Test
    fun credentialRequestCannotRedirectOrCopyBearerToAnotherHost() = runTest {
        var requests = 0
        val transport = DlcHttpTransport(client("", code = 302, onRequest = { requests++ }))
        var rejected = false
        try { transport.get("https://embed.gog.com/user/data/games".toHttpUrl(), "synthetic-token") } catch (_: DlcTransportException) { rejected = true }
        assertTrue(rejected)
        assertEquals(1, requests)
    }

    @Test
    fun credentialBearingPublicCatalogAndWrongPathsAreRejectedBeforeNetwork() = runTest {
        var requests = 0
        val transport = DlcHttpTransport(client("{}", onRequest = { requests++ }))
        val urls = listOf("https://api.gog.com/products/42?expand=expanded_dlcs", "https://embed.gog.com/user/data/games?token=secret", "http://embed.gog.com/user/data/games", "https://evil.invalid/user/data/games")
        urls.forEach { url ->
            var rejected = false
            try { transport.get(url.toHttpUrl(), "synthetic-token") } catch (_: DlcTransportException) { rejected = true }
            assertTrue(url, rejected)
        }
        assertEquals(0, requests)
    }

    @Test
    fun oversizedResponseCannotReachJsonParserOrLeakBody() = runTest {
        val transport = DlcHttpTransport(client("private-body".repeat(200_000)))
        var failure: DlcTransportException? = null
        try { transport.get("https://api.gog.com/products/42?expand=expanded_dlcs".toHttpUrl()) } catch (error: DlcTransportException) { failure = error }
        assertNotNull(failure)
        assertEquals("DLC provider unavailable", failure!!.message)
        assertNull(failure.cause)
    }

    @Test
    fun arbitraryNetworkExceptionDoesNotExposeAccountOrCredentialPayload() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { throw IOException("private-token account-id") }.build()
        var failure: DlcTransportException? = null
        try { DlcHttpTransport(client).get("https://api.gog.com/products/42?expand=expanded_dlcs".toHttpUrl()) } catch (error: DlcTransportException) { failure = error }
        assertNotNull(failure)
        assertEquals("DLC provider unavailable", failure!!.message)
        assertNull(failure.cause)
    }

    @Test
    fun malformedCredentialHeaderCannotExposeItsValueThroughBuilderErrors() = runTest {
        var failure: Exception? = null
        try { DlcHttpTransport(client("{}")).get("https://embed.gog.com/user/data/games".toHttpUrl(), "synthetic-token\nprivate-account") } catch (error: Exception) { failure = error }
        assertTrue(failure is DlcTransportException)
        assertEquals("DLC provider unavailable", failure!!.message)
        assertNull(failure.cause)
    }

    private fun client(body: String, code: Int = 200, onRequest: (Request) -> Unit = {}) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            onRequest(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("fixture").header("Location", "https://evil.invalid/account")
                .header("Set-Cookie", "private=synthetic-cookie")
                .body(body.toResponseBody()).build()
        }.build()
}
