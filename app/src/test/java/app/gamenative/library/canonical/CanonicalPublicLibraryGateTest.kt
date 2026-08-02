package app.gamenative.library.canonical

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class CanonicalPublicLibraryGateTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PrefManager.init(context)
        clearPreferencesAndAwait()
    }

    @After
    fun tearDown() {
        clearPreferencesAndAwait()
    }

    @Test
    fun publicCanonicalLibraryDefaultsOnWhenNoPreferenceExists() {
        assertTrue(PrefManager.canonicalProjectionEnabled)
        assertTrue(PrefManager.canonicalPublicLibraryEnabled)
        assertTrue(PrefManagerCanonicalPublicLibraryGate().isEnabled())
    }

    @Test
    fun explicitFalseSurvivesPrefManagerReinitialization() {
        PrefManager.canonicalPublicLibraryEnabled = false
        awaitPreference { !PrefManager.canonicalPublicLibraryEnabled }

        PrefManager.init(context)

        assertTrue(PrefManager.canonicalProjectionEnabled)
        assertFalse(PrefManager.canonicalPublicLibraryEnabled)
        assertFalse(PrefManagerCanonicalPublicLibraryGate().isEnabled())
    }

    private fun clearPreferencesAndAwait() {
        PrefManager.canonicalProjectionEnabled = false
        awaitPreference { !PrefManager.canonicalProjectionEnabled }
        PrefManager.clearPreferences()
        awaitPreference { PrefManager.canonicalProjectionEnabled }
    }

    private fun awaitPreference(condition: () -> Boolean) {
        repeat(200) {
            if (condition()) return
            Thread.sleep(10L)
        }
        assertTrue("Preference update did not settle", condition())
    }
}
