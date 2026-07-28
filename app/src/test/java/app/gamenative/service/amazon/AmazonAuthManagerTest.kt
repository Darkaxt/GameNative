package app.gamenative.service.amazon

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.AmazonCredentials
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class AmazonAuthManagerTest {
    private lateinit var context: Context
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory("amazonauthtest").toFile()
        context = object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
            override fun getFilesDir(): File = tempDir
        }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun legacyCredentialsGainOnePersistentProfileScopeId() {
        val file = credentialsFile().also { it.parentFile?.mkdirs() }
        file.writeText(
            JSONObject()
                .put("access_token", "legacy-access-token")
                .put("refresh_token", "legacy-refresh-token")
                .put("device_serial", "legacy-device-serial")
                .put("client_id", "legacy-client-id")
                .put("expires_at", 1234L)
                .toString(),
        )

        val first = AmazonAuthManager.getOrCreateProfileScopeId(context)
        val persistedAfterFirstRead = JSONObject(file.readText()).optString("profile_scope_id")
        val second = AmazonAuthManager.getOrCreateProfileScopeId(context)
        val loaded = AmazonAuthManager.loadCredentials(context)

        assertTrue(first != null && UUID.fromString(first).toString() == first)
        assertEquals(first, persistedAfterFirstRead)
        assertEquals(first, second)
        assertEquals(first, loaded?.profileScopeId)
        assertEquals(first, JSONObject(file.readText()).getString("profile_scope_id"))
    }

    @Test
    fun savingRefreshedCredentialsPreservesProfileScopeId() {
        val profileScopeId = UUID.randomUUID().toString()
        AmazonAuthManager.saveCredentials(context, credentials(profileScopeId))

        val loaded = AmazonAuthManager.loadCredentials(context)
        val refreshed = requireNotNull(loaded).copy(
            accessToken = "refreshed-access-token",
            expiresAt = 9999L,
        )
        AmazonAuthManager.saveCredentials(context, refreshed)

        assertEquals(profileScopeId, AmazonAuthManager.loadCredentials(context)?.profileScopeId)
        assertEquals(profileScopeId, AmazonAuthManager.getOrCreateProfileScopeId(context))
    }

    @Test
    fun credentialDeletionRemovesScopeAndLaterAuthenticationUsesANewOne() {
        val firstProfileScopeId = UUID.randomUUID().toString()
        AmazonAuthManager.saveCredentials(context, credentials(firstProfileScopeId))

        assertTrue(AmazonAuthManager.clearStoredCredentials(context))
        assertFalse(credentialsFile().exists())
        assertNull(AmazonAuthManager.getOrCreateProfileScopeId(context))

        val laterProfileScopeId = UUID.randomUUID().toString()
        AmazonAuthManager.saveCredentials(context, credentials(laterProfileScopeId))

        assertNotEquals(firstProfileScopeId, laterProfileScopeId)
        assertEquals(laterProfileScopeId, AmazonAuthManager.getOrCreateProfileScopeId(context))
    }

    @Test
    fun missingOrMalformedCredentialsHaveNoProfileScope() {
        assertNull(AmazonAuthManager.getOrCreateProfileScopeId(context))

        val malformed = credentialsFile().also { it.parentFile?.mkdirs() }
        malformed.writeText("not-json")

        assertNull(AmazonAuthManager.getOrCreateProfileScopeId(context))
        assertEquals("not-json", malformed.readText())

        malformed.writeText(
            JSONObject()
                .put("access_token", "")
                .put("refresh_token", "refresh-token")
                .put("device_serial", "device-serial")
                .put("client_id", "client-id")
                .put("expires_at", 1234L)
                .toString(),
        )
        assertNull(AmazonAuthManager.getOrCreateProfileScopeId(context))
        assertFalse(JSONObject(malformed.readText()).has("profile_scope_id"))
    }

    @Test
    fun atomicReplacementFailurePreservesExistingCredentials() {
        val file = credentialsFile().also { it.parentFile?.mkdirs() }
        val original = "valid-existing-credentials"
        file.writeText(original)

        val result = runCatching {
            AmazonAuthManager.replaceFileAtomically(file) { output ->
                output.write("partial-replacement".toByteArray())
                throw IOException("injected write failure")
            }
        }

        assertTrue(result.isFailure)
        assertEquals(original, file.readText())
    }

    @Test
    fun staleGenerationCannotRecreateCredentialsAfterClear() {
        val generation = AmazonAuthManager.captureCredentialGeneration()

        assertTrue(AmazonAuthManager.clearStoredCredentials(context))
        val saved = AmazonAuthManager.saveCredentialsIfCurrent(
            context = context,
            credentials = credentials(UUID.randomUUID().toString()),
            expectedGeneration = generation,
        )

        assertFalse(saved)
        assertFalse(credentialsFile().exists())
    }

    @Test
    fun logoutFailsWhenLocalCredentialsCannotBeDeleted() = runTest {
        val credentialsPath = credentialsFile().also { it.mkdirs() }
        File(credentialsPath, "blocking-child").writeText("x")

        val result = AmazonAuthManager.logout(context)

        assertTrue(result.isFailure)
        assertTrue(credentialsPath.exists())
    }

    private fun credentials(profileScopeId: String) = AmazonCredentials(
        accessToken = "access-token",
        refreshToken = "refresh-token",
        deviceSerial = "device-serial",
        clientId = "client-id",
        expiresAt = 1234L,
        profileScopeId = profileScopeId,
    )

    private fun credentialsFile(): File = File(tempDir, "amazon/credentials.json")
}
