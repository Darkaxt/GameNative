package app.gamenative.library.metadata

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// These URLs are displayed for explicit browser actions, never fetched by metadata providers.
internal fun safeMetadataExternalLink(raw: String?): String? {
    if (raw.isNullOrBlank() || raw.length > 2_048) return null
    val url = raw.toHttpUrlOrNull() ?: return null
    if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return null
    val host = url.host
    if (!host.contains('.') || host.endsWith(".local") || host.endsWith(".localhost") ||
        host.contains(':') || host.all { it.isDigit() || it == '.' }
    ) return null
    if (url.queryParameterNames.any { key ->
            SENSITIVE_LINK_PARAMETER.containsMatchIn(key)
        }
    ) return null
    return url.toString()
}

private val SENSITIVE_LINK_PARAMETER = Regex(
    "token|password|secret|auth|session|cookie|api.?key|steam.?id|user.?id|account",
    RegexOption.IGNORE_CASE,
)
