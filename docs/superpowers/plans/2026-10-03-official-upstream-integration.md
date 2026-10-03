# Official Upstream Integration Implementation Plan

> **For agentic workers:** Execute inline. Do not spawn subagents. Preserve the dirty main checkout; work only in `sync/official-2026-10-03`.

**Goal:** Integrate official `cdd82053255c00ada1f58ae63372576b519867b8` without reducing the fork's feature completeness, ownership authority, package identity, or upgrade preservation.

**Architecture:** Resolve complementary changes at their existing boundaries. Canonical identity/presentation and account-scoped source actions remain distinct. Published fork schemas 26/27 remain immutable; a new combined schema and explicit preservation migrations must reconcile official/fork version collisions before the merge is committed or published.

**Tech Stack:** Kotlin/Java, Compose, Room 2.8.4, Gradle 8.12.1, owning JVM/Robolectric tests.

**Authority:** User goal authorization and subsequent instructions to synchronize official changes before selecting further fork work and to assess overlapping designs by feature completeness.

## Integration decisions

| Area | Official capability | Fork capability / acceptance boundary | Resolution |
| --- | --- | --- | --- |
| Store details | Source-native Steam/GOG details; title-only supplements for Epic/Amazon; revised legacy detail layout | Verified canonical Steam identity, manual correction, provenance, Epic fallback, cached media and source-safe actions | Retain canonical resolver and detail/community UI. Integrate source-native legacy detail breadth; title-only supplements are not identity authority. D3 requires a field-by-field integrated reconciliation, not a closure based on this merge. |
| Community | Store review summary | Native bounded Reviews/Discussions, stable pagination identities, typed partial/error states | Retain native providers and UI. Summary is complementary, not replacement. |
| LSFG | Native playback backend alongside lsfg-vk; installed Steam DLL discovery | L1 requires an explicit safe managed manual per-container import decision | Integrate backend; L1 remains open. |
| Storage | Adopted primary volume recognition, hardlink-aware size accounting, native deletion/download engines | Source-safe runtime paths plus required journal/rollback/active-operation/recovery work | Combine path/channel contracts and owning tests. S1–S3 are not closed by adopted-volume support. |
| Library | Hidden Steam/GOG metadata, curated lists, configurable tabs, VR classification | Canonical deduplication, source ownership, discovery facets, favorites, render-generation guards and retry/cooldown | Port UI/filter behavior into canonical and legacy paths while retaining render authority. Hidden presentation must not retire entitlement or remove copies from action authority. |
| Accounts | Family lender preferences, Epic build-version/backfill enrichment | Complete fail-closed ownership snapshots, lifecycle generations, atomic credential changes | Combine enrichments with existing complete-snapshot and lifecycle authority. |
| Packages | Official launch deep links, OAuth callbacks, analytics sanitation | Package-derived launch action, side-by-side paths, updater/analytics guards, persistent signer | Retain fork channels and package-scoped launch action. Do not invent new OAuth schemes or bypass package restrictions in proprietary upstream binaries. New shared deep-link behavior needs host tests and later live acceptance. |

## Task 1 — Resolve complementary source conflicts

- [x] Preserve fork download branding and both jsoup/LZMA notices.
- [x] Retain fork version code 40 rather than regress to official 23. This merge does not declare a new release version.
- [x] Retain PICS discovery fields alongside official Steam VR columns.
- [x] Combine startup account lifecycle/Nexus initialization and gate official analytics sanitation by release channel.
- [x] Retain synchronous Steam session clearing and clear new family lender preferences.
- [x] Retain package-derived launch action alongside official VIEW run parsing and Discord callback.
- [x] Integrate native Amazon deletion and downloader improvements with sanitized conflict-boundary logs; retain fork temporary-file Kotlin fallback. Do not claim transactional download/storage completeness.
- [x] Preserve Epic atomic credential/lifecycle writes; expose the guarded local credential reader for official offline launch.
- [x] Combine Epic latest-build enrichment and GOG hidden flags with fail-closed materialization.
- [x] Combine Steam identity/generation readiness and official exception isolation/family preference refresh.
- [x] Retain canonical source/detail/community routes while wiring official curated controls and AI-run callbacks.
- [x] Combine custom-artwork load-state recovery with canonical cards and retain both sets of storage tests.
- [x] Audit auto-merged changes as well as conflict blocks; source authority and channel neutrality can regress without a textual conflict.

## Task 2 — Reconcile library filtering

- [x] Add owning regressions for curated selection, VR classification, hidden Steam/GOG presentation, mixed-source cards, and configurable tabs.
- [x] Run the regressions before the semantic port; compilation/environment errors are not behavioral RED.
- [x] Integrate filters/counts into both canonical and legacy paths without substituting stale legacy publication for canonical render-epoch authority.
- [x] Keep hidden ownership/copy actions authoritative and preserve disclosure, favorites, discovery and pagination behavior.
- [x] Verify AI/diagnostic launch callbacks cannot escape canonical action revalidation.

## Task 3 — Reconcile database version collisions

- [x] Preserve fork schemas 26/27 from HEAD, not official exports with the same version numbers.
- [x] Isolate transitional host-generated schema exports through `-ProomSchemaDirectory`; do not let a transitional build overwrite historical exports.
- [x] Add real-SQLite owning tests using fork and official historical schema shapes, with row preservation and exact target-schema validation.
- [x] Reproduce the missing merged upgrade path before adding it.
- [x] Implement the new combined schema and preservation paths, including missing canonical/PICS/lifecycle, GOG hidden, mod target/index and Steam VR fields.
- [x] Preserve the explicitly recorded old-version 7–16 recovery limitation; do not extend destructive recovery to modern installs.
- [x] Generate the new export, verify immutable historical exports, and test registered builder paths as well as SQL bodies.

**Database checkpoint:** The merged target is version 29, with direct shape-aware preservation paths from 26/27/28. Seventeen fixture tests now cover shared 17–25 through registered auto/explicit paths, both collided published histories, the later fork-26 ledger shape, and deleted recipe/manifest ID high-watermarks (including empty tables). Four recovery tests cover target-29 diagnostics and retained legacy pending markers. The new 24→25 tests exposed lost deleted-ID sequences in both rebuilt mod tables; those sequences are now captured before rebuild and restored afterward. All 21 database tests passed on Legacy (`D:/Temp/gamenative-upgrades-green-VNnQ6I.log`); the full separate-flavor batches remain pending. Published fork schemas 26/27 were retained byte-identical to HEAD; export 29 was checked against the generated scratch export and will be rechecked before publication.

## Task 4 — Verify and publish the host checkpoint

- [x] Run owning Legacy/Modern unit tests, including policy/normalization, migrations, canonical filtering, source-action guards, channel launch and relevant storage tests. Keep opt-in live providers disabled.
- [x] Check merge markers, semantic diff, ancestry, schema immutability and main-checkout preservation.
- [x] Record exact passes/failures, equivalence decisions and remaining acceptance. Do not silently close visible-core ledger items.
- [x] Commit the merge and push only to the fork without force after host verification.
- [ ] Resume the remaining host roadmap; pause before actual live endpoint/device acceptance.

## Evidence so far

- `./gradlew --no-daemon --no-parallel help`: successful, 42 seconds; dependency/configuration blocker is restored. This does not establish Android compilation.
- Actual-source pure policy/normalization runner: 32 tests pass on the in-progress source state. It uses Kotlin 2.0.21, not the project's Android compiler gate.
- Initial Android owning run failed at test compilation: upstream `GOGManagerPlayTaskTest` used the pre-ledger constructor. Test setup was reconciled; its four play-task tests then passed.
- Behavioral RED: curated filtering returned extra cards in two tests; five migration tests failed because registered direct preservation routes to 29 were absent.
- GREEN: five exact fork/official history fixture tests pass through the registered Room builder after migration implementation. Three canonical curated regressions pass. Command: `:app:testLegacyDebugUnitTest --tests app.gamenative.ui.model.CanonicalUpstreamFilterTest --tests app.gamenative.db.migration.MergedRoomMigrationTest` with isolated schema output (the run also selected two incorrectly packaged class names; they supplied no coverage).
- Fixture setup repairs: short temporary database paths avoided Windows SQLite open failures; quoted SQL identifiers handled the historical `order` column. These failures were test-environment/setup errors, not behavioral RED.
- Background launch fallback was blocked by a missing worktree watchdog registry. Both watchdogs were canceled; subsequent runs used foreground execution. No background operation remains active.
- Behavioral RED and GREEN established for hidden Steam/GOG presentation, explicit Hidden collection reveal, mixed-source admission, VR-only/supported buckets, and synchronous curated/hidden/tab callback input supersession. The 113-test Legacy batch (81 ViewModel + 17 discovery + 9 upstream filters + 5 migrations + 1 scale) passed after test-fixture isolation repairs.
- Test-fixture isolation repairs: use plain Android Application in ViewModel tests to avoid production coordinator startup; supply every preference-backed input in the pure scale fixture; settle initial DAO loading before measuring one deliberately blocked long legacy render. No assertions were relaxed.
- Native metadata projection RED: Steam/GOG runtime adapters and canonical assembly initially dropped VR/hidden flags. After wiring native fields, 146 tests passed (116 runtime adapters, 21 canonical repository, 9 upstream filters).
- Curated metadata-emission supersession subsequently passed. Special graphics/diagnostic/AI callbacks are wired through PLAY revalidation and forward the fresh LibraryItem identity; owning behavior and source-wiring RED/GREEN are recorded below.
- No agents, ADB, APK release or device acceptance executed. Host tests are being isolated from production startup; opt-in live-provider tests are not deliberately selected.

## Safe checkpoint — proxy restart, 2026-10-03

**Historical checkpoint: PAUSED_BY_USER_FOR_PROXY_RESTART; subsequently resumed by the user's “continue”. Current status: IN_PROGRESS.** The parent upstream integration is unfinished, not complete. The user discarded the DroidDeck request and explicitly resumed the existing native-library approach, then requested this safe checkpoint before restarting the Claude-GPT proxy. Preserve that scope; do not infer new live-testing or release permission from restart/completion events.

### Preserved workspace

- Worktree: `C:/Users/darka/Documents/Projects/Android/GameNative/.claude/worktrees/steam-resolution-community-docs`.
- Branch: `sync/official-2026-10-03`.
- HEAD: `1358240396e16d3864d4715901a5c9a55c75ff2b`.
- MERGE_HEAD: `cdd82053255c00ada1f58ae63372576b519867b8`.
- All textual conflicts are resolved; the merge remains uncommitted, with staged merge content plus unstaged/new integration changes. Do not reset, clean, stash, abort, or replace it.
- The original checkout remains on `codex/steam-normalized-game-details-spec`, HEAD `8311ec59f47ac821ce3d31c036b4bc3b6d83961c`, with its twelve tracked modifications and the two media-layout source/test files retained. Never `cd` there or overwrite that work.
- No merge commit, push, APK publication, device acceptance, or DroidDeck integration occurred.
- Compatibility/side-by-side IDs, version code 40, persistent signing identity, canonical ownership/actions/community, and the existing visible-core ledger remain authoritative.

### Completed evidence, with chronology

1. Special-launch helper behavioral GREEN: two tests after the implemented PLAY dispatch. Full wired Legacy action/router/guard/ViewModel batch: **161 passed** (`D:/Temp/gamenative-wired-actions-20261003.log`).
2. Menu-wiring regression was deliberately RED against bypass callbacks, then GREEN after restoration. Legacy null-guard exactly-once behavior is covered. The source-wiring check supplements, rather than replaces, real guard/runtime tests.
3. Pre-pause Legacy owning batch: **362 tests, 361 passed, one explicit Windows hardlink skip, zero failures** (`D:/Temp/gamenative-integration-legacy-20261003.log`). Five selectors were incorrectly packaged and contributed no coverage.
4. Corrected Modern batch: **406 tests, 405 passed, one explicit Windows hardlink skip, zero failures**, twenty matched classes (`D:/Temp/gamenative-integration-modern-resumed-20261003.log`). This verifies the latest special-launch wiring and eight migration/four recovery cases, but predates the new GOG/Epic provider fixes below.
5. The formerly unmatched five classes were selected correctly in the next Legacy RED run: candidate policy, normalization, release channel, GOG play tasks, and runtime paths all passed. That run had **54 tests, three expected provider failures** (`D:/Temp/gamenative-provider-audit-red-20261003.log`).
6. Fresh read-only checks: repository schemas 26/27 exactly match HEAD; export 29 matches scratch; no unmerged paths; unstaged `git diff --check` clean. Staged whitespace check remains nonzero (11,145 issue lines, including upstream native/vendor formatting); do not describe it as clean.

### Latest source changes awaiting final verification

- `GOGManager.refreshHiddenIds`: capture GOG lifecycle generation before fetch; propagate returned CancellationException; serialize generation/credential check and hidden-flag transaction against account lifecycle changes; discard retired responses. Comment now reflects cancellation semantics. New six-case `GOGHiddenRefreshTest` established RED for stale-account publication and swallowed cancellation.
- `EpicManager.fetchAssetBuildVersions`: rethrow CancellationException before optional enrichment failure handling. New four-case `EpicAssetBuildVersionTest` established RED for swallowed cancellation.
- No source/schema changes were made by the native host experiment below.

### Interrupted batch and immediate next action

- Tracked batch `b9datt222` was explicitly stopped at the user's checkpoint request. Its log is `D:/Temp/gamenative-integration-final-N6CkAF.log`.
- Important selector correction: placing two Gradle test tasks before one set of `--tests` filters does not bound both tasks. The attempted combined command let Legacy run the full suite. **Invoke each flavor separately with the complete selector set immediately after that task.** Do not claim that interrupted command supplied two successful owning batches.
- XML files at interruption contain partial/previous data and are not final suite evidence. Available Modern XML showed 520 tests, one failure, one skip; GOGHiddenRefreshTest had six passes. The Epic failure now propagates cancellation correctly but the assertion requires object identity: coroutines stack-trace recovery creates an equivalent CancellationException. Repair that test to check cancellation type/message (and appropriate causal semantics), not `assertSame` across `withContext`; rerun to obtain genuine GREEN. Do not weaken the requirement that cancellation propagates.
- Concrete next action after restart/resumption: repair that fixture assertion, then separately run the ten provider tests plus lifecycle/auth owners on Legacy and Modern; next rerun the complete corrected integration owning batches. No live providers or device testing.
- TaskStop terminated the shell but left its Gradle daemon BUSY. Daemon log confirmed PID 89916's current build belonged to this worktree; that exact daemon and its child workers were terminated with taskkill. Verify quiescence on resume rather than assuming another session's processes are ours.
- Watchdogs `d4c88051` and `c61a63c4` were canceled. No active tracked operation or watchdog is intended to survive this checkpoint.

### Native-host experiment and remaining integration gates

- Linux/WSL overlay core compiled with AddressSanitizer/UBSan using the published C bench in an isolated scratch directory. Running on mounted NTFS `D:/Temp` failed permission-mode assertions (test_core.c lines 414, 444, 445 among reported failures); compiler also emitted upstream test warnings. **This bench is not GREEN.** Rerun in a unique Linux-native filesystem directory before attributing these failures to source. Scratch: `D:/Temp/gamenative-overlay-host-kk8pvrrc`; two earlier wrapper attempts failed at shell/path setup, not behavioral RED.
- WSL Cargo/Rust are 1.75.0. Compatibility with the merged Rust lock/dependencies has not been established; no Rust or Android native build acceptance is claimed.
- Remaining IDs for this integration checkpoint:
  - **U01 — provider owning verification:** target GOG/Epic enrichment and lifecycle/auth tests; accept only fresh separate-flavor GREEN after the cancellation fixture repair.
  - **U02 — native host/Android build gate:** target merged overlay/download/LSFG integration; distinguish filesystem/toolchain setup from code failures, preserve proprietary package restrictions, and record actual successful build/bench evidence before acceptance.
  - **U03 — older preservation routes:** target shared/published versions 17–24 through the registered Room builder to 29; preserve rows/FKs/defaults and keep the explicit 7–16 destructive-recovery limitation unchanged.
  - **U04 — final integration audit/publication:** target auto-merged account/channel/native boundaries, schema/ancestry/merge-marker/semantic-diff checks and original-checkout preservation; update exact evidence before a verified merge checkpoint pushed only to `fork`, without force. No official-origin push or unverified release.
- These are named outstanding gates, not silent roadmap deferrals. D3, L1, S1–S3 and the visible-core ledger are not closed by upstream feature names or these host counts.
- Resume the original roadmap only after the integration gate; pause before actual live endpoint/device acceptance. Do not spawn agents.

### Corrected selector reference

Use one task per invocation (`:app:testLegacyDebugUnitTest`, then a separate `:app:testModernDebugUnitTest`) and `-ProomSchemaDirectory=D:/Temp/gamenative-merge-schema-7uy0ky0n/schemas`.

Core owners: `app.gamenative.ui.model.CanonicalLibraryViewModelTest`, `app.gamenative.ui.model.CanonicalUpstreamFilterTest`, `app.gamenative.db.migration.*Test`, `app.gamenative.library.canonical.CanonicalLibraryRepositoryTest`, `app.gamenative.library.canonical.runtime.OwnedCopyRuntimeAdapterTest`, `app.gamenative.library.discovery.CanonicalDiscoveryFilterTest`, `app.gamenative.library.canonical.CanonicalLibraryScaleTest`, `app.gamenative.ui.screen.library.appscreen.CanonicalActionExecutionTest`, `app.gamenative.library.canonical.action.*Test`.

Previously mispackaged owners: `app.gamenative.library.canonical.catalog.SteamCatalogCandidatePolicyTest`, `app.gamenative.library.canonical.catalog.SteamCatalogNormalizationTest`, `app.gamenative.utils.ReleaseChannelPolicyTest`, `app.gamenative.service.gog.GOGManagerPlayTaskTest`, `com.winlator.core.RuntimePathsTest`.

Additional audit owners: `app.gamenative.service.gog.GOGHiddenRefreshTest`, `app.gamenative.service.epic.EpicAssetBuildVersionTest`, `app.gamenative.library.canonical.Account*Test`, `app.gamenative.service.gog.GOGAuthManagerTest`, `app.gamenative.service.epic.EpicAuthManagerTest`, `app.gamenative.service.amazon.AmazonAuthManagerTest`, `app.gamenative.service.ea.EaAuthManagerTest`, `app.gamenative.service.gog.GogFilteredProductsParserTest`, `app.gamenative.utils.KeyValueUtilsTest`, `app.gamenative.data.HiddenGameFilterTest`, `app.gamenative.PrefManagerHiddenGamesDefaultsTest`, `app.gamenative.utils.IntentLaunchManagerTest`, `app.gamenative.utils.StorageUtilsTest`.

## Resumed verification and next action

The historical interruption/NTFS failure above is superseded by [the host checkpoint report](../reports/2026-10-03-upstream-integration-host.md). Both flavors now cover 529 tests in 33 matched classes: 528 pass, one Windows hardlink skip, zero failures/errors. All 17 migration fixtures and four recovery cases pass in each flavor. The native Linux overlay bench has 246 passing sanitizer checks. Both debug APK assemblies pass and packaged downloader/overlay bytes match the pinned official blobs. No live acceptance or production release is claimed.

The cancellation fixture assertion was repaired without weakening propagation: type/message and original causal identity are required, not object identity across coroutine stack recovery. Provider and lifecycle/auth owners pass in both flavors. Versions 17–24 are now covered; their newly exposed deleted recipe/manifest ID loss across 24→25 was reproduced RED and fixed GREEN, including empty tables.

Current parent status is IN_PROGRESS. The first verified merge was committed and pushed only to the fork as `266ce078265d70ffc38cc2e1bcd8ee6c6794a313`. New official `8ea964fcfeddf37920305f14a49f98d1a0d45127` (AI support/account UI and main-window active-time reporting) has now been integrated, audited and host-verified: 555 owning tests per flavor (554 passed, one Windows platform skip), 38 matched classes, and both debug APK assemblies. New private-payload logging regressions were reproduced RED and fixed GREEN; bounded support suggestions/debug parameters and active-time attribution are covered. Exact evidence is in the report.

The newer merge was committed and pushed only to the fork as `f24b3da84971038f6fde3f17fb4cd1bbbeec9c7a`; the final origin query still matched `8ea964fcfeddf37920305f14a49f98d1a0d45127`. Task 15 resolver durability/detail parity has resumed; its offline 900-canonical accounting fixture and all 32 repository tests pass in both flavors. R5/R6 durable history tests are next. Follow [the resolver/detail progress report](../reviews/2026-10-03-resolver-detail-completion-progress.md) rather than the historical pause instructions above. U01/U03/U06 are host-closed. U05 (native Rust suite/rebuild) and LIVE01 (actual integrated live acceptance) retain explicit targets/acceptance in the report. No background task/watchdog remains active; no live provider/device acceptance or production release is claimed.

