package app.gamenative.library.canonical.catalog

import java.text.Normalizer
import java.util.Locale

internal object SteamCatalogNormalization {
    private val trademarkSymbols = Regex("[™®©]")
    private val nonWord = Regex("[^\\p{L}\\p{N}_]+")
    private val whitespace = Regex("\\s+")
    private val playdeadAlias = Regex("^playdead['’]s\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val romanNumerals = mapOf("iii" to "3")
    private val legalSuffixes = setOf(
        "inc",
        "incorporated",
        "llc",
        "ltd",
        "limited",
        "corp",
        "corporation",
        "plc",
    )
    private val editions = listOf(
        "director s cut" to "director's cut",
        "final cut" to "final cut",
        "game of the year" to "game of the year",
        "definitive" to "definitive",
        "enhanced" to "enhanced",
        "ultimate" to "ultimate",
        "remastered" to "remastered",
        "redux" to "redux",
        "complete" to "complete",
        "anniversary" to "anniversary",
    )

    fun titleKey(value: String?): String = value?.let(::words)?.joinToString(" ").orEmpty()

    fun developerKey(value: String?): String {
        val words = value?.let(::words).orEmpty().toMutableList()
        while (words.lastOrNull() in legalSuffixes) words.removeLast()
        return words.joinToString(" ")
    }

    fun titleQueries(value: String): List<String> {
        val original = value.trim()
        val queries = linkedSetOf(original)
        playdeadAlias.matchEntire(original)?.groupValues?.get(1)?.trim()?.let(queries::add)
        val normalized = titleKey(original)
        if (!normalized.equals(original, ignoreCase = true)) queries += normalized
        return queries.filter(String::isNotEmpty)
    }

    fun titleKeys(value: String): List<CatalogTitleKey> = buildList {
        add(CatalogTitleKey(CatalogTitleMatch.EXACT, titleKey(value)))
        titleQueries(value).drop(1).forEach { alias ->
            add(CatalogTitleKey(CatalogTitleMatch.SAFE_ALIAS_EXACT, titleKey(alias)))
        }
    }.distinct()

    fun editionTokens(value: String?): Set<String> {
        val key = titleKey(value)
        return editions.mapNotNullTo(linkedSetOf()) { (phrase, label) ->
            label.takeIf { phrase in key }
        }
    }

    fun editionBaseTitle(value: String?): String {
        var key = titleKey(value)
        editions.forEach { (phrase, _) ->
            key = Regex("\\b${Regex.escape(phrase)}\\b").replace(key, " ")
        }
        val words = collapseWhitespace(key)
            .split(' ')
            .filter { it.isNotEmpty() && it !in setOf("edition", "version") }
            .toMutableList()
        while (words.lastOrNull() == "the") words.removeLast()
        return words.joinToString(" ")
    }

    private fun words(value: String): List<String> {
        val normalized = translateMarks(
            Normalizer.normalize(translateMarks(value), Normalizer.Form.NFKC),
        ).lowercase(Locale.ROOT)
        return collapseWhitespace(nonWord.replace(normalized, " "))
            .split(' ')
            .filter(String::isNotEmpty)
            .map { romanNumerals[it] ?: it }
    }

    private fun translateMarks(value: String): String =
        trademarkSymbols.replace(value, "").replace("&", " and ")

    private fun collapseWhitespace(value: String): String = whitespace.replace(value, " ").trim()
}

internal data class CatalogTitleKey(
    val match: CatalogTitleMatch,
    val value: String,
)

internal enum class CatalogTitleMatch {
    EXACT,
    SAFE_ALIAS_EXACT,
}
