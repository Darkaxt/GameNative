package app.gamenative.library.canonical.artwork

import app.gamenative.data.GameSource

interface ArtworkFingerprintSource {
    suspend fun original(source: GameSource, raw: String, steamAppId: Int? = null): ArtworkFingerprint?
    suspend fun candidate(steamAppId: Int, raw: String): ArtworkFingerprint?
}

internal fun steamPortraitArtworkUrl(steamAppId: Int): String =
    "https://shared.akamai.steamstatic.com/steam/apps/$steamAppId/library_600x900.jpg"
