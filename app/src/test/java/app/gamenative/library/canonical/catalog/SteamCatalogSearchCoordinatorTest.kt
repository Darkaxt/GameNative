package app.gamenative.library.canonical.catalog

import app.gamenative.library.metadata.MetadataLocale
import app.gamenative.library.metadata.SteamRateLimitExhaustedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SteamCatalogSearchCoordinatorTest {
    private val locale = MetadataLocale("en-US", "US")

    @Test
    fun completeStoreResultsRemainAuthoritativeWhenOptionalIndexFails() = runTest {
        val coordinator = coordinator(
            store = source(SteamCatalogSearchResult(listOf(hit(42)), complete = true)),
            appList = SteamCatalogSearchSource { _, _ -> error("AppList unavailable") },
        )

        val result = coordinator.searchResult("Example", locale)

        assertEquals(SteamCatalogSearchResult(listOf(hit(42)), complete = true), result)
    }

    @Test
    fun completeStoreResultDoesNotWaitForOptionalIndexRefresh() = runTest {
        var appListCalls = 0
        val coordinator = coordinator(
            store = source(SteamCatalogSearchResult(listOf(hit(42)), complete = true)),
            appList = SteamCatalogSearchSource { _, _ ->
                appListCalls += 1
                awaitCancellation()
            },
        )

        val result = withTimeout(1) {
            coordinator.searchResult("Example", locale)
        }

        assertEquals(SteamCatalogSearchResult(listOf(hit(42)), complete = true), result)
        assertEquals(0, appListCalls)
    }

    @Test
    fun optionalAppListAddsExactCandidatesWithoutReplacingStoreOrder() = runTest {
        val coordinator = coordinator(
            store = source(SteamCatalogSearchResult(listOf(hit(42)), complete = true)),
            appList = source(
                SteamCatalogSearchResult(listOf(hit(84), hit(42)), complete = true),
            ),
        )

        val result = coordinator.searchResult("Example", locale)

        assertEquals(listOf(42, 84), result.hits.map(SteamStoreSearchHit::steamAppId))
        assertEquals(true, result.complete)
    }

    @Test
    fun optionalIndexFallbackAfterStoreFailureIsExplicitlyPartial() = runTest {
        val coordinator = coordinator(
            store = SteamCatalogSearchSource { _, _ -> throw SteamCatalogSearchException() },
            appList = source(SteamCatalogSearchResult(listOf(hit(84)), complete = true)),
        )

        val result = coordinator.searchResult("Example", locale)

        assertEquals(SteamCatalogSearchResult(listOf(hit(84)), complete = false), result)
    }

    @Test
    fun storeRateLimitExhaustionCannotBeMaskedByOptionalIndex() {
        var appListCalls = 0
        val coordinator = coordinator(
            store = SteamCatalogSearchSource { _, _ -> throw SteamRateLimitExhaustedException() },
            appList = SteamCatalogSearchSource { _, _ ->
                appListCalls += 1
                listOf(hit(84))
            },
        )

        assertThrows(SteamRateLimitExhaustedException::class.java) {
            runTest { coordinator.searchResult("Example", locale) }
        }
        assertEquals(0, appListCalls)
    }

    @Test
    fun cancellationFromEitherSourcePropagates() {
        listOf(
            coordinator(
                store = SteamCatalogSearchSource { _, _ -> throw CancellationException() },
                appList = source(SteamCatalogSearchResult(emptyList(), true)),
            ),
            coordinator(
                store = source(SteamCatalogSearchResult(emptyList(), true)),
                appList = object : SteamCatalogSearchSource {
                    override suspend fun search(query: String, locale: MetadataLocale) = emptyList<SteamStoreSearchHit>()

                    override fun searchLoaded(query: String, locale: MetadataLocale): List<SteamStoreSearchHit> =
                        throw CancellationException()
                },
            ),
        ).forEach { coordinator ->
            assertThrows(CancellationException::class.java) {
                runTest { coordinator.searchResult("Example", locale) }
            }
        }
    }

    private fun coordinator(
        store: SteamCatalogSearchSource,
        appList: SteamCatalogSearchSource,
    ) = SteamCatalogSearchCoordinator(store, appList)

    private fun source(result: SteamCatalogSearchResult) = object : SteamCatalogSearchSource {
        override suspend fun search(query: String, locale: MetadataLocale) = result.hits

        override suspend fun searchResult(query: String, locale: MetadataLocale) = result

        override fun searchLoaded(query: String, locale: MetadataLocale) = result.hits
    }

    private fun hit(steamAppId: Int) = SteamStoreSearchHit(
        steamAppId = steamAppId,
        title = "Example $steamAppId",
        headerImageUrl = null,
    )
}
