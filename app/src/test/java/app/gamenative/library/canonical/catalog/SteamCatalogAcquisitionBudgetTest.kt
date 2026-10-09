package app.gamenative.library.canonical.catalog

import app.gamenative.library.metadata.MetadataLocale
import app.gamenative.library.metadata.SteamHttpRetryExecutor
import app.gamenative.library.metadata.SteamUrlPolicy
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class SteamCatalogAcquisitionBudgetTest {
    @get:Rule val timeout = Timeout.seconds(10)
    private lateinit var server: MockWebServer
    private val locale = MetadataLocale("en-US", "US")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun storeProviderCapsTenHitsAndReportsTruncationAsIncomplete() = runTest {
        enqueue((1..15).map { app(it, "Game $it") })
        val result = provider().searchResult("Game", locale)
        assertEquals((1..10).toList(), result.hits.map(SteamStoreSearchHit::steamAppId))
        assertFalse("Discarded catalog hits cannot become complete evidence", result.complete)
    }

    @Test
    fun lateExactStoreHitIsRetainedForReviewWithinTheTenHitBound() = runTest {
        enqueue((1..15).map { app(it, if (it == 15) "Fixture Game" else "Other $it") })
        val result = provider().searchResult("Fixture Game", locale)
        assertEquals(10, result.hits.size)
        assertEquals(15, result.hits.first().steamAppId)
        assertFalse(result.complete)
    }

    @Test
    fun exactlyTenDistinctAppsStayCompleteDespiteDuplicatesAndKnownBundles() = runTest {
        val items = (1..10).map { app(it, "Game $it") } + app(1, "Duplicate") +
            """{"type":"bundle","id":99,"name":"Bundle"}"""
        enqueue(items)
        val result = provider().searchResult("Game", locale)
        assertEquals((1..10).toList(), result.hits.map(SteamStoreSearchHit::steamAppId))
        assertTrue(result.complete)
    }

    @Test
    fun malformedAppCannotLeaveTheRemainingHitsMarkedComplete() = runTest {
        enqueue(listOf(app(0, "Invalid app"), app(42, "Fixture Game")))
        val result = provider().searchResult("Fixture Game", locale)
        assertEquals(listOf(42), result.hits.map(SteamStoreSearchHit::steamAppId))
        assertFalse(result.complete)
    }

    @Test
    fun nonzeroTotalWithEmptyItemsIsPartialRatherThanAnAuthoritativeMiss() = runTest {
        server.enqueue(MockResponse().setBody("""{"total":1,"items":[]}"""))
        val result = provider().searchResult("Fixture Game", locale)
        assertTrue(result.hits.isEmpty())
        assertFalse(result.complete)
    }

    @Test
    fun genuineCompleteEmptyStoreResultRemainsAuthoritative() = runTest {
        server.enqueue(MockResponse().setBody("""{"total":0}"""))
        assertEquals(SteamCatalogSearchResult(emptyList(), complete = true), provider().searchResult("Fixture Game", locale))
    }

    @Test
    fun zeroTotalWithAnAppRowCannotBecomeCompleteEvidence() = runTest {
        server.enqueue(MockResponse().setBody("""{"total":0,"items":[${app(42, "Fixture Game")}]}"""))
        val result = provider().searchResult("Fixture Game", locale)
        assertEquals(listOf(42), result.hits.map(SteamStoreSearchHit::steamAppId))
        assertFalse(result.complete)
    }

    @Test
    fun totalSmallerThanTheRawRowCountCannotBecomeCompleteEvidence() = runTest {
        server.enqueue(MockResponse().setBody("""{"total":1,"items":[${app(42, "Fixture Game")},${app(43, "Other Game")}]}"""))
        val result = provider().searchResult("Fixture Game", locale)
        assertEquals(listOf(42, 43), result.hits.map(SteamStoreSearchHit::steamAppId))
        assertFalse(result.complete)
    }

    @Test
    fun missingTotalWithEmptyItemsCannotBecomeAnAuthoritativeMiss() = runTest {
        server.enqueue(MockResponse().setBody("""{"items":[]}"""))
        val result = provider().searchResult("Fixture Game", locale)
        assertTrue(result.hits.isEmpty())
        assertFalse(result.complete)
    }

    @Test
    fun missingTotalWithAnAppRowCannotBecomeCompleteEvidence() = runTest {
        server.enqueue(MockResponse().setBody("""{"items":[${app(42, "Fixture Game")}]}"""))
        val result = provider().searchResult("Fixture Game", locale)
        assertEquals(listOf(42), result.hits.map(SteamStoreSearchHit::steamAppId))
        assertFalse(result.complete)
    }

    @Test
    fun malformedTotalsCannotBecomeCompleteEvidence() = runTest {
        for (total in listOf("null", "{}", "[]", "true", "\"invalid\"", "1.5")) {
            server.enqueue(MockResponse().setBody("""{"total":$total,"items":[${app(42, "Fixture Game")}]}"""))
            val result = provider().searchResult("Fixture Game", locale)
            assertEquals(listOf(42), result.hits.map(SteamStoreSearchHit::steamAppId))
            assertFalse("Malformed total $total cannot establish completeness", result.complete)
        }
    }

    @Test
    fun malformedItemsWithZeroTotalFailRatherThanBecomingAnAuthoritativeMiss() = runTest {
        for (items in listOf("null", "{}", "true", "\"invalid\"")) {
            server.enqueue(MockResponse().setBody("""{"total":0,"items":$items}"""))
            try {
                provider().searchResult("Fixture Game", locale)
                fail("Malformed items $items cannot become authoritative empty evidence")
            } catch (_: SteamCatalogSearchException) {
            }
        }
    }

    @Test
    fun unknownRowTypeCannotLeaveTheRemainingAppsMarkedComplete() = runTest {
        enqueue(listOf(app(42, "Fixture Game"), """{"type":"unknown","id":43,"name":"Other Game"}"""))
        val result = provider().searchResult("Fixture Game", locale)
        assertEquals(listOf(42), result.hits.map(SteamStoreSearchHit::steamAppId))
        assertFalse(result.complete)
    }

    @Test
    fun loadedExactIndexHitSurvivesAggregationButCannotHideTruncation() = runTest {
        val store = source(SteamCatalogSearchResult((1..10).map { hit(it, "Other $it") }, complete = true))
        val loaded = source(SteamCatalogSearchResult(listOf(hit(99, "Fixture Game")), complete = true))
        val result = SteamCatalogSearchCoordinator(store, loaded).searchResult("Fixture Game", locale)
        assertEquals(10, result.hits.size)
        assertEquals(99, result.hits.first().steamAppId)
        assertFalse(result.complete)
    }

    @Test
    fun optionalIndexFallbackIsTenHitBoundedAndStillPartial() = runTest {
        val store = SteamCatalogSearchSource { _, _ -> throw SteamCatalogSearchException() }
        val loaded = source(SteamCatalogSearchResult((1..20).map { hit(it, "Game $it") }, complete = true))
        val result = SteamCatalogSearchCoordinator(store, loaded).searchResult("Game", locale)
        assertEquals((1..10).toList(), result.hits.map(SteamStoreSearchHit::steamAppId))
        assertFalse(result.complete)
    }

    private fun provider() = SteamStoreSearchProvider(
        client = OkHttpClient.Builder().followRedirects(false).build(),
        searchEndpoint = server.url("/api/storesearch/"),
        urlPolicy = SteamUrlPolicy(
            apiHosts = setOf(server.hostName), mediaHosts = SteamUrlPolicy.STEAM_MEDIA_HOSTS,
            requireHttps = false, allowedPorts = setOf(server.port, 443),
        ),
        retryExecutor = SteamHttpRetryExecutor(sleep = {}, nowEpochMs = { 0L }),
    )

    private fun enqueue(items: List<String>) {
        server.enqueue(MockResponse().setBody("""{"total":${items.size},"items":[${items.joinToString()}]}"""))
    }

    private fun app(id: Int, name: String) = """{"type":"app","id":$id,"name":"$name"}"""
    private fun hit(id: Int, name: String) = SteamStoreSearchHit(id, name, null)

    private fun source(result: SteamCatalogSearchResult) = object : SteamCatalogSearchSource {
        override suspend fun search(query: String, locale: MetadataLocale) = result.hits
        override suspend fun searchResult(query: String, locale: MetadataLocale) = result
        override fun searchLoaded(query: String, locale: MetadataLocale) = result.hits
    }
}
