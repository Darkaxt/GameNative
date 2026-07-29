package app.gamenative.library.canonical

import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.data.canonical.OwnedCopyKey
import java.security.MessageDigest

sealed interface CanonicalCardKey {
    data class Grouped(val canonicalId: CanonicalGameId) : CanonicalCardKey
    data class Independent(val copyKey: OwnedCopyKey) : CanonicalCardKey
}

internal fun CanonicalCardKey.stableComposeKey(): String = when (this) {
    is CanonicalCardKey.Grouped -> "group:${canonicalId.value}"
    is CanonicalCardKey.Independent -> {
        val raw = listOf(
            copyKey.accountScope.value,
            copyKey.source.name,
            copyKey.stableSourceId,
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { byte -> "%02x".format(byte) }
        "copy:${copyKey.source.name}:$digest"
    }
}
