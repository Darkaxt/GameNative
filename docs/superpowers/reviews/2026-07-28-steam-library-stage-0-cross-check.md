# Steam Library Stage 0 Design Cross-Check

**Implementation commits:** `2436ee33`–`63fe2470` (inclusive)  
**Design:** `docs/superpowers/specs/2026-07-27-steam-normalized-library-design.md`  
**Plan:** `docs/superpowers/plans/2026-07-27-steam-library-stage-0-diagnostics.md`

## Verified requirements

- [x] **The feature report is local, bounded, and manually controlled (Sections 15, 17, 19; criterion 18).** `DiagnosticLogStore` writes JSONL under `filesDir/diagnostics`, rotates at 512 KiB per file, and retains at most three files. The Debug settings expose separate Export and Clear actions. `FeatureDiagnostics` and the report path contain no network client or upload operation.
- [x] **Diagnostic attributes are constrained and sanitized at storage (Section 15).** Callers use `DiagnosticAttribute`; `DiagnosticRedactor.sanitizePersisted` drops unknown wire names and sanitizes approved values again immediately before append.
- [x] **Forbidden values are covered by deterministic tests (Sections 15, 18.1, 21).** Tests cover URLs, Android and Windows paths, bearer values, token-like key/value text, JWT-like text, long opaque values, control characters, unapproved keys, and raw correlation input. Correlations are stable 12-character SHA-256 prefixes rather than raw identifiers.
- [x] **The observed privacy-filtered reports contain only approved evidence (Section 15).** Release reports contained fixed source names, reason codes, counts, durations, build/device fields, and hashed correlations. They contained no game or candidate title, search text, account ID, SteamID, username, install path, URL, token, review body, or discussion body.
- [x] **Storage failures do not break app behavior (Sections 14, 17).** Initialization, append, read, and clear failures are caught by the facade and reported only through Timber. Clear verifies deletion of every rotation and reports success or failure to the user.
- [x] **Startup and current filtering are instrumented (Section 19).** Release evidence included `APP_STARTED` and `LIBRARY_FILTER` `STARTED`/`SUCCEEDED` events with durations, aggregate result count, and per-source counts. Search records only the fixed `search` category when active, never the query.
- [x] **Current game resolution and external launch routing are instrumented (Sections 19, 20; criterion 18).** An uninstalled custom-game request produced `ACTION_ROUTE STARTED`, `GAME_RESOLUTION FAILED`, and `ACTION_ROUTE FAILED`, all sharing only a hashed correlation. A Steam request without service readiness produced `ACTION_ROUTE STARTED`, `DEFERRED`, and `FAILED` with `service_not_ready` and `service_timeout` reason codes.
- [x] **Deferred routing has nonterminal semantics.** Manual validation exposed that requests can stop before game resolution. Commit `63fe2470` added a distinct `DEFERRED` outcome plus timeout, Steam-login-failure, not-installed, prelaunch-dispatch, and existing-session outcomes instead of mislabeling deferred work as successful or skipped.
- [x] **Crash reports include a bounded feature-event tail (Sections 17, 19; criterion 18).** Diagnostics initialize before `CrashHandler`; uncaught exceptions record only the exception class. An emulator crash induced through `adb shell am crash app.gamenative` produced a crash report whose Feature Diagnostics section contained the preceding route sequence and `APP_CRASHED` with `error_type=CrashedByAdbException`. The tail is limited to 100 events.
- [x] **Raw crash/logcat content remains distinct from the privacy-filtered export (Section 15).** “Save logcat,” “Export feature diagnostics,” and crash-report viewing remain separate UI/actions. The structured tail is clearly labeled when appended to a raw crash report; the manual feature report never imports raw logcat or stack traces.
- [x] **Clear works in a minified release build (Sections 18.1, 19; criterion 18).** The release UI displayed `Feature diagnostics cleared`; an immediate export reported `Events: 0`. Later exports contained only post-clear action events.
- [x] **The vocabulary is ready for later stages (Sections 17, 19; criterion 18).** It already defines database migration, canonical index, match resolution, metadata fetch, facet refresh, detail section, review page, and discussion page boundaries. Each later stage must add events at its actual failure boundaries rather than inventing private free-form fields.

## Intentional deviations

- The eight new Debug-setting strings are marked `translatable="false"`. The repository has many locale resource sets and treats missing translations as lint errors; Stage 0 keeps the technical diagnostics controls in one language rather than adding unreviewed machine translations. This does not change the product or privacy contract.
- The plan initially used `ACTION_ROUTE STARTED` at the prelaunch dispatch point. Device validation showed that service deferral can happen earlier, so the implementation now records request receipt, a distinct nonterminal `DEFERRED` state, and a terminal routing outcome. This is a corrective expansion of the design’s “launch-request boundaries” requirement, not a product-scope change.

## Diagnostics coverage

- `APP_STARTED`: exercised in a minified release build.
- `APP_CRASHED`: exercised with a controlled emulator crash; crash-tail presence verified.
- `LIBRARY_FILTER`: `STARTED` and `SUCCEEDED` exercised with duration and per-source counts; non-cancellation exceptions emit `FAILED` with exception class only.
- `GAME_RESOLUTION`: `FAILED/copy_not_installed` exercised for `CUSTOM_GAME`; installed-copy success is implemented but was not device-exercised because the validation profile had no installed copy.
- `ACTION_ROUTE`: release evidence exercised `STARTED`, `DEFERRED`, and `FAILED` for Steam service timeout, plus `STARTED` and `FAILED` for an uninstalled custom game. Code also covers Steam login failure, successful prelaunch dispatch, and foregrounding an already-running session.
- Future boundaries reserved by the enum: `DATABASE_MIGRATION`, `CANONICAL_INDEX_BUILD`, `MATCH_RESOLUTION`, `METADATA_FETCH`, `FACET_REFRESH`, `DETAIL_SECTION`, `REVIEW_PAGE`, and `DISCUSSION_PAGE`.

## Verification evidence

- `./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.diagnostics.*" :app:testModernDebugUnitTest --tests "app.gamenative.diagnostics.*" :app:compileLegacyDebugKotlin :app:compileModernDebugKotlin`
  - **Passed after the final routing change.** All diagnostics tests passed in both flavors and both Debug variants compiled.
- `./gradlew :app:testLegacyDebugUnitTest :app:testModernDebugUnitTest`
  - **Did not pass globally.** The command stopped in Legacy after 874 tests with 63 failures. A clean worktree at pre-Stage-0 commit `f8db3a5e` produced 62 failures across the same 62 test names. The one additional failure was the unrelated `BestConfigServiceTest.testPrintParsedOutputForVerification`; it passed immediately when rerun alone. The seven added diagnostics tests all passed.
  - A separate Modern full-suite attempt emitted the same environment/fixture-sensitive failure families and then stopped making progress; it was stopped. Focused Modern diagnostics tests pass.
  - Shared baseline failures are concentrated in Windows/path-sensitive fixtures and unrelated existing tests including `PathTypeTest`, `RegistryKeyFixTest`, `SteamAutoCloudTest`, `EpicCloudSavesTest`, `GOGConstantsTest`, `ManifestIdCorrelationTest`, `PreInstallStepsTest`, `SteamUtilsFileSearchTest`, and `ImageFsInstallerTest`.
- `./gradlew :app:lintLegacyDebug`
  - **Failed globally with 32 errors and 1,955 warnings.** The first error is the pre-existing API-29 `FileObserver` constructor in `AchievementWatcher.kt:63` with minSdk 26. No error points to the diagnostics package, new diagnostics strings, `SettingsGroupDebug`, `CrashHandler`, or the added routing code. The only `PluviaMain` entries are unrelated existing `getIdentifier` warnings near line 2269.
- `./gradlew :app:assembleLegacyRelease`
  - **Passed after the final routing change, including R8/minification and lint-vital.** Existing AGP compileSdk and Kotlin-metadata/R8 warnings remain.
- Release APK: `app/build/outputs/apk/legacy/release/app-legacy-release.apk`
  - Installed and launched on an Android 15 emulator.
  - Export and Clear were exercised through `Settings → Debug`.
  - Post-clear Steam route report contained exactly three ordered events: `STARTED/external_launch_requested`, `DEFERRED/service_not_ready`, and `FAILED/service_timeout`.
  - Post-clear custom route report contained exactly three ordered events: route `STARTED`, resolution `FAILED/copy_not_installed`, and route `FAILED/copy_not_installed`.
  - Controlled crash report contained those post-clear events plus `APP_CRASHED` in its bounded tail.

## Open findings

- **Medium — repository verification baseline, owner: project/upstream test hygiene.** The complete unit matrix and lint task are not green before Stage 0, so they cannot currently serve as clean regression gates. Stage 0 added no failure shared with the baseline, its focused tests pass in both flavors, lint reports no new diagnostics error, and the minified release builds. Track baseline cleanup separately rather than hiding it with a new lint or test baseline.
- **Low — live success-path coverage, owner: Stage 2 action-routing validation.** The emulator profile had no installed game/account, so successful installed-copy resolution, prelaunch dispatch, and Steam-login-failure completion were not exercised manually. Their events compile in the release build; Stage 2’s source/copy routing matrix must exercise them before canonical routing is enabled.

**Gate result:** No unresolved Critical or High finding. Stage 0 satisfies its exit criterion, and detailed Stage 1 planning may begin.
