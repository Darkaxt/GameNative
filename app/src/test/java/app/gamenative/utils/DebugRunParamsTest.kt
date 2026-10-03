package app.gamenative.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class DebugRunParamsTest {
    @Test
    fun unrecognizedAndUnboundedChannelsAreRejected() {
        val params = DebugRunParams.fromJson(JSONObject().put("winedebug", JSONArray(listOf("all", "+relay", "server", "bad,channel", "+seh", "-seh"))))!!
        assertEquals(listOf("+seh"), params.winedebug)
    }

    @Test
    fun acceptedChannelsHaveABoundedCount() {
        val params = DebugRunParams.fromJson(JSONObject().put("winedebug", JSONArray((0..20).map { "+channel$it" })))!!
        assertEquals(8, params.winedebug.size)
    }

    @Test
    fun environmentIsAllowlistedAndRejectsMultilineOrOversizedValues() {
        val env = JSONObject().put("LD_PRELOAD", "untrusted")
            .put("DXVK_LOG_LEVEL", "info")
            .put("BOX64_LOG", "1\n2")
            .put("VKD3D_DEBUG", "x".repeat(129))
        val params = DebugRunParams.fromJson(JSONObject().put("env", env))!!
        assertEquals(mapOf("DXVK_LOG_LEVEL" to "info"), params.env)
    }

    @Test
    fun durationInstructionAndAttachmentsRemainBounded() {
        val params = DebugRunParams.fromJson(JSONObject()
            .put("minSeconds", 3601)
            .put("instruction", " x\n".repeat(400))
            .put("attach", JSONArray(listOf("logcat", "perf", "wrapper_diag", "unknown"))))!!
        assertNull(params.minSeconds)
        assertEquals(300, params.instruction!!.length)
        assertEquals(setOf(DebugRunParams.ATTACH_WRAPPER_DIAG), params.attach)
    }

    @Test
    fun emptyOrWhollyRejectedRequestDoesNotCreateRunParameters() {
        assertNull(DebugRunParams.fromJson(null))
        assertNull(DebugRunParams.fromJson(JSONObject()))
        assertNull(DebugRunParams.fromJson(JSONObject().put("winedebug", JSONArray(listOf("all")))))
    }

    @Test
    fun dllOverridesRetainUnchangedEntriesAndReplaceRequestedOnes() {
        assertEquals("b=n;a=b;c=n", DebugRunParams.mergeDllOverrides("a,b=n", "a=b;c=n"))
    }
}
