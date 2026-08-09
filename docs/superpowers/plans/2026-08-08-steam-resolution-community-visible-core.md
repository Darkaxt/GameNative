# Steam Resolution and Native Community Visible-Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Every delegated worker must be told: **“IMPORTANT: Do not invoke Agent and do not spawn/delegate to any subagents. Work alone.”**

**Goal:** Automatically resolve non-Steam games to trustworthy Steam catalog identities with a native correction path, then ship native Reviews and Discussions as three independently testable signed deliverables.

**Architecture:** Keep canonical projection and source execution unchanged. Use authenticated Steam Web API AppList discovery with a compact app-private public-catalog cache and exact local title lookup, then feed Store-validated evidence into guarded canonical mutations and reuse accepted AppIDs for metadata/facets/popularity. Add Reviews and Discussions through a separate no-store community package and extend only the existing detail ViewModel and placeholder branches.

**Tech Stack:** Kotlin 2.1.21, Jetpack Compose, Material 3, Room 2.8.4 schema 27, Hilt, DataStore Preferences, coroutines/Flow, kotlinx.serialization, OkHttp/MockWebServer, Jsoup 1.23.1, JUnit 4, Robolectric, Compose UI tests, GitHub Actions

**Design:** `docs/superpowers/specs/2026-08-08-steam-resolution-community-visible-core-design.md`

---

## 1. Delivery discipline

The implementation first produces three highest-priority visible signed RCs:

| Core deliverable | Initial reserved release |
|---|---|
| Automatic resolution + Fix Steam match | code 31, `1.1.3-rc5`, `v1.1.3-rc5`; corrected provider/source RC code 32, `1.1.3-rc6`, `v1.1.3-rc6`; corrected key-validation/persistence RC code 33, `1.1.3-rc7`, `v1.1.3-rc7`; corrected key-gate/terminal-page RC code 34, `1.1.3-rc8`, `v1.1.3-rc8` |
| Native Reviews | code 35, `1.1.3-rc9`, `v1.1.3-rc9` |
| Native Discussions | code 36, `1.1.3-rc10`, `v1.1.3-rc10` |

If a correction release consumes a code/tag, increment every subsequent reservation. Never move or reuse a published tag.

For each deliverable:

1. one implementation pass;
2. owning tests only;
3. one focused design cross-check;
4. one consolidated correction commit for confirmed Critical/High blockers;
5. rerun affected owning tests and safety/privacy sentinels;
6. sync official upstream;
7. publish one signed fork RC;
8. stop the deliverable and report the visible acceptance path.

A second surviving Critical/High blocker stops delivery. Every Medium/Low finding receives a design-ledger ID, target task/stage, and acceptance condition before the cross-check closes.

RC5–RC9 do not end the plan. Tasks 15–19 execute the design ledger's resolver/detail completion, community completion, external-storage hardening, LSFG decision, and aggregate upstream handoff. An item may leave the plan only with implementation evidence or an explicit user-approved permanent-boundary decision.

## 2. File structure

### Steam resolution

- Create: `app/src/main/java/app/gamenative/library/canonical/catalog/SteamCatalogModels.kt`
- Create: `app/src/main/java/app/gamenative/library/canonical/catalog/SteamCatalogCandidatePolicy.kt`
- Create: `app/src/main/java/app/gamenative/library/canonical/catalog/SteamCatalogSearchProvider.kt`
- Create: `app/src/main/java/app/gamenative/library/canonical/catalog/SteamCatalogResolutionRepository.kt`
- Create: `app/src/main/java/app/gamenative/library/canonical/catalog/SteamAcceptedIdentityEnricher.kt`
- Create: `app/src/main/java/app/gamenative/library/canonical/catalog/SteamCatalogResolutionDiagnostics.kt`
- Create: `app/src/main/java/app/gamenative/library/canonical/catalog/SteamPublicPicsFacetSource.kt`
- Create: `app/src/main/java/app/gamenative/ui/model/SteamMatchViewModel.kt`
- Create: `app/src/main/java/app/gamenative/ui/screen/library/components/SteamMatchPicker.kt`
- Create: `app/src/main/java/app/gamenative/ui/screen/library/components/SteamResolutionStatus.kt`
- Modify: `CanonicalGameResolver.kt`, `CanonicalMutationRepository.kt`, canonical DAOs, metadata/facet repositories, `SteamService.kt`, Hilt bindings, library/detail UI, strings, and focused tests.

### Community foundation and Reviews

- Create: `app/src/main/java/app/gamenative/library/community/SteamCommunityModels.kt`
- Create: `app/src/main/java/app/gamenative/library/community/SteamCommunityUrlPolicy.kt`
- Create: `app/src/main/java/app/gamenative/library/community/SteamCommunityTransport.kt`
- Create: `app/src/main/java/app/gamenative/library/community/SteamReviewPageProvider.kt`
- Create: `app/src/main/java/app/gamenative/library/community/InMemorySteamCommunityRepository.kt`
- Create: `app/src/main/java/app/gamenative/library/community/SteamCommunityDiagnostics.kt`
- Create: `app/src/main/java/app/gamenative/ui/screen/library/components/SteamReviewsTab.kt`
- Modify: `GameDetailViewModel.kt`, `CanonicalGameDetailScreen.kt`, `LibraryScreen.kt`, DI, strings, and tests.

### Discussions

- Create: `app/src/main/java/app/gamenative/library/community/SteamDiscussionProvider.kt`
- Create: `app/src/main/java/app/gamenative/library/community/SteamDiscussionParser.kt`
- Create: `app/src/main/java/app/gamenative/ui/screen/library/components/SteamDiscussionsTab.kt`
- Add synthetic fixtures under `app/src/test/resources/steam/community/`.
- Modify: version catalog, app dependencies, third-party notices, community repository/ViewModel/UI/tests.

---

### Task 1: Freeze the implementation baseline and release contracts

**Files:**
- Modify only on conflict: `docs/superpowers/specs/2026-08-08-steam-resolution-community-visible-core-design.md`
- Test: `app/src/test/java/app/gamenative/build/TaggedReleaseWorkflowContractTest.kt`
- Create: `app/src/test/java/app/gamenative/build/DarkaxtFastReleaseWorkflowContractTest.kt`

- [ ] **Step 1: Fetch official and fork refs without touching the dirty main worktree**

Run from the implementation worktree:

```bash
git fetch --prune origin master
git fetch --prune fork master codex/steam-normalized-game-details-spec
git rev-list --left-right --count origin/master...HEAD
git rev-list --left-right --count fork/master...HEAD
```

Expected: exact divergence counts are recorded before merging; no push targets `origin`.

- [ ] **Step 2: Integrate current official upstream**

```bash
git merge --no-ff origin/master -m "merge: sync official upstream"
```

Expected: the feature branch contains official master and preserves fork-only package/signing behavior. Resolve only concrete conflicts, then run the owning tests for every upstream-touched feature boundary.

- [ ] **Step 3: Add a failing fast-release workflow contract test**

Create assertions that read `.github/workflows/darkaxt-fast-release.yml` and prove:

```kotlin
@Test
fun fastReleasePublishesFourPersistentlySignedChannels() {
    val workflow = repositoryFile(".github/workflows/darkaxt-fast-release.yml").readText()

    assertTrue(workflow.contains("EXPECTED_FORK_CERT_SHA256"))
    assertTrue(workflow.contains("compat-legacy-xr"))
    assertTrue(workflow.contains("side-by-side-legacy-xr"))
    assertTrue(workflow.contains("Verified using v2 scheme"))
    assertTrue(workflow.contains("sha256sum --check SHA256SUMS"))
    assertTrue(workflow.contains("softprops/action-gh-release"))
}
```

Use the repository-root helper from `TaggedReleaseWorkflowContractTest` rather than hard-coding an absolute path.

- [ ] **Step 4: Verify RED, then preserve the current workflow contract**

```bash
./gradlew --no-daemon --no-parallel :app:testLegacyDebugUnitTest --tests "app.gamenative.build.DarkaxtFastReleaseWorkflowContractTest"
```

Expected before creating the contract file: test selection/class is absent. Expected after adding it: PASS against the existing proven workflow without changing secrets or signing behavior.

- [ ] **Step 5: Commit and push the baseline**

```bash
git add app/src/test/java/app/gamenative/build/DarkaxtFastReleaseWorkflowContractTest.kt
git commit -m "test: pin fork release workflow contract" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD:codex/steam-normalized-game-details-spec
```

---

### Task 2: Implement pure Steam candidate policy

**Files:**
- Create: `app/src/main/java/app/gamenative/library/canonical/catalog/SteamCatalogModels.kt`
- Create: `app/src/main/java/app/gamenative/library/canonical/catalog/SteamCatalogCandidatePolicy.kt`
- Create: `app/src/test/java/app/gamenative/library/canonical/catalog/SteamCatalogCandidatePolicyTest.kt`
- Reuse: `app/src/main/java/app/gamenative/data/canonical/CanonicalNormalization.kt`

- [ ] **Step 1: Write failing policy tests**

Cover these exact outcomes:

```kotlin
@Test
fun uniqueExactCandidateWithDeveloperEvidenceAutoAccepts() {
    val result = policy.evaluate(
        source = source(title = "Example Deluxe", developer = "Studio Ltd", year = 2020),
        candidates = listOf(candidate(42, "Example Deluxe", "Studio", 2020)),
    )

    assertEquals(CatalogDecision.AutoAccept(42), result)
}

@Test
fun titleOnlyCandidateRequiresReview() {
    val result = policy.evaluate(
        source = source(title = "Example", developer = null, year = null),
        candidates = listOf(candidate(42, "Example", null, null)),
    )

    assertEquals(listOf(42), (result as CatalogDecision.ReviewRequired).steamAppIds)
}

@Test
fun editionConflictNeverAutoAccepts() {
    val result = policy.evaluate(
        source = source(title = "Example Deluxe", developer = "Studio", year = 2020),
        candidates = listOf(candidate(42, "Example", "Studio", 2020)),
    )

    assertEquals(CatalogDecision.Unmatched, result)
}

@Test
fun equallyEligibleCandidatesRequireReview() {
    val result = policy.evaluate(
        source = source(title = "Example", developer = "Studio", year = 2020),
        candidates = listOf(
            candidate(42, "Example", "Studio", 2020),
            candidate(84, "Example", "Studio", 2020),
        ),
    )

    assertEquals(listOf(42, 84), (result as CatalogDecision.ReviewRequired).steamAppIds)
}
```

Also cover unknown/incompatible type, developer conflict, year gap greater than one, empty result, duplicate AppID removal, and stable AppID ordering.

- [ ] **Step 2: Run tests and verify RED**

```bash
./gradlew --no-daemon --no-parallel :app:testLegacyDebugUnitTest --tests "app.gamenative.library.canonical.catalog.SteamCatalogCandidatePolicyTest"
```

Expected: compilation fails because catalog policy types do not exist.

- [ ] **Step 3: Add the exact catalog types**

```kotlin
data class SourceCatalogEvidence(
    val title: String,
    val developer: String?,
    val releaseYear: Int?,
    val appType: CanonicalAppType,
)

data class SteamCatalogCandidate(
    val steamAppId: Int,
    val title: String,
    val developer: String?,
    val releaseYear: Int?,
    val appType: CanonicalAppType,
    val headerImageUrl: String?,
)

sealed interface CatalogDecision {
    data class AutoAccept(val steamAppId: Int) : CatalogDecision
    data class ReviewRequired(val steamAppIds: List<Int>) : CatalogDecision
    data object Unmatched : CatalogDecision
}
```

Require positive AppIDs when candidates are created.

- [ ] **Step 4: Implement the strict policy**

The implementation must:

```kotlin
class SteamCatalogCandidatePolicy {
    fun evaluate(
        source: SourceCatalogEvidence,
        candidates: List<SteamCatalogCandidate>,
    ): CatalogDecision {
        val sourceTitleKey = CanonicalNormalization.titleKey(source.title)
        if (sourceTitleKey.isEmpty() || source.appType == CanonicalAppType.UNKNOWN) {
            return CatalogDecision.Unmatched
        }
        val sourceDeveloperKey = source.developer
            ?.let(CanonicalNormalization::developerKey)
            .orEmpty()
        val eligible = candidates
            .distinctBy(SteamCatalogCandidate::steamAppId)
            .filter { candidate ->
                candidate.appType == source.appType &&
                    CanonicalNormalization.titleKey(candidate.title) == sourceTitleKey
            }
            .sortedBy(SteamCatalogCandidate::steamAppId)
        if (eligible.isEmpty()) return CatalogDecision.Unmatched

        val corroborated = eligible.filter { candidate ->
            val candidateDeveloperKey = candidate.developer
                ?.let(CanonicalNormalization::developerKey)
                .orEmpty()
            val developerMatches = sourceDeveloperKey.isNotEmpty() &&
                candidateDeveloperKey == sourceDeveloperKey
            val yearMatches = source.releaseYear != null &&
                candidate.releaseYear != null &&
                kotlin.math.abs(source.releaseYear - candidate.releaseYear) <= 1
            val developerConflicts = sourceDeveloperKey.isNotEmpty() &&
                candidateDeveloperKey.isNotEmpty() &&
                sourceDeveloperKey != candidateDeveloperKey
            val yearConflicts = source.releaseYear != null &&
                candidate.releaseYear != null &&
                kotlin.math.abs(source.releaseYear - candidate.releaseYear) > 1
            !developerConflicts && !yearConflicts && (developerMatches || yearMatches)
        }
        return when {
            corroborated.size == 1 -> CatalogDecision.AutoAccept(corroborated.single().steamAppId)
            corroborated.size > 1 -> CatalogDecision.ReviewRequired(
                corroborated.map(SteamCatalogCandidate::steamAppId),
            )
            else -> CatalogDecision.ReviewRequired(
                eligible.map(SteamCatalogCandidate::steamAppId),
            )
        }
    }
}
```

- [ ] **Step 5: Verify GREEN and commit**

```bash
./gradlew --no-daemon --no-parallel :app:testLegacyDebugUnitTest --tests "app.gamenative.library.canonical.catalog.SteamCatalogCandidatePolicyTest"
git add app/src/main/java/app/gamenative/library/canonical/catalog app/src/test/java/app/gamenative/library/canonical/catalog/SteamCatalogCandidatePolicyTest.kt
git commit -m "feat: define Steam catalog match policy" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD:codex/steam-normalized-game-details-spec
```

---

### Task 3: Add bounded Steam Store search and candidate metadata

**Files:**
- Create: `app/src/main/java/app/gamenative/library/canonical/catalog/SteamCatalogSearchProvider.kt`
- Create: `app/src/test/java/app/gamenative/library/canonical/catalog/SteamCatalogSearchProviderTest.kt`
- Modify: `app/src/main/java/app/gamenative/library/metadata/SteamCatalogProvider.kt`
- Modify: `app/src/main/java/app/gamenative/library/metadata/GameMetadataModels.kt`
- Modify: `app/src/test/java/app/gamenative/library/metadata/SteamCatalogProviderTest.kt`
- Create fixture: `app/src/test/resources/steam/store-search.json`

- [ ] **Step 1: Write failing transport tests**

Tests must prove:

- locale/country are encoded with `HttpUrl.Builder`;
- only HTTPS `store.steampowered.com:443/api/storesearch/` requests pass;
- every redirect and final effective URL is revalidated;
- cross-host, credentialed, fragment, wrong-path, excess-hop, oversized, malformed, duplicate, nonpositive-ID, and cancellation cases fail closed;
- exception messages contain a fixed string only;
- response/request text is never logged;
- at most ten candidates survive parsing.

Use `MockWebServer` and assert request path/query directly. Do not assert full URLs in diagnostic output.

- [ ] **Step 2: Verify RED**

```bash
./gradlew --no-daemon --no-parallel :app:testLegacyDebugUnitTest --tests "app.gamenative.library.canonical.catalog.SteamCatalogSearchProviderTest"
```

Expected: provider types are unresolved.

- [ ] **Step 3: Implement the search source contract**

```kotlin
fun interface SteamCatalogSearchSource {
    suspend fun search(
        query: String,
        locale: MetadataLocale,
    ): List<SteamStoreSearchHit>
}

data class SteamStoreSearchHit(
    val steamAppId: Int,
    val title: String,
    val headerImageUrl: String?,
)
```

Reject blank queries, trim only in memory, cap query length at 256 Unicode code points, encode only `term`, `cc`, and `l`, parse at most ten results, and use a dedicated no-cookie/no-cache client with automatic redirects disabled.

- [ ] **Step 4: Extend appdetails with candidate evidence without breaking metadata callers**

Add:

```kotlin
data class SteamCatalogRecord(
    val steamAppId: Int,
    val appType: CanonicalAppType,
    val releaseYear: Int?,
    val metadata: CanonicalGameMetadata,
)

interface SteamCatalogRecordSource {
    suspend fun fetchRecord(
        trustedSteamAppId: Int,
        locale: MetadataLocale,
    ): SteamCatalogRecord?
}
```

Make `SteamCatalogProvider` implement both `SteamCatalogDataSource` and `SteamCatalogRecordSource`. Existing `fetch()` returns `fetchRecord()?.metadata`. Parse `data.type` into the exact `CanonicalAppType` enum and extract one supported four-digit year from the sanitized release-date field. Preserve all existing media and redirect tests.

- [ ] **Step 5: Verify providers and commit**

```bash
./gradlew --no-daemon --no-parallel :app:testLegacyDebugUnitTest --tests "app.gamenative.library.canonical.catalog.SteamCatalogSearchProviderTest" --tests "app.gamenative.library.metadata.SteamCatalogProviderTest"
git add app/src/main/java/app/gamenative/library/canonical/catalog/SteamCatalogSearchProvider.kt app/src/main/java/app/gamenative/library/metadata app/src/test/java/app/gamenative/library/canonical/catalog/SteamCatalogSearchProviderTest.kt app/src/test/java/app/gamenative/library/metadata/SteamCatalogProviderTest.kt app/src/test/resources/steam/store-search.json
git commit -m "feat: search and validate Steam catalog candidates" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD:codex/steam-normalized-game-details-spec
```

---

### Task 4: Add guarded catalog decisions and foreground resolution

**Files:**
- Create: `app/src/main/java/app/gamenative/library/canonical/catalog/SteamCatalogResolutionRepository.kt`
- Create: `app/src/main/java/app/gamenative/library/canonical/catalog/SteamCatalogResolutionDiagnostics.kt`
- Modify: `app/src/main/java/app/gamenative/library/canonical/CanonicalMutationRepository.kt`
- Modify: `app/src/main/java/app/gamenative/db/dao/StoreMatchDao.kt`
- Modify: `app/src/main/java/app/gamenative/di/CanonicalLibraryModule.kt`
- Test: `app/src/test/java/app/gamenative/library/canonical/CanonicalMutationRepositoryTest.kt`
- Create: `app/src/test/java/app/gamenative/library/canonical/catalog/SteamCatalogResolutionRepositoryTest.kt`

- [ ] **Step 1: Write failing guarded-mutation tests**

Prove:

- automatic acceptance writes `AUTOMATIC`/`HIGH` and assigns or merges the requested Steam identity;
- manual confirmation writes `USER`/`VERIFIED`/`MANUAL`;
- candidate recording writes `REVIEW_REQUIRED` without changing canonical Steam identity;
- rejection is sticky and Reset makes it eligible again;
- absent copy, changed canonical, changed decision revision, direct Steam key, stale candidate, and target-type conflict return `EXPECTED_STATE_CHANGED` with no write;
- merge moves snapshots/facets/preferences exactly once and never changes `OwnedCopyKey`.

- [ ] **Step 2: Add one expected-state value object**

```kotlin
data class ExpectedMatchState(
    val key: OwnedCopyKey,
    val canonicalId: String,
    val matchMethod: MatchMethod,
    val confidence: MatchConfidence,
    val decisionSource: MatchDecisionSource,
    val candidateSteamAppId: Int?,
    val resolverVersion: Int,
    val decisionRevision: Long,
)
```

Use it in new guarded APIs instead of another long primitive argument list:

```kotlin
suspend fun guardedRecordCandidate(
    expected: ExpectedMatchState,
    steamAppId: Int,
    resolverVersion: Int,
    nowEpochMs: Long,
): CanonicalGuardedMutationResult

suspend fun guardedAcceptAutomaticSteamMatch(
    expected: ExpectedMatchState,
    steamAppId: Int,
    resolverVersion: Int,
    nowEpochMs: Long,
): CanonicalGuardedMutationResult

suspend fun guardedConfirmSteamMatch(
    expected: ExpectedMatchState,
    steamAppId: Int,
    nowEpochMs: Long,
): CanonicalGuardedMutationResult

suspend fun guardedRejectSteamMatch(
    expected: ExpectedMatchState,
    steamAppId: Int,
    nowEpochMs: Long,
): CanonicalGuardedMutationResult
```

All implementations call `requireMutableMatch`, re-read the exact row inside `db.withTransaction`, compare every expected field and `isPresent`, then mutate. Do not call the existing unguarded public methods from UI.

- [ ] **Step 3: Verify guarded mutation GREEN**

```bash
./gradlew --no-daemon --no-parallel :app:testLegacyDebugUnitTest --tests "app.gamenative.library.canonical.CanonicalMutationRepositoryTest"
```

- [ ] **Step 4: Write failing resolution repository tests**

Use fakes to prove:

- one search per canonical, not per copy;
- strongest present evidence wins;
- local/sticky/direct matches are skipped;
- concurrency never exceeds two;
- at most five plausible hits call `fetchRecord`;
- one failure increments failed count and does not cancel siblings;
- cancellation propagates;
- accepted/review/unmatched outcomes are fixed categories;
- manual search ignores scan cooldown;
- no title/query/AppID/URL enters diagnostics.

- [ ] **Step 5: Implement repository state and progress**

```kotlin
data class SteamResolutionProgress(
    val completed: Int,
    val total: Int,
    val failed: Int,
    val autoAccepted: Int,
    val needsReview: Int,
    val unmatched: Int,
)

sealed interface SteamResolutionItemResult {
    data object AutoAccepted : SteamResolutionItemResult
    data object ReviewRequired : SteamResolutionItemResult
    data object Unmatched : SteamResolutionItemResult
    data object ExpectedStateChanged : SteamResolutionItemResult
    data object ProviderUnavailable : SteamResolutionItemResult
}
```

The repository receives canonical IDs, loads current match evidence from `StoreMatchDao`, searches/fetches outside Room, applies `SteamCatalogCandidatePolicy`, then performs one guarded mutation. Keep process-session candidate lists in memory. Persist only the accepted/rejected/store-match decision and fixed global scan timestamp/version; do not persist search text or candidate titles.

- [ ] **Step 6: Verify, commit, and push**

```bash
./gradlew --no-daemon --no-parallel :app:testLegacyDebugUnitTest --tests "app.gamenative.library.canonical.CanonicalMutationRepositoryTest" --tests "app.gamenative.library.canonical.catalog.SteamCatalogResolutionRepositoryTest"
git add app/src/main/java/app/gamenative/library/canonical app/src/main/java/app/gamenative/db/dao/StoreMatchDao.kt app/src/main/java/app/gamenative/di/CanonicalLibraryModule.kt app/src/test/java/app/gamenative/library/canonical
git commit -m "feat: resolve Steam catalog identities safely" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD:codex/steam-normalized-game-details-spec
```

---

### Task 5: Enrich accepted non-owned Steam identities

**Files:**
- Create: `app/src/main/java/app/gamenative/library/canonical/catalog/SteamAcceptedIdentityEnricher.kt`
- Create: `app/src/main/java/app/gamenative/library/canonical/catalog/SteamPublicPicsFacetSource.kt`
- Modify: `app/src/main/java/app/gamenative/service/SteamService.kt`
- Modify: `app/src/main/java/app/gamenative/library/metadata/GameMetadataRepository.kt`
- Modify: `app/src/main/java/app/gamenative/library/discovery/GameFacetRepository.kt`
- Modify: `app/src/main/java/app/gamenative/library/discovery/SteamPopularityEnricher.kt`
- Create: `app/src/test/java/app/gamenative/library/canonical/catalog/SteamAcceptedIdentityEnricherTest.kt`

- [ ] **Step 1: Write failing handoff tests**

Prove that accepting a trusted AppID:

- stores the already-sanitized appdetails snapshot;
- preserves Steam-derived genres/features across the next non-Steam source projection;
- submits review-count enrichment for a GOG/Epic/Amazon-only canonical;
- requests public PICS facets only with an active Steam session;
- stores canonical PICS genre/category/tag associations without creating an owned Steam copy;
- treats unavailable PICS as success-with-unknown-tags;
- never inserts the result as an actionable Steam entitlement.

- [ ] **Step 2: Add the narrow PICS façade**

```kotlin
data class SteamPublicPicsFacets(
    val genreIds: Set<Int>,
    val categoryIds: Set<Int>,
    val storeTagIds: Set<Int>,
)

fun interface SteamPublicPicsFacetSource {
    suspend fun fetch(trustedSteamAppId: Int): SteamPublicPicsFacets?
}
```

Implement it inside the authenticated Steam service boundary with a direct cancellable `picsGetProductInfo(PICSRequest(id = trustedSteamAppId))`. Verify the response contains the requested AppID, parse with existing KeyValue utilities, return only facets, and bypass the global owned-app PICS insertion channel.

- [ ] **Step 3: Implement accepted-identity enrichment**

The enricher receives canonical ID, trusted AppID, locale, and validated catalog record. It persists metadata first, updates provider-aware facets, requests optional PICS facets, then queues review-count enrichment. Catch provider-specific fixed failures independently; propagate cancellation.

- [ ] **Step 4: Verify and commit**

```bash
./gradlew --no-daemon --no-parallel :app:testLegacyDebugUnitTest --tests "app.gamenative.library.canonical.catalog.SteamAcceptedIdentityEnricherTest" --tests "app.gamenative.library.discovery.SteamPopularityEnricherTest" --tests "app.gamenative.library.metadata.GameMetadataRepositoryTest"
git add app/src/main/java/app/gamenative/library/canonical/catalog app/src/main/java/app/gamenative/service/SteamService.kt app/src/main/java/app/gamenative/library/metadata app/src/main/java/app/gamenative/library/discovery app/src/test/java/app/gamenative/library/canonical/catalog/SteamAcceptedIdentityEnricherTest.kt
git commit -m "feat: enrich matched non-Steam games" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD:codex/steam-normalized-game-details-spec
```

---

### Task 6: Add resolution progress and Fix Steam match UI

**Files:**
- Create: `app/src/main/java/app/gamenative/ui/model/SteamMatchViewModel.kt`
- Create: `app/src/main/java/app/gamenative/ui/screen/library/components/SteamMatchPicker.kt`
- Create: `app/src/main/java/app/gamenative/ui/screen/library/components/SteamResolutionStatus.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/LibraryScreen.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/CanonicalGameDetailScreen.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/components/CanonicalCopiesSheet.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryOptionsPanel.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/test/java/app/gamenative/ui/model/SteamMatchViewModelTest.kt`
- Modify: canonical Compose tests.

- [ ] **Step 1: Write failing ViewModel tests**

Cover:

- first eligible library observation starts one scan;
- progress survives tab changes but not process recreation;
- opening picker preloads source title in memory;
- changing query cancels the prior manual search;
- positive numeric query performs direct AppID validation;
- confirm/reject/reset use the exact `ExpectedMatchState` captured for the chosen copy;
- stale mutation closes with a fixed refresh message;
- close clears query/candidates and cancels work;
- no query/candidate title enters saved state or diagnostics.

- [ ] **Step 2: Define picker state**

```kotlin
sealed interface SteamMatchPickerState {
    data object Closed : SteamMatchPickerState
    data class Searching(val expected: ExpectedMatchState) : SteamMatchPickerState
    data class Results(
        val expected: ExpectedMatchState,
        val candidates: List<SteamCatalogCandidate>,
        val selectedSteamAppId: Int?,
    ) : SteamMatchPickerState
    data class Empty(val expected: ExpectedMatchState) : SteamMatchPickerState
    data class Unavailable(val expected: ExpectedMatchState) : SteamMatchPickerState
}
```

Keep the query in `MutableStateFlow` owned by the active ViewModel session and clear it on close. Do not put it in `SavedStateHandle`, DataStore, `rememberSaveable`, diagnostics, or navigation arguments.

- [ ] **Step 3: Build native correction UI**

`SteamMatchPicker` must expose:

- in-memory search field;
- candidate artwork/title/developer/year/type;
- selected/current state;
- Confirm;
- Keep separate;
- Reset to automatic;
- Retry and Cancel;
- deterministic gamepad focus and Back behavior.

Place a top-level provenance/Fix action in canonical Details. If several mutable non-Steam copies exist, open the Copies sheet and choose the row first. Direct Steam rows remain immutable.

- [ ] **Step 4: Add source-agnostic progress UI**

The Options panel shows resolved/eligible, review-required, unmatched, completed/total/failed, Review matches, and Retry regardless of current source tab. Add a regression test with a GOG-only trusted AppID and review count passing the threshold in All and GOG tabs.

- [ ] **Step 5: Restore exactly four detail tabs**

Remove `RESOURCES` from `CanonicalDetailTab`; render resource links as a Details section. Update the Compose test to assert exactly Overview, Reviews, Discussions, and Details.

- [ ] **Step 6: Verify production/Android-test compilation and commit**

```bash
./gradlew --no-daemon --no-parallel :app:testLegacyDebugUnitTest --tests "app.gamenative.ui.model.SteamMatchViewModelTest" --tests "app.gamenative.library.discovery.CanonicalDiscoveryFilterTest"
./gradlew --no-daemon --no-parallel :app:testModernDebugUnitTest --tests "app.gamenative.ui.model.SteamMatchViewModelTest" --tests "app.gamenative.library.discovery.CanonicalDiscoveryFilterTest"
./gradlew --no-daemon --no-parallel :app:compileLegacyDebugAndroidTestKotlin
git add app/src/main/java/app/gamenative/ui app/src/main/res/values/strings.xml app/src/test/java/app/gamenative/ui app/src/test/java/app/gamenative/library/discovery app/src/androidTest/java/app/gamenative/ui/screen/library
git commit -m "feat: add automatic and manual Steam matching" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD:codex/steam-normalized-game-details-spec
```

---

### Task 7: Cross-check, correct once, and publish the resolver RC

**Files:**
- Create: `docs/superpowers/reviews/2026-08-08-steam-catalog-resolution-cross-check.md`
- Modify: `app/build.gradle.kts`
- Modify: `.github/workflows/darkaxt-fast-release.yml`
- Modify: `.github/workflows/tagged-release.yml`
- Modify: release workflow contract tests.

- [ ] **Step 1: Run resolver owning tests once in each variant**

```bash
./gradlew --no-daemon --no-parallel :app:testLegacyDebugUnitTest --tests "app.gamenative.library.canonical.catalog.*" --tests "app.gamenative.library.canonical.CanonicalMutationRepositoryTest" --tests "app.gamenative.library.canonical.action.*" --tests "app.gamenative.ui.model.SteamMatchViewModelTest" --tests "app.gamenative.library.discovery.CanonicalDiscoveryFilterTest" --tests "app.gamenative.diagnostics.*"
./gradlew --no-daemon --no-parallel :app:testModernDebugUnitTest --tests "app.gamenative.library.canonical.catalog.*" --tests "app.gamenative.library.canonical.CanonicalMutationRepositoryTest" --tests "app.gamenative.library.canonical.action.*" --tests "app.gamenative.ui.model.SteamMatchViewModelTest" --tests "app.gamenative.library.discovery.CanonicalDiscoveryFilterTest" --tests "app.gamenative.diagnostics.*"
./gradlew --no-daemon --no-parallel :app:compileLegacyDebugAndroidTestKotlin
```

- [ ] **Step 2: Perform one design cross-check**

Check design Sections 5–6, 10–13, and Steam-resolution acceptance criteria 1–11. Record only deterministic evidence. Classify every discrepancy. Batch confirmed Critical/High fixes into one correction commit; do not launch a second review.

- [ ] **Step 3: Prepare the next unused release identity**

If RC5 remains unused, set:

```kotlin
versionCode = 31
versionName = "1.1.3-rc5"
```

Set both release workflows to expected code `31` and name `1.1.3-rc5`; update workflow contract tests.

- [ ] **Step 4: Sync upstream and publish**

```bash
git fetch origin master
git merge --no-ff origin/master -m "merge: sync official upstream"
./gradlew --no-daemon --no-parallel :app:testLegacyDebugUnitTest --tests "app.gamenative.build.*WorkflowContractTest"
git add docs/superpowers/reviews/2026-08-08-steam-catalog-resolution-cross-check.md app/build.gradle.kts .github/workflows app/src/test/java/app/gamenative/build
git commit -m "chore: prepare Steam resolution RC" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD:codex/steam-normalized-game-details-spec
git fetch fork master
git merge-base --is-ancestor fork/master HEAD
git push fork HEAD:master
git tag -a v1.1.3-rc5 -m "GameNative 1.1.3 RC5"
git push fork refs/tags/v1.1.3-rc5
gh workflow run darkaxt-fast-release.yml --repo Darkaxt/GameNative --ref master -f release_tag=v1.1.3-rc5
```

If RC5 is occupied, substitute the next unused increasing code/name/tag consistently.

- [ ] **Step 5: Verify publication**

Verify four APKs plus `SHA256SUMS`, exact package/channel mapping, expected version, v2 signature, checksums, and signer digest `90d491f4c194d4f6e9efaf2ba1a548e59388edd9ecbd96853d330fe6a9c260c9`. No adb is required.

---

### Task 8: Add no-store community transport and native review provider

**Files:**
- Create: `app/src/main/java/app/gamenative/library/community/SteamCommunityModels.kt`
- Create: `app/src/main/java/app/gamenative/library/community/SteamCommunityUrlPolicy.kt`
- Create: `app/src/main/java/app/gamenative/library/community/SteamCommunityTransport.kt`
- Create: `app/src/main/java/app/gamenative/library/community/SteamReviewPageProvider.kt`
- Create: `app/src/main/java/app/gamenative/library/community/SteamCommunityDiagnostics.kt`
- Create tests and `app/src/test/resources/steam/reviews-page.json`.
- Modify: `SteamReviewSummaryProvider.kt` to enforce no-store/no-cache.

- [ ] **Step 1: Write failing model/provider/privacy tests**

Prove Helpful/Recent, All/Positive/Negative, App language/All, All purchases/Steam purchases, opaque cursor encoding, pagination, malformed/oversized JSON, redirect rejection, cancellation, text/page bounds, fixed error messages, SteamID discard, and zero disk cache/cookies.

Seed synthetic forbidden title/query/URL/SteamID/username/review text into diagnostic tests and assert none survives export.

- [ ] **Step 2: Add exact review models**

```kotlin
enum class SteamReviewSort { HELPFUL, RECENT }
enum class SteamReviewPolarity { ALL, POSITIVE, NEGATIVE }
enum class SteamReviewLanguage { APP_LANGUAGE, ALL }
enum class SteamReviewPurchaseType { ALL, STEAM }

data class SteamReviewQuery(
    val sort: SteamReviewSort = SteamReviewSort.HELPFUL,
    val polarity: SteamReviewPolarity = SteamReviewPolarity.ALL,
    val language: SteamReviewLanguage = SteamReviewLanguage.APP_LANGUAGE,
    val purchaseType: SteamReviewPurchaseType = SteamReviewPurchaseType.ALL,
)

data class SteamReviewCard(
    val recommended: Boolean,
    val text: String,
    val playtimeMinutes: Int?,
    val helpfulVotes: Int,
    val funnyVotes: Int,
    val commentCount: Int,
    val postedAtEpochSeconds: Long,
    val updatedAtEpochSeconds: Long,
    val receivedForFree: Boolean,
    val earlyAccess: Boolean,
    val developerResponse: String?,
)

data class SteamReviewPage(
    val reviews: List<SteamReviewCard>,
    val nextCursor: String?,
)
```

Do not add serialization annotations to domain models.

- [ ] **Step 3: Implement endpoint-bound no-store transport**

Build the client with `cache(null)`, `CookieJar.NO_COOKIES`, disabled redirects, provider timeouts, and `Cache-Control: no-store`. Bind AppReviews requests to the trusted AppID path. Cap each page at 20 reviews, one MiB response, 16 KiB review text, 8 KiB developer response, and a 512-byte opaque cursor.

- [ ] **Step 4: Verify providers and commit**

```bash
./gradlew --no-daemon --no-parallel :app:testLegacyDebugUnitTest --tests "app.gamenative.library.community.*" --tests "app.gamenative.library.discovery.SteamReviewSummaryProviderTest" --tests "app.gamenative.diagnostics.*"
git add app/src/main/java/app/gamenative/library/community app/src/main/java/app/gamenative/library/discovery/SteamReviewSummaryProvider.kt app/src/test/java/app/gamenative/library/community app/src/test/resources/steam/reviews-page.json
git commit -m "feat: add private native Steam review transport" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD:codex/steam-normalized-game-details-spec
```

---

### Task 9: Render native Reviews in the existing detail shell

**Files:**
- Create: `app/src/main/java/app/gamenative/library/community/InMemorySteamCommunityRepository.kt`
- Create: `app/src/main/java/app/gamenative/ui/screen/library/components/SteamReviewsTab.kt`
- Modify: `app/src/main/java/app/gamenative/ui/model/GameDetailViewModel.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/CanonicalGameDetailScreen.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/LibraryScreen.kt`
- Modify strings and focused tests.

- [ ] **Step 1: Write failing repository/ViewModel tests**

Cover independent Loading/Content/LoadingMore/Empty/Offline/Unavailable states, filter-change cancellation, cursor deduplication, five-page/100-card bound, failed-refresh content retention, canonical-change clearing, detail-close clearing, and no process recreation.

- [ ] **Step 2: Add section state**

```kotlin
sealed interface ReviewSectionState {
    data object Idle : ReviewSectionState
    data object Loading : ReviewSectionState
    data class Content(
        val reviews: List<SteamReviewCard>,
        val canLoadMore: Boolean,
        val loadingMore: Boolean,
    ) : ReviewSectionState
    data object Empty : ReviewSectionState
    data object Offline : ReviewSectionState
    data object Unavailable : ReviewSectionState
}
```

`GameDetailViewModel` owns review query/state and active calls. Add `clearDetail()` that cancels community work and clears body content; call it whenever canonical detail closes or changes.

- [ ] **Step 3: Build Reviews UI**

Render fixed filters, summary, native plain-text cards, Refresh, Load more, and Open Steam Reviews. Use `Steam user`; render no avatar/profile link. Add focus traversal and semantics for loading/empty/offline/error.

- [ ] **Step 4: Verify and commit**

```bash
./gradlew --no-daemon --no-parallel :app:testLegacyDebugUnitTest --tests "app.gamenative.library.community.*" --tests "app.gamenative.ui.model.GameDetailViewModelTest"
./gradlew --no-daemon --no-parallel :app:testModernDebugUnitTest --tests "app.gamenative.library.community.*" --tests "app.gamenative.ui.model.GameDetailViewModelTest"
./gradlew --no-daemon --no-parallel :app:compileLegacyDebugAndroidTestKotlin
git add app/src/main/java/app/gamenative/library/community app/src/main/java/app/gamenative/ui app/src/main/res/values/strings.xml app/src/test/java/app/gamenative app/src/androidTest/java/app/gamenative/ui/screen/library
git commit -m "feat: browse Steam reviews natively" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD:codex/steam-normalized-game-details-spec
```

---

### Task 10: Cross-check, correct once, and publish the Reviews RC

**Files:**
- Create: `docs/superpowers/reviews/2026-08-08-native-reviews-cross-check.md`
- Modify release version/workflows/tests.

- [ ] **Step 1: Run Reviews owning tests once per variant**

```bash
./gradlew --no-daemon --no-parallel :app:testLegacyDebugUnitTest --tests "app.gamenative.library.community.*" --tests "app.gamenative.ui.model.GameDetailViewModelTest" --tests "app.gamenative.library.discovery.SteamReviewSummaryProviderTest" --tests "app.gamenative.diagnostics.*"
./gradlew --no-daemon --no-parallel :app:testModernDebugUnitTest --tests "app.gamenative.library.community.*" --tests "app.gamenative.ui.model.GameDetailViewModelTest" --tests "app.gamenative.library.discovery.SteamReviewSummaryProviderTest" --tests "app.gamenative.diagnostics.*"
./gradlew --no-daemon --no-parallel :app:compileLegacyDebugAndroidTestKotlin
```

- [ ] **Step 2: Cross-check design Sections 5, 7, 9–13 and criteria 11–14**

Apply the one-pass classification/correction rule. Review-body/identity persistence, unsafe endpoint/query mapping, unbounded content, cursor loop, or a Reviews failure blanking another section is a release blocker.

- [ ] **Step 3: Set the next unused release version and publish**

Use code 33/name/tag RC7. Repeat Task 7's upstream sync, workflow-contract test, feature-branch push, fork-master fast-forward, immutable annotated tag, parallel workflow dispatch, four-APK/checksum/signature verification. Commit message:

```bash
git commit -m "chore: prepare native Reviews RC" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 11: Add safe public discussion parsing and transport

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `THIRD_PARTY_NOTICES`
- Create: `app/src/main/java/app/gamenative/library/community/SteamDiscussionProvider.kt`
- Create: `app/src/main/java/app/gamenative/library/community/SteamDiscussionParser.kt`
- Create tests and synthetic fixtures under `app/src/test/resources/steam/community/`.

- [ ] **Step 1: Write failing URL/parser tests**

Prove exact host/port/listing path, same-AppID thread path, cross-AppID redirect rejection, no cookies/auth, body/text/count bounds, redirect loop handling, cancellation, malformed/missing selectors, sanitized plain text, fixed author label, and external fallback route retention.

Fixtures must be synthetic and contain no copied private library/account data.

- [ ] **Step 2: Add pinned Jsoup dependency and notice**

```toml
jsoup = "1.23.1"
jsoup = { module = "org.jsoup:jsoup", version.ref = "jsoup" }
```

```kotlin
implementation(libs.jsoup)
```

Record Jsoup's name, version, project URL, and MIT license in the existing third-party notice format.

- [ ] **Step 3: Define discussion models**

```kotlin
data class SteamDiscussionSummary(
    val threadRoute: String,
    val title: String,
    val replyCount: Int?,
    val viewCount: Int?,
    val lastActivity: String?,
)

data class SteamDiscussionPost(
    val text: String,
    val postedAt: String?,
)

data class SteamDiscussionThread(
    val title: String,
    val posts: List<SteamDiscussionPost>,
    val nextPageRoute: String?,
)
```

Routes are validated relative routes, not full URLs. No model stores usernames, SteamIDs, raw HTML, or arbitrary anchors.

- [ ] **Step 4: Implement fixture-bound parsing**

Use Jsoup selectors proved by fixtures. Cap listings/posts at 50, HTML at one MiB, title at 512 characters, and each post at 32 KiB. Unsupported layouts return a fixed unavailable result, not partial unsafe HTML.

- [ ] **Step 5: Verify and commit**

```bash
./gradlew --no-daemon --no-parallel :app:testLegacyDebugUnitTest --tests "app.gamenative.library.community.SteamDiscussion*Test"
git add gradle/libs.versions.toml app/build.gradle.kts THIRD_PARTY_NOTICES app/src/main/java/app/gamenative/library/community app/src/test/java/app/gamenative/library/community app/src/test/resources/steam/community
git commit -m "feat: parse public Steam discussions safely" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD:codex/steam-normalized-game-details-spec
```

---

### Task 12: Render native discussion lists and threads

**Files:**
- Create: `app/src/main/java/app/gamenative/ui/screen/library/components/SteamDiscussionsTab.kt`
- Modify: `InMemorySteamCommunityRepository.kt`
- Modify: `GameDetailViewModel.kt`
- Modify: `CanonicalGameDetailScreen.kt`
- Modify strings and focused tests.

- [ ] **Step 1: Write failing state/navigation tests**

Cover listing load, supported category selection, thread open, Back-to-list, detail Back, page append/deduplication, independent listing/thread failures, current-content retention, clear-on-close, parser-unavailable external fallback, and no persistence.

- [ ] **Step 2: Add discussion section state**

```kotlin
sealed interface DiscussionSectionState {
    data object Idle : DiscussionSectionState
    data object Loading : DiscussionSectionState
    data class Listing(
        val threads: List<SteamDiscussionSummary>,
        val canLoadMore: Boolean,
    ) : DiscussionSectionState
    data class Thread(
        val value: SteamDiscussionThread,
        val loadingMore: Boolean,
    ) : DiscussionSectionState
    data object Empty : DiscussionSectionState
    data object Offline : DiscussionSectionState
    data object Unavailable : DiscussionSectionState
}
```

- [ ] **Step 3: Build Discussions UI**

Render native list/thread plain text, supported categories, Refresh/Load more, deterministic focus, thread Back, and always-visible Open Community/Open Thread. No WebView, copied cookie, avatar, arbitrary link, posting, reply, vote, moderation, or report action is native.

- [ ] **Step 4: Verify and commit**

```bash
./gradlew --no-daemon --no-parallel :app:testLegacyDebugUnitTest --tests "app.gamenative.library.community.*" --tests "app.gamenative.ui.model.GameDetailViewModelTest"
./gradlew --no-daemon --no-parallel :app:testModernDebugUnitTest --tests "app.gamenative.library.community.*" --tests "app.gamenative.ui.model.GameDetailViewModelTest"
./gradlew --no-daemon --no-parallel :app:compileLegacyDebugAndroidTestKotlin
git add app/src/main/java/app/gamenative/library/community app/src/main/java/app/gamenative/ui app/src/main/res/values/strings.xml app/src/test/java/app/gamenative app/src/androidTest/java/app/gamenative/ui/screen/library
git commit -m "feat: browse Steam discussions natively" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD:codex/steam-normalized-game-details-spec
```

---

### Task 13: Cross-check, correct once, and publish the Discussions RC

**Files:**
- Create: `docs/superpowers/reviews/2026-08-08-native-discussions-cross-check.md`
- Modify release version/workflows/tests.

- [ ] **Step 1: Run Discussions owning tests once per variant**

```bash
./gradlew --no-daemon --no-parallel :app:testLegacyDebugUnitTest --tests "app.gamenative.library.community.*" --tests "app.gamenative.ui.model.GameDetailViewModelTest" --tests "app.gamenative.diagnostics.*"
./gradlew --no-daemon --no-parallel :app:testModernDebugUnitTest --tests "app.gamenative.library.community.*" --tests "app.gamenative.ui.model.GameDetailViewModelTest" --tests "app.gamenative.diagnostics.*"
./gradlew --no-daemon --no-parallel :app:compileLegacyDebugAndroidTestKotlin
```

- [ ] **Step 2: Cross-check design Sections 5, 8–13 and criteria 15–18**

Apply the one-pass classification/correction rule. Authenticated scraping/cookies, unsafe redirects, cross-AppID threads, raw HTML execution, body/identity persistence, missing external fallback, or Discussions breaking Reviews/other sections is a blocker.

- [ ] **Step 3: Set the next unused release version and publish**

Use code 34/name/tag RC8. Repeat Task 7's full upstream-sync and publication procedure. Commit message:

```bash
git commit -m "chore: prepare native Discussions RC" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 14: Reconcile the completion ledger after the three core RCs

**Files:**
- Create: `docs/superpowers/reviews/2026-08-08-steam-visible-core-final-cross-check.md`
- Modify production/test files only for failures attributable to Tasks 2–13.

- [ ] **Step 1: Collect complaint-driven evidence**

Record visible symptoms and fixed outcome/count categories only. Never commit titles, AppIDs, queries, usernames, SteamIDs, paths, full URLs, screenshots of private libraries, review bodies, discussion bodies, or raw exported reports.

- [ ] **Step 2: Run one aggregate focused matrix**

Run the owning test classes from Tasks 2–13 in Legacy and Modern, migration/schema export checks, workflow contracts, and Android-test compilation. Run instrumentation only on an explicitly claimed separate AVD and serial when source tests cannot prove the behavior. Never touch `emulator-5554`.

- [ ] **Step 3: Run broad baselines once**

```bash
./gradlew --no-daemon --no-parallel :app:testLegacyDebugUnitTest --continue
./gradlew --no-daemon --no-parallel :app:testModernDebugUnitTest --continue
./gradlew --no-daemon --no-parallel :app:lintLegacyDebug
```

Compare exact failures with the recorded inherited baseline. Do not loop broad commands. New attributable Critical/High failures block the final core RC; inherited failures are reported without weakening invariants.

- [ ] **Step 4: Make one final core correction and ledger decision**

Fix current Critical/High defects involving:

- active visible-path failures;
- false identity merges or stale mutation;
- source-action retargeting;
- unsafe URL/content behavior;
- privacy/persistence leakage;
- unbounded memory/network behavior;
- Reviews/Discussions failure coupling.

Then update every row in design Section 15 with observed status, target task, owner, and completion evidence. Tasks 15–19 own resolver durability/global coverage, detail parity, community completion, storage hardening, LSFG disposition, aggregate release, and upstream handoff. No row may be removed or left with a generic deferred status.

- [ ] **Step 5: Commit and push the final evidence**

```bash
git add docs/superpowers/reviews/2026-08-08-steam-visible-core-final-cross-check.md
git commit -m "docs: cross-check Steam visible core" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD:codex/steam-normalized-game-details-spec
```

Prepare an additional signed RC when this gate changes production code, then continue to Task 15. Official upstream PR construction starts only after Tasks 15–19 reach their recorded closure state. Exclude fork-only package IDs, signing, branding, versioning, and release workflow commits from the upstream PR.

---

### Task 15: Complete resolver durability and native detail parity

**Ledger:** R4–R6, P2, D2–D4, A1

**Files:**
- Create: `app/src/main/java/app/gamenative/data/canonical/SteamCatalogResolutionAttemptEntity.kt`
- Create: `app/src/main/java/app/gamenative/data/canonical/RejectedSteamCandidateEntity.kt`
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/app/gamenative/db/PluviaDatabase.kt`
- Modify: `app/src/main/java/app/gamenative/db/migration/RoomMigration.kt`
- Modify: `app/src/main/java/app/gamenative/library/canonical/catalog/SteamCatalogResolutionRepository.kt`
- Modify: `app/src/main/java/app/gamenative/ui/model/SteamMatchViewModel.kt`
- Create: `app/src/main/java/app/gamenative/db/dao/SteamCatalogResolutionDao.kt`
- Modify: `app/src/main/java/app/gamenative/db/dao/StoreMatchDao.kt`
- Test: `app/src/androidTest/java/app/gamenative/db/CanonicalMigrationTest.kt`
- Test: `app/src/test/java/app/gamenative/db/migration/RoomMigrationTest.kt`
- Modify: `app/src/main/java/app/gamenative/library/canonical/CanonicalLibraryRepository.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/CanonicalGameDetailScreen.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/LibraryScreen.kt`
- Modify: `app/src/main/java/app/gamenative/ui/model/GameDetailViewModel.kt`
- Test: `app/src/test/java/app/gamenative/library/canonical/CanonicalLibraryRepositoryTest.kt`
- Test: `app/src/test/java/app/gamenative/ui/model/GameDetailViewModelTest.kt`
- Test: `app/src/androidTest/java/app/gamenative/ui/screen/library/CanonicalGameDetailScreenTest.kt`
- Create: `docs/superpowers/reviews/2026-08-08-resolver-detail-completion-cross-check.md`

- [ ] **Step 1: Measure aggregate resolver coverage**

Add a 900-canonical fixture with representative exact, edition, duplicate-name, missing-developer, missing-year, and no-result cases. Record only aggregate categories. The completion threshold is:

```kotlin
data class ResolverCoverage(
    val eligible: Int,
    val autoAccepted: Int,
    val reviewRequired: Int,
    val unmatched: Int,
) {
    val usefulCoveragePercent: Int
        get() = if (eligible == 0) 100 else
            ((autoAccepted + reviewRequired) * 100) / eligible
}
```

`usefulCoveragePercent >= 80` closes the global-index expansion trigger. A lower result requires the local global-AppList candidate index in Step 3; it cannot be silently accepted.

- [ ] **Step 2: Add durable attempt and rejection history**

Migrate schema 27→28 with immutable exports and upgrade tests. Store only canonical/candidate IDs, evidence hash, fixed status, resolver version, and timestamps—never titles or queries:

```kotlin
enum class SteamCatalogResolutionStatus {
    PENDING,
    AUTO_ACCEPTED,
    REVIEW_REQUIRED,
    UNMATCHED,
    FAILED,
}

@Entity(tableName = "steam_catalog_resolution_attempt")
data class SteamCatalogResolutionAttemptEntity(
    @PrimaryKey @ColumnInfo("canonical_id") val canonicalId: String,
    @ColumnInfo("evidence_hash") val evidenceHash: String,
    @ColumnInfo("resolver_version") val resolverVersion: Int,
    @ColumnInfo("status") val status: SteamCatalogResolutionStatus,
    @ColumnInfo("attempted_at") val attemptedAt: Long,
)

@Entity(
    tableName = "rejected_steam_candidate",
    primaryKeys = ["account_scope", "source", "stable_source_id", "steam_app_id"],
)
data class RejectedSteamCandidateEntity(
    @ColumnInfo("account_scope") val accountScope: String,
    @ColumnInfo("source") val source: GameSource,
    @ColumnInfo("stable_source_id") val stableSourceId: String,
    @ColumnInfo("steam_app_id") val steamAppId: Int,
    @ColumnInfo("rejected_at") val rejectedAt: Long,
)
```

Add `androidx.work:work-runtime-ktx:2.10.5` through `gradle/libs.versions.toml` and `app/build.gradle.kts`.

Add unique WorkManager work constrained to network availability. It reads only IDs/hashes from persisted input, reconstructs current evidence from Room, revalidates presence/current decision, and resumes unresolved attempts. Search text never enters WorkManager data.

- [ ] **Step 3: Apply the coverage-triggered catalog expansion**

If Step 1 is below 80%, add a bounded keyless Steam AppList download and app-private local title-key index. The index stores public AppID/title keys only, validates candidates through `appdetails`, and never becomes action authority. If Step 1 is at least 80%, record the measured closure in the cross-check and do not add the index.

Evaluate the existing GOG map only as a candidate hint. It remains review-required unless a map source satisfies the validated one-to-one contract.

- [ ] **Step 4: Finish Steam-first card/detail parity**

Use accepted cached Steam metadata for card title/artwork with source fallback. Restore the integrated guarded install/play action bar. Complete the original design's Overview/Details field/provenance matrix, move all resource links into Details, and finish canonical-detail gamepad B/focus/semantics/translations.

- [ ] **Step 5: Verify, cross-check, correct once, and publish**

Run resolver scale, schema migration, card repository, detail ViewModel/Compose, action-routing, privacy, and release-contract tests in Legacy and Modern. Cross-check every assigned ledger ID, apply one blocker correction pass, update the ledger with evidence, sync upstream, and publish the next signed RC using Task 7's immutable-tag/four-APK/signature procedure.

---

### Task 16: Complete public community browsing

**Ledger:** V2, C2, A1; permanent-boundary verification for V3 and C3

**Files:**
- Modify: `app/src/main/java/app/gamenative/library/community/SteamCommunityModels.kt`
- Modify: `app/src/main/java/app/gamenative/library/community/SteamCommunityUrlPolicy.kt`
- Modify: `app/src/main/java/app/gamenative/library/community/SteamCommunityTransport.kt`
- Modify: `app/src/main/java/app/gamenative/library/community/SteamReviewPageProvider.kt`
- Modify: `app/src/main/java/app/gamenative/library/community/SteamDiscussionProvider.kt`
- Modify: `app/src/main/java/app/gamenative/library/community/SteamDiscussionParser.kt`
- Modify: `app/src/main/java/app/gamenative/library/community/InMemorySteamCommunityRepository.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/components/SteamReviewsTab.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/components/SteamDiscussionsTab.kt`
- Modify: `app/src/main/java/app/gamenative/ui/model/GameDetailViewModel.kt`
- Test: `app/src/test/java/app/gamenative/library/community/SteamReviewPageProviderTest.kt`
- Test: `app/src/test/java/app/gamenative/library/community/SteamDiscussionProviderTest.kt`
- Test: `app/src/test/java/app/gamenative/library/community/SteamDiscussionParserTest.kt`
- Test: `app/src/test/java/app/gamenative/library/community/InMemorySteamCommunityRepositoryTest.kt`
- Test: `app/src/test/java/app/gamenative/ui/model/GameDetailViewModelTest.kt`
- Create fixtures: `app/src/test/resources/steam/community/review-comments.html`, `discussion-search.html`, `discussion-category.html`, and `discussion-thread-page-2.html`
- Create: `docs/superpowers/reviews/2026-08-08-community-completion-cross-check.md`

- [ ] **Step 1: Capture safe public fixtures and write failing parsers**

Use synthetic or publicly reproducible sanitized fixtures for review-comment pages, additional review filters, discussion search, categories, and pagination. Remove usernames, SteamIDs, titles, URLs, and bodies that are not necessary to prove selectors. Tests must fail closed when a layout cannot bind every route to the trusted AppID.

- [ ] **Step 2: Implement safe public review-comment reading where available**

Add native read-only comments only when an unauthenticated, no-cookie, endpoint-bound route is fixture-proven. Comment bodies follow the same active-session-only limits as reviews. Posting/replying remains an explicit external action. If no safe public route exists, present the evidence and obtain user approval before closing V2 as an external permanent boundary.

- [ ] **Step 3: Expand discussion coverage**

Add fixture-supported categories, in-memory search, and validated multipage listing/thread navigation. Search text remains ViewModel memory only. Unsupported layouts retain Open Community/Open Thread and a fixed explanation.

- [ ] **Step 4: Complete community UX coverage**

Finish focus order, gamepad Back, accessibility state announcements, and translations for every Reviews/Discussions state and action. Verify review/discussion failures remain independent.

- [ ] **Step 5: Verify, cross-check, correct once, and publish**

Run community transport/parser/repository/ViewModel/Compose/privacy tests in Legacy and Modern plus Android-test compilation. Resolve every assigned ledger row with implementation evidence or explicit user-approved boundary, sync upstream, and publish the next signed community-completion RC.

---

### Task 17: Harden external-storage movement and recovery

**Ledger:** S1–S3

**Files:**
- Modify: `app/src/main/java/app/gamenative/utils/ContainerStorageManager.kt`
- Modify: `app/src/main/java/app/gamenative/utils/StorageUtils.kt`
- Modify: `app/src/main/java/app/gamenative/service/DownloadService.kt`
- Modify: `app/src/main/java/app/gamenative/db/dao/GOGGameDao.kt`
- Modify: `app/src/main/java/app/gamenative/db/dao/EpicGameDao.kt`
- Modify: `app/src/main/java/app/gamenative/db/dao/AmazonGameDao.kt`
- Create: `app/src/main/java/app/gamenative/storage/GameMoveJournal.kt`
- Create: `app/src/test/java/app/gamenative/storage/GameMoveCoordinatorTest.kt`
- Create: `docs/superpowers/reviews/2026-08-08-external-storage-hardening-cross-check.md`

- [ ] **Step 1: Write failing move-safety tests**

Cover same-volume rename, cross-volume copy, insufficient capacity, short writes, checksum mismatch, cancellation, destination collision, metadata-update failure, active download/update/run exclusion, source unmount, destination unmount, read-only media, retry, rollback, and recovery after interruption.

- [ ] **Step 2: Add a durable move journal**

```kotlin
enum class GameMovePhase { PLANNED, COPYING, VERIFIED, PROMOTED, METADATA_UPDATED, SOURCE_REMOVED }

data class GameMoveJournal(
    val operationId: String,
    val sourceKind: GameSource,
    val sourceRootHash: String,
    val destinationRootHash: String,
    val phase: GameMovePhase,
    val copiedBytes: Long,
    val expectedBytes: Long,
)
```

Persist only fixed source kind, hashed roots, counts, and phase in app-private storage. Never place install paths in diagnostics.

- [ ] **Step 3: Implement copy-verify-promote-delete**

Preflight free space and state guards. Copy into a destination staging directory without deleting source files, drain every write, verify the completed manifest/checksums, promote, update source metadata, then remove the source. The journal makes every interrupted phase recoverable or safely retryable.

- [ ] **Step 4: Validate storage/device behavior**

Run unit/integration tests first. Then claim explicit thread-scoped ownership of physical SD/USB devices or a separate AVD serial. Test fresh/full/read-only/removed/reinserted media, legacy and modern flavors, compatibility and side-by-side roots, Steam/GOG/Epic/Amazon move/update/launch/uninstall, and interruption recovery. Never touch an occupied device.

- [ ] **Step 5: Cross-check, correct once, and publish**

Resolve S1–S3 with exact evidence, sync upstream, and publish a signed storage-hardening RC.

---

### Task 18: Decide and, if approved, implement safe per-container LSFG import

**Ledger:** L1

**Files if approved:**
- Modify: `app/src/main/java/app/gamenative/ui/component/dialog/GraphicsTab.kt`
- Modify: `app/src/main/java/app/gamenative/utils/LsfgVkManager.kt`
- Modify: `app/src/main/java/app/gamenative/utils/ContainerUtils.kt`
- Modify: `app/src/main/java/com/winlator/container/ContainerData.kt`
- Create: `app/src/main/java/app/gamenative/utils/ManagedLsfgImporter.kt`
- Create: `app/src/test/java/app/gamenative/utils/ManagedLsfgImporterTest.kt`
- Create: `docs/superpowers/reviews/2026-08-08-lsfg-decision-and-cross-check.md`

- [ ] **Step 1: Present a concrete user decision**

Show the benefit, Wine execution risk, validation limits, per-container behavior, and storage model. L1 cannot close through assistant inference. Record either explicit rejection or approval.

- [ ] **Step 2: If approved, write failing importer tests**

Cover valid bounded PE/DLL input, invalid/empty/oversized input, interrupted copy, existing valid asset preservation, atomic replacement, remove, stale reference, multiple containers with different assets, and Steam-installed-versus-manual priority.

- [ ] **Step 3: Implement managed import**

```kotlin
data class ManagedLsfgAsset(
    val relativePath: String,
    val sha256: String,
    val sizeBytes: Long,
)
```

Copy a content URI to a temporary app-private file, enforce a 64 MiB limit, validate DOS/PE headers and DLL characteristics, hash, fsync, atomically promote, and store a relative managed identity per container. Provide explicit Replace, Remove, missing-file, and import-error UI. Never share one mutable global absolute path across containers.

- [ ] **Step 4: Verify and close L1**

If implemented, run focused tests, one safety cross-check/correction, upstream sync, and a signed LSFG RC. If rejected, commit the explicit user decision and rationale; no release is required.

---

### Task 19: Close the ledger and prepare the final upstream handoff

**Ledger:** F1 and every row not closed by Tasks 1–18

**Files:**
- Create: `docs/superpowers/reviews/2026-08-08-steam-first-completion-ledger.md`
- Modify if assigned by the ledger: `app/src/main/java/app/gamenative/library/canonical/catalog/SteamCatalogResolutionRepository.kt`
- Modify if assigned by the ledger: `app/src/main/java/app/gamenative/ui/model/GameDetailViewModel.kt`
- Modify if assigned by the ledger: `app/src/main/java/app/gamenative/utils/ContainerStorageManager.kt`
- Modify if L1 was approved: `app/src/main/java/app/gamenative/utils/ManagedLsfgImporter.kt`
- Modify the exact owning tests named in the unresolved ledger rows.

- [ ] **Step 1: Prove every ledger row has closure evidence**

The final ledger lists implementation commit/tests/release for implemented rows and explicit user approval for permanent boundaries. Any row with a generic deferred, optional, later, or unassigned state blocks completion.

- [ ] **Step 2: Run the aggregate gates once**

Run full Legacy/Modern unit suites with `--continue`, Legacy lint, schema migration/export tests, all four release-workflow contracts, release assembly, and the explicitly authorized device matrix. Record inherited failures separately; do not loop broad commands.

- [ ] **Step 3: Apply one final attributable correction**

Fix final Critical/High defects and ledger-assigned final corrections only. Rerun their owning tests and the release sentinels.

- [ ] **Step 4: Publish the final signed fork RC**

Sync current official upstream, fast-forward fork master, create one immutable increasing tag, publish four signed APKs, and verify checksums/version/packages/v2 signature/persistent fork certificate.

- [ ] **Step 5: Prepare the official PR series**

Rebase or merge from then-current official master, retain upstream-compatible feature commits, and exclude fork-only application IDs, signing, branding, version bumps, release notes, tags, and publication workflow commits. Do not push to official origin; open the PR only after the user's signed-build testing.

---

## Plan self-check matrix

| Design requirement | Owning tasks |
|---|---|
| Automatic non-Steam catalog discovery | 2–4 |
| Strict multi-field acceptance | 2–3 |
| No network in projection transaction | 4 |
| Guarded automatic/manual mutation | 4 |
| Metadata/facets/popularity/PICS handoff | 5 |
| Visible progress and manual correction | 6 |
| Exactly four detail tabs | 6 |
| Resolver cross-check/release | 7 |
| No-store native Reviews | 8–9 |
| Reviews cross-check/release | 10 |
| Safe native Discussions | 11–12 |
| Discussions cross-check/release | 13 |
| Per-deliverable 80/20 classification/correction | 7, 10, 13–14 |
| Resolver durability, coverage, and detail parity | 15 |
| Review comments and broader community coverage | 16 |
| External-storage safety and recovery | 17 |
| Explicit LSFG decision and safe implementation | 18 |
| No-forgotten completion ledger | 14–19 |
| Aggregate final gate and official handoff | 19 |
| Official sync and persistent signing | 1, 7, 10, 13, 15–19 |
