package app.gamenative.ui.screen.library

import app.gamenative.library.metadata.CanonicalGameMetadata
import app.gamenative.library.metadata.GameDetailState
import app.gamenative.library.metadata.GameMovie
import app.gamenative.library.metadata.MetadataProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalSteamMediaBoundaryTest {
    @Test
    fun metadataMoviesKeepPlaybackAndPosterBeforeScreenshots() {
        val streamUrl = "https://video.akamai.steamstatic.com/movie.webm"
        val posterUrl = "https://shared.akamai.steamstatic.com/poster.jpg"
        val screenshotUrl = "https://shared.akamai.steamstatic.com/screenshot.jpg"
        val metadata = CanonicalGameMetadata(
            title = "Fixture",
            shortDescription = null,
            about = null,
            headerImageUrl = "https://shared.akamai.steamstatic.com/header.jpg",
            screenshots = listOf(screenshotUrl),
            movies = listOf(
                GameMovie(
                    name = "Trailer",
                    previewImageUrl = posterUrl,
                    streamUrl = streamUrl,
                ),
            ),
            developers = emptyList(),
            publishers = emptyList(),
            releaseDate = null,
            platforms = emptySet(),
            languages = emptyList(),
            requirements = null,
            features = emptyList(),
            achievementCount = null,
            dlcCount = null,
            fetchedAtEpochMs = 1L,
        )

        val media = canonicalSteamMediaItems(metadata)

        assertEquals(2, media.size)
        assertEquals(streamUrl, media[0].videoUrl)
        assertEquals(posterUrl, media[0].imageUrl)
        assertEquals(screenshotUrl, media[1].imageUrl)
    }

    @Test
    fun canonicalDetailRoutesMediaThroughSnapshotProvider() {
        val epic = GameDetailState.Content(
            metadata = metadata(),
            provider = MetadataProvider.EPIC_CMS,
            stale = false,
        )

        assertEquals(MetadataProvider.EPIC_CMS, canonicalMediaProvider(epic))
        assertEquals(
            MetadataProvider.STEAM_APPDETAILS,
            canonicalMediaProvider(GameDetailState.Loading),
        )
    }

    private fun metadata() = CanonicalGameMetadata(
        title = "Fixture",
        shortDescription = null,
        about = null,
        headerImageUrl = null,
        screenshots = emptyList(),
        movies = emptyList(),
        developers = emptyList(),
        publishers = emptyList(),
        releaseDate = null,
        platforms = emptySet(),
        languages = emptyList(),
        requirements = null,
        features = emptyList(),
        achievementCount = null,
        dlcCount = null,
        fetchedAtEpochMs = 1L,
    )
}
