package app.gamenative.buildcontract

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamWebApiKeyHostContractTest {
    @Test
    fun envExampleContainsOnlyTheEmptySteamKeyAssignment() {
        val example = File(repositoryRoot(), ".env.example")

        assertEquals("STEAM_WEB_API_KEY=\n", example.readText().replace("\r\n", "\n"))
    }

    @Test
    fun androidBuildDoesNotLoadOrCompileTheSteamWebApiKey() {
        val root = repositoryRoot()
        val appBuild = File(root, "app/build.gradle.kts").readText()

        assertFalse(appBuild.contains("STEAM_WEB_API_KEY"))
        assertFalse(appBuild.contains(".env"))
        assertFalse(
            File(root, "app/src").walkTopDown().any {
                it.isFile && it.name.startsWith(".env")
            },
        )
    }

    @Test
    fun smokeHelperSourcesEnvUsesHeaderAuthAndPrintsOnlyAggregates() {
        val helper = File(repositoryRoot(), "tools/steam-web-api-smoke.sh")
        val source = helper.readText()

        assertTrue(source.contains("source \"\$ENV_FILE\""))
        assertTrue(source.contains("x-webapi-key: %s"))
        assertTrue(source.contains("--header \"@\$HEADER_FILE\""))
        assertTrue(source.contains("app_count={len(apps)}"))
        assertTrue(source.contains("status=ok http_status=%s %s"))
        assertFalse(source.contains("set -x"))
        assertFalse(source.contains("?key="))
        assertFalse(source.contains("--data-urlencode 'key="))
        assertFalse(source.contains("cat \"\$BODY_FILE\""))
    }

    @Test
    fun androidKeyStoreCipherLetsTheProviderGenerateEncryptionIvs() {
        val source = File(
            repositoryRoot(),
            "app/src/main/java/app/gamenative/service/steam/AndroidSteamWebApiKeyCipher.kt",
        ).readText()
        val encryptionMethod = source
            .substringAfter("override fun encrypt")
            .substringBefore("override fun decrypt")

        assertFalse(encryptionMethod.contains("SecureRandom"))
        assertTrue(encryptionMethod.contains("init(Cipher.ENCRYPT_MODE, getOrCreateKey())"))
        assertTrue(encryptionMethod.contains("val iv = cipher.iv.copyOf()"))
    }

    @Test
    fun smokeHelperHasValidBashSyntax() {
        val toolsDirectory = File(repositoryRoot(), "tools")
        val process = ProcessBuilder("bash", "-n", "steam-web-api-smoke.sh")
            .directory(toolsDirectory)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }

        assertEquals(output, 0, process.waitFor())
    }

    private fun repositoryRoot(): File = generateSequence(
        File(checkNotNull(System.getProperty("user.dir"))),
    ) { it.parentFile }.first { File(it, "app/src/main").isDirectory }
}
