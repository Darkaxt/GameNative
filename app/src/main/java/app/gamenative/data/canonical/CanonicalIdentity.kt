package app.gamenative.data.canonical

import app.gamenative.data.GameSource
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

@JvmInline
value class CanonicalGameId(val value: String) {
    init {
        val parsed = UUID.fromString(value)
        require(parsed.toString() == value)
    }

    companion object {
        fun parse(value: String): CanonicalGameId = CanonicalGameId(value)

        fun random(): CanonicalGameId = CanonicalGameId(UUID.randomUUID().toString())
    }
}

fun interface CanonicalIdGenerator {
    fun generate(): CanonicalGameId
}

@JvmInline
value class AccountScope(val value: String) {
    init {
        require(FORMAT.matches(value))
    }

    companion object {
        private val FORMAT = Regex("[0-9a-f]{64}")

        fun parse(value: String): AccountScope = AccountScope(value)
    }
}

data class OwnedCopyKey(
    val accountScope: AccountScope,
    val source: GameSource,
    val stableSourceId: String,
) {
    init {
        require(stableSourceId.isNotBlank())
    }
}

object EpicStableSourceId {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(namespace: String, catalogId: String): String {
        require(namespace.isNotBlank() && catalogId.isNotBlank())
        return listOf(namespace, catalogId).joinToString(".") { value ->
            encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))
        }
    }

    fun decode(value: String): Pair<String, String> {
        val parts = value.split('.')
        require(parts.size == 2)
        val namespace = decodePart(parts[0])
        val catalogId = decodePart(parts[1])
        require(encode(namespace, catalogId) == value)
        return namespace to catalogId
    }

    private fun decodePart(value: String): String =
        String(decoder.decode(value), StandardCharsets.UTF_8).also { decoded ->
            require(decoded.isNotBlank())
        }
}
