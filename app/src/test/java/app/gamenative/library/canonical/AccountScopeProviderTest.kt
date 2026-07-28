package app.gamenative.library.canonical

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.service.gog.GOGConstants
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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
class AccountScopeProviderTest {
    private lateinit var context: Context
    private lateinit var provider: AccountScopeProvider

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearCredentialFiles()
        PrefManager.init(context)
        setSteamId(0L)
        provider = DefaultAccountScopeProvider(context)
    }

    @After
    fun tearDown() {
        clearCredentialFiles()
        setSteamId(0L)
    }

    @Test
    fun identicalSourceAndAccountKeyProduceTheSameLowercaseDigest() = runTest {
        val first = provider.current(GameSource.CUSTOM_GAME)
        val second = provider.current(GameSource.CUSTOM_GAME)

        assertEquals(first, second)
        assertTrue(first != null && Regex("[0-9a-f]{64}").matches(first.value))
        assertEquals(
            expectedScope(GameSource.CUSTOM_GAME, CUSTOM_ACCOUNT_NAMESPACE),
            first?.value,
        )
        assertFalse(first?.value.orEmpty().contains(CUSTOM_ACCOUNT_NAMESPACE))
    }

    @Test
    fun identicalRawKeysAreSeparatedBySourceDomain() = runTest {
        val accountKey = 123456789L
        setSteamId(accountKey)
        File(context.filesDir, "gog_auth.json").writeText(
            JSONObject()
                .put(
                    GOGConstants.GOG_CLIENT_ID,
                    JSONObject()
                        .put("user_id", accountKey.toString())
                        .put("access_token", "not-used-by-account-scope"),
                ).toString(),
        )

        val steam = provider.current(GameSource.STEAM)
        val gog = provider.current(GameSource.GOG)

        assertNotEquals(steam, gog)
        assertEquals(expectedScope(GameSource.STEAM, accountKey.toString()), steam?.value)
        assertEquals(expectedScope(GameSource.GOG, accountKey.toString()), gog?.value)
        assertFalse(steam?.value.orEmpty().contains(accountKey.toString()))
        assertFalse(gog?.value.orEmpty().contains(accountKey.toString()))
    }

    @Test
    fun zeroSteamIdHasNoAccountScope() = runTest {
        setSteamId(0L)

        assertNull(provider.current(GameSource.STEAM))
    }

    @Test
    fun synchronousSteamIdentityChangesAreImmediatelyVisible() = runTest {
        val steamId = 123456789L

        PrefManager.setSteamAccountIdentitySynchronously(
            accountId = 42,
            steamId64 = steamId,
        )
        assertEquals(expectedScope(GameSource.STEAM, steamId.toString()), provider.current(GameSource.STEAM)?.value)

        PrefManager.clearSteamSessionPreferencesSynchronously()
        assertNull(provider.current(GameSource.STEAM))
    }

    @Test
    fun malformedOrMissingCredentialFilesHaveNoAccountScope() = runTest {
        assertNull(provider.current(GameSource.GOG))
        assertNull(provider.current(GameSource.EPIC))
        assertNull(provider.current(GameSource.AMAZON))

        File(context.filesDir, "gog_auth.json").writeText("not-json")
        File(context.filesDir, "epic").mkdirs()
        File(context.filesDir, "epic/credentials.json").writeText("not-json")
        File(context.filesDir, "amazon").mkdirs()
        File(context.filesDir, "amazon/credentials.json").writeText("not-json")

        assertNull(provider.current(GameSource.GOG))
        assertNull(provider.current(GameSource.EPIC))
        assertNull(provider.current(GameSource.AMAZON))
    }

    private fun expectedScope(source: GameSource, accountKey: String): String {
        val separator = Char.MIN_VALUE
        val input = "gamenative-owned-copy-scope-v1$separator${source.name}$separator$accountKey"
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
    }

    private fun setSteamId(value: Long) {
        PrefManager.steamUserSteamId64 = value
        val deadline = System.nanoTime() + 5_000_000_000L
        while (PrefManager.steamUserSteamId64 != value && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(value, PrefManager.steamUserSteamId64)
    }

    private fun clearCredentialFiles() {
        File(context.filesDir, "gog_auth.json").delete()
        File(context.filesDir, "epic").deleteRecursively()
        File(context.filesDir, "amazon").deleteRecursively()
    }

    private companion object {
        const val CUSTOM_ACCOUNT_NAMESPACE = "local-custom-games-v1"
    }
}
