package app.gamenative.library.canonical.artwork

import app.gamenative.data.GameSource
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkUrlPolicyTest {
    @Test(timeout = 5_000)
    fun originalArtworkUsesOnlyItsNativePublicStoreCdn() {
        assertNotNull(original(GameSource.GOG, "https://images.gog-statics.com/fixture_glx_vertical_cover.webp"))
        assertNotNull(original(GameSource.EPIC, "https://cdn1.epicgames.com/fixture/portrait.jpg"))
        assertNotNull(original(GameSource.AMAZON, "https://m.media-amazon.com/images/I/fixture.jpg"))
    }

    @Test(timeout = 5_000)
    fun displayedSteamFallbackCannotBecomeOriginalGogOrEpicEvidence() {
        val steam = "https://shared.akamai.steamstatic.com/steam/apps/42/library_600x900.jpg"
        assertNull(original(GameSource.GOG, steam))
        assertNull(original(GameSource.EPIC, steam))
        assertNull(original(GameSource.AMAZON, steam))
    }

    @Test(timeout = 5_000)
    fun credentialsQueriesFragmentsInsecurePortsAndHostSuffixesFailClosed() {
        listOf(
            "http://images.gog.com/fixture.jpg",
            "https://user:synthetic-secret@images.gog.com/fixture.jpg",
            "https://images.gog.com/fixture.jpg?token=synthetic-secret",
            "https://images.gog.com/fixture.jpg#fragment",
            "https://images.gog.com:8443/fixture.jpg",
            "https://images.gog.com.invalid/fixture.jpg",
            "file:///fixture.jpg",
        ).forEach { assertNull(it, original(GameSource.GOG, it)) }
    }

    @Test(timeout = 5_000)
    fun candidateArtworkIsBoundToTheExactPositiveAppIdAndSupportedAsset() {
        assertNotNull(candidate(42, "https://shared.akamai.steamstatic.com/steam/apps/42/library_600x900.jpg"))
        assertNotNull(candidate(42, "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/42/library_600x900.jpg?t=123"))
        assertNull(candidate(42, "https://shared.akamai.steamstatic.com/steam/apps/43/library_600x900.jpg"))
        assertNull(candidate(0, "https://shared.akamai.steamstatic.com/steam/apps/0/library_600x900.jpg"))
        assertNull(candidate(42, "https://shared.akamai.steamstatic.com/steam/apps/42/screenshot.jpg"))
        assertNull(candidate(42, "https://cdn1.epicgames.com/fixture/portrait.jpg"))
    }

    @Test(timeout = 5_000)
    fun nativeSteamArtworkRequiresItsNativeAppIdRatherThanThePresentationAnchor() {
        val steam = "https://shared.akamai.steamstatic.com/steam/apps/42/library_600x900.jpg"
        assertNull(original(GameSource.STEAM, steam))
        assertNull(original(GameSource.STEAM, steam, steamAppId = 43))
        assertNotNull(original(GameSource.STEAM, steam, steamAppId = 42))
    }

    @Test(timeout = 5_000)
    fun unsupportedCustomArtworkAndOversizedUrlsAddNoEvidence() {
        assertNull(original(GameSource.CUSTOM_GAME, "https://images.gog.com/fixture.jpg"))
        assertNull(original(GameSource.GOG, "https://images.gog.com/" + "a".repeat(4_096) + ".jpg"))
    }

    private fun policy(): Any {
        val type = ArtworkBoundaryFixtures.type("ArtworkUrlPolicy")
        return type.getField("Default").get(null)
    }

    private fun original(source: GameSource, raw: String, steamAppId: Int? = null): Any? {
        val policy = policy()
        return policy.javaClass.getMethod("originalUrl", GameSource::class.java, String::class.java, Integer::class.java)
            .invoke(policy, source, raw, steamAppId)
    }

    private fun candidate(steamAppId: Int, raw: String): Any? {
        val policy = policy()
        return policy.javaClass.getMethod("candidateUrl", Int::class.javaPrimitiveType, String::class.java)
            .invoke(policy, steamAppId, raw)
    }
}

internal object ArtworkBoundaryFixtures {
    fun type(name: String): Class<*> {
        val type = runCatching { Class.forName("app.gamenative.library.canonical.artwork.$name") }.getOrNull()
        assertNotNull("Bounded independent-artwork $name boundary is required", type)
        return requireNotNull(type)
    }
}
