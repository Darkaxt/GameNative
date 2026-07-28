package app.gamenative.service.epic

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.EpicCredentials
import app.gamenative.data.GameSource
import app.gamenative.library.canonical.AccountScopeInvalidations
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class EpicAuthManagerTest {
    private lateinit var context: Context
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory("epicauthtest").toFile()
        context = object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
            override fun getFilesDir(): File = tempDir
        }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun credentialReplacementAndRemovalEmitSourceOnlyInvalidations() = runTest {
        val replacement = async(start = CoroutineStart.UNDISPATCHED) {
            AccountScopeInvalidations.forSource(GameSource.EPIC).first()
        }
        EpicAuthManager.saveCredentials(
            context,
            EpicCredentials(
                accessToken = "access",
                refreshToken = "refresh",
                accountId = "account",
                displayName = "display",
                expiresAt = 1L,
            ),
        )
        assertEquals(Unit, replacement.await())

        val removal = async(start = CoroutineStart.UNDISPATCHED) {
            AccountScopeInvalidations.forSource(GameSource.EPIC).first()
        }
        assertEquals(true, EpicAuthManager.clearStoredCredentials(context))
        assertEquals(Unit, removal.await())
    }

    @Test
    fun staleGenerationCannotRecreateCredentialsAfterClear() {
        val generation = EpicAuthManager.captureCredentialGeneration()

        assertEquals(true, EpicAuthManager.clearStoredCredentials(context))
        val saved = EpicAuthManager.saveCredentialsIfCurrent(
            context = context,
            credentials = credentials("account"),
            expectedGeneration = generation,
        )

        assertFalse(saved)
        assertFalse(credentialsFile().exists())
    }

    @Test
    fun storedAccountIdReadsOnlyLocalIdentity() {
        val credentialsFile = credentialsFile().also { it.parentFile?.mkdirs() }
        val credentialsJson = JSONObject()
            .put("access_token", "local-only-access-token")
            .put("refresh_token", "local-only-refresh-token")
            .put("account_id", "stored-account-id")
            .put("display_name", "not-used-by-account-scope")
            .put("expires_at", 0)
            .toString()
        credentialsFile.writeText(credentialsJson)

        assertEquals("stored-account-id", EpicAuthManager.getStoredAccountId(context))
        assertEquals(credentialsJson, credentialsFile.readText())
    }

    @Test
    fun storedAccountIdReturnsNullForMissingMalformedOrBlankFiles() {
        assertNull(EpicAuthManager.getStoredAccountId(context))

        val credentialsFile = credentialsFile()
        credentialsFile.writeText("not-json")
        assertNull(EpicAuthManager.getStoredAccountId(context))

        credentialsFile.writeText(JSONObject().put("account_id", "").toString())
        assertNull(EpicAuthManager.getStoredAccountId(context))
    }

    private fun credentials(accountId: String) = EpicCredentials(
        accessToken = "access",
        refreshToken = "refresh",
        accountId = accountId,
        displayName = "display",
        expiresAt = 1L,
    )

    private fun credentialsFile(): File {
        val directory = File(tempDir, "epic").also { it.mkdirs() }
        return File(directory, "credentials.json")
    }
}
