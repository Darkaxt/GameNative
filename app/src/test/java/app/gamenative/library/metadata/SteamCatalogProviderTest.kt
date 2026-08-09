package app.gamenative.library.metadata

import app.gamenative.data.canonical.CanonicalAppType

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import timber.log.Timber

@RunWith(RobolectricTestRunner::class)
class SteamCatalogProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var logs: MutableList<String>
    private lateinit var logTree: Timber.Tree

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        logs = mutableListOf()
        logTree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logs += listOfNotNull(tag, message, t?.message).joinToString(" ")
            }
        }
        Timber.plant(logTree)
    }

    @After
    fun tearDown() {
        Timber.uproot(logTree)
        server.shutdown()
    }

    @Test
    fun requestsOneTrustedIdWithValidatedLocaleAndCountry() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(successFixture()))
        val provider = provider()

        val metadata = provider.fetch(TRUSTED_APP_ID, MetadataLocale("en-US", "us"))

        assertNotNull(metadata)
        val request = server.takeRequest()
        assertEquals("/api/appdetails", request.requestUrl?.encodedPath)
        assertEquals(TRUSTED_APP_ID.toString(), request.requestUrl?.queryParameter("appids"))
        assertEquals("english", request.requestUrl?.queryParameter("l"))
        assertEquals("US", request.requestUrl?.queryParameter("cc"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun rejectsInvalidLocaleOrCountryBeforeNetwork() {
        assertInvalidLocale { MetadataLocale("not a locale", "US") }
        assertInvalidLocale { MetadataLocale("en-US", "USA") }
        assertInvalidLocale { MetadataLocale("en-US", "U1") }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun rejectsRedirectToUntrustedOrCleartextHost() = runTest {
        listOf(
            "https://store.steampowered.com.evil.example/api/appdetails",
            "http://store.steampowered.com/api/appdetails",
        ).forEach { location ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", location),
            )
            try {
                provider().fetch(TRUSTED_APP_ID, MetadataLocale("en-US", "US"))
                fail("Expected unsafe redirect to be rejected")
            } catch (_: SteamCatalogException) {
                // Fixed provider failure with no URL or identifier in its message.
            }
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun rejectsResponseEnvelopeForDifferentSteamIdentity() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "999999": {
                    "success": true,
                    "data": {"name": "Wrong game"}
                  }
                }
                """.trimIndent(),
            ),
        )

        try {
            provider().fetch(TRUSTED_APP_ID, MetadataLocale("en-US", "US"))
            fail("Expected mismatched response identity to be rejected")
        } catch (_: SteamCatalogException) {
        }
    }

    @Test
    fun sanitizesTextAndParsesUsefulFieldsIndependently() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(successFixture()))

        val metadata = requireNotNull(
            provider().fetch(TRUSTED_APP_ID, MetadataLocale("en-US", "US")),
        )

        assertEquals("Hello & welcome", metadata.shortDescription)
        assertEquals("About Steam-first details.", metadata.about)
        assertFalse(metadata.shortDescription.orEmpty().contains('<'))
        assertFalse(metadata.shortDescription.orEmpty().contains("privateMarker"))
        assertEquals(listOf("Fixture Studio"), metadata.developers)
        assertEquals(setOf(GamePlatform.WINDOWS, GamePlatform.LINUX), metadata.platforms)
        assertEquals(listOf("English", "French", "German"), metadata.languages)
        assertEquals(12, metadata.achievementCount)
        assertEquals(2, metadata.dlcCount)
        assertEquals(2, metadata.features.size)
        assertEquals(
            listOf(MetadataFacet(1, "Action"), MetadataFacet(2, "Strategy")),
            metadata.genres,
        )
        assertNotNull(metadata.requirements)
        assertEquals(1, metadata.screenshots.size)
        assertEquals(1, metadata.movies.size)
    }

    @Test
    fun parsesCurrentHlsMovieStreamsBeforeScreenshots() = runTest {
        val hlsUrl = "https://video.akamai.steamstatic.com/movie/master.m3u8"
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "$TRUSTED_APP_ID": {
                    "success": true,
                    "data": {
                      "name": "Fixture Game",
                      "movies": [
                        {
                          "name": "Trailer",
                          "thumbnail": "https://shared.akamai.steamstatic.com/poster.jpg",
                          "hls_h264": "$hlsUrl",
                          "dash_h264": "https://video.akamai.steamstatic.com/movie/manifest.mpd"
                        }
                      ],
                      "screenshots": [
                        {
                          "path_full": "https://shared.akamai.steamstatic.com/screenshot.jpg"
                        }
                      ]
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

        val metadata = requireNotNull(
            provider().fetch(TRUSTED_APP_ID, MetadataLocale("en-US", "US")),
        )

        assertEquals(hlsUrl, metadata.movies.single().streamUrl)
        assertEquals(1, metadata.screenshots.size)
    }

    @Test
    fun malformedOptionalFieldsDoNotDiscardUsableMetadata() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "$TRUSTED_APP_ID": {
                    "success": true,
                    "data": {
                      "name": "Partial Game",
                      "short_description": "Still useful",
                      "developers": ["Good Studio", 7, null],
                      "screenshots": [null, {"path_full": 4}, {"path_full": "https://evil.example/x.jpg"}],
                      "platforms": {"windows": true, "linux": "yes"},
                      "achievements": {"total": "many"},
                      "dlc": "broken",
                      "categories": [{"id": "bad", "description": "Co-op"}]
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

        val metadata = provider().fetch(TRUSTED_APP_ID, MetadataLocale("en-US", "US"))

        assertEquals("Partial Game", metadata?.title)
        assertEquals("Still useful", metadata?.shortDescription)
        assertEquals(listOf("Good Studio"), metadata?.developers)
        assertEquals(setOf(GamePlatform.WINDOWS), metadata?.platforms)
        assertTrue(metadata?.screenshots?.isEmpty() == true)
        assertNull(metadata?.achievementCount)
        assertNull(metadata?.dlcCount)
        assertEquals("Co-op", metadata?.features?.single()?.label)
    }

    @Test
    fun fetchRecordIncludesValidatedIdentityTypeYearAndMetadata() = runTest {
        server.enqueue(
            MockResponse().setBody(recordFixture(type = "game", releaseDate = "31 Jul, 2026")),
        )

        val record = requireNotNull(
            provider().fetchRecord(TRUSTED_APP_ID, MetadataLocale("en-US", "US")),
        )

        assertEquals(TRUSTED_APP_ID, record.steamAppId)
        assertEquals(CanonicalAppType.GAME, record.appType)
        assertEquals(2026, record.releaseYear)
        assertEquals("Fixture Game", record.metadata.title)
    }

    @Test
    fun mapsSupportedSteamTypesWithoutGuessingUnknownValues() = runTest {
        val expectedTypes = listOf(
            "game" to CanonicalAppType.GAME,
            "application" to CanonicalAppType.APPLICATION,
            "tool" to CanonicalAppType.TOOL,
            "demo" to CanonicalAppType.DEMO,
            "dlc" to CanonicalAppType.DLC,
            "music" to CanonicalAppType.SOUNDTRACK,
            "video" to CanonicalAppType.UNKNOWN,
        )
        expectedTypes.forEach { (steamType, _) ->
            server.enqueue(MockResponse().setBody(recordFixture(steamType, "Coming soon")))
        }

        expectedTypes.forEach { (_, expectedType) ->
            val record = requireNotNull(
                provider().fetchRecord(TRUSTED_APP_ID, MetadataLocale("en-US", "US")),
            )
            assertEquals(expectedType, record.appType)
            assertNull(record.releaseYear)
        }
    }

    @Test
    fun existingFetchReturnsRecordMetadata() = runTest {
        server.enqueue(MockResponse().setBody(recordFixture("game", "31 Jul, 2026")))
        server.enqueue(MockResponse().setBody(recordFixture("game", "31 Jul, 2026")))
        val provider = provider()

        val metadata = provider.fetch(TRUSTED_APP_ID, MetadataLocale("en-US", "US"))
        val record = provider.fetchRecord(TRUSTED_APP_ID, MetadataLocale("en-US", "US"))

        assertEquals(record?.metadata, metadata)
    }

    @Test
    fun releaseYearMustBeSupportedAndUnambiguous() = runTest {
        listOf("1969", "Released 2020, remastered 2022", "9999").forEach { date ->
            server.enqueue(MockResponse().setBody(recordFixture("game", date)))
        }

        repeat(3) {
            val record = requireNotNull(
                provider().fetchRecord(TRUSTED_APP_ID, MetadataLocale("en-US", "US")),
            )
            assertNull(record.releaseYear)
        }
    }

    @Test
    fun rejectsOversizedAppDetailsResponse() = runTest {
        server.enqueue(
            MockResponse().setBody(
                successFixture() + " ".repeat(MAX_APP_DETAILS_RESPONSE_BYTES + 1),
            ),
        )

        try {
            provider().fetch(TRUSTED_APP_ID, MetadataLocale("en-US", "US"))
            fail("Expected oversized app-details response to be rejected")
        } catch (_: SteamCatalogException) {
        }
    }

    @Test
    fun cancellationEscapesAndCancelsTheHttpCall() = runTest {
        server.enqueue(
            MockResponse()
                .setBody(successFixture())
                .setBodyDelay(1, TimeUnit.MINUTES),
        )
        val job = launch {
            provider().fetch(TRUSTED_APP_ID, MetadataLocale("en-US", "US"))
        }
        while (server.requestCount == 0) {
            kotlinx.coroutines.yield()
        }

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
    }

    @Test
    fun neverLogsOrDiagnosesRequestUrlOrAppId() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("unavailable"))

        try {
            provider().fetch(TRUSTED_APP_ID, MetadataLocale("en-US", "US"))
        } catch (_: SteamCatalogException) {
        }

        val output = logs.joinToString("\n")
        assertFalse(output.contains(TRUSTED_APP_ID.toString()))
        assertFalse(output.contains("appdetails"))
        assertFalse(output.contains(server.hostName))
    }

    private fun provider(): SteamCatalogProvider = SteamCatalogProvider(
        client = OkHttpClient.Builder().followRedirects(false).build(),
        apiEndpoint = server.url("/api/appdetails"),
        urlPolicy = SteamUrlPolicy(
            apiHosts = setOf(server.hostName),
            mediaHosts = SteamUrlPolicy.STEAM_MEDIA_HOSTS,
            requireHttps = false,
            allowedPorts = setOf(server.port, 443),
        ),
        clock = MetadataClock { 1234L },
    )

    private fun successFixture(): String = requireNotNull(
        javaClass.getResource("/steam/appdetails-success.json"),
    ).readText()

    private fun recordFixture(type: String, releaseDate: String): String = successFixture()
        .replaceFirst(
            "\"data\": {",
            "\"data\": {\n      \"type\": \"$type\",",
        )
        .replace("\"date\": \"31 Jul, 2026\"", "\"date\": \"$releaseDate\"")

    private fun assertInvalidLocale(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid locale input to be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    private companion object {
        const val TRUSTED_APP_ID = 424242
        const val MAX_APP_DETAILS_RESPONSE_BYTES = 4 * 1024 * 1024
    }
}
