package app.gamenative.api

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class SupportSuggestionTest {
    @Test
    fun unsupportedVersionDoesNotCreateApplicableSuggestion() {
        assertNull(SupportSuggestion.parse(JSONObject().put("v", 2).put("rerun", true)))
    }

    @Test
    fun knownTypedConfigurationRemainsApplicable() {
        val suggestion = parse(listOf(change("dxwrapper", "dxvk")))
        assertTrue(suggestion.applicable)
        assertEquals("dxvk", suggestion.changes.single().to)
    }

    @Test
    fun unknownKeysOrInvalidEnumsRejectEntireSuggestion() {
        assertFalse(parse(listOf(change("dxwrapper", "unknown"))).applicable)
        assertFalse(parse(listOf(change("arbitraryPrivatePath", "value"))).applicable)
    }

    @Test
    fun duplicateChangesRejectEntireSuggestion() {
        assertFalse(parse(listOf(change("dxwrapper", "dxvk"), change("dxwrapper", "wined3d"))).applicable)
    }

    @Test
    fun oversizedChangeListRemainsInapplicableRatherThanPartiallyApplied() {
        val suggestion = parse((0..20).map { change("envVars", "1").put("name", "SYNTHETIC_$it") })
        assertFalse(suggestion.applicable)
        assertEquals(20, suggestion.changes.size)
    }

    @Test
    fun environmentChangesCannotReplaceBoundedWineDebugConfiguration() {
        assertFalse(parse(listOf(change("envVars", "all").put("name", "winedebug"))).applicable)
        assertFalse(parse(listOf(change("envVars", "1\n2").put("name", "SYNTHETIC"))).applicable)
    }

    @Test
    fun subkeyValuesCannotInjectAdditionalKeyValueEntries() {
        assertFalse(parse(listOf(change("dxwrapperConfig.test", "value,other=1"))).applicable)
        assertTrue(parse(listOf(change("wincomponents.direct3d", "1"))).applicable)
        assertFalse(parse(listOf(change("wincomponents.unknown", "1"))).applicable)
    }

    private fun change(key: String, value: String) = JSONObject().put("key", key).put("to", value)

    private fun parse(changes: List<JSONObject>) = SupportSuggestion.parse(
        JSONObject().put("v", 1).put("changes", JSONArray(changes)),
    )!!
}
