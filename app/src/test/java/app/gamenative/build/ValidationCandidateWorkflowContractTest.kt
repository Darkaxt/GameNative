package app.gamenative.build

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationCandidateWorkflowContractTest {
    private fun workflow(): String {
        val root = generateSequence(File(checkNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(root, ".github/workflows/app-release-signed.yml").readText()
    }

    @Test
    fun validationIsExactForkOnlyArtifactWithoutPublication() {
        val workflow = workflow()
        val job = workflow.substringAfter("  validation-candidate:")
        assertTrue(workflow.contains("if: \${{ !inputs.validation_candidate }}"))
        assertTrue(job.contains("github.repository == 'Darkaxt/GameNative'"))
        assertTrue(job.contains("github.event_name == 'workflow_dispatch' && inputs.validation_candidate"))
        assertTrue(job.contains("contents: read"))
        assertTrue(job.contains("test \"\$(git rev-parse HEAD)\" = \"\$EXPECTED_COMMIT\""))
        assertTrue(job.contains(":app:assembleLegacyReleaseDarkaxt"))
        assertTrue(job.contains("Verified using v2 scheme (APK Signature Scheme v2): true"))
        assertTrue(job.contains("assert digests == [os.environ['EXPECTED_FORK_CERT_SHA256']]"))
        assertTrue(job.contains("package: name='app.gamenative.darkaxt'"))
        assertTrue(job.contains("schema31_sha256="))
        assertTrue(job.contains("artifact_only=True"))
        assertFalse(job.contains("contents: write"))
        assertFalse(job.contains("git push"))
        assertFalse(job.contains("gh release"))
        assertFalse(job.contains("DISCORD_WEBHOOK"))
    }

    @Test
    fun freshCandidateCheckoutCreatesRequiredPropertiesBeforeGradle() {
        val job = workflow().substringAfter("  validation-candidate:")
        val configure = job.indexOf(": > local.properties")
        val build = job.indexOf(":app:assembleLegacyReleaseDarkaxt")
        assertTrue("Fresh checkouts need the secrets plugin's properties file", configure >= 0)
        assertTrue("Properties must exist before Gradle configures the project", configure < build)
    }

    @Test
    fun candidateR8BudgetUsesOnlyTheDiagnosedSharedHeapIncrease() {
        val job = workflow().substringAfter("  validation-candidate:")
        assertTrue(job.contains("-Dorg.gradle.jvmargs=-Xmx5g"))
        assertTrue(job.contains("--max-workers=2 --no-parallel"))
        assertTrue(job.contains("-Pkotlin.compiler.execution.strategy=in-process"))
        assertFalse(job.contains("-Pkotlin.daemon.jvmargs"))
        assertFalse(job.contains("-Pandroid.enableR8=false"))
    }

    @Test
    fun minifiedCandidateRetainsHashedMappingForClassProvenance() {
        val job = workflow().substringAfter("  validation-candidate:")
        assertTrue(job.contains("app/build/outputs/mapping/legacyReleaseDarkaxt/mapping.txt"))
        assertTrue(job.contains("root / 'mapping.txt'"))
        assertTrue(job.contains("mapping_sha256="))
    }
}
