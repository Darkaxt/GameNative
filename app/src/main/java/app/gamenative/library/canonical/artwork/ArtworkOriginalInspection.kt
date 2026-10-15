package app.gamenative.library.canonical.artwork

internal data class ArtworkOriginalInspection(
    val reference: ArtworkReference,
    val features: ArtworkFeatures?,
    val stage: ArtworkAcquisitionStage,
    val httpStatus: Int? = null,
)
