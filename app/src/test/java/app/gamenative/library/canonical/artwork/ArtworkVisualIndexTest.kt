package app.gamenative.library.canonical.artwork

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, manifest = Config.NONE, sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkVisualIndexTest {
    @get:Rule val timeout = Timeout.seconds(30)

    @Test fun smallLibrariesDoNotNeedAnyCoarseVisualAdmission() {
        val index = ArtworkVisualIndex()
        val images = (0L..10L).map(::noise)
        images.forEach(index::add)
        assertEquals(images.drop(1).map { it.contentHash }.sorted(), index.candidates(images.first()))
    }

    @Test fun lowResolutionDredgePairsRemainCandidatesAmongMetadataIndependentNoise() {
        val gog = ArtworkFeatureFixtures.artwork("dredge-gog.jpg")
        val epic = ArtworkFeatureFixtures.artwork("dredge-epic.jpg")
        val steam = ArtworkFeatureFixtures.artwork("dredge-steam.jpg")
        val distractors = (0L..255L).map(::noise)
        for ((query, original) in listOf(gog to epic, epic to gog, gog to steam, steam to gog)) {
            val index = ArtworkVisualIndex()
            (distractors + original).forEach(index::add)
            val candidates = index.candidates(query)
            assertTrue("Actual art pair ${query.contentHash}/${original.contentHash} must survive visual retrieval", original.contentHash in candidates)
            assertTrue(candidates.size <= ArtworkVisualIndex.MAX_CANDIDATES)
        }
    }

    @Test fun reorderAndUncompactedIncrementalAdditionsGiveTheSameShortlist() {
        val images = (0L..100L).map(::noise)
        val forward = ArtworkVisualIndex().apply { images.forEach(::add) }
        val reverse = ArtworkVisualIndex().apply { images.reversed().forEach(::add) }
        assertEquals(forward.candidates(images.first()), reverse.candidates(images.first()))
        forward.add(images.first())
        assertEquals(reverse.candidates(images.first()), forward.candidates(images.first()))
    }

    private fun noise(seed: Long): ArtworkFeatures {
        val random = Random(seed)
        val points = FloatArray(ArtworkFeatures.MAX_FEATURES * 2) { random.nextFloat() }
        val bytes = ByteArray(ArtworkFeatures.MAX_FEATURES * 32).also(random::nextBytes)
        return ArtworkFeatures(ArtworkFeatures.hash(bytes), 600, 360, points, bytes)
    }
}
