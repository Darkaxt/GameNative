package app.gamenative.build

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaggedReleaseWorkflowContractTest {
    @Test
    fun taggedReleasePublishesFourValidatedDualPackageChannels() {
        val root = repositoryRoot()
        val workflow = File(root, ".github/workflows/tagged-release.yml").readText()
        val appGradle = File(root, "app/build.gradle.kts").readText()
        val expectedAssets = setOf(
            "gamenative-\${{ env.RELEASE_TAG }}-compat.apk",
            "gamenative-\${{ env.RELEASE_TAG }}-compat-legacy-xr.apk",
            "gamenative-\${{ env.RELEASE_TAG }}-side-by-side.apk",
            "gamenative-\${{ env.RELEASE_TAG }}-side-by-side-legacy-xr.apk",
            "SHA256SUMS",
        )
        val universalApks = "universal-compat.apk universal-compat-legacy-xr.apk universal-side-by-side.apk universal-side-by-side-legacy-xr.apk"

        assertTrue(appGradle.contains("versionCode = 28"))
        assertTrue(appGradle.contains("versionName = \"1.1.3-rc2\""))
        assertTrue(workflow.contains("timeout-minutes: 75"))
        assertTrue(workflow.contains(":app:bundleLegacyRelease"))
        assertTrue(workflow.contains(":app:bundleLegacyXrRelease"))
        assertTrue(workflow.contains(":app:bundleLegacyReleaseDarkaxt"))
        assertTrue(workflow.contains(":app:bundleLegacyXrReleaseDarkaxt"))
        assertEquals(2, workflow.count("for apk in $universalApks; do"))

        assertTrue(workflow.contains("gamenative-\${RELEASE_TAG}-compat.apk|app.gamenative"))
        assertTrue(workflow.contains("gamenative-\${RELEASE_TAG}-compat-legacy-xr.apk|app.gamenative"))
        assertTrue(workflow.contains("gamenative-\${RELEASE_TAG}-side-by-side.apk|app.gamenative.darkaxt"))
        assertTrue(workflow.contains("gamenative-\${RELEASE_TAG}-side-by-side-legacy-xr.apk|app.gamenative.darkaxt"))
        assertTrue(workflow.contains("manifest application-id \"\$apk\""))
        assertTrue(workflow.contains("manifest version-code \"\$apk\""))
        assertTrue(workflow.contains("manifest version-name \"\$apk\""))
        assertTrue(workflow.contains("Verified using v2 scheme (APK Signature Scheme v2): true"))
        assertTrue(workflow.indexOf("Verify APK signatures, packages, versions, and generate checksums") <
            workflow.indexOf("Upload release assets for next job"),)

        assertEquals(expectedAssets, uploadedAssets(workflow))
        assertEquals(expectedAssets, publishedAssets(workflow))
    }

    private fun uploadedAssets(workflow: String): Set<String> = workflow
        .substringAfter("path: |\n")
        .substringBefore("\n\n  release:")
        .lines()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()

    private fun publishedAssets(workflow: String): Set<String> = workflow
        .substringAfter("files: |\n")
        .substringBefore("\n        env:")
        .lines()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()

    private fun String.count(value: String): Int = windowed(value.length, 1).count { it == value }

    private fun repositoryRoot(): File = generateSequence(
        File(checkNotNull(System.getProperty("user.dir"))),
    ) { it.parentFile }.first { File(it, "app/src/main").isDirectory }
}
