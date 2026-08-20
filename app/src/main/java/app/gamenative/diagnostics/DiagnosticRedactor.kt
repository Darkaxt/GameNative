package app.gamenative.diagnostics

import java.security.MessageDigest

object DiagnosticRedactor {
    private const val MAX_VALUE_LENGTH = 120

    private val alwaysForbiddenPatterns = listOf(
        Regex("(?i)(?:/storage/|/sdcard/|/data/user/)"),
        Regex("(?i)(?:^|\\s)[a-z]:(?:\\\\|/)"),
        Regex("(?i)bearer\\s+"),
        Regex(
            "(?i)(?:api[_-]?key|access[_-]?token|auth(?:orization|[_-]?token)?|" +
                "password|passwd|token|secret|cookie|session(?:id)?)\\s*[=:]",
        ),
        Regex("(?i)https?://[^/\\s:@]+:[^/@\\s]+@"),
        Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"),
    )
    private val untypedForbiddenPatterns = listOf(
        Regex("(?i)https?://"),
        Regex("[A-Za-z0-9_-]{32,}"),
    )
    private val typedPublicAttributes = setOf(
        DiagnosticAttribute.STEAM_APP_ID,
        DiagnosticAttribute.STOREFRONT_PRODUCT_ID,
        DiagnosticAttribute.PUBLIC_TITLE,
        DiagnosticAttribute.PUBLIC_URL,
        DiagnosticAttribute.PUBLIC_ROUTE,
        DiagnosticAttribute.PUBLIC_CONTENT_ID,
    )
    private val attributesByWireName = DiagnosticAttribute.entries.associateBy { it.wireName }

    fun sanitize(attributes: Map<DiagnosticAttribute, String>): Map<String, String> =
        attributes.entries.associate { (key, rawValue) ->
            key.wireName to sanitizeValue(key, rawValue)
        }

    internal fun sanitizePersisted(attributes: Map<String, String>): Map<String, String> =
        attributes.mapNotNull { (wireName, rawValue) ->
            attributesByWireName[wireName]?.let { attribute ->
                wireName to sanitizeValue(attribute, rawValue)
            }
        }.toMap()

    fun correlationId(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun sanitizeValue(
        attribute: DiagnosticAttribute,
        rawValue: String,
    ): String {
        val singleLine = rawValue
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .trim()
        if (alwaysForbiddenPatterns.any { it.containsMatchIn(singleLine) }) return "[redacted]"
        if (
            attribute !in typedPublicAttributes &&
            untypedForbiddenPatterns.any { it.containsMatchIn(singleLine) }
        ) {
            return "[redacted]"
        }
        return singleLine.take(MAX_VALUE_LENGTH)
    }
}
