package app.gamenative.library.canonical.artwork

import android.app.Application
import app.gamenative.data.GameSource
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.junit.rules.TimeoutRule

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkImageProviderTest {
    @get:Rule val timeout = TimeoutRule.seconds(30)
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun publicImageFetchDecodesIndependentArtwork() = runBlocking {
        val provider = provider()
        server.enqueue(imageResponse())
        val result = original(provider, server.url("/fixture.png").toString())
        assertNotNull(result)
        assertTrue(ArtworkFixtures.corroborates(ArtworkFixtures.fingerprint(192, 288), requireNotNull(result)))
    }

    @Test
    fun invalidOriginAddsNoEvidenceAndMakesNoRequest() = runBlocking {
        val provider = provider()
        assertNull(original(provider, "http://untrusted.invalid/fixture.png"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun chunkedBodyCannotBypassTheEncodedByteBudget() = runBlocking {
        val consumed = AtomicLong()
        val closed = AtomicBoolean()
        val client = OkHttpClient.Builder().addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = requireNotNull(response.body)
            val source = object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, byteCount).also {
                    if (it > 0) consumed.addAndGet(it)
                }
                override fun close() {
                    closed.set(true)
                    super.close()
                }
            }.buffer()
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source(): BufferedSource = source
            }).build()
        }.build()
        val provider = provider(client)
        val body = Buffer().write(ArtworkEncodedFixtures.png()).write(ByteArray(4_194_304))
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setChunkedBody(body, 4_096))
        assertNull(original(provider, server.url("/fixture.png").toString()))
        assertTrue("Fixture must actually reach the byte limit", consumed.get() > 2_097_152)
        assertTrue("Only one buffered read beyond the limit is permitted", consumed.get() <= 2_097_152 + 8_192L)
        assertTrue("Rejected response must close its source", closed.get())
    }

    @Test
    fun candidateRedirectCannotChangeItsAppId() = runBlocking {
        val provider = provider()
        server.enqueue(MockResponse().setResponseCode(302)
            .setHeader("Location", server.url("/steam/apps/43/library_600x900.jpg")))
        assertNull(fetch(provider, "candidate", 42, server.url("/steam/apps/42/library_600x900.jpg").toString()))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun inheritedCookiesAreNotSentToPublicArtwork() = runBlocking {
        val cookies = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            override fun loadForRequest(url: HttpUrl): List<Cookie> = listOf(
                Cookie.Builder().name("session").value("synthetic-secret").domain(url.host).build(),
            )
        }
        val provider = provider(OkHttpClient.Builder().cookieJar(cookies).build())
        server.enqueue(imageResponse())
        assertNotNull(original(provider, server.url("/fixture.png").toString()))
        val request = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(request)
        assertNull(requireNotNull(request).getHeader("Cookie"))
    }

    @Test
    fun effectiveResponseUrlIsRevalidatedBeforeUsingItsBody() = runBlocking {
        val client = OkHttpClient.Builder().addNetworkInterceptor { chain ->
            chain.proceed(chain.request()).newBuilder()
                .request(chain.request().newBuilder().url("http://untrusted.invalid/fixture.png").build())
                .build()
        }.build()
        val provider = provider(client)
        server.enqueue(imageResponse())
        assertNull(original(provider, server.url("/fixture.png").toString()))
    }

    @Test
    fun cancellationDuringBodyConsumptionCancelsTheOwnedCall() = runBlocking {
        val headers = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun responseHeadersEnd(call: Call, response: Response) { headers.complete(Unit) }
            override fun callFailed(call: Call, ioe: IOException) { stopped.complete(Unit) }
        }).build()
        val provider = provider(client)
        server.enqueue(MockResponse().setBody(Buffer())
            .setHeader("Content-Type", "image/png").setHeader("Content-Length", "100"))
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            original(provider, server.url("/fixture.png").toString())
        }
        withTimeout(2_000) { headers.await() }
        request.cancelAndJoin()
        withTimeout(2_000) { stopped.await() }
        assertTrue(request.isCancelled)
    }

    private fun imageResponse() = MockResponse().setHeader("Content-Type", "image/png")
        .setBody(Buffer().write(ArtworkEncodedFixtures.png()))

    private fun provider(client: OkHttpClient = OkHttpClient()): Any {
        val policyType = ArtworkBoundaryFixtures.type("ArtworkUrlPolicy")
        val policy = policyType.getConstructor(Map::class.java, Boolean::class.javaPrimitiveType, Set::class.java)
            .newInstance(mapOf(GameSource.GOG to setOf(server.hostName), GameSource.STEAM to setOf(server.hostName)),
                false, setOf(server.port))
        return ArtworkBoundaryFixtures.type("ArtworkImageProvider")
            .getConstructor(OkHttpClient::class.java, policyType).newInstance(client, policy)
    }

    private suspend fun original(provider: Any, raw: String): Any? = fetch(provider, "original", GameSource.GOG, raw, null)

    private suspend fun fetch(provider: Any, name: String, vararg arguments: Any?): Any? =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            val method = provider.javaClass.methods.single { it.name == name && it.parameterCount == arguments.size + 1 }
            try {
                method.invoke(provider, *arguments, continuation)
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        }
}
