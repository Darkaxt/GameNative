package app.gamenative.diagnostics

import java.security.MessageDigest

object DiagnosticRedactor {
    private const val MAX_VALUE_LENGTH = 120

    private val forbiddenPatterns = listOf(
        Regex("(?i)https?://"),
        Regex("(?i)(?:/storage/|/sdcard/|/data/user/)"),
        Regex("(?i)[a-z]:[\\/]"),
        Regex("(?i)bearer\\s+"),
        Regex("(?i)(?:token|secret|authorization|cookie)\\s*[=:]"),
        Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"),
        Regex("[A-Za-z0-9_-]{32,}"),
    )
    private val allowedWireNames = DiagnosticAttribute.entries.mapTo(mutableSetOf()) { it.wireName }

    fun sanitize(attributes: Map<DiagnosticAttribute, String>): Map<String, String> =
        attributes.entries.associate { (key, rawValue) ->
            key.wireName to sanitizeValue(rawValue)
        }

    internal fun sanitizePersisted(attributes: Map<String, String>): Map<String, String> =
        attributes
            .filterKeys(allowedWireNames::contains)
            .mapValues { (_, rawValue) -> sanitizeValue(rawValue) }

    fun correlationId(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun sanitizeValue(rawValue: String): String {
        val singleLine = rawValue
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .trim()
        if (forbiddenPatterns.any { it.containsMatchIn(singleLine) }) return "[redacted]"
        return singleLine.take(MAX_VALUE_LENGTH)
    }
}
