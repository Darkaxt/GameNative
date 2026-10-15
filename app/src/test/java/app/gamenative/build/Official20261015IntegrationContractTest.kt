package app.gamenative.build

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source integration guards, not runtime proof of downloads, focus or Wine teardown. */
class Official20261015IntegrationContractTest {
    @Test
    fun headlessArchiveRevisionIsSharedByDownloadExtractionAndInstalledMarker() {
        val main = source("ui/PluviaMain.kt")
        val server = source("ui/screen/xserver/XServerScreen.kt")
        assertTrue(main.contains("const val REAL_STEAM_CLIENT_ARCHIVE = \"steamhost-20261015.2.tzst\""))
        assertTrue(main.contains("SteamService.isFileInstallable(context, REAL_STEAM_CLIENT_ARCHIVE)"))
        assertTrue(main.contains("context = context,\n                    REAL_STEAM_CLIENT_ARCHIVE,"))
        assertTrue(server.contains("File(imageFs.getFilesDir(), app.gamenative.ui.REAL_STEAM_CLIENT_ARCHIVE)"))
        assertTrue(server.contains("readText() == steamhostArchive.name"))
        assertTrue(server.contains("headlessMarker?.writeText(steamhostArchive.name)"))
    }

    @Test
    fun cegTeardownHasIndependentKillAndWaitAfterBatchCleanup() {
        val pass = source("ui/screen/xserver/XServerScreen.kt")
            .substringAfter("private fun runSteamHostCegPass(")
            .substringBefore("\nprivate fun ")
        val batch = pass.indexOf("""launcher.execShellCommand("wine cmd /c C:\\steamhost_ceg.bat")""")
        val cleanup = pass.indexOf("pollProgress.set(false)")
        val kill = pass.indexOf("""launcher.execShellCommand("wineserver -k")""")
        val wait = pass.indexOf("""launcher.execShellCommand("wineserver -w")""")
        val result = pass.indexOf("val result = runCatching { resultFile.readText()")
        assertTrue(batch >= 0 && cleanup > batch && kill > cleanup && wait > kill && result > wait)
        assertFalse(pass.contains("&& wineserver -k"))
    }

    @Test
    fun dialogScrollIsAChildBeforeIndependentDepotLoading() {
        val effect = source("ui/component/dialog/GameManagerDialog.kt")
            .substringAfter("LaunchedEffect(visible) {").substringBefore("\n    }")
        val scroll = effect.indexOf("launch { scrollState.scrollTo(0) }")
        val clear = effect.indexOf("downloadableDepots.clear()")
        val load = effect.indexOf("SteamService.getDownloadableDepots(gameId)")
        assertTrue(scroll >= 0 && clear > scroll && load > clear)
        assertFalse(effect.contains("animateScrollTo"))
    }

    private fun source(path: String): String = File(repositoryRoot(), "app/src/main/java/app/gamenative/$path")
        .readText().replace("\r\n", "\n")

    private fun repositoryRoot(): File = generateSequence(File(checkNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app/src/main").isDirectory }
}
