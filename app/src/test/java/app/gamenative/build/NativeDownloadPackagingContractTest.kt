package app.gamenative.build

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDownloadPackagingContractTest {
    private fun repositoryRoot(): File = generateSequence(File(checkNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "app/src/main").isDirectory }

    @Test
    fun onlyTheAlreadyBuiltDownloadLibraryBypassesAdditionalSymbolStripping() {
        val gradle = File(repositoryRoot(), "app/build.gradle.kts").readText()
        assertTrue(gradle.contains("keepDebugSymbols += \"**/libgndownload.so\""))
        assertFalse(gradle.contains("keepDebugSymbols += \"**/*.so\""))
    }

    @Test
    fun candidateStillRequiresExactCommittedDownloadBytes() {
        val job = File(repositoryRoot(), ".github/workflows/app-release-signed.yml").readText()
            .substringAfter("  validation-candidate:")
        assertTrue(job.contains("assert set(native) == {'lib/arm64-v8a/libgndownload.so', 'lib/armeabi-v7a/libgndownload.so'}"))
        assertTrue(job.contains("assert digest == hashlib.sha256(pathlib.Path('app/src/main/jniLibs', n.removeprefix('lib/')).read_bytes()).hexdigest()"))
    }
}
