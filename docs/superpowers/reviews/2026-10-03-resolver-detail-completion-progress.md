# Resolver/detail completion progress — 2026-10-03

## Status and preserved baseline

IN_PROGRESS. The user's resumed authorization is the existing native Steam-first roadmap; DroidDeck was discarded. Work remains inline in `sync/official-2026-10-03`; do not use the original dirty checkout or spawn agents. Pause only when actual live testing/acceptance or a genuine user decision is required.

Official integration is published on the fork at `f24b3da84971038f6fde3f17fb4cd1bbbeec9c7a`, containing official `8ea964fcfeddf37920305f14a49f98d1a0d45127`. That checkpoint has 555 owning tests per flavor (554 pass, one platform skip), both debug APK assemblies, immutable schemas and retained original-checkout state. See the integration host report for exact scope and unclosed native/live acceptance.

## Task 15 Step 1 — offline resolver accounting/scale

The actual Room-backed SteamCatalogResolutionRepository and candidate policy processed 900 synthetic canonical games with 1,800 synthetic owned copies, six equally weighted categories:

- Exact title/developer/year.
- Edition mismatch against the base game.
- Duplicate-name candidates tied in the same year.
- Missing source developer.
- Missing source year.
- Complete no-result search.

Results: 450 automatic acceptance, 300 review-required, 150 unmatched, zero failed; 900 completed without scanning duplicate copies independently. One provider request active at a time; search count bounded to 900–2,700. Keyless behavior and cleared scanning state are asserted. Useful fixture coverage is 83%.

This fixture is deliberately synthetic. Its distribution does not represent a real library, and its percentage cannot establish real Steam search/candidate quality or authorize live calls. R4's real-library acceptance remains open. The missing-developer/year cases preserve the current corroboration policy; edition mismatches and ties remain review-required rather than becoming false automatic matches.

All 32 resolver repository tests passed separately in both flavors:

- Legacy: `D:/Temp/gamenative-resolver-900-Legacy-Q6uXJJ.log`, 2m05s.
- Modern: `D:/Temp/gamenative-resolver-900-Modern-mfdFGm.log`, 1m54s.

The test class now uses plain Android Application/Config.NONE to avoid production coordinator startup. It uses synthetic local Room rows and fake provider/writer boundaries, no live endpoints or account credentials.

## R5/R6 storage floor — schema 30

Owning behavioral RED: the published merged schema-29 fixture lacked a registered 29→30 path (`D:/Temp/gamenative-resolver-history-migration-red-0ffDcJ.log`). Added exactly the planned catalog-attempt and separately account-scoped rejection entities, target version 30, and explicit additive 29→30 migration. The registered builder validates the complete 31-entity target and exact history column sets (no account/ownership/private-query fields in catalog attempt history).

All registered historical routes now exercise target 30. A second RED reproduced stale target-29 recovery diagnostics after the version bump (`D:/Temp/gamenative-target30-recovery-red-msWWWN.log`). Current recovery labels derive from target 30; already-pending target-27/29 markers retain their original identities. Mixed acknowledgments retain the unacknowledged current marker.

Both flavors pass all 27 migration tests: 18 exact historical fixture cases, five recovery cases, two cleanup cases, two historical ledger migration cases. Logs:

- Legacy: `D:/Temp/gamenative-target30-migrations-Legacy-ZLWL5S.log`, 4m24s.
- Modern: `D:/Temp/gamenative-target30-migrations-Modern-RY41JQ.log`, 5m44s.

Fresh pre-publication runs after the final import-order cleanup also pass all 27 tests per flavor with zero failures/errors/skips: `D:/Temp/gamenative-schema30-precommit-mOjphj.log` (Legacy, 1m01s) and `D:/Temp/gamenative-schema30-modern-precommit-eO8XeZ.log` (Modern, 1m). Diff check passes; original checkout remains at `8311ec59f47ac821ce3d31c036b4bc3b6d83961c` with its 12 tracked modifications retained. No current-schema APK or live acceptance is claimed.

Export 30 has 31 entities and SHA-256 `e93fdc2ac95d4d78ca5f934a0d76df631fd3e126d30b683459b30fadc2c29d18`, copied exactly from the isolated generated schema. Published exports 26/27/29 remain byte-identical to HEAD. The exact published merged 29 fixture is sourced from `b22e53075d67210b34684ddca29935c368b13598`, not invented as an official version 29.

Storage/migration checkpoint is published at `06aa22fc4`. At that checkpoint there was no DAO, resolver persistence integration, durable rejection behavior, or WorkManager resume; storage alone does not close R5/R6.

## R5/R6 integrated implementation — IN_PROGRESS, no fragment commits

The user clarified on 2026-10-04 that continuous authorization and the existing approach are unchanged: commits must contain proper integrated implementations, not schema-only/test-only mini-deliverables. Keep the current connected R5/R6 implementation uncommitted until persistence, rejection/reset, and restart-safe resume work together and their owning verification is complete. Existing published history is preserved; no history rewrite is authorized.

Seven file-backed Room tests first reproduced five missing behaviors with valid numeric GOG IDs (`D:/Temp/gamenative-durable-rejections-owning-red-oHiKHJ.log`). The initial malformed-ID run was fixture setup failure, not behavioral RED. The subsequent combined owner was exceptionally slow: its legacy mutation test class booted the production application, and an owned thread dump showed background Room invalidation observers occupying disk executors while the test waited. The tracked task was stopped; its orphaned executor was verified by PID/worktree and stopped separately. A first attempted rerun encountered that executor's locked result file and is not behavioral evidence. Isolating the test with Application/Config.NONE yields all 31 existing mutation plus seven new rejection tests GREEN in 1m15s (`D:/Temp/gamenative-durable-rejections-isolated-green-Yrnwig.log`). No production change was made for this test-startup correction.

Nine further owning catalog tests reproduced missing pending/completed/failed history, process-recreation deduplication, canceled-work recovery, newly projected games, stale evidence/presence, superseded completion, and automatic rejection filtering (`D:/Temp/gamenative-resolver-durability-red-NCTuME.log`). All 41 repository tests subsequently pass (`D:/Temp/gamenative-resolver-durability-green-VaVAnU.log`, 3m55s). The process-local completion gate is replaced by public-evidence/version/status history; pending work is stored before providers, and guarded decision publication plus compare-and-set completion share one Room transaction. Private queries and account/copy identities are not stored in catalog-attempt fields.

Three integrated rejection failures then exposed missing picker multi-rejection, unmerge history, and older-sibling rejection protection (`D:/Temp/gamenative-rejection-integration-red-75miss.log`). Their owning ten-test group is GREEN (`D:/Temp/gamenative-rejection-integration-green-6EDE74.log`, 3m51s): validated manual-picker candidates can be rejected without resetting prior history; unmerge persists the detached identity's rejection; a sibling's earlier rejection is not overwritten by its latest rejected ID. Exact account/source/copy scope, guarded stale mutations, reset invalidation, and transaction rollback remain covered.

WorkManager runtime/testing 2.10.5 configuration is added locally; both exact public artifact POMs respond HTTP 200. The scheduling owner completed with the expected behavioral RED: zero resume jobs where one must be persisted before provider work (`D:/Temp/gamenative-resume-scheduling-red-8KhVnr.log`, 19m36s; one assertion failure, no setup errors). Its watchdog is canceled. Worker implementation, Modern integrated verification, current APK, and full R5/R6 completion remain pending.

## Build resource policy — subsequent commands only

An already-running cold compile subsequently passed with two workers/no parallel, a single-use 4GB Gradle JVM and Kotlin IN_PROCESS: `D:/Temp/gamenative-low-memory-cold-compile-CMv5h0.log`, all 29 tasks executed, 9m02s. This establishes plugin compatibility at 4GB, not a tested 3GB guarantee. The host-wide gate policy arrived while that command ran; no host-wide gate was acquired for this pre-policy invocation.

The user's new constraint applies to every subsequent Gradle command: one active build on this Windows host, across all repositories/worktrees/threads/agents, under supervising-process-owned mutex `Local\Darka.AndroidGradleBuildGate` from before host inspection until command exit. Active or ambiguous non-cooperating clients/daemon connections/logs require waiting for confirmed completion, not killing or inferring idleness from CPU. The installed `gradle-build-gate` skill/runtime release `e62c1d085ac1dc4d` is now the shared route; do not duplicate tooling or use Navic's temporary alternate helper. A status snapshot is not launch clearance.

Local configuration uses two workers/no parallel, 3GB Gradle and explicit 3GB Kotlin daemon limits with existing heap-dump/UTF-8 flags retained, in-process compilation, and one 1GB test fork. The first gated focused run compiled with IN_PROCESS and failed only the missing-worker assertion (`%LOCALAPPDATA%/Tools/gradle-build-gate/logs/adb2eeaca97747ad9219f612795cdb2f.log`, 4m30s). A subsequent nine-test resume group reproduced seven missing worker/scheduling-failure behaviors; the runtime constructor and actual network-constrained scheduling test passed (`D:/Temp/gamenative-resume-worker-red-342900f216.json`, 8m13s). Its JSON confirms `gate_acquired=true`, `gradle_profile_verified=true`, actual Gradle heap 3221225472 bytes, workers=2/parallel=false, and configuration-time test fork=1/heap=1g. The helper's `memory_failure_evidence` erroneously includes its profile marker solely because that marker contains the required `HeapDumpOnOutOfMemoryError` flag; no actual memory exception occurred. Native concurrency is not established by these Kotlin/unit-test runs.

The connected Legacy resolver/rejection/mutation/ViewModel owner completed (`D:/Temp/gamenative-resume-integrated-legacy-a261fdd16b.json`, 11m36s): 102 tests, 101 pass, one cancellation failure. The already-single-worker child-launch loop swallowed a provider CancellationException; it is now a direct paced serial loop, awaiting owning GREEN. No memory budget was increased. The original focused `--info` log also proves the executed test JVM used `-Xmx1g`, beyond the configuration-time profile marker.

Five bounded integration tests reproduced shared-canonical decision/identity changes, preparation after scheduling, an earlier pre-history rejection, and no-op resume progress. Their first owner stopped before tests with eight Room/KSP incremental symbol-processing errors; this was a setup failure, not behavioral RED (`D:/Temp/gamenative-resolver-boundary-red-8949f20a41.json`, build duration 1m21s excluding gate wait). Invocation-only fresh symbol processing (`-Pksp.incremental=false`) completed the same seven-test group: the five new boundaries were RED, while serial cancellation and pacing were GREEN (`D:/Temp/gamenative-resolver-boundary-fresh-ksp-cfcbc39dae.json`, 12m06s). No permanent cache setting or memory increase was made.

The connected fixes now share a current-evidence guard at preparation/publication, preserve prior legacy rejection history inside the guarded transaction, and leave foreground progress intact for a no-op resume. Final normal-cache Legacy verification is GREEN: all 134 tests (55 resolver, one runtime-worker constructor, nine durable rejection, 31 mutation, 11 ViewModel, 27 migration) pass without failures/errors/skips (`D:/Temp/gamenative-durable-resolution-legacy-cde2d3a41b.json`, 14m38s). The gate was acquired and the requested 3GB/two-worker/no-parallel/in-process profile was verified; no actual memory failure occurred. Published 26/27/29/30 and the generated 30 export are byte-identical; the original checkout retains its recorded head, 12 tracked modifications and media files.

Modern verification of the same 134 owning cases is also GREEN, with zero failures/errors/skips (`D:/Temp/gamenative-durable-resolution-modern-0740d73169.json`, 16m37s). Together these are 268 owning test passes, not a rerun of the entire broad baseline. Cancellation, file-backed repository/database recreation, partial-review retry, readiness/disabled gates, current presence/user decisions, no recursive wakeup, and graceful scheduling-failure handling are covered in both flavors. The worker reconstructs Room state through the existing repository/Hilt entry point; Work Data remains empty. Actual Android process termination/OS scheduling and live providers remain under LIVE01.

Both current debug APKs assembled in one gated invocation (`D:/Temp/gamenative-durable-resolution-apks-94a1294aff.json`, 14m06s; 136 tasks, 54 executed, four from cache, 78 up-to-date). Gate acquisition and the 3GB/in-process/two-worker/no-parallel profile are verified; no actual memory failure occurred. This run packaged prebuilt JNI libraries and does not prove native source-build concurrency. The public worker, WorkManager implementation and initializer are defined in the APK DEX; the nonexported AndroidX Startup provider includes WorkManager initializer metadata and the correct package-bound authority. These default local debug builds are `app.gamenative`, versionCode 40 / `1.2.0-rc14`, not side-by-side production release artifacts. An initial inspection asserted an incorrect debug package suffix; actual badging established the configured default identity, and corrected packaging checks passed without any source change.

| Debug artifact | ABI / SDK | SHA-256 |
| --- | --- | --- |
| `app/build/outputs/apk/legacy/debug/app-legacy-debug.apk` | arm64-v8a + armeabi-v7a; min 26 / target 28 | `580e5b0db2167350438066fb67ce862fb6c15ced84f108adf46c48662449d083` |
| `app/build/outputs/apk/modern/debug/app-modern-debug.apk` | arm64-v8a; min 29 / target 36 | `a0fff540fb84f018c784709e9a9485da2cfa596d3e62c6802dcacb6f31e3e3eb` |

After assembly, owning XML counts were rechecked, immutable 26/27/29/30 and isolated generated 30 still match, and the original checkout retains its recorded head, 12 tracked modifications and both media files. This preservation check proves recorded head/count/presence, not a byte-by-byte comparison of every original modification. The complete production/config diff was reviewed, including transactional leases, exact rejection scope, empty work data and exception-type-only worker logging; diff whitespace check passes. All completed-owner watchdogs are canceled. No production signer, live provider or runtime acceptance is claimed.

## Concrete next action — R5/R6 persistence and resume

Task 15 Step 2 remains IN_PROGRESS, not a pause or completion signal:

1. Publish the complete connected implementation only to the fork after the recorded two-flavor tests, APK packaging and source/schema checks; no disconnected runtime layer or fragment commit is required.
2. Continue Steam-first card/detail/UX work (P2, D2–D4, A1) under the existing authorization. A verification checkpoint is not completion of the parent roadmap.
3. Preserve catalog attempts as public-evidence-only, empty WorkManager Data, exact account/source/copy rejection scope and explicit reset semantics.
4. Preserve published 26–30 and all registered historical preservation/recovery routes. Host recreation tests and debug packaging do not replace Android process-loss/provider acceptance under LIVE01.

Ledger attribution correction: the authoritative design assigns durable resume to R4 and catalog-coverage-triggered expansion to R5. Earlier progress text used R5 as shorthand for attempt persistence and R4 for coverage; it did not change those acceptance contracts. The table below uses the authoritative IDs. R5 real-library coverage is still open; this durable implementation must not be mistaken for its closure.

The old plan's schema 27→28 reservation is obsolete after upstream collisions; durable history is now schema 30. This changes no historical export and does not widen the 7–16 destructive-recovery boundary. Existing pending target-27 and target-29 markers retain their identities until acknowledged.

## Open acceptance ledger

| ID | Owner / target | Acceptance |
| --- | --- | --- |
| R4 | Task 15 Step 2; attempt entity/DAO, resolver repository, WorkManager resume | Implementation and two-flavor host verification complete; real Android process-loss/OS scheduling acceptance remains under LIVE01. |
| R5 | Task 15 Steps 1/3; aggregate coverage and evidence-triggered catalog expansion | 900-game synthetic fixture is host-green; actual useful coverage and any justified expansion need real public-catalog/library evidence before closure. |
| R6 | Task 15 Step 2; rejection entity/DAO and guarded mutations | Implementation and two-flavor recreation/scope/rollback/reset tests complete; integrated signed-live acceptance remains under LIVE01. |
| P2 | Task 15 Step 4; canonical card repository/artwork | Accepted cached Steam title/artwork with source fallback and safe loading/error behavior. |
| D2–D4 | Task 15 Step 4; canonical detail screen/ViewModel/action routing/provenance | Field-by-field native detail parity, guarded actions, Details resource links, genuine Epic fallback and correct provenance. |
| A1 | Task 15 Step 4; focus/back/semantics/translations | Host interaction coverage, then coordinated integrated live acceptance. |
| U05 | Native downloader Rust host/cross-build tools | Compatible isolated Linux toolchain, locked host suite, and ABI provenance when sources/binaries are rebuilt; APK packaging is not this proof. |
| LIVE01 | Signed integrated app, real provider/community/resolver/UI/storage/native acceptance | Explicit coordinated live-testing gate; no acceptance inferred from mocks, debug builds or published commits. |

No row is silently deferred or closed by the upstream merge. Parent execution must resume at the concrete R5/R6 tests above.
