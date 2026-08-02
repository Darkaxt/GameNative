package app.gamenative.ui.screen.library.components

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryOptionsPanelPrivacyTest {
    @Test
    fun searchQueriesStayOutOfSavedInstanceState() {
        val source = File(
            repositoryRoot(),
            "app/src/main/java/app/gamenative/ui/screen/library/components/LibraryOptionsPanel.kt",
        ).readText()

        assertFalse(source.contains("genreSearchQuery by rememberSaveable"))
        assertFalse(source.contains("tagSearchQuery by rememberSaveable"))
        assertTrue(source.contains("genreSearchQuery by remember {"))
        assertTrue(source.contains("tagSearchQuery by remember {"))
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(workingDirectory) { it.parentFile }
            .first { File(it, "app/src/main/java").isDirectory }
    }
}
