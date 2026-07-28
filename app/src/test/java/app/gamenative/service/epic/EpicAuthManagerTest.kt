package app.gamenative.service.epic

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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

    private fun credentialsFile(): File {
        val directory = File(tempDir, "epic").also { it.mkdirs() }
        return File(directory, "credentials.json")
    }
}
