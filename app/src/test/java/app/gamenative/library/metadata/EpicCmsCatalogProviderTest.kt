package app.gamenative.library.metadata

import app.gamenative.data.canonical.EpicStableSourceId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class EpicCmsCatalogProviderTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun alanWakeTwoMapsToExactCanonicalMetadataShape() = runTest {
        server.enqueue(jsonResponse(cmsFixture()))

        val record = requireNotNull(provider().fetch(request()))

        assertEquals(STABLE_SOURCE_ID, record.stableSourceId)
        assertEquals(NAMESPACE, record.namespace)
        assertEquals(CATALOG_ID, record.catalogId)
        assertEquals("alan-wake-2", record.slug)
        assertEquals(OFFER_ID, record.offerId)
        assertEquals("https://store.epicgames.com/en-US/p/alan-wake-2", record.storeUrl)
        assertEquals(
            CanonicalGameMetadata(
                title = "Alan Wake 2",
                shortDescription = "A supernatural survival-horror sequel.",
                about = "Saga Anderson and Alan Wake confront a dark story.",
                headerImageUrl = HERO,
                screenshots = listOf(SCREENSHOT),
                movies = listOf(GameMovie(null, POSTER, HLS)),
                developers = listOf("Remedy Entertainment"),
                publishers = listOf("Epic Games Publishing"),
                releaseDate = null,
                platforms = setOf(GamePlatform.WINDOWS),
                languages = listOf("English", "German", "French"),
                requirements = GameRequirements(
                    minimum = "Windows OS: Windows 10 64-bit\nWindows Memory: 16 GB",
                    recommended = "Windows OS: Windows 11 64-bit\nWindows Memory: 16 GB",
                ),
                genres = emptyList(),
                features = emptyList(),
                achievementCount = null,
                dlcCount = null,
                fetchedAtEpochMs = 1_700_000_000_000L,
            ),
            record.metadata,
        )
        val networkRequest = server.takeRequest()
        assertEquals("/api/en-US/content/products/alan-wake-2", networkRequest.requestUrl?.encodedPath)
        assertNull(networkRequest.getHeader("Authorization"))
        assertNull(networkRequest.getHeader("Cookie"))
    }

    @Test
    fun rejectsConflictingCmsIdentityAndUnsafeMedia() = runTest {
        listOf(
            cmsFixture(namespace = "wrong") to "namespace",
            cmsFixture(rootTitle = "Different Game") to "title",
            cmsFixture(catalogId = "different") to "catalog",
            cmsFixture(hero = "https://evil.example/hero.jpg") to "media",
        ).forEach { (body, expectedMessage) ->
            server.enqueue(jsonResponse(body))
            assertProviderFailure(expectedMessage)
        }
    }

    @Test
    fun rejectsWrongContentTypeOversizedBodyAndEndpointRedirect() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(cmsFixture()))
        assertProviderFailure()

        server.enqueue(jsonResponse("x".repeat(1_048_577)))
        assertProviderFailure()

        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/api/en-US/not-products"))
        assertProviderFailure()
    }

    @Test
    fun cancellationClosesAStalledSuccessfulResponseBody() = runTest {
        server.enqueue(
            jsonResponse(cmsFixture())
                .setBodyDelay(30, TimeUnit.SECONDS),
        )
        val job = launch(Dispatchers.IO) { provider().fetch(request()) }
        requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        Thread.sleep(100)

        withContext(Dispatchers.Default) {
            withTimeout(1_000) {
                job.cancelAndJoin()
            }
        }
    }

    @Test
    fun invalidCalendarReleaseDateIsNotNormalized() = runTest {
        server.enqueue(jsonResponse(cmsFixture(releaseDate = "April 31, 2024")))

        val record = requireNotNull(provider().fetch(request()))

        assertNull(record.metadata.releaseDate)
    }

    @Test
    fun malformedOptionalCatalogIdentityFieldsAreRejected() = runTest {
        listOf(
            cmsFixture().replace("\"catalogId\": \"\"", "\"catalogId\": 123"),
            cmsFixture().replace("\"catalogId\": \"\", \"namespace\": \"$NAMESPACE\"", "\"catalogId\": \"\", \"namespace\": 123"),
        ).forEach { body ->
            server.enqueue(jsonResponse(body))
            assertProviderFailure()
        }
    }

    @Test
    fun derivedSlug404ReturnsNoCatalogRecord() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val record = provider().fetch(request())

        assertNull(record)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun retriesRateLimitFourTimesThenReturnsTypedFailureOnly() = runTest {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(429)) }

        try {
            provider().fetch(request())
            fail("Expected typed rate-limit exhaustion")
        } catch (_: SteamRateLimitExhaustedException) {
        }

        assertEquals(4, server.requestCount)
    }

    private suspend fun assertProviderFailure(expectedMessage: String? = null) {
        try {
            provider().fetch(request())
            fail("Expected Epic CMS provider failure")
        } catch (error: EpicCmsCatalogException) {
            if (expectedMessage != null) {
                assertTrue(error.message.orEmpty().contains(expectedMessage, ignoreCase = true))
            }
        }
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun provider(): EpicCmsCatalogProvider = EpicCmsCatalogProvider(
        client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .readTimeout(2, TimeUnit.SECONDS)
            .build(),
        apiEndpoint = server.url("/api/"),
        urlPolicy = EpicUrlPolicy(
            cmsHosts = setOf(server.hostName),
            requireHttps = false,
            allowedPorts = setOf(server.port, 443),
        ),
        clock = MetadataClock { 1_700_000_000_000L },
        retryExecutor = SteamHttpRetryExecutor(sleep = {}),
    )

    private fun request(): EpicCmsCatalogRequest = EpicCmsCatalogRequest(
        stableSourceId = STABLE_SOURCE_ID,
        sourceTitle = "Alan Wake 2",
        locale = MetadataLocale("en-US", "US"),
    )

    private fun cmsFixture(
        namespace: String = NAMESPACE,
        rootTitle: String = "Alan Wake 2",
        catalogId: String = "",
        hero: String = HERO,
        releaseDate: String = "Coming Soon",
    ): String =
        """
        {
          "namespace": "$namespace",
          "productName": "$rootTitle",
          "_slug": "alan-wake-2",
          "_locale": "en-US",
          "pages": [
            {
              "type": "productHome",
              "namespace": "$NAMESPACE",
              "productName": "Alan Wake 2",
              "_locale": "en-US",
              "item": {"catalogId": "$catalogId", "namespace": "$NAMESPACE"},
              "offer": {"namespace": "$NAMESPACE", "id": "$OFFER_ID", "hasOffer": true},
              "data": {
                "about": {
                  "shortDescription": "A supernatural survival-horror sequel.",
                  "description": "Saga Anderson and Alan Wake confront a dark story.",
                  "developerAttribution": "Remedy Entertainment",
                  "publisherAttribution": "Epic Games Publishing"
                },
                "hero": {"backgroundImageUrl": "$hero"},
                "carousel": {
                  "items": [
                    {
                      "image": {},
                      "video": {
                        "recipes": "{\"en-US\":[{\"outputs\":[{\"contentType\":\"application/x-mpegURL\",\"key\":\"manifest\",\"url\":\"$HLS\"},{\"contentType\":\"image/jpeg\",\"key\":\"thumbnail\",\"url\":\"$POSTER\"}]}]}"
                      }
                    },
                    {"image": {"src": "$SCREENSHOT"}, "video": {}}
                  ]
                },
                "requirements": {
                  "systems": [
                    {
                      "systemType": "Windows",
                      "details": [
                        {"title": "Windows OS", "minimum": "Windows 10 64-bit", "recommended": "Windows 11 64-bit"},
                        {"title": "Windows Memory", "minimum": "16 GB", "recommended": "16 GB"}
                      ]
                    }
                  ],
                  "languages": ["AUDIO: English, German | TEXT: English, French, German"]
                },
                "meta": {"customReleaseDate": "$releaseDate"}
              }
            },
            {"type": "offer", "namespace": "$NAMESPACE"}
          ]
        }
        """.trimIndent()

    private companion object {
        const val NAMESPACE = "c4763f236d08423eb47b4c3008779c84"
        const val CATALOG_ID = "93f2a8c3547846eda966cb3c152a026e"
        const val OFFER_ID = "a7364ebfa54147f1b90f78a81c8093f7"
        val STABLE_SOURCE_ID = EpicStableSourceId.encode(NAMESPACE, CATALOG_ID)
        const val HERO = "https://cdn2.unrealengine.com/alan-wake-2-hero.jpg"
        const val SCREENSHOT = "https://cdn2.unrealengine.com/alan-wake-2-shot.jpg"
        const val HLS = "https://media-cdn.epicgames.com/video/manifest.m3u8"
        const val POSTER = "https://cdn2.unrealengine.com/alan-wake-2-poster.jpg"
    }
}
