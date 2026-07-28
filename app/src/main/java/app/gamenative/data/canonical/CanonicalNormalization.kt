package app.gamenative.data.canonical

import app.gamenative.enums.AppType
import java.text.Normalizer
import java.time.Instant
import java.time.Year
import java.time.ZoneOffset
import java.util.Locale

object CanonicalNormalization {
    private val TRADEMARK_SYMBOLS = Regex("[™®©]")
    private val PUNCTUATION_OR_SYMBOLS = Regex("[\\p{P}\\p{S}]+")
    private val WHITESPACE = Regex("[\\p{Z}\\s]+")
    private val RELEASE_YEAR = Regex("^\\s*(\\d{4})(?:$|[-T/].*)")
    private val LEGAL_SUFFIXES = setOf(
        "inc",
        "incorporated",
        "llc",
        "ltd",
        "limited",
        "corp",
        "corporation",
        "gmbh",
        "ab",
        "sa",
    )

    fun displayName(value: String): String = collapseWhitespace(normalize(value))

    fun titleKey(value: String): String = matchingKey(value)

    fun developerKey(value: String): String {
        val key = matchingKey(value)
        if (key.isEmpty()) return key
        return key.split(' ')
            .dropLastWhile(LEGAL_SUFFIXES::contains)
            .joinToString(" ")
    }

    fun releaseYear(value: String): Int? {
        val year = RELEASE_YEAR.find(value)?.groupValues?.get(1)?.toIntOrNull()
        return year?.takeIf(::isSupportedYear)
    }

    fun releaseYear(epochSeconds: Long): Int? {
        if (epochSeconds <= 0L) return null
        return runCatching {
            Instant.ofEpochSecond(epochSeconds).atZone(ZoneOffset.UTC).year
        }.getOrNull()?.takeIf(::isSupportedYear)
    }

    fun appType(value: AppType): CanonicalAppType = when (value) {
        AppType.game -> CanonicalAppType.GAME
        AppType.application -> CanonicalAppType.APPLICATION
        AppType.tool -> CanonicalAppType.TOOL
        AppType.demo -> CanonicalAppType.DEMO
        AppType.dlc -> CanonicalAppType.DLC
        AppType.music -> CanonicalAppType.SOUNDTRACK
        else -> CanonicalAppType.UNKNOWN
    }

    private fun matchingKey(value: String): String {
        val withoutTrademarkSymbols = TRADEMARK_SYMBOLS.replace(value, "")
        val normalized = normalize(withoutTrademarkSymbols).lowercase(Locale.ROOT)
        return collapseWhitespace(PUNCTUATION_OR_SYMBOLS.replace(normalized, " "))
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)

    private fun collapseWhitespace(value: String): String = WHITESPACE.replace(value, " ").trim()

    private fun isSupportedYear(year: Int): Boolean =
        year in MIN_RELEASE_YEAR..(Year.now(ZoneOffset.UTC).value + 1)

    private const val MIN_RELEASE_YEAR = 1970
}
