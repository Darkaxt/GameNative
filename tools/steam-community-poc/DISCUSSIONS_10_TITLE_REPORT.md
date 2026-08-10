# Steam Discussions 10-Title Validation Report

## Scope

The GOG group is the resolver corpus's first exact group of 10. This report remains separate from, and does not weaken, the strict three-title validation in `FEATURE_REPORT.md`.

The breadth runner requests one review page as collector scaffolding, scans up to 10 bounded discussion-listing pages, and requires at least four unique nonempty listing pages plus three unique nonempty pages from one dynamically selected thread. A candidate is accepted only when its live paging summary and fetched continuation pages prove the required depth. Counted whitespace/`<br>`-only omissions are allowed only while each required page still contains mapped posts; unexplained skips remain forbidden. No thread route or expected discussion content is hardcoded.

A title probe must resolve exactly to the expected AppID. If Steam search cannot resolve the corpus title, the runner records that typed failure and retries through the POC's supported positive-AppID path. A successful expected-AppID probe may satisfy identity resolution, but all Discussions, schema, identity, parser-skip, and diagnostic gates remain unchanged.

Runner:

```text
.venv/Scripts/python -m steam_community_poc.discussion_validation --output reports/discussions-10-title-validation.json
```

The runner exited 0: all 10 targets satisfy the strict proof.

## Root-cause findings

- **Disco Elysium and Divinity:** Steam store search returned `exact_title_not_found`. Each completed title-search failure and subsequent expected-AppID fallback are retained in `resolutionAttempts`. App-details resolved AppIDs 632470 and 435150, and both complete Discussions probes passed.
- **Cyberpunk, Witcher 3, and Baldur's Gate 3:** the earlier HTTP-200 pages were neither empty forums nor transient block/error pages. They were deterministic client-rendered representations selected by the POC's custom User-Agent: an empty `#application_root` plus Steam's community application bundle, with no server-rendered topics or explicit empty/error marker. The parser now identifies this exact representation as `steam_client_rendered_shell`. Preserving `requests`' standard User-Agent negotiates Steam's bounded server-rendered forum HTML; all three then parsed and passed. Repeating the same deterministic shell was therefore not treated as a transient retry.
- **Stardew Valley:** the first four listing pages exposed only a two-page leading candidate. The bounded 10-page scan found a later reply-rich candidate, and selection accepted it only after its paging summary proved at least three pages. The final sampled thread produced 3 nonempty pages and 46 unique posts.
- **Disco emoticon-only reply:** a post containing only Steam `<img class="emoticon" alt=":1scoreSD:">` was real semantic content, not blank media. The parser now emits the bounded inert alt text and records no skip.
- **Terraria blank reply:** the inspected element contains only `<br><br><br>` inside an otherwise ordinary comment and has no text that can map to `SteamDiscussionPost`. It is omitted and surfaced as `blankPostCount: 1`, not emitted as a post or treated as an unexplained parser skip. Fail-closed behavior remains: if every candidate on a nonempty thread page is blank, parsing raises `thread_selector_drift` rather than returning false empty/content state.

## Fresh live result

| Resolver target / expected AppID | Resolution | Listing proof | Sampled-thread proof | Result |
|---|---|---:|---:|---|
| Disco Elysium - The Final Cut / 632470 | title failed; AppID fallback passed | 10 pages / 150 topics | 3 pages / 46 posts | pass |
| Cyberpunk 2077 / 1091500 | exact title | 10 / 150 | 3 / 46 | pass |
| The Witcher 3: Wild Hunt / 292030 | exact title | 10 / 150 | 3 / 46 | pass |
| Baldur's Gate 3 / 1086940 | exact title | 10 / 150 | 3 / 46 | pass |
| Stardew Valley / 413150 | exact title | 10 / 150 | 3 / 46 | pass |
| No Man's Sky / 275850 | exact title | 10 / 150 | 3 / 46 | pass |
| Control Ultimate Edition / 870780 | exact normalized title | 10 / 150 | 3 / 46 | pass |
| Hollow Knight / 367520 | exact title | 10 / 150 | 3 / 46 | pass |
| Divinity: Original Sin 2 - Definitive Edition / 435150 | title failed; AppID fallback passed | 10 / 150 | 3 / 46 | pass |
| Terraria / 105600 | exact title | 10 / 150 | 3 / 45, plus 1 counted blank omission | pass |

Aggregate: **10 passed, 0 failed**. Every target resolved to its expected AppID, fetched 10 unique nonempty listing pages, and fetched three unique nonempty sampled-thread pages. All topic and post duplicate-identity counts were zero, all schemas were valid, all unexplained discussion parser-skip counts were zero, and no target had warning/error diagnostics. Terraria alone records one explicit blank-post omission; its three pages still contain 45 mapped posts.

Completed endpoint responses:

```json
{
  "app_details": 2,
  "discussion_listing": 100,
  "discussion_thread": 30,
  "reviews": 10,
  "store_search": 10
}
```

Total completed endpoint responses: **152**. The store-search count includes both failed exact-title resolutions; the app-details count records their two fallbacks.

The machine report is `reports/discussions-10-title-validation.json`. It contains commands, resolution attempts, endpoint totals, counts, public routes, `blankPostCount`, typed unexplained-skip reasons, and unmet conditions, but no raw response bodies or complete review/post bodies.
