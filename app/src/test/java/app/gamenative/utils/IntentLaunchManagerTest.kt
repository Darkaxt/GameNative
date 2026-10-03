package app.gamenative.utils

import android.content.Intent
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import org.junit.Before
import org.junit.Assert.assertFalse
import timber.log.Timber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class IntentLaunchManagerTest {
    @Before
    fun setUp() {
        PrefManager.init(ApplicationProvider.getApplicationContext<Context>())
    }

    @Test
    fun viewLaunchRetainsOfficialSharedSchemeAndNormalizesSource() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("gamenative://run?appid=42&gamesource=gog"))
            .putExtra("container_config", "{}")

        val request = IntentLaunchManager.parseLaunchIntent(intent, IntentLaunchManager.launchAction("app.gamenative.darkaxt"))

        assertEquals("GOG_42", request?.appId)
        assertNull(request?.containerConfig)
    }

    @Test
    fun viewLaunchRejectsMissingInvalidOrOverflowingIdentity() {
        listOf(
            "gamenative://run?gamesource=steam",
            "gamenative://run?appid=-1&gamesource=steam",
            "gamenative://run?appid=2147483648&gamesource=steam",
            "gamenative://run?appid=42",
            "gamenative://run?appid=42&gamesource=unknown",
            "gamenative-darkaxt://run?appid=42&gamesource=steam",
            "https://run?appid=42&gamesource=steam",
        ).forEach { uri ->
            assertNull(uri, IntentLaunchManager.parseLaunchIntent(Intent(Intent.ACTION_VIEW, Uri.parse(uri))))
        }
    }

    @Test
    fun unrelatedDeepLinkIsRejectedWithoutLoggingCallbackPayload() {
        val messages = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                messages += message
            }
        }
        Timber.plant(tree)
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("gamenative://discord-linked?token=synthetic-callback-value"))
            assertNull(IntentLaunchManager.parseLaunchIntent(intent))
            assertFalse("Rejected callback payload must not reach logs", messages.any { it.contains("synthetic-callback-value") })
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test
    fun launchActionUsesSuppliedApplicationId() {
        assertEquals(
            "app.gamenative.darkaxt.LAUNCH_GAME",
            IntentLaunchManager.launchAction("app.gamenative.darkaxt"),
        )
    }

    @Test
    fun sideBySideParserRejectsOfficialAction() {
        val expectedAction = IntentLaunchManager.launchAction("app.gamenative.darkaxt")
        val sideIntent = Intent(expectedAction).putExtra("app_id", 42)
        val officialIntent = Intent("app.gamenative.LAUNCH_GAME").putExtra("app_id", 42)

        assertNotNull(IntentLaunchManager.parseLaunchIntent(sideIntent, expectedAction))
        assertNull(IntentLaunchManager.parseLaunchIntent(officialIntent, expectedAction))
    }

    @Test
    fun omittedDriveOverrideRemainsBlankSentinel() {
        val action = IntentLaunchManager.launchAction("app.gamenative.darkaxt")
        val intent = Intent(action)
            .putExtra("app_id", 42)
            .putExtra("container_config", "{}")

        val request = IntentLaunchManager.parseLaunchIntent(intent, action)

        assertEquals("", request?.containerConfig?.drives)
    }
}
