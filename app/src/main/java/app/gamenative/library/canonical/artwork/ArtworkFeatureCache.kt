package app.gamenative.library.canonical.artwork

import android.content.Context
import app.gamenative.library.metadata.MetadataClock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

/** App-private, versioned image evidence only. Callers use IO; no copy/account/entitlement data is stored. */
@Singleton
class ArtworkFeatureCache internal constructor(private val directory: File, private val clock: MetadataClock) {
    @Inject constructor(@ApplicationContext context: Context, clock: MetadataClock) :
        this(File(context.filesDir, "canonical-artwork/orb-v1"), clock)

    private data class UrlEntry(val content: String, val fetchedAt: Long)
    private data class FileEntry(val bytes: Long, var accessedAt: Long)
    private val urls = linkedMapOf<String, UrlEntry>()
    private val pairs = linkedMapOf<String, Boolean>()
    private val indexed = linkedSetOf<String>()
    private val files = linkedMapOf<String, FileEntry>()
    private val memory = LinkedHashMap<String, ArtworkFeatures>(64, 0.75f, true)
    private val pending = ByteArrayOutputStream()
    private val lock = Any()
    private var loaded = false
    private var compact = false
    private val journal get() = File(directory, "evidence.bin")

    fun original(reference: ArtworkReference, freshOnly: Boolean = true): ArtworkFeatures? = synchronized(lock) {
        load()
        val key = urlKey(reference) ?: return@synchronized null
        val entry = urls[key] ?: return@synchronized null
        val now = clock.nowEpochMs()
        if (entry.fetchedAt < 0 || now < entry.fetchedAt || (freshOnly && now - entry.fetchedAt > URL_MAX_AGE_MS)) return@synchronized null
        feature(entry.content)
    }

    fun contentHash(reference: ArtworkReference): String? = original(reference, freshOnly = false)?.contentHash

    /** A known invalid replacement cannot resurrect the old URL association after restart. */
    fun invalidate(reference: ArtworkReference) = synchronized(lock) {
        load()
        val key = urlKey(reference) ?: return@synchronized
        if (urls.remove(key) != null) {
            DataOutputStream(pending).apply { writeByte(URL_REMOVED_RECORD); writeUTF(key) }
        }
    }

    fun featureHashes(): List<String> = synchronized(lock) { load(); files.keys.sorted() }
    fun isIndexed(content: String): Boolean = synchronized(lock) { load(); content in indexed }
    fun markIndexed(content: String) = synchronized(lock) {
        load()
        if (ArtworkFeatures.HASH.matches(content) && indexed.add(content)) {
            DataOutputStream(pending).apply { writeByte(INDEXED_RECORD); writeUTF(content) }
        }
    }
    fun acceptedPairs(): Set<Pair<String, String>> = synchronized(lock) {
        load()
        pairs.filterValues { it }.keys.mapTo(linkedSetOf()) { key ->
            val parts = key.split('/'); parts[0] to parts[1]
        }
    }

    fun feature(content: String): ArtworkFeatures? = synchronized(lock) {
        load()
        if (!ArtworkFeatures.HASH.matches(content)) return@synchronized null
        memory[content]?.let { return@synchronized it }
        val file = featureFile(content)
        if (!file.isFile || file.length() !in 1..MAX_FEATURE_FILE_BYTES) return@synchronized null
        val result = try {
            DataInputStream(FileInputStream(file).buffered()).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != ArtworkFeatures.ALGORITHM_VERSION) return@use null
                val width = input.readInt()
                val height = input.readInt()
                val count = input.readInt()
                if (count !in 24..ArtworkFeatures.MAX_FEATURES) return@use null
                val points = FloatArray(count * 2) { input.readFloat() }
                val descriptors = ByteArray(count * ArtworkFeatures.DESCRIPTOR_BYTES).also(input::readFully)
                if (input.read() != -1) return@use null
                ArtworkFeatures(content, width, height, points, descriptors).takeIf(ArtworkFeatures::isValid)
            }
        } catch (_: IOException) { null } catch (_: SecurityException) { null }
        result?.also {
            memory[content] = it
            while (memory.size > MAX_MEMORY_FEATURES) memory.remove(memory.keys.first())
            files[content]?.accessedAt = clock.nowEpochMs()
        }
    }

    fun put(reference: ArtworkReference, features: ArtworkFeatures): Boolean = synchronized(lock) {
        load()
        val key = urlKey(reference) ?: return@synchronized false
        if (!features.isValid()) return@synchronized false
        val now = clock.nowEpochMs()
        if (now < 0) return@synchronized false
        val file = featureFile(features.contentHash)
        if (feature(features.contentHash) == null && !atomic(file) { output ->
            output.writeInt(MAGIC)
            output.writeInt(ArtworkFeatures.ALGORITHM_VERSION)
            output.writeInt(features.width)
            output.writeInt(features.height)
            output.writeInt(features.count)
            features.points.forEach(output::writeFloat)
            output.write(features.descriptors)
        }) return@synchronized false
        files[features.contentHash] = FileEntry(file.length(), now)
        memory[features.contentHash] = features
        while (memory.size > MAX_MEMORY_FEATURES) memory.remove(memory.keys.first())
        urls.remove(key)
        urls[key] = UrlEntry(features.contentHash, now)
        while (urls.size > MAX_URLS) urls.remove(urls.keys.first())
        DataOutputStream(pending).apply {
            writeByte(URL_RECORD); writeUTF(key); writeUTF(features.contentHash); writeLong(now)
        }
        trimFiles()
        if (pending.size() >= 131_072) flush()
        true
    }

    fun comparison(left: String, right: String): Boolean? = synchronized(lock) {
        load()
        pairKey(left, right)?.let(pairs::get)
    }

    fun putComparison(left: String, right: String, accepted: Boolean) = synchronized(lock) {
        load()
        val key = pairKey(left, right) ?: return@synchronized
        if (pairs[key] == accepted) return@synchronized
        pairs.remove(key)
        pairs[key] = accepted
        while (pairs.size > MAX_PAIRS) pairs.remove(pairs.keys.first())
        DataOutputStream(pending).apply { writeByte(PAIR_RECORD); writeUTF(key); writeBoolean(accepted) }
        if (pending.size() >= 131_072) flush()
    }

    /** Append bounded batches; compact only at the size boundary, not once per comparison/library emission. */
    fun flush(): Boolean = synchronized(lock) {
        load()
        if (pending.size() == 0 && !compact) return@synchronized true
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return@synchronized false
            if (compact || journal.length() + pending.size() > MAX_JOURNAL_BYTES || !journal.isFile) {
                if (!atomic(journal) { output ->
                    output.writeInt(MAGIC); output.writeInt(ArtworkFeatures.ALGORITHM_VERSION)
                    urls.forEach { (key, entry) ->
                        output.writeByte(URL_RECORD); output.writeUTF(key); output.writeUTF(entry.content); output.writeLong(entry.fetchedAt)
                    }
                    pairs.forEach { (key, accepted) -> output.writeByte(PAIR_RECORD); output.writeUTF(key); output.writeBoolean(accepted) }
                    indexed.filter { it in files }.forEach { content -> output.writeByte(INDEXED_RECORD); output.writeUTF(content) }
                }) return@synchronized false
            } else {
                FileOutputStream(journal, true).use { output -> pending.writeTo(output); output.fd.sync() }
            }
            pending.reset()
            compact = false
            true
        } catch (_: IOException) { compact = true; false } catch (_: SecurityException) { compact = true; false }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        try {
            directory.listFiles()?.filter { it.name.matches(FEATURE_FILE) && it.isFile && it.length() in 1..MAX_FEATURE_FILE_BYTES }
                ?.forEach { file -> files[file.name.removeSuffix(".orb")] = FileEntry(file.length(), file.lastModified()) }
            if (!journal.isFile) return
            if (journal.length() > MAX_JOURNAL_BYTES) { compact = true; return }
            DataInputStream(FileInputStream(journal).buffered()).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != ArtworkFeatures.ALGORITHM_VERSION) {
                    compact = true; return
                }
                while (true) {
                    when (val kind = input.read()) {
                        -1 -> break
                        URL_RECORD -> {
                            val key = input.readUTF()
                            val content = input.readUTF()
                            val time = input.readLong()
                            if (!ArtworkFeatures.HASH.matches(key) || !ArtworkFeatures.HASH.matches(content) || time < 0) throw IOException()
                            urls.remove(key)
                            urls[key] = UrlEntry(content, time)
                            while (urls.size > MAX_URLS) urls.remove(urls.keys.first())
                        }
                        URL_REMOVED_RECORD -> {
                            val key = input.readUTF()
                            if (!ArtworkFeatures.HASH.matches(key)) throw IOException()
                            urls.remove(key)
                        }
                        PAIR_RECORD -> {
                            val key = input.readUTF()
                            val parts = key.split('/')
                            if (parts.size != 2 || pairKey(parts[0], parts[1]) != key) throw IOException()
                            val flag = input.readUnsignedByte()
                            if (flag !in 0..1) throw IOException()
                            pairs.remove(key)
                            pairs[key] = flag == 1
                            while (pairs.size > MAX_PAIRS) pairs.remove(pairs.keys.first())
                        }
                        INDEXED_RECORD -> {
                            val content = input.readUTF()
                            if (!ArtworkFeatures.HASH.matches(content)) throw IOException()
                            if (content in files) indexed.add(content)
                        }
                        else -> throw IOException("Invalid artwork record $kind")
                    }
                }
            }
        } catch (_: EOFException) { compact = true } catch (_: IOException) { compact = true }
        catch (_: SecurityException) { compact = true }
    }

    private fun trimFiles() {
        var bytes = files.values.sumOf { it.bytes }
        for ((content, entry) in files.entries.sortedWith(compareBy({ it.value.accessedAt }, { it.key }))) {
            if (files.size <= MAX_FILES && bytes <= MAX_DISK_FEATURE_BYTES) break
            if (featureFile(content).delete()) {
                files.remove(content)
                indexed.remove(content)
                memory.remove(content)
                bytes -= entry.bytes
            }
        }
    }

    private fun atomic(file: File, writer: (DataOutputStream) -> Unit): Boolean {
        val temporary = File(directory, "${file.name}.tmp")
        return try {
            if (!directory.isDirectory && !directory.mkdirs()) return false
            FileOutputStream(temporary).use { stream ->
                val output = DataOutputStream(stream.buffered())
                writer(output); output.flush(); stream.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: IOException) { false } catch (_: SecurityException) { false }
        finally { temporary.delete() }
    }

    private fun featureFile(content: String) = File(directory, "$content.orb")
    private fun urlKey(reference: ArtworkReference): String? = ArtworkUrlPolicy.Default
        .groupingUrl(reference.source, reference.url, reference.steamAppId)?.toString()?.toByteArray()?.let(ArtworkFeatures::hash)
    private fun pairKey(left: String, right: String): String? =
        if (!ArtworkFeatures.HASH.matches(left) || !ArtworkFeatures.HASH.matches(right)) null
        else if (left <= right) "$left/$right" else "$right/$left"

    companion object {
        private const val MAGIC = 0x474e4f42
        private const val URL_RECORD = 1
        private const val PAIR_RECORD = 2
        private const val INDEXED_RECORD = 3
        private const val URL_REMOVED_RECORD = 4
        private const val MAX_URLS = 8_192
        private const val MAX_FILES = 8_192
        private const val MAX_PAIRS = 65_536
        private const val MAX_MEMORY_FEATURES = 32
        private const val MAX_FEATURE_FILE_BYTES = 65_536L
        private const val MAX_DISK_FEATURE_BYTES = 268_435_456L
        private const val MAX_JOURNAL_BYTES = 16_777_216L
        const val URL_MAX_AGE_MS = 86_400_000L
        private val FEATURE_FILE = Regex("[a-f0-9]{64}\\.orb")
    }
}
