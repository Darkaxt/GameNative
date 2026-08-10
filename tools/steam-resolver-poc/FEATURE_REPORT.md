# Standalone Steam Resolver POC

## Result

Implemented a self-contained Python 3 package and CLI under this directory only. It accepts GameNative-aligned owned-copy evidence (`source`, `stableSourceId`, `displayName`, optional `developer`, `releaseYear`, and `appType`) plus an optional Epic `epicProductSlug` or canonical `epicStoreUrl`. It emits deterministic JSON using `candidateSteamAppId`, `matchMethod`, `confidence`, and `decisionSource` names.

The authoritative corpus contains exactly 30 cases: 10 GOG, 10 Epic, and 10 Amazon. Epic IDs are canonical unpadded base64url encodings of `namespace.catalogId`; GOG decimal and Amazon `amzn1.adg.product.<UUID>` forms are strictly validated.

Expected Steam AppIDs exist only in the evaluation corpus. Each case is converted to `OwnedCopy` before resolution, and the evaluator reads the expected AppID only after `resolve()` returns.

## Resolution policy

- Candidate retrieval: public no-key Steam Store `api/storesearch` using original, safe Playdead alias, and punctuation-distinct normalized queries. Every idempotent GET retries HTTP 429 up to four total attempts; numeric or HTTP-date `Retry-After` is capped at 30 seconds, otherwise delays are `1/2/4` seconds.
- Exhausted rate limiting aborts the whole operation as typed `RATE_LIMIT_EXHAUSTED`. It never creates candidates or a resolution JSON; the CLI exits `4` and writes only the typed error to stderr.
- Candidate verification: every candidate must pass `api/appdetails`, including response-key/AppID agreement and `type=game`, before automatic acceptance.
- Optional index: `IStoreService/GetAppList/v1` can populate a local name index when `STEAM_WEB_API_KEY` is present. The key is sent only in `x-webapi-key` and is not written to URLs, caches, diagnostics, or output.
- Score: exact title `0.56`, safe-alias exact title `0.53`, developer up to `0.20`, year exact/±1 `0.14/0.10`, unresolved year conflict `-0.10`, compatible game type `0.10`. A later Steam release year is neutral rather than conflicting only for an already verified exact-title, exact-developer/publisher, game-type-compatible candidate.
- `AUTO_ACCEPT/HIGH`: score at least `0.80`, verified strong title, developer or year corroboration, margin at least `0.08`, known compatible type, and no edition conflict.
- Plausible ambiguity and same-base edition conflicts produce `REVIEW_REQUIRED`. Any partial provider run is fail-closed: provisional candidates produce `REVIEW_REQUIRED`, while no candidates produce `PROVIDER_UNAVAILABLE`. Only a complete provider run can produce `AUTO_ACCEPT` or `UNMATCHED`.
- When multiple verified title-family/developer/type candidates remain, a unique closest release year at or before the source year resolves ambiguity (`max(candidateYear <= sourceYear)`). Same-year wins and later remasters/remakes cannot displace an eligible earlier base game. Missing/no-prior/tied/edition-conflicting/partial evidence stays `REVIEW_REQUIRED`.
- Candidates are deduplicated and bounded. Normal ordering is descending score then AppID; a resolved prior-year ambiguity deterministically places the selected candidate first, then eligible prior candidates by descending year and AppID, then remaining candidates.

## Epic-exclusive presentation fallback

A complete, nonpartial Steam `UNMATCHED` for an Epic `GAME` with no plausible Steam candidate may fetch presentation data from the unauthenticated Epic CMS endpoint `GET https://store-content.ak.epicgames.com/api/{locale}/content/products/{slug}`. It does not call GraphQL or authenticated catalog APIs. An explicit slug defaults to `en-US`; a canonical `https://store.epicgames.com/{locale}/p/{slug}` URL supplies both values. With neither, exactly one normalized title-derived slug is tried, and a 404 emits typed `SLUG_REQUIRED` rather than guessing another slug.

The fallback never runs after `REVIEW_REQUIRED`, provider failure, partial results, timeout, HTTP 429 exhaustion, or malformed Steam data. CMS validation is fail-closed: status 200 JSON at most 1 MiB, exactly one `productHome`, matching root/page/offer namespace, strict normalized title and slug agreement, canonical offer identity, and only validated HTTPS Epic/Unreal media. It preserves the decoded stable namespace/catalog ID, requested slug, offer ID, and whether CMS independently supplied the catalog ID.

A successful presentation is explicitly not a Steam match: `decision=SOURCE_CATALOG_FALLBACK`, `matchMethod=SOURCE_CATALOG`, `candidateSteamAppId=null`, and `confidence=SOURCE_ONLY`. Provider-specific output remains under `sourcePresentation`; a separate `canonicalGameMetadata` object now matches all 17 fields and nested `GameMovie` shape consumed by GameNative's current `CanonicalGameMetadata` display model. It maps header, developer/publisher lists, movies, platform enums, language names, nullable unsupported values, and local fetch time without putting Epic identity into Steam fields. Alan Wake 2's stale raw `Coming Soon` label is retained in a warning while `releaseDate` and `releaseYear` remain null.

## TDD and deterministic validation

Tests were written before the package implementation. The isolated initial run observed RED with eight collection errors caused by the absent `steam_resolver` package. The current deterministic suite is GREEN:

```text
85 passed
```

Additional defects were reproduced with focused RED tests before fixes: escaped apostrophes in GOG JSON, Windows console Unicode encoding, missing normalized search queries, edition-conflict review behavior, minimal-input false `UNMATCHED`, partial-provider false certainty, conflated automatic metrics, cross-store release-year vetoes, HTTP 429 leaking into match outcomes, later-release candidates displacing eligible prior-year games, the absence of a guarded Epic-exclusive presentation path, and Epic output that was only Steam-like rather than compatible with GameNative's actual display model. Epic fallback tests cover all required trigger exclusions, strict identity/media validation, derived-slug 404 handling, typed 429 exhaustion, and exact canonical display-field projection.

The minimal required input contract is now proven independently: a verified exact-title game with no developer, release year, or app type returns `REVIEW_REQUIRED`, exposes AppID `870780`, and scores `0.56`. It cannot auto-accept without developer/year corroboration and known compatible type.

Cross-store year evidence now distinguishes original-release metadata from Steam's later store release. KOTOR II's source year 2005 versus Steam year 2012 remains visible as `CROSS_STORE_RELEASE_VARIANCE` with zero weight; it no longer vetoes the verified exact-title, matching-developer, compatible-game, clear-margin match. This exception does not apply to bare title equality, developer conflict, unknown/incompatible type, unverified candidates, edition conflict, ambiguity, or a partial provider run.

Multi-candidate year selection emits `AMBIGUITY_RESOLVED_BY_PRIOR_YEAR` with source year, selected AppID, and deterministically ordered eligible AppID/year/delta evidence. A synthetic 2015-source case with candidate years 2010, 2012, and 2020 selects the unique closest prior candidate from 2012; title equality without developer/type corroboration still cannot auto-accept.

Rate-limit tests prove 429→200 recovery, four-attempt exhaustion, `Retry-After` parsing/capping, no sleep after immediate success, fail-fast candidate traversal, and stderr-only CLI failure. Attempt/status/delay histories contain no request headers or credentials.

Corpus metrics now report `autoAccepted` separately from `automaticCorrect`. A wrong automatic decision receives `WRONG_AUTOMATIC_MATCH`, and failure diagnostics derive `topCandidateSteamAppId` from the ranked candidate list rather than the nullable accepted-candidate field.

Recorded/offline corpus evaluation is byte-deterministic and passes the design gates: recall@5 `30/30`, top-1 `30/30`, auto accepted `30/30`, and automatic correct `30/30`.

## Live observations

Public source corroboration succeeded for `30/30` cases. The run recorded 40 successful HTTP 200 responses across GOG, Epic, Junk Store, and UMU evidence.

No-key live Steam evaluation, with five retrieved/output candidates per case:

| Store | Recall@5 | Top-1 | Auto accepted | Automatic correct |
|---|---:|---:|---:|---:|
| GOG | 10/10 | 10/10 | 10/10 | 10/10 |
| Epic | 10/10 | 10/10 | 10/10 | 10/10 |
| Amazon | 10/10 | 10/10 | 10/10 | 10/10 |
| Overall | 30/30 | 30/30 | 30/30 | 30/30 |

KOTOR II now resolves `AUTO_ACCEPT/HIGH` to AppID `208580` at score `0.86`. Its seven-year delta is retained in evidence as neutral cross-store release variance; no scoring threshold, edition gate, ambiguity margin, verification requirement, or minimal-input policy was weakened.

The newest Steam run recorded 44 successful storesearch parses, 59 verified game details, 51 verified non-game exclusions, and 154 HTTP 200 responses. All completed on attempt one, so no live retry delay was needed; deterministic tests cover both successful retry and exhaustion. Full per-case evidence, attempt histories, retry/ambiguity contracts, separate automatic metrics, minimal-input proof, and sanitized endpoint/status/content-type/body-size/parser diagnostics are in `reports/live-validation-summary.json`.

The live Alan Wake 2 proof first completed Steam search as `UNMATCHED` with no plausible game candidate, then returned `SOURCE_CATALOG_FALLBACK/SOURCE_ONLY` from Epic CMS. Namespace `c4763f236d08423eb47b4c3008779c84` and offer `a7364ebfa54147f1b90f78a81c8093f7` matched, CMS catalog-ID omission was explicitly recorded as `false` corroboration, the stale release label yielded null date/year plus warning, and the bounded source presentation contained nine screenshots and four HLS/DASH/poster movies. The same live result now includes an exact 17-field `canonicalGameMetadata` projection: one developer, one publisher, 14 language names, `WINDOWS`, mapped minimum/recommended requirements, nine screenshots, and four `GameMovie` objects. Full evidence is in `reports/epic-fallback-validation.json` and `EPIC_FALLBACK_REPORT.md`.

## Commands

```bash
python -m pip install -e '.[test]'
python -m pytest -q
python -m steam_resolver corpus validate-sources --file tests/corpus/real-30.json --offline
python -m steam_resolver corpus validate-sources --file tests/corpus/real-30.json --live
python -m steam_resolver corpus evaluate --file tests/corpus/real-30.json --candidate-provider fixture --fixture tests/fixtures/steam-catalog-30.json --require-recall-at5 30 --require-top1 30 --require-auto 30
python -m steam_resolver corpus evaluate --file tests/corpus/real-30.json --candidate-provider storesearch --max-search-candidates 5 --max-output-candidates 5 --timeout 15
printf '%s' '{"source":"EPIC","stableSourceId":"YzQ3NjNmMjM2ZDA4NDIzZWI0N2I0YzMwMDg3NzljODQ.OTNmMmE4YzM1NDc4NDZlZGE5NjZjYjNjMTUyYTAyNmU","displayName":"Alan Wake 2","appType":"GAME","epicProductSlug":"alan-wake-2"}' | python -m steam_resolver resolve --input - --candidate-provider storesearch --timeout 30
```

Optional keyed index:

```bash
export STEAM_WEB_API_KEY='<key>'
python -m steam_resolver steam-index refresh --cache-dir .cache
python -m steam_resolver corpus evaluate --file tests/corpus/real-30.json --candidate-provider cached-index --cache-dir .cache
```

The public Steam Store and Epic CMS endpoints are undocumented and can rate-limit; bounded retries and typed exhaustion keep that transport condition outside resolution outcomes. Exact canonical shape does not by itself change GameNative's frozen Steam-only persistence, provenance, or media policy; those source-aware integration changes remain required after report validation. No app, app-test, app-doc, community-POC, or adb/device operation was performed for this correction.
