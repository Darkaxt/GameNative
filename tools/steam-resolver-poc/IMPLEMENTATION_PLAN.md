# Standalone Steam Resolver POC Implementation Plan

> **For agentic workers:** Execute inline in this session. The user explicitly prohibited subagents, commits, and changes outside `tools/steam-resolver-poc/**`.

**Goal:** Build and live-evaluate a standalone Python 3 CLI that resolves GOG, Epic, and Amazon owned-copy evidence to Steam catalog candidates without using expected Steam AppIDs during resolution.

**Architecture:** Keep immutable input/output models, source-ID validation, normalization/scoring, HTTP providers, resolution orchestration, corpus validation, and CLI wiring in focused modules. Candidate retrieval uses public Steam `storesearch`; every candidate is verified through `appdetails`. A key-gated `IStoreService/GetAppList/v1` refresh path may populate a local candidate-name index, sending the key only through `x-webapi-key`.

**Tech Stack:** Python 3.11+, standard-library HTTP/JSON/dataclasses/argparse, pytest as the only test dependency.

---

## File map

- `pyproject.toml`: self-contained package metadata, CLI entry point, pytest dependency/configuration.
- `src/steam_resolver/models.py`: validated input, Steam candidate, evidence, diagnostic, and resolution records.
- `src/steam_resolver/source_ids.py`: strict GOG, canonical Epic base64url, and Amazon UUID validation/encoding.
- `src/steam_resolver/normalization.py`: Unicode/title/developer normalization, aliases, edition extraction.
- `src/steam_resolver/scoring.py`: deterministic score/evidence and decision thresholds.
- `src/steam_resolver/http.py`: bounded standard-library HTTP transport and redacted diagnostics.
- `src/steam_resolver/steam.py`: live storesearch/appdetails provider and optional cached IStoreService index.
- `src/steam_resolver/resolver.py`: query, verification, ranking, decision, and provider-failure orchestration.
- `src/steam_resolver/corpus.py`: authoritative 30-case contract, public source corroboration, evaluation metrics.
- `src/steam_resolver/cli.py`, `__main__.py`, `__init__.py`: JSON CLI.
- `tests/corpus/real-30.json`: exactly the approved 10 GOG, 10 Epic, and 10 Amazon cases.
- `tests/fixtures/*.json`: deterministic Steam HTTP and malformed-response fixtures.
- `tests/test_*.py`: deterministic unit, provider, resolver, corpus, and CLI contracts.
- `reports/live-validation-summary.json`: observed source/live resolver results and diagnostics.
- `FEATURE_REPORT.md`: concise implementation and honest live findings.

### Task 1: Package and authoritative test inputs

- [ ] Create `pyproject.toml` with `steam-resolver = "steam_resolver.cli:main"`, Python `>=3.11`, no runtime dependencies, and pytest in `test` extras.
- [ ] Create `tests/corpus/real-30.json` with exactly the approved cases. Store expected AppIDs only under evaluation-only `expectedSteamAppId`; include Epic raw namespace/catalogId solely to assert canonical stable-ID derivation and public corroboration URLs for each source.
- [ ] Add tests asserting exactly 30 cases, 10 per source, unique stable IDs, expected AppIDs, and that resolver inputs are projected without `expectedSteamAppId`.

### Task 2: Write all deterministic tests before implementation

- [ ] Add source-ID tests for valid approved forms and malformed decimal/base64url/UUID forms, including canonical Epic re-encoding.
- [ ] Add normalization tests for NFKC/casefold, punctuation/trademarks, legal developer suffixes, Playdead possessive aliases, and preserved edition tokens.
- [ ] Add scoring tests for exact title/developer/year/type weights, year conflict, edition conflict, thresholds, margin, and AppID tie-breaking.
- [ ] Add provider tests using injected fake transport for storesearch parsing, appdetails key/type/title/developer/year verification, malformed JSON, timeout, 429, partial details, bounded deduplication, and header-only IStore key handling.
- [ ] Add resolver tests for `AUTO_ACCEPT/HIGH`, `REVIEW_REQUIRED`, `UNMATCHED`, `PROVIDER_UNAVAILABLE`, sorted bounded candidates, and absent expected-AppID access.
- [ ] Add CLI/schema tests for GameNative-aligned names and deterministic JSON serialization.
- [ ] Run `python -m pytest -q` and record the expected RED caused by the absent `steam_resolver` implementation.

### Task 3: Minimal source models and deterministic normalization

- [ ] Implement strict input parsing: required `source`, `stableSourceId`, `displayName`; optional nullable `developer`, `releaseYear`, and `appType` defaulting to `UNKNOWN`.
- [ ] Implement GOG decimal validation, strict no-padding canonical Epic base64url decode/re-encode, and lowercase canonical Amazon UUID validation.
- [ ] Implement title/developer normalization and only the approved safe alias (`Playdead's/Playdead’s` prefix removal).
- [ ] Implement edition token extraction without discarding edition terms.
- [ ] Run focused tests, then the full deterministic suite to GREEN for these modules.

### Task 4: Minimal scoring and decisions

- [ ] Implement score components exactly: title `0.56` exact or `0.53` safe-alias exact; developer up to `0.20`; year `0.14` exact/`0.10` ±1/`-0.10` conflict; compatible game type `0.10`.
- [ ] Emit structured evidence for every score component and edition conflict.
- [ ] Enforce `AUTO_ACCEPT/HIGH` only at score `>=0.80`, strong title, developer or year corroboration, margin `>=0.08`, and no edition conflict. Use `REVIEW_REQUIRED` at plausible `>=0.62`; otherwise `UNMATCHED`.
- [ ] Sort by descending score then ascending AppID and bound output candidates.
- [ ] Run scoring/resolver tests to GREEN without changing thresholds to fit corpus expectations.

### Task 5: Steam HTTP providers and diagnostics

- [ ] Implement a transport that reports sanitized endpoint, HTTP status, content type, body size, parser result, and error class/message without recording headers or credentials.
- [ ] Query `https://store.steampowered.com/api/storesearch/` with original/alias normalized title variants and deduplicate candidate AppIDs.
- [ ] Verify every retrieved AppID through `https://store.steampowered.com/api/appdetails`, requiring a matching response key, `success=true`, and `type=game`; parse title, developer/publisher, and release year.
- [ ] Distinguish complete no-match from provider unavailability. Partial detail failures produce review warnings/diagnostics, never false unmatched certainty.
- [ ] Implement optional paginated `IStoreService/GetAppList/v1` cache refresh; require `STEAM_WEB_API_KEY`, send it only as `x-webapi-key`, and never serialize/log it.
- [ ] Run provider and full tests to GREEN.

### Task 6: CLI and corpus operations

- [ ] Implement `resolve`, `steam-index refresh`, `corpus validate-sources`, and `corpus evaluate` commands with deterministic JSON output and nonzero exits only for invalid input/contract or unmet explicitly requested gates.
- [ ] Implement offline source-ID/corpus validation and live public corroboration: GOG product API, Epic official product content, and Amazon's public Junk Store record plus UMU evidence where available.
- [ ] Compute per-store and overall count, recall@5, top1, automatic decisions, decision counts, failures, endpoint/status/body/parser diagnostics, commands, and schema/contract result.
- [ ] Ensure expected AppIDs are passed only to post-resolution metric comparison and never into resolver/provider calls.
- [ ] Run all tests to GREEN and run CLI help/smoke commands.

### Task 7: Live validation and reports

- [ ] Install editable test dependencies in a POC-local `.venv` (ignored by POC `.gitignore`).
- [ ] Run live public source corroboration for all 30 and capture honest endpoint diagnostics.
- [ ] Run no-key live resolver evaluation for all 30 through storesearch/appdetails without required-success gates.
- [ ] Save `reports/live-validation-summary.json` with per-case and aggregate metrics, failures, commands, schema/contract result, and sanitized diagnostics.
- [ ] Write `FEATURE_REPORT.md` summarizing architecture, TDD RED/GREEN evidence, commands, observed live metrics/misses, and limitations. Do not claim the design report's aspirational 30/30 gates if observation differs.

### Task 8: Final verification and scope audit

- [ ] Run `python -m pytest -q` from the POC and record the exact pass count.
- [ ] Run offline corpus validation/evaluation using recorded fixtures and verify deterministic output byte-for-byte across two runs.
- [ ] Run `git status --short` and verify every new/modified path from this work is under `tools/steam-resolver-poc/**`; do not alter pre-existing unrelated worktree changes.
- [ ] Scan POC files/reports for `STEAM_WEB_API_KEY`, key values, headers, or personal data and verify no secret was recorded.
- [ ] Do not commit or push.
