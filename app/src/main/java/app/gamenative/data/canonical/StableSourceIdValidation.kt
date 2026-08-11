package app.gamenative.data.canonical

import app.gamenative.data.GameSource
import java.util.UUID

object StableSourceIdValidation {
    private val gogId = Regex("[1-9][0-9]*")
    private val amazonId = Regex(
        "amzn1\\.adg\\.product\\." +
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
    )
    private const val AMAZON_PREFIX = "amzn1.adg.product."

    fun isValid(source: GameSource, value: String): Boolean = when (source) {
        GameSource.GOG -> gogId.matches(value)
        GameSource.AMAZON -> isCanonicalAmazonId(value)
        else -> false
    }

    fun requireAllValid(source: GameSource, values: Collection<String>) {
        require(source == GameSource.GOG || source == GameSource.AMAZON)
        require(values.all { value -> isValid(source, value) }) {
            "Malformed ${source.name} stable source ID"
        }
    }

    private fun isCanonicalAmazonId(value: String): Boolean {
        if (!amazonId.matches(value)) return false
        val uuidText = value.removePrefix(AMAZON_PREFIX)
        return runCatching { UUID.fromString(uuidText).toString() == uuidText }.getOrDefault(false)
    }
}
