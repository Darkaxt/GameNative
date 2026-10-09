package app.gamenative.library.canonical.artwork

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.library.metadata.MetadataClock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArtworkFingerprintCache internal constructor(
    private val file: File,
    private val clock: MetadataClock,
    private val maximumEntries: Int,
) {
    private val lock = Any()
    private var entries: Map<String, CachedFingerprint>? = null

    init {
        require(maximumEntries in 1..MAX_ENTRIES)
    }

    @Inject
    constructor(@ApplicationContext context: Context, clock: MetadataClock) : this(
        File(context.cacheDir, "canonical-artwork/fingerprints-v1.bin"), clock, MAX_ENTRIES,
    )

    // Callers keep this small disk cache on their I/O dispatcher, never in Room or projection.
    fun get(source: GameSource, raw: String, steamAppId: Int?): ArtworkFingerprint? {
        val key = key(source, raw, steamAppId) ?: return null
        return synchronized(lock) {
            load()[key]?.takeIf { it.isFresh(clock.nowEpochMs()) }?.fingerprint
        }
    }

    fun put(source: GameSource, raw: String, steamAppId: Int?, fingerprint: ArtworkFingerprint): Boolean {
        val key = key(source, raw, steamAppId) ?: return false
        if (!fingerprint.isValid()) return false
        return synchronized(lock) {
            val now = clock.nowEpochMs()
            if (now < 0L) return@synchronized false
            val updated = bounded(load().filterValues { it.isFresh(now) } + (key to CachedFingerprint(fingerprint, now)))
            if (!persist(updated)) return@synchronized false
            entries = updated
            true
        }
    }

    private fun key(source: GameSource, raw: String, steamAppId: Int?): String? {
        val url = ArtworkUrlPolicy.Default.originalUrl(source, raw, steamAppId) ?: return null
        return MessageDigest.getInstance("SHA-256").digest(url.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun load(): Map<String, CachedFingerprint> {
        entries?.let { return it }
        val loaded = try {
            read()
        } catch (_: IOException) {
            emptyMap()
        } catch (_: SecurityException) {
            emptyMap()
        }
        return bounded(loaded).also { entries = it }
    }

    private fun read(): Map<String, CachedFingerprint> {
        if (!file.isFile || file.length() > MAX_DISK_BYTES) return emptyMap()
        val bytes = ByteArray(MAX_DISK_BYTES + 1)
        val length = FileInputStream(file).use { input ->
            var length = 0
            while (length < bytes.size) {
                val count = input.read(bytes, length, bytes.size - length)
                if (count == -1) break
                length += count
            }
            length
        }
        if (length > MAX_DISK_BYTES) return emptyMap()
        return DataInputStream(ByteArrayInputStream(bytes, 0, length)).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) return emptyMap()
            val count = input.readInt()
            if (count !in 0..MAX_ENTRIES) return emptyMap()
            val result = linkedMapOf<String, CachedFingerprint>()
            val now = clock.nowEpochMs()
            repeat(count) {
                val key = input.readUTF()
                if (key.length != 64 || key.any { it !in '0'..'9' && it !in 'a'..'f' } || key in result) {
                    return emptyMap()
                }
                val timestamp = input.readLong()
                val fingerprint = ArtworkFingerprint(
                    width = input.readInt(), height = input.readInt(), bits = input.readLong(),
                    algorithmVersion = input.readInt(),
                )
                val entry = CachedFingerprint(fingerprint, timestamp)
                if (fingerprint.isValid() && entry.isFresh(now)) result[key] = entry
            }
            if (input.available() != 0) return emptyMap()
            result
        }
    }

    private fun bounded(values: Map<String, CachedFingerprint>): Map<String, CachedFingerprint> = values.entries
        .sortedWith(compareByDescending<Map.Entry<String, CachedFingerprint>> { it.value.fetchedAt }.thenBy { it.key })
        .take(maximumEntries)
        .associate { it.key to it.value }

    private fun persist(values: Map<String, CachedFingerprint>): Boolean {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        return try {
            val parent = file.parentFile ?: return false
            if (!parent.isDirectory && !parent.mkdirs()) return false
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(FORMAT_VERSION)
                output.writeInt(values.size)
                values.toSortedMap().forEach { (key, entry) ->
                    output.writeUTF(key)
                    output.writeLong(entry.fetchedAt)
                    output.writeInt(entry.fingerprint.width)
                    output.writeInt(entry.fingerprint.height)
                    output.writeLong(entry.fingerprint.bits)
                    output.writeInt(entry.fingerprint.algorithmVersion)
                }
            }
            if (buffer.size() > MAX_DISK_BYTES) return false
            FileOutputStream(temporary).use { output ->
                output.write(buffer.toByteArray())
                output.flush()
                output.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } finally {
            temporary.delete()
        }
    }

    private data class CachedFingerprint(val fingerprint: ArtworkFingerprint, val fetchedAt: Long) {
        fun isFresh(now: Long): Boolean = fetchedAt >= 0L && now >= fetchedAt && now - fetchedAt < TTL_MS
    }

    private companion object {
        const val MAGIC = 0x474E4146
        const val FORMAT_VERSION = 1
        const val MAX_ENTRIES = 512
        const val MAX_DISK_BYTES = 65_536
        const val TTL_MS = 604_800_000L
    }
}
