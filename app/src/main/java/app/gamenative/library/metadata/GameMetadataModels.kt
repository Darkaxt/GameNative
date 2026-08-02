package app.gamenative.library.metadata

import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable
data class CanonicalGameMetadata(
    val title: String,
    val shortDescription: String?,
    val about: String?,
    val headerImageUrl: String?,
    val screenshots: List<String>,
    val movies: List<GameMovie>,
    val developers: List<String>,
    val publishers: List<String>,
    val releaseDate: String?,
    val platforms: Set<GamePlatform>,
    val languages: List<String>,
    val requirements: GameRequirements?,
    val features: List<MetadataFacet>,
    val achievementCount: Int?,
    val dlcCount: Int?,
    val fetchedAtEpochMs: Long,
)

@Serializable
data class GameMovie(
    val name: String?,
    val previewImageUrl: String?,
    val streamUrl: String,
)

@Serializable
data class GameRequirements(
    val minimum: String?,
    val recommended: String?,
)

@Serializable
data class MetadataFacet(
    val id: Int?,
    val label: String,
)

@Serializable
enum class GamePlatform {
    WINDOWS,
    MACOS,
    LINUX,
}

@Serializable
data class GameMetadataProvenance(
    val provider: MetadataProvider,
    val fields: Set<MetadataField>,
)

@Serializable
enum class MetadataProvider {
    STEAM_APPDETAILS,
}

@Serializable
enum class MetadataField {
    TITLE,
    SHORT_DESCRIPTION,
    ABOUT,
    HEADER_IMAGE,
    SCREENSHOTS,
    MOVIES,
    DEVELOPERS,
    PUBLISHERS,
    RELEASE_DATE,
    PLATFORMS,
    LANGUAGES,
    REQUIREMENTS,
    FEATURES,
    ACHIEVEMENT_COUNT,
    DLC_COUNT,
}

data class MetadataLocale(
    val locale: String,
    val country: String,
) {
    val normalizedLocale: String
    val normalizedCountry: String

    init {
        require(LOCALE_PATTERN.matches(locale)) { "Invalid metadata locale" }
        require(COUNTRY_PATTERN.matches(country)) { "Invalid metadata country" }
        val parsed = Locale.forLanguageTag(locale)
        require(parsed.language.length in 2..3) { "Invalid metadata locale" }
        normalizedLocale = parsed.toLanguageTag()
        normalizedCountry = country.uppercase(Locale.ROOT)
    }

    internal val steamLanguage: String
        get() = STEAM_LANGUAGES[normalizedLocale.lowercase(Locale.ROOT)]
            ?: STEAM_LANGUAGES[normalizedLocale.substringBefore('-').lowercase(Locale.ROOT)]
            ?: "english"

    private companion object {
        val LOCALE_PATTERN = Regex("[A-Za-z]{2,3}(?:-[A-Za-z]{2}|-[A-Za-z]{4})?(?:-[A-Za-z]{2})?")
        val COUNTRY_PATTERN = Regex("[A-Za-z]{2}")
        val STEAM_LANGUAGES = mapOf(
            "ar" to "arabic",
            "bg" to "bulgarian",
            "cs" to "czech",
            "da" to "danish",
            "de" to "german",
            "en" to "english",
            "es" to "spanish",
            "fi" to "finnish",
            "fr" to "french",
            "hu" to "hungarian",
            "id" to "indonesian",
            "it" to "italian",
            "ja" to "japanese",
            "ko" to "koreana",
            "nl" to "dutch",
            "no" to "norwegian",
            "nb" to "norwegian",
            "pl" to "polish",
            "pt" to "portuguese",
            "pt-br" to "brazilian",
            "ro" to "romanian",
            "ru" to "russian",
            "sv" to "swedish",
            "th" to "thai",
            "tr" to "turkish",
            "uk" to "ukrainian",
            "vi" to "vietnamese",
            "zh-cn" to "schinese",
            "zh-sg" to "schinese",
            "zh-tw" to "tchinese",
            "zh-hk" to "tchinese",
        )
    }
}

fun interface MetadataClock {
    fun nowEpochMs(): Long
}

fun interface MetadataLocaleProvider {
    fun current(): MetadataLocale
}

internal fun sanitizeSteamText(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val withoutActiveContent = raw
        .take(MAX_TEXT_INPUT_LENGTH)
        .replace(SCRIPT_OR_STYLE, " ")
    val withoutTags = withoutActiveContent.replace(HTML_TAG, " ")
    val decoded = decodeHtmlEntities(withoutTags)
        .replace(' ', ' ')
        .replace(WHITESPACE, " ")
        .replace(SPACE_BEFORE_PUNCTUATION, "$1")
        .trim()
    return decoded.takeIf(String::isNotBlank)
}

internal fun CanonicalGameMetadata.sanitizedForPersistence(): CanonicalGameMetadata = copy(
    title = sanitizeSteamText(title).orEmpty(),
    shortDescription = sanitizeSteamText(shortDescription),
    about = sanitizeSteamText(about),
    developers = developers.mapNotNull(::sanitizeSteamText).distinct(),
    publishers = publishers.mapNotNull(::sanitizeSteamText).distinct(),
    releaseDate = sanitizeSteamText(releaseDate),
    languages = languages.mapNotNull(::sanitizeSteamText).distinct(),
    requirements = requirements?.let { value ->
        GameRequirements(
            minimum = sanitizeSteamText(value.minimum),
            recommended = sanitizeSteamText(value.recommended),
        ).takeIf { it.minimum != null || it.recommended != null }
    },
    features = features.mapNotNull { facet ->
        sanitizeSteamText(facet.label)?.let { label -> facet.copy(label = label) }
    }.distinctBy { it.id to it.label },
    achievementCount = achievementCount?.takeIf { it >= 0 },
    dlcCount = dlcCount?.takeIf { it >= 0 },
)

private fun decodeHtmlEntities(value: String): String = HTML_ENTITY.replace(value) { match ->
    val entity = match.groupValues[1]
    when {
        entity.startsWith("#x", ignoreCase = true) ->
            entity.drop(2).toIntOrNull(16)?.safeCodePoint() ?: match.value
        entity.startsWith('#') -> entity.drop(1).toIntOrNull()?.safeCodePoint() ?: match.value
        else -> NAMED_ENTITIES[entity.lowercase(Locale.ROOT)] ?: match.value
    }
}

private fun Int.safeCodePoint(): String? =
    takeIf { Character.isValidCodePoint(it) && it !in 0xD800..0xDFFF }
        ?.let(Character::toChars)
        ?.concatToString()

private const val MAX_TEXT_INPUT_LENGTH = 500_000
private val SCRIPT_OR_STYLE = Regex(
    "<(script|style)\\b[^>]*>.*?</\\1\\s*>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val HTML_TAG = Regex("<[^>]*>")
private val HTML_ENTITY = Regex("&(#(?:x[0-9a-fA-F]+|[0-9]+)|[A-Za-z]+);")
private val WHITESPACE = Regex("\\s+")
private val SPACE_BEFORE_PUNCTUATION = Regex(" \\s*([,.;:!?])")
private val NAMED_ENTITIES = mapOf(
    "amp" to "&",
    "apos" to "'",
    "gt" to ">",
    "lt" to "<",
    "nbsp" to " ",
    "quot" to "\"",
)
