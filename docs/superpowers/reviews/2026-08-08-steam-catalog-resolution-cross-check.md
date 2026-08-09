# Steam Catalog Resolution Design Cross-Check

**Implementation commits reviewed:** `6e65c24a`–`a33c940d` (inclusive)  
**Upstream integration:** `b784679d`  
**Completed:** 2026-08-08  
**Corrected-RC follow-up:** 2026-08-09
**Design:** `docs/superpowers/specs/2026-08-08-steam-resolution-community-visible-core-design.md`  
**Plan:** `docs/superpowers/plans/2026-08-08-steam-resolution-community-visible-core.md`

## Gate scope

This is the one focused Deliverable 1 cross-check required by Task 7. It covers the automatic Steam catalog resolver, guarded identity mutations, accepted-identity enrichment, source-agnostic progress, and the visible Fix Steam match flow. It does not reopen broad review after the consolidated Critical/High correction pass. Reviews and Discussions remain blocked until the signed resolver release candidate is published and reported.

## Verified requirements

- [x] **Steam identity is presentation/catalog identity, not ownership or action authority.** Accepted AppIDs update canonical presentation and discovery data. They do not create `SteamAppDao` rows, licenses, packages, depots, manifests, or Steam action capability. `OwnedCopyKey(accountScope, source, stableSourceId)` remains authoritative for every source-native action.
- [x] **Automatic matching is bounded and conservative.** Authenticated Steam Web API AppList discovery downloads the complete game catalog in bounded pages, caches a compact app-private public-catalog snapshot, and performs exact normalized-title lookup locally without transmitting source titles. At most five exact candidates receive Store `appdetails` validation. Automatic work is serialized and paced at 350 ms between items. Automatic acceptance requires a unique exact edition-preserving title, compatible known app type, and developer, publisher, or release-year corroboration. Ambiguity, fuzzy-only evidence, unknown or incompatible type, edition conflict, missing corroboration, and partial validation fail closed to review or unmatched.
- [x] **Manual correction is explicit and reversible.** The picker supports editable search, direct positive AppID lookup, candidate comparison, explicit selection and confirmation, Keep separate, Reset to automatic with immediate retry, retry after unavailable/empty results, and Cancel/Back without mutation. Several mutable non-Steam copies route through copy selection. Direct Steam copies are visibly immutable.
- [x] **Automatic and manual mutations are stale-guarded.** Expected copy/canonical state is checked transactionally. Stale confirmation, rejection, or reset writes nothing and returns fixed refresh feedback. Replacing an existing Steam identity invalidates the previous identity's presentation, review count, facets, and detail snapshots before assigning the replacement.
- [x] **Accepted enrichment reuses validated evidence without refetch.** Sanitized `appdetails` presentation, genres, features, and snapshot are written under the expected AppID in one guarded Room transaction. Optional PICS genres/categories/tags and one-target review-count enrichment use the trusted AppID without creating entitlement.
- [x] **Resolver-owned state and credentials have explicit persistence boundaries.** User search text, candidate lists, selected evidence, validated candidate records, and progress remain process-memory only and absent from diagnostics. The reproducible public Steam AppList title/AppID catalog alone is cached under app-private files for seven days. The runtime Web API credential is encrypted with a dedicated randomized Android Keystore AES-GCM key whose provider generates each encryption IV, and exposed to UI only as configured/not configured; it is never compiled from `.env`, sent in a URL, logged, or exported. Save is gated by a bounded provider Test for the exact entered key, while the ViewModel retains only its SHA-256 fingerprint.
- [x] **Coverage and popularity are source-agnostic.** Coverage observes the complete present canonical library rather than the active tab or page. A GOG-only card with a trusted Steam AppID and review count participates in popularity filtering under All and GOG while remaining excluded from the Steam ownership tab.
- [x] **Visible detail structure matches the approved shell.** Canonical detail exposes exactly Overview, Reviews, Discussions, and Details. Resources are rendered under Details. Match provenance and Fix Steam match are visible at top level and per copy.
- [x] **Official upstream was integrated before release preparation.** The resolver UI and upstream modern custom-game import coexist after merge `b784679d`; neither side's user path was discarded.

## Consolidated Critical/High correction pass

No Critical finding was confirmed. Three High findings were confirmed and repaired in this single pass:

1. **Replacing Steam identity retained data derived from the previous AppID.** `CanonicalMutationRepository.assignSteamIdentity` now clears prior genres, tags, features, detail snapshots, and review count, restores source-evidence presentation/classification, and assigns the replacement identity in the same transaction. A focused Room regression test covers A→B correction.
2. **Validated Steam presentation was not identity-atomic.** Presentation previously changed before a separately guarded facet/snapshot transaction. `GameFacetRepository.upsertValidatedSteamPresentation` now guards the expected AppID and writes presentation, facets, snapshot, and classification in one Room transaction. Stale B metadata cannot overwrite a canonical already corrected to C.
3. **Search and unaccepted candidate media could enter persistent HTTP/image caches.** Store search now sends `Cache-Control: no-store` on the initial request and every validated redirect. Session-only picker media disables the inherited OkHttp cache, sends `no-store` across redirects, and disables Coil disk caching.

No second design-review pass will be launched. Subsequent work is limited to deterministic test/build/release failures discovered while closing this gate.

## Complaint-driven corrected-RC follow-up

Live RC5 evidence exposed provider and source failures before Reviews began. The corrected RC therefore adds only the narrow repairs needed to make Deliverable 1 usable:

1. Store title search is replaced by authenticated `IStoreService/GetAppList/v1` bootstrap and exact local normalized-title search. The credential is header-only, runtime-configured, and Android-Keystore encrypted; the developer `.env` value is never compiled into the APK.
2. The public catalog is stored as a bounded, versioned, atomically replaced app-private binary cache. Stale fallback remains usable, and explicit retry can refresh a stale in-memory fallback after transient failure.
3. Candidate validation is serialized and paced. A partial `appdetails` failure retains validated candidates as review-required; complete detail failure remains provider-unavailable. Diagnostic failures use fixed `APP_LIST_UNAVAILABLE`, `APP_DETAILS_UNAVAILABLE`, `CANDIDATE_DETAILS_INCOMPLETE`, or `UNEXPECTED_FAILURE` categories rather than exception names.
4. Steam publisher evidence can corroborate source developer evidence, covering stores that report the publisher in their developer field. Store `appdetails` responses are bounded before decoding.
5. GOG complete snapshots accept legitimate non-game product types and exclude them as non-base entries instead of aborting materialization.
6. The package no longer declares itself as a game to Samsung's launcher policy; the game classification had allowed Gaming Hub settings to hide the launcher icon.
7. Both release workflows reject a release tag that does not equal `v` plus the expected APK version name, preventing correctly signed APKs from being published under an unrelated valid tag.
8. RC6 key persistence failed after Save because its cipher supplied a caller-generated AES-GCM encryption IV to an Android Keystore key configured with randomized encryption required. Encryption now initializes with the key only, verifies the provider-generated 12-byte IV, and preserves the versioned ciphertext/decryption format.
9. The key editor now provides the requested **Test** action. A bounded one-result, header-only AppList request classifies valid, rejected, and temporarily unavailable outcomes. Save remains disabled until the exact current 32-hex key validates; changing the input, cancellation, validation failure, or provider unavailability clears that authority. Storage failure leaves the editor open without reporting success.
10. AppList validation and full-catalog response bodies are consumed and bounded on OkHttp callback workers before coroutine resumption. The caller/Main dispatcher remains responsive while a body stalls, and cancellation remains connected to `Call.cancel()` through body consumption without a cross-thread `Response.close()` race.
11. Automatic resolution now checks encrypted-key configuration before selecting or searching any library item. A missing key leaves progress idle in an explicit key-required state instead of reporting one provider failure per eligible game.
12. Full AppList pagination treats an omitted `have_more_results` field as terminal completion, matching the live fourth-page response. A present malformed flag still fails closed, and a true flag still requires a valid advancing cursor.
13. The key-required resolver UI links to Steam's official Web API key page and replaces Review/Retry with one **Enter API key** action. That action opens the same Test → Save editor used by Settings. Successful Save/Delete operations publish configuration changes, so a retained library resolver refreshes after either the inline editor or Settings changes the credential; the editor closes only after successful persistence.
14. Official upstream commit `4c3269c6` (gametime tracking correction) was integrated in merge `ac9ff46e` before RC8 release validation.

The separate GOG recommendation-media Store title-search fallback is recorded as ledger R8. It is outside canonical ownership resolution and remains assigned to Stage 4 rather than being silently treated as migrated. AppList `last_modified` is retained for the named Stage 4 enrichment-refresh optimization in R9; the corrected RC does not claim that optimization is already wired.

## Named carryovers

These are not silent omissions and do not block Deliverable 1:

- **R4 — process-death/durable resolver resume:** remains owned by Stage 4 / Task 15. Deliverable 1 intentionally resumes only in process memory. Closure requires durable resume or fixture/live evidence that foreground resume meets acceptance.
- **R6 — rejection history stores only one candidate:** remains owned by Stage 4 / Task 15 and the planned schema-28 decision. Closure requires evidence that rejected candidates do not recur incorrectly or a durable history repair.
- **P2 — accepted Steam artwork precedence:** remains owned by Stage 4 / Task 15. Deliverable 1 promotes accepted Steam detail presentation, but canonical library-card artwork precedence still requires the named cached-Steam-with-fallback completion.

## Verification evidence

Fresh correction verification passed in both variants:

```bash
JAVA_TOOL_OPTIONS=-Xshare:off ./gradlew :app:testLegacyDebugUnitTest \
  --tests '*CanonicalMutationRepositoryTest.guarded manual correction invalidates prior Steam presentation and facets' \
  --tests '*GameMetadataRepositoryTest.staleValidatedRecordDoesNotOverwriteNewerIdentityPresentation' \
  --tests '*GameFacetRepositoryTest.staleSteamPresentationIsRejectedWithoutPartialWrites' \
  --tests '*SteamCatalogSearchProviderTest.encodesOnlyTrimmedTermCountryAndLanguage' \
  --tests '*SteamCatalogSearchProviderTest.followsOnlyRevalidatedSameHostRedirects' \
  --tests '*SteamMediaDataSourceTest.sessionOnlyMediaRequestsAreNoStoreAcrossRedirects'

JAVA_TOOL_OPTIONS=-Xshare:off ./gradlew :app:testModernDebugUnitTest \
  --tests '*CanonicalMutationRepositoryTest.guarded manual correction invalidates prior Steam presentation and facets' \
  --tests '*GameMetadataRepositoryTest.staleValidatedRecordDoesNotOverwriteNewerIdentityPresentation' \
  --tests '*GameFacetRepositoryTest.staleSteamPresentationIsRejectedWithoutPartialWrites' \
  --tests '*SteamCatalogSearchProviderTest.encodesOnlyTrimmedTermCountryAndLanguage' \
  --tests '*SteamCatalogSearchProviderTest.followsOnlyRevalidatedSameHostRedirects' \
  --tests '*SteamMediaDataSourceTest.sessionOnlyMediaRequestsAreNoStoreAcrossRedirects'
```

- **Legacy:** passed in 4m 29s.
- **Modern:** passed in 3m 18s.

```bash
JAVA_TOOL_OPTIONS=-Xshare:off ./gradlew \
  :app:compileLegacyDebugAndroidTestKotlin \
  :app:compileModernDebugAndroidTestKotlin
```

- **Passed in 15s.** Both Android-test source sets compiled after the correction. The same final-HEAD command passed again in 57s after setting RC5 identity.

The final bounded owning gate passed with catalog, guarded correction, action-routing, resolver ViewModel, popularity-filter, and diagnostics tests:

- **Legacy:** passed in 6m 5s.
- **Modern:** passed in 6m 22s.

The two release-workflow contract tests also passed at final HEAD:

- **Legacy:** passed in 34s.
- **Modern:** passed in 37s.

An additional combined run that included the complete `GameFacetRepositoryTest` class was stopped after four Room tests reported `UncompletedCoroutinesError` in the shared process. The correction's focused stale-presentation Room test passes alone in both variants, and the required bounded gate above is green. This extra test-harness failure is not represented as product verification or silently counted as a pass.

Corrected-RC owning verification:

- Focused Legacy catalog, metadata, key, GOG parsing, launcher contract, and host-contract tests passed in 4m 52s.
- The same focused Modern matrix passed in 8m 48s.
- Legacy and Modern Android-test Kotlin source sets compiled separately in 11s and 9s.
- The credential-safe Web API smoke helper returned HTTP 200 with a bounded aggregate response. Exact-value scans found zero credential matches across 1,999 tracked/unignored files and the built APK.
- `assembleLegacyDebug` passed; `apkanalyzer` reported code 32/name `1.1.3-rc6`, a valid launcher entry/icon, and no game classification.
- Tag/version binding contract tests passed in Legacy and Modern after the focused release-workflow correction.
- Physical-device instrumentation remains pending because `adb devices -l` reported no connected device at this checkpoint; it is not counted as passed.

RC7 key-validation/persistence correction verification:

- The host cipher contract was observed RED against the caller-generated encryption IV and GREEN after Android Keystore became the IV provider.
- Focused Legacy and Modern provider, ViewModel, and host-contract unit tests passed, including bounded validation, rejected/unavailable classification, cancellation, stalled-body caller responsiveness, exact-key Save binding, persistence-failure feedback, and cipher initialization.
- Legacy and Modern Settings Compose Android-test source sets compiled successfully. Physical execution on `SM_X910` then passed all four selected tests: Android Keystore ciphertext/randomization/recreation/delete and the three Test-before-Save Settings flows.
- A credential-safe real Web API smoke returned HTTP 200 with one bounded result; an all-zero fabricated key returned HTTP 403. No key value was printed, committed, or compiled.
- RC7 release identity contracts were observed RED while production files still declared RC6, then passed in Legacy after code/name/workflows were updated to code 33 / `1.1.3-rc7`.
- The isolated `app.gamenative.keystoreprobe` instrumentation target was removed after the four tests and did not replace or clear the installed compatibility release. RC7 was published and the signed side-by-side APK installed. One deliberate real-key Test → Save succeeded and persisted across process recreation, but live resolution then exposed the omitted terminal AppList flag; three-per-store sampling, AppID `2229940`, and Samsung launcher visibility therefore remained blocked rather than being represented as passed.

RC8 key-gate/terminal-page correction evidence:

- The terminal-page regression was observed RED when RC7 required `have_more_results` and GREEN after omission became terminal completion.
- The resolver key-gate regression was observed RED before the repository exposed a key-required state and GREEN after unconfigured scans returned without invoking catalog search.
- Review found that a key saved through Settings could leave a retained library resolver stale, then that the first shared-change repair bypassed projection/public-library readiness. Configuration changes now use the same readiness gate as initial scanning; both defects have focused RED/GREEN ViewModel coverage, and final focused review found no remaining concrete blocker.
- After upstream merge `ac9ff46e`, the final focused repository, AppList provider, key repository/settings, resolver ViewModel, launcher, and release-contract matrix passed in Legacy (6m 24s) and Modern (5m 45s). Both Compose Android-test source sets compiled together in 34s.
- An attempted combined Gradle invocation applied `--tests` only to the final test task and unintentionally started the unrelated unfiltered Legacy suite. It was stopped after pre-existing baseline failures appeared, stale daemons were stopped, and both intended variant gates were rerun independently as recorded above; the accidental run is not counted as product verification.
- RC8 publication, installation, live resolver completion, three-per-store sampling, AppID `2229940`, and Samsung launcher visibility remain required and are not represented as passed.

RC10 visible-media and resolver follow-up evidence:

- RC10 was published as a four-channel persistently signed prerelease and installed over RC8 without clearing app data; package code 36/name `1.1.3-rc10` was verified on the signed tablet.
- The seven-day support prompt appeared once after upgrading from a build with no stored timestamp, then remained absent across two cold relaunches.
- Current Steam HLS trailer payloads were restored and old screenshot-only metadata snapshots invalidated. The signed DREDGE detail showed two moving trailers as media 1/18 and 2/18 before screenshots.
- Live inspection also confirmed that the Epic-owned DREDGE copy remained separate because its automatic Steam catalog relationship was latched in `REVIEW_REQUIRED`; equal visible titles are deliberately not a canonical grouping key.

RC11 latched-review correction evidence:

- The resolver regression was observed RED in Legacy: explicit Retry selected zero current automatic review decisions, resolver-v2 review decisions were skipped after upgrade, and an accepted automatic catalog identity was destabilized by a resolver-version change.
- Resolver v3 now reconsiders persisted v2 automatic review decisions once, explicit Retry revalidates current automatic review decisions, and accepted automatic catalog identities survive version upgrades. User decisions remain excluded before force/version eligibility is evaluated.
- The existing candidate policy still owns acceptance, so app-type or developer/year conflicts remain review-required rather than being merged by equal title alone.
- Focused Legacy and Modern resolver, guarded automatic merge, grouped-card projection, source-action routing, and RC11 release-contract tests passed after the correction.
- `assembleLegacyDebug` passed; `apkanalyzer` verified package `app.gamenative`, version code 37, and version name `1.1.3-rc11`.
- Signed publication and tablet acceptance evidence are recorded only after their fresh gates complete.
- The `v1.1.3-rc11` tag was pushed for the correction checkpoint, but its publication workflow was canceled before release when delivery policy changed to batch the remaining visible work before one consolidated QA/release pass. RC11 is not an acceptance release and its immutable tag will not be reused.

**Gate result:** The resolver correction is implemented and retained as a pushed checkpoint. Per the updated delivery policy, remaining visible-core tasks are implemented before one consolidated QA/release pass; RC11 was canceled and is not represented as accepted.
