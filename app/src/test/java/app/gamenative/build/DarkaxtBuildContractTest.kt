package app.gamenative.build

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DarkaxtBuildContractTest {
    @Test
    fun sideBySideBuildKeepsNamespaceAndDefinesPermanentIdentity() {
        val root = repositoryRoot()
        val appGradle = File(root, "app/build.gradle.kts").readText()
        val featureGradle = File(root, "ubuntufs/build.gradle.kts").readText()
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()

        assertTrue(appGradle.contains("namespace = \"app.gamenative\""))
        assertTrue(appGradle.contains("applicationId = \"app.gamenative\""))
        assertTrue(appGradle.contains("create(\"releaseDarkaxt\")"))
        assertTrue(appGradle.contains("applicationIdSuffix = \".darkaxt\""))
        assertTrue(appGradle.contains("resValue(\"string\", \"app_name\", \"GameNative Darkaxt\")"))
        assertTrue(appGradle.contains("manifestPlaceholders[\"icon\"] = \"@mipmap/ic_launcher\""))
        assertTrue(appGradle.contains("manifestPlaceholders[\"roundIcon\"] = \"@mipmap/ic_launcher_round\""))
        assertTrue(appGradle.contains("manifestPlaceholders[\"altIcon\"] = \"@mipmap/ic_launcher_alt\""))
        assertTrue(appGradle.contains("buildConfigField(\"String\", \"RELEASE_CHANNEL\", \"\\\"darkaxt-side-by-side\\\"\")"))
        assertTrue(appGradle.contains("buildConfigField(\"boolean\", \"OFFICIAL_UPDATER_ENABLED\", \"false\")"))
        assertTrue(appGradle.contains("buildConfigField(\"boolean\", \"OFFICIAL_ANALYTICS_ENABLED\", \"false\")"))
        assertTrue(appGradle.contains("buildConfigField(\"String\", \"PUBLIC_INSTALL_DIR_NAME\", \"\\\"GameNative-Darkaxt\\\"\")"))
        assertTrue(featureGradle.contains("create(\"releaseDarkaxt\")"))
        assertTrue(manifest.contains("${'$'}{applicationId}.LAUNCH_GAME"))
        assertTrue(manifest.contains("${'$'}{internalDeepLinkHost}"))
        assertTrue(manifest.contains("${'$'}{icon}"))
        assertTrue(manifest.contains("${'$'}{roundIcon}"))
        assertTrue(manifest.contains("${'$'}{altIcon}"))
        assertTrue(manifest.contains("android:scheme=\"nxm\""))
    }

    @Test
    fun launcherPackageIsNotClassifiedAsGameSoSamsungDoesNotHideIt() {
        val manifest = File(repositoryRoot(), "app/src/main/AndroidManifest.xml").readText()

        assertFalse(manifest.contains("android:isGame=\"true\""))
        assertFalse(manifest.contains("android:appCategory=\"game\""))
        assertTrue(manifest.contains("android.intent.category.LAUNCHER"))
    }

    private fun repositoryRoot(): File = generateSequence(
        File(checkNotNull(System.getProperty("user.dir"))),
    ) { it.parentFile }.first { File(it, "app/src/main").isDirectory }
}
