package app.gamenative.library.discovery

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.canonical.SteamTagDictionaryEntity
import app.gamenative.db.PluviaDatabase
import app.gamenative.library.metadata.MetadataClock
import app.gamenative.library.metadata.MetadataLocale
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
class SteamTagDictionaryProviderTest {
    private lateinit var database: PluviaDatabase
    private lateinit var server: MockWebServer
    private lateinit var logs: MutableList<String>
    private lateinit var logTree: Timber.Tree

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
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
        database.close()
    }

    @Test
    fun validatedLocaleRequestsLocalizedDictionaryAndBulkUpsertsValidEntries() = runTest {
        server.enqueue(MockResponse().setBody(fixture()))

        val result = provider().refresh(MetadataLocale("en-US", "US"))

        assertEquals(
            SteamTagDictionaryRefreshResult.Updated(setOf(19, 492, 1685)),
            result,
        )
        assertEquals(
            listOf(
                SteamTagDictionaryEntity(19, "en-US", "Action", 1234L),
                SteamTagDictionaryEntity(492, "en-US", "Indie", 1234L),
                SteamTagDictionaryEntity(1685, "en-US", "Co-op", 1234L),
            ),
            database.canonicalFacetDao().getSteamTags("en-US").sortedBy { it.tagId },
        )
        val request = server.takeRequest()
        assertEquals("/tagdata/populartags/english", request.requestUrl?.encodedPath)
        assertEquals(null, request.requestUrl?.query)
    }

    @Test
    fun invalidLocaleOrEndpointIsRejectedBeforeNetwork() = runTest {
        assertInvalidLocale { MetadataLocale("english/../../private", "US") }
        val unsafeProvider = SteamTagDictionaryProvider(
            client = client(),
            dictionaryEndpoint = server.url("/wrong/path"),
            facetDao = database.canonicalFacetDao(),
            allowedHosts = setOf(server.hostName),
            requireHttps = false,
            allowedPorts = setOf(server.port),
            clock = MetadataClock { 1234L },
        )

        assertEquals(
            SteamTagDictionaryRefreshResult.Failed,
            unsafeProvider.refresh(MetadataLocale("en-US", "US")),
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun followsSafeRedirectAndRejectsUntrustedOrCleartextRedirects() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", server.url("/tagdata/populartags/english-redirected")),
        )
        server.enqueue(MockResponse().setBody(fixture()))
        assertTrue(provider().refresh(MetadataLocale("en-US", "US")) is SteamTagDictionaryRefreshResult.Updated)

        listOf(
            "https://store.steampowered.com.evil.example/tagdata/populartags/english",
            "http://store.steampowered.com/tagdata/populartags/english",
        ).forEach { location ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", location))
            assertEquals(
                SteamTagDictionaryRefreshResult.Failed,
                provider().refresh(MetadataLocale("en-US", "US")),
            )
        }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun failedOrMalformedRefreshRetainsPreviousDictionary() = runTest {
        val previous = SteamTagDictionaryEntity(19, "en-US", "Previous label", 1L)
        database.canonicalFacetDao().upsertSteamTags(listOf(previous))
        server.enqueue(MockResponse().setResponseCode(500).setBody("unavailable"))
        server.enqueue(MockResponse().setBody("not-json"))

        assertEquals(
            SteamTagDictionaryRefreshResult.Failed,
            provider().refresh(MetadataLocale("en-US", "US")),
        )
        assertEquals(
            SteamTagDictionaryRefreshResult.Failed,
            provider().refresh(MetadataLocale("en-US", "US")),
        )

        assertEquals(listOf(previous), database.canonicalFacetDao().getSteamTags("en-US"))
    }

    @Test
    fun cancellationEscapesAndCancelsHttpWithoutLeakingPrivateValues() = runTest {
        server.enqueue(
            MockResponse()
                .setBody(fixture())
                .setBodyDelay(1, TimeUnit.MINUTES),
        )
        val job = launch {
            provider().refresh(MetadataLocale("en-US", "US"))
        }
        while (server.requestCount == 0) kotlinx.coroutines.yield()

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        val output = logs.joinToString("\n")
        assertFalse(output.contains("populartags"))
        assertFalse(output.contains(server.hostName))
        assertFalse(output.contains("1685"))
        assertFalse(output.contains("Co-op"))
        assertFalse(output.contains("privateMarker"))
    }

    @Test
    fun providerFailureNeverLogsUrlsTagIdsOrLabels() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("unavailable"))

        assertEquals(
            SteamTagDictionaryRefreshResult.Failed,
            provider().refresh(MetadataLocale("fr-FR", "FR")),
        )

        val output = logs.joinToString("\n")
        assertFalse(output.contains("populartags"))
        assertFalse(output.contains(server.hostName))
        assertFalse(output.contains("492"))
        assertFalse(output.contains("Indie"))
    }

    private fun provider() = SteamTagDictionaryProvider(
        client = client(),
        dictionaryEndpoint = server.url("/tagdata/populartags"),
        facetDao = database.canonicalFacetDao(),
        allowedHosts = setOf(server.hostName),
        requireHttps = false,
        allowedPorts = setOf(server.port),
        clock = MetadataClock { 1234L },
    )

    private fun client() = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private fun fixture(): String = requireNotNull(
        javaClass.getResource("/steam/popular-tags-english.json"),
    ).readText()

    private fun assertInvalidLocale(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid locale input")
        } catch (_: IllegalArgumentException) {
        }
    }
}
