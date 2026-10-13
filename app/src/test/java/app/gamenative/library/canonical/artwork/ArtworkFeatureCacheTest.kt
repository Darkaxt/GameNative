package app.gamenative.library.canonical.artwork

import app.gamenative.data.GameSource
import app.gamenative.library.metadata.MetadataClock
import java.io.File
import java.io.FileOutputStream
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtworkFeatureCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private var time = 100L
    private val clock = MetadataClock { time }
    private val reference = ArtworkReference(GameSource.GOG, "https://images.gog.com/public-fixture.jpg")
    private fun feature(seed: Long = 1): ArtworkFeatures {
        val random = Random(seed)
        val points = FloatArray(48) { random.nextFloat() }
        val descriptors = ByteArray(24 * 32).also(random::nextBytes)
        return ArtworkFeatures(ArtworkFeatures.hash(descriptors), 300, 200, points, descriptors)
    }

    @Test fun restartRetainsDescriptorsUrlMappingsPositiveNegativePairsAndIndexedMarkers() {
        val folder = temporary.newFolder()
        val first = feature(1)
        val second = feature(2)
        val cache = ArtworkFeatureCache(folder, clock)
        assertTrue(cache.put(reference, first))
        cache.put(reference.copy(url = "https://images.gog.com/second.jpg"), second)
        cache.putComparison(first.contentHash, second.contentHash, true)
        cache.putComparison(first.contentHash, first.contentHash, false)
        cache.markIndexed(first.contentHash)
        assertTrue(cache.flush())
        val restarted = ArtworkFeatureCache(folder, clock)
        assertEquals(first.contentHash, restarted.original(reference)!!.contentHash)
        assertTrue(first.descriptors.contentEquals(restarted.feature(first.contentHash)!!.descriptors))
        assertEquals(true, restarted.comparison(second.contentHash, first.contentHash))
        assertEquals(false, restarted.comparison(first.contentHash, first.contentHash))
        assertTrue(restarted.isIndexed(first.contentHash))
        assertEquals(setOf(listOf(first.contentHash, second.contentHash).sorted().let { it[0] to it[1] }), restarted.acceptedPairs())
    }

    @Test fun expiredUrlsPermitStaleOfflineFallbackButFutureClockEntriesCannotSupplyWarmRelationships() {
        val cache = ArtworkFeatureCache(temporary.newFolder(), clock)
        val image = feature()
        cache.put(reference, image)
        time += ArtworkFeatureCache.URL_MAX_AGE_MS + 1
        assertNull(cache.original(reference))
        assertEquals(image.contentHash, cache.original(reference, freshOnly = false)!!.contentHash)
        time = 0
        assertNull(cache.original(reference, freshOnly = false))
        assertNull("Warm relationships cannot bypass the URL clock fence", cache.contentHash(reference))
    }

    @Test fun changedContentReplacesTheUrlButKeepsReusableImageEvidence() {
        val cache = ArtworkFeatureCache(temporary.newFolder(), clock)
        val before = feature(1)
        val after = feature(2)
        cache.put(reference, before)
        cache.put(reference, after)
        assertEquals(after.contentHash, cache.contentHash(reference))
        assertEquals(setOf(before.contentHash, after.contentHash), cache.featureHashes().toSet())
    }

    @Test fun corruptDescriptorCannotSupplyWarmRelationshipsAndCanBeRepaired() {
        val folder = temporary.newFolder()
        val image = feature()
        ArtworkFeatureCache(folder, clock).apply { put(reference, image); flush() }
        File(folder, "${image.contentHash}.orb").writeBytes(byteArrayOf(1, 2, 3))
        val restarted = ArtworkFeatureCache(folder, clock)
        assertNull(restarted.original(reference))
        assertNull(restarted.contentHash(reference))
        assertTrue(restarted.put(reference, image))
        assertEquals(image.contentHash, restarted.original(reference)!!.contentHash)
    }

    @Test fun partialJournalKeepsTheValidPrefixAndCompactsBeforeAppending() {
        val folder = temporary.newFolder()
        val image = feature()
        ArtworkFeatureCache(folder, clock).apply { put(reference, image); flush() }
        FileOutputStream(File(folder, "evidence.bin"), true).use { it.write(byteArrayOf(2, 0, 100, 1)) }
        val recovered = ArtworkFeatureCache(folder, clock)
        assertEquals(image.contentHash, recovered.original(reference)!!.contentHash)
        assertTrue(recovered.flush())
        val restarted = ArtworkFeatureCache(folder, clock)
        assertEquals(image.contentHash, restarted.original(reference)!!.contentHash)
    }

    @Test fun credentialUrlsAndInvalidFeaturesNeverEnterPersistence() {
        val folder = temporary.newFolder()
        val cache = ArtworkFeatureCache(folder, clock)
        assertFalse(cache.put(reference.copy(url = "https://name:password@images.gog.com/private.jpg"), feature()))
        assertFalse(cache.put(reference, ArtworkFeatures("bad", 64, 64, FloatArray(48), ByteArray(768))))
        assertTrue(cache.featureHashes().isEmpty())
        assertTrue(folder.listFiles().orEmpty().isEmpty())
    }
}
