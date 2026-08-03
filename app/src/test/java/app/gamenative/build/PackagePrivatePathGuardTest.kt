package app.gamenative.build

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PackagePrivatePathGuardTest {
    @Test
    fun productionSourcesContainNoOfficialPrivateRoot() {
        val root = repositoryRoot()
        val forbidden = listOf(
            "/data/data/" + "app.gamenative",
            "/data/user/0/" + "app.gamenative",
        )
        val violations = File(root, "app/src/main").walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java", "c", "cpp", "h", "xml") }
            .flatMap { file ->
                file.readLines().asSequence().mapIndexedNotNull { index, line ->
                    forbidden.firstOrNull(line::contains)?.let {
                        "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1}"
                    }
                }
            }.toList()

        assertTrue("Hard-coded package roots: ${violations.joinToString()}", violations.isEmpty())
    }

    private fun repositoryRoot(): File = generateSequence(
        File(checkNotNull(System.getProperty("user.dir"))),
    ) { it.parentFile }.first { File(it, "app/src/main").isDirectory }
}
