package app.gamenative.utils

import com.winlator.xserver.Window
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class WindowActivityTest {
    @Test
    fun repeatedSnapshotsDoNotDoubleCountActiveTime() {
        val activity = WindowActivity()
        activity.onTrackedWindow(window(1, "first"), 0, 0)
        assertEquals(10L, activity.snapshot(null, 10, 10_000)["main_window_active_seconds"])
        val next = activity.snapshot(null, 20, 20_000)
        assertEquals(20L, next["main_window_active_seconds"])
        assertEquals(20L, next["main_window_frames"])
    }

    @Test
    fun activeTimeFollowsTrackedWindowRatherThanWholeSession() {
        val activity = WindowActivity()
        activity.onTrackedWindow(window(1, "first"), 0, 0)
        activity.onTrackedWindow(window(2, "second"), 10, 12_000)
        val result = activity.snapshot(null, 30, 27_000)
        assertEquals("second", result["main_window_class"])
        assertEquals(15L, result["main_window_active_seconds"])
        assertEquals(20L, result["main_window_frames"])
    }

    @Test
    fun untrackedIntervalsAreNotAttributedToPreviousWindow() {
        val activity = WindowActivity()
        val window = window(1, "first")
        activity.onTrackedWindow(window, 0, 0)
        activity.onTrackedWindow(null, 10, 12_000)
        activity.onTrackedWindow(window, 30, 27_000)
        val result = activity.snapshot(null, 40, 40_000)
        assertEquals(25L, result["main_window_active_seconds"])
        assertEquals(20L, result["main_window_frames"])
    }

    private fun window(id: Int, name: String): Window = object : Window(id, null, 0, 0, 1, 1, null) {
        override fun getClassName() = name
    }
}
