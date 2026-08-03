package app.gamenative.build

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TestTempIsolationBuildConfigTest {
    @Test
    fun jvmTestTasksUseIsolatedBuildTemporaryDirectories() {
        val buildScript = File(repositoryRoot(), "app/build.gradle.kts").readText()

        assertTrue(buildScript.contains("tasks.withType<Test>().configureEach"))
        assertTrue(buildScript.contains("buildDirectory.dir(\"test-tmp/\$name\")"))
        assertTrue(buildScript.contains("systemProperty(\"java.io.tmpdir\""))
        assertTrue(buildScript.contains("isolatedTmpDir.deleteRecursively()"))
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(workingDirectory) { it.parentFile }
            .first { File(it, "app/build.gradle.kts").isFile }
    }
}
