package app.gamenative.build

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val universalApks =
            "universal-compat.apk universal-compat-legacy-xr.apk" +
                " universal-side-by-side.apk universal-side-by-side-legacy-xr.apk"

        assertTrue(appGradle.contains("versionCode = 36"))
        assertTrue(appGradle.contains("versionName = \"1.1.3-rc10\""))
        assertTrue(workflow.contains("EXPECTED_VERSION_CODE: \"36\""))
        assertTrue(workflow.contains("EXPECTED_VERSION_NAME: \"1.1.3-rc10\""))
        assertEquals(1, workflow.lines().count { it.trimStart().startsWith("EXPECTED_VERSION_CODE:") })
        assertEquals(1, workflow.lines().count { it.trimStart().startsWith("EXPECTED_VERSION_NAME:") })
        assertTrue(workflow.contains("[[ \"\$RELEASE_TAG\" != \"v\$EXPECTED_VERSION_NAME\" ]]"))
        assertTrue(workflow.contains("timeout-minutes: 75"))
        assertTrue(workflow.contains(":app:bundleLegacyRelease"))
        assertTrue(workflow.contains(":app:bundleLegacyXrRelease"))
        assertTrue(workflow.contains(":app:bundleLegacyReleaseDarkaxt"))
        assertTrue(workflow.contains(":app:bundleLegacyXrReleaseDarkaxt"))
        assertTrue(workflow.contains("app/build/outputs/bundle/legacyReleaseDarkaxt/app-legacy-releaseDarkaxt.aab"))
        assertTrue(workflow.contains("app/build/outputs/bundle/legacyXrReleaseDarkaxt/app-legacyXr-releaseDarkaxt.aab"))
        assertEquals(2, workflow.count("for apk in $universalApks; do"))

        val publishInput =
            "      publish_release:\n" +
                "        description: \"Publish the validated candidate as a GitHub prerelease\"\n" +
                "        required: false\n" +
                "        default: false\n" +
                "        type: boolean"
        val releaseJob =
            "  release:\n" +
                "    if: \${{ github.event_name == 'workflow_dispatch' && inputs.publish_release }}\n" +
                "    needs: build"
        assertTrue(workflow.contains(publishInput))
        assertTrue(workflow.contains(releaseJob))

        assertTrue(workflow.contains("gamenative-\${RELEASE_TAG}-compat.apk|app.gamenative"))
        assertTrue(workflow.contains("gamenative-\${RELEASE_TAG}-compat-legacy-xr.apk|app.gamenative"))
        assertTrue(workflow.contains("gamenative-\${RELEASE_TAG}-side-by-side.apk|app.gamenative.darkaxt"))
        assertTrue(workflow.contains("gamenative-\${RELEASE_TAG}-side-by-side-legacy-xr.apk|app.gamenative.darkaxt"))
        assertTrue(workflow.contains("APKANALYZER=\"\${ANDROID_HOME}/cmdline-tools/latest/bin/apkanalyzer\""))
        assertTrue(workflow.contains("APKANALYZER=\"\${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/apkanalyzer\""))
        assertTrue(workflow.contains("if [ ! -x \"\$APKANALYZER\" ]; then"))
        assertTrue(workflow.contains("\"\$APKANALYZER\" manifest application-id \"\$apk\""))
        assertTrue(workflow.contains("\"\$APKANALYZER\" manifest version-code \"\$apk\""))
        assertTrue(workflow.contains("\"\$APKANALYZER\" manifest version-name \"\$apk\""))
        assertFalse(workflow.contains("\${BUILD_TOOLS_DIR}/apkanalyzer"))
        assertTrue(workflow.contains("Verified using v2 scheme (APK Signature Scheme v2): true"))
        assertTrue(
            workflow.indexOf("Verify APK signatures, packages, versions, and generate checksums") <
                workflow.indexOf("Upload release assets for next job"),
        )

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
