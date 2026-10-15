package app.gamenative.utils

import com.winlator.core.FileUtils
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.rules.ExternalResource

/** Preserve Android's replacement-rename file semantics in Windows registry fixtures. */
class WindowsRegistryFileRule : ExternalResource() {
    private val windowsHost = System.getProperty("os.name").startsWith("Windows")

    override fun before() {
        if (!windowsHost) return
        mockkStatic(FileUtils::class)
        every { FileUtils.createTempFile(any(), any()) } answers {
            ReplacementFile((callOriginal() as File).path)
        }
    }

    override fun after() {
        if (windowsHost) unmockkStatic(FileUtils::class)
    }

    private class ReplacementFile(path: String) : File(path) {
        override fun renameTo(dest: File): Boolean = try {
            Files.move(toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: IOException) { false }
    }
}
