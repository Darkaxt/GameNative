package app.gamenative.service.steam

import app.gamenative.PrefManager
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

enum class SteamWebApiKeyStatus {
    CONFIGURED,
    NOT_CONFIGURED,
}

enum class SteamWebApiKeySaveResult {
    SAVED,
    INVALID_FORMAT,
}

interface SteamWebApiKeyRepository {
    suspend fun status(): SteamWebApiKeyStatus

    suspend fun save(key: String): SteamWebApiKeySaveResult

    suspend fun delete()
}

internal fun interface SteamWebApiKeySource {
    suspend fun keyOrNull(): String?
}

internal interface SteamWebApiKeyPersistence {
    suspend fun read(): ByteArray?

    suspend fun write(value: ByteArray)

    suspend fun delete()
}

internal interface SteamWebApiKeyCipher {
    fun encrypt(plaintext: ByteArray): ByteArray

    fun decrypt(ciphertext: ByteArray): ByteArray
}

@Singleton
internal class DefaultSteamWebApiKeyRepository @Inject constructor(
    private val persistence: SteamWebApiKeyPersistence,
    private val cipher: SteamWebApiKeyCipher,
) : SteamWebApiKeyRepository, SteamWebApiKeySource {
    override suspend fun status(): SteamWebApiKeyStatus =
        if (persistence.read() == null) {
            SteamWebApiKeyStatus.NOT_CONFIGURED
        } else {
            SteamWebApiKeyStatus.CONFIGURED
        }

    override suspend fun save(key: String): SteamWebApiKeySaveResult {
        if (!KEY_PATTERN.matches(key)) return SteamWebApiKeySaveResult.INVALID_FORMAT

        val plaintext = key.toByteArray(StandardCharsets.UTF_8)
        return try {
            persistence.write(cipher.encrypt(plaintext))
            SteamWebApiKeySaveResult.SAVED
        } finally {
            plaintext.fill(0)
        }
    }

    override suspend fun delete() {
        persistence.delete()
    }

    override suspend fun keyOrNull(): String? {
        val encrypted = persistence.read() ?: return null
        val plaintext = cipher.decrypt(encrypted)
        return try {
            String(plaintext, StandardCharsets.UTF_8).also { key ->
                check(KEY_PATTERN.matches(key)) {
                    "Stored Steam Web API key has an invalid format"
                }
            }
        } finally {
            plaintext.fill(0)
        }
    }

    private companion object {
        val KEY_PATTERN = Regex("[0-9A-Fa-f]{32}")
    }
}

@Singleton
internal class PrefManagerSteamWebApiKeyPersistence @Inject constructor() :
    SteamWebApiKeyPersistence {
    override suspend fun read(): ByteArray? = PrefManager.readEncryptedSteamWebApiKey()

    override suspend fun write(value: ByteArray) {
        PrefManager.writeEncryptedSteamWebApiKey(value)
    }

    override suspend fun delete() {
        PrefManager.deleteEncryptedSteamWebApiKey()
    }
}
