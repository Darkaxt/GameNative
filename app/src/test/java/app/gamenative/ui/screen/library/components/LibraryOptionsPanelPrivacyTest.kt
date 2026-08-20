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

    @Test
    fun popularityControlsExposeExactThresholdCoverageProgressFailureAndRetry() {
        val root = repositoryRoot()
        val source = File(
            root,
            "app/src/main/java/app/gamenative/ui/screen/library/components/LibraryOptionsPanel.kt",
        ).readText()
        val strings = File(root, "app/src/main/res/values/strings.xml").readText()

        assertTrue(source.contains("listOf(null, 100, 1_000, 10_000)"))
        assertTrue(source.contains("steamPopularityKnownCount"))
        assertTrue(source.contains("steamPopularityEligibleCount"))
        assertTrue(source.contains("steamPopularityProgress.completed"))
        assertTrue(source.contains("steamPopularityProgress.total"))
        assertTrue(source.contains("steamPopularityProgress.failed"))
        assertTrue(source.contains("onRetrySteamPopularity"))
        assertTrue(strings.contains("Steam review indexing failed. Cached counts are still available."))
        assertTrue(strings.contains(">Retry</string>"))
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(workingDirectory) { it.parentFile }
            .first { File(it, "app/src/main/java").isDirectory }
    }
}
