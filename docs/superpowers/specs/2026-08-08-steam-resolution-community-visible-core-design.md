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
4. Execute the named resolver/detail completion, community completion, external-storage hardening, LSFG decision, and aggregate upstream-handoff stages without dropping ledger items.

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

### 3.2 Scheduled after the first three RCs

These items are not removed from the project. Section 15 assigns each one a named stage, acceptance condition, and closure rule:

- resolver durability, candidate history, broader catalog indexing, fuzzy candidate quality, and indirect mapping hints;
- Steam-first card artwork/title precedence and remaining native detail/action-bar parity;
- review-comment reading, broader review identity presentation, community search/categories, and additional thread layouts where a safe public path exists;
- global accessibility, gamepad, and translation completion for the shipped paths;
- transactional external-storage movement and device/mount recovery;
- a safe per-container LSFG import decision and, if accepted, implementation;
- aggregate hardening, signed final RC, and official upstream PR preparation.

### 3.3 Permanent safety and product boundaries

These are explicit rejected designs rather than forgotten features. Reconsidering one requires a new user-approved design that changes the named invariant:

- Persisting review bodies, discussion bodies, usernames, profiles, avatars, SteamIDs, account IDs, or community HTML is rejected by the mandatory privacy contract.
- Copying Steam cookies, embedding authenticated WebViews, or silently performing authenticated posting/voting/moderation is rejected by the credential and source-authority boundaries. Unsupported authenticated actions remain explicit external Steam actions.
- Treating unsigned GOG maps, indirect Epic→GOG→Steam joins, SteamGridDB, autocomplete order, or the first search result as automatic identity authority is rejected by the false-merge boundary. They may be separately validated candidate hints.
- Automatic merging of fuzzy or edition-ambiguous candidates is rejected; those candidates remain user-reviewable.
- A broad rewrite of the canonical library or detail shell is rejected unless a later deterministic blocker proves the current boundary structurally unusable.

## 4. The 80/20 and rewrite rule

Every touched subsystem is classified before implementation and again during that deliverable's cross-check.

| Classification | Rule |
|---|---|
| **Reuse** | The current boundary already preserves the required semantics and has owning evidence. Use it unchanged or through a thin adapter. |
| **Narrow repair now** | The active feature touches the seam and a bounded correction is required for visible behavior, identity safety, URL/content safety, diagnostics privacy, source-action safety, or reliable recovery. |
| **Rewrite now** | The existing boundary structurally violates the active contract and wrapping it would retain the defect. Rewrite only that boundary. |
| **Defer to named stage** | The issue does not block the active visible path or create a current Critical/High safety/privacy defect. Assign a ledger ID, target stage, acceptance condition, and closure evidence before moving forward. An unassigned deferred item blocks the cross-check. |

A rewrite is not justified by style, test difficulty, age, file size alone, or an opportunity to make the architecture ideal. A rewrite is justified when the old implementation cannot safely implement the current feature.

### 4.1 Current classification

| Existing area | Classification | Decision |
|---|---|---|
| Canonical IDs, `OwnedCopyKey`, schema-27 canonical entities | Reuse | Preserve identity and source-action separation. |
| `CanonicalGameResolver` normalization and confidence rules | Narrow repair | Feed it validated catalog evidence; do not replace its local deterministic rules. |
| `CanonicalProjectionCoordinator` | Reuse | Keep local projection network-free and transactional. |
| `RoomCanonicalMutationRepository` | Narrow repair | Add guarded automatic/manual confirm, reject, candidate, and reset operations. |
| `GogRecommendationsRepository.searchSteamAppId` | Rewrite if reused | It is recommendation-specific, first-result-oriented, hard-coded, and may log forbidden context. New catalog transport is required. |
| `GogMapRepository` as authority | Named-stage candidate hint | Stage 4 may validate it as a review-only hint; it can never become automatic authority without satisfying the direct-map contract. |
| Steam `appdetails` metadata provider | Narrow repair | Reuse parsing/security; expose candidate app type/year evidence and persist accepted metadata. |
| PICS association parsing | Narrow repair | Add a session-bound public facet façade for trusted non-owned AppIDs; do not insert false ownership. |
| Popularity threshold and sort | Reuse | They are already source-agnostic once a canonical has a Steam AppID/count. |
| Detail screen shell | Narrow repair | Keep the shell; restore exactly four tabs and replace only placeholder branches. |
| Aggregate review-count provider | Reuse | Keep it separate from full review browsing. |
| Review browsing | New boundary | No production implementation exists. |
| Discussion browsing | New boundary | No production implementation exists. |
| Community body persistence | Permanent boundary | Rejected by Section 3.3 privacy invariants; active-session memory and external fallback provide the supported behavior. |
| External-storage mover | Named Stage 5 | Harden transactionality, recovery, and device behavior after visible-core completion. |

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
- owned-library titles or match-candidate titles in diagnostics;
- search text;
- install paths;
- full URLs;
- review bodies;
- discussion bodies.

Owned-library search text and public community content may exist only in active process memory and network request/response buffers. They must not enter Room, DataStore, `SavedStateHandle`, `rememberSaveable`, WorkManager input/output, OkHttp disk cache, Coil disk cache, Timber context, crash messages, or diagnostic attributes. The sole title-persistence exception is the reproducible public Steam AppList catalog: positive AppID, sanitized public title, and `last_modified` may be stored in a bounded, versioned app-private cache with atomic replacement and seven-day freshness. It contains no ownership, account, search, candidate-selection, or credential data.

Typed diagnostics may contain fixed categories, counts, durations, source names, outcomes, fixed reason codes, HTTP status, exception class, and short hashed correlations only.

## 6. Deliverable 1 — automatic and correctable Steam resolution

### 6.1 Components

#### `SteamWebApiAppListProvider` and `SteamAppListSearchProvider`

A dedicated authenticated provider downloads the authoritative public game catalog from `IStoreService/GetAppList/v1`; a separate search provider builds an exact normalized-title index locally. Source-library titles are therefore never transmitted for catalog discovery.

Contract:

- HTTPS only and exact allowlisted Steam Web API host, port, path, and query keys;
- API key sent only in the `x-webapi-key` header, never in a URL;
- redirects, cookies, and disk HTTP caching disabled;
- bounded page count, entries, decompressed response size, and cancellation through body consumption;
- response bodies consumed on OkHttp workers rather than the caller/Main dispatcher;
- complete game-only catalog pages with strictly increasing positive AppIDs and cursor validation;
- compact versioned app-private cache containing only positive AppID, sanitized public title, and `last_modified`;
- seven-day freshness, atomic replacement, stale fallback, and explicit retry after provider failure;
- exact normalized-title lookup with at most ten local results;
- no source title, query, AppID, URL, body, or credential logging.

The runtime key setting has an explicit **Test** action. Test accepts only the fixed 32-hex format, makes a bounded one-result AppList request, distinguishes provider rejection from temporary unavailability, and enables Save only for the exact key instance that validated. The ViewModel retains only a SHA-256 fingerprint of that key. Save encrypts with a dedicated randomized Android Keystore AES-GCM key, lets Keystore generate the encryption IV, and stores versioned ciphertext only after successful validation. A failed validation or storage operation leaves the editor open with fixed retry feedback and never reports success.

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
5. Load a fresh public AppList cache or refresh the complete game catalog with the validated runtime key, then perform exact normalized-title lookup locally.
6. Keep at most ten exact local results and validate at most five candidates through bounded Store `appdetails` requests.
7. Apply the policy.
8. Commit only the final fixed-category result in one guarded Room transaction.
9. Publish progress and continue after per-game failures.
10. Stop promptly when the owning scope is cancelled. Accepted decisions already committed remain valid; a later run naturally resumes unresolved games.

The 80/20 first release uses no new Room table. Automatic scanning runs once per process session and may use only a fixed global last-success timestamp/resolver version in DataStore to avoid immediate repeat scans. Candidate lists and search strings remain memory-only; the reproducible public AppList cache follows Section 5.3. Stage 4 decides schema-28 durable attempt/rejection history and WorkManager resume from measured completion evidence, then applies the 80% coverage trigger only to fuzzy/indirect candidate expansion.

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

Writing, commenting, voting, reporting, profile navigation, and nested review-comment browsing use explicit Steam actions in Deliverable 2. Stage 5 implements every safe fixture-backed public read-only path and requires user approval to close any remainder as a permanent external boundary.

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

- Deliverables 1–3 remain on schema 27; Stage 4 migrates to schema 28 for durable attempt and multi-candidate rejection history with immutable schema export and upgrade tests.
- Accepted/rejected/reset match decisions use existing canonical/store-match storage.
- Sanitized Steam detail snapshots and facet cross-references remain the only persisted catalog content.
- Review/discussion content receives no Room entity, DataStore key, serialization annotation, WorkManager payload, or disk cache.

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
4. Classification of every confirmed discrepancy as Reuse, Narrow repair now, Rewrite now, Named-stage work, or Permanent boundary.
5. One consolidated correction commit for all confirmed Critical/High blockers.
6. Rerun only affected owning tests plus privacy/action/release sentinels.
7. Sync official upstream, publish one signed fork RC, and run the visible acceptance path.

A finding is confirmed only by a deterministic test, reproduction, or direct violated invariant. Every Medium/Low finding receives a completion-ledger ID and named target stage unless the user explicitly accepts it as a permanent boundary. If a second Critical/High blocker survives the single correction pass, stop and report instead of starting another review loop or the next feature.

Cross-check documents:

- `docs/superpowers/reviews/2026-08-08-steam-catalog-resolution-cross-check.md`
- `docs/superpowers/reviews/2026-08-08-native-reviews-cross-check.md`
- `docs/superpowers/reviews/2026-08-08-native-discussions-cross-check.md`

Each document records verified requirements, immediate correction, every new ledger item with owner/target/acceptance condition, diagnostics/privacy evidence, exact tests, release commit/tag, and visible acceptance result. A cross-check with an unassigned item is incomplete.

## 13. Release sequence

Starting from `versionCode 30` / `1.1.3-rc4`:

| Deliverable | Planned version | Planned tag |
|---|---|---|
| Steam resolution + Fix match | `31` / `1.1.3-rc5`; corrected provider/source RC `32` / `1.1.3-rc6`; corrected key-validation/persistence RC `33` / `1.1.3-rc7`; corrected key-gate/terminal-page RC `34` / `1.1.3-rc8` | `v1.1.3-rc5`; `v1.1.3-rc6`; `v1.1.3-rc7`; `v1.1.3-rc8` |
| Native Reviews | `35` / `1.1.3-rc9` | `v1.1.3-rc9` |
| Native Discussions | `36` / `1.1.3-rc10` | `v1.1.3-rc10` |

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

## 14. Completion stages after the three visible-core RCs

The first three RCs establish the highest-value paths; they do not close the project. The following named stages are part of this specification and remain visible in the plan until completed or explicitly rejected by the user.

### Stage 4 — resolver and detail completion

- Run the aggregate resolver coverage fixture and record only counts.
- If exact local AppList search resolves or surfaces credible candidates for less than 80% of eligible non-Steam canonicals, add the next bounded candidate source: validated GOG hints, alternate normalization, or durable candidate history according to measured failure categories.
- Decide WorkManager/process-death resume with evidence from the 900-game fixture and live scan completion. Implement it if foreground scanning cannot complete/resume acceptably; otherwise close it with recorded evidence rather than silence.
- Finish Steam-first card title/artwork precedence for accepted matches.
- Restore the integrated detail action bar and remaining approved Overview/Details fields.
- Complete gamepad, accessibility, and translation coverage for the resolver/detail path.
- Publish a signed resolver/detail completion RC.

### Stage 5 — community completion

- Evaluate public read-only review-comment access, broader review identity presentation, additional review filters, discussion search, categories, pagination, and additional public layouts.
- Implement every safe, fixture-backed public path that materially improves browsing.
- For any item without a safe public unauthenticated path, retain an explicit external action and obtain user approval before classifying it as a permanent boundary.
- Complete gamepad, accessibility, and translation coverage for Reviews and Discussions.
- Publish a signed community completion RC.

### Stage 6 — external-storage hardening

- Add capacity preflight, copy verification, journaled/atomic promotion, rollback/recovery, cancellation, and safe retry.
- Prevent movement while a game is running, downloading, updating, or otherwise mutating.
- Make file movement and source metadata updates recoverable as one operation.
- Validate unmounted/read-only/full/reinserted SD and USB states, modern scoped-storage fallback, package-channel roots, launch mapping, update, and uninstall behavior.
- Publish a signed storage-hardening RC after focused unit tests and an explicitly owned physical/device matrix.

### Stage 7 — LSFG decision and safe implementation

- Present the user with the candidate feature's exact benefit and remaining safety trade-offs; it may not disappear as an unnamed optional item.
- If approved, implement atomic managed import, bounded PE/DLL validation, per-container identity, explicit replace/remove/error UI, existence checks, and Steam-installed-versus-manual priority.
- Reject shared mutable global DLL paths, silently swallowed copy errors, arbitrary unvalidated files, and public test-key signing.
- Publish a signed LSFG RC when implemented. A no-implementation closure requires explicit user rejection recorded in the ledger.

### Stage 8 — aggregate correction and upstream handoff

- Run one aggregate broad test/lint/design gate after Stages 4–7 reach their recorded closure state.
- Fix remaining attributable Critical/High issues and every ledger item assigned to final correction.
- Validate signed side-by-side and compatibility upgrade paths.
- Prepare the official upstream PR while excluding fork-only signing, package, branding, version, and publication commits.

## 15. Feature completion ledger

This ledger is authoritative. Cross-checks may add rows but may not delete unresolved rows. Closing a row requires implementation evidence or an explicit user-approved permanent-boundary decision.

| ID | Missing or misimplemented feature | Current disposition | Target | Completion evidence |
|---|---|---|---|---|
| R1 | Non-Steam-only games cannot discover Steam identities | Corrected implementation; signed live acceptance pending | Deliverable 1 corrected RC | Authenticated full AppList bootstrap, exact local search, bounded validation tests, and signed acceptance path |
| R2 | No visible provenance or Fix Steam match flow | Implement now | Deliverable 1 | Search/select/reject/reset UI and sticky-decision tests |
| R3 | Non-owned accepted AppIDs lack PICS tags/categories | Implement now | Deliverable 1 | Logged-in best-effort PICS test without false ownership |
| R4 | Resolver scan lacks process-death/durable resume | Named decision | Stage 4 | Implement durable resume or record fixture/live evidence that foreground resume meets acceptance |
| R5 | Full authoritative catalog coverage is available for exact normalized title matches; fuzzy quality and indirect mapping hints remain incomplete | Exact AppList index implemented; fuzzy expansion remains coverage-triggered | Stage 4 | Corrected RC resolves the measured target and aggregate exact-match coverage is measured before any fuzzy expansion |
| R6 | Rejection history stores only one candidate | Evidence-triggered repair | Stage 4 | Rejected candidates do not recur incorrectly, or schema/history repair ships |
| R7 | Resolver provider failures were opaque/redacted and detail responses were unbounded | Corrected now | Deliverable 1 corrected RC | Fixed provider reason categories, one-worker pacing, partial-detail review fallback, and bounded-response tests |
| R8 | GOG recommendation-media fallback still performs a separate Store title search outside canonical resolution | Named correction; not part of ownership resolution | Stage 4 | Route through a trusted resolved/AppList identity or remove the fallback without reducing canonical resolver coverage |
| R9 | AppList `last_modified` is retained in the public cache but not yet used to suppress accepted-identity enrichment refreshes | Named optimization | Stage 4 | Unchanged accepted AppIDs avoid redundant rich enrichment while explicit/manual refresh remains possible |
| R10 | RC6 could validate format locally but Android Keystore rejected the caller-supplied AES-GCM encryption IV, and Save had no provider validation gate | Corrected now; physical acceptance pending | Deliverable 1 corrected RC7 | Host cipher regression, bounded provider validator tests, exact-key Test-before-Save ViewModel/UI tests, physical Keystore persistence, and signed-device Test → Save acceptance |
| O1 | A legitimate GOG non-game product type aborted complete ownership materialization | Corrected now | Deliverable 1 corrected RC | Pack-product parsing regression test and signed live GOG source acceptance |
| U1 | Samsung launcher policy could hide the package because the launcher was classified as a game | Corrected now | Deliverable 1 corrected RC | Manifest contract test and signed-device launcher visibility acceptance |
| U2 | The separate Copies glyph looked like a placeholder and duplicated the owned-store badges | Corrected implementation; signed live acceptance pending | Deliverable 1 next corrected RC | Grouped store badges are the only card-level Copies action; Compose click, accessibility, focus, and gamepad tests plus signed-device acceptance |
| U3 | Library layouts offer fixed List/Capsule/Hero/Carousel choices but no persisted card size or density control | Named work | Stage 4 | A persisted, gamepad-accessible size/density control visibly changes applicable card dimensions, with focused Compose tests and signed-device acceptance |
| U4 | The support prompt can recur on every fresh app process when the user has not permanently tipped | Corrected implementation; signed live acceptance pending | Deliverable 1 next corrected RC | Persist prompt-show time before display; suppress repeat prompts for seven days with boundary/clock-rollback tests and signed relaunch acceptance |
| P1 | Accepted Steam metadata can be overwritten by non-Steam projection | Repair now | Deliverable 1 | Provider-precedence regression test |
| P2 | Steam-first card artwork/title remains incomplete | Named work | Stage 4 | Accepted non-Steam cards use cached Steam presentation with fallback |
| D1 | Detail has a fifth Resources tab | Repair now | Deliverable 1 | Exactly four tabs; Resources rendered inside Details |
| D2 | Integrated install/play action bar is incomplete | Named work | Stage 4 | Guarded action matrix reachable from canonical detail |
| D3 | Approved Overview/Details fields remain incomplete | Named work | Stage 4 | Field/provenance matrix against the original design |
| D4 | Canonical detail gamepad B/focus and accessibility are incomplete | Partial now, complete in named stage | Deliverables 2–3 and Stage 4 | Focus/Back/semantics tests and device acceptance |
| D5 | Current Steam movie payloads use direct HLS/DASH fields, so trailers were omitted when only legacy WebM/MP4 objects were parsed | Corrected implementation; signed live acceptance pending | Deliverable 1 next corrected RC | Live-shaped current-HLS and legacy parser tests, movie-before-screenshot boundary test, metadata source-revision cache invalidation, and signed-device playback acceptance |
| V1 | Reviews tab is a placeholder | Implement now | Deliverable 2 | Native paginated Reviews signed acceptance |
| V2 | Public review comments and broader identity presentation are absent | Named evaluation/work | Stage 5 | Safe native path ships or user approves explicit external boundary |
| V3 | Persistent review/discussion body cache | Permanent privacy boundary | Section 3.3 | No body persistence; active-session cache tests |
| C1 | Discussions tab is a placeholder | Implement now | Deliverable 3 | Native listing/thread signed acceptance |
| C2 | Discussion search/categories/layout coverage is incomplete | Named work | Stage 5 | Fixture-backed supported matrix and explicit fallback for remainder |
| C3 | Authenticated posting/voting/moderation is absent | Permanent credential boundary | Section 3.3 | Explicit external actions; no copied credentials/cookies |
| A1 | New-path translations and broad accessibility are incomplete | Named work | Stages 4–5 | Resource/semantics/gamepad completion matrix |
| S1 | External moves are non-transactional and can split data | Named work | Stage 6 | Journaled verified move/rollback tests |
| S2 | Storage movement lacks active-game/download guards | Named work | Stage 6 | State exclusion and cancellation tests |
| S3 | Mount/scoped-storage/device recovery is incomplete | Named work | Stage 6 | Explicit SD/USB/device matrix |
| L1 | Candidate fork's LSFG picker is unsafe and not integrated | User decision plus implementation | Stage 7 | User-approved closure or safe per-container signed RC |
| F1 | Aggregate broad baseline and official PR remain open | Named final work | Stage 8 | Final cross-check, signed RC, and upstream-ready commit series |

## 16. Acceptance criteria

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
10. A runtime Steam Web API key must pass bounded provider validation before Save; a different or untested key cannot be persisted, and successful persistence survives repository recreation.
11. Search/title/AppID/URL/credential evidence is absent from exported diagnostics.

### Reviews

12. Reviews are native, paginated, independently refreshable, and support the fixed 80/20 filters.
13. Review content is bounded, sanitized, process-memory-only, and absent from diagnostics/disk caches.
14. Offline/error/empty states are honest and do not blank other detail sections.
15. Unsupported authenticated actions open Steam explicitly.

### Discussions

16. Public discussion listings and fixture-supported threads render natively as plain text.
17. URL policy binds every request/redirect/thread to the trusted AppID and Steam Community host.
18. Parser failure retains a visible external fallback.
19. Discussion content/user identity is bounded, process-memory-only, and absent from persistence/diagnostics.

### Delivery discipline

20. Each deliverable has one focused cross-check, at most one blocker correction pass, owning tests, and one signed fork RC.
21. Rewrites occur only at structurally invalid active boundaries; every non-blocking issue remains in the completion ledger with a named target or explicit user-approved permanent boundary.
22. Stages 4–8 remain part of the delivery sequence; completing RC5–RC9 does not silently close their ledger items.
