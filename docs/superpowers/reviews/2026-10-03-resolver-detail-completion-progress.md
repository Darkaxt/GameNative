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

## Concrete next action — R5/R6 durable history

Task 15 Step 2 is next, not a pause or completion signal:

1. Write owning tests for published schema 29→30 through the registered builder, row/FK/default/sequence preservation, new attempt/rejection table shapes, and current/legacy recovery acknowledgments. Reproduce absent durable storage/path before production changes.
2. Add attempt history (canonical ID, public-evidence hash, resolver version, status, timestamp) and account-scoped rejection decisions in separate tables. Do not put ownership associations, account IDs, private user-entered queries, credentials or personal paths into catalog attempt history or WorkManager Data.
3. Add the DAO, guarded persistence, and unique network-constrained WorkManager resume. Reconstruct current evidence from Room; revalidate presence/current user decision and reject stale publication. Rejections must survive process recreation; explicit reset behavior needs tests.
4. Export schema 30 into the isolated scratch path first, preserve published 26–29 byte-for-byte, and rerun all registered 17–29 preservation routes plus recovery markers. A process-local session gate is not durable completion proof.
5. Verify separately on Legacy and Modern, update ledger evidence, commit and push the bounded checkpoint only to the fork. Continue remaining card/detail/UX work before actual live acceptance.

The old plan's schema 27→28 reservation is obsolete after upstream collisions; the next schema is 30. This changes no historical export and does not widen the 7–16 destructive-recovery boundary. Existing pending target-27 and target-29 markers must retain their identities until acknowledged.

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
