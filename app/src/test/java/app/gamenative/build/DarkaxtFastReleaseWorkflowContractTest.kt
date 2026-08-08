package app.gamenative.build

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DarkaxtFastReleaseWorkflowContractTest {
    @Test
    fun fastReleasePublishesFourPersistentlySignedChannels() {
        val workflow = repositoryFile(".github/workflows/darkaxt-fast-release.yml").readText()

        assertTrue(workflow.contains("EXPECTED_FORK_CERT_SHA256"))
        assertTrue(workflow.contains("EXPECTED_VERSION_CODE: \"31\""))
        assertTrue(workflow.contains("EXPECTED_VERSION_NAME: \"1.1.3-rc5\""))
        assertTrue(workflow.contains("compat-legacy-xr"))
        assertTrue(workflow.contains("side-by-side-legacy-xr"))
        assertTrue(workflow.contains("Verified using v2 scheme"))
        assertTrue(workflow.contains("sha256sum --check SHA256SUMS"))
        assertTrue(workflow.contains("softprops/action-gh-release"))
    }

    private fun repositoryFile(path: String): File = File(repositoryRoot(), path)

    private fun repositoryRoot(): File = generateSequence(
        File(checkNotNull(System.getProperty("user.dir"))),
    ) { it.parentFile }.first { File(it, "app/src/main").isDirectory }
}
