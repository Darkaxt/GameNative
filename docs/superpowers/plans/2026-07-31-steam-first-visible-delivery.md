# Steam-First Visible Delivery Implementation Plan

> **For agentic workers:** Implement one vertical slice at a time. Every delegated worker must be told: **“IMPORTANT: Do not invoke Agent and do not spawn/delegate to any subagents. Work alone.”** Do not begin the next slice until the current slice has a signed fork nightly that the user can see and test.

**Goal:** Turn the completed canonical foundation into the visible product originally requested: a default-visible deduplicated Steam-first library, a native game-information page, genre and multi-tag discovery, and transparent Steam-review popularity controls.

**Architecture:** Reuse schema 27, the canonical repository, existing facets/detail snapshots, exact source-action guards, legacy fallback, and the persistent fork signing identity. Deliver five narrow vertical slices; each slice changes production UI, runs only its owning tests, receives one focused review, and ends in a signed `Darkaxt/GameNative` nightly. Full-suite/lint/design auditing occurs once at the final release-candidate gate rather than between slices.

**Tech Stack:** Kotlin 2.1.21, Jetpack Compose, Material 3, Room 2.8.4, Hilt, DataStore Preferences, coroutines/Flow, kotlinx.serialization, OkHttp/MockWebServer, Coil, Media3, JUnit 4, Robolectric, Compose UI tests, GitHub Actions

> **Execution update — 2026-08-08:** The five original visible slices reached signed RC4. The missing automatic/manual Steam catalog resolution and native Reviews/Discussions now follow `docs/superpowers/specs/2026-08-08-steam-resolution-community-visible-core-design.md` and `docs/superpowers/plans/2026-08-08-steam-resolution-community-visible-core.md`. Their three-deliverable 80/20 gates supersede unfinished final-gate work in this plan.

---

## 1. Current checkpoint and supersession

Start from feature commit `fedf889c1b9a597021e296dd60173a14c4c50f2b` on `codex/steam-normalized-game-details-spec`.

Already delivered:

- schema-27 canonical identities, matching, facets, tag dictionary, detail snapshots, and Steam review-count storage;
- deduplicated cards, badges, Copies chooser, preferences, safe unmerge/reset, and exact source-action revalidation;
- bounded manual-export diagnostics;
- persistent fork-only GitHub signing and `v1.1.2-prerelease`;
- a 1,500-copy fixture proving 900 deterministic canonical cards.

Not visibly delivered by default:

- canonical cards still default off;
- canonical card selection still opens a source-native detail screen;
- no genre/tag discovery controls;
- no Steam-review popularity control.

This plan supersedes unfinished Tasks 12–14 in `2026-07-29-steam-library-stage-2-deduplicated-cards-and-actions.md` and the old Stage 3-before-Stage 4 execution order. It does not discard their safety contracts. Reviews, discussion browsing, candidate-search UI, background all-library enrichment, and exhaustive hardening remain later work and do not block the five core slices.

## 2. Non-negotiable boundaries

- Keep the Debug recovery switch. Explicitly stored `false` must continue to restore source-native cards and actions.
- Missing projection readiness or canonical assembly failure must continue to publish the legacy library.
- Canonical identity must never enter source executors. Every core action continues through `OwnedCopyActionGuard`.
- Never persist or export tokens, account IDs, SteamIDs, usernames, game or candidate titles, search text, install paths, full URLs, review bodies, or discussion bodies through diagnostics.
- Metadata/network code may use public Steam AppIDs internally but must not log/export request URLs or IDs.
- Reuse schema 27. Any required schema version change stops the slice and requires explicit user approval.
- Never touch occupied `emulator-5554`; use an exclusive temporary AVD only when a slice’s Compose behavior cannot be proven otherwise.
- Push implementation commits to the feature branch. Before each nightly, fetch and merge official `origin/master`, run the slice’s focused tests, fast-forward fork `master`, and publish with the persistent fork key.
- Official upstream PR comes only after the final signed RC and user testing.

## 3. Time and review budget

Each slice follows this exact budget:

1. One implementation pass.
2. Owning tests only, in isolated Gradle invocations when Windows/Room ordering matters.
3. One focused review of only that slice’s diff.
4. At most one correction pass for a confirmed Critical/High defect in the slice’s visible behavior, identity/account safety, execution boundary, URL/content safety, or diagnostics privacy.
5. Publish a signed fork nightly and ask for the visible symptom plus exported diagnostic report.

If a second blocker appears, stop the slice and report it; do not begin another review loop. Medium/Low findings enter a backlog unless they prevent the visible acceptance test.

Do not run repository-wide unit suites, lint, broad security audits, or design cross-checks between slices. Run them once in Task 6.

Known unrelated failures get one isolated owning-class rerun. If the failure matches the recorded Stage 0/1 Windows/path/Room baseline, record it and continue. Never weaken a test or production invariant to make an inherited failure disappear.

## 4. Release sequence

Reserved versions from the current `versionCode = 22` checkpoint:

| Slice | versionCode | versionName | Tag |
|---|---:|---|---|
| 1 | 23 | `1.1.3-nightly.1` | `v1.1.3-nightly.1` |
| 2 | 24 | `1.1.3-nightly.2` | `v1.1.3-nightly.2` |
| 3 | 25 | `1.1.3-nightly.3` | `v1.1.3-nightly.3` |
| 4 | 26 | `1.1.3-nightly.4` | `v1.1.3-nightly.4` |
| 5/RC | 27 | `1.1.3-rc1` | `v1.1.3-rc1` |

If official upstream consumes one of these version codes before a release, increment that and every later code while preserving strict ordering. Never move or reuse a published tag.

Every nightly announcement contains only:

- the one visible capability to test;
- **Settings → Debug → Canonical library cards** as the kill switch;
- **Settings → Debug → Export feature report** for failures;
- the fork-signing uninstall warning for users coming from official builds.

---

### Task 1: Make the deduplicated library visible by default

**Visible acceptance:** A clean install immediately shows deduplicated canonical cards. A Steam+GOG duplicate appears once with both badges. Disabling the recovery switch and restarting restores the source-native library.

**Files:**

- Modify: `app/src/main/java/app/gamenative/PrefManager.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/settings/SettingsGroupDebug.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/test/java/app/gamenative/library/canonical/action/OwnedCopyActionRouterTest.kt`
- Modify: `app/src/test/java/app/gamenative/ui/model/CanonicalLibraryViewModelTest.kt`
- Create: `app/src/test/java/app/gamenative/library/canonical/CanonicalPublicLibraryGateTest.kt`
- Modify: `.github/workflows/tagged-release.yml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add default and recovery tests**

Prove these exact cases:

```kotlin
@Test
fun publicCanonicalLibraryDefaultsOnWhenNoPreferenceExists() {
    assertTrue(PrefManager.canonicalProjectionEnabled)
    assertTrue(PrefManager.canonicalPublicLibraryEnabled)
}

@Test
fun explicitDisableStillRestoresLegacyLibrary() = runTest {
    PrefManager.canonicalPublicLibraryEnabled = false
    awaitPreference { !PrefManager.canonicalPublicLibraryEnabled }

    assertFalse(publicGate.isEnabled())
    assertTrue(viewModel.state.value.cards.all { it.identity is LibraryCardIdentity.SourceCopy })
}
```

Retain existing readiness-false and canonical-assembly-failure tests; both must still publish legacy cards.

- [ ] **Step 2: Change only the missing-preference default**

```kotlin
var canonicalPublicLibraryEnabled: Boolean
    get() = getPref(CANONICAL_PUBLIC_LIBRARY_ENABLED, true)
    set(value) = setPref(CANONICAL_PUBLIC_LIBRARY_ENABLED, value)
```

Do not overwrite a stored `false` during startup or migration.

- [ ] **Step 3: Update the recovery copy**

The localized subtitle must say that canonical cards are enabled by default, disabling the switch and restarting restores source-native cards/actions, and automatic fallback remains available after canonical failure.

- [ ] **Step 4: Add deterministic release-workflow recovery**

Add `workflow_dispatch` with one required `release_tag` input to `tagged-release.yml`. For manual runs:

- validate `^v[0-9]+\.[0-9]+\.[0-9]+-(nightly\.[0-9]+|rc[0-9]+|prerelease)$`;
- require the tag already exists and never create/move it;
- checkout the immutable tag;
- require the checked-out commit equals the tag target;
- reuse the existing fork signing, signature verification, checksum, and release steps.

Tag-push behavior remains unchanged.

- [ ] **Step 5: Run the owning gate**

```bash
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.library.canonical.CanonicalPublicLibraryGateTest"
./gradlew :app:testModernDebugUnitTest --tests "app.gamenative.library.canonical.CanonicalPublicLibraryGateTest"
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.ui.model.CanonicalLibraryViewModelTest"
./gradlew :app:testModernDebugUnitTest --tests "app.gamenative.ui.model.CanonicalLibraryViewModelTest"
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.library.canonical.action.OwnedCopyActionRouterTest"
./gradlew :app:testModernDebugUnitTest --tests "app.gamenative.library.canonical.action.OwnedCopyActionRouterTest"
```

- [ ] **Step 6: Review once and correct at most once**

Review only default semantics, explicit-disable precedence, readiness fallback, and gate-before-action behavior.

- [ ] **Step 7: Commit and publish nightly 1**

```bash
git add app/src/main app/src/test .github/workflows/tagged-release.yml app/build.gradle.kts
git commit -m "feat: enable canonical library by default" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD
```

Then merge current `origin/master`, run Step 5 once, fast-forward fork `master`, set version code/name to 23/`1.1.3-nightly.1`, and publish immutable tag `v1.1.3-nightly.1`.

---

### Task 2: Add the native Steam-first game-details MVP

**Visible acceptance:** Selecting a canonical card opens a shared native page with media, plain-text description, feature chips, ownership, compatibility/HLTB summary, and structured details. It works from cache offline. Copies and source details remain available. Reviews and Discussions show honest placeholders rather than blocking the page.

**Files:**

- Create: `app/src/main/java/app/gamenative/library/metadata/GameMetadataModels.kt`
- Create: `app/src/main/java/app/gamenative/library/metadata/SteamCatalogProvider.kt`
- Create: `app/src/main/java/app/gamenative/library/metadata/SteamUrlPolicy.kt`
- Create: `app/src/main/java/app/gamenative/library/metadata/GameMetadataRepository.kt`
- Create: `app/src/main/java/app/gamenative/ui/model/GameDetailViewModel.kt`
- Create: `app/src/main/java/app/gamenative/ui/screen/library/CanonicalGameDetailScreen.kt`
- Create: `app/src/main/java/app/gamenative/ui/screen/library/components/GameMediaPager.kt`
- Modify: `app/src/main/java/app/gamenative/db/dao/GameDetailSnapshotDao.kt`
- Modify: `app/src/main/java/app/gamenative/di/CanonicalLibraryModule.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/LibraryScreen.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryDetailPane.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/RecommendedGameScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/test/java/app/gamenative/library/metadata/SteamCatalogProviderTest.kt`
- Create: `app/src/test/java/app/gamenative/library/metadata/SteamUrlPolicyTest.kt`
- Create: `app/src/test/java/app/gamenative/library/metadata/GameMetadataRepositoryTest.kt`
- Create: `app/src/test/java/app/gamenative/ui/model/GameDetailViewModelTest.kt`
- Create: `app/src/androidTest/java/app/gamenative/ui/screen/library/CanonicalGameDetailScreenTest.kt`

- [ ] **Step 1: Define the smallest useful detail model**

```kotlin
data class CanonicalGameMetadata(
    val title: String,
    val shortDescription: String?,
    val about: String?,
    val headerImageUrl: String?,
    val screenshots: List<String>,
    val movies: List<GameMovie>,
    val developers: List<String>,
    val publishers: List<String>,
    val releaseDate: String?,
    val platforms: Set<GamePlatform>,
    val languages: List<String>,
    val requirements: GameRequirements?,
    val genres: List<MetadataFacet>,
    val features: List<MetadataFacet>,
    val achievementCount: Int?,
    val dlcCount: Int?,
    val fetchedAtEpochMs: Long,
)

sealed interface GameDetailState {
    data object Loading : GameDetailState
    data class Content(val metadata: CanonicalGameMetadata, val stale: Boolean) : GameDetailState
    data class Unavailable(val cached: CanonicalGameMetadata?) : GameDetailState
}
```

Do not include review bodies, discussion bodies, credentials, account values, install paths, or arbitrary provider errors.

- [ ] **Step 2: Add provider and URL-policy tests first**

Use MockWebServer to prove:

- one trusted Steam AppID request;
- locale/country validation;
- redirects accepted only when final HTTPS host is `store.steampowered.com` or an explicitly allowlisted Steam media host;
- HTML becomes sanitized plain text before persistence;
- malformed/partial fields degrade independently;
- cancellation escapes unchanged;
- no request URL or AppID reaches diagnostics/log output.

- [ ] **Step 3: Implement one keyless Steam provider**

Use `https://store.steampowered.com/api/appdetails` through the existing `Net.http` client. Request one trusted Steam AppID. Do not add SteamGridDB, HLTB scraping, community APIs, authenticated endpoints, or another provider in this slice.

- [ ] **Step 4: Cache in the existing snapshot table**

```kotlin
interface GameMetadataRepository {
    fun observe(canonicalId: CanonicalGameId): Flow<GameDetailState>
    suspend fun refresh(canonicalId: CanonicalGameId): MetadataRefreshResult
}
```

Use `game_detail_snapshot` JSON; no migration. Serve cached data immediately, refresh after seven days, retain last-known-good content after provider failure, and persist only sanitized fields.

- [ ] **Step 5: Route canonical selection to canonical details**

A canonical card opens `CanonicalGameDetailScreen` directly. `OPEN_SOURCE_DETAILS` moves behind a localized **Source details** action. **Copies** continues to open the existing sheet. Core install/play/update/uninstall continues through the existing router/guard.

- [ ] **Step 6: Build only four stable tabs**

- **Overview:** media, short description, about text, feature chips, compatibility/HLTB summary, owned-source badges.
- **Reviews:** fixed “Review browsing is not available in this build” placeholder plus optional external Steam link through `SteamUrlPolicy`.
- **Discussions:** fixed unavailable placeholder; no page parsing.
- **Details:** developer/publisher/release/platform/language/requirements/achievement/DLC fields.

Extract/reuse `GameMediaPager` from the recommendation screen; do not predownload media.

- [ ] **Step 7: Run owning tests**

```bash
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.library.metadata.*"
./gradlew :app:testModernDebugUnitTest --tests "app.gamenative.library.metadata.*"
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.ui.model.GameDetailViewModelTest"
./gradlew :app:testModernDebugUnitTest --tests "app.gamenative.ui.model.GameDetailViewModelTest"
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.ui.screen.library.appscreen.CanonicalActionExecutionTest"
./gradlew :app:testModernDebugUnitTest --tests "app.gamenative.ui.screen.library.appscreen.CanonicalActionExecutionTest"
./gradlew :app:compileLegacyDebugAndroidTestKotlin
```

Run `CanonicalGameDetailScreenTest` once on an exclusive temporary AVD only if its behavior cannot be covered in Robolectric.

- [ ] **Step 8: Review, commit, and publish nightly 2**

Review only URL/content safety, offline cache behavior, canonical-to-source boundary, and visible page completeness.

```bash
git add app/src/main app/src/test app/src/androidTest
git commit -m "feat: add canonical game details" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD
```

Publish version code/name 24/`1.1.3-nightly.2` at `v1.1.3-nightly.2` after the focused gate.

---

### Task 3: Add visible genre discovery

**Visible acceptance:** The Options panel has searchable Genres. Selecting several genres matches any selected genre. Genre selection combines with every existing filter group using AND. Active removable chips, result count, and classification coverage update immediately.

**Files:**

- Create: `app/src/main/java/app/gamenative/library/discovery/GameFacetModels.kt`
- Create: `app/src/main/java/app/gamenative/library/discovery/GameFacetRepository.kt`
- Create: `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryActiveFilterChips.kt`
- Modify: `app/src/main/java/app/gamenative/db/dao/CanonicalLibraryDao.kt`
- Modify: `app/src/main/java/app/gamenative/db/dao/CanonicalFacetDao.kt`
- Modify: `app/src/main/java/app/gamenative/library/canonical/CanonicalLibraryModels.kt`
- Modify: `app/src/main/java/app/gamenative/library/canonical/CanonicalLibraryRepository.kt`
- Modify: `app/src/main/java/app/gamenative/PrefManager.kt`
- Modify: `app/src/main/java/app/gamenative/ui/data/LibraryState.kt`
- Modify: `app/src/main/java/app/gamenative/ui/model/LibraryViewModel.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryOptionsPanel.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/LibraryScreen.kt`
- Create: `app/src/test/java/app/gamenative/library/discovery/CanonicalDiscoveryFilterTest.kt`
- Create: `app/src/androidTest/java/app/gamenative/ui/screen/library/CanonicalDiscoveryScreenTest.kt`

- [ ] **Step 1: Expose existing canonical facets without a migration**

```kotlin
data class CanonicalDiscoveryFacets(
    val genreKeys: Set<String>,
    val tagIds: Set<Int>,
    val steamReviewCount: Int?,
)
```

Attach facets to the canonical read model in one transactional aggregate read. Never query once per card.

- [ ] **Step 2: Add one explicit filter state**

```kotlin
data class DiscoveryFilterState(
    val selectedGenreKeys: Set<String> = emptySet(),
    val selectedTagIds: Set<Int> = emptySet(),
    val tagMatchMode: TagMatchMode = TagMatchMode.ANY,
    val minimumSteamReviewCount: Int? = null,
)
```

Persist sorted selections through DataStore. Genre/tag values are catalog facets, not account/source identifiers.

- [ ] **Step 3: Prove filter algebra before UI**

```kotlin
val matchesGenres = selectedGenres.isEmpty() ||
    card.genreKeys.any(selectedGenres::contains)

val admitted = matchesExistingSearchSourceStatusTypeStatsCollections && matchesGenres
```

Tests must prove genre OR, AND across groups, filtering before count/pagination, clear restoration, unclassified-card behavior, and 900-card completion without per-card database calls.

- [ ] **Step 4: Populate only trustworthy Steam-first genres**

Reuse existing Steam PICS genre facets. When Task 2 refreshes a trusted Steam-matched canonical, upsert Steam appdetails genre IDs/labels into existing canonical facet tables. Do not build cross-store alias normalization in this slice; non-Steam-only unknown genres remain honestly unclassified.

- [ ] **Step 5: Add UI and coverage**

Add searchable multi-select genre rows, active removable chips, final result count, and `classified / total canonical cards` coverage. Clearing all genres restores unclassified games immediately.

- [ ] **Step 6: Run owning tests, review, and release**

```bash
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.library.discovery.CanonicalDiscoveryFilterTest"
./gradlew :app:testModernDebugUnitTest --tests "app.gamenative.library.discovery.CanonicalDiscoveryFilterTest"
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.ui.model.CanonicalLibraryViewModelTest"
./gradlew :app:testModernDebugUnitTest --tests "app.gamenative.ui.model.CanonicalLibraryViewModelTest"
./gradlew :app:compileLegacyDebugAndroidTestKotlin
```

```bash
git add app/src/main app/src/test app/src/androidTest
git commit -m "feat: filter canonical library by genre" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD
```

Publish version code/name 25/`1.1.3-nightly.3` at `v1.1.3-nightly.3`.

---

### Task 4: Add multi-tag Match Any/All

**Visible acceptance:** The Options panel has a searchable localized Steam tag list. Users can select multiple tags and explicitly choose Match Any or Match All. Tags combine with genres and existing groups using AND. Mode and selections survive restart.

**Files:**

- Create: `app/src/main/java/app/gamenative/library/discovery/SteamTagDictionaryProvider.kt`
- Create: `app/src/test/resources/steam/popular-tags-english.json`
- Create: `app/src/test/java/app/gamenative/library/discovery/SteamTagDictionaryProviderTest.kt`
- Modify: `app/src/main/java/app/gamenative/db/dao/CanonicalFacetDao.kt`
- Modify: `app/src/main/java/app/gamenative/library/discovery/GameFacetModels.kt`
- Modify: `app/src/main/java/app/gamenative/library/discovery/GameFacetRepository.kt`
- Modify: `app/src/main/java/app/gamenative/PrefManager.kt`
- Modify: `app/src/main/java/app/gamenative/ui/data/LibraryState.kt`
- Modify: `app/src/main/java/app/gamenative/ui/model/LibraryViewModel.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryOptionsPanel.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryActiveFilterChips.kt`
- Modify: `app/src/test/java/app/gamenative/library/discovery/CanonicalDiscoveryFilterTest.kt`

- [ ] **Step 1: Define explicit matching semantics**

```kotlin
enum class TagMatchMode { ANY, ALL }

fun matchesTags(cardTags: Set<Int>, selected: Set<Int>, mode: TagMatchMode): Boolean =
    selected.isEmpty() || when (mode) {
        TagMatchMode.ANY -> selected.any(cardTags::contains)
        TagMatchMode.ALL -> selected.all(cardTags::contains)
    }
```

The final predicate is existing groups AND genre group AND tag group.

- [ ] **Step 2: Populate the existing tag dictionary**

Fetch the public localized Steam popular-tag dictionary through the existing HTTP client. Validate HTTPS host and locale. Bulk-upsert `(tagId, locale, label)` into `steam_tag_dictionary`. Cache successful results; retain previous labels after failure. Never render numeric IDs as fallback labels.

- [ ] **Step 3: Persist and restore selections**

Store sorted tag IDs and `TagMatchMode.name` in DataStore. Unknown/removed selected IDs are ignored in UI and removed on the next successful dictionary reconciliation.

- [ ] **Step 4: Add UI and tests**

The tag section includes search, checkbox rows, Any/All segmented control, removable chips, and classified coverage. Tests prove Any, All, genre+tag AND, source/status/search composition, restart persistence, unknown-label behavior, pagination, and the 900-card fixture.

- [ ] **Step 5: Run owning tests, review, and release**

```bash
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.library.discovery.*"
./gradlew :app:testModernDebugUnitTest --tests "app.gamenative.library.discovery.*"
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.ui.model.CanonicalLibraryViewModelTest"
./gradlew :app:testModernDebugUnitTest --tests "app.gamenative.ui.model.CanonicalLibraryViewModelTest"
./gradlew :app:compileLegacyDebugAndroidTestKotlin
```

```bash
git add app/src/main app/src/test
git commit -m "feat: add multi-tag discovery filters" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD
```

Publish version code/name 26/`1.1.3-nightly.4` at `v1.1.3-nightly.4`.

---

### Task 5: Add transparent Steam-review popularity

**Visible acceptance:** Users can choose Any, 100+, 1,000+, or 10,000+ Steam reviews and sort by Steam review count. Unknown counts are excluded only when a threshold is active and sort last otherwise. Indexing coverage/progress is visible and cached counts work offline.

**Files:**

- Create: `app/src/main/java/app/gamenative/library/discovery/SteamReviewSummaryProvider.kt`
- Create: `app/src/main/java/app/gamenative/library/discovery/SteamPopularityEnricher.kt`
- Create: `app/src/test/java/app/gamenative/library/discovery/SteamReviewSummaryProviderTest.kt`
- Create: `app/src/test/java/app/gamenative/library/discovery/SteamPopularityEnricherTest.kt`
- Modify: `app/src/main/java/app/gamenative/db/dao/CanonicalGameDao.kt`
- Modify: `app/src/main/java/app/gamenative/library/canonical/CanonicalLibraryModels.kt`
- Modify: `app/src/main/java/app/gamenative/library/canonical/CanonicalLibraryRepository.kt`
- Modify: `app/src/main/java/app/gamenative/PrefManager.kt`
- Modify: `app/src/main/java/app/gamenative/ui/data/LibraryState.kt`
- Modify: `app/src/main/java/app/gamenative/ui/enums/SortOption.kt`
- Modify: `app/src/main/java/app/gamenative/ui/model/LibraryViewModel.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryOptionsPanel.kt`
- Modify: `app/src/main/java/app/gamenative/library/canonical/CanonicalLibraryDiagnostics.kt`
- Modify: `app/src/test/java/app/gamenative/library/discovery/CanonicalDiscoveryFilterTest.kt`

- [ ] **Step 1: Parse only aggregate review summary**

```kotlin
data class SteamReviewSummary(
    val totalReviews: Int,
)
```

Call Steam AppReviews for one trusted AppID with one result, read only `query_summary.total_reviews`, and discard author/review body fields before returning. Never persist or diagnose review text, author data, URL, or AppID.

- [ ] **Step 2: Add bounded foreground enrichment**

```kotlin
data class PopularityProgress(
    val completed: Int,
    val total: Int,
    val failed: Int,
)
```

When the popularity panel first opens, enrich visible null-count canonicals first, then remaining null-count canonicals with concurrency 4. Persist only non-negative count through `CanonicalGameDao`. Cancel when the owning ViewModel clears; a later invocation resumes from null values. Defer WorkManager/process-death scheduling.

- [ ] **Step 3: Add exact filter and sort rules**

```kotlin
val matchesPopularity = minimum == null ||
    (card.steamReviewCount?.let { it >= minimum } == true)

val popularitySortKey = card.steamReviewCount ?: Int.MIN_VALUE
```

Thresholds are exactly `null`, `100`, `1_000`, and `10_000`. Add a distinct `SortOption.STEAM_REVIEW_COUNT`; do not reuse device/GPU review sorts or appdetails recommendation totals.

- [ ] **Step 4: Show coverage and progress**

Display `known / eligible Steam-matched canonicals`, current enrichment progress, and fixed retry action. Provider failure keeps cached counts and shows a fixed message without hiding unknown cards unless a threshold is active.

- [ ] **Step 5: Run owning tests and one 900-card performance fixture**

```bash
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.library.discovery.*"
./gradlew :app:testModernDebugUnitTest --tests "app.gamenative.library.discovery.*"
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.ui.model.CanonicalLibraryViewModelTest"
./gradlew :app:testModernDebugUnitTest --tests "app.gamenative.ui.model.CanonicalLibraryViewModelTest"
```

MockWebServer tests prove aggregate-only parsing, concurrency 4, cancellation, resume, negative/overflow rejection, cache retention, and no review bodies in persistence/diagnostics.

- [ ] **Step 6: Review, commit, and publish RC**

Review only provider privacy, bounded enrichment, threshold/sort semantics, and visible unknown/progress behavior.

```bash
git add app/src/main app/src/test
git commit -m "feat: add Steam review popularity" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD
```

Publish version code/name 27/`1.1.3-rc1` at `v1.1.3-rc1`.

---

### Task 6: Run one final gate and prepare the official PR

This task starts only after the user has tested the five visible slices. It adds no feature.

**Files:**

- Create: `docs/superpowers/reviews/2026-07-31-steam-first-visible-delivery-cross-check.md`
- Modify production/test files only when a new failure is attributable to Tasks 1–5.

- [ ] **Step 1: Record complaint-driven results**

Record fixed outcomes and aggregate counts only. Do not write titles, IDs, scopes, usernames, paths, URLs, screenshots of private libraries, or report bodies into Git.

- [ ] **Step 2: Run the focused matrix once**

Run each owning class from Tasks 1–5 in Legacy and Modern, canonical Compose tests on one exclusive temporary AVD, migration tests, and `assembleLegacyRelease`.

- [ ] **Step 3: Run broad baseline commands once**

```bash
./gradlew --no-parallel :app:testLegacyDebugUnitTest --continue
./gradlew --no-parallel :app:testModernDebugUnitTest --continue
./gradlew --no-parallel :app:lintLegacyDebug
```

Compare failure names against the documented Stage 0/1 baseline. Do not rerun the full commands in a loop. New attributable failures block the RC; inherited names are reported.

- [ ] **Step 4: Cross-check only the shipped core contract**

Verify:

- default-visible deduplication and kill-switch fallback;
- one canonical detail page with honest Reviews/Discussions exclusions;
- genre OR, tag Any/All, AND across groups;
- transparent Steam-review thresholds/sort/unknown behavior;
- exact source-action targeting and final revalidation;
- manual-export diagnostics privacy;
- fork signing and upgrade path.

Explicitly list deferred community browsing, authenticated interactions, candidate search, WorkManager enrichment, and cross-store taxonomy expansion.

- [ ] **Step 5: Publish one final RC and stop for approval**

Sync official upstream, run the focused gate once, fast-forward fork `master`, publish a new immutable RC tag with a higher version code, and wait for user approval. Do not open the official PR automatically.

- [ ] **Step 6: Create the official PR only after approval**

The PR targets official `master`, includes the signed RC and cross-check evidence, identifies the fork-only workflow/signing branches that must not replace official signing, and accurately lists deferred community features.

---

## 5. Stop conditions

Stop a slice immediately and report rather than opening another investigation cycle when:

- it needs a schema migration;
- trusted Steam identity cannot be resolved without guessing;
- source-action or account isolation regresses;
- a provider requires credentials or prohibited scraping;
- a second confirmed blocker survives the one correction pass;
- the visible acceptance test cannot be demonstrated in the signed nightly.

The response to a stop is a short decision request or a reduced visible scope—not another broad review fleet.
