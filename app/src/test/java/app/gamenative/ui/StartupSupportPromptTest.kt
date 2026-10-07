package app.gamenative.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupSupportPromptTest {
    @Test
    fun `home never automatically claims or displays the support pitch`() {
        val source = File(repositoryRoot(), "app/src/main/java/app/gamenative/ui/PluviaMain.kt").readText()

        assertFalse("Home must not claim an automatic support prompt", source.contains("claimSupportPrompt("))
        assertFalse("Home must not display the thank-you pitch", source.contains("R.string.main_thank_you_title"))
        assertFalse("Home must not track an automatic launch pitch", source.contains("membershipPitchTrigger = \"launch\""))
    }

    @Test
    fun `removing the startup pitch keeps update and crash recovery dialogs`() {
        val source = File(repositoryRoot(), "app/src/main/java/app/gamenative/ui/PluviaMain.kt").readText()

        assertTrue(source.contains("type = DialogType.APP_UPDATE"))
        assertTrue(source.contains("type = DialogType.CRASH"))
    }

    @Test
    fun `manual support remains available without changing donation preferences`() {
        val source = File(repositoryRoot(), "app/src/main/java/app/gamenative/ui/screen/settings/SettingsGroupInfo.kt").readText()

        assertTrue(source.contains("R.string.settings_info_send_tip_title"))
        assertTrue(source.contains("uriHandler.openUri(Constants.Misc.KO_FI_LINK)"))
    }

    private fun repositoryRoot(): File = generateSequence(
        File(checkNotNull(System.getProperty("user.dir"))),
    ) { it.parentFile }.first { File(it, "app/src/main").isDirectory }
}
