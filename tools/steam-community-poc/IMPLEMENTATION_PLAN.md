# Standalone Steam Community POC Implementation Plan

> **For agentic workers:** Execute inline. Subagents, commits, pushes, device work, and edits outside `tools/steam-community-poc/**` are prohibited.

**Goal:** Build a bounded, credential-free Python CLI that resolves an exact Steam title or positive AppID, collects paginated review/discussion data, emits GameNative-shaped JSON, and records deterministic and live validation evidence.

**Architecture:** Keep pure validation/parsing/pagination functions separate from a no-cookie HTTP transport and orchestration layer. Validate every initial and redirected URL by request purpose; parse only bounded bodies; represent failures as typed diagnostics and final section states. Validate every successful output against a bundled JSON Schema.

**Tech Stack:** Python 3.11+, requests, BeautifulSoup4, jsonschema, pytest.

---

## File map

- `pyproject.toml`: self-contained package, runtime dependencies, pytest configuration, CLI entry points.
- `src/steam_community_poc/bounds.py`: central page/body/item/text limits.
- `src/steam_community_poc/models.py`: diagnostic and collection result helpers matching GameNative field names.
- `src/steam_community_poc/routes.py`: positive AppID, exact-title normalization, URL/route-kind validation, and `fp`/`ctp` route construction.
- `src/steam_community_poc/parsers.py`: deterministic store-search, review JSON, listing HTML, and thread HTML parsers.
- `src/steam_community_poc/http.py`: bounded, fresh-session-per-hop HTTP with manual validated redirects.
- `src/steam_community_poc/collector.py`: resolution and bounded multi-page collection with uniqueness metrics and section states.
- `src/steam_community_poc/schema.py` and `result.schema.json`: output-schema loading and jsonschema validation.
- `src/steam_community_poc/cli.py`: argument validation and JSON output.
- `src/steam_community_poc/live_validation.py`: three-title probe runner that retains summaries rather than live bodies.
- `tests/fixtures/*`: synthetic bounded parser fixtures only.
- `tests/test_*.py`: focused deterministic parser, route, pagination, HTTP, schema, and CLI tests.
- `reports/live-validation-summary.json`: generated machine-readable live evidence.
- `FEATURE_REPORT.md`: concise implementation and live-validation report.

### Task 1: Package and deterministic parser contract

- [ ] Create `pyproject.toml` with `requests`, `beautifulsoup4`, `jsonschema`, and pytest test dependency.
- [ ] Add synthetic review/listing/thread/search fixtures and tests asserting exact GameNative-shaped fields, inert text, malformed-item skipping, truncation, item bounds, `fp` listing continuation, numeric-span-derived `ctp` continuation, and opening-post omission for `ctp>1`.
- [ ] Run `python -m pytest tests/test_parsers.py -q`; expect collection errors because implementation modules do not exist (RED).
- [ ] Implement only parser/model/bounds behavior needed by those tests.
- [ ] Re-run the parser tests; expect PASS (GREEN).

Core assertions include:

```python
assert review == {
    "recommended": True,
    "text": "Safe plain text",
    "playtimeMinutes": 90,
    "helpfulVotes": 2,
    "funnyVotes": 1,
    "commentCount": 3,
    "postedAtEpochSeconds": 10,
    "updatedAtEpochSeconds": 11,
    "receivedForFree": False,
    "earlyAccess": True,
    "developerResponse": "Developer plain text",
}
assert listing["nextRoute"] == "/app/42/discussions/?fp=2"
assert thread_page_2["route"] == "/app/42/discussions/0/100/?ctp=2"
assert thread_page_2["nextRoute"] == "/app/42/discussions/0/100/?ctp=3"
assert "opening post" not in [post["text"] for post in thread_page_2["posts"]]
```

### Task 2: Route, bounds, and redirect policy

- [ ] Write tests for positive decimal AppIDs, page limits, exact normalized-title matching, allowed hosts/ports/schemes, route-kind preservation, listing-only `fp`, thread-only `ctp`, app-ID preservation, credential/fragment rejection, redirect-hop bounds, decoded-body byte bounds, and fresh cookie-free sessions.
- [ ] Run `python -m pytest tests/test_routes.py tests/test_http.py -q`; expect missing behavior failures (RED).
- [ ] Implement route validation and `BoundedHttpClient`, creating and closing one new `requests.Session` for every hop, clearing cookies, setting no `Cookie` header, disabling automatic redirects, validating each `Location`, and stopping before body bytes exceed the cap.
- [ ] Re-run focused tests; expect PASS (GREEN).

The transport interface is:

```python
def get(self, url: str, purpose: RequestPurpose, app_id: int | None = None) -> HttpResult:
    """Return status/body/public hop metadata or raise a typed PocError."""
```

### Task 3: Multi-page collector and output schema

- [ ] Write tests with a scripted transport for exact title resolution, review cursors, unique page routes, listing `fp` pagination, sampled thread `ctp` pagination, repeated-cursor stopping, item overlap diagnostics, unavailable/empty/content section states, and schema validation.
- [ ] Run `python -m pytest tests/test_collector.py tests/test_schema.py -q`; expect failures (RED).
- [ ] Implement collection and the bundled schema. Bound requested pages and sampled threads; stop on missing/repeated continuation; preserve all public routes/URLs while never including headers, cookies, credentials, or account identifiers in diagnostics.
- [ ] Re-run focused tests; expect PASS (GREEN).

Final records use the current Kotlin names:

```json
{
  "reviews": {"sectionState": {"kind": "Content", "canLoadMore": true}, "items": []},
  "discussions": {
    "sectionState": {"kind": "Listing", "canLoadMore": true},
    "items": [],
    "sampledThreads": [{"sectionState": {"kind": "Thread", "canLoadMore": false}, "posts": []}]
  },
  "diagnostics": [{"type": "http", "severity": "info", "code": "http_response", "message": "...", "context": {}}]
}
```

### Task 4: CLI contract

- [ ] Write subprocess/argument tests for a title or positive AppID plus `--review-pages`, `--discussion-pages`, `--thread-pages`, `--sample-threads`, and `--output`; reject zero/negative/over-limit page and item requests before networking.
- [ ] Run `python -m pytest tests/test_cli.py -q`; expect failures (RED).
- [ ] Implement the CLI and entry point, emitting UTF-8 JSON and nonzero status with a typed safe diagnostic on fatal failure.
- [ ] Re-run focused tests; expect PASS (GREEN).
- [ ] Run the complete deterministic suite: `python -m pytest -q`; expect all tests PASS.

### Task 5: Live three-page validation and reports

- [ ] Implement a summary-only live runner for `DREDGE`/`1562430`, `Dota 2`/`570`, and `Stardew Valley`/`413150` after deterministic tests are green.
- [ ] Run equivalent CLI probes requesting three review pages, three listing pages, and up to three pages for sampled threads.
- [ ] Validate each in-memory result against `result.schema.json`; record commands, safe HTTP/parser diagnostics, requested/fetched page counts, item counts, request/item uniqueness, and honest failures in `reports/live-validation-summary.json`.
- [ ] Do not write response bodies or full live item text into tracked reports.
- [ ] Write `FEATURE_REPORT.md` with the deterministic test command/result, exact live commands, concise per-title outcomes, bounds/security behavior, and known live limitations.

### Task 6: Final verification

- [ ] Run `python -m pytest -q` again and `python -m steam_community_poc.cli --help`.
- [ ] Validate `reports/live-validation-summary.json` parses as JSON and contains all three targets.
- [ ] Run `git status --short` and verify every newly written path is under `tools/steam-community-poc/**`; leave pre-existing outside changes untouched.
- [ ] Do not commit or push.
