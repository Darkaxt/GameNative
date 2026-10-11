# Grouping runtime correction cross-check — 2026-10-11

**Status:** G1 host correction verified; parent IN_PROGRESS. Exact signed/tablet R14 acceptance remains open. Inline review, no agents or Android access.

## Confirmed boundaries and repairs

| Boundary | Owning failure evidence | Repair and verification |
|---|---|---|
| Original GOG URL rejected before cache/hash | Public DREDGE GOG product1744110647 returns `..._glx_vertical_cover.webp?namespace=gamesdb`; native formatter preserves that query. Legacy namespace RED29 cases: policy rejection and producer returning two cards,27 controls, no errors/skips | Permit exactly one `namespace=gamesdb` parameter on original GOG CDN URLs only. Other sources, credentials, extra/duplicate/wrong query values, HTTP/non443/untrusted hosts/fragments and displayed Steam fallbacks remain rejected. Policy, actual producer, Room flow, cache recreation and production-policy/native-decode tests pass |
| Later optional failure discarded a completed family | Legacy progress RED44 cases: timeout and IOException each returned four cards instead of three after the first family was corroborated | Revalidate and publish each complete family independently; later optional failure preserves already validated families. Owning cancellation/programming exceptions still propagate, partial families remain unpublished |
| Whole-library cap permanently excluded later candidates | Same RED: real Room-backed32-member/16-family flow never produced all16 families after processing its first30 members | Shared raw-first flow consumes sequential complete-family phases. Each family has at most10 members, below the existing30-member single-phase cap, with its own20-second deadline; immutable progress is emitted between completed families. Subsequent families are attempted after an unavailable phase. Single-phase API retains30/20s and no longer discards completed evidence |

This is a prospective liveness revision, **not** unchanged whole-library20-second latency. The phase bounds are retained; the global permanent exclusion is removed. No pHash distance/aspect/usability threshold was weakened. No manual match/reset/preference operation, catalog/ownership mutation, or title-specific production rule was introduced.

## Fresh qualification

Evidence root: `D:/Temp/gamenative-grouping-correction-20261011`.

- `gog-namespace-red-counts.json`: expected two failures; preserve original producer `.single()` exception as actual RED evidence, not relabel it an assertion.
- `family-progress-red-counts.json`: expected three assertion failures,41 controls; the query-policy/producer/actual Room query tests passed after the narrow allowance.
- `grouping-affected-green-counts.json` and both retained fresh XML folders: **257/257 each flavor**, zero failures/errors/skips, identical case identities across flavors; all case identities from both RED sets retained.
-23 selected suites cover original-art policy/codec/transport/cache, catalog acquisition/producer/repository, pure family projection, actual Room/shared flow, scale/card lookup/exact family actions, guarded source execution, diagnostic redaction and candidate/tagged-release guards.
- Both Android-test Kotlin compilation tasks succeeded. No APK/device acceptance is inferred from compilation.
- Installed shared Gradle FIFO/mutex acquired (Legacy ticket660, Modern ticket663); verified3GiB shared in-process Kotlin/Gradle, two workers/no parallel, one1GiB test fork, forkEvery1, fail-fast/no ignored failures and30-minute test-task containment. No heap increase or diagnosed OOM. Native hints are not claimed as verified effective native concurrency.
- Frozen source/model/test manifest unchanged through both flavors; independent XML/source reconciliation passed. Historical schemas and source identities were not changed by this batch.

## Specification reconciliation

- **R15/D8:** Native evidence can reach hashing with the production GOG URL shape, including members with no Steam identity. Pairwise original-art corroboration, compatible game types, exact runtime bindings, source action authority, user rejection/separation, independent member catalogs/copies and stale-source cancellation remain guarded.
- **R14:** Generic production blockers are repaired and host-qualified. The exact installed GOG/Epic records and their original-image pair were not read in the ended tablet batch. Do not claim that the tablet now shows one DREDGE card or that this was its sole cause.
- Public host GOG and Steam images were independently fetched; a separate Python reference port measured distance28 for those different compositions. That is **not** the Epic/GOG pair or a native-codec result, does not authorize raising the threshold, and is retained only as an investigation lead. Unauthenticated Epic Store/GraphQL returned403 and attempted CMS routes404; no login/cookie or authenticated fallback was used.
- **U5/D9 → D10 → V1/C1/C2/C4/V2:** The user's next stages remain layout/language, individual source-authoritative DLC ownership, then community loading and comments. This grouping checkpoint does not close those rows or the parent backlog.
- No master/tag/release/upstream write is authorized by this cross-check. Publish only the verified feature checkpoint; Q1 remains the later coherent signed candidate stage.

**Next action:** L1 owning RED for independently flowing unequal Overview/Details columns and truthful names-only/known-capability language presentation, then bounded implementation and affected verification. No renewed device request until the coherent correction batch is ready.
