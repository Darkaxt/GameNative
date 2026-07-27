# Steam-Normalized Library and Native Game Details

**Status:** Approved design; staged implementation in progress (Stage 0 complete)

**Date:** 2026-07-27

**Target:** Public GameNative fork
**Scope:** Canonical library identity, duplicate-store grouping, genre/tag discovery, and a native rich game-detail experience

## 1. Summary

GameNative can install and launch games from Steam, GOG, Epic Games Store, and Amazon Games, but its library presentation exposes only a small portion of the metadata available from its synchronized source data and public storefront transports. Some rich Steam fields require new retrieval, parsing, sanitization, and persistence rather than merely exposing an existing presentation model. This gap becomes a serious usability problem for libraries containing hundreds of promotional or unfamiliar titles.

The fork will introduce a Steam-normalized presentation layer. When a non-Steam entitlement can be confidently matched to a Steam application, Steam becomes the canonical source for artwork, title, descriptions, trailers, screenshots, tags, genres, supported features, languages, requirements, reviews, and community discussions. The owning storefront remains authoritative for entitlement, installation, launch, updates, save synchronization, and source-specific state.

Games owned on more than one storefront will appear as one canonical library card. The card will retain all owned copies and route install, play, update, and uninstall actions to the selected copy. Low-confidence matches will never merge automatically.

**Product north star:** Make the library Steam-first, show one card per actual game, provide a native store-like page that quickly explains unfamiliar titles, and let users cut through a roughly 900-game collection with genres, multi-tag matching, popularity, and other useful metadata. The detailed architecture below exists to make those four outcomes safe and maintainable.

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
- Add fast, offline-capable genre and tag filtering across all classified titles, including explicit Match Any/All multi-tag behavior.
- Add popularity filtering and sorting using last-known Steam review count as a transparent community-activity proxy, without inventing a proprietary score.
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
- Add purchasing, cart, pricing-history, or embedded storefront checkout features. Last-verified store price, discount, package, edition, and DLC information may still be displayed as read-only metadata.
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

- Projects existing Steam, GOG, Epic, Amazon, and custom-game entities together with observed install and play-history state into canonical games and owned copies.
- Groups copies that share a confirmed canonical identity.
- Provides canonical library flows, source-aware counts, and detail entry points.
- Does not perform network calls itself. Volatile copy state is supplied by source adapters rather than persisted as canonical metadata.

#### `CanonicalGameResolver`

- Resolves a source-native game identity to a canonical identity and optional Steam AppID.
- Applies trusted mappings, deterministic matching rules, stored user decisions, and optional secondary resolvers.
- Produces a match method and confidence classification with every result.

#### `GameFacetRepository`

- Stores and exposes canonical genres, tags, feature categories, platforms, classification coverage, and lightweight discovery metrics such as Steam review count.
- Resolves Steam genre/tag/category identifiers into localized labels.
- Computes filter counts over unique canonical games.

#### `GameMetadataRepository`

- Selects providers according to source precedence.
- Combines cached Steam and storefront metadata into a normalized `GameMetadata` model.
- Implements stale-while-revalidate behavior and section-level failure isolation.

#### `SteamCatalogProvider`

- Reads and extends the Steam PICS data synchronized by GameNative, including the facet fields that the current persisted projection omits.
- Retrieves missing public catalog/app-info and rich store detail for one AppID at a time, including AppIDs matched from non-Steam ownership.
- Parses descriptions, media, requirements, languages, categories, genres, ratings, DLC, achievements, support, and content descriptors while preserving unsupported/unavailable states.

#### `SteamReviewRepository`

- Fetches Steam review summaries and cursor-paginated review pages.
- Supports the documented AppReviews query mappings for helpful/recent ordering, polarity, purchase type, and language.
- Exposes review bodies, playtime, helpfulness, comment counts, purchase/free flags, and developer responses.

#### `SteamCommunityRepository`

- Fetches and parses public Steam game-hub discussion listings and readable threads.
- Keeps parsing isolated behind stable domain models and fixture tests.
- Produces an external Steam URL for every thread and authenticated action.

#### `OwnedCopySourceAdapter`

- Defines the stable owned-copy key for one source and resolves it to the source's current local row and provider identifiers.
- Observes volatile installation, update, download, branch, container, save, and play-history state from the source's existing managers.
- Declares source capabilities and executes source-native operations without exposing credentials to the canonical layer.
- Revalidates account scope, entitlement, and current state before an operation begins.

#### `OwnedCopyActionRouter`

- Selects the correct source adapter for install, play, update, uninstall, store-page, save, branch, container, launch-configuration, diagnostics, shortcut, mod, and other source-supported actions.
- Resolves a durable owned-copy key to an immutable action target when the user invokes an action, then revalidates it immediately before execution.
- Remembers the preferred copy for each canonical game.
- Never uses presentation provenance, title matching, or mutable list position to choose an executable action.

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

`ProviderGameIdentity` identifies catalog metadata, not an owned or executable copy. It contains the provider and the minimum provider-native catalog identifiers needed for lookup. It never serves as an action target.

`ProviderResult` distinguishes fresh data, stale cached data, unavailable data, unsupported data, and recoverable errors. Each provider documents which fields and query modes its transport actually supports. Providers can change transport or parsing implementation without changing the UI, but unsupported fields are not synthesized to satisfy the presentation model.

## 7. Data Model

Existing `SteamApp`, `GOGGame`, `EpicGame`, `AmazonGame`, and custom-game storage remain authoritative for ownership and source-specific state. The canonical layer references those records through source adapters rather than duplicating credentials, manifests, paths, or entitlement state. Because current sources use different combinations of local row IDs and provider IDs, a generic integer or unscoped string is not a valid cross-source action identity.

### 7.1 Identity boundaries

Three identities remain distinct:

- `canonicalId` is an immutable, opaque internal identifier for presentation grouping. Matching or correcting a Steam AppID never changes it.
- `OwnedCopyKey(accountScope, source, stableSourceId)` durably identifies one entitlement or custom-game copy. `accountScope` is a non-secret stable account discriminator; `stableSourceId` has a source-defined format and is not assumed to be numeric.
- Provider catalog identities such as Steam AppID, Epic namespace/catalog ID, Amazon product/entitlement ID, and GOG game ID are stored or resolved by the corresponding source adapter. They do not become generic executable identifiers.

A source adapter resolves `OwnedCopyKey` to the current local row and provider fields. An action captures the resulting immutable target at the user gesture and revalidates account scope, ownership, installation state, and capabilities immediately before execution. List positions, canonical presentation IDs, and title matches are never action targets.

### 7.2 `CanonicalGameEntity`

| Field | Purpose |
|---|---|
| `canonicalId` | Immutable opaque primary key generated by GameNative. It does not encode a source or Steam AppID. |
| `steamAppId` | Unique nullable canonical Steam AppID when matched. |
| `displayName` | Normalized presentation title. |
| `primaryMetadataSource` | Steam or fallback storefront. |
| `appType` | Game, application, tool, demo, DLC, soundtrack, or unknown. |
| `releaseYear` | Normalized year used in matching and display. |
| `developerKey` | Normalized developer identifier used in matching. |
| `classificationState` | Classified, partially classified, or unclassified; independent of match rejection state. |
| `steamReviewCount` | Nullable last-known total Steam review count used as the transparent popularity metric. |
| `createdAt` / `updatedAt` | Canonical record timestamps. |

### 7.3 `StoreMatchEntity`

The composite key is `(accountScope, source, stableSourceId)`, matching `OwnedCopyKey`.

| Field | Purpose |
|---|---|
| `accountScope` | Non-secret stable discriminator for the owning account or local custom-game scope. |
| `source` | Owning storefront or custom source. |
| `stableSourceId` | Source-defined stable entitlement/copy ID; never assumed to be an integer or an action-ready provider ID. |
| `canonicalId` | Resolved immutable canonical identity. |
| `candidateSteamAppId` | Candidate or confirmed Steam AppID evaluated by this decision, if any. For an accepted row it matches the canonical record; for a rejected row it preserves what must not be proposed again. |
| `matchMethod` | Direct Steam ID, trusted map, exact metadata, optional resolver, fuzzy candidate, or manual. |
| `confidence` | Verified, high, review-required, rejected, or unmatched. |
| `decisionSource` | Automatic or user. |
| `resolverVersion` | Allows deterministic re-evaluation after resolver changes. |
| `matchedAt` | Match timestamp. |

User-confirmed and user-rejected rows are immutable to automatic rematching unless the user explicitly resets them. Automatic rows retain enough normalized evidence and resolver version information to explain the confidence category and to support deterministic re-evaluation; the UI displays the category and method rather than an invented percentage.

### 7.4 `CanonicalGamePreferenceEntity`

One row per canonical game stores user-owned presentation and routing choices:

- Preferred `OwnedCopyKey`, nullable when no valid preference exists
- Explicit title and artwork overrides
- Other future field overrides only when they have a clear reset-to-provider behavior
- Update timestamp

A preferred copy that is temporarily unavailable remains remembered but is not routed until its source adapter confirms it is usable. Automatic metadata refresh never overwrites these preferences.

Canonical merge, unmerge, manual correction, and last-copy removal are single database transactions. They move or invalidate match rows, facets, snapshots, and preferences together; preserve explicit user decisions; and never change an existing `canonicalId`. On merge, the record already associated with the confirmed Steam AppID survives; otherwise the oldest record survives with `canonicalId` as the tie-breaker, and all dependents are repointed in the same transaction. On unmerge, the remaining group keeps its ID while each detached independent game receives a new opaque ID; its copy-specific decision moves with it, while canonical-wide presentation overrides remain with the original group unless the user reapplies them. Source rows and custom-game scans remain application-resolved references rather than polymorphic Room foreign keys.

### 7.5 Canonical facet tables

Normalized cross-reference tables will support fast lookup, counts, localization, and future providers:

- `CanonicalGameGenreCrossRef(canonicalId, genreKey)`
- `CanonicalGameTagCrossRef(canonicalId, tagId)`
- `CanonicalGameFeatureCrossRef(canonicalId, featureKey)`
- `SteamTagDictionaryEntity(tagId, locale, label, fetchedAt)`

Each cross-reference uses its listed columns as a composite primary key, has a reverse index beginning with the facet key, and references `CanonicalGameEntity` with transactional cascade cleanup. Dictionary rows use `(tagId, locale)` as their primary key.

Genre keys will be normalized strings with provider aliases mapped to a shared taxonomy where an unambiguous mapping exists. Steam community tags remain Steam tag IDs so localized labels can change without rewriting every relationship. Raw provider identifiers are retained when a normalized mapping is unavailable.

### 7.6 `GameDetailSnapshotEntity`

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
- Last-observed price, base price, currency, country, discount, packages, editions, and purchase availability when exposed
- Provenance per field group
- Fetch timestamp and source revision information

The snapshot may contain sanitized limited markup, but raw untrusted HTML will not be rendered directly.

### 7.7 Runtime models

`OwnedCopy` is a runtime projection assembled by a source adapter from the authoritative source row plus observed install, update, download, save, and play-history state. Persisted canonical tables do not treat these volatile fields as authoritative:

```kotlin
data class OwnedCopy(
    val key: OwnedCopyKey,
    val nativeTitle: String,
    val isInstalled: Boolean,
    val installPath: String?,
    val lastPlayed: Instant?,
    val playtime: Duration?,
    val updateAvailable: Boolean,
    val capabilities: Set<OwnedCopyCapability>,
)
```

`OwnedCopyKey` is durable and preference-safe, but it is not itself an executable provider request. The source adapter resolves it to a current source row and captures a `ResolvedActionTarget` for the requested operation. Resolution failure or changed account/entitlement state fails closed and returns the user to copy selection instead of falling back to another copy silently.

`CanonicalLibraryItem` combines one `CanonicalGame` with one or more current `OwnedCopy` values, presentation artwork, compatibility, filter facets, and a remembered preferred copy.

## 8. Steam Resolution and Matching

### 8.1 Resolution order

The resolver will apply these methods in order:

1. **Existing Steam identity:** A Steam library entry resolves directly to its Steam AppID and creates or reuses an immutable canonical record.
2. **Stored user decision:** A manual confirmation or rejection is final.
3. **Trusted direct map:** A source-supplied Steam AppID or versioned, validated, one-to-one source-to-Steam map may resolve directly. Indirect joins through another store do not qualify by themselves.
4. **Exact metadata match:** Compatible app type, exact normalized title including meaningful edition tokens, and matching developer or compatible release year.
5. **Optional secondary resolver:** SteamGridDB or a future provider may supply a candidate, which is verified against locally known title/developer/type data.
6. **Fuzzy candidate search:** Produces review-required suggestions only.
7. **Unmatched fallback:** Creates an independent canonical record with no Steam AppID and uses the source's metadata in the shared presentation layout.

### 8.2 Confidence policy

- **Verified:** Direct Steam identity, user confirmation, source-supplied external Steam ID, or a versioned and validated one-to-one direct map. Automatically canonicalized and eligible for duplicate collapse.
- **High:** Exact compatible title plus corroborating developer or release-year evidence. Automatically canonicalized and eligible for duplicate collapse.
- **Review-required:** Fuzzy title, conflicting editions, missing corroboration, optional-resolver-only evidence, or an indirect cross-store map. Never automatically merged.
- **Rejected:** User explicitly rejected the candidate. The resolver must not propose it again unless reset.
- **Unmatched:** No acceptable candidate. The game remains independent and fully usable.

### 8.3 Matching safeguards

- Games, demos, DLC, soundtracks, tools, and applications are never matched across incompatible types.
- Edition tokens such as Deluxe, Definitive, Remastered, Complete, GOTY, and Collection are retained during candidate validation even if a base-title key is also generated.
- Developer aliases are normalized conservatively; a missing developer does not count as positive evidence.
- Release years may differ by one year across storefronts, but a large conflict blocks automatic matching.
- Unknown source app type blocks automatic matching; the candidate remains review-required until the type is known or the user confirms it.
- Existing GOG-title indexes, indirect Epic-to-GOG-to-Steam joins, and first-result autocomplete services produce candidates only unless they satisfy the trusted direct-map contract.
- A single owned-copy key can map to only one canonical game.
- Matching and correction change the canonical record's Steam association and dependent relationships transactionally; they never rederive an action target or mutate source credentials.
- Manual "Fix match" supports search, candidate comparison, confirmation, rejection, and returning to an unmatched identity while preserving the immutable canonical ID.

## 9. Metadata Precedence

For a verified or high-confidence Steam match, presentation fields use this order:

1. Explicit user override
2. Steam canonical metadata
3. Owning storefront metadata
4. Existing GameNative fallback or placeholder

This applies to title, artwork, media, description, genres, tags, features, languages, requirements, ratings, reviews, and discussions.

Ownership and execution fields use a different, non-negotiable rule:

1. Copy explicitly selected for the current action
2. Remembered preferred copy when its adapter confirms it supports the action
3. Most recently used installed copy for play-only actions
4. The only copy that currently supports the action
5. Otherwise, require the copy chooser

The chosen key is captured for the action; a later flow update cannot retarget that invocation. Steam presentation metadata must never overwrite source-native entitlement IDs, install paths, manifest IDs, branches, cloud-save configuration, update state, or authentication state.

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

- If no copy is installed and exactly one copy currently supports installation, Install routes directly to it.
- If no copy is installed and several copies support installation, Install opens a storefront chooser and remembers the choice.
- If exactly one installed copy currently supports play, Play routes directly to it.
- If several installed copies support play, Play uses a valid remembered preferred copy, then the most recently used eligible copy.
- A secondary "Copies" action always allows choosing a different copy.
- The preferred copy is stored per canonical game and can be changed without changing the match.
- Before any action starts, the selected source adapter revalidates the captured copy's account, entitlement, state, and capability. Failure never silently falls through to another copy.

### 10.3 Copy management

The Copies sheet shows, per source:

- Ownership source and source-native title
- Installed state and path
- Installed/download size
- Branch or version where applicable
- Update state
- Source-specific playtime and last played
- Install, play, update, uninstall, store-page, source-details, and other actions declared by that copy's source capabilities

Uninstall and update never operate on more than one copy without an explicit separate action for each copy.

### 10.4 Sorting and summary state

- Recently played uses the maximum `lastPlayed` across copies.
- Installed-first is true when any copy is installed.
- Size sorting uses the preferred copy, or the single installed copy, and labels the source in detail UI.
- Playtime shown in the main detail summary belongs to the active/preferred copy. Playtime is not summed across stores because providers may overlap or measure it differently.
- Compatibility is shown as canonical presentation only when its provider result is confidently associated with the canonical Steam AppID. Existing title-based compatibility retains its lookup title/source provenance and is not silently promoted to stable canonical state.

## 11. Library Discovery and Filtering

### 11.1 Filter UI

The existing left-side Options panel will gain collapsible Genres, Tags, and Popularity sections using the established GameNative option rows, typography, focus rings, spacing, and panel animation.

The library surface will show:

- Result count
- Removable active-filter pills
- Classification and popularity coverage
- A "Review unclassified games" action when coverage is incomplete

Genres and tags will be searchable inside the panel. Frequently used values appear first, followed by a complete localized list.

### 11.2 Filter semantics

- Multiple genres use OR semantics.
- Tags support an explicit Match Any or Match All control.
- Popularity uses the last-known total Steam review count. The initial minimum-community-activity choices are Any, 100+, 1,000+, and 10,000+ reviews; the values are displayed directly rather than hidden behind labels such as "popular."
- Popularity sorting orders by review count descending and places games with unknown popularity last.
- Different filter groups combine with AND semantics.
- Source, status, compatibility, collection, search, genre, tag, and popularity filters compose rather than replace one another.
- Search checks the canonical title and source-native aliases.
- Unknown/unclassified games are excluded from active genre/tag results.
- Games with unknown popularity are excluded only while a minimum-popularity filter is active and are included in the visible coverage count.
- Clearing genre, tag, and popularity filters immediately restores unknown/unclassified games to normal library results.
- Selected genre/tag filters, Match Any/All mode, popularity threshold, and popularity sort persist in preferences consistently with existing library filters.

### 11.3 Facet population

- Steam games use genre, category, supported-language, and store-tag fields parsed from synchronized PICS app-info where those fields are present. The current persisted `SteamApp` projection does not yet retain this complete index, so Phase 1 adds explicit parsing, storage, and update triggers.
- A Steam AppID matched from a non-Steam copy is not assumed to exist in the owned-Steam PICS set. `SteamCatalogProvider` retrieves or reuses public catalog/app-info for that AppID through its approved keyless transport.
- Popularity uses the latest available total Steam review count from synchronized PICS review aggregates or the lightweight AppReviews summary. All copies matched to the same Steam AppID share that value. Unmatched games remain popularity-unknown rather than mixing incomparable storefront metrics.
- The Steam tag dictionary is cached per locale through a separately specified dictionary transport. If the transport is unavailable, raw tag IDs remain stored while unlabeled tags stay out of the filter UI; genres and features continue to work.
- Steam-matched non-Steam games inherit Steam facets.
- Unmatched GOG and Epic games use their persisted genres/tags mapped into the normalized taxonomy.
- Unmatched Amazon and custom games remain unclassified unless native data or a manual Steam match becomes available.

The facet index is eager and lightweight. Heavy descriptions, review pages, screenshots, and videos are not required for filtering. Canonical/facet updates emit an explicit index invalidation signal; the implementation must not rely on source-list row-count changes to notice metadata revisions. The repository may use indexed Room queries or a compact in-memory index, but filtering must occur over canonical IDs before UI pagination and satisfy the benchmark contract in Section 18.5.

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

`Epic owned · Normalized to Steam 620 · High match (exact metadata) · Fix match`

Match presentation uses the stored categorical confidence and method. A numerical percentage is shown only if a future resolver defines, calibrates, persists, and tests a meaningful score.

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
- Helpful and recent ordering, positive/negative, language, and purchase-type filters supported by the public Steam AppReviews transport. "Helpful" maps to `filter=all`, "Recent" maps to `filter=recent`, and the remaining controls map only to documented query values.
- Native review cards with recommendation, review text, available author identifier, playtime, helpfulness, funny votes, comment count, Steam purchase/free-copy flags, Early Access flag, and developer response
- Cursor pagination and pull-to-refresh
- A display name is optional enrichment; when unavailable the UI uses a privacy-conscious Steam user label or identifier rather than fabricating one.
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
- Last-verified read-only price, discount, package, and edition information when available, with country, currency, and refresh state
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
- Steam review count for popularity filtering/sorting
- Classification and popularity coverage

This data is sufficient for grouping, sorting, filtering, and card badges.

#### Lazy rich details

Opening a game loads its cached detail snapshot immediately, then refreshes stale sections in the background. Reviews and discussions load only when their tabs are visited, except for the lightweight review summary used on Overview.

### 13.2 Cache policy

- PICS-derived facets follow Steam change/revision data rather than a wall-clock refresh alone.
- The Steam tag dictionary is cached per locale and refreshed approximately monthly when its transport is available.
- Rich store detail is refreshed after approximately seven days or through manual refresh.
- Price, discount, package, and purchase-availability fields carry country, currency, and fetch time. Once stale they are labeled "last verified" and are never presented as current availability.
- Review summaries are refreshed after approximately six hours.
- Review pages are cached by query and cursor for the active browsing session and may persist within the bounded community cache for offline rereading.
- Discussion listings are refreshed after approximately thirty minutes; parsed threads may persist within that bounded cache.
- Successful core metadata remains readable indefinitely until replaced or explicitly cleared. Review and discussion caches use separate byte/item budgets with LRU eviction and a user-visible clear action so browsing a large library cannot grow storage without bound.
- Image caching continues through Coil. Videos and full screenshot collections are not proactively downloaded.

These durations control freshness rather than UI visibility. Network work uses structured coroutine cancellation, bounded provider concurrency, provider-owned connect/read/call timeouts, retry/backoff that honors recoverable responses, and transport cancellation where supported. A failed or timed-out request keeps the last successful cache entry.

### 13.3 Provider transport contract

Steam catalog detail, AppReviews, tag dictionaries, and community pages are best-effort keyless public transports with different stability and field-coverage guarantees; not all are supported structured APIs. Each provider defines its supported request fields, response fixtures, rate-limit handling, and `unsupported`/`unavailable` behavior. The catalog country comes from an existing validated storefront/account country when available or an explicit application setting; it is never silently hardcoded. Locale and country are part of relevant cache keys.

Provider requests construct HTTPS URLs from approved hosts, validate the effective host after redirects, and reject unapproved media or external-link hosts before passing URLs to Coil, media playback, or the browser. Community requests use no app cookie jar, copied Steam session, age-gate bypass, or authenticated scraping. Public HTML parsing may fail independently and always retains the external Steam fallback.

### 13.4 Large-library behavior

- Canonical grouping and facet filtering occur before UI pagination.
- Rich metadata is deduplicated by canonical Steam AppID, so the same game owned on several stores is fetched once.
- At most one rich-detail refresh per canonical/locale/country key runs concurrently.
- Library sync never launches hundreds of simultaneous detail calls.
- The optional background "Enrich library metadata" operation uses unique persisted Android work with network constraints, bounded batches, retry/backoff, transactional checkpoints, account-change cancellation, visible progress, and a foreground notification when platform rules require it. If that lifecycle integration is not ready for the first release, enrichment remains an explicit foreground operation rather than claiming resumability across process death.

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
- Provider metadata and media requests are HTTPS-only and use provider-specific host allowlists validated after redirects, regardless of the application's broader network-security configuration.
- External URLs are validated before display. Approved Steam/support links open through the system browser only after explicit user action; unapproved hosts are never fetched silently.
- Discussion and review text is treated as untrusted user-generated content.
- The first version will not maintain a Steam Community WebView session, attach a general cookie jar to community requests, or copy community cookies into GameNative.
- Analytics will record feature usage, source type, match method, and success/failure categories only when analytics is enabled. Review text, discussion text, usernames, SteamIDs, search text, and match-candidate titles will not be sent.
- Feature diagnostics are separate from analytics: structured events are stored in a bounded app-private rotation and leave the device only through an explicit manual export. There is no automatic diagnostic upload.
- Diagnostic attributes are enum-allowlisted and sanitized again at the storage boundary. They may contain event categories, counts, durations, source names, capability names, reason codes, error classes, HTTP status, and short hashed correlation IDs.
- Diagnostic events and their report never contain tokens, account IDs, SteamIDs, usernames, game or candidate titles, search text, install paths, full URLs, review bodies, or discussion bodies. Existing raw logcat and crash-log exports remain visibly separate actions and are not included in the privacy-filtered feature diagnostic report.

## 16. Database Migration and Compatibility

- The Room database version will be incremented with explicit migrations for every new table and index.
- Before release, the implementation identifies every public database version supported for direct upgrade and provides a tested, non-destructive chain from each one. Existing destructive fallback does not satisfy this requirement. Any historical version that cannot be supported is documented as an explicit recovery limitation rather than covered by a blanket preservation claim.
- Existing Steam, GOG, Epic, Amazon, container, install, save, and history rows are not rewritten destructively on supported paths.
- Initial canonical rows are generated from existing source tables and source adapters after migration.
- Generation is resumable and idempotent.
- A new versioned detail route accepts `canonicalId` and an optional `OwnedCopyKey`. Existing external launch intents keep their current source/native-ID contract and resolve through the corresponding source adapter; the design does not assume an existing canonical detail deep link where none exists.
- Frontend sync files continue to use source-native IDs unless their public schema is separately versioned.
- If the feature is disabled or initialization fails, the existing source-entry library remains available as a recovery path during rollout.

## 17. Rollout Controls

The bounded local feature-diagnostic foundation is installed before these capabilities and remains independent of their switches so a failed rollout can still be explained. The work will be delivered behind independently controllable capabilities:

- Canonical identity and owned-copy grouping
- Genre/tag facet index and filters
- Native rich detail screen
- Steam reviews
- Steam discussions

Capability dependencies are explicit:

1. Canonical projection, source adapters, migrations, and recovery fallback are prerequisites for every other capability.
2. A completed facet-index checkpoint is required before genre/tag controls become visible; an incomplete index reports progress rather than misleading coverage.
3. The shared native detail shell is required before Reviews or Discussions can be enabled.
4. Reviews and Discussions remain independently switchable and may fail closed to their external Steam actions.

Development builds can enable components independently only when their prerequisites are satisfied. The public build enables a component after its migration, offline behavior, gamepad navigation, cache bounds, and fallback path pass validation. A consumer encountering a missing prerequisite returns to the existing source-entry experience rather than partially interpreting canonical data.

## 18. Testing Strategy

### 18.1 Unit tests

- Title, developer, year, app-type, and edition normalization
- Trusted direct-map boundaries and confidence policy
- Manual confirmation and rejection precedence
- Immutable canonical identity across match, correction, merge, and unmerge
- Account-scoped copy identity and source-adapter resolution
- Duplicate grouping and ungrouping
- Preferred-copy selection, invalid preference handling, and recovery
- Install/play/update/uninstall/save and extended source-action routing matrix
- Captured action targets fail closed after account, entitlement, or install-state changes
- Genre OR and tag Any/All semantics
- Popularity thresholds, descending sort, unknown-last behavior, and coverage
- Cross-group AND semantics
- Classification coverage and unclassified exclusion
- Metadata precedence and fallback
- HTML sanitization
- Steam PICS, store-detail, review, discussion-list, and discussion-thread parsing fixtures
- Diagnostic attribute allowlisting, sensitive-value redaction, hashed correlation stability, deterministic JSONL rotation, chronological report ordering, and clear behavior

### 18.2 Database tests

- Migration from every explicitly supported public schema
- Idempotent and resumable canonical index creation
- Composite unique constraints, reverse facet indexes, and canonical foreign-key cleanup
- Transactional merge, unmerge, correction, and last-copy removal
- Persistence of manual decisions, preferred copies, and presentation overrides
- Locale-specific tag dictionaries and detail snapshots
- Recovery from interrupted enrichment and process death

### 18.3 View-model and repository tests

- Cached-first detail loading
- Independent section failures
- Stale-while-revalidate behavior
- Concurrent request deduplication
- Offline results
- Source-tab counts versus All-tab unique counts
- Versioned canonical detail routes and preservation of existing source-native launch-intent behavior

### 18.4 Compose tests

- Overview media appears above description and information
- Exactly four detail tabs exist in the specified order
- Gamepad focus moves through tabs, media, cards, filters, reviews, discussions, and copy chooser
- Focused content is brought into view
- Store badges and provenance are accessible to screen readers
- Install chooser and Copies sheet route to the selected source
- Loading, stale, empty, error, and unclassified states are understandable without color alone

### 18.5 Scale and regression tests

- A fixed synthetic data fixture containing Room rows and source-adapter runtime snapshots for at least 1,500 source entries with duplicates and 900+ canonical games
- A named canonical filter/query matrix covering source, status, search, genre OR, tag Any/All, popularity threshold/sort, and representative combinations in warm and cold-cache cases
- Facet filtering meets a p95 budget under 100 ms in the JVM benchmark fixture and produces no main-thread work above the Android frame budget in a representative device trace
- Canonical grouping occurs before pagination and produces stable ordering
- Existing install, play, update, uninstall, cloud-save, collection, compatibility, recommendation, and custom-game flows continue to work
- Community cache budgets and eviction are validated against repeated browsing across the large fixture
- No external live endpoint is required for deterministic CI tests; live contract checks are separate diagnostics

## 19. Delivery Sequence

### Phase 0: Feature diagnostics

- Add bounded, app-private structured event storage before changing library identity or routing behavior.
- Add manual export and clear actions plus a structured diagnostic tail in crash reports.
- Instrument current startup, filtering, game resolution, and launch-request boundaries without recording private text or identities.
- Cross-check every later phase against this design and add events at each new failure boundary.

### Phase 1: Canonical foundation

- Add immutable canonical identity, account-scoped copy keys, match, facet, preference/override storage, and explicit migration support.
- Extract source adapters that resolve owned-copy keys, observe volatile state, declare capabilities, and execute current source-native actions.
- Parse and persist Steam PICS facets and tag IDs, including explicit update triggers and non-owned-AppID fallback retrieval.
- Project existing source rows and runtime state into owned copies.
- Implement deterministic matching, transactional merge/unmerge, and manual correction.
- Group duplicate ownerships and route captured copy-specific actions safely.

### Phase 2: Library discovery

- Add genre/tag and popularity sections to the existing Options panel, including multi-tag Any/All and explicit review-count thresholds.
- Add active-filter pills, popularity sorting, counts, coverage, and unclassified/unknown review flows.
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
4. Install, play, update, uninstall, saves, and store links capture an explicitly selected or deterministic owned-copy key, resolve it through the correct source adapter, and fail closed if account, entitlement, state, or capability changes before execution.
5. Low-confidence candidates never merge without user confirmation.
6. Users can fix or reject a match and the decision persists.
7. Genre and tag filters work across unique canonical games, expose Any/All tag modes, compose with existing filters, and report unclassified exclusions; popularity filtering/sorting uses visible Steam review-count values and reports unknown coverage.
8. The native detail screen visually follows GameNative and has exactly Overview, Reviews, Discussions, and Details tabs.
9. Media appears at the top of Overview; there is no separate Media tab.
10. Steam-matched GOG, Epic, and Amazon games use available Steam artwork, media, descriptions, tags, features, last-verified store/package information, review data, and discussions while retaining visible ownership provenance and honest unavailable states.
11. Unmatched games use the same layout with source metadata and remain fully installable/playable.
12. Reviews are browsable natively with documented AppReviews cursor pagination, supported query mappings, available author identity, and external actions for unsupported authenticated operations.
13. Discussions are readable natively when public parsing succeeds, with Steam opened for authenticated actions.
14. Cached facets and details remain useful offline, and independent network failures do not blank unrelated sections.
15. A 900+ game library remains responsive and does not prefetch all heavy media/content.
16. The new versioned canonical detail route works, and existing source-native launch intents and core install/launch flows remain compatible on supported upgrade paths.
17. No Steam Web API key is embedded or required for the feature.
18. A release build can manually export and clear a bounded privacy-filtered diagnostic report covering startup, canonical indexing, matching, metadata, facets, filtering, details, community loading, and action routing without a diagnostic network upload.

## 21. Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Steam rich store-detail transport changes | Isolate parsing in `SteamCatalogProvider`, retain fixtures, cache successful data, label stale commerce data last-verified, and fall back to PICS/source metadata and the external store page. |
| Keyless transport is rate-limited, region-gated, or unavailable | Honor retry guidance and circuit breaking, keep country/locale explicit, degrade each capability independently, and never bypass age gates or require copied credentials. |
| Steam tag-dictionary transport changes | Cache localized dictionaries, preserve raw tag IDs, retain the last successful dictionary, and fall back to genres/features until labels can be refreshed. |
| Steam discussion HTML changes | Isolate parsing, test saved fixtures, fail only the Discussions section, and retain Open Community. |
| Incorrect cross-store match | Conservative confidence policy, strict direct-map contract, app-type/edition safeguards, visible provenance, manual correction, and persistent rejection. |
| Duplicate grouping routes the wrong install | Separate canonical metadata from account-scoped `OwnedCopyKey`, centralize source adapters, capture and revalidate action targets, require a chooser when ambiguous, and test the full capability matrix. |
| Account or entitlement changes during an action | Re-resolve the captured copy through its source adapter and fail closed without silently choosing another copy. |
| Large migration or enrichment queue | Idempotent resumable index construction, supported-schema migration tests, persisted bounded work, visible progress, and cached-first UI. |
| Community cache grows without bound | Separate byte/item budgets, LRU eviction, and a user-visible clear-community-cache action. |
| Diagnostic events leak private library or account data | Accept only enum-allowlisted attributes, sanitize again before persistence, prohibit private text and raw identities, test exported reports with seeded forbidden values, bound local storage, and require manual export. |
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
- Genre, multi-tag, and transparent Steam-review-count popularity filtering are first-class scope.
- Unclassified games are reported honestly rather than silently treated as matches.
- Canonical IDs are immutable internal identities; owned-copy keys, provider catalog IDs, and resolved action targets remain distinct.
- User preferences and matching corrections are transactional and outrank automated refresh.
- Keyless Steam catalog, review, tag, and community transports are best-effort capabilities with cached, external, or unavailable fallbacks rather than guaranteed APIs.
- Feature diagnostics are bounded, privacy-filtered, manually exported, and independent of analytics; they never upload automatically.
- The public fork and APK are accepted project scope; licensing/notices are preserved but are not a design blocker.
