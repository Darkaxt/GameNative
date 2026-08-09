# Native Discussions Design Cross-Check

**Completed:** 2026-08-09  
**Design:** `docs/superpowers/specs/2026-08-08-steam-resolution-community-visible-core-design.md`

## Verified requirements

- [x] Community transport accepts only the configured HTTPS host/port, trusted-AppID listing/forum/thread paths, and bounded `ctp` pagination. Cross-AppID routes/redirects fail closed.
- [x] Requests use no cookies, session, WebView, HTTP cache, or authenticated scraping; redirects are manually revalidated and bodies are capped at 1 MiB.
- [x] Pinned Jsoup 1.23.1 performs HTML parsing and its MIT notice is recorded.
- [x] Listing rows retain only sanitized title, public counts/activity, and validated relative routes. Thread pages retain only bounded plain-text posts and validated pagination.
- [x] Usernames, SteamIDs, author/profile elements, scripts, arbitrary links, images, embeds, and quote markup are removed; posts render under the fixed Steam user label.
- [x] Listing and thread pages are capped at 50 parsed items; ViewModel pagination is loop-guarded and capped at five pages/100 retained items.
- [x] Native list/thread views expose loading, content, empty, offline, unavailable, retained-content failure, Refresh, automatic near-bottom pagination, and explicit Load more.
- [x] Back and controller B return from thread to listing before detail returns to the library.
- [x] Open Community and Open Thread remain explicit external boundaries.

## Focused correction

Live public-layout inspection returned HTTP 200 and confirmed listing selectors. It also proved the opening post uses `.forum_op > .content`, not the synthetic `.forum_post_text` selector. The parser and fixture were corrected before this gate; reply pages retain `.commentthread_comment_text` support. No live titles, bodies, usernames, IDs, or routes were persisted.

The first UI draft exposed only automatic bottom pagination. Explicit Load more controls were added for accessibility/controller use while retaining the requested lazy-loading behavior.

## Named carryovers

- **C1 — typed community diagnostics:** shared with the Reviews cross-check; owned by Stage 5 with the fixed-field/privacy acceptance condition recorded there.
- **C2 — translations and full focus/accessibility sweep:** shared with Reviews and owned by Stage 5.
- **C5 — categories, search, and additional public layouts:** Stage 5 community completion. Add only fixture-proven routes/layouts; unsupported/authenticated actions continue to open Steam externally.
- **C6 — additional discussion statistics:** Stage 5 community completion. Reply counts and last activity are present now; view count renders when the public layout exposes a fixture-proven value.

## Verification evidence

Focused provider/ViewModel tests passed in both Legacy and Modern variants. Legacy Android-test Kotlin compilation passed with native listing/thread, back-to-list, external fallback, and bottom-trigger pagination coverage. A bounded live layout check confirmed 15 listing rows and current opening-post/reply selectors without exporting community content. Physical execution remains part of the single consolidated release/device pass and is not represented as completed here.
