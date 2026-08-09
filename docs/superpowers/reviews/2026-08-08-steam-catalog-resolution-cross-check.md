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
- The isolated `app.gamenative.keystoreprobe` instrumentation target was removed after the four tests and did not replace or clear the installed compatibility release. Signed RC7 installation, one deliberate real-key Test → Save, resolver acceptance, three-per-store sampling, AppID `2229940`, and Samsung launcher visibility remain post-publication acceptance work and are not represented as passed.

**Gate result:** No unresolved Critical or High code finding remains in the focused correction. The physical Android Keystore/UI gate passed on the claimed tablet, so signed RC7 publication may proceed. Reviews remain blocked until publication and the requested live resolver acceptance are verified and reported.
