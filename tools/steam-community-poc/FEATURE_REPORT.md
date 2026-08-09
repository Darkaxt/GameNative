# Steam Community POC Feature Report

## Result

Implemented a standalone Python 3 POC confined to this directory. It accepts an exact Steam title or positive AppID, resolves identity, fetches bounded multi-page reviews and discussion listings, samples reply-rich threads, and emits current GameNative `SteamReviewCard`, `SteamDiscussionSummary`, `SteamDiscussionPost`, `ReviewSectionState`, and `DiscussionSectionState` fields. Complete results are validated against the bundled JSON Schema.

Dependencies and entry points are self-contained in `pyproject.toml`: `requests`, `beautifulsoup4`, `jsonschema`, and `pytest`.

## Deterministic TDD evidence

Every behavior was exercised RED before implementation. The focused review cycle added failing tests for:

- Strict live proof gates and machine-readable unmet conditions.
- Arbitrary 200 HTML, Steam error/block HTML, listing/thread selector drift, and explicit empty markers.
- Response content types that do not match the validated request purpose.
- Transient recommendation and post/comment identities, final-field stripping, cross-page identity overlap, and structural page/element fallback.
- Live range spans such as `Showing 16 - 24 of 24 comments` and comma-separated totals such as `1,545`.
- Live `forum_op_<id>` opening-post identity.

Final deterministic command:

```text
.venv/Scripts/python -m pytest -q
```

Result: **77 passed in 0.53s**.

## Proof, bounds, and safety behavior

- Live success requires exact AppID identity, `Content`/`Listing`/`Thread` states, requested = fetched = unique page counts, nonempty items/posts, the requested sampled-thread count, identity-count consistency, zero duplicate identities, and zero warning/error diagnostics. Every failed condition is retained in `unmetConditions`.
- Reviews dedupe across pages by transient Steam `recommendationid`; thread posts dedupe by transient `forum_op`, `forum_post`, or comment IDs. These internal identities are removed before GameNative-shaped output.
- When a stable post ID is unavailable, identity falls back to validated route/page plus DOM element position. `identityKinds` reports that strategy; identical post text is not treated as identity.
- Listing `fp` and thread `ctp` routes are generated from validated route kinds. Live paging summaries are interpreted as start/end/total item spans to derive page count rather than misreading comment numbers as page numbers.
- Discussion parsers require genuine topic/post containers or explicit empty markers. Arbitrary, blocked, and selector-drift HTML raises typed `ParseError` rather than becoming a false empty state.
- Successful JSON endpoints require `application/json`; discussion endpoints require HTML media types.
- Maximum decoded body: 1 MiB. Maximum redirect hops: 4. Redirects are manual and must preserve scheme, host, port, AppID, and route kind.
- A fresh session is used per request/redirect hop. Cookies are cleared before and after each request, and no `Cookie` header is sent.
- Requests are bounded to 1–10 pages per kind and 1–10 sampled threads. Per-page limits are 20 reviews and 50 discussion items/posts; text, title, route, cursor, and diagnostic structures are bounded.
- Opening posts are emitted only on thread page 1. HTML is converted to inert plain text; scripts, styles, media, and rich-HTML side channels are excluded.
- Public titles, AppIDs, routes, URLs, cursors, reviews, and discussions may be emitted. Credentials, cookies, headers, secrets, and personal account data are not retained.

## Fresh strict live validation

Runner:

```text
.venv/Scripts/python -m steam_community_poc.live_validation --output reports/live-validation-summary.json
```

Equivalent per-title CLI commands are recorded in `reports/live-validation-summary.json`; each requested `--review-pages 3 --discussion-pages 3 --thread-pages 2 --sample-threads 1`. The strict proof therefore requires three review pages, three listing pages, and two nonempty sampled-thread pages per target.

| Target | Reviews | Listing | Sampled thread | Identity strategies | Strict result |
|---|---:|---:|---:|---|---|
| DREDGE / 1562430 | 3 pages / 60 | 3 / 45 | 2 / 31 | recommendation ID, route, Steam post ID | pass |
| Dota 2 / 570 | 3 pages / 60 | 3 / 45 | 2 / 31 | recommendation ID, route, Steam post ID | pass |
| Stardew Valley / 413150 | 3 pages / 60 | 3 / 45 | 2 / 25 | recommendation ID, route, Steam post ID | pass |

All three exact title resolutions matched the expected AppIDs, all complete results passed JSON Schema validation, all identity duplicate counts were zero, all parsers skipped zero items, and no target produced warning/error diagnostics. All 27 HTTP responses were status 200 with zero redirects and validated media types (12 JSON, 15 HTML); decoded body sizes ranged from 710 to 146,439 bytes.

The aggregate strict live result is **all-succeeded**. Every target fetched exactly 3 unique review pages, 3 unique listing pages, and 2 unique nonempty sampled-thread pages. This keeps a strong multi-page proof while matching the available Stardew thread depth instead of treating its legitimate end after page 2 as a failure.

A separate final numeric-AppID probe used `1562430` instead of a title. It resolved by `app_id` and schema-validated 2 review pages / 40 cards, 2 listing pages / 30 topics, and 2 thread pages / 31 posts, with zero duplicate review, topic, or post identities.

The machine report is 36 KB and retains commands, counts, identity strategies, typed HTTP/parser diagnostics, and unmet-condition arrays without persisting complete live review/post bodies.
