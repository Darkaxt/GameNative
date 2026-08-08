# Steam Resolution and Native Community Visible-Core Design Specification

**Status:** Approved product direction; implementation awaits review of this focused specification
**Date:** 2026-08-08
**Base checkpoint:** `8311ec59` (`1.1.3-rc4`, schema 27)
**Extends:** `docs/superpowers/specs/2026-07-27-steam-normalized-library-design.md`

## 1. Summary

GameNative will complete the missing visible core in this strict order:

1. Resolve non-Steam-owned games to authoritative Steam catalog entries automatically as a best effort, then provide a visible **Fix Steam match** flow for ambiguous or incorrect results.
2. Replace the Reviews placeholder with native, cursor-paginated Steam review browsing.
3. Replace the Discussions placeholder with native public discussion listings and readable threads.
4. Only after those three features ship, address external-storage hardening and consider a safe manual LSFG import.

The resolver must not turn a roughly 500-game non-Steam collection into 500 manual chores. Automatic catalog search and conservative multi-field validation do the common work. Manual selection is the correction path, not the indexing strategy.

The owning storefront remains authoritative for entitlement, installation, launch, update, uninstall, saves, containers, and source-specific actions. Steam identity is presentation and discovery metadata only.

## 2. Product outcomes

The design succeeds when the user can:

- open a GOG, Epic, Amazon, or custom game and receive Steam descriptions, media, genres, features, review-count popularity, and other available Steam presentation data when a credible match exists;
- see automatic matching progress and coverage across the whole canonical library, not only the Steam tab;
- correct a wrong or missing match by searching Steam, comparing candidates, selecting the right entry, rejecting a proposed match, keeping a game separate, or resetting to automation;
- browse Steam reviews natively with useful filters and pagination;
- browse public Steam discussion listings and read supported threads natively;
- fall back to Steam externally for unsupported or authenticated community actions;
- disable canonical presentation through the existing recovery switch without compromising source-native actions.

## 3. Scope and non-goals

### 3.1 Included

- Keyless, targeted public Steam Store search for non-Steam canonical games without a trusted Steam AppID.
- Strict candidate validation against normalized title, meaningful edition tokens, app type, developer, and release year.
- Automatic acceptance of one unambiguous high-confidence candidate.
- Native correction and provenance UI.
- Automatic handoff from a newly accepted Steam identity to existing metadata and review-count enrichment.
- Best-effort public PICS facet enrichment for a trusted non-owned AppID while a Steam client session is active.
- Native Reviews and Discussions tabs using process-memory-only user-generated content.
- One focused design cross-check and at most one consolidated blocker correction after each core deliverable.
- One signed fork RC after each core deliverable.

### 3.2 Deferred

- A full local index of the global Steam AppList or Room FTS catalog.
- WorkManager/process-death catalog resolution.
- Automatic fuzzy or edition-ambiguous merging.
- Treating unsigned GOG maps, indirect Epic→GOG→Steam joins, SteamGridDB, autocomplete order, or first search result as authoritative.
- Persistent review bodies, discussion bodies, usernames, profiles, avatars, SteamIDs, account IDs, or community HTML.
- Authenticated review/discussion posting, comments, voting, reporting, moderation, or copied Steam cookies.
- WebView-based community browsing.
- Full community search and every Steam forum category/layout.
- External-storage changes and LSFG work until the three visible core deliverables ship.
- A broad redesign of the library/detail architecture.

## 4. The 80/20 and rewrite rule

Every touched subsystem is classified before implementation and again during that deliverable's cross-check.

| Classification | Rule |
|---|---|
| **Reuse** | The current boundary already preserves the required semantics and has owning evidence. Use it unchanged or through a thin adapter. |
| **Narrow repair now** | The active feature touches the seam and a bounded correction is required for visible behavior, identity safety, URL/content safety, diagnostics privacy, source-action safety, or reliable recovery. |
| **Rewrite now** | The existing boundary structurally violates the active contract and wrapping it would retain the defect. Rewrite only that boundary. |
| **Defer** | The issue belongs to a later named deliverable or final hardening, and it neither blocks the current visible path nor creates a Critical/High safety or privacy defect. Record it once and move forward. |

A rewrite is not justified by style, test difficulty, age, file size alone, or an opportunity to make the architecture ideal. A rewrite is justified when the old implementation cannot safely implement the current feature.

### 4.1 Current classification

| Existing area | Classification | Decision |
|---|---|---|
| Canonical IDs, `OwnedCopyKey`, schema-27 canonical entities | Reuse | Preserve identity and source-action separation. |
| `CanonicalGameResolver` normalization and confidence rules | Narrow repair | Feed it validated catalog evidence; do not replace its local deterministic rules. |
| `CanonicalProjectionCoordinator` | Reuse | Keep local projection network-free and transactional. |
| `RoomCanonicalMutationRepository` | Narrow repair | Add guarded automatic/manual confirm, reject, candidate, and reset operations. |
| `GogRecommendationsRepository.searchSteamAppId` | Rewrite if reused | It is recommendation-specific, first-result-oriented, hard-coded, and may log forbidden context. New catalog transport is required. |
| `GogMapRepository` as authority | Defer | It may provide a review-only hint after separate validation, never an automatic identity. |
| Steam `appdetails` metadata provider | Narrow repair | Reuse parsing/security; expose candidate app type/year evidence and persist accepted metadata. |
| PICS association parsing | Narrow repair | Add a session-bound public facet façade for trusted non-owned AppIDs; do not insert false ownership. |
| Popularity threshold and sort | Reuse | They are already source-agnostic once a canonical has a Steam AppID/count. |
| Detail screen shell | Narrow repair | Keep the shell; restore exactly four tabs and replace only placeholder branches. |
| Aggregate review-count provider | Reuse | Keep it separate from full review browsing. |
| Review browsing | New boundary | No production implementation exists. |
| Discussion browsing | New boundary | No production implementation exists. |
| Community persistence | Do not implement | It conflicts with the mandatory privacy contract. |
| External-storage mover | Defer | Important risks exist, but it does not block the three visible core features. |

## 5. Shared invariants

### 5.1 Identity and action authority

- `CanonicalGameId` groups presentation only.
- A trusted positive Steam AppID identifies Steam catalog content only.
- Every executable action continues to capture and revalidate an exact `OwnedCopyKey` and source-native provider identity.
- Catalog resolution never creates Steam ownership, a Steam license, an executable Steam `LibraryItem`, or a Steam action capability.
- A match correction cannot silently switch an already-invoked install/play/update/uninstall action.
- Existing Steam-owned direct identities cannot be detached or overwritten by non-Steam automation.

### 5.2 Manual decisions outrank automation

- User confirmation is `VERIFIED` and sticky.
- User rejection or **Keep separate** remains sticky until explicit Reset.
- Reset clears the user decision and immediately makes the copy eligible for a fresh automatic/manual search.
- Automatic resolution cannot overwrite any current user decision.
- A stale UI decision fails closed if its canonical, copy-presence, candidate, or decision revision changed.

### 5.3 Privacy contract

Do not persist or export:

- tokens;
- account IDs or SteamIDs;
- usernames;
- game titles or match-candidate titles in diagnostics;
- search text;
- install paths;
- full URLs;
- review bodies;
- discussion bodies.

Search and public community content may exist only in active process memory and network request/response buffers. They must not enter Room, DataStore, `SavedStateHandle`, `rememberSaveable`, WorkManager input/output, OkHttp disk cache, Coil disk cache, Timber context, crash messages, or diagnostic attributes.

Typed diagnostics may contain fixed categories, counts, durations, source names, outcomes, fixed reason codes, HTTP status, exception class, and short hashed correlations only.

## 6. Deliverable 1 — automatic and correctable Steam resolution

### 6.1 Components

#### `SteamCatalogSearchProvider`

A dedicated keyless provider calls the public Steam Store search endpoint. It does not reuse the recommendation repository.

Contract:

- HTTPS only;
- exact allowlisted Steam Store host and port 443;
- endpoint-bound path and query keys;
- redirects disabled in OkHttp and manually revalidated at every hop and final effective URL;
- no cookies or Steam session material;
- `Cache-Control: no-store`;
- bounded response/decompression size;
- provider-owned timeout and cancellation;
- locale and country from the existing `MetadataLocale` boundary;
- at most ten parsed positive AppID results;
- no title, query, AppID, URL, or body logging.

Automatic search sends the source title to Steam because Steam is the selected authoritative catalog provider. The title must not be retained after the request or exposed through diagnostics.

#### `SteamCatalogCandidatePolicy`

A pure policy compares source evidence with bounded Store-search results validated through Steam `appdetails`.

Inputs:

- source title and edition-preserving normalized title key;
- source developer key when known;
- source release year when known;
- source app type;
- candidate AppID, title/title key, developer key, release year, and app type.

Outputs:

- `AutoAccept(candidate)`;
- `ReviewRequired(candidates)`;
- `Unmatched`.

#### `SteamCatalogResolutionRepository`

This repository performs network work outside Room transactions, validates candidate details, then invokes one guarded mutation transaction. It never calls network code from `CanonicalGameResolver` or `CanonicalProjectionEngine`.

It exposes:

- one bounded automatic library scan;
- manual search that ignores the automatic-scan cooldown;
- progress and aggregate coverage;
- retry;
- candidate comparison state;
- confirm, reject/keep-separate, and reset.

#### Guarded canonical mutations

The mutation repository gains guarded operations for:

- recording a review-required candidate without merging;
- accepting an automatic `HIGH` candidate;
- confirming a manual `VERIFIED` candidate;
- rejecting the current candidate or current Steam association;
- resetting a user decision.

Every transaction revalidates:

- exact `OwnedCopyKey`;
- `isPresent`;
- expected canonical ID;
- expected match method/confidence/decision source;
- expected candidate and resolver version;
- expected decision revision;
- positive candidate AppID;
- direct-Steam immutability;
- current target-canonical compatibility.

### 6.2 Automatic data flow

1. Wait for canonical projection readiness and canonical public-library enablement.
2. Select present non-Steam canonicals with no trusted Steam AppID and no sticky user decision.
3. Choose the present copy with the strongest evidence: known compatible type, developer, then release year. Search once per canonical, not once per copy.
4. Preserve existing local exact canonical matching as the first automatic path.
5. Query Steam Store search for unresolved canonicals with concurrency two.
6. Keep at most ten search results and validate at most five plausible title candidates through `appdetails`.
7. Apply the policy.
8. Commit only the final fixed-category result in one guarded Room transaction.
9. Publish progress and continue after per-game failures.
10. Stop promptly when the owning scope is cancelled. Accepted decisions already committed remain valid; a later run naturally resumes unresolved games.

The 80/20 release uses no new Room table. Automatic scanning runs once per process session and may use only a fixed global last-success timestamp/resolver version in DataStore to avoid immediate repeat scans. Candidate lists and search strings remain memory-only. A later WorkManager/schema design is justified only if live evidence shows foreground resume is inadequate.

### 6.3 Automatic acceptance policy

A candidate is accepted automatically only when all of these are true:

1. Exactly one candidate survives validation.
2. Source and candidate title keys are exactly equal after edition-preserving normalization.
3. App types are known and compatible.
4. At least one corroborator is positive:
   - normalized developer equality; or
   - both release years are known and differ by at most one.
5. There is no developer conflict, large year conflict, or competing equally eligible candidate.
6. No sticky user rejection/confirmation exists.

Missing corroboration, unknown app type, conflicting editions, fuzzy/base-title similarity, indirect maps, or multiple eligible candidates produce `REVIEW_REQUIRED`, never an automatic merge.

No result produces `UNMATCHED`. The game remains fully usable with source metadata.

### 6.4 Manual correction experience

A visible **Steam match** provenance block appears in canonical Details and the Copies flow. It shows a fixed status—Automatic, User confirmed, Needs review, Rejected/kept separate, Unmatched, or Checking—and never exposes private diagnostic material.

**Fix Steam match** opens a native picker:

- the source title is prefilled in composition memory only;
- the user can edit the title or enter a positive Steam AppID;
- search always reruns against Steam and is not limited by automatic cooldown;
- candidate cards show Steam artwork, title, developer, release year, and app type;
- the current association is identified;
- selection requires explicit confirmation;
- **Keep separate** rejects the current association;
- **Reset to automatic** clears the sticky user decision and immediately reruns resolution;
- gamepad Back/cancel never mutates the match.

When a canonical contains several mutable non-Steam copies, the flow first asks which owned copy/evidence is being corrected. Direct Steam copies are shown as immutable.

### 6.5 Metadata, facets, and popularity handoff

After a verified/high Steam AppID is accepted:

1. Persist already-validated sanitized `appdetails` metadata through `GameMetadataRepository`.
2. Make Steam metadata the canonical presentation preference without creating Steam ownership.
3. Refresh canonical Steam genres and feature/category facets with provider-aware precedence so a later non-Steam projection cannot erase fresher Steam-derived presentation facets.
4. Submit the canonical to the existing review-count popularity enricher.
5. If a Steam client session is active, request public PICS product info for that trusted AppID through a new session-bound façade and persist only canonical genre/category/store-tag associations.
6. If PICS is unavailable, retain `appdetails` genres/features, leave tag membership unknown, and do not scrape store HTML as a fallback.
7. Store unknown tag IDs but hide them until the localized tag dictionary has labels.

The PICS request must not insert a non-owned game as an owned Steam entitlement. Existing `steam_app` ownership/depot storage remains non-authoritative for non-owned catalog records.

### 6.6 Progress and coverage

The library Options panel exposes a Steam-resolution section with:

- resolved/eligible canonical count;
- needs-review count;
- unmatched count;
- completed/total/failed progress while scanning;
- **Review matches** and **Retry** actions.

Counts are canonical and source-agnostic. They do not disappear outside the Steam tab. A threshold-active popularity view must admit a GOG/Epic/Amazon-only card when that canonical has a trusted Steam AppID and sufficient review count.

### 6.7 Resolver failure behavior

- One provider failure does not cancel other games.
- Cancellation is propagated and never diagnosed as failure.
- Rate limiting/backoff is bounded; no infinite retry.
- Cached accepted/user decisions remain usable offline.
- Search unavailable leaves the game unmatched and exposes Retry/Fix match.
- A stale mutation returns `EXPECTED_STATE_CHANGED`; it never applies to a sibling or newer decision.
- Automatic resolution failure never disables the canonical library or source actions.

## 7. Deliverable 2 — native Reviews

### 7.1 Boundary

Reviews use a new isolated `app.gamenative.library.community` package. They do not reuse the aggregate popularity DTO and do not enter `GameDetailSnapshotEntity`.

Core models are non-serializable:

- `SteamReviewQuery`;
- `SteamReviewSort` (`HELPFUL`, `RECENT`);
- `SteamReviewPolarity` (`ALL`, `POSITIVE`, `NEGATIVE`);
- `SteamReviewLanguage` (`APP_LANGUAGE`, `ALL`);
- `SteamReviewPurchaseType` (`ALL`, `STEAM`);
- `SteamReviewCard`;
- `SteamReviewPage` with an opaque next cursor;
- typed loading/content/empty/offline/error/loading-more states.

### 7.2 Transport and parsing

A dedicated community transport is built from the shared client with:

- disk cache disabled explicitly;
- `CookieJar.NO_COOKIES`;
- automatic redirects disabled;
- `Cache-Control: no-store` requests;
- strict `store.steampowered.com/appreviews/{trustedAppId}` path binding;
- approved query keys only;
- final effective URL and every redirect revalidated;
- bounded body, page count, cursor length, review count, review text, and developer-response text;
- cancellation propagation;
- fixed safe errors with no response/body text.

The provider parses only:

- recommended/not recommended;
- sanitized review text;
- playtime;
- helpful/funny/comment counts;
- posted/updated time;
- purchase/free/Early Access flags;
- sanitized developer response when present.

SteamID/account ID is discarded during parsing. The UI uses a fixed **Steam user** label and does not fetch profiles or avatars.

### 7.3 Repository and state

Community content is bounded to the active detail session:

- at most five pages or 100 reviews per query;
- independent cache keys for filter/cursor state in process memory only;
- failed refresh keeps current session content visible;
- changing canonical game or closing detail cancels calls and clears all review/discussion content;
- after process death the user returns to the library; reopening starts at Overview with no community body cache.

### 7.4 Native Reviews UI

The Reviews tab provides:

- loading, content, loading-more, empty, offline, and fixed unavailable states;
- Helpful/Recent;
- All/Positive/Negative;
- App language/All languages;
- All purchases/Steam purchases;
- Refresh and Load more;
- native plain-text cards;
- an explicit **Open Steam Reviews** fallback for authenticated or unsupported actions.

Writing, commenting, voting, reporting, profile navigation, and nested review-comment browsing remain external/deferred.

A Reviews failure cannot blank Overview, Discussions, Details, Copies, or source actions.

## 8. Deliverable 3 — native Discussions

### 8.1 Boundary

Discussions extend the same no-store community transport with a separate Steam Community URL policy:

- HTTPS and exact `steamcommunity.com:443`;
- listing paths bound to `/app/{trustedAppId}/discussions/`;
- thread paths bound to the same trusted AppID and fixture-supported discussion namespace;
- no cross-AppID redirects;
- no cookies, WebView, Steam session, login, age-gate bypass, or authenticated scraping;
- bounded HTML and manual redirect validation.

### 8.2 Parsing

Use a pinned Jsoup dependency and record its license notice. Regex is not an HTML parser.

Fixture-driven parsers extract only:

- supported category labels;
- sanitized thread title;
- reply/view counts and last-activity text;
- sanitized first-page thread posts/replies;
- validated next-page/thread routes.

Author IDs/usernames are discarded or replaced with the fixed **Steam user** label. Arbitrary links, scripts, images, embeds, quotes requiring unsafe markup, and unsupported layouts are not rendered.

Limits:

- at most 50 listing rows;
- at most 50 posts per loaded thread page;
- bounded title/post text and HTML body;
- bounded pagination with loop detection.

### 8.3 Native Discussions UI

The Discussions tab provides:

- loading/content/empty/offline/unavailable states;
- native thread list;
- supported category selection only when fixtures prove the layout;
- native thread reader and Back-to-list behavior;
- Refresh and Load more where a validated next page exists;
- **Open Community/Open Thread** fallback at all times.

Posting, replying, voting, reporting, moderation, unsupported pages, and authenticated content open externally.

Parser failure never hides the fallback and never affects Reviews or other detail sections.

## 9. Detail shell, gamepad, and accessibility

The approved shell contains exactly:

1. Overview
2. Reviews
3. Discussions
4. Details

The current fifth Resources tab moves into Details as a resource section. The detail page itself is not rewritten.

Before Reviews ships:

- detail close explicitly clears `GameDetailViewModel` community state;
- gamepad focus starts at Back or the selected tab;
- gamepad B returns from thread to list, then detail to library;
- tabs, filters, cards, Retry, Load more, and external actions have deterministic focus order;
- Loading, Empty, Offline, Error, and stale states use visible text and accessibility semantics, not color alone;
- review/discussion bodies render as Compose `Text`, never raw HTML.

## 10. Storage and schema

- Schema remains 27 for all three deliverables.
- Accepted/rejected/reset match decisions use existing canonical/store-match storage.
- Sanitized Steam detail snapshots and facet cross-references remain the only persisted catalog content.
- Review/discussion content receives no Room entity, DataStore key, serialization annotation, WorkManager payload, or disk cache.
- A schema 28 candidate/history table is deferred unless real testing proves that rerun search and the existing sticky-decision model are insufficient.

## 11. Diagnostics

Each feature gets a typed diagnostic sink. Allowed resolver outcomes include fixed categories such as `AUTO_ACCEPTED`, `REVIEW_REQUIRED`, `UNMATCHED`, `USER_CONFIRMED`, `USER_REJECTED`, `STALE_DECISION`, `PROVIDER_UNAVAILABLE`, and `CANCELLED` (cancellation is not logged as failure).

Allowed community outcomes include fixed provider/section/filter/cache/outcome/reason values, page/result counts, HTTP status, duration, and exception class.

Forbidden values are seeded into tests to prove rejection/redaction at the export boundary. Provider exceptions exposed above the transport contain fixed messages only. Raw server errors, HTML, JSON, titles, AppIDs, queries, cursors, usernames, SteamIDs, URLs, review text, and discussion text never enter diagnostics or Timber.

Diagnostics remain bounded, app-private, manual-export only, and never upload automatically.

## 12. Cross-check and correction gate after each deliverable

Each core deliverable follows exactly:

1. One implementation pass.
2. Owning tests only.
3. One focused diff-to-design cross-check.
4. Classification of every confirmed discrepancy as Reuse, Narrow repair now, Rewrite now, or Defer.
5. One consolidated correction commit for all confirmed Critical/High blockers.
6. Rerun only affected owning tests plus privacy/action/release sentinels.
7. Sync official upstream, publish one signed fork RC, and run the visible acceptance path.

A finding is confirmed only by a deterministic test, reproduction, or direct violated invariant. Medium/Low findings are recorded and deferred unless they prevent the visible acceptance path. If a second Critical/High blocker survives the single correction pass, stop and report instead of starting another review loop or the next feature.

Cross-check documents:

- `docs/superpowers/reviews/2026-08-08-steam-catalog-resolution-cross-check.md`
- `docs/superpowers/reviews/2026-08-08-native-reviews-cross-check.md`
- `docs/superpowers/reviews/2026-08-08-native-discussions-cross-check.md`

Each document records verified requirements, immediate correction, deferred items with owner, diagnostics/privacy evidence, exact tests, release commit/tag, and visible acceptance result.

## 13. Release sequence

Starting from `versionCode 30` / `1.1.3-rc4`:

| Deliverable | Planned version | Planned tag |
|---|---|---|
| Steam resolution + Fix match | `31` / `1.1.3-rc5` | `v1.1.3-rc5` |
| Native Reviews | `32` / `1.1.3-rc6` | `v1.1.3-rc6` |
| Native Discussions | `33` / `1.1.3-rc7` | `v1.1.3-rc7` |

A correction release consumes the next unused version code/tag and shifts later versions. Published tags are immutable and never reused.

Before every release:

- fetch and integrate current official upstream;
- verify feature branch includes upstream and local commits;
- run the deliverable's focused gate;
- fast-forward `Darkaxt/GameNative` master without force;
- publish all four APK channels through the proven parallel workflow;
- verify package IDs, version, APK Signature Scheme v2, checksums, and persistent fork certificate SHA-256 `90d491f4c194d4f6e9efaf2ba1a548e59388edd9ecbd96853d330fe6a9c260c9`;
- never expose signing-secret values;
- never touch an occupied device. Instrumentation requires an explicitly claimed separate AVD and serial.

## 14. Final hardening after all three core features

Only after the resolver, Reviews, and Discussions have each shipped:

- run one aggregate broad test/lint/design gate;
- fix remaining core-attributable Critical/High issues and the highest-value deferred Medium issues;
- validate the signed side-by-side upgrade path;
- prepare the official upstream PR while excluding fork-only signing, package, branding, version, and publication commits;
- create a separate design/plan for transactional external-storage moves;
- decide separately whether a safe per-container LSFG import is worth implementing.

## 15. Acceptance criteria

### Steam resolution

1. A fresh non-Steam-only canonical can discover a Steam entry without an owned Steam copy.
2. A unique exact candidate with compatible type and developer/year corroboration is accepted automatically.
3. Ambiguous, fuzzy, unknown-type, or conflicting-edition candidates never merge automatically.
4. The user can search again, select a correct candidate, keep separate, reject, and reset.
5. User decisions survive restart and outrank automation.
6. Match changes never alter owning-store execution or create Steam ownership.
7. Newly mapped non-Steam cards participate in popularity filtering in All and their native source tabs.
8. Accepted identities trigger Steam details/genres/features/review count and best-effort PICS tags without false ownership.
9. Resolver progress/coverage is visible and source-agnostic.
10. Search/title/AppID/URL evidence is absent from exported diagnostics.

### Reviews

11. Reviews are native, paginated, independently refreshable, and support the fixed 80/20 filters.
12. Review content is bounded, sanitized, process-memory-only, and absent from diagnostics/disk caches.
13. Offline/error/empty states are honest and do not blank other detail sections.
14. Unsupported authenticated actions open Steam explicitly.

### Discussions

15. Public discussion listings and fixture-supported threads render natively as plain text.
16. URL policy binds every request/redirect/thread to the trusted AppID and Steam Community host.
17. Parser failure retains a visible external fallback.
18. Discussion content/user identity is bounded, process-memory-only, and absent from persistence/diagnostics.

### Delivery discipline

19. Each deliverable has one focused cross-check, at most one blocker correction pass, owning tests, and one signed fork RC.
20. Rewrites occur only at structurally invalid active boundaries; unrelated improvements remain deferred until the three visible core features ship.
