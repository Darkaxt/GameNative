package app.gamenative.library.canonical.artwork

import app.gamenative.data.GameSource
import app.gamenative.library.metadata.MetadataClock
import java.io.File
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout

class ArtworkFingerprintCacheTest {
    @get:Rule val timeout = Timeout.seconds(5)
    @get:Rule val temporary = TemporaryFolder()
    private var now = 864_000_000L
    private val clock = MetadataClock { now }

    @Test
    fun validatedPublicFingerprintSurvivesCacheRecreationWithoutStoringOwnership() {
        val file = File(temporary.root, "fingerprints.bin")
        val first = cache(file)
        val fingerprint = ArtworkFixtures.fingerprint(192, 288)
        assertTrue(put(first, url("a"), fingerprint))
        assertEquals(fingerprint, get(cache(file), url("a")))
        val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains("images.gog.com"))
        assertFalse(bytes.contains("accountScope"))
        assertFalse(bytes.contains("stableSourceId"))
    }

    @Test
    fun expiryIsExactAndDoesNotRenewOnRead() {
        val owner = cache()
        val fingerprint = ArtworkFixtures.fingerprint(192, 288)
        assertTrue(put(owner, url("a"), fingerprint))
        now += 604_800_000L - 1
        assertEquals(fingerprint, get(owner, url("a")))
        now++
        assertNull(get(owner, url("a")))
    }

    @Test
    fun aFutureTimestampCannotBecomeFreshAfterClockRollback() {
        val file = File(temporary.root, "fingerprints.bin")
        val owner = cache(file)
        assertTrue(put(owner, url("a"), ArtworkFixtures.fingerprint(192, 288)))
        now--
        assertNull(get(owner, url("a")))
        assertNull(get(cache(file), url("a")))
    }

    @Test
    fun invalidFingerprintDimensionsVersionsAndUnusedBitsAreNotStored() {
        val owner = cache()
        val type = ArtworkBoundaryFixtures.type("ArtworkFingerprint")
        val constructor = type.getConstructor(
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        )
        listOf(
            constructor.newInstance(1, 288, 1L, 1),
            constructor.newInstance(4_096, 4_096, 1L, 1),
            constructor.newInstance(192, 288, 1L, 2),
            constructor.newInstance(192, 288, Long.MIN_VALUE, 1),
        ).forEach { fingerprint -> assertFalse(put(owner, url("a"), fingerprint)) }
        assertNull(get(owner, url("a")))
    }

    @Test
    fun credentialBearingForeignAndWrongAppIdUrlsCannotEnterTheCache() {
        val owner = cache()
        val fingerprint = ArtworkFixtures.fingerprint(192, 288)
        listOf(
            "https://images.gog.com/a.jpg?token=synthetic-secret",
            "https://synthetic-secret@images.gog.com/a.jpg",
            "https://untrusted.invalid/a.jpg",
            "https://shared.akamai.steamstatic.com/steam/apps/42/library_600x900.jpg",
        ).forEach { raw ->
            assertFalse(put(owner, raw, fingerprint))
            assertNull(get(owner, raw))
        }
        val steamUrl = "https://shared.akamai.steamstatic.com/steam/apps/42/library_600x900.jpg"
        assertFalse(put(owner, steamUrl, fingerprint, GameSource.STEAM, 43))
        assertTrue(put(owner, steamUrl, fingerprint, GameSource.STEAM, 42))
        assertEquals(fingerprint, get(owner, steamUrl, GameSource.STEAM, 42))
        assertNull(get(owner, steamUrl, GameSource.STEAM, 43))
    }

    @Test
    fun oldestEntryIsEvictedAtTheBoundAcrossRecreation() {
        val file = File(temporary.root, "fingerprints.bin")
        val owner = cache(file, maximumEntries = 3)
        val fingerprint = ArtworkFixtures.fingerprint(192, 288)
        listOf("a", "b", "c", "d").forEach { name ->
            assertTrue(put(owner, url(name), fingerprint))
            now++
        }
        val recreated = cache(file, maximumEntries = 3)
        assertNull(get(recreated, url("a")))
        listOf("b", "c", "d").forEach { assertEquals(fingerprint, get(recreated, url(it))) }
    }

    @Test
    fun truncatedAndUnsupportedPersistenceAddsNoEvidence() {
        val file = File(temporary.root, "fingerprints.bin")
        val fingerprint = ArtworkFixtures.fingerprint(192, 288)
        assertTrue(put(cache(file), url("a"), fingerprint))
        val valid = file.readBytes()
        file.writeBytes(valid.copyOf(valid.size - 1))
        assertNull(get(cache(file), url("a")))
        file.writeBytes(valid.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
        assertNull(get(cache(file), url("a")))
    }

    @Test
    fun oversizedPersistenceIsRejectedBeforeParsing() {
        val file = File(temporary.root, "fingerprints.bin")
        file.writeBytes(ByteArray(65_537))
        assertNull(get(cache(file), url("a")))
    }

    @Test
    fun failedPersistenceDoesNotPublishAnInMemoryEntry() {
        val directory = temporary.newFolder("not-a-file")
        val owner = cache(directory)
        assertFalse(put(owner, url("a"), ArtworkFixtures.fingerprint(192, 288)))
        assertNull(get(owner, url("a")))
    }

    private fun cache(file: File = File(temporary.root, "fingerprints.bin"), maximumEntries: Int = 512): Any =
        ArtworkBoundaryFixtures.type("ArtworkFingerprintCache")
            .getConstructor(File::class.java, MetadataClock::class.java, Int::class.javaPrimitiveType)
            .newInstance(file, clock, maximumEntries)

    private fun get(owner: Any, raw: String, source: GameSource = GameSource.GOG, appId: Int? = null): Any? =
        invoke(owner, "get", source, raw, appId)

    private fun put(
        owner: Any, raw: String, fingerprint: Any,
        source: GameSource = GameSource.GOG, appId: Int? = null,
    ): Boolean = invoke(owner, "put", source, raw, appId, fingerprint) as Boolean

    private fun invoke(owner: Any, name: String, vararg arguments: Any?): Any? = try {
        owner.javaClass.methods.single { it.name == name && it.parameterCount == arguments.size }
            .invoke(owner, *arguments)
    } catch (failure: InvocationTargetException) {
        throw failure.targetException
    }

    private fun url(name: String) = "https://images.gog.com/$name.jpg"
}
