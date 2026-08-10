package app.gamenative.library.metadata

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamUrlPolicyTest {
    private val policy = SteamUrlPolicy()

    @Test
    fun acceptsOnlyExactHttpsSteamAppdetailsEndpoint() {
        assertTrue(
            policy.isAllowedApiRequest(
                "https://store.steampowered.com/api/appdetails".toHttpUrl(),
            ),
        )

        listOf(
            "http://store.steampowered.com/api/appdetails",
            "https://store.steampowered.com.evil.example/api/appdetails",
            "https://user@store.steampowered.com/api/appdetails",
            "https://store.steampowered.com:444/api/appdetails",
            "https://store.steampowered.com/api/other",
        ).forEach { url ->
            assertFalse(url, policy.isAllowedApiRequest(url.toHttpUrl()))
        }
    }

    @Test
    fun rejectsUnexpectedAppDetailsAndStoreSearchQueryParameters() {
        assertTrue(
            policy.isAllowedApiRequest(
                "https://store.steampowered.com/api/appdetails?appids=42&l=english&cc=US"
                    .toHttpUrl(),
            ),
        )
        assertFalse(
            policy.isAllowedApiRequest(
                "https://store.steampowered.com/api/appdetails?appids=42&redirect=evil"
                    .toHttpUrl(),
            ),
        )
        assertTrue(
            policy.isAllowedStoreSearchRequest(
                "https://store.steampowered.com/api/storesearch/?term=example&l=english&cc=US"
                    .toHttpUrl(),
            ),
        )
        assertFalse(
            policy.isAllowedStoreSearchRequest(
                "https://store.steampowered.com/api/storesearch/?term=example&key=secret"
                    .toHttpUrl(),
            ),
        )
    }

    @Test
    fun acceptsOnlyExplicitHttpsSteamMediaHosts() {
        listOf(
            "https://shared.akamai.steamstatic.com/store_item_assets/a.jpg",
            "https://shared.cloudflare.steamstatic.com/store_item_assets/a.jpg",
            "https://cdn.akamai.steamstatic.com/steam/apps/a.jpg",
            "https://cdn.cloudflare.steamstatic.com/steam/apps/a.jpg",
            "https://video.akamai.steamstatic.com/store_trailers/a.webm",
            "https://steamcdn-a.akamaihd.net/steam/apps/a.jpg",
        ).forEach { url ->
            assertTrue(url, policy.isAllowedMediaUrl(url.toHttpUrl()))
        }

        listOf(
            "http://shared.akamai.steamstatic.com/a.jpg",
            "https://shared.akamai.steamstatic.com.evil.example/a.jpg",
            "https://example.com/a.jpg",
            "https://user@cdn.cloudflare.steamstatic.com/a.jpg",
            "https://video.akamai.steamstatic.com:8443/a.webm",
        ).forEach { url ->
            assertFalse(url, policy.isAllowedMediaUrl(url.toHttpUrl()))
        }
    }

    @Test
    fun redirectAndFinalUrlRequireAllowedHttpsHost() {
        assertTrue(
            policy.isAllowedNetworkUrl(
                "https://store.steampowered.com/api/appdetails?ignored=true".toHttpUrl(),
            ),
        )
        assertTrue(
            policy.isAllowedNetworkUrl(
                "https://shared.akamai.steamstatic.com/redirected".toHttpUrl(),
            ),
        )
        assertFalse(
            policy.isAllowedNetworkUrl(
                "https://store.steampowered.com.evil.example/redirected".toHttpUrl(),
            ),
        )
        assertFalse(
            policy.isAllowedNetworkUrl(
                "http://store.steampowered.com/redirected".toHttpUrl(),
            ),
        )
    }
}
