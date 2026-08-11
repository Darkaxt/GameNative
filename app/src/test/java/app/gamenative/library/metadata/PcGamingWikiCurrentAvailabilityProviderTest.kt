package app.gamenative.library.metadata

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PcGamingWikiCurrentAvailabilityProviderTest {
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
    fun alanWakeTwoUsesExactSafeBulkRequestsAndReturnsFixedEvidence() = runTest {
        server.enqueue(revisionResponse(revision = 1_783_673L))
        server.enqueue(cargoResponse(alanWakeRow()))

        val result = provider().check(
            request(
                title = "Alan Wake 2",
                releaseYear = 2023,
                developer = "Remedy Entertainment",
            ),
        )

        assertEquals(
            PcGamingWikiCurrentAvailabilityResult.Confirmed(
                PcGamingWikiCurrentAvailabilityEvidence(
                    sourceRevision = 1_783_673L,
                    futureSteamAvailability = false,
                ),
            ),
            result,
        )
        assertRevisionRequest(server.takeRequest())
        assertCargoRequest(server.takeRequest())
    }

    @Test
    fun rawmenRetainsFutureSteamAsCurrentOnlyContext() = runTest {
        server.enqueue(revisionResponse())
        server.enqueue(
            cargoResponse(
                row(
                    page = "RAWMEN",
                    developers = "Company:ANIMAL",
                    publishers = "Company:tinyBuild",
                    released = "2024-07-23",
                    futureStores = "Steam",
                ),
            ),
        )

        val result = provider().check(request("RAWMEN", 2024, "ANIMAL"))

        val evidence = (result as PcGamingWikiCurrentAvailabilityResult.Confirmed).evidence
        assertEquals(PcGamingWikiAvailabilityLabel.PCGW_CURRENT_EGS_ACCOUNT_REQUIRED, evidence.label)
        assertTrue(evidence.futureSteamAvailability)
    }

    @Test
    fun noStrongIdentityMatchIsNotConfirmed() = runTest {
        server.enqueue(revisionResponse())
        server.enqueue(cargoResponse(alanWakeRow()))

        val result = provider().check(request("Control 2", 2023, "Remedy Entertainment"))

        assertEquals(PcGamingWikiCurrentAvailabilityResult.NotConfirmed, result)
    }

    @Test
    fun conflictingReleaseYearDoesNotUseDeveloperAlone() = runTest {
        server.enqueue(revisionResponse())
        server.enqueue(cargoResponse(alanWakeRow()))

        val result = provider().check(request("Alan Wake 2", 2010, "Remedy Entertainment"))

        assertEquals(PcGamingWikiCurrentAvailabilityResult.NotConfirmed, result)
    }

    @Test
    fun multiplePlausibleRowsAreInconclusive() = runTest {
        server.enqueue(revisionResponse())
        server.enqueue(cargoResponse(alanWakeRow(), alanWakeRow()))

        val result = provider().check(request("Alan Wake 2", 2023, "Remedy Entertainment"))

        assertEquals(PcGamingWikiCurrentAvailabilityResult.NotConfirmed, result)
    }

    @Test
    fun missingCorroborationIsNotConfirmed() = runTest {
        server.enqueue(revisionResponse())
        server.enqueue(cargoResponse(alanWakeRow()))

        val result = provider().check(request("Alan Wake 2", null, null))

        assertEquals(PcGamingWikiCurrentAvailabilityResult.NotConfirmed, result)
    }

    @Test
    fun revisionContinuationOrSchemaFailureIsUnavailable() = runTest {
        val failures = listOf(
            """{"continue":{"rvcontinue":"next"},"query":{"pages":[]}}""",
            """{"batchcomplete":true,"query":{"pages":[]}}""",
            """{"batchcomplete":true,"query":{"pages":[{"pageid":173493,"title":"Wrong page","revisions":[{"revid":1783673}]}]}}""",
            """{"batchcomplete":true,"query":{"pages":[{"pageid":173493,"title":"$LIST_PAGE","revisions":[{"revid":0}]}]}}""",
        )

        failures.forEach { body ->
            server.enqueue(jsonResponse(body))
            assertEquals(
                PcGamingWikiCurrentAvailabilityResult.Unavailable,
                provider().check(request("Alan Wake 2", 2023, "Remedy Entertainment")),
            )
        }
    }

    @Test
    fun cargoContinuationSchemaOrBoundsFailureIsUnavailable() = runTest {
        val oversizedRows = List(51) { index -> row(page = "Game $index") }.toTypedArray()
        val failures = listOf(
            """{"continue":{"offset":50},"cargoquery":[]}""",
            """{"batchcomplete":true}""",
            cargoBody(*oversizedRows),
            cargoBody(row(page = "x".repeat(257))),
            """{"cargoquery":[{"title":{"Page":17}}]}""",
        )

        failures.forEach { body ->
            server.enqueue(revisionResponse())
            server.enqueue(jsonResponse(body))
            assertEquals(
                PcGamingWikiCurrentAvailabilityResult.Unavailable,
                provider().check(request("Alan Wake 2", 2023, "Remedy Entertainment")),
            )
        }
    }

    @Test
    fun rejectsRedirectWrongContentTypeOversizedBodyAndForbiddenResponse() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/w/api.php"))
        assertEquals(
            PcGamingWikiCurrentAvailabilityResult.Unavailable,
            provider().check(request("Alan Wake 2", 2023, "Remedy Entertainment")),
        )

        server.enqueue(revisionResponse())
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(cargoBody(alanWakeRow())))
        assertEquals(
            PcGamingWikiCurrentAvailabilityResult.Unavailable,
            provider().check(request("Alan Wake 2", 2023, "Remedy Entertainment")),
        )

        server.enqueue(revisionResponse())
        server.enqueue(jsonResponse("x".repeat(524_289)))
        assertEquals(
            PcGamingWikiCurrentAvailabilityResult.Unavailable,
            provider().check(request("Alan Wake 2", 2023, "Remedy Entertainment")),
        )

        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(
            PcGamingWikiCurrentAvailabilityResult.Unavailable,
            provider().check(request("Alan Wake 2", 2023, "Remedy Entertainment")),
        )
    }

    @Test
    fun retries429FourTimesThenReturnsUnavailable() = runTest {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(429)) }

        val result = provider().check(request("Alan Wake 2", 2023, "Remedy Entertainment"))

        assertEquals(PcGamingWikiCurrentAvailabilityResult.Unavailable, result)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun concurrentChecksSerializeOneBulkRefresh() = runTest {
        server.enqueue(revisionResponse().setBodyDelay(100, TimeUnit.MILLISECONDS))
        server.enqueue(cargoResponse(alanWakeRow(), rawmenRow()))
        val provider = provider()

        val alan = async(Dispatchers.IO) {
            provider.check(request("Alan Wake 2", 2023, "Remedy Entertainment"))
        }
        val rawmen = async(Dispatchers.IO) {
            provider.check(request("RAWMEN", 2024, "ANIMAL"))
        }

        assertTrue(alan.await() is PcGamingWikiCurrentAvailabilityResult.Confirmed)
        assertTrue(rawmen.await() is PcGamingWikiCurrentAvailabilityResult.Confirmed)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun oneBulkSnapshotExpiresAtTtlAndRefreshesOnce() = runTest {
        var now = 1_700_000_000_000L
        server.enqueue(revisionResponse(revision = 100))
        server.enqueue(cargoResponse(alanWakeRow(), rawmenRow()))
        val provider = provider(nowEpochMs = { now })

        assertTrue(
            provider.check(request("Alan Wake 2", 2023, "Remedy Entertainment")) is
                PcGamingWikiCurrentAvailabilityResult.Confirmed,
        )
        assertTrue(
            provider.check(request("RAWMEN", 2024, "ANIMAL")) is
                PcGamingWikiCurrentAvailabilityResult.Confirmed,
        )
        assertEquals(2, server.requestCount)

        now += TimeUnit.HOURS.toMillis(12) + 1
        server.enqueue(revisionResponse(revision = 101))
        server.enqueue(cargoResponse(rawmenRow()))

        val refreshed = provider.check(request("Alan Wake 2", 2023, "Remedy Entertainment"))

        assertEquals(PcGamingWikiCurrentAvailabilityResult.NotConfirmed, refreshed)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun failedRefreshIsNotCached() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val provider = provider()

        assertEquals(
            PcGamingWikiCurrentAvailabilityResult.Unavailable,
            provider.check(request("Alan Wake 2", 2023, "Remedy Entertainment")),
        )

        server.enqueue(revisionResponse())
        server.enqueue(cargoResponse(alanWakeRow()))

        assertTrue(
            provider.check(request("Alan Wake 2", 2023, "Remedy Entertainment")) is
                PcGamingWikiCurrentAvailabilityResult.Confirmed,
        )
        assertEquals(3, server.requestCount)
    }

    @Test
    fun staleSnapshotRefreshFailureIsInconclusiveAndRetried() = runTest {
        var now = 1_700_000_000_000L
        server.enqueue(revisionResponse())
        server.enqueue(cargoResponse(alanWakeRow()))
        val provider = provider(nowEpochMs = { now })
        assertTrue(
            provider.check(request("Alan Wake 2", 2023, "Remedy Entertainment")) is
                PcGamingWikiCurrentAvailabilityResult.Confirmed,
        )
        now += TimeUnit.HOURS.toMillis(12) + 1
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(revisionResponse(revision = 1_783_674L))
        server.enqueue(cargoResponse(alanWakeRow()))

        assertEquals(
            PcGamingWikiCurrentAvailabilityResult.Unavailable,
            provider.check(request("Alan Wake 2", 2023, "Remedy Entertainment")),
        )
        assertTrue(
            provider.check(request("Alan Wake 2", 2023, "Remedy Entertainment")) is
                PcGamingWikiCurrentAvailabilityResult.Confirmed,
        )
        assertEquals(5, server.requestCount)
    }

    @Test
    fun cancellationClosesStalledRevisionBody() = runTest {
        server.enqueue(revisionResponse().setBodyDelay(30, TimeUnit.SECONDS))
        val job = launch(Dispatchers.IO) {
            provider().check(request("Alan Wake 2", 2023, "Remedy Entertainment"))
        }
        requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        Thread.sleep(100)

        withContext(Dispatchers.Default) {
            withTimeout(1_000) {
                job.cancelAndJoin()
            }
        }
    }

    private fun assertRevisionRequest(request: RecordedRequest) {
        assertEquals("GET", request.method)
        assertEquals("/w/api.php", request.requestUrl?.encodedPath)
        assertEquals(
            mapOf(
                "action" to "query",
                "format" to "json",
                "formatversion" to "2",
                "prop" to "revisions",
                "titles" to LIST_PAGE,
                "rvprop" to "ids",
                "rvlimit" to "1",
            ),
            request.requestUrl?.queryParameterNames?.associateWith { name ->
                request.requestUrl?.queryParameter(name).orEmpty()
            },
        )
        assertSafeHeaders(request)
    }

    private fun assertCargoRequest(request: RecordedRequest) {
        assertEquals("POST", request.method)
        assertEquals("/w/api.php", request.requestUrl?.encodedPath)
        assertNull(request.requestUrl?.query)
        assertEquals(
            mapOf(
                "action" to "cargoquery",
                "format" to "json",
                "tables" to "Infobox_game,Availability",
                "join_on" to "Infobox_game._pageID=Availability._pageID",
                "limit" to "50",
                "fields" to EXPECTED_FIELDS,
                "where" to expectedWhere(),
            ),
            request.formParameters(),
        )
        assertFalse(request.body.clone().readUtf8().contains("Alan Wake 2"))
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("application/x-www-form-urlencoded"))
        assertSafeHeaders(request)
    }

    private fun assertSafeHeaders(request: RecordedRequest) {
        assertEquals(USER_AGENT, request.getHeader("User-Agent"))
        assertEquals("no-store", request.getHeader("Cache-Control"))
        assertEquals("application/json", request.getHeader("Accept"))
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("Cookie"))
    }

    private fun RecordedRequest.formParameters(): Map<String, String> = body.clone().readUtf8()
        .split('&')
        .associate { entry ->
            val (name, value) = entry.split('=', limit = 2)
            decode(name) to decode(value)
        }

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun provider(
        nowEpochMs: () -> Long = { 1_700_000_000_000L },
    ): PcGamingWikiCurrentAvailabilityProvider = PcGamingWikiCurrentAvailabilityProvider(
        client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .readTimeout(2, TimeUnit.SECONDS)
            .build(),
        apiEndpoint = server.url("/w/api.php"),
        urlPolicy = PcGamingWikiUrlPolicy(
            allowedHost = server.hostName,
            requireHttps = false,
            allowedPort = server.port,
        ),
        clock = MetadataClock(nowEpochMs),
        retryExecutor = SteamHttpRetryExecutor(sleep = {}),
    )

    private fun request(
        title: String,
        releaseYear: Int?,
        developer: String?,
        publisher: String? = null,
    ) = PcGamingWikiCurrentAvailabilityRequest(
        sourceTitle = title,
        sourceReleaseYear = releaseYear,
        sourceDeveloper = developer,
        sourcePublisher = publisher,
    )

    private fun revisionResponse(revision: Long = 1_783_673L): MockResponse = jsonResponse(
        """
        {
          "batchcomplete": true,
          "query": {
            "pages": [
              {
                "pageid": 173493,
                "ns": 0,
                "title": "$LIST_PAGE",
                "revisions": [{"revid": $revision, "parentid": 1780000}]
              }
            ]
          }
        }
        """.trimIndent(),
    )

    private fun cargoResponse(vararg rows: String): MockResponse = jsonResponse(cargoBody(*rows))

    private fun cargoBody(vararg rows: String): String =
        """{"cargoquery":[${rows.joinToString(",")}]}"""

    private fun alanWakeRow(): String = row(
        page = "Alan Wake II",
        series = "Alan Wake",
        developers = "Company:Remedy Entertainment",
        publishers = "Company:Epic Games Publishing",
        released = "2023-10-27",
    )

    private fun rawmenRow(): String = row(
        page = "RAWMEN",
        developers = "Company:ANIMAL",
        publishers = "Company:tinyBuild",
        released = "2024-07-23",
        futureStores = "Steam",
    )

    private fun row(
        page: String,
        series: String? = null,
        developers: String? = null,
        publishers: String? = null,
        released: String? = "2020-01-01",
        availableOn: String? = "Windows",
        futureStores: String? = null,
    ): String {
        fun value(raw: String?): String = raw?.let { "\"${escapeJson(it)}\"" } ?: "null"
        return """
            {"title":{
              "Page":${value(page)},
              "Series":${value(series)},
              "Developers":${value(developers)},
              "Publishers":${value(publishers)},
              "Released":${value(released)},
              "AvailableOn":${value(availableOn)},
              "FutureStores":${value(futureStores)}
            }}
        """.trimIndent()
    }

    private fun escapeJson(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun expectedWhere(): String = buildString {
        append("Availability.Available_from HOLDS 'Epic Games Store'")
        EXPECTED_ALTERNATIVE_STORES.forEach { (store, drmField) ->
            append("\nAND NOT (Availability.Available_from HOLDS '")
            append(store)
            append("' AND Availability.")
            append(drmField)
            append(" HOLDS NOT 'Epic Games Launcher')")
        }
    }

    private companion object {
        const val LIST_PAGE = "List of games exclusive to Epic Games Store"
        const val USER_AGENT = "GameNative/1.0 (https://github.com/Darkaxt/GameNative)"
        const val EXPECTED_FIELDS = "Infobox_game._pageName=Page," +
            "Infobox_game.Series=Series," +
            "Infobox_game.Developers=Developers," +
            "Infobox_game.Publishers=Publishers," +
            "Infobox_game.Released=Released," +
            "Infobox_game.Available_on=AvailableOn," +
            "Availability.Available_from_future=FutureStores"
        val EXPECTED_ALTERNATIVE_STORES = listOf(
            "Amazon DE" to "Amazon_DE_DRM",
            "Amazon ES" to "Amazon_ES_DRM",
            "Amazon FR" to "Amazon_FR_DRM",
            "Amazon IT" to "Amazon_IT_DRM",
            "Amazon JP" to "Amazon_JP_DRM",
            "Amazon UK" to "Amazon_UK_DRM",
            "Amazon US" to "Amazon_US_DRM",
            "Battle.net" to "Battlenet_DRM",
            "Developer Website" to "Developer_website_DRM",
            "EA app" to "EA_app_DRM",
            "GamersGate" to "GamersGate_DRM",
            "Gamesplanet" to "Gamesplanet_DRM",
            "GOG.com" to "GOGcom_DRM",
            "Green Man Gaming" to "Green_Man_Gaming_DRM",
            "Humble Store" to "Humble_Store_DRM",
            "itch.io" to "itchio_DRM",
            "Mac App Store" to "Mac_App_Store_DRM",
            "Meta Store" to "Meta_Store_DRM",
            "Microsoft Store" to "Microsoft_Store_DRM",
            "Official website" to "Official_website_DRM",
            "Publisher website" to "Publisher_website_DRM",
            "Retail" to "Retail_DRM",
            "Steam" to "Steam_DRM",
            "Ubisoft Store" to "Ubisoft_Store_DRM",
            "Viveport" to "Viveport_DRM",
            "Zoom Platform" to "Zoom_Platform_DRM",
        )
    }
}
