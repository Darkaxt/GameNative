# Epic Source-Catalog Fallback Validation

## Result

Implemented a presentation-only fallback for Epic-exclusive games. It runs only after a complete, nonpartial Steam lookup returns `UNMATCHED` with no plausible Steam candidate. A success remains explicitly source-only:

```text
decision=SOURCE_CATALOG_FALLBACK
matchMethod=SOURCE_CATALOG
candidateSteamAppId=null
confidence=SOURCE_ONLY
```

It never represents Epic metadata as a Steam identity or invents a Steam AppID.

## Public endpoint and input

The implementation uses only the verified unauthenticated endpoint:

```text
GET https://store-content.ak.epicgames.com/api/{locale}/content/products/{slug}
```

No GraphQL, authenticated Epic catalog API, API key, cookie, or account data is used.

Epic input may provide exactly one of:

- `epicProductSlug`: canonical lowercase ASCII product slug; locale defaults to `en-US`.
- `epicStoreUrl`: canonical `https://store.epicgames.com/{locale}/p/{slug}` URL with no credentials, port, query, or fragment.

If neither is present, exactly one normalized title-derived slug is requested. A derived 404 aborts with typed `SLUG_REQUIRED`; it does not try alternate guesses.

## Trigger and failure policy

All of these conditions are required:

1. `source=EPIC`.
2. `appType=GAME`.
3. The safely retried Steam run completed and is nonpartial.
4. Steam decision is `UNMATCHED`.
5. No plausible Steam candidate remains.

The fallback is not called after `REVIEW_REQUIRED`, provider failure, partial results, timeout, HTTP 429/rate-limit exhaustion, or malformed Steam data. Persistent Epic CMS 429 also aborts the entire operation as typed `RATE_LIMIT_EXHAUSTED`; the CLI writes only error JSON to stderr, exits `4`, and emits no match or presentation JSON.

## CMS validation and presentation

The CMS parser requires:

- HTTP 200 JSON no larger than 1 MiB.
- Exactly one object in `pages[]` with `type=productHome`.
- Stable-ID namespace agreement at the root, product-home page, and offer.
- Requested slug agreement.
- Strict normalized source/root/page title agreement.
- A canonical offer ID.
- Catalog-ID agreement when CMS supplies one; omission remains explicit as `catalogIdCorroboratedByCms=false`.
- HTTPS media hosted only under `epicgames.com` or `unrealengine.com`.

The bounded presentation includes title, short description, about text, header image, up to 20 screenshots, up to 10 movies with HLS/DASH/poster URLs, developer, publisher, reliable release date/year, Windows/macOS/Linux flags, structured and raw languages, canonical Epic store URL, and empty genre/tag/feature lists when absent. Unreliable release labels do not become dates.

## Deterministic TDD proof

The focused pre-implementation test run was RED because `steam_resolver.epic` did not exist. The completed isolated suite is GREEN:

```text
84 passed
```

Required deterministic cases cover:

- Valid Alan Wake-style CMS response.
- Missing explicit slug plus derived 404 to `SLUG_REQUIRED`.
- Namespace mismatch.
- Wrong input store host and wrong media host.
- Multiple `productHome` pages.
- Partial Steam run does not trigger.
- `REVIEW_REQUIRED` does not trigger.
- Steam provider failure does not trigger.
- Malformed Steam data does not trigger.
- Four persistent Epic CMS 429 responses produce typed failure only.

## Live Alan Wake 2 proof

Input identity:

- Stable ID: `YzQ3NjNmMjM2ZDA4NDIzZWI0N2I0YzMwMDg3NzljODQ.OTNmMmE4YzM1NDc4NDZlZGE5NjZjYjNjMTUyYTAyNmU`
- Slug: `alan-wake-2`
- Namespace: `c4763f236d08423eb47b4c3008779c84`
- Expected offer: `a7364ebfa54147f1b90f78a81c8093f7`

The fresh live run completed Steam search without a plausible Steam game, then fetched the Epic CMS response on attempt one:

- HTTP status: 200
- Content type: `application/json`
- Body: 45,723 bytes
- Decision: `SOURCE_CATALOG_FALLBACK`
- Confidence: `SOURCE_ONLY`
- Candidate Steam AppID: null
- Namespace: matched
- Offer ID: matched
- CMS catalog-ID corroboration: false because the product item omitted it
- Screenshots: 9
- HLS/DASH/poster movies: 4
- Structured languages: 14
- Platforms: Windows true; macOS/Linux false
- Raw release label: `Coming Soon`
- Release date/year: null/null
- Warning preserves the stale raw label

## Thirty-case regression

Fresh offline and live validation retained all existing gates:

| Validation | Result |
|---|---:|
| Public source corroboration | 30/30 |
| Offline recall@5 | 30/30 |
| Offline top-1 | 30/30 |
| Offline automatic correct | 30/30 |
| Live recall@5 | 30/30 |
| Live top-1 | 30/30 |
| Live auto accepted | 30/30 |
| Live automatic correct | 30/30 |

The live Steam regression recorded 44 successful storesearch parses, 59 verified game details, 51 verified non-game exclusions, and 154 HTTP 200 responses, all on attempt one.

Full machine-readable evidence is in `reports/epic-fallback-validation.json`. Consolidated 30-case evidence remains in `reports/live-validation-summary.json`.

No GameNative app/source/test/docs file, community POC file, device, adb session, credential, commit, or push was used for this implementation.
