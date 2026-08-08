package app.gamenative.library.metadata

import okhttp3.HttpUrl

class SteamUrlPolicy(
    private val apiHosts: Set<String> = STEAM_API_HOSTS,
    private val mediaHosts: Set<String> = STEAM_MEDIA_HOSTS,
    private val requireHttps: Boolean = true,
    private val allowedPorts: Set<Int> = setOf(443),
) {
    fun isAllowedApiRequest(url: HttpUrl): Boolean =
        isSafeBase(url) &&
            url.host in apiHosts &&
            url.encodedPath == APPDETAILS_PATH

    fun isAllowedStoreSearchRequest(url: HttpUrl): Boolean =
        isSafeBase(url) &&
            url.host in apiHosts &&
            url.encodedPath == STORE_SEARCH_PATH &&
            url.queryParameterNames.all(STORE_SEARCH_QUERY_PARAMETERS::contains)

    fun isAllowedMediaUrl(url: HttpUrl): Boolean =
        isSafeBase(url) && url.host in mediaHosts

    fun isAllowedNetworkUrl(url: HttpUrl): Boolean =
        isSafeBase(url) && (url.host in apiHosts || url.host in mediaHosts)

    private fun isSafeBase(url: HttpUrl): Boolean =
        (!requireHttps || url.isHttps) &&
            url.port in allowedPorts &&
            url.username.isEmpty() &&
            url.password.isEmpty() &&
            url.fragment == null

    companion object {
        const val APPDETAILS_PATH = "/api/appdetails"
        const val STORE_SEARCH_PATH = "/api/storesearch/"
        val STORE_SEARCH_QUERY_PARAMETERS = setOf("term", "cc", "l")
        val STEAM_API_HOSTS: Set<String> = setOf("store.steampowered.com")
        val STEAM_MEDIA_HOSTS: Set<String> = setOf(
            "shared.akamai.steamstatic.com",
            "shared.cloudflare.steamstatic.com",
            "cdn.akamai.steamstatic.com",
            "cdn.cloudflare.steamstatic.com",
            "video.akamai.steamstatic.com",
            "steamcdn-a.akamaihd.net",
        )
    }
}
