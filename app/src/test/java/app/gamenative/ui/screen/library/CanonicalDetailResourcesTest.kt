package app.gamenative.ui.screen.library

import app.gamenative.library.metadata.CanonicalGameMetadata
import app.gamenative.library.metadata.GameMovie
import app.gamenative.library.metadata.GamePlatform
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalDetailResourcesTest {
    @Test
    fun resourcesUseOnlyTrustedSteamHttpsDestinations() {
        val links = steamResourceLinks(123456)

        assertEquals(7, links.size)
        links.forEach { link ->
            val url = link.url.toHttpUrl()
            assertTrue(url.isHttps)
            assertTrue(url.host == "store.steampowered.com" || url.host == "steamcommunity.com")
            assertTrue(url.username.isEmpty())
            assertTrue(url.password.isEmpty())
        }
    }

    @Test
    fun invalidSteamIdentityHasNoResources() {
        assertTrue(steamResourceLinks(null).isEmpty())
        assertTrue(steamResourceLinks(0).isEmpty())
        assertTrue(steamResourceLinks(-1).isEmpty())
    }

    @Test
    fun overviewMediaKeepsPlayableTrailerBeforeScreenshots() {
        val metadata = CanonicalGameMetadata(
            title = "Fixture",
            shortDescription = null,
            about = null,
            headerImageUrl = "https://shared.akamai.steamstatic.com/header.jpg",
            screenshots = listOf("https://shared.akamai.steamstatic.com/screenshot.jpg"),
            movies = listOf(
                GameMovie(
                    name = "Trailer",
                    previewImageUrl = "https://shared.akamai.steamstatic.com/poster.jpg",
                    streamUrl = "https://video.akamai.steamstatic.com/trailer.webm",
                ),
            ),
            developers = emptyList(),
            publishers = emptyList(),
            releaseDate = null,
            platforms = setOf(GamePlatform.WINDOWS),
            languages = emptyList(),
            requirements = null,
            features = emptyList(),
            achievementCount = null,
            dlcCount = null,
            fetchedAtEpochMs = 1L,
        )

        val media = canonicalSteamMediaItems(metadata)

        assertEquals(2, media.size)
        assertEquals("https://video.akamai.steamstatic.com/trailer.webm", media[0].videoUrl)
        assertEquals("https://shared.akamai.steamstatic.com/poster.jpg", media[0].imageUrl)
        assertEquals("https://shared.akamai.steamstatic.com/screenshot.jpg", media[1].imageUrl)
    }
}
