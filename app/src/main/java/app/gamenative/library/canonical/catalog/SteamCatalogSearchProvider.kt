package app.gamenative.library.canonical.catalog

import app.gamenative.library.metadata.MetadataLocale
import java.io.IOException

fun interface SteamCatalogSearchSource {
    suspend fun search(
        query: String,
        locale: MetadataLocale,
    ): List<SteamStoreSearchHit>

    fun requestImmediateRetry() = Unit
}

data class SteamStoreSearchHit(
    val steamAppId: Int,
    val title: String,
    val headerImageUrl: String?,
)

class SteamCatalogSearchException internal constructor() :
    IOException("Steam catalog search unavailable")
