package app.gamenative.data.canonical

enum class CanonicalAppType {
    GAME,
    APPLICATION,
    TOOL,
    DEMO,
    DLC,
    SOUNDTRACK,
    UNKNOWN,
}

enum class ClassificationState {
    CLASSIFIED,
    PARTIALLY_CLASSIFIED,
    UNCLASSIFIED,
}

enum class MatchMethod {
    DIRECT_STEAM,
    STORED_USER_DECISION,
    TRUSTED_DIRECT_MAP,
    EXACT_METADATA,
    STEAM_CATALOG,
    OPTIONAL_RESOLVER,
    FUZZY_CANDIDATE,
    MANUAL,
    UNMATCHED,
}

enum class MatchConfidence {
    VERIFIED,
    HIGH,
    REVIEW_REQUIRED,
    REJECTED,
    UNMATCHED,
}

enum class MatchDecisionSource {
    AUTOMATIC,
    USER,
}
