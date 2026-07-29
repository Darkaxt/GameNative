# Steam Library Stage 1 Design Cross-Check

**Implementation commits:** `93b88e9a`–`e76d5da9` (inclusive)  
**Completed:** 2026-07-29  
**Design:** `docs/superpowers/specs/2026-07-27-steam-normalized-library-design.md`  
**Plan:** `docs/superpowers/plans/2026-07-28-steam-library-stage-1-canonical-foundation.md`

## Gate scope

This gate covers the Stage 1 canonical foundation in shadow mode. Existing source tables, public library cards, frontend IDs, launch IDs, and storefront-specific install/play/update/uninstall/save paths remain authoritative. Public canonical cards, copy selection, preferred-copy routing, native details, and discovery filters remain Stage 2 or later work.

## Verified requirements

- [x] **Canonical and owned-copy identities are stable and app-private (Sections 7.1–7.3).** Canonical IDs are lowercase opaque UUIDs. `OwnedCopyKey` remains structured as account scope, source, and stable source ID. Account scopes are non-secret, source-domain-separated SHA-256 discriminators. Stage 1 projection code does not populate canonical storage with credentials, tokens, paths, manifests, containers, or volatile install/download/save state.
- [x] **Public identity and action-routing contracts did not adopt canonical identity (Sections 5.1, 6, 10; Phase 1).** The exact legacy-file diff from the Stage 0 gate is empty for `PluviaMain`, `LibraryViewModel`, `FrontendSyncManager`, and `LibraryItem`. A source scan found canonical identity types only in additive canonical data, DAO, DI, resolver, projection, mutation, and source-adapter code—not in public cards or executable action paths. Stage 1 did change source authentication/synchronization internals to bind ownership to lifecycle generations; their focused tests are recorded below.
- [x] **Room storage is additive, constrained, and non-destructive for supported history (Sections 7 and 18.1).** Schema 27 contains canonical games, account-scoped relationships, preferences, facets, snapshots, ownership ledgers, foreign keys, uniqueness, reverse indexes, and explicit migrations. Unsupported versions retain the already-documented recovery limitation rather than gaining a blanket destructive fallback.
- [x] **Both committed feature-branch schema-26 shapes upgrade safely.** Commit `732da653` first committed/pushed schema 26 without ownership-ledger tables; commit `b5aaa4a3` later committed/pushed schema 26 with them. The immutable `26.json` export is restored to the first shape. Migration 26→27 creates missing ledgers when absent, preserves later-shape ledger data when present, and adds `lifecycle_generation` with the legacy-not-ready default.
- [x] **Ownership is account- and lifecycle-scoped (Sections 7.2, 10, 14).** Steam, GOG, Epic, and Amazon use durable per-source generations and exact-generation ownership reads. Account A→B→A cannot reuse A's old presence snapshot. Missing scope and lifecycle-unavailable snapshots retire cached presence; ordinary read failures preserve it.
- [x] **Steam ownership becomes visible only after the matching license transaction commits.** The ready generation is durable, lifecycle advancement clears it, stale callbacks cannot mark a newer generation ready, and PICS work is queued only after readiness publication.
- [x] **Lifecycle changes cannot interleave with a projection commit.** Account-sensitive adapter batches carry their captured generation. Final generation validation and the complete Room transaction share one serializer with Steam transitions/license replacement/cleanup and GOG/Epic/Amazon credential lifecycle changes. Stale batches fail before database mutation; a transition started during projection waits for commit.
- [x] **Steam PICS facets are revisioned without erasing local workshop state (Sections 7.5 and 18.2).** Genre, category, and tag shapes are parsed independently from the UFS parser revision. PICS replacement rereads and preserves workshop fields transactionally.
- [x] **Automatic matching remains conservative and convergent (Section 8).** Direct Steam identity wins first. User decisions outrank automation. Trusted mappings require the supported version and one-to-one validation. Exact matching requires compatible type plus known developer or year corroboration; conflicts and ambiguity fail closed. Self-derived fallback canonicals are not accepted as corroborating evidence. Current automatic decisions reevaluate newly available evidence while true no-ops preserve canonical ID and timestamps.
- [x] **Projection is deterministic, atomic, and idempotent (Sections 6, 8, 14).** Sources use the fixed Steam, GOG, Epic, Amazon, Custom order. Complete, partial, unavailable, and lifecycle-unavailable semantics are distinct. Partial snapshots retain omitted-copy facets. Resolver failure rolls back the run. Reassignment transfers dependents and deletes a zero-reference old canonical; a still-referenced canonical is retained.
- [x] **Manual decisions and grouping mutations preserve user intent (Sections 5.5, 7.3–7.4, 8).** Confirmation, correction, merge, unmerge, rejection, reset, preference merge, and failure rollback are transactional. Direct Steam keys cannot be reassigned. Rejecting the current Steam association now detaches only the selected grouped copy, or clears the identity in place for a sole relationship, while persisting sticky `USER/MANUAL/REJECTED` evidence.
- [x] **The shadow coordinator is bounded and production-failure-contained (Sections 14, 17, 19).** Triggers are conflated, adapters collect before projection, cancellation remains cancellation, source failures become typed unavailable results, and gate, clock, runner, and adapter failures do not terminate future rebuilds. The production diagnostic facade swallows persistence failures; arbitrary test-sink exceptions are not claimed as a contained boundary.
- [x] **Library-scale behavior is proven offline (acceptance criterion 15 foundation).** The 1,500-copy fixture converges to 900 canonicals: 900 direct Steam identities and 600 exact-metadata GOG copies grouped with their corresponding Steam canonicals. A second run is exactly idempotent, including IDs, rows, timestamps, match buckets, and facets.
- [x] **Stage 0's privacy contract remains intact (Sections 15 and 17; criterion 18).** Feature diagnostics remain bounded and manual-export only, with no upload path. Stage 1 emits only source names, fixed outcomes/reasons, counts, durations, match methods/confidences, HTTP status where applicable, exception classes, and short correlations. Raw logcat/crash export remains separate.

## Gate remediations incorporated

The final implementation includes these evidence-driven corrections:

- `be3cc510` — bind ownership ledgers to durable account lifecycle generations.
- `07fc56fe` — gate Steam ownership on exact-generation readiness.
- `16d0f97d` — migrate both committed schema-26 variants.
- `de484805` — reject stale GOG authentication responses before lifecycle or credential mutation.
- `edc041d5` — exclude resolver self-evidence and reuse fallback identity without preserving unsupported Steam association.
- `32121f95` — reevaluate same-version automatic decisions while preserving true no-op timestamps.
- `baa3e2f6` — transfer dependents and remove zero-reference canonicals after automatic reassignment.
- `7912888a` — serialize lifecycle validation plus projection commit against account transitions.
- `e76d5da9` — detach a non-Steam copy from the current Steam identity it rejects.

## Intentional deviations and exclusions

- **Schema 27 instead of the plan's original schema 26 endpoint.** Schema 26 had already been committed and pushed in two forms before durable lifecycle binding was added. Version 27 is the required forward-only repair and is now the Stage 1 endpoint.
- **Legacy public cards and actions still read raw source models.** This is an intentional Stage 1 boundary, not an implementation gap. Stage 2 must introduce public canonical cards and source-safe action selection without retroactively claiming Stage 1 changed those contracts.
- **No optional network resolver was enabled.** SteamGridDB/HLTB-style candidates remain untrusted or review-required until a later stage supplies and validates an explicit provider contract.
- **Final migration instrumentation used an isolated emulator.** A temporary `GameNativeStage1` AVD on `emulator-5556` was created so the already-running `emulator-5554` session was not touched. The first attempt hit a fresh-boot startup ANR before running tests; after the isolated AVD finished boot-time initialization, the retry ran all 22 migration tests successfully. The temporary AVD was then stopped and deleted.

## Diagnostics coverage

Two manually exported Legacy release reports were inspected:

- `%LOCALAPPDATA%/Temp/gamenative-stage1-diagnostics.txt` (SHA-256 `f1b1b6b44cdd40dd3001c5015ec1b378a19ccaac0ce0eb3c5ebbea5db39ffcb4`) recorded two successful empty shadow projections. Each reported Steam/GOG/Epic/Amazon as `MISSING_ACCOUNT_SCOPE`, Custom as available with zero results, and aggregate zero canonical/copy counts.
- `%LOCALAPPDATA%/Temp/gamenative-stage1-live-match-diagnostics.txt` (SHA-256 `34fbdb224213d67a0d31f9b14c209b1909b2eb81ee0e88e100f50c10f5d3e159`) recorded a synthetic Custom copy as `UNMATCHED/UNMATCHED`, with one source result, one canonical, and one copy on two consecutive idempotent projections.
- Both reports state `Upload: manual export only` and contain only approved aggregate/fixed-vocabulary attributes. Inspection found no game title, candidate title, source-native ID, account scope, account identifier, username, search text, path, URL, token, review body, discussion body, or free-form private content.
- The synthetic Custom source data was removed after validation.

## Verification evidence

### Focused JVM and Robolectric tests

The following focused classes passed in both Legacy and Modern. Windows test processes were kept focused where Room/Robolectric class-order contamination had previously been observed.

- `CanonicalGameResolverTest`
- `CanonicalProjectionEngineTest`
- `CanonicalProjectionScaleTest`
- `CanonicalMutationRepositoryTest` (16 tests per flavor after the rejection remediation)
- `OwnedCopySourceAdapterTest`
- `SteamOwnershipReadinessTest`
- `AccountLifecycleStateTest`
- `GOGAuthManagerTest`, `EpicAuthManagerTest`, and `AmazonAuthManagerTest`
- `RoomMigrationTest`, covering the 26→27 migration body with both missing-ledger and existing-ledger preconditions

Representative final commands:

```bash
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.library.canonical.CanonicalGameResolverTest"
./gradlew :app:testModernDebugUnitTest --tests "app.gamenative.library.canonical.CanonicalGameResolverTest"
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.library.canonical.CanonicalProjectionEngineTest"
./gradlew :app:testModernDebugUnitTest --tests "app.gamenative.library.canonical.CanonicalProjectionEngineTest"
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.library.canonical.CanonicalProjectionScaleTest"
./gradlew :app:testModernDebugUnitTest --tests "app.gamenative.library.canonical.CanonicalProjectionScaleTest"
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.library.canonical.CanonicalMutationRepositoryTest"
./gradlew :app:testModernDebugUnitTest --tests "app.gamenative.library.canonical.CanonicalMutationRepositoryTest"
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.library.canonical.source.OwnedCopySourceAdapterTest"
./gradlew :app:testModernDebugUnitTest --tests "app.gamenative.library.canonical.source.OwnedCopySourceAdapterTest"
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.library.canonical.SteamOwnershipReadinessTest"
./gradlew :app:testModernDebugUnitTest --tests "app.gamenative.library.canonical.SteamOwnershipReadinessTest"
./gradlew :app:testLegacyDebugUnitTest \
  --tests "app.gamenative.library.canonical.AccountLifecycleStateTest" \
  --tests "app.gamenative.service.gog.GOGAuthManagerTest" \
  --tests "app.gamenative.service.epic.EpicAuthManagerTest" \
  --tests "app.gamenative.service.amazon.AmazonAuthManagerTest"
./gradlew :app:testModernDebugUnitTest \
  --tests "app.gamenative.library.canonical.AccountLifecycleStateTest" \
  --tests "app.gamenative.service.gog.GOGAuthManagerTest" \
  --tests "app.gamenative.service.epic.EpicAuthManagerTest" \
  --tests "app.gamenative.service.amazon.AmazonAuthManagerTest"
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.db.migration.RoomMigrationTest"
./gradlew :app:testModernDebugUnitTest --tests "app.gamenative.db.migration.RoomMigrationTest"
```

Owning-task verification also passed for identity/entity constraints, account-scope providers, Steam PICS parsing and DAO replacement, mutation/projection rollback, coordinator containment, and diagnostics privacy in both flavors.

### Android-test source and release build

```bash
./gradlew :app:compileLegacyDebugAndroidTestKotlin :app:compileModernDebugAndroidTestKotlin
```

- **Passed.** Both migration Android-test source sets compile.

```bash
ANDROID_SERIAL=emulator-5556 ./gradlew :app:connectedLegacyDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.gamenative.db.CanonicalMigrationTest
```

- **Passed on the isolated Android 15 AVD: 22/22 tests.** The first fresh-boot attempt ended in a startup ANR before executing any test; the settled-device retry completed all supported upgrade, historical schema-26, recovery-limitation, constraint, and cascade cases with zero failures. The separate AVD was removed afterward.

```bash
git diff --exit-code 3b6af8a3..HEAD -- \
  app/src/main/java/app/gamenative/ui/PluviaMain.kt \
  app/src/main/java/app/gamenative/ui/model/LibraryViewModel.kt \
  app/src/main/java/app/gamenative/sync/FrontendSyncManager.kt \
  app/src/main/java/app/gamenative/data/LibraryItem.kt
```

- **Passed with no output.** The named public legacy files are unchanged from the Stage 0 gate.

```bash
./gradlew :app:assembleLegacyRelease
```

- **Passed after the final rejection remediation.** The minified Legacy release completed successfully; existing AGP compileSdk warnings are unrelated to Stage 1. This proves release buildability, not signed-in account behavior.

### Earlier Stage 1 release/device observation

A signed Legacy release build was installed and launched on an Android 15 emulator during Stage 1 implementation. Settings exported the two reports above. The shadow coordinator rebuilt successfully, an empty signed-out profile produced typed source-unavailable results, and a synthetic Custom copy produced one unmatched canonical without changing the existing public source-native card/action behavior.

## Baseline limitations and evidence gaps

- Final-HEAD full-matrix attempts were run separately with `./gradlew :app:testLegacyDebugUnitTest --continue` and `./gradlew :app:testModernDebugUnitTest --continue`. Both reproduced the first shared Windows/path-sensitive failures (`PathTypeTest` and `RegistryKeyFixTest`), then reported the order-contaminated `AccountLifecycleStateTest.concurrentAdvancesAreMonotonic` and stopped making progress; both processes were terminated rather than represented as complete runs. That lifecycle test and every owning Stage 1 class pass when run in focused processes. The Stage 0 baseline remains the only completed repository-wide count: 874 Legacy tests with 63 failures, 62 failure names shared with pre-Stage-0 and one unrelated test that passed alone.
- The Stage 0 lint run reported 32 errors and 1,955 warnings; its first error was the pre-existing API-29 `FileObserver` constructor. Stage 1 does not claim a green global lint or unit suite. Focused owning tests in both flavors, 22/22 migration instrumentation tests, exact legacy-contract diff, and final release compilation are the Stage 1 evidence.
- No signed-in multi-store account matrix was exercised on device. Exact lifecycle, account-switch, readiness, stale-callback, and projection-transition behavior is deterministic test evidence. A signed-in matrix remains required before public canonical behavior is enabled; complaint-driven prerelease feedback is supplemental evidence after that gate.

## Open findings

- **Medium — repository-wide verification baseline; owner: project/upstream test hygiene.** Full unit and lint tasks remain unsuitable as clean gates on this Windows checkout. This is inherited from Stage 0; no focused Stage 1 test is failing.
- **Low — Epic snapshot defense in depth; owner: Stage 2 hardening.** The ownership-ledger writer excludes Epic DLC/Unreal entries, while the snapshot adapter trusts that filtered ledger. Reapply the same visibility predicate in the adapter before public canonical cards consume it.
- **Low — coordinator fallback generation token; owner: canonical maintenance.** If an adapter escapes its own exception handling, the coordinator creates an unscoped `SOURCE_READ_FAILED` fallback without a lifecycle token. The engine treats that batch as mutation-free, so it cannot retire or rewrite ownership; carrying a token would make the invariant more uniform but does not block Stage 1.
- **Low — live signed-in coverage; owner: later public prerelease.** Multi-account Steam/GOG/Epic/Amazon behavior remains offline-tested rather than device-observed.

**Gate result:** No unresolved Critical or High finding. Stage 1 satisfies its shadow-foundation exit criterion; Stage 2 planning may begin, while the listed lower-severity items remain explicit follow-up work.
