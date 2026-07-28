package app.gamenative.diagnostics

import java.io.File
import java.io.RandomAccessFile
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DiagnosticLogStore(
    private val directory: File,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val maxFileBytes: Long = 512L * 1024L,
    private val maxFiles: Int = 3,
) {
    init {
        require(maxFileBytes > 0)
        require(maxFiles > 0)
        check(directory.isDirectory || directory.mkdirs()) {
            "Unable to create diagnostic directory"
        }
    }

    @Synchronized
    fun append(event: DiagnosticEvent) {
        val safeEvent = event.copy(
            attributes = DiagnosticRedactor.sanitizePersisted(event.attributes),
        )
        val line = json.encodeToString(safeEvent) + "\n"
        val lineBytes = line.toByteArray(Charsets.UTF_8).size
        val current = file(0)
        val needsSeparator = current.hasUnterminatedFinalRecord()
        val appendBytes = lineBytes + if (needsSeparator) NEWLINE_BYTES else 0
        if (current.exists() && current.length() + appendBytes > maxFileBytes) {
            rotate()
            current.appendText(line, Charsets.UTF_8)
        } else {
            current.appendText(if (needsSeparator) "\n$line" else line, Charsets.UTF_8)
        }
    }

    @Synchronized
    fun recent(limit: Int): List<DiagnosticEvent> {
        if (limit <= 0) return emptyList()
        return (0 until maxFiles)
            .asSequence()
            .map(::file)
            .filter(File::exists)
            .flatMap { it.readLines(Charsets.UTF_8).asReversed().asSequence() }
            .mapNotNull { line -> runCatching { json.decodeFromString<DiagnosticEvent>(line) }.getOrNull() }
            .take(limit)
            .toList()
            .asReversed()
    }

    @Synchronized
    fun clear() {
        directory.listFiles().orEmpty()
            .filter { it.name.startsWith(FILE_PREFIX) }
            .forEach { diagnosticFile ->
                check(diagnosticFile.delete() || !diagnosticFile.exists()) {
                    "Unable to delete diagnostic rotation"
                }
            }
    }

    private fun File.hasUnterminatedFinalRecord(): Boolean {
        if (!isFile || length() == 0L) return false
        return RandomAccessFile(this, "r").use { file ->
            file.seek(file.length() - 1)
            file.read() != '\n'.code
        }
    }

    private fun rotate() {
        val oldest = file(maxFiles - 1)
        check(oldest.delete() || !oldest.exists()) { "Unable to delete oldest diagnostic rotation" }
        for (index in maxFiles - 2 downTo 0) {
            val source = file(index)
            if (source.exists()) {
                val target = file(index + 1)
                if (!source.renameTo(target)) {
                    source.copyTo(target, overwrite = true)
                    check(source.delete()) { "Unable to finalize diagnostic rotation" }
                }
            }
        }
    }

    private fun file(index: Int): File = File(directory, "$FILE_PREFIX.$index.jsonl")

    private companion object {
        const val FILE_PREFIX = "feature-events"
        const val NEWLINE_BYTES = 1
    }
}
