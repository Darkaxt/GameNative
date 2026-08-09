# Native Reviews Design Cross-Check

**Completed:** 2026-08-09  
**Design:** `docs/superpowers/specs/2026-08-08-steam-resolution-community-visible-core-design.md`

## Verified requirements

- [x] AppReviews transport is bounded to 1 MiB, 20 cards per page, fixed errors, no cookies, no HTTP cache, and `Cache-Control: no-store`.
- [x] SteamIDs, recommendation IDs, usernames, and profile data are discarded. Review and developer-response bodies exist only in process memory.
- [x] Helpful/Recent, polarity, language, and purchase filters are native controls.
- [x] Loading, content, loading-more, empty, offline, unavailable, and retained-content refresh-failure states are visible.
- [x] Native plain-text cards expose recommendation, playtime, date, public vote/comment counts, disclosure flags, and developer response.
- [x] Cursor pagination is loop-guarded and bounded to five pages/100 cards. Approaching the bottom loads the next page automatically; an explicit Load more control remains available.
- [x] Changing/closing canonical detail cancels requests and clears in-memory bodies.
- [x] Open Steam Reviews remains available as the external boundary for authenticated or unsupported actions.

## Focused correction

The initial UI draft exposed only automatic bottom pagination. The design also requires an explicit Load more action for accessibility and controller use, so the final implementation provides both paths through the same guarded ViewModel action.

A structural-error fixture then proved that a valid JSON document with the wrong `success` shape could escape as an `IllegalArgumentException`. Parsing now maps malformed JSON and malformed JSON structure to the same fixed `Steam reviews unavailable` error; the regression is green in both variants.

## Named carryovers

- **C1 — typed community diagnostics:** Stage 5 community completion. Add only fixed outcome/reason/count fields and prove body, identity, query, cursor, AppID, and URL rejection at export. No current body or identity reaches Timber/Room/DataStore.
- **C2 — translations and full focus/accessibility sweep:** Stage 5 community completion. Closure requires translated community resources and physical controller/screen-reader evidence; English fallback and natural Compose focus order remain usable now.
- **C3 — public review comments and broader identity presentation:** Stage 5 community completion. Implement only fixture-backed unauthenticated paths; otherwise retain the explicit Steam fallback and obtain user approval before permanent closure.
- **C4 — complete Steam-language mapping:** Stage 5 community completion. Closure requires mapping supported app locales to Steam language parameters with fallback coverage; the visible core uses English for App language and `all` for All languages.

## Verification evidence

Focused provider/ViewModel tests passed in both Legacy and Modern variants. Legacy Android-test Kotlin compilation passed with native review, filters, external fallback, and bottom-trigger pagination coverage. Physical execution remains part of the single consolidated release/device pass and is not represented as completed here.
