# Steam-First Library Staged Implementation Roadmap

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement one stage at a time. Steps use checkbox (`- [ ]`) syntax for tracking. Delegated agents must not invoke or spawn subagents.

**Goal:** Make GameNative Steam-first, collapse duplicate ownership safely, provide a native store-like game page, and make a roughly 900-game library discoverable through genres, multi-tag filters, and transparent popularity controls.

**Architecture:** Deliver independently testable vertical stages behind recovery boundaries. Stage 0 adds local diagnostics before behavior changes; every later stage emits structured events and ends with a design cross-check. Detailed plans are written only after the previous stage is implemented and cross-checked, so implementation discoveries can correct the next plan instead of making a large stale plan.

**Tech Stack:** Kotlin, Jetpack Compose, Room, DataStore Preferences, Hilt, Kotlin coroutines/Flow, kotlinx.serialization, OkHttp, Coil, Media3, Timber, JUnit 4, Robolectric, MockWebServer, Compose UI tests, GitHub Actions

> **Execution update — 2026-07-31:** Remaining user-visible delivery after commit `fedf889c` follows `docs/superpowers/plans/2026-07-31-steam-first-visible-delivery.md`. Its vertical-slice process and review/test budget supersede the stage-by-stage execution rule below; the approved safety and privacy contracts remain in force.
>
> **Execution update — 2026-08-08:** After signed RC4, the highest-priority missing core is automatic/manual Steam catalog resolution followed by native Reviews and Discussions. Execute `docs/superpowers/plans/2026-08-08-steam-resolution-community-visible-core.md` against `docs/superpowers/specs/2026-08-08-steam-resolution-community-visible-core-design.md`; its 80/20 classification, per-deliverable cross-checks, and completion ledger supersede older remaining tasks. Unresolved work must retain a named target or explicit user-approved boundary.

---

## Product north star

The work is successful when the user can:

1. See Steam-quality presentation wherever a match is trustworthy.
2. See one library card for one actual game even when it is owned on several stores.
3. Open a native page and quickly understand an unfamiliar game.
4. Reduce a roughly 900-game library with genres, tag Match Any/All, popularity thresholds, and popularity sorting.
5. Export a bounded diagnostic report that explains indexing, matching, metadata, filtering, detail loading, and action-routing behavior without exposing credentials or private text.

Reviews and Discussions remain in the approved design, but they follow the core library experience instead of blocking it.

## Stage execution rule

Only one stage is implemented at a time.

At the end of every stage:

- Run the stage's focused tests plus both unit-test variants:
  - `./gradlew :app:testLegacyDebugUnitTest :app:testModernDebugUnitTest`
- Run `./gradlew :app:lintLegacyDebug` when the stage changes Android UI/resources.
- Record the exact commands and outcomes.
- Write `docs/superpowers/reviews/YYYY-MM-DD-steam-library-stage-N-cross-check.md`.
- Compare the implementation commit range against the named sections and acceptance criteria in `docs/superpowers/specs/2026-07-27-steam-normalized-library-design.md`.
- List every intentional deviation. Update and commit the design before continuing when reality changes the contract.
- Confirm diagnostic events cover the stage's failure boundaries.
- Commit and push the stage and cross-check to `Darkaxt/GameNative`.

Do not start the next detailed plan while a Critical or High cross-check finding remains open.

## Stage map

| Stage | User-visible outcome | Design sections checked | Public behavior |
|---|---|---|---|
| 0. Diagnostics | Exportable local report for present and future library behavior | 15, 17-21, criterion 18 | Always-on bounded local events; manual export only |
| 1. Canonical foundation | Stable internal identities and safe source projections, with legacy UI unchanged | 5, 6, 7, 8, 16, 18.1-18.3 | Canonical projection built in shadow mode |
| 2. Deduplicated library and copy routing | One canonical card, store badges, Copies chooser, preferred copy, safe action targeting | 9, 10, 14, 18.3-18.4, criteria 1-6 | Capability flag enabled after routing matrix passes |
| 3. Steam metadata and discovery | Steam-first facets plus genre, multi-tag, and popularity filtering/sorting | 6.3, 11, 13, 18.5, criteria 7, 14-15 | Discovery flag enabled after index checkpoint completes |
| 4. Native store-like details | Shared hero shell, media-first Overview, structured Details, provenance, copy actions | 12 Overview/Details, 13-15, criteria 8-11 | Detail flag enabled with legacy source screen as recovery |
| 5. Reviews, Discussions, and hardening | Native review browsing, resilient public discussions, offline/error/accessibility hardening | 12 Reviews/Discussions, 14-15, 17-18, criteria 12-17 | Community capabilities independently enabled |
| 6. Live release and feedback | Signed prerelease APK published; user reports drive fixes through exported diagnostics | 18, 20, 21 | GitHub prerelease, then feedback patch releases |

## Stage 0 — Diagnostics first

**Detailed plan:** `docs/superpowers/plans/2026-07-27-steam-library-stage-0-diagnostics.md`

Deliver:

- Structured events with area, event, outcome, duration, session correlation, and allowlisted attributes.
- No tokens, account IDs, SteamIDs, usernames, titles, search text, install paths, URLs, review text, or discussion text.
- A bounded JSONL ring under app-private storage.
- Export and clear actions in Debug settings.
- Diagnostic tail in crash reports.
- Initial instrumentation for app startup, library filtering, game resolution, and launch routing.

Exit criterion: a release build can produce a useful report without `READ_LOGS`, network upload, or exposing forbidden values.

## Stage 1 — Canonical foundation in shadow mode

Write the detailed Stage 1 plan only after the Stage 0 cross-check. It must use the current database schema and logging API rather than guessing their final form.

Deliver:

- Immutable opaque canonical IDs.
- Account-scoped `OwnedCopyKey` values and source adapters for Steam, GOG, Epic, Amazon, and custom games.
- Room migration and DAOs for canonical games, matches, preferences, facets, tag labels, and detail snapshots.
- Deterministic title/developer/year/type normalization.
- Direct Steam identity, validated direct mappings, conservative exact matching, stored user decisions, and unmatched fallback.
- Idempotent projection that runs beside the legacy library without changing cards or actions.
- Index diagnostics containing counts and confidence categories, never game titles or account identifiers.

Exit criterion: repeated projection produces stable identities and the legacy library remains unchanged.

## Stage 2 — Deduplicated cards and source-safe actions

Write the detailed Stage 2 plan from the implemented Stage 1 types and cross-check.

Deliver:

- Canonical library projection before pagination.
- One All-tab card per canonical game and source-aware tab/count semantics.
- Owned-source badges, aliases for search, preferred copy persistence, and a Copies chooser.
- Action resolution that captures an exact copy target, revalidates it, and fails closed.
- Legacy source details remain available for extended source-specific operations while adapters are extracted incrementally.
- Manual Fix match, rejection, unmerge, and reset behavior.

Exit criterion: the full install/play/update/uninstall routing matrix passes and no ambiguous action silently chooses a copy.

## Stage 3 — Steam metadata and discovery

Write the detailed Stage 3 plan after deduplication is stable.

Deliver:

- PICS facet parsing with explicit update invalidation.
- Public Steam catalog fallback for matched AppIDs absent from the owned-Steam set.
- Cached localized tag dictionary with unlabeled-tag fallback.
- Steam review-count summary as the transparent popularity metric.
- Genre OR, tag Match Any/All, cross-group AND, popularity thresholds, popularity sorting, aliases, coverage, and persisted selections.
- Filtering over unique canonical IDs before pagination.
- A fixed 1,500-source-entry/900+-canonical-game performance fixture.

Exit criterion: the named filter matrix meets the design budget and unknown classifications return immediately when discovery filters clear.

## Stage 4 — Native store-like game details

Write the detailed Stage 4 plan against the actual action-adapter boundary produced by Stages 1-2.

Deliver:

- Cached-first `GameMetadataRepository` and Steam catalog provider.
- Shared `GameDetailViewModel` with section-specific states.
- Full-bleed shared shell with exactly Overview, Reviews, Discussions, and Details tabs.
- Media-first Overview, descriptions, feature chips, review summary, compatibility/HLTB/release information, ownership, and provenance.
- Structured Details for developers, publishers, languages, requirements, controller/multiplayer support, age/content information, DLC/achievements, and last-verified commerce data.
- Reviews/Discussions show honest unavailable/external states until Stage 5 enables their native repositories.
- A source-details recovery action opens the existing source screen for unsupported operations.

Exit criterion: matched and unmatched games use the same native presentation shell without breaking install or launch.

## Stage 5 — Community and release hardening

Write the detailed Stage 5 plan after live behavior of the core page is known.

Deliver:

- Cursor-paginated AppReviews query mappings and native cards.
- Isolated public discussion-list/thread parsing with saved fixtures and Open Community fallback.
- Bounded review/discussion caches and clear action.
- Offline, stale, empty, error, gamepad focus, screen-reader, and process-recreation validation.
- Supported Room upgrade-path tests and recovery-mode validation.
- Final scale, memory, and regression pass.

Exit criterion: all design acceptance criteria intended for the first release are either passing or explicitly revised in the design cross-check.

## Stage 6 — Live release and complaint-driven validation

The user's live validation authorizes publishing a prerelease when Stages 0-5 and their cross-checks are complete. Publishing still requires evidence that the fork's signing secrets and workflow are ready.

- [ ] Confirm `Darkaxt/GameNative` has every secret consumed by `.github/workflows/tagged-release.yml`.
- [ ] Increment `versionCode` and set a prerelease `versionName` in `app/build.gradle.kts`.
- [ ] Run `./gradlew :app:testLegacyDebugUnitTest :app:testModernDebugUnitTest`.
- [ ] Run `./gradlew :app:lintLegacyDebug :app:bundleLegacyRelease :app:bundleModernRelease`.
- [ ] Commit and push the release version.
- [ ] Create and push a `v<version>-steam-library-rc<N>` tag.
- [ ] Watch the tagged-release workflow to completion and verify the APK assets exist.
- [ ] Install the published APK and verify startup, library sync, one detail page, one filter combination, and diagnostic export.
- [ ] Give the user the APK link and the path `Settings → Debug → Export diagnostic report`.
- [ ] Wait for user complaints; for each report, request the exported diagnostic report and exact visible symptom.
- [ ] Correlate the report by session/event sequence, reproduce when possible, patch, rerun the owning stage's tests/cross-check, and publish the next prerelease.

The diagnostic report is manually shared. It is not uploaded automatically and does not reuse PostHog analytics.

## Cross-check document template

Every stage review uses this exact structure:

```markdown
# Steam Library Stage N Design Cross-Check

**Implementation commits:** `<first>..<last>`
**Design:** `docs/superpowers/specs/2026-07-27-steam-normalized-library-design.md`
**Plan:** `<stage plan path>`

## Verified requirements
- [x] Requirement with test/manual evidence

## Intentional deviations
- None, or the design section and committed correction

## Diagnostics coverage
- Event names and failure outcomes exercised

## Verification evidence
- Command and observed result

## Open findings
- None, or severity + owner; High/Critical blocks the next stage
```
