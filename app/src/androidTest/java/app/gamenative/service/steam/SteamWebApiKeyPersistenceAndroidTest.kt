package app.gamenative.service.steam

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.gamenative.PrefManager
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SteamWebApiKeyPersistenceAndroidTest {
    @Test
    fun dedicatedAesGcmKeyEncryptsDataStorePersistenceAndSupportsDeletion() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dataStoreField = PrefManager::class.java.getDeclaredField("dataStore").apply {
            isAccessible = true
        }
        val originalDataStore = dataStoreField.get(PrefManager)
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val testFile = File(context.cacheDir, "steam-web-api-key-${UUID.randomUUID()}.preferences_pb")
        val testDataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { testFile },
        )
        val androidKeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        androidKeyStore.deleteEntry(STEAM_WEB_API_KEY_ALIAS)
        dataStoreField.set(PrefManager, testDataStore)

        try {
            val persistence = PrefManagerSteamWebApiKeyPersistence()
            val firstRepository = DefaultSteamWebApiKeyRepository(
                persistence = persistence,
                cipher = AndroidSteamWebApiKeyCipher(),
            )
            val key = (0 until 32).joinToString(separator = "") { index ->
                (index % 16).toString(radix = 16)
            }
            val plaintext = key.toByteArray(StandardCharsets.UTF_8)

            assertEquals(SteamWebApiKeySaveResult.SAVED, firstRepository.save(key))
            val firstCiphertext = PrefManager.readEncryptedSteamWebApiKey()
            assertNotNull(firstCiphertext)
            assertFalse(firstCiphertext!!.contentEquals(plaintext))
            assertFalse(firstCiphertext.containsSubsequence(plaintext))
            assertTrue(androidKeyStore.containsAlias(STEAM_WEB_API_KEY_ALIAS))
            assertNotEquals("pluvia_secret", STEAM_WEB_API_KEY_ALIAS)

            assertEquals(SteamWebApiKeySaveResult.SAVED, firstRepository.save(key))
            val secondCiphertext = PrefManager.readEncryptedSteamWebApiKey()
            assertNotNull(secondCiphertext)
            assertFalse(firstCiphertext.contentEquals(secondCiphertext))

            val recreatedSource: SteamWebApiKeySource = DefaultSteamWebApiKeyRepository(
                persistence = PrefManagerSteamWebApiKeyPersistence(),
                cipher = AndroidSteamWebApiKeyCipher(),
            )
            assertEquals(key, recreatedSource.keyOrNull())

            firstRepository.delete()
            assertEquals(SteamWebApiKeyStatus.NOT_CONFIGURED, firstRepository.status())
            assertNull(PrefManager.readEncryptedSteamWebApiKey())
            assertNull(recreatedSource.keyOrNull())
        } finally {
            PrefManager.deleteEncryptedSteamWebApiKey()
            dataStoreField.set(PrefManager, originalDataStore)
            androidKeyStore.deleteEntry(STEAM_WEB_API_KEY_ALIAS)
            testScope.cancel()
            testFile.delete()
        }
    }

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
        if (candidate.isEmpty() || candidate.size > size) return false
        return indices.any { start ->
            start + candidate.size <= size &&
                copyOfRange(start, start + candidate.size).contentEquals(candidate)
        }
    }
}
