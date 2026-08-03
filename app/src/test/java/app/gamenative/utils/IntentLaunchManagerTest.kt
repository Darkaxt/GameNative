package app.gamenative.utils

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IntentLaunchManagerTest {
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
