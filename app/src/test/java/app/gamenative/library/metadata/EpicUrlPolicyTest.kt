package app.gamenative.library.metadata

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpicUrlPolicyTest {
    private val policy = EpicUrlPolicy()

    @Test
    fun acceptsOnlyHttpsEpicAndUnrealMediaHosts() {
        listOf(
            "https://epicgames.com/image.jpg",
            "https://cdn.epicgames.com/image.jpg",
            "https://unrealengine.com/video.m3u8",
            "https://cdn2.unrealengine.com/video.m3u8",
        ).forEach { url ->
            assertTrue(url, policy.isAllowedMediaUrl(url.toHttpUrl()))
        }

        listOf(
            "http://cdn.epicgames.com/image.jpg",
            "https://epicgames.com.evil.example/image.jpg",
            "https://notepicgames.com/image.jpg",
            "https://user@cdn2.unrealengine.com/video.m3u8",
            "https://cdn2.unrealengine.com:8443/video.m3u8",
            "https://shared.akamai.steamstatic.com/image.jpg",
        ).forEach { url ->
            assertFalse(url, policy.isAllowedMediaUrl(url.toHttpUrl()))
        }
    }

    @Test
    fun metadataProviderSelectsOnlyItsOwnMediaPolicy() {
        val steamUrl = "https://shared.akamai.steamstatic.com/image.jpg".toHttpUrl()
        val epicUrl = "https://cdn2.unrealengine.com/image.jpg".toHttpUrl()

        assertTrue(MetadataProvider.STEAM_APPDETAILS.mediaUrlPolicy().isAllowedMediaUrl(steamUrl))
        assertFalse(MetadataProvider.STEAM_APPDETAILS.mediaUrlPolicy().isAllowedMediaUrl(epicUrl))
        assertTrue(MetadataProvider.EPIC_CMS.mediaUrlPolicy().isAllowedMediaUrl(epicUrl))
        assertFalse(MetadataProvider.EPIC_CMS.mediaUrlPolicy().isAllowedMediaUrl(steamUrl))
    }
}
