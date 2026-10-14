package app.gamenative.library.canonical.artwork

import app.gamenative.diagnostics.DiagnosticArea
import app.gamenative.diagnostics.DiagnosticAttribute
import app.gamenative.diagnostics.DiagnosticEventName
import app.gamenative.diagnostics.DiagnosticOutcome
import app.gamenative.diagnostics.FeatureDiagnostics

internal enum class ArtworkAcquisitionStage {
    FETCHING, HTTP_UNAVAILABLE, TRANSPORT_UNAVAILABLE, INVALID_RESPONSE, REDIRECT_REJECTED,
    TIMEOUT, CANCELLED, BYTES_READY, DESCRIPTOR_CACHE_HIT, STALE_CACHE_FALLBACK, SIGNATURE_REJECTED,
    DECODE_UNAVAILABLE, EXTRACTOR_UNAVAILABLE, FEATURES_READY,
}

internal fun recordArtworkAcquisition(reference: ArtworkReference, stage: ArtworkAcquisitionStage,
    content: String? = null, httpStatus: Int? = null) {
    if (ArtworkUrlPolicy.Default.groupingUrl(reference.source, reference.url, reference.steamAppId) == null) return
    FeatureDiagnostics.record(DiagnosticArea.MATCHING, DiagnosticEventName.GAME_RESOLUTION,
        when (stage) {
            ArtworkAcquisitionStage.FETCHING -> DiagnosticOutcome.STARTED
            ArtworkAcquisitionStage.CANCELLED -> DiagnosticOutcome.SKIPPED
            ArtworkAcquisitionStage.DESCRIPTOR_CACHE_HIT, ArtworkAcquisitionStage.STALE_CACHE_FALLBACK -> DiagnosticOutcome.CACHE_HIT
            ArtworkAcquisitionStage.BYTES_READY, ArtworkAcquisitionStage.FEATURES_READY -> DiagnosticOutcome.SUCCEEDED
            else -> DiagnosticOutcome.UNAVAILABLE
        }, attributes = buildMap {
            put(DiagnosticAttribute.OPERATION, "ARTWORK_ORIGINAL")
            put(DiagnosticAttribute.SOURCE, reference.source.name)
            put(DiagnosticAttribute.PUBLIC_URL, reference.url)
            val urlId = ArtworkFeatures.hash(reference.url.toByteArray()).take(16)
            put(DiagnosticAttribute.CORRELATION_ID, urlId)
            put(DiagnosticAttribute.PUBLIC_URL_ID, urlId)
            put(DiagnosticAttribute.STAGE, stage.name)
            put(DiagnosticAttribute.REASON, stage.name)
            if (content != null && ArtworkFeatures.HASH.matches(content)) put(DiagnosticAttribute.PUBLIC_CONTENT_ID, content.take(16))
            httpStatus?.let { put(DiagnosticAttribute.HTTP_STATUS, it.toString()) }
        })
}
