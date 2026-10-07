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
        // These host-only normalization fixtures are not linked by either native build.
        val overlayHostFixtures = File(root, "app/src/main/cpp/gnoverlay/tests")
        val violations = File(root, "app/src/main").walkTopDown()
            .onEnter { it != overlayHostFixtures }
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

    @Test
    fun excludedOverlayFixturesRemainHostOnly() {
        val overlay = File(repositoryRoot(), "app/src/main/cpp/gnoverlay")
        assertTrue(File(overlay, "tests/test_core.c").isFile)
        listOf("CMakeLists.txt", "build.sh").forEach { name ->
            val build = File(overlay, name).readText()
            assertTrue("$name must enumerate production sources", build.contains("gnoverlay_core.c"))
            assertTrue("$name must enumerate production hooks", build.contains("gnoverlay_hooks.c"))
            assertTrue("$name must not link host fixtures", !build.contains("test_core.c") && !build.contains("tests/"))
            assertTrue("$name must not recursively include host fixtures", !build.contains("GLOB_RECURSE"))
        }
    }

    private fun repositoryRoot(): File = generateSequence(
        File(checkNotNull(System.getProperty("user.dir"))),
    ) { it.parentFile }.first { File(it, "app/src/main").isDirectory }
}
