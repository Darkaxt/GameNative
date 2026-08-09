package app.gamenative.service.steam

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

internal const val STEAM_WEB_API_KEY_ALIAS = "gamenative_steam_web_api_key_aes_gcm_v1"

@Singleton
internal class AndroidSteamWebApiKeyCipher @Inject constructor() : SteamWebApiKeyCipher {
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    }

    override fun encrypt(plaintext: ByteArray): ByteArray {
        require(plaintext.isNotEmpty()) { "Plaintext cannot be empty" }

        val iv = ByteArray(IV_LENGTH_BYTES).also(SecureRandom()::nextBytes)
        val header = byteArrayOf(FORMAT_VERSION, IV_LENGTH_BYTES.toByte())
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            updateAAD(header)
        }
        return header + iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size >= MINIMUM_PAYLOAD_BYTES) { "Encrypted key payload is too short" }
        require(ciphertext[0] == FORMAT_VERSION) { "Unsupported encrypted key payload version" }

        val ivLength = ciphertext[1].toInt() and 0xFF
        require(ivLength == IV_LENGTH_BYTES) { "Invalid encrypted key IV length" }
        val ivEnd = HEADER_LENGTH_BYTES + ivLength
        val header = ciphertext.copyOfRange(0, HEADER_LENGTH_BYTES)
        val iv = ciphertext.copyOfRange(HEADER_LENGTH_BYTES, ivEnd)
        val encrypted = ciphertext.copyOfRange(ivEnd, ciphertext.size)
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            updateAAD(header)
            doFinal(encrypted)
        }
    }

    private fun getOrCreateKey(): SecretKey = synchronized(keyStore) {
        val existing = keyStore.getEntry(STEAM_WEB_API_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        existing?.secretKey ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE,
        ).run {
            init(
                KeyGenParameterSpec.Builder(
                    STEAM_WEB_API_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION: Byte = 1
        const val HEADER_LENGTH_BYTES = 2
        const val IV_LENGTH_BYTES = 12
        const val TAG_LENGTH_BITS = 128
        const val TAG_LENGTH_BYTES = TAG_LENGTH_BITS / 8
        const val KEY_SIZE_BITS = 256
        const val MINIMUM_PAYLOAD_BYTES = HEADER_LENGTH_BYTES + IV_LENGTH_BYTES + TAG_LENGTH_BYTES
    }
}
