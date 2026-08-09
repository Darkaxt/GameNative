package app.gamenative.service.steam

import java.nio.charset.StandardCharsets
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class SteamWebApiKeyRepositoryTest {
    @Test
    fun publicRepositoryDoesNotExposePlaintextReads() {
        assertEquals(
            setOf("delete", "save", "status"),
            SteamWebApiKeyRepository::class.java.declaredMethods.map { it.name }.toSet(),
        )
        assertFalse(
            SteamWebApiKeyRepository::class.java.declaredMethods.any {
                it.returnType == String::class.java
            },
        )
    }

    @Test
    fun invalidKeysAreRejectedWithoutEncryptionOrPersistence() = runTest {
        val persistence = FakePersistence()
        val cipher = FakeCipher()
        val repository = DefaultSteamWebApiKeyRepository(persistence, cipher)
        val invalidKeys = listOf(
            "",
            "0".repeat(31),
            "0".repeat(33),
            "g".repeat(32),
            " ${"0".repeat(32)}",
            "${"0".repeat(31)}\n",
            "${"0".repeat(31)}é",
        )

        invalidKeys.forEach { invalidKey ->
            assertEquals(SteamWebApiKeySaveResult.INVALID_FORMAT, repository.save(invalidKey))
        }

        assertEquals(0, cipher.encryptions)
        assertNull(persistence.value)
        assertEquals(SteamWebApiKeyStatus.NOT_CONFIGURED, repository.status())
    }

    @Test
    fun validKeyIsEncryptedPersistedAndAvailableOnlyFromInternalSource() = runTest {
        val persistence = FakePersistence()
        val cipher = FakeCipher()
        val implementation = DefaultSteamWebApiKeyRepository(persistence, cipher)
        val uiRepository: SteamWebApiKeyRepository = implementation
        val networkSource: SteamWebApiKeySource = implementation
        val key = "0123456789abcdefABCDEF0123456789"

        assertEquals(SteamWebApiKeySaveResult.SAVED, uiRepository.save(key))

        assertEquals(SteamWebApiKeyStatus.CONFIGURED, uiRepository.status())
        assertFalse(key.toByteArray(StandardCharsets.UTF_8).contentEquals(persistence.value))
        assertArrayEquals(cipher.encrypt(key.toByteArray(StandardCharsets.UTF_8)), persistence.value)
        assertEquals(key, networkSource.keyOrNull())
    }

    @Test
    fun uppercaseAndLowercaseHexAreBothAccepted() = runTest {
        val persistence = FakePersistence()
        val repository = DefaultSteamWebApiKeyRepository(persistence, FakeCipher())

        assertEquals(SteamWebApiKeySaveResult.SAVED, repository.save("A".repeat(32)))
        assertEquals(SteamWebApiKeySaveResult.SAVED, repository.save("f".repeat(32)))
    }

    @Test
    fun deleteRemovesConfigurationAndNetworkAccess() = runTest {
        val persistence = FakePersistence()
        val implementation = DefaultSteamWebApiKeyRepository(persistence, FakeCipher())
        implementation.save("a".repeat(32))

        implementation.delete()

        assertEquals(SteamWebApiKeyStatus.NOT_CONFIGURED, implementation.status())
        assertNull(implementation.keyOrNull())
        assertNull(persistence.value)
    }

    @Test
    fun invalidDecryptedPersistenceFailsClosedWithoutIncludingPlaintextInError() = runTest {
        val invalidPlaintext = "not-a-valid-stored-key"
        val cipher = FakeCipher()
        val persistence = FakePersistence(cipher.encrypt(invalidPlaintext.toByteArray(StandardCharsets.UTF_8)))
        val source: SteamWebApiKeySource = DefaultSteamWebApiKeyRepository(persistence, cipher)

        try {
            source.keyOrNull()
            fail("Expected corrupt persisted key to be rejected")
        } catch (error: IllegalStateException) {
            assertFalse(error.message.orEmpty().contains(invalidPlaintext))
        }
    }

    private class FakePersistence(
        initialValue: ByteArray? = null,
    ) : SteamWebApiKeyPersistence {
        var value: ByteArray? = initialValue?.copyOf()
            private set

        override suspend fun read(): ByteArray? = value?.copyOf()

        override suspend fun write(value: ByteArray) {
            this.value = value.copyOf()
        }

        override suspend fun delete() {
            value = null
        }
    }

    private class FakeCipher : SteamWebApiKeyCipher {
        var encryptions: Int = 0
            private set

        override fun encrypt(plaintext: ByteArray): ByteArray {
            encryptions += 1
            return byteArrayOf(FORMAT_MARKER) + plaintext.reversedArray()
        }

        override fun decrypt(ciphertext: ByteArray): ByteArray {
            require(ciphertext.firstOrNull() == FORMAT_MARKER)
            return ciphertext.copyOfRange(1, ciphertext.size).reversedArray()
        }

        private companion object {
            const val FORMAT_MARKER: Byte = 0x5A
        }
    }
}
