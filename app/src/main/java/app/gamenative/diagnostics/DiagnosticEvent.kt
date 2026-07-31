package app.gamenative.diagnostics

import kotlinx.serialization.Serializable

@Serializable
data class DiagnosticEvent(
    val timestampEpochMs: Long,
    val sessionId: String,
    val area: DiagnosticArea,
    val name: DiagnosticEventName,
    val outcome: DiagnosticOutcome,
    val durationMs: Long? = null,
    val attributes: Map<String, String> = emptyMap(),
)

@Serializable
enum class DiagnosticArea {
    APP,
    DATABASE,
    CANONICAL_INDEX,
    MATCHING,
    LIBRARY_FILTER,
    ACTION_ROUTING,
    METADATA,
    FACETS,
    GAME_DETAIL,
    REVIEWS,
    DISCUSSIONS,
}

@Serializable
enum class DiagnosticOutcome {
    STARTED,
    SUCCEEDED,
    FAILED,
    DEFERRED,
    SKIPPED,
    CACHE_HIT,
    STALE,
    UNAVAILABLE,
}

@Serializable
enum class DiagnosticEventName {
    APP_STARTED,
    APP_CRASHED,
    DATABASE_MIGRATION,
    CANONICAL_INDEX_BUILD,
    MATCH_RESOLUTION,
    LIBRARY_FILTER,
    GAME_RESOLUTION,
    ACTION_ROUTE,
    METADATA_FETCH,
    FACET_REFRESH,
    DETAIL_SECTION,
    REVIEW_PAGE,
    DISCUSSION_PAGE,
}

enum class DiagnosticAttribute(val wireName: String) {
    APP_VERSION("app_version"),
    BUILD_FLAVOR("build_flavor"),
    SOURCE("source"),
    OPERATION("operation"),
    SELECTION_POLICY("selection_policy"),
    REASON("reason"),
    ERROR_TYPE("error_type"),
    RESULT_COUNT("result_count"),
    STEAM_COUNT("steam_count"),
    GOG_COUNT("gog_count"),
    EPIC_COUNT("epic_count"),
    AMAZON_COUNT("amazon_count"),
    CUSTOM_COUNT("custom_count"),
    CANONICAL_COUNT("canonical_count"),
    COPY_COUNT("copy_count"),
    MATCH_METHOD("match_method"),
    CONFIDENCE("confidence"),
    PROVIDER("provider"),
    CACHE_STATE("cache_state"),
    HTTP_STATUS("http_status"),
    FILTER_GROUPS("filter_groups"),
    TAG_MODE("tag_mode"),
    POPULARITY_THRESHOLD("popularity_threshold"),
    SECTION("section"),
    CAPABILITY("capability"),
    DB_VERSION("db_version"),
    MIGRATION("migration"),
    CORRELATION_ID("correlation_id"),
}
