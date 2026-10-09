package app.gamenative.library.canonical.artwork

import app.gamenative.data.GameSource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class ArtworkUrlPolicy internal constructor(
    hostsBySource: Map<GameSource, Set<String>>,
    private val requireHttps: Boolean,
    allowedPorts: Set<Int>,
) {
    private val hostsBySource = hostsBySource.mapValues { it.value.toSet() }
    private val allowedPorts = allowedPorts.toSet()

    fun originalUrl(source: GameSource, raw: String, steamAppId: Int?): HttpUrl? {
        if (source == GameSource.CUSTOM_GAME) return null
        if (source == GameSource.STEAM) return steamAppId?.let { candidateUrl(it, raw) }
        val url = publicUrl(source, raw) ?: return null
        if (url.querySize != 0 || !IMAGE_PATH.matches(url.encodedPath)) return null
        return url
    }

    fun candidateUrl(steamAppId: Int, raw: String): HttpUrl? {
        if (steamAppId <= 0) return null
        val url = publicUrl(GameSource.STEAM, raw) ?: return null
        val roots = listOf("/steam/apps/$steamAppId/", "/store_item_assets/steam/apps/$steamAppId/")
        val asset = roots.firstOrNull(url.encodedPath::startsWith)?.let(url.encodedPath::removePrefix)
            ?: return null
        if (asset !in STEAM_ASSETS) return null
        if (url.querySize != 0) {
            if (url.queryParameterNames != setOf("t")) return null
            val timestamps = url.queryParameterValues("t")
            if (timestamps.size != 1 || timestamps.single()?.matches(TIMESTAMP) != true) return null
        }
        return url
    }

    private fun publicUrl(source: GameSource, raw: String): HttpUrl? {
        if (raw.length > 2_048) return null
        val url = raw.toHttpUrlOrNull() ?: return null
        if ((requireHttps && url.scheme != "https") || url.port !in allowedPorts ||
            url.host !in hostsBySource[source].orEmpty() || url.username.isNotEmpty() ||
            url.password.isNotEmpty() || url.fragment != null
        ) return null
        return url
    }

    companion object {
        private val IMAGE_PATH = Regex("/[^?#]*\\.(?:png|jpe?g|webp)", RegexOption.IGNORE_CASE)
        private val TIMESTAMP = Regex("[0-9]{1,20}")
        private val STEAM_ASSETS = setOf("library_600x900.jpg", "library_600x900_2x.jpg", "header.jpg")

        @JvmField
        val Default = ArtworkUrlPolicy(
            hostsBySource = mapOf(
                GameSource.GOG to setOf("images.gog.com", "images.gog-statics.com"),
                GameSource.EPIC to setOf("cdn1.epicgames.com", "cdn2.unrealengine.com", "cdn.unrealengine.com"),
                GameSource.AMAZON to setOf("m.media-amazon.com", "images-na.ssl-images-amazon.com"),
                GameSource.STEAM to setOf(
                    "shared.akamai.steamstatic.com", "shared.fastly.steamstatic.com",
                    "cdn.akamai.steamstatic.com", "cdn.cloudflare.steamstatic.com",
                ),
            ),
            requireHttps = true,
            allowedPorts = setOf(443),
        )
    }
}
