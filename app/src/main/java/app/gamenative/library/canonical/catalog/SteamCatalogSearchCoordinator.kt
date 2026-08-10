package app.gamenative.library.canonical.catalog

import app.gamenative.library.metadata.MetadataLocale
import app.gamenative.library.metadata.SteamRateLimitExhaustedException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
class SteamCatalogSearchCoordinator internal constructor(
    private val storeSource: SteamCatalogSearchSource,
    private val appListSource: SteamCatalogSearchSource,
) : SteamCatalogSearchSource {
    @Inject
    internal constructor(
        storeSearchProvider: SteamStoreSearchProvider,
        appListSearchProvider: SteamAppListSearchProvider,
    ) : this(
        storeSource = storeSearchProvider,
        appListSource = appListSearchProvider,
    )

    override suspend fun search(
        query: String,
        locale: MetadataLocale,
    ): List<SteamStoreSearchHit> = searchResult(query, locale).hits

    override suspend fun searchResult(
        query: String,
        locale: MetadataLocale,
    ): SteamCatalogSearchResult {
        val storeResult = try {
            storeSource.searchResult(query, locale)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SteamRateLimitExhaustedException) {
            throw error
        } catch (storeFailure: Exception) {
            val appListResult = try {
                appListSource.searchResult(query, locale)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                throw storeFailure
            }
            return SteamCatalogSearchResult(
                hits = appListResult.hits.boundedDistinctHits(),
                complete = false,
            )
        }

        val loadedAppListHits = try {
            appListSource.searchLoaded(query, locale)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        return SteamCatalogSearchResult(
            hits = (storeResult.hits + loadedAppListHits).boundedDistinctHits(),
            complete = storeResult.complete,
        )
    }

    override fun requestImmediateRetry() {
        storeSource.requestImmediateRetry()
        appListSource.requestImmediateRetry()
    }

    private fun List<SteamStoreSearchHit>.boundedDistinctHits(): List<SteamStoreSearchHit> =
        distinctBy(SteamStoreSearchHit::steamAppId).take(MAX_RESULTS)

    private companion object {
        const val MAX_RESULTS = 10
    }
}
