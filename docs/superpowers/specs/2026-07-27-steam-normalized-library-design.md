# Steam-Normalized Library and Native Game Details

**Status:** Design approved for specification

**Date:** 2026-07-27

**Target:** Public GameNative fork
**Scope:** Canonical library identity, duplicate-store grouping, genre/tag discovery, and a native rich game-detail experience

## 1. Summary

GameNative can install and launch games from Steam, GOG, Epic Games Store, and Amazon Games, but its library presentation exposes only a small portion of the metadata it already receives. This becomes a serious usability problem for libraries containing hundreds of promotional or unfamiliar titles.

The fork will introduce a Steam-normalized presentation layer. When a non-Steam entitlement can be confidently matched to a Steam application, Steam becomes the canonical source for artwork, title, descriptions, trailers, screenshots, tags, genres, supported features, languages, requirements, reviews, and community discussions. The owning storefront remains authoritative for entitlement, installation, launch, updates, save synchronization, and source-specific state.

Games owned on more than one storefront will appear as one canonical library card. The card will retain all owned copies and route install, play, update, and uninstall actions to the selected copy. Low-confidence matches will never merge automatically.

The user interface will follow GameNative's existing Compose design language. The game page will preserve the current full-bleed hero and integrated action bar, followed by four GameNative-style pill tabs:

1. Overview
2. Reviews
3. Discussions
4. Details

Media will appear at the top of Overview, immediately followed by the game description, features, ratings, compatibility, play status, and source provenance. There will be no separate Media tab.

## 2. Problem Statement

The current library is optimized for finding and launching a known title. It is not optimized for understanding or exploring a large multi-store collection.

Current limitations include:

- Owned-game cards carry names, artwork, install state, size, and compatibility, but not a normalized genre/tag index.
- The detail DTO and owned-game detail page omit descriptions, screenshots, movies, supported features, store genres, languages, requirements, content descriptors, review text, and discussions.
- Steam, GOG, Epic, and Amazon each map into separate presentation flows, even when the products represent the same game.
- The same game can appear more than once when owned on multiple storefronts.
- The filter drawer supports type, status, compatibility, statistics, layout, and Steam collections, but not genre or tag discovery.
- Existing metadata is frequently parsed into store entities and then discarded while constructing `LibraryItem` or `GameDisplayInfo`.

The desired outcome is a library where an unfamiliar title can be understood without leaving GameNative and where a collection of approximately 900 titles can be browsed by meaningful facets.

## 3. Goals

### 3.1 Primary goals

- Present the rich information normally found on a Steam store page using native GameNative components.
- Normalize confidently matched GOG, Epic, and Amazon games to their Steam equivalent for consistent tags, metadata, artwork, and layout.
- Keep ownership and all executable actions attached to the actual owning storefront.
- Collapse multiple owned copies into one canonical library card without losing source-specific state.
- Add fast, offline-capable genre and tag filtering across all classified titles.
- Display Steam user reviews in a native Reviews tab.
- Display Steam community discussion listings and readable threads in a native Discussions tab, while opening Steam for authenticated posting actions.
- Work well with a library of at least 900 owned titles.
- Preserve GameNative's current visual language, gamepad navigation, and accessibility behavior.
- Provide explicit provenance and manual correction for cross-store matches.

### 3.2 Secondary goals

- Create clean provider interfaces for later integrations in the Steam ecosystem.
- Reuse existing PICS, store synchronization, Coil, media playback, HLTB, compatibility, and SteamGridDB capabilities where appropriate.
- Keep the changes modular enough to rebase onto a fast-moving upstream repository.

## 4. Non-Goals

The first version will not:

- Post Steam reviews, review comments, discussion threads, replies, votes, or reports from native UI.
- Store or embed a shared Steam Web API key.
- Guarantee Steam matches for every Amazon, GOG, Epic, custom, demo, DLC, soundtrack, tool, or edition entry.
- Silently merge fuzzy or ambiguous matches.
- Reproduce Steam's store page pixel-for-pixel or import its web layout.
- Predownload every screenshot or video in the library.
- Add purchasing, cart, pricing-history, or embedded storefront checkout features. Current store price, discount, package, edition, and DLC information may still be displayed as read-only metadata.
- Require a hosted aggregation backend.
- Provide complete native community parity when Steam exposes only public HTML rather than a supported structured interface.

## 5. Design Principles

### 5.1 Canonical presentation, source-specific execution

Presentation identity and ownership identity are separate concerns.

- `CanonicalGame` answers: "What game is this, and how should it be presented?"
- `OwnedCopy` answers: "Where is it owned, installed, updated, launched, and synchronized?"

A Steam match changes presentation only. It must never turn an Epic, GOG, or Amazon entitlement into a Steam entitlement or route an action to the wrong service.

### 5.2 One native presentation model

Every source will render through one normalized detail model and one shared native detail screen. Unmatched games will use owning-store metadata as fallback but will retain the same layout and component hierarchy. Separate source-specific visual detail screens will not remain the long-term presentation architecture.

### 5.3 Honest classification

Genre and tag filters will only return games known to satisfy the selected facets. Unclassified games will be excluded from active facet results and reported through a visible coverage count and a dedicated "Review unclassified games" entry point.

### 5.4 Stale data is better than an empty page

Cached metadata will remain usable offline and while refreshes fail. The UI will use stale-while-revalidate behavior and explicit source/freshness indicators where helpful. Network failures will degrade sections independently rather than blanking the whole detail page.

### 5.5 User decisions outrank automation

Manual match confirmations, corrections, rejections, artwork overrides, and preferred-store selections always outrank automatic resolution.

## 6. Architecture

The feature will be divided into focused modules rather than being added directly to the already-large `LibraryViewModel` and `LibraryAppScreen`.

### 6.1 Components

#### `CanonicalLibraryRepository`

- Projects existing Steam, GOG, Epic, Amazon, and custom-game entities into canonical games and owned copies.
- Groups copies that share a confirmed canonical identity.
- Provides canonical library flows, source-aware counts, and detail entry points.
- Does not perform network calls itself.

#### `CanonicalGameResolver`

- Resolves a source-native game identity to a canonical identity and optional Steam AppID.
- Applies trusted mappings, deterministic matching rules, stored user decisions, and optional secondary resolvers.
- Produces a match method and confidence classification with every result.

#### `GameFacetRepository`

- Stores and exposes canonical genres, tags, feature categories, platforms, and classification coverage.
- Resolves Steam genre/tag/category identifiers into localized labels.
- Computes filter counts over unique canonical games.

#### `GameMetadataRepository`

- Selects providers according to source precedence.
- Combines cached Steam and storefront metadata into a normalized `GameMetadata` model.
- Implements stale-while-revalidate behavior and section-level failure isolation.

#### `SteamCatalogProvider`

- Reads Steam PICS data already synchronized by GameNative.
- Fetches missing rich store detail for one AppID at a time when necessary.
- Parses descriptions, media, requirements, languages, categories, genres, ratings, DLC, achievements, support, and content descriptors.

#### `SteamReviewRepository`

- Fetches Steam review summaries and cursor-paginated review pages.
- Supports helpful, recent, positive, negative, purchase type, and language filters.
- Exposes review bodies, playtime, helpfulness, comment counts, purchase/free flags, and developer responses.

#### `SteamCommunityRepository`

- Fetches and parses public Steam game-hub discussion listings and readable threads.
- Keeps parsing isolated behind stable domain models and fixture tests.
- Produces an external Steam URL for every thread and authenticated action.

#### `OwnedCopyActionRouter`

- Selects the correct source adapter for install, play, update, uninstall, store-page, and save actions.
- Remembers the preferred copy for each canonical game.
- Never uses presentation provenance to choose an executable action.

#### `GameDetailViewModel`

- Owns the selected canonical game, selected/preferred owned copy, tab state, section states, review filters, discussion filters, and manual refresh actions.
- Keeps network and Room operations out of composables.

#### `CanonicalGameDetailScreen`

- Replaces source-specific presentation with the shared GameNative-native layout.
- Delegates source-specific executable actions through `OwnedCopyActionRouter`.

### 6.2 Third-party enrichment seam

Later Steam-ecosystem services will integrate through capability-specific enrichment providers rather than being called directly from composables or source managers. Supported capabilities may include artwork, compatibility, completion time, technical fixes, community ratings, and external links.

An enrichment result must declare its provider, canonical Steam AppID, capabilities, fetch time, and field-level provenance. It may fill missing data or provide a separately labeled section, but it will not silently replace available Steam canonical metadata. Existing SteamGridDB, HLTB, and PCGamingWiki integrations can migrate behind this seam incrementally without being prerequisites for the first release.

### 6.3 Provider interface

Providers will return normalized partial data rather than presentation DTOs:

```kotlin
interface GameMetadataProvider {
    suspend fun getFacets(identity: ProviderGameIdentity, locale: String): ProviderResult<GameFacets>
    suspend fun getDetails(identity: ProviderGameIdentity, locale: String, country: String): ProviderResult<GameDetails>
}
```

`ProviderResult` will distinguish fresh data, stale cached data, unavailable data, unsupported data, and recoverable errors. Providers can change their transport or parsing implementation without changing the UI.

## 7. Data Model

Existing `SteamApp`, `GOGGame`, `EpicGame`, `AmazonGame`, and custom-game storage remain authoritative for ownership and source-specific state. The canonical layer references those rows rather than duplicating credentials, manifests, paths, or entitlement state.

### 7.1 `CanonicalGameEntity`

| Field | Purpose |
|---|---|
| `canonicalId` | Stable primary key. `steam:<appid>` when Steam-normalized; otherwise `<source>:<nativeId>`. |
| `steamAppId` | Canonical Steam AppID when matched. |
| `displayName` | Normalized presentation title. |
| `primaryMetadataSource` | Steam or fallback storefront. |
| `appType` | Game, application, tool, demo, DLC, soundtrack, or unknown. |
| `releaseYear` | Normalized year used in matching and display. |
| `developerKey` | Normalized developer identifier used in matching. |
| `classificationState` | Classified, partially classified, unclassified, or rejected. |
| `createdAt` / `updatedAt` | Canonical record timestamps. |

### 7.2 `StoreMatchEntity`

The composite key is `(source, sourceGameId)`.

| Field | Purpose |
|---|---|
| `canonicalId` | Resolved canonical identity. |
| `steamAppId` | Resolved Steam AppID, if any. |
| `matchMethod` | Direct Steam ID, trusted map, exact metadata, optional resolver, fuzzy candidate, or manual. |
| `confidence` | Verified, high, review-required, rejected, or unmatched. |
| `decisionSource` | Automatic or user. |
| `resolverVersion` | Allows deterministic re-evaluation after resolver changes. |
| `matchedAt` | Match timestamp. |

User-confirmed and user-rejected rows are immutable to automatic rematching unless the user explicitly resets them.

### 7.3 Canonical facet tables

Normalized cross-reference tables will support fast lookup, counts, localization, and future providers:

- `CanonicalGameGenreCrossRef(canonicalId, genreKey)`
- `CanonicalGameTagCrossRef(canonicalId, tagId)`
- `CanonicalGameFeatureCrossRef(canonicalId, featureKey)`
- `SteamTagDictionaryEntity(tagId, locale, label, fetchedAt)`

Genre keys will be normalized strings with provider aliases mapped to a shared taxonomy where an unambiguous mapping exists. Steam community tags remain Steam tag IDs so localized labels can change without rewriting every relationship.

### 7.4 `GameDetailSnapshotEntity`

The key is `(canonicalId, locale, country)`.

The snapshot stores:

- Short, detailed, and "About" descriptions
- Developers and publishers
- Release information
- Supported languages with interface/audio/subtitle capabilities
- Platforms and controller support
- Minimum and recommended requirements
- Content descriptors and age ratings
- Website, support, and manual links
- Metacritic and Steam recommendation summary
- Screenshot and movie descriptors/URLs
- DLC and achievement summaries
- Current price, base price, currency, discount, packages, editions, and purchase availability when exposed
- Provenance per field group
- Fetch timestamp and source revision information

The snapshot may contain sanitized limited markup, but raw untrusted HTML will not be rendered directly.

### 7.5 Runtime models

`OwnedCopy` is a runtime projection of an existing source row:

```kotlin
data class OwnedCopy(
    val source: GameSource,
    val sourceGameId: String,
    val isInstalled: Boolean,
    val installPath: String?,
    val lastPlayed: Instant?,
    val playtime: Duration?,
    val updateAvailable: Boolean,
)
```

`CanonicalLibraryItem` combines one `CanonicalGame` with one or more `OwnedCopy` values, presentation artwork, compatibility, filter facets, and a remembered preferred copy.

## 8. Steam Resolution and Matching

### 8.1 Resolution order

The resolver will apply these methods in order:

1. **Existing Steam identity:** A Steam library entry resolves directly to `steam:<appid>`.
2. **Stored user decision:** A manual confirmation or rejection is final.
3. **Trusted direct map:** Existing GameNative GOG mappings, Epic namespace mappings, or another provider's exact external Steam AppID.
4. **Exact metadata match:** Compatible app type, exact normalized title including meaningful edition tokens, and matching developer or compatible release year.
5. **Optional secondary resolver:** SteamGridDB or a future provider may supply a candidate, which is verified against locally known title/developer/type data.
6. **Fuzzy candidate search:** Produces review-required suggestions only.
7. **Unmatched fallback:** Creates a source-native canonical identity and uses the source's metadata in the shared presentation layout.

### 8.2 Confidence policy

- **Verified:** Direct Steam identity, user confirmation, or trusted exact external-ID map. Automatically canonicalized and eligible for duplicate collapse.
- **High:** Exact compatible title plus corroborating developer or release-year evidence. Automatically canonicalized and eligible for duplicate collapse.
- **Review-required:** Fuzzy title, conflicting editions, missing corroboration, or optional-resolver-only evidence. Never automatically merged.
- **Rejected:** User explicitly rejected the candidate. The resolver must not propose it again unless reset.
- **Unmatched:** No acceptable candidate. The game remains independent and fully usable.

### 8.3 Matching safeguards

- Games, demos, DLC, soundtracks, tools, and applications are never matched across incompatible types.
- Edition tokens such as Deluxe, Definitive, Remastered, Complete, GOTY, and Collection are retained during candidate validation even if a base-title key is also generated.
- Developer aliases are normalized conservatively; a missing developer does not count as positive evidence.
- Release years may differ by one year across storefronts, but a large conflict blocks automatic matching.
- A single source entry can map to only one canonical game.
- Manual "Fix match" supports search, candidate comparison, confirmation, rejection, and returning to source-native identity.

## 9. Metadata Precedence

For a verified or high-confidence Steam match, presentation fields use this order:

1. Explicit user override
2. Steam canonical metadata
3. Owning storefront metadata
4. Existing GameNative fallback or placeholder

This applies to title, artwork, media, description, genres, tags, features, languages, requirements, ratings, reviews, and discussions.

Ownership and execution fields use a different, non-negotiable rule:

1. Selected `OwnedCopy`
2. Remembered preferred copy
3. Most recently used installed copy
4. The only available copy

Steam presentation metadata must never overwrite source-native entitlement IDs, install paths, manifest IDs, branches, cloud-save configuration, update state, or authentication state.

## 10. Duplicate Ownership Behavior

### 10.1 Library cards

- The All tab displays one card per canonical game.
- A source tab displays a canonical card once when at least one owned copy belongs to that source.
- The same canonical card may therefore appear in several source tabs, but only once in All.
- Store badges on the card show every ownership source.
- All-tab counts represent unique canonical titles.
- Per-source counts represent unique canonical titles owned on that source.
- Genre/tag counts are calculated over unique canonical titles in the current source/status context.

Only Verified and High matches are collapsed. Review-required and unmatched entries remain separate.

### 10.2 Install and play

- If no copy is installed and only one copy exists, Install routes directly to it.
- If no copy is installed and several copies exist, Install opens a storefront chooser and remembers the choice.
- If exactly one copy is installed, Play routes directly to it.
- If several copies are installed, Play defaults to the most recently used installed copy or the remembered preferred copy.
- A secondary "Copies" action always allows choosing a different copy.
- The preferred copy is stored per canonical game and can be changed without changing the match.

### 10.3 Copy management

The Copies sheet shows, per source:

- Ownership source and source-native title
- Installed state and path
- Installed/download size
- Branch or version where applicable
- Update state
- Source-specific playtime and last played
- Install, play, update, uninstall, store-page, and source-details actions

Uninstall and update never operate on more than one copy without an explicit separate action for each copy.

### 10.4 Sorting and summary state

- Recently played uses the maximum `lastPlayed` across copies.
- Installed-first is true when any copy is installed.
- Size sorting uses the preferred copy, or the single installed copy, and labels the source in detail UI.
- Playtime shown in the main detail summary belongs to the active/preferred copy. Playtime is not summed across stores because providers may overlap or measure it differently.
- Compatibility is canonical-game state and is shared across copies unless future evidence proves a source-specific runtime difference.

## 11. Library Discovery and Filtering

### 11.1 Filter UI

The existing left-side Options panel will gain collapsible Genres and Tags sections using the established GameNative option rows, typography, focus rings, spacing, and panel animation.

The library surface will show:

- Result count
- Removable active-filter pills
- Classification coverage
- A "Review unclassified games" action when coverage is incomplete

Genres and tags will be searchable inside the panel. Frequently used values appear first, followed by a complete localized list.

### 11.2 Filter semantics

- Multiple genres use OR semantics.
- Tags support an explicit Match Any or Match All control.
- Different filter groups combine with AND semantics.
- Source, status, compatibility, collection, search, genre, and tag filters compose rather than replace one another.
- Search checks the canonical title and source-native aliases.
- Unknown/unclassified games are excluded from active genre/tag results.
- Clearing genre/tag filters immediately restores unclassified games to normal library results.
- Selected genre/tag filters and Match Any/All mode persist in preferences consistently with existing library filters.

### 11.3 Facet population

- Steam games use genres, category flags, supported-language data, and store-tag IDs already present in PICS.
- The Steam tag dictionary is cached per locale.
- Steam-matched non-Steam games inherit Steam facets.
- Unmatched GOG and Epic games use their persisted genres/tags mapped into the normalized taxonomy.
- Unmatched Amazon and custom games remain unclassified unless native data or a manual Steam match becomes available.

The facet index is eager and lightweight. Heavy descriptions, review pages, screenshots, and videos are not required for filtering.

## 12. Native Game Detail Experience

### 12.1 Shared shell

The current full-bleed hero, back button, title treatment, and integrated action bar remain the visual foundation. New components will use GameNative's current:

- Bricolage Grotesque typography
- Black/zinc Material 3 surfaces
- Magenta primary, cyan tertiary, and semantic status colors
- 8dp action shapes and 12-16dp content-card shapes
- Two-pixel gamepad focus rings and focus scaling
- Gamepad action bar and bring-into-view behavior

The source line will read in the form:

`Epic owned · Normalized to Steam 620 · 99% match · Fix match`

### 12.2 Tabs

The selected tab is represented by a GameNative-style sliding pill:

#### Overview

Overview is the default landing page and contains, in order:

1. Trailer and screenshot gallery
2. Short and detailed game description
3. Supported-feature chips
4. Review summary
5. Install/compatibility/HLTB/release information
6. Ownership and metadata provenance

The media gallery streams content on demand. The first playable trailer is prominent, followed by screenshots and an explicit total-media count. Playback failure falls back to the associated still or hero artwork.

#### Reviews

The Reviews tab contains:

- Overall and recent Steam review summaries when available
- Helpful, recent, positive/negative, language, and purchase-type filters
- Native review cards with recommendation, review text, author, playtime, helpfulness, funny votes, comment count, Steam purchase/free-copy flags, Early Access flag, and developer response
- Cursor pagination and pull-to-refresh
- An external Steam action for writing, commenting, voting, reporting, or opening the full review

#### Discussions

The Discussions tab contains:

- Public Steam game-hub forum categories when discoverable
- Thread title, author, reply count, view count, and last activity
- Search and category filters when the public page supports them
- Readable public thread content where parsing is reliable
- External Steam actions for posting, replying, voting, reporting, moderation, or any authenticated operation

If discussion parsing becomes unavailable, the tab retains an explanation and a direct Open Community action rather than disappearing.

#### Details

Details contains structured technical and store information:

- Developers and publishers
- Release date and platforms
- Languages with interface/audio/subtitle support
- Minimum and recommended system requirements
- Controller and multiplayer support
- Content descriptors and age ratings
- Achievements, DLC, editions, support, website, and manual links
- Current read-only price, discount, package, and edition information when available
- Field-group provenance and last refresh state

## 13. Network and Cache Strategy

### 13.1 Two-tier loading

#### Eager library index

During normal library synchronization, GameNative builds or refreshes:

- Canonical identity
- Owned-copy relationships
- Match confidence
- Genres
- Tags
- Feature categories
- Classification coverage

This data is sufficient for grouping, sorting, filtering, and card badges.

#### Lazy rich details

Opening a game loads its cached detail snapshot immediately, then refreshes stale sections in the background. Reviews and discussions load only when their tabs are visited, except for the lightweight review summary used on Overview.

### 13.2 Cache policy

- PICS-derived facets follow Steam change/revision data rather than a wall-clock refresh alone.
- The Steam tag dictionary is cached per locale and refreshed approximately monthly.
- Rich store detail is refreshed after approximately seven days or through manual refresh.
- Review summaries are refreshed after approximately six hours.
- Review pages are cached by query and cursor for the active browsing session and may persist for offline rereading.
- Discussion listings are refreshed after approximately thirty minutes; parsed threads are retained for offline rereading.
- Stale successful data remains readable indefinitely until replaced or explicitly cleared.
- Image caching continues through Coil. Videos and full screenshot collections are not proactively downloaded.

These durations control freshness, not cancellation. Network work uses structured coroutine cancellation, bounded provider concurrency, retry/backoff for recoverable responses, and no hard cancellation timeouts.

### 13.3 Large-library behavior

- Canonical grouping and facet filtering occur before UI pagination.
- Rich metadata is deduplicated by canonical Steam AppID, so the same game owned on several stores is fetched once.
- At most one rich-detail refresh per canonical/locale/country key runs concurrently.
- Library sync never launches hundreds of simultaneous detail calls.
- An optional background "Enrich library metadata" operation may process the text/facet queue while on an allowed network, with visible progress and resumable state.

## 14. Offline and Error Behavior

Each detail section has independent loading, stale, empty, and error states.

- Missing Steam detail falls back to PICS and owning-store data.
- A failed metadata refresh keeps the cached snapshot and marks it stale.
- Failed review loading does not affect Overview, Discussions, Details, or executable actions.
- Failed discussion parsing shows an Open Steam Community action.
- Failed media playback falls back to a screenshot or hero image.
- An unmatched non-Steam game still renders the shared detail layout using source metadata.
- A deleted or revoked owned copy is removed from the canonical group; the canonical game remains if another copy exists.
- If a canonical game's last copy disappears, its canonical rows become eligible for cleanup after dependent user decisions and overrides are preserved or migrated.
- A wrong automatic match can be corrected without reinstalling, changing paths, or altering source credentials.

## 15. Security, Privacy, and Content Safety

- No Steam Web API key will be committed, embedded in BuildConfig, included in an APK, or required for rich store details and reviews.
- Publisher keys are out of scope.
- Existing authenticated storefront tokens remain inside their current credential storage and service boundaries.
- Steam description markup is sanitized to a small allowlist before native rendering.
- External URLs are validated to supported HTTPS hosts or opened through the system browser with explicit user action.
- Discussion and review text is treated as untrusted user-generated content.
- The first version will not maintain a Steam Community WebView session or copy community cookies into GameNative.
- Analytics will record feature usage, source type, match method, and success/failure categories only when analytics is enabled. Review text, discussion text, usernames, SteamIDs, search text, and match-candidate titles will not be sent.

## 16. Database Migration and Compatibility

- The Room database version will be incremented with explicit migrations for every new table and index.
- Existing Steam, GOG, Epic, Amazon, container, install, save, and history rows are not rewritten destructively.
- Initial canonical rows are generated from existing source tables after migration.
- Generation is resumable and idempotent.
- Existing `LibraryItem.appId` deep links and intents remain accepted through a compatibility resolver that maps them to `(canonicalId, sourceCopy)`.
- Frontend sync files continue to use source-native IDs unless their public schema is separately versioned.
- If the feature is disabled or initialization fails, the existing source-entry library remains available as a recovery path during rollout.

## 17. Rollout Controls

The work will be delivered behind independently controllable capabilities:

- Canonical identity and owned-copy grouping
- Genre/tag facet index and filters
- Native rich detail screen
- Steam reviews
- Steam discussions

Development builds can enable components independently. The public build should enable a component only after its migration, offline behavior, gamepad navigation, and fallback path pass validation.

## 18. Testing Strategy

### 18.1 Unit tests

- Title, developer, year, app-type, and edition normalization
- Trusted-map and confidence policy
- Manual confirmation and rejection precedence
- Duplicate grouping and ungrouping
- Preferred-copy selection
- Install/play/update/uninstall routing matrix
- Genre OR and tag Any/All semantics
- Cross-group AND semantics
- Classification coverage and unclassified exclusion
- Metadata precedence and fallback
- HTML sanitization
- Steam PICS, store-detail, review, discussion-list, and discussion-thread parsing fixtures

### 18.2 Database tests

- Migration from the current production schema
- Idempotent canonical index creation
- Unique constraints and foreign-key cleanup
- Persistence of manual decisions and preferred copies
- Locale-specific tag dictionaries and detail snapshots
- Recovery from interrupted enrichment

### 18.3 View-model and repository tests

- Cached-first detail loading
- Independent section failures
- Stale-while-revalidate behavior
- Concurrent request deduplication
- Offline results
- Source-tab counts versus All-tab unique counts
- Source-native deep-link compatibility

### 18.4 Compose tests

- Overview media appears above description and information
- Exactly four detail tabs exist in the specified order
- Gamepad focus moves through tabs, media, cards, filters, reviews, discussions, and copy chooser
- Focused content is brought into view
- Store badges and provenance are accessible to screen readers
- Install chooser and Copies sheet route to the selected source
- Loading, stale, empty, error, and unclassified states are understandable without color alone

### 18.5 Scale and regression tests

- A synthetic library of at least 1,500 source entries with duplicates and 900+ canonical games
- Facet filtering completes without visible UI stalls; the target filter computation budget is under 100 ms on the JVM benchmark fixture
- Canonical grouping occurs before pagination and produces stable ordering
- Existing install, play, update, uninstall, cloud-save, collection, compatibility, recommendation, and custom-game flows continue to work
- No external live endpoint is required for deterministic CI tests; live contract checks are separate diagnostics

## 19. Delivery Sequence

### Phase 1: Canonical foundation

- Add canonical, match, facet, and preference storage.
- Parse Steam PICS facets and tag IDs.
- Project existing source rows into owned copies.
- Implement deterministic matching and manual correction.
- Group duplicate ownerships and route copy-specific actions.

### Phase 2: Library discovery

- Add genre/tag sections to the existing Options panel.
- Add active-filter pills, counts, coverage, and unclassified review flow.
- Validate source tabs, counts, sorting, search aliases, pagination, and 900-game performance.

### Phase 3: Native rich details

- Add normalized metadata providers and cache.
- Replace owned-game source-specific presentation with `CanonicalGameDetailScreen`.
- Implement Overview with media first, descriptions, features, ratings, compatibility, HLTB, and provenance.
- Implement structured Details.

### Phase 4: Community content

- Add official Steam review retrieval and native review browsing.
- Add isolated public Steam discussion parsing and external authenticated actions.
- Add resilient fallbacks and fixture coverage.

### Phase 5: Hardening and public release

- Run migrations and regression suites across existing storefronts.
- Validate offline behavior, gamepad focus, accessibility, memory use, and large-library responsiveness.
- Update user-facing documentation, privacy disclosures, licenses/notices, and release notes.

## 20. Acceptance Criteria

The feature is complete when all of the following are true:

1. All confidently matched copies share one canonical presentation and one set of Steam-normalized facets.
2. The All tab shows a single card for a game owned on multiple storefronts.
3. Source tabs show that canonical game when it is owned on the selected source and preserve source-aware counts.
4. Install, play, update, uninstall, saves, and store links always use an explicitly selected or deterministic owned copy.
5. Low-confidence candidates never merge without user confirmation.
6. Users can fix or reject a match and the decision persists.
7. Genre and tag filters work across unique canonical games, expose Any/All tag modes, compose with existing filters, and report unclassified exclusions.
8. The native detail screen visually follows GameNative and has exactly Overview, Reviews, Discussions, and Details tabs.
9. Media appears at the top of Overview; there is no separate Media tab.
10. Steam-matched GOG, Epic, and Amazon games use Steam artwork, media, descriptions, tags, features, current store/package information, review data, and discussions while retaining visible ownership provenance.
11. Unmatched games use the same layout with source metadata and remain fully installable/playable.
12. Reviews are browsable natively with official API pagination and filters.
13. Discussions are readable natively when public parsing succeeds, with Steam opened for authenticated actions.
14. Cached facets and details remain useful offline, and independent network failures do not blank unrelated sections.
15. A 900+ game library remains responsive and does not prefetch all heavy media/content.
16. Existing source-native deep links and core install/launch flows remain compatible.
17. No Steam Web API key is embedded or required for the feature.

## 21. Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Steam rich store-detail transport changes | Isolate parsing in `SteamCatalogProvider`, retain fixtures, cache successful data, and fall back to PICS/source metadata and the external store page. |
| Steam tag-dictionary transport changes | Cache localized dictionaries, preserve raw tag IDs, retain the last successful dictionary, and fall back to genres/features until labels can be refreshed. |
| Steam discussion HTML changes | Isolate parsing, test saved fixtures, fail only the Discussions section, and retain Open Community. |
| Incorrect cross-store match | Conservative confidence policy, app-type/edition safeguards, visible provenance, manual correction, and persistent rejection. |
| Duplicate grouping routes the wrong install | Separate canonical metadata from `OwnedCopy`, centralize routing, require a chooser when ambiguous, and test the full action matrix. |
| Large migration or enrichment queue | Idempotent resumable index construction, bounded concurrency, visible progress, and cached-first UI. |
| Fast upstream changes cause merge conflicts | Keep provider, resolver, cache, filter, and screen modules isolated; minimize edits to source service managers and legacy screens. |
| Community content exposes unsafe markup | Treat all content as untrusted, sanitize descriptions, render review/discussion text as text, and validate external URLs. |
| Store taxonomy conflicts | Prefer Steam for matched games, normalize only unambiguous fallback genres, preserve provenance, and keep raw provider identifiers available. |

## 22. Resolved Product Decisions

- Native full-detail presentation is required; a WebView store page is not the primary experience.
- GameNative's visual language is retained and extended rather than replaced.
- Media is part of Overview and appears before descriptive information.
- Reviews and Discussions are separate tabs.
- Steam is the canonical presentation source for confidently matched non-Steam games.
- The owning storefront remains authoritative for execution and entitlement.
- Duplicate owned copies collapse into one canonical card when the match is Verified or High.
- Ambiguous install/play actions use a remembered, user-visible copy selection.
- Genre and tag filtering is first-class scope.
- Unclassified games are reported honestly rather than silently treated as matches.
- The public fork and APK are accepted project scope; licensing/notices are preserved but are not a design blocker.
