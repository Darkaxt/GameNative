package app.gamenative.library.canonical.catalog

import app.gamenative.library.metadata.MetadataLocale
import app.gamenative.service.steam.SteamWebApiKeySource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SteamAppListSearchProviderTest {
    @Test
    fun firstSearchBootstrapsOnceThenUsesExactLocalTitleIndex() = runTest {
        val remote = FakeRemote(
            entries = listOf(
                SteamAppListEntry(20, "Example: Deluxe", 100),
                SteamAppListEntry(10, "Example Deluxe", 100),
                SteamAppListEntry(30, "Different", 100),
            ),
        )
        val cache = FakeCache()
        val provider = provider(remote = remote, cache = cache)

        val first = provider.search("  Example Deluxe  ", locale())
        val second = provider.search("example deluxe", locale())

        assertEquals(listOf(10, 20), first.map(SteamStoreSearchHit::steamAppId))
        assertEquals(first, second)
        assertEquals(1, remote.calls)
        assertEquals(3, cache.snapshot?.entries?.size)
    }

    @Test
    fun staleCompleteCacheRemainsUsableWhenRefreshFails() = runTest {
        val cache = FakeCache(
            SteamAppListSnapshot(
                refreshedAtEpochSeconds = 1,
                entries = listOf(SteamAppListEntry(10, "Available Offline", 100)),
            ),
        )
        val remote = FakeRemote(failure = SteamCatalogSearchException())

        val results = provider(remote = remote, cache = cache).search("Available Offline", locale())

        assertEquals(listOf(10), results.map(SteamStoreSearchHit::steamAppId))
        assertEquals(1, remote.calls)
    }

    @Test
    fun immediateRetryRefreshesAStaleInMemoryFallback() = runTest {
        val cache = FakeCache(
            SteamAppListSnapshot(
                refreshedAtEpochSeconds = 1,
                entries = listOf(SteamAppListEntry(10, "Stale Catalog", 100)),
            ),
        )
        val remote = RetryingRemote()
        val provider = provider(remote = remote, cache = cache)

        val stale = provider.search("Stale Catalog", locale())
        remote.available = true
        provider.requestImmediateRetry()
        val refreshed = provider.search("Fresh Catalog", locale())

        assertEquals(listOf(10), stale.map(SteamStoreSearchHit::steamAppId))
        assertEquals(listOf(20), refreshed.map(SteamStoreSearchHit::steamAppId))
        assertEquals(2, remote.calls)
    }

    @Test
    fun missingCacheAndCredentialFailsUnavailableInsteadOfRecordingUnmatched() = runTest {
        val provider = provider(key = null)

        try {
            provider.search("Example", locale())
            fail("Expected missing catalog to be unavailable")
        } catch (error: SteamCatalogSearchException) {
            assertEquals("Steam catalog search unavailable", error.message)
        }
    }

    @Test
    fun returnsAtMostTenStableExactMatches() = runTest {
        val entries = (20 downTo 1).map { id -> SteamAppListEntry(id, "Same Name", 100) }

        val results = provider(remote = FakeRemote(entries)).search("Same Name", locale())

        assertEquals((1..10).toList(), results.map(SteamStoreSearchHit::steamAppId))
    }

    private fun provider(
        remote: SteamAppListRemoteSource = FakeRemote(emptyList()),
        cache: FakeCache = FakeCache(),
        key: String? = API_KEY,
    ) = SteamAppListSearchProvider(
        remoteSource = remote,
        keySource = SteamWebApiKeySource { key },
        cache = cache,
        nowEpochSeconds = { NOW_EPOCH_SECONDS },
    )

    private fun locale() = MetadataLocale("en-US", "US")

    private class FakeRemote(
        private val entries: List<SteamAppListEntry> = emptyList(),
        private val failure: Exception? = null,
    ) : SteamAppListRemoteSource {
        var calls = 0
            private set

        override suspend fun fetchAll(apiKey: String): List<SteamAppListEntry> {
            calls += 1
            failure?.let { throw it }
            return entries
        }
    }

    private class RetryingRemote : SteamAppListRemoteSource {
        var available = false
        var calls = 0
            private set

        override suspend fun fetchAll(apiKey: String): List<SteamAppListEntry> {
            calls += 1
            if (!available) throw SteamCatalogSearchException()
            return listOf(SteamAppListEntry(20, "Fresh Catalog", 200))
        }
    }

    private class FakeCache(
        initial: SteamAppListSnapshot? = null,
    ) : SteamAppListCache {
        var snapshot: SteamAppListSnapshot? = initial
            private set

        override suspend fun load(): SteamAppListSnapshot? = snapshot

        override suspend fun write(snapshot: SteamAppListSnapshot) {
            this.snapshot = snapshot
        }
    }

    private companion object {
        const val API_KEY = "0123456789abcdef0123456789ABCDEF"
        const val NOW_EPOCH_SECONDS = 1_800_000_000L
    }
}
