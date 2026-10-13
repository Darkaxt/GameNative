package app.gamenative.library.canonical.artwork

import android.app.Application
import app.gamenative.data.GameSource
import app.gamenative.library.metadata.MetadataClock
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.junit.rules.TimeoutRule

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CachedArtworkFeatureSourceTest {
    @get:Rule val timeout = TimeoutRule.seconds(30)
    @get:Rule val temporary = TemporaryFolder()
    private var now = 100L
    private val clock = MetadataClock { now }
    private val requests = AtomicInteger()
    private var body = bytes("dredge-gog.jpg")
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        requests.incrementAndGet()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody("image/jpeg".toMediaType())).build()
    }.build()
    private val reference = ArtworkReference(GameSource.GOG, "https://images.gog.com/public-fixture.jpg")
    private fun source(cache: ArtworkFeatureCache) = CachedArtworkFeatureSource(
        ArtworkImageProvider(client, ArtworkUrlPolicy.Default), cache, ArtworkFeatureMatcher())
    private fun bytes(name: String) = requireNotNull(javaClass.getResourceAsStream("/artwork/$name")).use { it.readBytes() }
    private fun test(body: suspend () -> Unit) = runTest { withContext(Dispatchers.Default) { body() } }

    @Test fun productionTransportDecoderFeaturesAndRestartReuseTheOriginalWithoutNetwork() = test {
        val folder = temporary.newFolder()
        val cache = ArtworkFeatureCache(folder, clock)
        val first = requireNotNull(source(cache).original(reference))
        assertEquals(ArtworkFeatures.hash(body), first.contentHash)
        assertTrue(cache.flush())
        val restarted = source(ArtworkFeatureCache(folder, clock))
        assertEquals(first.contentHash, restarted.original(reference)!!.contentHash)
        assertEquals(1, requests.get())
        assertEquals(1, folder.listFiles().orEmpty().count { it.extension == "orb" })
    }

    @Test fun realThreeStoreAssetsMatchThroughProductionAcquisitionAndNativeHostDecoding() = test {
        val cache = ArtworkFeatureCache(temporary.newFolder(), clock)
        val source = source(cache)
        val assets = listOf("dredge-gog.jpg", "dredge-epic.jpg", "dredge-steam.jpg").mapIndexed { index, name ->
            body = bytes(name)
            requireNotNull(source.original(reference.copy(url = "https://images.gog.com/public-$index.jpg")))
        }
        val matcher = ArtworkFeatureMatcher()
        for (left in assets.indices) for (right in left + 1 until assets.size) {
            assertTrue("Production decoder pair $left/$right", matcher.compare(assets[left], assets[right]).accepted)
        }
        assertEquals(3, requests.get())
    }

    @Test fun changedValidContentInvalidatesTheUrlWithoutErasingReusableFeatures() = test {
        val cache = ArtworkFeatureCache(temporary.newFolder(), clock)
        val source = source(cache)
        val before = requireNotNull(source.original(reference))
        now += ArtworkFeatureCache.URL_MAX_AGE_MS + 1
        body = bytes("portal2.jpg")
        val after = requireNotNull(source.original(reference))
        assertEquals(ArtworkFeatures.hash(body), after.contentHash)
        assertTrue(before.contentHash != after.contentHash)
        assertTrue(!ArtworkFeatureMatcher().compare(before, after).accepted)
        assertEquals(after.contentHash, cache.contentHash(reference))
        assertEquals(2, requests.get())
    }

    @Test fun changedMalformedArtworkCannotReturnAsAWarmRelationshipAfterRestart() = test {
        val folder = temporary.newFolder()
        val cache = ArtworkFeatureCache(folder, clock)
        val source = source(cache)
        requireNotNull(source.original(reference))
        cache.flush()
        now += ArtworkFeatureCache.URL_MAX_AGE_MS + 1
        body = byteArrayOf(1, 2, 3)
        assertNull(source.original(reference))
        cache.flush()
        assertNull(ArtworkFeatureCache(folder, clock).contentHash(reference))
    }
}
