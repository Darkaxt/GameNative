# Steam Resolution and Native Community Visible-Core Design Specification

**Current execution reconciliation — 2026-10-15:** The current-upstream acquisition/lifecycle repair is host-qualified and delivered as signed46/`v1.3.0-rc20`, immutable source `7e1279f45ea7f5c649c6b135a03b00c23dc423f7`, successful workflow38752020157. All four published APK identities/checksums/persistent signers/native payloads independently verified; this is not live acceptance. Freshly coordinated Samsung readiness returned `device 'R52W60CFTRL' not found` before installation or interaction; all peer reservations released. ART7/R14 and EX3–EX4/FC-LIVE-1 remain blocked on connection, with unchanged production artwork/restart/two-original/fresh automatic non-review resolver criteria. Resume under the existing iterative Samsung authority with fresh ownership/readiness, not another routine permission question. Exact evidence and next action are in the qualification plan's RC20 delivery section; historical failed/limited records and the one explicit Windows capability skip remain intact.

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

- Keyless, targeted public Steam Store search for non-Steam canonical games without a trusted Steam AppID, with the authenticated AppList retained only as optional exact-match indexing assistance.
- Strict candidate validation against normalized title, meaningful edition tokens, app type, developer/publisher, and release year.
- Automatic acceptance of one unambiguous high-confidence candidate, including corroborated cross-store release-year variance and the unique closest eligible Steam release at or before the source year.
- Native correction and provenance UI.
- Automatic handoff from a newly accepted Steam identity to existing metadata and review-count enrichment.
- Public Epic CMS presentation fallback for genuine Epic exclusives only after a complete, nonpartial Steam `UNMATCHED` result with no plausible Steam candidate.
- Best-effort public PICS facet enrichment for a trusted non-owned AppID while a Steam client session is active.
- Native Reviews and Discussions tabs using bounded public content with field-sensitive credential and account-identity protection.
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

- Persisting usernames, profiles, avatars, SteamIDs, account IDs, authenticated community HTML, or account-linked community associations is rejected by the mandatory personal-data and credential contract. Public review/discussion text, routes, URLs, AppIDs, and content IDs may use bounded app-private persistence and diagnostics.
- Copying Steam cookies, embedding authenticated WebViews, or silently performing authenticated posting/voting/moderation is rejected by the credential and source-authority boundaries. Unsupported authenticated actions remain explicit external Steam actions.
- Treating unsigned GOG maps, indirect Epic→GOG→Steam joins, SteamGridDB, autocomplete order, or the first search result as automatic identity authority is rejected by the false-merge boundary. They may be separately validated candidate hints.
- Automatic assignment of a fuzzy or edition-ambiguous **Steam catalog identity** is rejected; those candidates remain user-reviewable. The explicit 2026-10-08 refinement in §9.2 permits independently corroborated **game-family presentation grouping** across editions without overwriting their exact catalog or executable identities.
- Unrelated broad rewrites remain rejected. The user-authorized 2026-10-08 responsive four-tab layout refactor is a bounded presentation change (§9.2), not permission to replace entitlement, source-action, or canonical mutation boundaries.

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
| Public community caching | Reuse or narrow optimization | Public review/discussion content may use bounded normal caches. Exclude credentials, cookies, SteamIDs, usernames, profiles, and account associations. |
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

### 5.3 Data-protection contract

Do not persist or export:

- passwords, API keys, signing secrets, authentication headers, cookies, or tokens;
- SteamIDs, usernames, profiles, account IDs, or personal/account associations;
- account-authority or entitlement evidence that reveals ownership;
- install paths or other personal filesystem locations;
- private user-entered search text.

Public game and project data is not private: titles, AppIDs, storefront product IDs, catalog metadata, public routes and URLs, review/discussion text, cursors, recommendation/post/content IDs, and PCGamingWiki facts may be captured, tested, cached, persisted, and included in bounded typed diagnostics. Storage remains app-private and bounded where appropriate, but no public field receives `no-store`, session-only, or redaction treatment merely because GameNative uses it.

Typed diagnostics use field-specific attributes. Public catalog/community fields are permitted; credential-bearing URLs, sensitive query parameters, local paths, tokens, account/profile identity, entitlement associations, and private search text remain excluded or redacted.

## 6. Deliverable 1 — automatic and correctable Steam resolution

### 6.1 Components

#### `SteamStoreSearchProvider` and optional AppList index

A dedicated keyless provider searches the public Steam Store catalog with the source title, then validates bounded candidates through Steam `appdetails`. The authenticated `IStoreService/GetAppList/v1` cache remains an optional exact-title index and refresh optimization; missing, invalid, stale, or unavailable API-key state never blocks the keyless resolver.

Contract:

- HTTPS only and exact allowlisted Steam Store hosts, ports, endpoint paths, and query keys for both Store search and `appdetails`;
- every redirect and effective response URL revalidates the same endpoint-specific path/query contract;
- redirects are followed manually and cookies remain disabled; public responses use normal cache semantics under bounded provider/app caches;
- bounded result count, redirect count, decompressed response size, and cancellation through body consumption and retry delays;
- a complete/partial result type distinguishes authoritative empty search from interrupted or incomplete discovery;
- public Store results retain enough title/AppID evidence for review while `appdetails` supplies type, developer/publisher, year, metadata, and identity verification;
- at most ten search hits and at most five `appdetails` validations per canonical resolution attempt;
- the optional AppList cache remains compact, versioned, atomically replaced, seven-day fresh, and limited to positive AppID, sanitized public title, and `last_modified`;
- no password, API key, authentication header, cookie, or personal/account detail enters logs, diagnostics, URLs, or persisted catalog records.

The optional runtime key setting retains its explicit **Test** action. Test accepts only the fixed 32-hex format, makes a bounded one-result AppList request, distinguishes provider rejection from temporary unavailability, and enables Save only for the exact key instance that validated. The ViewModel retains only a SHA-256 fingerprint of that key. Save encrypts with a dedicated randomized Android Keystore AES-GCM key, lets Keystore generate the encryption IV, and stores versioned ciphertext only after successful validation. AppList validation/storage failure leaves its editor open with fixed retry feedback but does not disable keyless Store resolution.

#### `SteamCatalogCandidatePolicy`

A pure policy compares source evidence with bounded Store-search results validated through Steam `appdetails`.

Inputs:

- source storefront, title, and edition-preserving normalized title key;
- all known source developer/publisher keys;
- source release year when known;
- source app type;
- candidate AppID, title/title key, all developer/publisher keys, release year, and app type;
- provider completeness and `appdetails` verification state.

Outputs:

- `AutoAccept(candidate)`;
- `ReviewRequired(candidates)`;
- `Unmatched`.

Porting the validated year and completeness semantics increments `CURRENT_RESOLVER_VERSION` from 3 to 4. Existing automatic review/unmatched decisions become eligible for reconsideration; accepted `HIGH`/`VERIFIED` identities and sticky user decisions remain stable.

The 2026-10-08 correction starts from the actual integrated version **5** and advances it to **6**: a unique exact-title, compatible-game candidate with a known one-year difference must not be vetoed solely by the numeric score floor when §6.3's corroboration already holds. Version-five automatic review decisions are reconsidered once without a user reset. Developer/publisher contradictions, ambiguity, edition conflicts, incomplete discovery, direct-Steam identities and sticky user decisions retain their existing safeguards. This generic repair does not establish why the tablet's Epic DREDGE record required review.

#### `SteamCatalogResolutionRepository`

The artwork-aware consumer advances the actual version **6 to 7** only after owning repository RED: complete verified candidate discovery may invoke the independent producer, while source-native validated public artwork URI changes invalidate stale leases and completed review evidence. Version-six automatic review decisions are reconsidered without user reset; accepted/direct-Steam identities and sticky user decisions remain stable. Local source DAO reads may enter the public evidence hash, but runtime lookup, network, decoding and fingerprint cache I/O remain outside Room transactions. Owning two-flavor integration, production acquisition, exact native-source hash qualification and broader version/privacy/action/schema/release sentinels are HOST_VERIFIED (414 affected cases each; both Android-test compile tasks up-to-date). Family projection and exact edition/copy Install/Run integration remain required Stage E work. This is not a DREDGE/device or family-completion claim.

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
3. Choose the present copy with the strongest evidence: known compatible type, developer/publisher, then release year. Search once per canonical, not once per copy.
4. Preserve existing local exact canonical matching as the first automatic path.
5. Run keyless bounded Steam Store search. An available exact AppList index may add or order exact-title candidates, but it is never a prerequisite and cannot turn an incomplete Store result into a complete one.
6. Keep at most ten hits and validate at most five candidates through bounded Store `appdetails` requests.
7. Apply the policy only to verified evidence and its explicit completeness state.
8. A complete empty result may become `UNMATCHED`; partial search/details, malformed payloads, transport failure, timeout, or exhausted rate limiting never become `UNMATCHED` and never trigger source-catalog fallback.
9. Only a complete, nonpartial Steam `UNMATCHED` result with no plausible Steam candidate may invoke the owned source's public presentation fallback. The first implementation supports Epic CMS for genuine Epic exclusives.
10. Commit only the final fixed-category result in one guarded Room transaction.
11. Publish progress and continue to the next canonical after a per-game typed failure; exhausted rate limiting aborts that game's remaining candidate traversal without returning partial resolution data.
12. Stop promptly when the owning scope is cancelled. Accepted decisions already committed remain valid; a later run naturally resumes unresolved games.

The 80/20 first release uses no new Room table. Automatic scanning runs once per process session and may use only a fixed global last-success timestamp/resolver version in DataStore to avoid immediate repeat scans. Private user-edited search text remains memory-only. Public candidate lists, titles, AppIDs, storefront IDs, URLs, and reproducible catalog evidence may use bounded app-private persistence and normal caches. Stage 4 decides schema-28 durable attempt/rejection history and WorkManager resume from measured completion evidence, then applies the 80% coverage trigger only to fuzzy/indirect candidate expansion.

### 6.3 Automatic acceptance policy

A candidate is eligible for automatic selection only when all of these are true:

1. Source and candidate title keys are exactly equal after edition-preserving normalization.
2. App types are known and compatible games.
3. There is no developer/publisher contradiction.
4. At least one corroborator is positive:
   - a normalized candidate developer or publisher equals a known source developer/publisher; or
   - both release years are known and differ by at most one; or
   - §9.2 / R15's independently sourced, usable original-store and exact-AppID candidate artwork corroborates under the versioned composition/aspect/distance checks. This adds no exception to exact title, compatible type, party/edition conflicts, completeness, sticky decisions or the multiple-candidate prior-year rule.
5. No sticky user rejection/confirmation exists.

For one eligible candidate, a matching developer/publisher makes a larger corroborated cross-store release-year difference neutral rather than a veto. This covers a source release preceding a later Steam release without weakening title, edition, or type checks.

For multiple eligible candidates, automation may select only the unique candidate whose Steam release year is closest to the source year without exceeding it. A same-year candidate wins naturally. Missing source year, no candidate at or before that year, a tie at the closest eligible year, conflicting editions, or incomplete/unverified candidate evidence produces `REVIEW_REQUIRED` rather than an automatic merge.

A complete search with no plausible candidate produces `UNMATCHED`. Partial, malformed, timed-out, failed, or rate-limit-exhausted discovery produces typed provider unavailability and retains the prior relationship unchanged.

### 6.4 Source-catalog fallback

A source-catalog fallback is presentation, not a fabricated Steam resolution. It runs only after complete, nonpartial Steam `UNMATCHED` with no plausible Steam candidate. It must not run after `REVIEW_REQUIRED`, partial search/details, malformed payloads, timeout, transport failure, or rate-limit exhaustion.

For an Epic-owned copy that is a genuine Epic exclusive, the provider may fetch the public Epic CMS product document and map it to the exact `CanonicalGameMetadata` contract: title, descriptions, header, screenshots, movies, developers, publishers, release date, platforms, languages, requirements, genres, features, nullable achievements/DLC, and fetch timestamp. Epic namespace/catalog/product identity remains separate from Steam AppID, persistence is source-aware and does not require a positive Steam AppID, provenance is `EPIC_CMS`, and Epic/Unreal media URLs use their own strict provider policy rather than widening Steam URL policy.

### 6.5 Manual correction experience

A visible **Steam match** provenance block appears in canonical Details and the Copies flow. It shows a fixed status—Automatic, User confirmed, Needs review, Rejected/kept separate, Unmatched, or Checking—and never exposes private diagnostic material.

**Fix Steam match** opens a native picker:

- the public source title may prefill the picker; only private user edits remain composition-memory-only;
- the user can edit the title or enter a positive Steam AppID;
- search always reruns against Steam and is not limited by automatic cooldown;
- candidate cards show Steam artwork, title, developer, release year, and app type;
- the current association is identified;
- selection requires explicit confirmation;
- **Keep separate** rejects the current association;
- **Reset to automatic** clears the sticky user decision and immediately reruns resolution;
- gamepad Back/cancel never mutates the match.

When a canonical contains several mutable non-Steam copies, the flow first asks which owned copy/evidence is being corrected. Direct Steam copies are shown as immutable.

### 6.6 Metadata, facets, and popularity handoff

After a verified/high Steam AppID is accepted:

1. Persist already-validated sanitized `appdetails` metadata through `GameMetadataRepository`.
2. Make Steam metadata the canonical presentation preference without creating Steam ownership.
3. Refresh canonical Steam genres and feature/category facets with provider-aware precedence so a later non-Steam projection cannot erase fresher Steam-derived presentation facets.
4. Submit the canonical to the existing review-count popularity enricher.
5. If a Steam client session is active, request public PICS product info for that trusted AppID through a new session-bound façade and persist only canonical genre/category/store-tag associations.
6. If PICS is unavailable, retain `appdetails` genres/features, leave tag membership unknown, and do not scrape store HTML as a fallback.
7. Store unknown tag IDs but hide them until the localized tag dictionary has labels.

The PICS request must not insert a non-owned game as an owned Steam entitlement. Existing `steam_app` ownership/depot storage remains non-authoritative for non-owned catalog records.

### 6.7 Progress and coverage

The library Options panel exposes a Steam-resolution section with:

- resolved/eligible canonical count;
- needs-review count;
- unmatched count;
- completed/total/failed progress while scanning;
- **Review matches** and **Retry** actions.

Counts are canonical and source-agnostic. They do not disappear outside the Steam tab. A threshold-active popularity view must admit a GOG/Epic/Amazon-only card when that canonical has a trusted Steam AppID and sufficient review count.

### 6.8 Resolver failure behavior

- Every idempotent Steam Store GET receives at most four total attempts.
- A numeric or HTTP-date `Retry-After` is honored and capped at 30 seconds; absent/invalid values use cancellable 1, 2, then 4 second delays.
- Success returns immediately without an extra delay. Cancellation during a request, body read, or delay propagates.
- Exhausting HTTP 429 returns only typed rate-limit exhaustion, no candidate list, partial resolution, `UNMATCHED`, or source fallback.
- Exhausted AppDetails rate limiting aborts that game's remaining candidate traversal; the library scan may continue with the next canonical.
- Complete-empty search is the only transport path to `UNMATCHED`. Partial results with verified candidates are review-only; partial results without a verified candidate are provider-unavailable.
- One per-game provider failure does not cancel other games.
- Cached accepted/user decisions remain usable offline.
- Search unavailable preserves the current relationship and exposes Retry/Fix match; it does not persist `UNMATCHED`.
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

- normal HTTP caching available for bounded public responses;
- `CookieJar.NO_COOKIES`;
- automatic redirects disabled;
- no explicit `Cache-Control: no-store` override;
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

Public community content is bounded independently of its storage lifecycle:

- at most five pages or 100 reviews per query;
- independent cache keys for filter/cursor state; bounded app-private memory or disk caches are permitted;
- failed refresh keeps current content visible;
- changing canonical game or closing detail cancels calls and clears the visible review/discussion state without imposing cache eviction;
- after process death the user may return to the library; reopening may reuse valid bounded public content while defaulting to Overview.

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

Discussions extend the same public unauthenticated community transport with a separate Steam Community URL policy:

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

### 9.1 Tablet-driven refinements — user requested 2026-10-07

These explicit user decisions refine the existing detail/action shell; they do not relax source authority or the four-tab contract.

- **U3:** The active trailer/image must fit the available Overview viewport with its complete frame, player controls, and carousel navigation visible together. Aspect ratio alone is insufficient when a landscape tablet's remaining height is smaller than the width-derived media height. Verify the signed updated build on the tablet, not only the old installed RC12 or a pure size policy.
- **U4:** Remove the automatic “Thank you for using GameNative!” startup/support popup. The previous seven-day throttling requirement is superseded by this explicit user request. Manual support navigation remains available; upgrade must not depend on deleting preferences or pretending the user donated.
- **D7:** Present one combined canonical shop/game page. Replace the standalone **Copies** and **Source details** action buttons with **Install**, opening the owned-source chooser first. Source-specific information and controls stay inside the same page, and installed-game options are accessible through a wrench there rather than a separate source-detail screen. Preserve appropriate installed/download/update actions and exact `OwnedCopyKey` capture/revalidation; selecting a storefront never grants another storefront's entitlement. Cancel/Back from the chooser preserves the page and performs no operation.

### 9.2 Layout and automatic grouping refinements — user requested 2026-10-08

The user explicitly directed host-only optimization after disconnecting the tablet. These requirements extend Stage 4; they are not optional polish and do not authorize further device access without renewed ownership coordination.

- **U5 — responsive four-tab layouts:** Replace long undifferentiated text columns with content-specific sections. Details uses compact fact cards, a readable language-support matrix, and separately expandable platform requirements. Overview separates media/about from source status and summary facts on wide windows; Reviews and Discussions use bounded readable content widths and a compact controls/list or reader arrangement. Narrow windows stack the same sections without horizontal clipping. Preserve exactly four tabs, fitted media/player controls, semantic reading/focus order, Back behavior, loading/offline/error states and selected-tab/source-action state. Owning Compose geometry, state-retention and keyboard tests precede signed portrait/landscape acceptance.
- **R14 — automatic grouping diagnosis:** Resolve the observed separate GOG/Epic DREDGE entries through reproducible source/catalog evidence and generic automatic behavior, not a hard-coded title/AppID or manual confirmation/reset on the user's library. Record whether source corroboration, policy thresholds, provider completeness or stale durable attempts caused the review state. An exact-title candidate is not sufficient alone; add independently sourced corroboration rather than lowering identity safeguards blindly.
- **R15 — artwork corroboration:** Evaluate and implement bounded original-store artwork matching, including perceptual hashing (pHash), as an additional automatic corroborator. Hash the original source-store image and independently fetched candidate artwork, never a Steam-derived displayed cover or its fallback. Version the algorithm; require usable, non-placeholder images, comparable composition/aspect, bounded decoding/network work and an explicit tested distance threshold. Matching artwork can corroborate a game family but never creates entitlement, defeats user Keep separate/rejection, or confuses a game with DLC/demo/soundtrack/sequel. Missing/unsupported/artwork-fetch failure preserves existing resolver behavior. Public fixtures must include resized/compressed copies, distinct art, shared edition art and false-positive/placeholder controls.
- **D8 — one game family, independent editions/copies:** The user accepts different editions sharing one presentation entry. This explicitly supersedes the prohibition on **presentation grouping** of edition variants; it does not equate their Steam AppIDs, contents or runtime identities. Keep exact store product, original edition name and installation identity for every copy. Install chooses a clearly labeled edition/store before the native confirmation; Run chooses among eligible installed editions/copies unless an explicit valid preference applies. Multiple editions may remain installed simultaneously, with independent paths/runtime state and no overwrite, uninstall or launch of a sibling. Search, source filters and detail routes must share the same grouping projection. Grouping must remain deterministic across restart and preserve sticky user separation and direct-Steam identities. Owning projection/chooser/guard tests and signed no-install chooser inspection are required; simultaneous real installs/launches need their own explicitly authorized acceptance checkpoint.

The earlier observed matching DREDGE covers are an investigation lead, not measured pixel equality or pHash evidence. The user reported that Reviews loaded after retry while Discussions did not; C1/C2 remain live blockers until the failed discussion representation/transport is reproduced and corrected with public fixtures.

### 9.3 Live corrections and priority — user requested 2026-10-11

The next correction batch follows the user's explicit order: **automatic grouping first (critical), layout second, DLC ownership next, Reviews/Discussions afterward**. This supersedes the scheduling order for these open corrections, not their acceptance or safety requirements. The signed 42/rc16 tablet check reproduced two DREDGE GOG/Epic cards, the all-Unknown language matrix and wasteful equal-width sections. Reviews and Discussions both ended in fixed unavailable states. These observations do not establish the failing transport, parser, artwork or policy boundary. Earlier host verification remains historical evidence, not closure of these failures.

- **R14/R15/D8:** Trace original-source evidence through the actual producer and shared library/search/detail projection. Prove any repair with failing integrated regressions before claiming it fixes the observed grouping. Independently corroborated pHash may group presentation without requiring every member to acquire a Steam identity; preserve exact copies, compatible type, user separation and false-positive controls. No title-specific rule, manual merge/reset or preference manipulation.
- **U5:** Wide Overview and Details use independently flowing, compact, content-appropriate unequal columns, not row-aligned equal-width blocks with blank areas. Narrow windows stack without clipping; preserve media fit, four tabs, state, Back/focus and source guards.
- **D9 — truthful language support:** Diagnose the missing capability data. Show ticks/crosses only for known supported/unsupported values; omit unsupported capability columns/matrices and use a compact language list for names-only data. Unknown is never false. Target: layout correction batch; evidence: real provider fixtures, parser regressions and actual Compose rendering.
- **D10 — individual DLC ownership:** After layout, present individual DLC with ownership derived from the selected exact owning-store copy's native entitlement authority. Steam catalog association alone does not grant Steam or cross-store DLC ownership. Unavailable/incomplete entitlement information is unknown, not unowned; edition bundles require authoritative constituent mappings. Target: DLC correction batch; evidence: entitlement/source/edition/stale-selection controls and native UI tests. No login, purchases or game installation is authorized by this request.
- **C4 — explain community loading failures:** After DLC, trace the native Reviews/Discussions loading process end to end. The user explicitly permits public community-resource traffic capture using their Helium browser. Retain bounded public routes/status/representation/fixtures, not browser credentials, cookies, tokens, account identities or unrelated traffic. Browser success alone is not native-app success; compare the app's request/representation/parser/UI boundaries. Implement fixture-backed repairs; if a resource cannot be supported, provide the concrete failing boundary and evidence rather than a generic unavailable message or unsupported success claim. Target: community correction batch; permanent-boundary closure still requires the user's decision.

The 2026-10-11 device batch is ended: application force-stopped, rotation unchanged, panel OFF, lease released. Helium capture permission does not grant fresh Android access. Before another live request, deliver a coherent correction with owning RED/GREEN, focused integration/privacy/action checks and an exact-source signed candidate, clearly stating what changed and what remains unproven.

### 9.4 Artwork-first grouping — explicit user revision 2026-10-13

**Authority:** The user's “Improve Cross-Store Grouping Using Artwork Similarity” implementation request supersedes the earlier artwork-family title/developer/AppID/type/edition and pHash admission restrictions in §§3.3, 6.3 (only where used to veto artwork-family presentation), 9.2–9.3 and R14/R15/D8. Historical qualifications and failed observations remain unchanged. Steam catalog assignment still has its independent safeguards: visual grouping never substitutes a member's Steam identity, grants ownership or changes a source action.

One ACTIVE G1 feature integrates into `CanonicalFamilyArtworkCorroborator` → `CanonicalGameFamilyProjection` → `CanonicalLibraryRepository` and the existing shared card/search/detail/Copies consumers. No parallel duplicate detector, new grouping UI or redesign of canonical identity/mutation is authorized or required.

- **ART1 — artwork equivalence:** Use original image assets available to the card generator (horizontal header/background and supported original artwork fallbacks), not screenshots or UI overlays. ORB binary features and Hamming kNN/Lowe ratio matching feed partial-affine RANSAC. A poor or missing pHash never blocks extraction, candidate discovery or verification. Preserve image aspect during bounded working-resolution normalization; do not impose equal source aspect ratios. SIFT is only a measured optional fallback.
- **ART2 — confidence and false positives:** Require sufficient inliers, inlier ratio, distributed spatial evidence and plausible geometric agreement. A shared palette, generic text or localized logo is insufficient. Version extraction/matching policy and calibrate explicit thresholds on real storefront images, including DREDGE Steam/Epic/GOG and unrelated/logo/palette controls. No title-specific rule or forced equal image substitution.
- **ART3 — metadata-independent discovery:** Consider all otherwise-present, non-manually-separated canonical store entries, even with unrelated titles, conflicting Steam AppIDs/developers/publishers, different editions or GAME/DLC/DEMO/SOUNDTRACK types. Large-library retrieval must be visual and followed by full geometric verification, not metadata buckets or pHash exclusion. Existing metadata-based canonical grouping remains operational.
- **ART4 — authority and overrides:** Add high-confidence visual equivalence relationships through the existing canonical family resolver. Preserve each `OwnedCopyKey`, native product/edition/title, per-member canonical/Steam association, capabilities, installation/download/update/saves state and exact source dispatch. Never repoint catalog identities merely to obtain one card. Existing user confirmation/grouping and rejection/Keep separate/family suppression remain authoritative.
- **ART5 — durable incremental work:** Persist versioned descriptors and accepted/completed comparison evidence using bounded app-private storage. Recreate the same deterministic family after restart/synchronization; invalidate changed artwork and algorithm versions. Reuse descriptors and completed pair results, incrementally compare new/changed imagery, and avoid repeated all-against-all work. File/cache failures must not break library rendering or source actions.
- **ART6 — asynchronous liveness:** Emit ordinary cards immediately. Fetch, decode, extract, index and verify off Main with bounded native concurrency and memory/network/image budgets. Unrelated stats/metadata emissions must not repeatedly cancel unchanged artwork work; artifact/membership/manual-decision changes fence stale results. Cancellation and cleanup remain owning-scope responsibilities.
- **ART7 — integrated acceptance:** Real DREDGE assets must produce one shared canonical presentation family even with deliberately incompatible metadata, retaining every independently accessible copy. Cover crop/position/aspect/resolution/brightness/compression, real unrelated storefront art and generic-logo/palette negatives, restart/cache invalidation, incremental comparison counts, current shared repository→search/filter/detail/Copies/action consumers, and deterministic order. Host OpenCV/JNI or helper success alone cannot close this feature. No device, release or new candidate authority is inferred.

**Prospective ORB-v1 confidence policy:** up to1600 32-byte descriptors; reciprocal Hamming kNN2, Lowe ratio0.75 and maximum distance64; deterministic partial-affine RANSAC at3px,2000 iterations/confidence0.995. Storefront compositions may reposition components: after a primary model with at least24 inliers, peel at most two additional models, each with at least12 inliers, relative scale0.75–1.33, rotation difference≤10° and translation difference≤35% of target dimensions. Combined evidence still requires≥24 inliers, ratio≥0.55, normalized convex-hull coverage≥0.18 and≥7 occupied4×4 cells **in both images**, horizontal/vertical spans≥0.45/0.40, primary scale0.15–6, rotation≤25° and mean per-model residual≤2.5px. This is a bounded geometric policy, not threshold relaxation for one title. Its real-original matcher diagnostic accepts all three DREDGE pairs and the crop/brightness/JPEG transform while rejecting ten unrelated pairs and a high-inlier-ratio localized logo. Integrated ART1–ART7 acceptance remains required; no SIFT benefit has been demonstrated.

The previous laptop portrait/pHash probe is historical negative evidence, not the acceptance algorithm. Current implementation and host verification are authorized inline. Live testing remains STOPPED; any subsequent device session still requires fresh genuine authorization and coordination.

### 9.5 Resolver defaults, throttling and explainable grouping — explicit user revision 2026-10-14

**Authority:** After the authorized signed-APK DREDGE check failed visibly, the user requested investigation of Steam resolution, reduced cadence after rate limiting, automatic same-name defaults instead of reviewing a large library manually, and this tie-break order: **already in their Steam library, then closest release time, then higher popularity**. These decisions prospectively supersede the conflicting exact-name corroboration/ambiguity, partial-candidate and retry/cadence requirements in §§3.1, 6.1–6.3, 6.5 and 6.8. Historical tests and failed evidence are not rewritten. No renewed device lease is inferred from the user reopening the app.

- **SR1 — shared Store cadence:** Pace actual Store search, AppDetails and resolver popularity requests through one injected, cancellation-safe gate. Start with at least two seconds between request starts; adapt upward after HTTP429. Stop immediate retry traversal on throttling, retain a shared app-private cooldown across restart, and honor the full numeric/date `Retry-After` (at least a one-minute cooldown when absent/short). Manual retry/search may bypass the automatic scan schedule, never the provider's cooldown. Non-Steam providers retain their separate behavior.
- **SR2 — deferred batch and truthful progress:** The first Steam throttle pauses the automatic batch before another game's request. Current and remaining work stay retryable/PENDING through the existing durable resume path. Deferred work is not completed, failed, unmatched, a review decision, or proof of absence from Steam. Expose deferred/cooldown status separately; preserve accepted identities and native actions while waiting.
- **SR3 — same-name automatic defaults:** Validated GAME candidates with the same full edition-preserving normalized name are automatically selectable without developer/publisher agreement or known/matching years. Prefer a candidate already in the current native Steam library, then the closest release year (prefer at-or-before the source year when available), then the larger public review count; use stable AppID only for a remaining tie. Missing metadata does not impose a manual-review chore. A validated exact-name GAME can be accepted despite unrelated partial discovery; no verified same-name candidate retains the incomplete/provider safeguard. Do not equate a DLC/tool/different full edition title with a GAME. Preserve sticky user decisions and guarded source identity mutations. Advance the actual resolver version so prior automatic review decisions are reconsidered, not accepted/direct-Steam/user decisions.
- **SR4 — explainable DREDGE path:** Typed bounded diagnostics must connect public game evidence to original-art acquisition, content digests, visual shortlist/cache/comparison outcome, generation cancellation and actual family publication. Retain bounded current-process per-game state in manual exports independently of the high-volume pair-event tail. Resolver events identify the public game and fixed decision/deferred reason; report cadence/cooldown without queries, credentials, account/copy/canonical keys or entitlement associations. No automatic uploads, artwork-threshold changes, new grouping UI or title-specific rule.

Acceptance is the owning regression RED→GREEN, affected integrated repository/provider/diagnostic/privacy/UI-state checks for both flavors and production/Hilt/Android-test compilation. Shared-gate tests cover request pacing, HTTP429 batch stop, full Retry-After, restart, cancellation and manual non-bypass. Policy/repository tests cover the stated priority order, missing/conflicting metadata, partial verified candidates, manual rejection and wrong-type/title controls. Manual-export tests retain per-game traces under pair-event flood and exclude private data. Host remediation is not new live DREDGE acceptance; live testing remains STOPPED until genuinely renewed authorization and coordination.

**2026-10-14 host reconciliation:** SR1–SR4 implementation and affected acceptance are HOST_VERIFIED, with0 current host blockers and0 host tracked deferrals. Final qualification passed398 distinct cases per flavor (Legacy2 fresh plus396 explicitly reused unchanged cases; Modern398 fresh), zero failures/errors/skips, all owning RED identities retained and no source drift. Production/Hilt and both Android-test Kotlin compilations executed in the preceding unchanged-input consolidated run and were UP-TO-DATE in completion. The existing artwork runtime cross-check records the corrected export harness, consolidated review correction, privacy/profile evidence and limitations. Qualified source checkpoint `fd93671f615c61de9688c012029b9ce14498cb2f` was committed/pushed to `fork/sync/official-2026-10-03` and independently matched by fresh `git ls-remote`; SR1–SR4 host delivery is complete. This does not close parent R14/live DREDGE acceptance, establish the sole tablet cause, update the installed APK or authorize another candidate/release/device session.

### 9.6 Focused capture and title fallback — explicit user revision 2026-10-14

**Authority:** The user requested signed live validation without crashes/review chores, DREDGE aggregation and useful failure logs, then explicitly proposed title grouping if that still failed. The exact remediation APK remained two DREDGE cards; its export hit8192 snapshots dominated by cached comparisons and cancellations, without the original/acquisition/target-pair/publication chain. The user's subsequent instruction prioritizes a simple in-app DREDGE logging filter. These are authorized host corrections, not renewed device or release authority. Historical host qualification remains historical; it does not close the failed live acceptance.

- **FC1 — selectable focused capture:** Add a Debug setting for a public-game title filter (case-insensitive substring, e.g. DREDGE), explicit start/stop and new-capture semantics. Persist its opt-in configuration across restart; disabled capture is the default. This is diagnostic configuration, not private Steam-picker search text. Never export the raw user-entered filter.
- **FC2 — independent retained history:** Retain every emitted, sanitized matching resolver/artwork lifecycle/original/comparison/publication event in a separate app-private chronological capture. Associate title-free approved-original and ORB metric events using the existing URL/content digests learned from matching public-title events. Other games' traffic and the global1000-event/8192-snapshot limits must not evict this capture. Retain the complete validated public original URL up to4096 characters rather than losing its path/query after120; credentials and private paths remain redacted and other scalar limits stay unchanged. Preserve the existing typed privacy boundary; no raw logcat, account/entitlement/copy/canonical keys or private files. Each public artwork trace also carries the generation-start native match method/confidence, so an already-cached local review decision is visible even when no fresh Steam request is attempted; these fields must not masquerade as later live state.
- **FC3 — bounded, truthful export:** Export the complete retained focused capture separately, and include it in the existing feature export without the global event-tail limit. Bound storage at8MiB per capture; stop retaining when full instead of silently rotating away its beginning, and report limit/write-failure status. Stop preserves captured evidence; explicitly starting a new capture/clearing diagnostics resets it. Capture failure must never affect matching, cards or native actions. File reads/exports and settings writes run off Main.
- **FC4 — integrated evidence:** Owning assertion RED→GREEN proves original/acquisition/metric/decision/publication retention through an attributed pair flood and more than1000 target events, unrelated-title exclusion, restart reuse, stop/reset/full/error behavior and privacy. Compile the actual Debug setting and affected production/Hilt/Android-test paths in both flavors; no host result substitutes for a future focused tablet capture.
- **TF1 — exact-title family fallback:** The user's conditional fallback is now applicable to the failed visible/logging outcome. After focused capture, add generic full edition-preserving normalized-name presentation grouping through the existing family projection, preserving independent native copies/member Steam identities and sticky manual separation. No hard-coded DREDGE rule, fuzzy/title-token collapse, catalog reassignment or executable merge. Owning shared repository/search/filter/detail/action regressions and affected two-flavor verification precede qualification. This prospectively revises the artwork-only admission restriction for this additional title-family path, not ORB confidence thresholds or Steam catalog policy.

FC1–FC4 form one cohesive focused-capture feature; TF1 runs in the named subsequent title-fallback stage, not concurrently or in place of missing focused-capture acceptance. Live testing is ended and its sole DREDGE cause remains NOT_ESTABLISHED.

**2026-10-14 focused-capture host reconciliation:** FC1–FC4 implementation and affected acceptance are HOST_VERIFIED:43 fresh cases plus25 explicitly reused unchanged UI/catalog/pacing cases in each flavor,68 identical distinct identities, zero failures/errors/skips and no source drift. Both production/Hilt and Android-test Kotlin compilations succeeded (Legacy current tasks UP-TO-DATE from prior executed unchanged-production-input compilation; Modern executed). Owning retention/full-original/initial-native-state REDs pass after one consolidated correction; the obsolete truncated-URL expectation was prospectively reconciled, not relabeled. Current FC host blockers:0; FC host tracked deferrals:0. Qualified FC source `a52165c065382e03d6258b9e19410f17d9b5ba5e` is feature-branch published and remote-verified; FC host delivery is complete. This does not update the installed APK, establish a focused tablet result, close TF1 or complete the parent.

**2026-10-14 subsequent TF1 host reconciliation:** Generic exact-full-normalized-title relationships now enter the existing eligible presentation-family graph without artwork admission. Edition-base stripping is not used for these edges. Owning RED reproduced four missing-family failures in19 cases before the five-line graph addition; affected GREEN passed305 identical cases in each flavor, zero failures/errors/skips/drift, including real Room→mandatory shared owner→search/source/detail/action behavior with unavailable artwork. Both production Kotlin and Android-test Kotlin tasks executed, Hilt stayed UP-TO-DATE; gate/profile/fail-fast and daemon exits verified. Native identities, manual suppression/rejection, independent/malformed cards, full editions, exact actions, mixed visual/title relationships and deterministic recreation are covered. Current TF1 host blockers:0; host tracked deferrals:0. Qualified TF1 source `c2ae373bfee15d76a62e03f54b25923b1ba8cfb1` was feature-branch committed/pushed and independently remote-verified; host delivery is complete. No installed-APK, live DREDGE or broader parent completion claim.

### 9.7 Per-entry aggregation explanation — user requested 2026-10-14

**Authority:** The user requested a diagnostic button in the game entry to simulate aggregation and print a report explaining metadata and banner results and any full-title fallback. Host implementation is already authorized; this adds no device or signed-release authority.

- **EX1 — integrated read-only action:** Expose **Explain aggregation** in the existing canonical game entry. Run asynchronously with visible loading, cancellation/close and fixed failure feedback; show a selectable readable report and manual text export. Reuse the shared canonical projection/metadata/artwork owners, not a second grouping engine. Never mutate catalog/native copy identities, grouping/manual overrides, installations, preferences or source actions.
- **EX2 — truthful evidence and causality:** Explain the selected entry's original members and relevant relationships: existing metadata association/status, original-artwork acquisition/cache/comparison results and exact full normalized-title edges. Distinguish accepted, rejected, unavailable, pending, skipped and cached checks. Cache Boolean results do not supply historical ORB metrics. Label a current-snapshot simulation separately from recorded publication evidence; never invent why a historical metadata resolver failed or infer an artwork edge from an unrelated pair or family size. Explain that title edges are additive/immediate, not a sequential retry after every other check fails. Identify manual exclusion and stale/disappeared-entry states without bypassing them.
- **EX3 — lineage and privacy:** Address FC-LIVE-1's warm-cache lineage/publication gap through the real owner: associate validated public original URL/content bindings before cached pair replay, retain actually emitted fresh metrics and report actual admitted title/artwork relationships. Reports use public game/source/catalog/artwork data only through the existing sanitizer; no account/entitlement/copy/canonical keys, private paths, credentials, private query text, raw logcat or automatic upload. Bound report/history work, keep IO/native work off Main and preserve library rendering.
- **EX4 — owning acceptance:** Assertion RED→GREEN and affected two-flavor shared-owner/projection/artwork/metadata/diagnostic/privacy/actual-detail-UI tests prove title-only, metadata-supported, artwork-supported, mixed/transitive, cached-versus-fresh, unavailable/pending, manual exclusion and stale/cancel cases. Verify manual export contains the same sanitized report and no state mutation; compile production/Hilt and Android-test Kotlin. Record host evidence separately from future live acceptance.

**2026-10-15 host reconciliation:** EX1–EX4 are HOST_VERIFIED:108 fresh Legacy cases plus608 explicitly reused unchanged cases, and716 fresh Modern cases, cover the same716 identities across58 classes with zero failures/errors/skips and no source drift. All original owning RED identities, shared-original self-pair and normalized-private-title regression pass. Production and Android-test Kotlin executed in both flavors; Legacy Hilt stayed UP-TO-DATE from its unchanged graph and Modern Hilt executed. Actual detail/SAF routing, VM immutable UTF-8 export/cancellation, selectable dialog rendering/scroll/system Back/Escape/ButtonB, Room/cache nonmutation and real owner→focused-export lineage are covered. Both installed gates verified the unchanged3GiB in-process/two-worker/no-parallel/one1GiB-test-fork/fail-fast profile and daemon exit; no actual OOM. The independent receipt's first task-prefix parser mislabeled a Hilt unit-test task; its strict-name v2 receipt corrects that metadata without changing test evidence. Current EX host implementation/verification blockers:0; host tracked deferrals:0. Historical FC/TF and failed live evidence remain unchanged. Qualified fork delivery and signed four-channel RC18 publication are complete at20254d65/run38659402615, with independently verified APK identities/hashes/persistent signer. The authorized Samsung session verified the real diagnostic dialog/manual export and unchanged Android ORB acceptance of actual GOG/Epic and Steam-header/Epic pairs. Current production support remained TITLE; a new complete focused capture established pre-acquisition cached-verdict diagnostic replay plus repeated generation cancellation, not a demonstrated tolerance failure. FC-LIVE-1/EX3 production liveness remediation is ACTIVE: owning acquisition-priority/cancellation RED, cheap evidence priming before deferred cooperative historical replay, cache-missing-original priority, affected two-flavor qualification and next signed live recheck. Preserve all existing acceptance criteria and historical failed evidence. Fresh automatic resolver/no-review and production artwork/restart acceptance remain open.

**2026-10-15 liveness host reconciliation:** The three owning assertions reproduced the acquisition/replay/cancellation defects before production correction (twelve cases, exactly three intended assertion failures; zero errors/skips/drift). The cohesive EX3 repair primes cached evidence without historical rejected-trace persistence, publishes native-revalidated warm support, stably prioritizes missing originals and defers cooperative historical replay until acquisition/publication progress. It retains cached explanations, accepted late-owner attribution, exact native/manual/stale fences and unchanged ORB thresholds. Fresh affected qualification passed280 identical cases/flavor across33 classes, zero failures/errors/skips/source drift; production/Hilt and Android-test Kotlin executed in both flavors. Installed gates1114/1116 verify the unchanged3GiB in-process/two-worker/no-parallel/one1GiB-test-fork/fail-fast profile and daemon exit. A heap-expiry warning is retained; no actual OOM or memory-budget increase. Pinned host JNI digest verified. Independent source/test review found no important defect; direct every-acquisition ordering and scheduler-fairness assertions were not added or claimed. Receipt: `artwork-liveness-green/independent-verification.json` under the existing evidence root. EX3 liveness host blockers:0; signed RC19 delivery and coordinated production artwork/restart verification remain ACTIVE. This affected host result is not full-suite or live acceptance. The unused older tagged-release route's stale contract is not included or claimed green.

**2026-10-15 signed delivery / live gate:** Repair `fd0fab42` is committed/pushed to the fork feature branch/master and immutable signed RC19/run38667708893 is published. All four exact source/tag/run/APK/hash/package/version45/persistent-signer/native checks passed; OpenCV payloads match RC18. Freshly coordinated Samsung in-place upgrade preserved first-install time and installed exact published bytes. Actual UI verification then encountered secure keyguard (`secure=true`, showing/input restricted): no credential/security bypass, no new focused capture and no retry action. Exact app stopped/PID absent, settled physical display OFF/committed OFF and peer lease release verified. Production artwork/restart and fresh automatic resolver/no-review acceptance are BLOCKED pending genuine user unlock/readiness; the parent remains IN_PROGRESS. Preserve existing requirements and RC18 failure evidence; resume the same RC19 live sequence after renewed coordination, without an unnecessary source/release cycle.

**2026-10-15 unlocked RC19 reconciliation:** Genuine user readiness and fresh peer coordination resolved the secure-keyguard blocker. The same signed RC19 was cold-started with a new DREDGE-only capture: one title-supported family retained both uninstalled GOG/Epic copies, but production artwork acceptance failed. The complete clean-session capture contains seven admissions and six cancellations per source, no target Epic acquisition/binding or cross-store comparison, and three fresh comparisons against unrelated content. Isolated read-only Android replay acquired all five originals and again accepted two cross-store pairs at unchanged thresholds; it is not production grouping evidence. The earlier historical rejection flood is gone, but per-original serial shortlist work and whole-library generation replacement remain liveness investigation targets; dominant live time attribution is not established. FC-LIVE-1 / ART6 / EX3 host remediation remains ACTIVE under existing iterative authority: reproduce owning acquisition/fairness failures, repair generic scheduling while preserving stale/native/manual/cache fences, qualify affected two-flavor paths and publish the next unused signed candidate before renewed live acceptance. No second persistence restart or fresh resolver retry was attempted after the artwork criterion failed. These remain blockers. Capture/export, exact-app stop/PID absence, current physical display OFF/committed OFF and peer lease release are verified; no device lease remains. Full evidence: `live-rc19-unlocked/test-report.md` and `independent-capture-census.json` under the existing local evidence root. Historical RC18/RC19 failures and all acceptance criteria remain intact.

**2026-10-15 prospective consolidated acquisition qualification:** ART6/EX3 implementation now starts a bounded64-descriptor acquisition producer before full descriptor hydration/shortlist work. One observation-owned acquisition survives unrelated matching-generation restarts; affected input changes cancel it after the old producer joins, and current native owners are revalidated after queue residence. Same-input null failures have a60-second monotonic cooldown and tail priority on subsequent generations; identical inputs do not introduce an automatic retry scheduler. Lost candidate descriptors cannot mark incomplete indexing complete. Matching policy/native concurrency remain unchanged; bounded prefetch and nominal coroutine timeouts are not unlimited progress or hard native wall-clock guarantees. Two intended assertions were reproduced after correcting an invalid GOG fixture; the first attempted GREEN then passed13/14 Legacy cases and failed the mixed virtual/real-clock comparison fixture. Preserve that failed evidence. The corrected real-clock fixture, reviewed cancellation ordering, conflict-free current official `a5045850` integration and46/RC20 metadata are together in the ACTIVE52-class/two-flavor integrated qualification. No new host GREEN, signed release or live acceptance is claimed; the parent and all production artwork/restart/fresh-resolver criteria remain IN_PROGRESS.

**2026-10-15 lifecycle qualification correction — IN_PROGRESS, same authorized ART6/EX3 feature:** The integrated candidate failed after125 Legacy cases (123passes, one Room retirement timeout, one native fail-fast skip); Modern was not reached and no source drift occurred. Both new acquisition-progress assertions passed, but lifecycle acceptance remains open. A checkpoint-only reproduction confirmed ordinary surviving membership published before the timeout: the unchanged pending Epic original retained its direct owner when the other eligible canonical member retired, so cleanup waited despite no remaining possible inter-canonical artwork edge. This is a production lifecycle boundary defect, not a tolerance failure or a reason to increase the existing deadline. Preserve failed receipts, unchanged native/manual/publication fences and prior acceptance history. Prospective correction cancels and joins observation-owned pending work when fewer than two eligible canonical cards remain, skips impossible visual acquisition/matching with truthful terminal diagnostics, and permits work again when a peer returns; ordinary/title/metadata projection and read-only Explain inspection remain unchanged. Add initial-singleton/no-reacquisition/recovery assertions before correction, retain the original Room retirement assertion and unrelated-churn progress, then complete affected two-flavor qualification. No RC20 publication or device result is claimed.

**2026-10-15 lifecycle execution reconciliation — host qualification still IN_PROGRESS:** The corrected candidate passed every acquisition/churn/singleton-recovery assertion and all14 Room-family-flow cases in Legacy. Integrated v2 stopped later at an incoming upstream OpenAL host fixture:366passes/one failure/zero errors/skips/drift. A probe of the actual compiled drive parser establishes that a Windows absolute fixture inside Android's `A:<path>` encoding introduces an extra drive delimiter and loses the fixture. Correct only the three incoming Windows fixture adapters (OpenAL/PhysX/PreInstallSteps) and the PhysX separator expectation; retain real `getGameDir` discovery with explicit canonical-directory assertions and all existing behavior tests. Production logic is unchanged by this test-only correction. Final verification explicitly reuses359 unchanged complete Legacy cases, reruns remaining/affected Legacy cases and full Modern scope, and requires equal combined identities plus all prior guards. The failed run remains failed; host closure, signed release, actual Android artwork progress/restart and fresh resolver/no-review remain pending.

**2026-10-15 incoming registry-fixture correction — qualification remains IN_PROGRESS:** The first completion attempt passed all eight corrected OpenAL cases but stopped at the real PhysX registry read (expected GPU, actual null):22passes/one failure/one fail-fast skip in24 Legacy cases, zero source drift; Modern was not reached. A Zulu21 synthetic-file probe proves Windows replacement `File.renameTo` differs from the Android semantics assumed by the existing registry editor. Preserve Android production code and every real registry assertion. A Windows-only per-test rule adapts the actual temporary-file replacement operation in PhysX, RedistInstallerExtractor and SteamInstallScriptRegistry fixtures, without fabricating registry contents or results. A prospective six-test-file-only admission preserves the prior scripts/admission/failed receipts and still requires unchanged production/configuration/reused-suite hashes, remaining Legacy plus complete Modern and original lifecycle/native/privacy guards. This is host-fixture qualification only; signed delivery and live artwork/restart/fresh automatic resolver acceptance remain blockers.

**Prospective Windows platform accounting:** Preserve the existing unreadable-script test's capability assumption and explicitly label its unsupported Windows fixture. Only that exact case may be recorded as a platform skip, never a pass or exercised coverage. All other cases remain failure/error/skip-free; all owning artwork/lifecycle and prior280 passing identities must pass, with identical combined cross-flavor passing/skipped identities. This is a prospective correction to the assistant-authored host qualification profile, not a revision of ART/EX behavior or user acceptance. Prior zero-skip receipts and failed operations remain unchanged.

**2026-10-15 completion-v2 outcome — same qualification ACTIVE:**31/33 Legacy cases passed, one command-wrapping fixture expected a command but received none, and one native fail-fast skip; zero source drift and no actual OOM, gate/profile/daemon exit verified. All twelve PhysX registry/discovery/component cases now pass. The colon-free relative drive fixture makes its incidental root-installer symlink setup unsuitable; wrapper and marker-reset fixtures now place the real installer in its supported `_CommonRedist` location, retaining all behavioral assertions. Symlink relocation is not exercised or claimed; Android production remains unchanged. Preserve the failed v2 receipt/profile and prospectively qualify v3 with the same exact six-test-only whitelist, explicit unchanged Legacy reuse and complete Modern scope. No host acceptance, signed delivery or live artwork/resolver closure is claimed.

**2026-10-15 final acquisition-remediation host reconciliation — HOST_VERIFIED:** `integrated-acquisition-rc20-completion-v3` completes exit0 with502 equal identities across52 classes per flavor:501 passes, zero failures/errors and exactly the documented Windows unreadable-script assumption skip, never counted as passing/exercised coverage. Legacy143 fresh plus359 verified unchanged passes equals full Modern502; all prior280 passing identities and six acquisition/lifecycle/Room guards pass, current2816 source/config/workflow hashes and pinned JNI match. Modern production/Hilt/Android-test Kotlin execute; Legacy tasks are current-input UP-TO-DATE. Both gates/profile/fail-fast/daemon exits verified at unchanged3GiB shared in-process/two-worker/no-parallel/one1GiB-fork budget; no actual OOM. The single artwork runtime cross-check reconciles ART5–ART7/EX3–EX4: bounded concurrent acquisition, unchanged pending-work retention, native/queue fences, cancel/join below viable visual demand and peer-return recovery, without matcher/title/Explain/native-identity changes. Current host implementation/verification blockers:0; host tracked deferrals:0. Historical failures remain intact. Officiala5045850 is freshly verified/integrated; qualified fork publication and signed46/RC20 delivery are next authorized actions. Actual production artwork/restart and fresh automatic resolver/non-review remain live blockers; the parent is IN_PROGRESS.

## 10. Storage and schema

- Deliverables 1–3 remain on schema 27; Stage 4 migrates to schema 28 for durable attempt and multi-candidate rejection history with immutable schema export and upgrade tests.
- Accepted/rejected/reset match decisions use existing canonical/store-match storage.
- Sanitized Steam detail snapshots and facet cross-references remain the only persisted catalog content.
- Public review/discussion text, routes, URLs, AppIDs, recommendation/post IDs, and bounded response caches may use app-private persistence. Credentials, cookies, SteamIDs, usernames, profiles, account associations, entitlement evidence, local paths, and private search text may not.

## 11. Diagnostics

Each feature gets a typed diagnostic sink. Allowed resolver outcomes include fixed categories such as `AUTO_ACCEPTED`, `REVIEW_REQUIRED`, `UNMATCHED`, `USER_CONFIRMED`, `USER_REJECTED`, `STALE_DECISION`, `PROVIDER_UNAVAILABLE`, `RATE_LIMIT_EXHAUSTED`, and `CANCELLED` (cancellation is not logged as failure). `RATE_LIMIT_EXHAUSTED` is transport state and can never be interpreted as a catalog result.

Allowed community outcomes include fixed provider/section/filter/cache/outcome/reason values, page/result counts, HTTP status, duration, and exception class.

Public titles, AppIDs, storefront product IDs, routes, validated URLs, cursors, recommendation/post/content IDs, review/discussion text, and PCGamingWiki facts may enter bounded typed diagnostics where useful. Redaction tests seed passwords, API keys, signing secrets, authentication headers/cookies/tokens, credential-bearing URLs, sensitive query parameters, SteamIDs, usernames/profiles, account or entitlement associations, install paths, private search text, and arbitrary untyped exception payloads. Provider exceptions exposed above the transport contain fixed messages only.

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

### 12.1 Host validation containment — 2026-10-08 remediation

**HV1:** Host verification must not repeat the unbounded Legacy run that held the shared Gradle gate for 14 hours 37 minutes. The user authorized stopping only its identity-verified test worker; the daemon and supervisor were left to unwind, and queued builds retained FIFO ownership. This is not general permission to interrupt another build.

- Bound each unit-test task to 30 minutes of task execution, retain one 1 GiB fork and the installed gate's two-worker/no-parallel profile, and isolate test classes in fresh workers. Queue waiting and prerequisite compilation are not covered by the test-task deadline; do not describe it as a universal host deadline.
- Use a Robolectric-compatible interrupting wall-clock rule around the affected Room tests, including setup and teardown. Prove that a blocked coroutine fails within the bound and executes cancellation cleanup without poisoning the next test thread.
- Cover Room flow collector cancellation/join before database close and repeated database reopen. Diagnose any remaining lifecycle/order failure before changing production database behavior; worker isolation contains cross-class contamination but does not prove its ultimate cause repaired.
- Verify Gradle's actual task-timeout/worker-exit behavior with an explicitly enabled blocked-worker probe. The probe must reject a missing/wrong effective deadline before blocking; configure its one-minute override after Android project configuration and retain an independent 75-second JUnit fallback. A default 30-minute timeout does not count as a successful one-minute probe. Preserve failed/partial evidence; never count start markers or canceled tests as passes.
- Log test completion as well as starts. A no-progress watchdog checkpoint must surface the concrete blocker and required decision promptly, not repeat “active” indefinitely. Never release the gate over a live build, kill unrelated owners, or bypass the installed gate.

## 13. Release sequence

Starting from `versionCode 30` / `1.1.3-rc4`:

| Deliverable | Planned version | Planned tag |
|---|---|---|
| Steam resolution + Fix match | `31` / `1.1.3-rc5` through correction checkpoint `37` / `1.1.3-rc11`; RC9 was withheld and RC11 publication was canceled before release so remaining visible work can ship after one consolidated QA pass | `v1.1.3-rc5` through immutable checkpoint tag `v1.1.3-rc11`; only published releases count as acceptance candidates and the next release uses the next unused version/tag |
| Native Reviews + Discussions | One consolidated next-unused release after both implementations and the single owning QA pass | One next-unused immutable prerelease tag; no per-feature micro-release |

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

- Run the aggregate resolver coverage fixture and record bounded public catalog evidence plus counts while excluding account/ownership associations and private search text.
- If keyless Store search plus strict validation resolves or surfaces credible candidates for less than 80% of eligible non-Steam canonicals, add the next bounded candidate source: optional AppList indexing, validated GOG hints, alternate normalization, or durable candidate history according to measured failure categories.
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
| EX1–EX4 / FC-LIVE-1 | Per-entry aggregation explanation and production acquisition/publication liveness | EX UI/lineage delivered in RC18/19; subsequent acquisition/lifecycle host remediation HOST_VERIFIED2026-10-15, live acceptance open | Qualified46/RC20 signed delivery → coordinated DREDGE production/restart/fresh-resolver check | Original716-case EX proof retained; current502 identities/flavor,501 passes and one explicit Windows capability skip. Six new/original acquisition/lifecycle guards and all prior280 passes verified, no source drift; compilation/gate/profile/daemon exits verified. Actual Android Explain accepts unchanged ORB pairs, but RC19 production failed acquisition; RC20 live acceptance remains required. |
| R1 | Non-Steam-only games cannot discover Steam identities | POC-validated keyless Store resolution integration in progress; signed live acceptance pending | Deliverable 1 corrected RC | Keyless bounded Store search, optional AppList assistance, bounded AppDetails validation, exact one-card grouping, and signed acceptance path |
| R2 | No visible provenance or Fix Steam match flow | Implement now | Deliverable 1 | Search/select/reject/reset UI and sticky-decision tests |
| R3 | Non-owned accepted AppIDs lack PICS tags/categories | Implement now | Deliverable 1 | Logged-in best-effort PICS test without false ownership |
| R4 | Resolver scan lacks process-death/durable resume | Implemented and host-verified in Task 15 Step 2; Android process-loss/OS scheduling acceptance remains open | Stage 4 / LIVE01 | Durable public-evidence attempts, transactional lease completion, CONNECTED-constrained unique WorkManager resume and actual worker recreation/cancellation/gating tests; 134 owning tests per flavor and both debug APK packaging checks. See `2026-10-03-resolver-detail-completion-progress.md`; host recreation does not close live acceptance. |
| R5 | Keyless Store search and strict validation provide the primary catalog path; optional AppList indexing, fuzzy quality, and indirect mapping hints remain incomplete | Store path integration now; expansion remains coverage-triggered | Stage 4 | Aggregate keyless exact-match coverage is measured before optional/fuzzy expansion, and no API key is required for baseline resolution |
| R6 | Rejection history stores only one candidate | Implemented and host-verified in Task 15 Step 2; integrated signed-live acceptance remains open | Stage 4 / LIVE01 | Schema-30 exact account/source/copy/AppID history; multi-rejection, legacy carry-forward, reset, unmerge, sibling protection, recreation and transaction-rollback tests pass in both flavors. See `2026-10-03-resolver-detail-completion-progress.md`; publication/debug packaging alone does not close live acceptance. |
| R7 | Resolver providers lacked complete/partial results, safe HTTP 429 retry, typed exhaustion, and strict redirect/effective-URL checks | Repair now | Deliverable 1 corrected RC | Four-attempt retry with bounded `Retry-After`, typed fail-fast exhaustion, endpoint-specific URL tests, complete-empty-only unmatched, and partial-review regressions |
| R8 | GOG recommendation-media fallback still performs a separate Store title search outside canonical resolution | Named correction; not part of ownership resolution | Stage 4 | Route through a trusted resolved/AppList identity or remove the fallback without reducing canonical resolver coverage |
| R9 | AppList `last_modified` is retained in the public cache but not yet used to suppress accepted-identity enrichment refreshes | Named optimization | Stage 4 | Unchanged accepted AppIDs avoid redundant rich enrichment while explicit/manual refresh remains possible |
| R10 | RC6 could validate format locally but Android Keystore rejected the caller-supplied AES-GCM encryption IV, and Save had no provider validation gate | Corrected now; physical acceptance pending | Deliverable 1 corrected RC7 | Host cipher regression, bounded provider validator tests, exact-key Test-before-Save ViewModel/UI tests, physical Keystore persistence, and signed-device Test → Save acceptance |
| R11 | Signed RC10 can show separate Steam and Epic copies of the same public game; the Epic copy's detail remains unavailable while the Steam copy's detail works | Root cause confirmed and narrow correction implemented; signed live acceptance pending | Deliverable 1 corrected RC | Automatic `STEAM_CATALOG` review decisions from resolver v2 are reconsidered once under resolver v3, explicit Retry also revalidates current automatic review decisions, accepted identities and user decisions remain sticky, and the signed tablet groups the accepted non-Steam copy with its direct Steam card without changing Epic action authority |
| R12 | Genuine Epic exclusives have no Steam identity and cannot persist source-native canonical detail metadata | Metadata projection, persistence, and media are integrated; validated live fallback trigger remains incomplete | Task 192, then Task 190 | A complete Steam result containing only non-game/implausible hits reaches Epic CMS for the Alan Wake 2 fixture, persists truthful source-catalog provenance without a fabricated AppID, and all partial/failure/rate-limit gates remain fail-closed |
| R13 | Exact title/type/year scoring could auto-accept a candidate despite contradictory known developer/publisher evidence | Corrected in the 2026-10-03 host checkpoint; live acceptance remains open | Stage 4; owner: Steam catalog policy | Equal-year developer and publisher-only contradictions remain review-required; absent-party and publisher-corroborated cases retain automation. Real Kotlin/JUnit RED: 32 tests, 2 expected failures; GREEN: 32/32. Android variant gates are blocked by incomplete dependency caches. Historical accepted identities are not automatically detached; live impact must be checked before final closure. |
| O1 | A legitimate GOG non-game product type aborted complete ownership materialization | Corrected now | Deliverable 1 corrected RC | Pack-product parsing regression test and signed live GOG source acceptance |
| U1 | Samsung launcher policy could hide the package because the launcher was classified as a game | Corrected now | Deliverable 1 corrected RC | Manifest contract test and signed-device launcher visibility acceptance |
| U2 | The separate Copies glyph looked like a placeholder and duplicated the owned-store badges | Corrected implementation; signed live acceptance pending | Deliverable 1 next corrected RC | Grouped store badges are the only card-level Copies action; Compose click, accessibility, focus, and gamepad tests plus signed-device acceptance |
| U3 | Detail media exceeds short remaining landscape height, hiding part of the frame/controls/navigation | Host RED/GREEN and signed 41/rc15 portrait/landscape frame/carousel/player-control inspection verified on 2026-10-08 | Current unified-detail coding batch / LIVE01 | Private captures and user confirmation recorded in `2026-10-07-upstream-tablet-acceptance-progress.md`; retain these fitting constraints during U5. This bounded media proof does not close broader LIVE01. |
| U4 | Automatic support/startup popup interrupts the library | Automatic Home claim/display removed and owning RED/GREEN verified; manual Support retained; signed upgrade/relaunch acceptance remains open | Current unified-detail coding batch / LIVE01 | Owning regression proves no automatic startup claim/display; manual support remains; signed upgrade and cold relaunch show no popup without clearing preferences or changing donation state |
| U5 | Four detail tabs waste wide-window space in long undifferentiated text/list layouts | Latest user-requested unequal independent columns HOST_VERIFIED on 2026-10-12; signed portrait/landscape acceptance remains open | L1 correction checkpoint, then coherent Q1 / LIVE01 | Existing 2026-10-09 row-grid qualification is historical. New actual-screen RED/GREEN proves wider Overview reading with compact summary, independently flowing Details facts/technical sections, no empty sidebar, bounded centered1180dp body, narrow large-font wrapping and responsive gallery/disclosure state. Fresh220/220 per flavor, zero failures/errors/skips and both Android-test compilations; all owning RED identities retained plus source/action/Back/media/privacy/release guards. Existing460 schema/resource files and guarded shell/action/media/link sections unchanged. See `../reviews/2026-10-12-layout-language-runtime-cross-check.md`; no new signed/device acceptance claim |
| R14 | DREDGE must aggregate automatically with truthful metadata/artwork diagnosis | IN_PROGRESS: exact-title visible aggregation and real Explain/export verified in RC18/19; actual unchanged Android ORB accepts cross-store banners, but RC19 production does not reach Epic acquisition/comparison under generation cancellation. Acquisition/lifecycle host repair is qualified, not live closure | Signed46/RC20 DREDGE production artwork/restart and fresh automatic resolver/non-review acceptance | Current502 identities/flavor with501 passes/one explicit host capability skip; actual shared owner/Room/guards and six acquisition/lifecycle cases pass. Preserve original failed two-card/production evidence and metadata/manual/native safeguards. No tolerance rejection or sole historical resolver cause is invented; live production acceptance remains required under existing iterative authority and fresh peer coordination. |
| R15 | Original source artwork is not used as independent automatic corroboration | IN_PROGRESS: version-1 bounded DCT pHash, URL provenance, native codec, cancellable bounded transport/cache, cache-backed fetching and exact original-store fields HOST_VERIFIED (55 cases per flavor plus Android-test compilation, invocation-only fresh KSP). Original eight-error incremental setup failure is preserved. Pure policy/Deluxe-suffix and independent producer checks are HOST_VERIFIED (111 affected cases per flavor plus Android-test compilation, normal incremental invocation); fifteen-assertion producer boundary RED and seventeen-case/two-assertion optional I/O RED remain preserved. The actual repository consumer is HOST_VERIFIED (184 affected cases per flavor; Android-test tasks included, Legacy up-to-date/Modern compiled), including real Room/writer/source-URL reconsideration and v7 controls after a diagnosed fixture-only real/virtual-clock correction. Original failed GREEN/timing RED remain preserved. Production-acquisition R16, exact native-source hash qualification and broader sentinels are HOST_VERIFIED in the consolidated 414/414 per flavor gate (zero failures/errors/skips; Android-test tasks up-to-date). Original failed/RED snapshots remain intact; family consumers remain mandatory Stage E work and no exact DREDGE/live closure is inferred | Stage 4 / responsive-layout-and-grouping batch | Versioned bounded source/candidate image evidence, threshold/placeholder/edition/false-positive fixture matrix and failure-safe integration. Core alone is disconnected from resolution/projection and does not establish DREDGE matching |
| R16 | Catalog acquisition previously validated up to 15 aggregated hits despite §6.1/§6.2's ten-hit/five-details bound | Bounded production acquisition HOST_VERIFIED: owning eight-case/six-assertion RED followed by 204/204 affected cases per flavor, zero failures/errors/skips and both Android-test compilations. Shared stable title-prioritized selection caps provider/coordinator/repository at ten; truncation, malformed apps and inconsistent totals remain partial, detail validation stays five. Existing 900-game accounting/30-public-case coverage controls passed unchanged; this does not establish live useful-library coverage. Consolidated 414/414 per flavor also qualifies broader sentinels and exact native-source hashes. Focused cross-check identified additional contradictory/missing/malformed total, malformed items and unknown-type completeness edges; seven owning assertions confirmed them before the narrow correction. Complete corrected644/644 per flavor, zero failures/errors/skips and both Android-test compilations, now qualify these parser edges with every514 prior case identity retained. Previous failed/RED evidence remains intact; no live useful-library coverage is inferred | Stage 4 / Stage D repository consumer; owner: Steam catalog resolution | `acquisition-green` retains actual production-boundary and affected repository/producer/policy evidence; all earlier RED/failed snapshots preserved. Parent R5/live requirements remain open |
| D8 | Presentation grouping and copy selection do not yet explicitly support edition families and multiple installed versions | IN_PROGRESS: thirteen-case missing-projection RED, then fourteen-case/one-assertion malformed-member RED before the binding repair; pure projection and exact member mappings HOST_VERIFIED in the 428/428 affected gate per flavor, zero failures/errors/skips and both Android-test Kotlin compilations. Production native family producer is separately HOST_VERIFIED after twenty-case/twenty-assertion owning RED and the 448/448 affected gate per flavor, zero failures/errors/skips and both Android-test Kotlin compilations. Shared repository flow, real Room recreation/cancellation and actual search/source-filter/detail-key qualification are HOST_VERIFIED: Legacy489 normal-incremental and Modern489 invocation-only fresh-KSP, zero failures/errors/skips and Android-test compilations. The failed eight-error Modern incremental attempt remains preserved. Current-detail transitions, installed Run selection, exact captured preference transactions and preserving schema31→32 separation/reset are HOST_VERIFIED in the514-per-flavor guard gate, both normal incremental with Android-test compilations. ActualVM/sheet/screen consumers were implemented after22-case/20-assertion owning RED; their first637-case gate stopped at64 recorded Legacy cases with a MockK UUID matcher failure and one skip. Fixture-only correction, seven owning parser assertions and the confirmation focus-lifecycle repair were followed by the distinct complete644/644-per-flavor qualification, zero failures/errors/skips, both Android-test compilations and exact preservation of all514 baseline case identities. ActualVM/sheet/screen family consumers are HOST_VERIFIED, including captured exact edition dispatch/stale rejection/reset, conflicting preference clear, same-store Install labels, installed PLAY eligibility and nested Back/focus. All partial/failed/RED evidence is retained; signed no-install chooser inspection and separately authorized simultaneous installations remain required | Stage 4 / responsive-layout-and-grouping batch | Same family in search/library/details; labeled edition/store Install/Run selection, independent installed-copy state and guarded sibling preservation; real installs/launches separately authorized |
| P1 | Accepted Steam metadata can be overwritten by non-Steam projection | Repair now | Deliverable 1 | Provider-precedence regression test |
| P2 | Steam-first card artwork/title remains incomplete | Implemented and host-verified in Task 15 Step 4; integrated image-loading/recomposition acceptance remains open | Stage 4 / LIVE01 | Supported locale/country Steam cache supplies accepted title/artwork without new ownership; explicit title overrides and richer owned-Steam artwork remain authoritative, missing Steam art fills safely, and source artwork survives image failure. 182 owning tests per flavor, both Android-test compilations and debug APK checks pass; see `2026-10-03-resolver-detail-completion-progress.md`. Pure selection tests and packaging do not close live rendering acceptance. |
| D1 | Detail has a fifth Resources tab | Repair now | Deliverable 1 | Exactly four tabs; Resources rendered inside Details |
| D2 | Integrated install/play action bar is incomplete | Implemented and host-verified in Task 15 Step 4; integrated signed-live action acceptance remains open | Stage 4 / LIVE01 | Actual canonical-detail Compose controls route all eight supported executable operations through existing automatic copy routing, with D7 moving source-owned uninstall/export/import/options into the same page's installed wrench menu rather than removing those operations; unavailable copies create no actions, chooser preserves detail, and exact guard/requested operation reaches existing source dispatch. 188 owning passes per flavor, both Android-test compilations and debug APK checks; see `2026-10-03-resolver-detail-completion-progress.md`. Host fake-route tests do not prove live execution. |
| D3 | Approved Overview/Details fields remain incomplete | Implemented and host-verified; signed integrated acceptance remains open | Stage 4 / LIVE01 | Native technical/store/rating/language/ownership/provenance fields, independent aggregate summary with six-hour bounded reuse/stale offline/retry/cancellation, and v2-stale/v3 cache compatibility. 305 owning passes per flavor, both Android-test compilations and debug APK checks on Gradle 9.8; field matrix in `2026-10-03-resolver-detail-completion-progress.md`. Unsupported capabilities and purchase guarantees are not synthesized; host fixtures do not close runtime acceptance. |
| D4 | Canonical detail gamepad B/focus and accessibility are incomplete | Native detail/disposal/tab-aware thread/modal-sheet Back and keyboard entry focus host-verified; broader focus/semantics and live acceptance remain open | Deliverables 2–3 and Stage 4 / LIVE01 | Actual LibraryScreenContent Compose controls cover native close, exactly-once owner disposal/Back cleanup, hidden-thread isolation, visible thread-to-list-to-library B and modal Copies B without closing detail. 19 Compose passes in each flavor within the 305-case owner; remaining broader focus/accessibility, nested confirmations and device acceptance stay named work. See `2026-10-03-resolver-detail-completion-progress.md`. |
| D5 | Current Steam movie payloads use direct HLS/DASH fields, so trailers were omitted when only legacy WebM/MP4 objects were parsed | Corrected and signed-live accepted in RC10 | Closed | Current-HLS and legacy parser tests pass; source-revision invalidation refreshed old caches; DREDGE showed two moving trailers as media 1/18 and 2/18 before screenshots on SM-X910 |
| D6 | Trailer autoplay is permanently silent because `SteamVideoHero` fixes ExoPlayer volume at zero and exposes no mute/unmute action | Implemented; consolidated QA pending | Final visible-core QA | Autoplay remains muted, an accessible touch/gamepad toggle changes only ExoPlayer volume, and the choice survives media changes for the detail session; focused policy/Compose coverage and audible signed-tablet acceptance remain in the consolidated QA pass |
| D7 | Copies and Source details split the install/options experience into separate pages | Unified guarded page, scoped Install chooser and installed wrench implemented and host-verified; real modal touch and signed acceptance remain open | Current unified-detail coding batch / LIVE01 | Install opens exact owned-source selection; cancel has no effect; source information and installed wrench/options remain in the canonical page; guarded action tests, Compose navigation/Back checks, and signed-tablet acceptance |
| V1 | Reviews tab lacks the validated POC's hardened multi-page transport and identity behavior | Native foundation integrated; POC hardening pending | Task 168 | Native review pagination preserves transient recommendation identity for deduplication, follows four-attempt 429 retry, validates content type, and passes the POC-equivalent multi-page regressions |
| V2 | Public review comments and broader identity presentation were absent | Safe anonymous native comments/source/controller/actual Library Reviews reader HOST_VERIFIED on2026-10-12 after17 owning assertion REDs. Fixed localized Steam-user presentation, exact recommendation reacquisition and ephemeral author routing, native sanitized text/stable IDs, five-page bounds, cancellation/selection fencing and Back-to-list. Final436/436 per flavor, zero failures/errors/skips, includes real-Main comments/body cancellation; earlier72/72 gate executed both Android-test compilations, final tasks UP-TO-DATE. Broader identity requirements, signed/live and global focus/accessibility remain open; no author/profile persistence or fetch | Stage 5; coherent Q1 / LIVE01 for shipped reader | See `../reviews/2026-10-12-community-runtime-cross-check.md`. Safe integrated reader must pass signed acceptance; unsupported remaining public paths require evidence and explicit user-approved permanent boundary, not silent deferral |
| V3 | Bounded public review/discussion cache | No broad cache is required for the visible core; normal bounded caching and persistence are permitted and are not a privacy boundary | Closed by Task 169 | Public community transport/media no longer force `no-store` or session-only caching; sensitive account/auth fields remain excluded |
| C1 | Discussions tab lacks the validated POC's live multi-page route, representation, parser-state, and post-identity contracts | Native foundation integrated; POC hardening pending | Task 168 | Listing pagination uses `fp`, thread pagination uses `ctp`, parser failures never become false empty results, stable post identity deduplicates pages, and the 3-title/10-title POC-equivalent regressions pass |
| C2 | Discussion search/categories/layout coverage is incomplete | Named work | Stage 5 | Fixture-backed supported matrix and explicit fallback for remainder |
| C3 | Authenticated posting/voting/moderation is absent | Permanent credential boundary | Section 3.3 | Explicit external actions; no copied credentials/cookies |
| D9 | Language capabilities resolve to unknown and render an all-Unknown matrix | Public data/parser/UI boundaries diagnosed and host-qualified on 2026-10-12; signed acceptance pending | L1 checkpoint, then coherent Q1 / LIVE01 | DREDGE public appdetails is names-only, so no Boolean authority is invented. Localized Steam audio legends now use bounded provider marker structure; only known capabilities render localized labeled ticks/crosses, absent values have no cells, all-null data renders compact names. v4 refresh retains stale v2/v3 fallback; card presentation and localized genre consumers accept corrected snapshots without creating ownership. Fresh220/220 per flavor and both Android-test compilations; exact tablet cached payload was not inspected. See `../reviews/2026-10-12-layout-language-runtime-cross-check.md` |
| D10 | Individual DLC ownership is not presented | IN_PROGRESS overall; supported Steam/GOG/Epic host checkpoint HOST_VERIFIED on2026-10-12. Exact-ID/native/session/factory/actual Library Details/selector RED retained, including two native shape assertions before the single fail-closed correction. Fresh311/311 per flavor, zero failures/errors/skips; all220 L1 and309 initial-GREEN identities plus every RED identity retained,2030 frozen inputs and installed gate/profile/fail-fast/containment verified. Both Android-test Kotlin tasks executed in the preceding309/309 gate and were UP-TO-DATE in final qualification. No signed/live acceptance; required D10A remains open. See `../reviews/2026-10-12-native-dlc-runtime-cross-check.md` | 2026-10-11 DLC batch, then coherent Q1 / LIVE01 | Exact owning-store entitlement and edition mapping; unknown versus unowned, stale-source and no-false-Steam-ownership tests, integrated provider/VM/Details and privacy/action/release evidence |
| D10A | Amazon and custom copies have no verified individual native DLC catalog/constituent mapping | Required provider-coverage gap, not a permanent-boundary decision. Current Amazon `GetEntitlements` materializes owned product data but the inspected API/model exposes no DLC parent/edition association. Custom imports have no native store license inventory. Render honest unavailable/unknown; never copy Steam licenses or infer an authoritative empty catalog. No real Amazon account/entitlement was read | DLC provider-coverage follow-up before aggregate Stage 8 closure; owner: native DLC source boundary | Establish a safe authoritative catalog/constituent and native-license path with fixture-backed tests, or obtain an explicit user decision for any genuine permanent external/unavailable boundary. The current Steam/GOG/Epic host correction cannot close this row |
| C4 | Signed rc16 Reviews and Discussions both fail loading; exact sole tablet cause remains unestablished | Actual Main header-only/body-consumption and post-header cancellation defect proved by five owning assertions, then51/51 per flavor. Bounded verified Helium and native-UA public probes admit DREDGE review/listing hybrid representations and anonymous AppID-keyed comments, not Android success. Narrow callback-worker bounded body/detached response repair and off-Main parsing retain provider policy/diagnostics. Final436/436 per flavor across43 suites, zero failures/errors/skips,2054 frozen inputs and all311 DLC/L1 plus72 community/comment identities retained; both earlier Android-test compilations executed, final UP-TO-DATE | 2026-10-11 community correction HOST_VERIFIED; coherent Q1 / LIVE01 remains required | See `../reviews/2026-10-12-community-runtime-cross-check.md`. This proves a real loading defect and repair, not sole live cause. Signed native Reviews/listing/thread/comments acceptance remains open; broader V1/C1/C2/A1 requirements unchanged |
| A1 | New-path translations and broad accessibility are incomplete | 100 resolver/detail keys translated and host/resource-package verified in all 14 existing non-English locales; remaining scope open | Stage 4 Task 15 Step 4 / Stage 5 Task 16 / LIVE01 | `scripts/check_detail_localizations.py` checks duplicate/empty/missing values and exact format arguments across all locale XML files; both APKs compile/package the additions. Complete remaining copy/preference/filter/community resources, broader semantics/focus (including nested confirmation Back) and actual device accessibility. The 100-key prefix matrix does not close global A1. |
| S1 | External moves are non-transactional and can split data | Named work | Stage 6 | Journaled verified move/rollback tests |
| S2 | Storage movement lacks active-game/download guards | Named work | Stage 6 | State exclusion and cancellation tests |
| S3 | Mount/scoped-storage/device recovery is incomplete | Named work | Stage 6 | Explicit SD/USB/device matrix |
| L1 | Candidate fork's LSFG picker is unsafe and not integrated | User decision plus implementation | Stage 7 | User-approved closure or safe per-container signed RC |
| HV1 | Unbounded shared-worker Room host validation held the shared build gate for over 14 hours | Bounded containment HOST_VERIFIED on 2026-10-09 and published in feature checkpoint `166acf72`; ultimate original lifecycle/order cause remains F1 | Before the next unfiltered Stage 4 / Stage 8 host gates | 44 owner cases and 2 guard cases GREEN per flavor, interrupting cancellation/finally and eight joined Room reopen cycles; actual guarded one-minute Gradle task timeouts and worker exits in both flavors. See `2026-10-08-responsive-layout-and-grouping.md`; expected failed probes are not passing tests. |
| F1 | Aggregate broad baseline and official PR remain open | Named final work, including unlocalized original Room/SQLite lifecycle/order cause and nine PathType/two RegistryKeyFix failures from the canceled 574-case partial Legacy run | Stage 8 | Diagnostic reproduction and attributable repair before fresh aggregate broad GREEN, final cross-check, signed RC, and upstream-ready commit series; current focused containment does not close the failed/partial broad baseline |

## 16. Acceptance criteria

### Steam resolution

1. A fresh non-Steam-only canonical can discover a Steam entry through keyless Store search without an owned Steam copy or configured Steam Web API key.
2. A unique exact candidate with compatible type and developer/publisher or close-year corroboration is accepted automatically; corroborated cross-store later-Steam-year variance is neutral.
3. Multiple verified candidates auto-resolve only to the unique closest eligible Steam release at or before the source year; no-prior, tied-prior, missing-year, ambiguous, fuzzy, unknown-type, or conflicting-edition cases never merge automatically.
4. Every idempotent Store GET follows the four-attempt bounded retry contract, and exhausted rate limiting returns no catalog result or source fallback.
5. Only complete, nonpartial Steam `UNMATCHED` with no plausible candidate may use source presentation fallback; a genuine Epic exclusive maps to exact canonical metadata with `EPIC_CMS` provenance and no fabricated Steam AppID.
6. The user can search again, select a correct candidate, keep separate, reject, and reset.
7. User decisions survive restart and outrank automation.
8. Match changes never alter owning-store execution or create Steam ownership.
9. Newly mapped non-Steam cards participate in popularity filtering in All and their native source tabs.
10. Accepted identities trigger Steam details/genres/features/review count and best-effort PICS tags without false ownership.
11. Resolver progress/coverage is visible and source-agnostic.
12. The optional runtime Steam Web API key must pass bounded provider validation before Save; a different or untested key cannot be persisted, and successful persistence survives repository recreation. Missing key state never disables keyless Store resolution.
13. Passwords, API keys, authentication headers/cookies, and personal/account details are absent from exported diagnostics.

### Reviews

14. Reviews are native, paginated, independently refreshable, and support the fixed 80/20 filters.
15. Review content is bounded and sanitized; public text, AppIDs, URLs, and content IDs may use normal app-private caches and typed diagnostics, while credentials and account/profile identity remain excluded.
16. Offline/error/empty states are honest and do not blank other detail sections.
17. Unsupported authenticated actions open Steam explicitly.

### Discussions

18. Public discussion listings and fixture-supported threads render natively as plain text.
19. URL policy binds every request/redirect/thread to the trusted AppID and Steam Community host.
20. Parser failure retains a visible external fallback.
21. Public discussion text, routes, URLs, AppIDs, and content IDs are bounded and cacheable; credentials, cookies, SteamIDs, usernames/profiles, and account associations remain excluded.

### Delivery discipline

22. Each deliverable has one focused cross-check, at most one blocker correction pass, owning tests, and one signed fork RC.
23. Rewrites occur only at structurally invalid active boundaries; every non-blocking issue remains in the completion ledger with a named target or explicit user-approved permanent boundary.
24. Stages 4–8 remain part of the delivery sequence; completing RC5–RC9 does not silently close their ledger items.
25. HV1 host validation containment has fresh bounded-failure, worker-exit and Room lifecycle evidence before another unfiltered gate; a canceled/partial gate never substitutes for owning GREEN or releases the mutex over surviving execution.
