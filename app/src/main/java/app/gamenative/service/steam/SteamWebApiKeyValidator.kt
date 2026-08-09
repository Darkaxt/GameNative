package app.gamenative.service.steam

enum class SteamWebApiKeyValidationResult {
    VALID,
    INVALID,
    UNAVAILABLE,
}

fun interface SteamWebApiKeyValidator {
    suspend fun validate(key: String): SteamWebApiKeyValidationResult
}

internal fun hasValidSteamWebApiKeyFormat(key: String): Boolean =
    STEAM_WEB_API_KEY_PATTERN.matches(key)

private val STEAM_WEB_API_KEY_PATTERN = Regex("[0-9A-Fa-f]{32}")
