# Standalone Steam Resolver POC

## Result

Implemented a self-contained Python 3 package and CLI under this directory only. It accepts GameNative-aligned owned-copy evidence (`source`, `stableSourceId`, `displayName`, optional `developer`, `releaseYear`, and `appType`) and emits deterministic JSON using `candidateSteamAppId`, `matchMethod`, `confidence`, and `decisionSource` names.

The authoritative corpus contains exactly 30 cases: 10 GOG, 10 Epic, and 10 Amazon. Epic IDs are canonical unpadded base64url encodings of `namespace.catalogId`; GOG decimal and Amazon `amzn1.adg.product.<UUID>` forms are strictly validated.

Expected Steam AppIDs exist only in the evaluation corpus. Each case is converted to `OwnedCopy` before resolution, and the evaluator reads the expected AppID only after `resolve()` returns.

## Resolution policy

- Candidate retrieval: public no-key Steam Store `api/storesearch` using original, safe Playdead alias, and punctuation-distinct normalized queries.
- Candidate verification: every candidate must pass `api/appdetails`, including response-key/AppID agreement and `type=game`, before automatic acceptance.
- Optional index: `IStoreService/GetAppList/v1` can populate a local name index when `STEAM_WEB_API_KEY` is present. The key is sent only in `x-webapi-key` and is not written to URLs, caches, diagnostics, or output.
- Score: exact title `0.56`, safe-alias exact title `0.53`, developer up to `0.20`, year exact/±1 `0.14/0.10`, year conflict `-0.10`, compatible game type `0.10`.
- `AUTO_ACCEPT/HIGH`: score at least `0.80`, verified strong title, developer or year corroboration, margin at least `0.08`, known compatible type, and no edition conflict.
- Plausible ambiguity and same-base edition conflicts produce `REVIEW_REQUIRED`. Any partial provider run is fail-closed: provisional candidates produce `REVIEW_REQUIRED`, while no candidates produce `PROVIDER_UNAVAILABLE`. Only a complete provider run can produce `AUTO_ACCEPT` or `UNMATCHED`.
- Candidates are deduplicated and sorted by descending score then ascending AppID, with bounded retrieval and output.

## TDD and deterministic validation

Tests were written before the package implementation. The isolated initial run observed RED with eight collection errors caused by the absent `steam_resolver` package. The current deterministic suite is GREEN:

```text
59 passed
```

Additional defects were reproduced with focused RED tests before fixes: escaped apostrophes in GOG JSON, Windows console Unicode encoding, missing normalized search queries, edition-conflict review behavior, minimal-input false `UNMATCHED`, partial-provider false certainty, and conflated automatic metrics.

The minimal required input contract is now proven independently: a verified exact-title game with no developer, release year, or app type returns `REVIEW_REQUIRED`, exposes AppID `870780`, and scores `0.56`. It cannot auto-accept without developer/year corroboration and known compatible type.

Corpus metrics now report `autoAccepted` separately from `automaticCorrect`. A wrong automatic decision receives `WRONG_AUTOMATIC_MATCH`, and failure diagnostics derive `topCandidateSteamAppId` from the ranked candidate list rather than the nullable accepted-candidate field.

Recorded/offline corpus evaluation is byte-deterministic and passes the design gates: recall@5 `30/30`, top-1 `30/30`, auto accepted `30/30`, and automatic correct `30/30`.

## Live observations

Public source corroboration succeeded for `30/30` cases. The run recorded 40 successful HTTP 200 responses across GOG, Epic, Junk Store, and UMU evidence.

No-key live Steam evaluation, with five retrieved/output candidates per case:

| Store | Recall@5 | Top-1 | Auto accepted | Automatic correct |
|---|---:|---:|---:|---:|
| GOG | 10/10 | 10/10 | 10/10 | 10/10 |
| Epic | 10/10 | 10/10 | 10/10 | 10/10 |
| Amazon | 10/10 | 10/10 | 9/10 | 9/10 |
| Overall | 30/30 | 30/30 | 29/30 | 29/30 |

Honest non-automatic result:

- STAR WARS KOTOR II: expected AppID ranked first and was verified, but source release year 2005 conflicts with Steam's 2012 release date. Score `0.76`; thresholds were not weakened, so the result is `REVIEW_REQUIRED`.

The newest Steam run recorded 44 successful storesearch parses, 59 verified game details, 51 verified non-game exclusions, no incomplete responses, and 154 HTTP 200 responses. Full per-case evidence, separate automatic metrics, minimal-input proof, and sanitized endpoint/status/content-type/body-size/parser diagnostics are in `reports/live-validation-summary.json`.

## Commands

```bash
python -m pip install -e '.[test]'
python -m pytest -q
python -m steam_resolver corpus validate-sources --file tests/corpus/real-30.json --offline
python -m steam_resolver corpus validate-sources --file tests/corpus/real-30.json --live
python -m steam_resolver corpus evaluate --file tests/corpus/real-30.json --candidate-provider fixture --fixture tests/fixtures/steam-catalog-30.json --require-recall-at5 30 --require-top1 30 --require-auto 30
python -m steam_resolver corpus evaluate --file tests/corpus/real-30.json --candidate-provider storesearch --max-search-candidates 5 --max-output-candidates 5 --timeout 15
```

Optional keyed index:

```bash
export STEAM_WEB_API_KEY='<key>'
python -m steam_resolver steam-index refresh --cache-dir .cache
python -m steam_resolver corpus evaluate --file tests/corpus/real-30.json --candidate-provider cached-index --cache-dir .cache
```

The public Steam Store endpoints are undocumented and can rate-limit, as the live diagnostics demonstrate. No app, app-test, app-doc, community-POC, adb/device, commit, or push operation was performed for this POC.
