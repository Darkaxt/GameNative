package app.gamenative.library.canonical.artwork

import app.gamenative.data.GameSource
import app.gamenative.library.metadata.MetadataClock
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout

class CachedArtworkFingerprintSourceTest {
    @get:Rule val timeout = Timeout.seconds(5)
    @get:Rule val temporary = TemporaryFolder()
    private var now = 864_000_000L
    private var calls = 0
    private val clock = MetadataClock { now }
    private val originalUrl = "https://images.gog.com/synthetic-cover.webp"
    private val candidateUrl = "https://shared.akamai.steamstatic.com/steam/apps/42/library_600x900.jpg"

    @Test
    fun successfulOriginalFetchIsReusedAcrossSourceRecreation() = runBlocking {
        val fingerprint = ArtworkFixtures.fingerprint(192, 288)
        val file = File(temporary.root, "fingerprints.bin")
        val first = cached(file) { fingerprint }
        assertEquals(fingerprint, original(first, originalUrl))
        assertEquals(fingerprint, original(first, originalUrl))
        val recreated = cached(file) { error("Fresh public cache must avoid network work") }
        assertEquals(fingerprint, original(recreated, originalUrl))
        assertEquals(1, calls)
    }

    @Test
    fun independentCandidateFetchIsBoundToItsOwnAppIdAndReused() = runBlocking {
        val fingerprint = ArtworkFixtures.fingerprint(192, 288)
        val owner = cached { fingerprint }
        assertEquals(fingerprint, candidate(owner, 42, candidateUrl))
        assertEquals(fingerprint, candidate(owner, 42, candidateUrl))
        assertNull(candidate(owner, 43, candidateUrl))
        assertEquals(1, calls)
    }

    @Test
    fun displayedSteamArtworkCannotBecomeAnOriginalGogFingerprint() = runBlocking {
        val fingerprint = ArtworkFixtures.fingerprint(192, 288)
        val owner = cached { fingerprint }
        assertEquals(fingerprint, candidate(owner, 42, candidateUrl))
        assertNull(original(owner, candidateUrl))
        assertEquals(1, calls)
    }

    @Test
    fun differentOriginalUrlsCannotReuseEachOthersEvidence() = runBlocking {
        val first = ArtworkFixtures.fingerprint(192, 288)
        val second = ArtworkFixtures.fingerprint(192, 288, distinct = true)
        val otherUrl = "https://images.gog.com/synthetic-other.webp"
        val owner = cached { raw -> if (raw == originalUrl) first else second }
        assertEquals(first, original(owner, originalUrl))
        assertEquals(second, original(owner, otherUrl))
        assertEquals(first, original(owner, originalUrl))
        assertEquals(2, calls)
    }

    @Test
    fun unavailableArtworkAddsNoPersistentEvidenceAndCanRecover() = runBlocking {
        val fingerprint = ArtworkFixtures.fingerprint(192, 288)
        val owner = cached { if (calls == 1) null else fingerprint }
        assertNull(original(owner, originalUrl))
        assertEquals(fingerprint, original(owner, originalUrl))
        assertEquals(fingerprint, original(owner, originalUrl))
        assertEquals(2, calls)
    }

    @Test
    fun expiredArtworkIsFetchedAgainInsteadOfSilentlyRenewed() = runBlocking {
        val fingerprint = ArtworkFixtures.fingerprint(192, 288)
        val owner = cached { fingerprint }
        assertEquals(fingerprint, original(owner, originalUrl))
        now += 604_800_000L
        assertEquals(fingerprint, original(owner, originalUrl))
        assertEquals(2, calls)
    }

    @Test
    fun invalidUrlsAreRejectedBeforeFetchingOrCacheUse() = runBlocking {
        val owner = cached { error("Invalid artwork must not reach provider") }
        listOf(
            "https://images.gog.com/synthetic-cover.webp?token=synthetic-secret",
            "https://synthetic-secret@images.gog.com/synthetic-cover.webp",
            "https://untrusted.invalid/synthetic-cover.webp",
        ).forEach { assertNull(original(owner, it)) }
        assertEquals(0, calls)
    }

    @Test
    fun providerCancellationPropagatesWithoutPublishingEvidence() {
        val file = File(temporary.root, "fingerprints.bin")
        val owner = cached(file) { throw CancellationException("synthetic cancellation") }
        assertThrows(CancellationException::class.java) { runBlocking { original(owner, originalUrl) } }
        val fingerprint = ArtworkFixtures.fingerprint(192, 288)
        val recovered = cached(file) { fingerprint }
        runBlocking { assertEquals(fingerprint, original(recovered, originalUrl)) }
        assertEquals(2, calls)
    }

    @Test
    fun optionalPersistenceFailureDoesNotDiscardFreshProviderEvidence() = runBlocking {
        val fingerprint = ArtworkFixtures.fingerprint(192, 288)
        val owner = cached(temporary.newFolder("not-a-cache-file")) { fingerprint }
        assertEquals(fingerprint, original(owner, originalUrl))
        assertEquals(fingerprint, original(owner, originalUrl))
        assertEquals(2, calls)
    }

    private fun cached(
        file: File = File(temporary.root, "fingerprints.bin"), fetch: (String) -> Any?,
    ): Any {
        val cachedType = ArtworkBoundaryFixtures.type("CachedArtworkFingerprintSource")
        val sourceType = ArtworkBoundaryFixtures.type("ArtworkFingerprintSource")
        val cacheType = ArtworkBoundaryFixtures.type("ArtworkFingerprintCache")
        val cache = cacheType.getConstructor(File::class.java, MetadataClock::class.java, Int::class.javaPrimitiveType)
            .newInstance(file, clock, 512)
        val provider = Proxy.newProxyInstance(sourceType.classLoader, arrayOf(sourceType)) { _, method, arguments ->
            when (method.name) {
                "original", "candidate" -> {
                    calls++
                    fetch(arguments[1] as String)
                }
                "toString" -> "SyntheticArtworkProvider"
                else -> null
            }
        }
        return cachedType.getConstructor(cacheType, sourceType).newInstance(cache, provider)
    }

    private suspend fun original(owner: Any, raw: String): Any? = invoke(owner, "original", GameSource.GOG, raw, null)
    private suspend fun candidate(owner: Any, appId: Int, raw: String): Any? = invoke(owner, "candidate", appId, raw)

    private suspend fun invoke(owner: Any, name: String, vararg arguments: Any?): Any? =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            try {
                owner.javaClass.methods.single { it.name == name && it.parameterCount == arguments.size + 1 }
                    .invoke(owner, *arguments, continuation)
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        }
}
