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

This establishes storage/migration only. There is not yet a DAO, resolver persistence integration, durable rejection behavior, or WorkManager resume; R5/R6 are not closed.

## Concrete next action — R5/R6 persistence and resume

Task 15 Step 2 remains IN_PROGRESS, not a pause or completion signal:

1. Write owning DAO round-trip/CAS tests, guarded resolver-history and rejection tests, and process-recreation/resume tests before their implementation. Migration/storage tests are already GREEN; do not repeat them as unimplemented work.
2. Add the DAO and persist attempts from current public evidence; keep account-scoped rejection decisions in their separate table. Do not put ownership associations, account IDs, private user-entered queries, credentials or personal paths into catalog attempt history or WorkManager Data.
3. Add unique network-constrained WorkManager resume. Reconstruct current evidence from Room; revalidate presence/current user decision and reject stale publication. Rejections must survive process recreation; explicit reset behavior needs tests.
4. Preserve published 26–30 and keep all registered 17–29 preservation routes plus recovery markers covered. A process-local session gate is not durable completion proof.
5. Verify separately on Legacy and Modern, update ledger evidence, commit and push the bounded checkpoint only to the fork. Continue remaining card/detail/UX work before actual live acceptance.

The old plan's schema 27→28 reservation is obsolete after upstream collisions; durable history is now schema 30. This changes no historical export and does not widen the 7–16 destructive-recovery boundary. Existing pending target-27 and target-29 markers retain their identities until acknowledged.

## Open acceptance ledger

| ID | Owner / target | Acceptance |
| --- | --- | --- |
| R4 | Inline implementation; aggregate fixture plus real public-catalog/library observations | Offline fixture is host-green; actual useful coverage and any justified bounded expansion require real evidence before closure. |
| R5 | Task 15 Step 2; attempt entity/DAO, resolver repository, WorkManager resume | Durable pending/failed history survives process loss; current evidence/presence/user decisions are revalidated; no private queries in work data. |
| R6 | Task 15 Step 2; rejection entity/DAO and guarded mutations | Multiple rejected candidates survive restart, remain exact account/copy scoped, cannot reappear automatically, and explicit reset has defined tested semantics. |
| P2 | Task 15 Step 4; canonical card repository/artwork | Accepted cached Steam title/artwork with source fallback and safe loading/error behavior. |
| D2–D4 | Task 15 Step 4; canonical detail screen/ViewModel/action routing/provenance | Field-by-field native detail parity, guarded actions, Details resource links, genuine Epic fallback and correct provenance. |
| A1 | Task 15 Step 4; focus/back/semantics/translations | Host interaction coverage, then coordinated integrated live acceptance. |
| U05 | Native downloader Rust host/cross-build tools | Compatible isolated Linux toolchain, locked host suite, and ABI provenance when sources/binaries are rebuilt; APK packaging is not this proof. |
| LIVE01 | Signed integrated app, real provider/community/resolver/UI/storage/native acceptance | Explicit coordinated live-testing gate; no acceptance inferred from mocks, debug builds or published commits. |

No row is silently deferred or closed by the upstream merge. Parent execution must resume at the concrete R5/R6 tests above.
