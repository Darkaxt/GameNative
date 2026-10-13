package app.gamenative.library.canonical.artwork

import java.util.concurrent.ConcurrentHashMap

/** Public originals through the production extractor; no synthetic equality replaces real matching. */
internal object ArtworkFeatureFixtures {
    private val extracted = ConcurrentHashMap<String, ArtworkFeatures>()

    fun artwork(name: String = "dredge-gog.jpg"): ArtworkFeatures = extracted.computeIfAbsent(name) {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/artwork/$name")).use { it.readBytes() }
        val pixels = requireNotNull(ArtworkImageDecoder.featurePixels(bytes, "image/jpeg"))
        requireNotNull(ArtworkFeatureMatcher().extract(pixels.width, pixels.height, pixels.argb, ArtworkFeatures.hash(bytes)))
    }
}
