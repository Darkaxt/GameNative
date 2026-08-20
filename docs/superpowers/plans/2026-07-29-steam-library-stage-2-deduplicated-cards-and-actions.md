# Steam Library Stage 2 Deduplicated Cards and Actions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Every delegated worker must be told: **“IMPORTANT: Do not invoke Agent and do not spawn/delegate to any subagents. Work alone.”**

**Goal:** Replace duplicate source rows in the public library with one canonical card per confidently matched game, expose every owned copy and a remembered preferred copy, and route existing source-native operations through an exact copy target that is revalidated immediately before execution.

**Architecture:** Keep canonical presentation identity, durable `OwnedCopyKey`, provider identity, and executable source-native `LibraryItem` separate. Add a Room-backed canonical read repository and source-specific runtime adapters, assemble cards before filtering and pagination, then feed typed cards through the existing library surface behind a public capability gate and a sticky first-success projection-readiness signal. A central router selects one copy, captures its immutable source reference, and supplies a memory-only guard to the existing source screen; the guard resolves the same key again before every core operation and confirmation commit and never substitutes another copy.

**Tech Stack:** Kotlin 2.1.21, Android, Jetpack Compose, Material 3, Room 2.8.4, Hilt, DataStore Preferences, coroutines/Flow, JUnit 4, Robolectric, MockK, Compose UI tests, bounded JSONL `FeatureDiagnostics`

> **Execution update — 2026-07-31:** Tasks 1–11 and the committed scale fixture are historical implementation evidence. Unfinished validation/live-matrix/cross-check work in Tasks 12–14 is superseded by `docs/superpowers/plans/2026-07-31-steam-first-visible-delivery.md`, which prioritizes default-visible vertical slices and signed complaint-driven nightlies. Do not resume Task 12 from this document.

---

## Starting point and hard boundaries

- Start from pushed commit `de17ce05` on branch `codex/steam-normalized-game-details-spec`; it preserves the Stage 1 gate at `b7d24ee9` and merges upstream `origin/master` at `e31fe6f8`.
- Stage 1 is complete in shadow mode. Schema version 27, immutable exported schemas, account lifecycle serialization, ownership ledgers, conservative matching, and mutation transactions are inputs to this plan.
- Implement and push one logical task at a time to remote `fork`. Never batch several completed tasks into one commit.
- Do not use the occupied `emulator-5554`. JVM/Robolectric tests are the default. If Android instrumentation is essential, create a separate temporary AVD and remove it after use.
- The public gate remains default-off until the focused routing matrix and signed-in multi-store matrix pass. Gate-off behavior must remain the exact existing source-native library.
- Stage 2 does **not** add rich canonical details, providers, reviews, discussions, genres, tags, popularity controls, or Stage 3 discovery queries.
- Existing external launch intents, containers, frontend sync files, downloads screen, and source-native IDs remain unchanged.
- Canonical identity must never be sent to `ContainerUtils`, `IntentLaunchManager`, `FrontendSyncManager`, `PluviaMain.preLaunchApp`, a source service, or an external intent.
- A source-native `LibraryItem` may be created only after one exact `OwnedCopyKey` resolves through its source runtime adapter.
- A captured action may fail after account, entitlement, source-row, installation, or capability changes. It must not retry against a sibling copy.
- Review-required, rejected, and unmatched relationships remain independent cards. Only `VERIFIED` and `HIGH` relationships share a grouped card.
- Stage 1’s low Epic visibility and coordinator fallback-generation findings are closed before any public-card work.

### Mandatory diagnostics/data-protection contract

Do not persist or export:

- Passwords, API keys, signing secrets, authentication headers, cookies, or tokens.
- SteamIDs, usernames/profiles, account IDs/scopes, or personal/account associations.
- Ownership/entitlement associations or raw `OwnedCopyKey` values.
- Private user-entered search text.
- Install paths or other personal filesystem locations.

Feature diagnostics remain bounded and manual-export only. There is no automatic diagnostic upload. New APIs accept typed source, operation, selection, capability, fixed reason, outcome, aggregate count, duration, HTTP status, exception class, short hashed correlations, and explicit bounded public title/AppID/storefront-ID/route/URL/content-ID fields. Public game/catalog/community data is not private. APIs do not accept credentials, account/profile or entitlement associations, paths, private search text, arbitrary error strings, or source objects.

## Stage 2 operation matrix

This matrix describes the core operations that may originate from a canonical card or Copies sheet. Existing source-detail screens continue to expose other source-specific tools through their legacy authority boundary.

| Source | Install | Play | Update | Uninstall/cancel | Save export/import | Source details |
|---|---:|---:|---:|---:|---:|---:|
| Steam | yes | installed only | only when pending | yes | yes | yes |
| GOG | yes | installed only | no; current handler is empty | yes | no | yes |
| Epic | yes | installed only | no; current handler is empty | yes | no | yes |
| Amazon | yes | installed only | only when pending | yes | no | yes |
| Custom | no | yes | no | no from canonical UI | no | yes |

`PAUSE_RESUME_DOWNLOAD` and `CANCEL_DOWNLOAD` are capabilities only while the captured source reports those states. Store-page routing is not exposed in Stage 2 because no current source screen implements a dependable store-page operation. This is recorded in the final cross-check instead of pretending that an empty menu enum is executable behavior.

## File and responsibility map

### New production files

- `app/src/main/java/app/gamenative/library/canonical/CanonicalLibraryModels.kt`
  - Typed grouped/independent card identity, owned-copy summary, card, query result, and fixed public failure types.
- `app/src/main/java/app/gamenative/db/dao/CanonicalLibraryDao.kt`
  - Reactive transactional aggregate of canonical games, matches, and preferences. No schema change.
- `app/src/main/java/app/gamenative/library/canonical/runtime/OwnedCopyRuntime.kt`
  - Runtime-only resolved copy, fixed visibility result, and operation capabilities.
- `app/src/main/java/app/gamenative/library/canonical/runtime/OwnedCopyRuntimeAdapter.kt`
  - Source runtime-adapter contract and registry.
- `app/src/main/java/app/gamenative/library/canonical/runtime/SteamOwnedCopyRuntimeAdapter.kt`
- `app/src/main/java/app/gamenative/library/canonical/runtime/GogOwnedCopyRuntimeAdapter.kt`
- `app/src/main/java/app/gamenative/library/canonical/runtime/EpicOwnedCopyRuntimeAdapter.kt`
- `app/src/main/java/app/gamenative/library/canonical/runtime/AmazonOwnedCopyRuntimeAdapter.kt`
- `app/src/main/java/app/gamenative/library/canonical/runtime/CustomOwnedCopyRuntimeAdapter.kt`
  - Resolve a Stage 1 copy reference to current source data, source facets, volatile state, capabilities, artwork, and an optional source-native `LibraryItem`; the item is null only when the current legacy bridge cannot represent a valid provider ID.
- `app/src/main/java/app/gamenative/library/canonical/CanonicalLibraryRepository.kt`
  - Observe, resolve, hide stale-account copies, group trusted matches, and emit immutable canonical cards before UI filtering or pagination.
- `app/src/main/java/app/gamenative/library/canonical/PreferredCopyRepository.kt`
  - Validate and persist/clear the complete preferred `OwnedCopyKey` without overwriting title/artwork overrides.
- `app/src/main/java/app/gamenative/library/canonical/action/OwnedCopyActionRouter.kt`
  - Deterministic selection, exact target capture, chooser result, and no-fallback failure behavior.
- `app/src/main/java/app/gamenative/library/canonical/action/OwnedCopyActionGuard.kt`
  - Memory-only captured reference and execution-time revalidation.
- `app/src/main/java/app/gamenative/library/canonical/CanonicalPublicLibraryGate.kt`
  - Independent public-card capability requiring the Stage 1 projection prerequisite.
- `app/src/main/java/app/gamenative/library/canonical/CanonicalProjectionReadiness.kt`
  - Process-local, sticky signal that prevents the public branch from treating pre-projection empty Room state as a valid empty library.
- `app/src/main/java/app/gamenative/library/canonical/CanonicalLibraryDiagnostics.kt`
  - Typed aggregate-only feature diagnostics.
- `app/src/main/java/app/gamenative/ui/data/LibraryCard.kt`
  - Typed display card whose identity is source-native, canonical, or promotional; canonical identity cannot be unwrapped as a `LibraryItem`.
- `app/src/main/java/app/gamenative/ui/screen/library/components/OwnedSourceBadges.kt`
  - Deterministically ordered, accessible multi-store badges.
- `app/src/main/java/app/gamenative/ui/screen/library/components/CanonicalCopiesSheet.kt`
  - Copy status, preferred choice, core capability actions, and safe “Separate copy” control.

### Existing production files changed

- `app/src/main/java/app/gamenative/library/canonical/source/EpicOwnedCopySourceAdapter.kt`
- `app/src/main/java/app/gamenative/library/canonical/CanonicalProjectionCoordinator.kt`
- `app/src/main/java/app/gamenative/PrefManager.kt`
- `app/src/main/java/app/gamenative/db/PluviaDatabase.kt`
- `app/src/main/java/app/gamenative/db/dao/CanonicalPreferenceDao.kt`
- `app/src/main/java/app/gamenative/db/dao/StoreMatchDao.kt`
- `app/src/main/java/app/gamenative/di/DatabaseModule.kt`
- `app/src/main/java/app/gamenative/di/CanonicalLibraryModule.kt`
- `app/src/main/java/app/gamenative/diagnostics/DiagnosticEvent.kt`
- `app/src/main/java/app/gamenative/ui/data/LibraryState.kt`
- `app/src/main/java/app/gamenative/ui/model/LibraryViewModel.kt`
- `app/src/main/java/app/gamenative/ui/screen/library/LibraryScreen.kt`
- `app/src/main/java/app/gamenative/ui/screen/library/LibraryAppScreen.kt`
- `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryAppItem.kt`
- `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryGridCard.kt`
- `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryListCard.kt`
- `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryList.kt`
- `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryListPane.kt`
- `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryCarouselPane.kt`
- `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryDynamicBackdrop.kt`
- `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryDetailPane.kt`
- `app/src/main/java/app/gamenative/ui/screen/library/appscreen/BaseAppScreen.kt`
- `app/src/main/java/app/gamenative/ui/screen/library/appscreen/SteamAppScreen.kt`
- `app/src/main/java/app/gamenative/ui/screen/library/appscreen/GOGAppScreen.kt`
- `app/src/main/java/app/gamenative/ui/screen/library/appscreen/EpicAppScreen.kt`
- `app/src/main/java/app/gamenative/ui/screen/library/appscreen/AmazonAppScreen.kt`
- `app/src/main/java/app/gamenative/ui/screen/library/appscreen/CustomGameAppScreen.kt`
- `app/src/main/java/app/gamenative/ui/screen/settings/SettingsGroupDebug.kt`
- `app/src/main/res/values/strings.xml`

### New or extended tests

- Extend `app/src/test/java/app/gamenative/library/canonical/source/OwnedCopySourceAdapterTest.kt`
- Extend `app/src/test/java/app/gamenative/library/canonical/CanonicalProjectionCoordinatorTest.kt`
- Create `app/src/test/java/app/gamenative/db/dao/CanonicalLibraryDaoTest.kt`
- Create `app/src/test/java/app/gamenative/library/canonical/runtime/OwnedCopyRuntimeAdapterTest.kt`
- Create `app/src/test/java/app/gamenative/library/canonical/CanonicalLibraryRepositoryTest.kt`
- Create `app/src/test/java/app/gamenative/library/canonical/PreferredCopyRepositoryTest.kt`
- Create `app/src/test/java/app/gamenative/library/canonical/action/OwnedCopyActionRouterTest.kt`
- Create `app/src/test/java/app/gamenative/library/canonical/action/OwnedCopyActionGuardTest.kt`
- Create `app/src/test/java/app/gamenative/library/canonical/CanonicalLibraryDiagnosticsTest.kt`
- Create `app/src/test/java/app/gamenative/ui/data/LibraryCardTest.kt`
- Create `app/src/test/java/app/gamenative/ui/model/CanonicalLibraryViewModelTest.kt`
- Create `app/src/test/java/app/gamenative/library/canonical/CanonicalLibraryScaleTest.kt`
- Create `app/src/test/java/app/gamenative/ui/screen/library/appscreen/CanonicalActionExecutionTest.kt`
- Create `app/src/androidTest/java/app/gamenative/ui/screen/library/CanonicalLibraryScreenTest.kt`

## Core types used by every task

Task 2 adds `CanonicalCardKey`; Task 3 extends the same file with the remaining types below. Later tasks must use these names and signatures exactly:

```kotlin
sealed interface CanonicalCardKey {
    data class Grouped(val canonicalId: CanonicalGameId) : CanonicalCardKey
    data class Independent(val copyKey: OwnedCopyKey) : CanonicalCardKey
}

enum class OwnedCopyOperation {
    INSTALL,
    PLAY,
    UPDATE,
    UNINSTALL,
    PAUSE_RESUME_DOWNLOAD,
    CANCEL_DOWNLOAD,
    EXPORT_SAVES,
    IMPORT_SAVES,
    OPEN_SOURCE_DETAILS,
}

enum class CopyUnavailableReason {
    SOURCE_READ_FAILED,
    SOURCE_ROW_CHANGED,
    LEGACY_BRIDGE_UNSUPPORTED,
}

enum class CanonicalPublicFailure {
    MISSING_PROJECTION_PREREQUISITE,
    ASSEMBLY_FAILED,
    INVALID_CARD_STATE,
    UNSUPPORTED_LEGACY_CONTEXT,
}

data class OwnedCopySummary(
    val key: OwnedCopyKey,
    val source: GameSource,
    val nativeTitle: String,
    val installPath: String?,
    val installedSizeBytes: Long?,
    val branchOrVersion: String?,
    val isInstalled: Boolean,
    val isDownloading: Boolean,
    val hasPartialDownload: Boolean,
    val updateAvailable: Boolean,
    val isShared: Boolean,
    val lastPlayedEpochMs: Long?,
    val playtimeMinutes: Long?,
    val capabilities: Set<OwnedCopyOperation>,
    val unavailableReason: CopyUnavailableReason?,
    val canSeparateMatch: Boolean,
    val matchMethod: MatchMethod,
    val confidence: MatchConfidence,
    val decisionSource: MatchDecisionSource,
)

data class CanonicalLibraryCard(
    val key: CanonicalCardKey,
    val canonicalId: CanonicalGameId,
    val displayName: String,
    val appType: CanonicalAppType,
    val iconUrl: String,
    val capsuleImageUrl: String,
    val headerImageUrl: String,
    val heroImageUrl: String,
    val gridHeroImageScale: Float,
    val aliases: Set<String>,
    val ownedSources: Set<GameSource>,
    val copies: List<OwnedCopySummary>,
    val preferredCopy: OwnedCopyKey?,
    val steamCollectionAppIds: Set<Int>,
    val isShared: Boolean,
) {
    val isInstalled: Boolean get() = copies.any(OwnedCopySummary::isInstalled)
    val lastPlayedEpochMs: Long? get() = copies.mapNotNull { it.lastPlayedEpochMs }.maxOrNull()
}
```

Runtime-only types added in Task 4:

```kotlin
data class OwnedCopyRuntime(
    val key: OwnedCopyKey,
    val reference: SourceOwnedCopyReference,
    val libraryItem: LibraryItem?,
    val nativeTitle: String,
    val aliases: Set<String>,
    val developerKey: String,
    val releaseYear: Int?,
    val appType: CanonicalAppType,
    val genreKeys: Set<String>,
    val tagIds: Set<Int>,
    val featureKeys: Set<String>,
    val iconUrl: String,
    val capsuleImageUrl: String,
    val headerImageUrl: String,
    val heroImageUrl: String,
    val gridHeroImageScale: Float,
    val installPath: String?,
    val installedSizeBytes: Long?,
    val branchOrVersion: String?,
    val isInstalled: Boolean,
    val isDownloading: Boolean,
    val hasPartialDownload: Boolean,
    val updateAvailable: Boolean,
    val isShared: Boolean,
    val lastPlayedEpochMs: Long?,
    val playtimeMinutes: Long?,
    val capabilities: Set<OwnedCopyOperation>,
)

sealed interface OwnedCopyRuntimeResult {
    data class Available(val copy: OwnedCopyRuntime) : OwnedCopyRuntimeResult
    data class Unavailable(
        val key: OwnedCopyKey,
        val reason: CopyUnavailableReason,
        val errorClass: KClass<out Throwable>? = null,
    ) : OwnedCopyRuntimeResult
    data object Hidden : OwnedCopyRuntimeResult
}

interface OwnedCopyRuntimeAdapter {
    val source: GameSource
    fun invalidations(): Flow<Unit>
    suspend fun resolve(key: OwnedCopyKey): OwnedCopyRuntimeResult
    suspend fun resolveAll(
        keys: Set<OwnedCopyKey>,
    ): Map<OwnedCopyKey, OwnedCopyRuntimeResult>
}
```

The repository never emits a copy for `Hidden`; that result covers signed-out sources, an account-scope or lifecycle mismatch, entitlement loss, excluded Epic content, and removed custom games. `Unavailable` is reserved for a read failure after the adapter has proved the key belongs to the current account, so the repository may retain that current account’s stale card with disabled actions. A scope-provider failure occurs before that proof and therefore returns `Hidden`, preventing account A’s titles from appearing after a switch to account B. An available runtime may have `libraryItem == null` only when the source entitlement is current but the existing legacy screen cannot represent its provider ID; the card remains visible and the adapter exposes no executable capability.

---

### Task 1: Close Stage 1 public-card prerequisites

**Files:**
- Modify: `app/src/main/java/app/gamenative/library/canonical/source/EpicOwnedCopySourceAdapter.kt:51-75`
- Modify: `app/src/main/java/app/gamenative/library/canonical/CanonicalProjectionCoordinator.kt:38-44,118-129`
- Modify: `app/src/test/java/app/gamenative/library/canonical/source/OwnedCopySourceAdapterTest.kt`
- Modify: `app/src/test/java/app/gamenative/library/canonical/CanonicalProjectionCoordinatorTest.kt`

- [ ] **Step 1: Add failing Epic snapshot tests**

Add fixtures with ledger-present rows where `isDLC = true`, `namespace = "ue"`, and `namespace = "89efe5924d3d467c839449ab6ab52e7f"`. Assert all three are omitted, a normal game remains, and intentional exclusions do not make the batch partial:

```kotlin
assertEquals(SnapshotCompleteness.COMPLETE, batch.completeness)
assertEquals(listOf(normalKey), batch.copies.map { it.key })
assertNull(batch.reason)
```

Also assert `resolve(excludedKey) == null`, so snapshot and point resolution use the same visibility contract.

- [ ] **Step 2: Add a failing coordinator fallback-generation test**

Use `InMemoryAccountLifecycleState`, advance GOG to generation 7, make its fake adapter throw, start the coordinator, and assert:

```kotlin
val failed = runner.batches.single().single { it.source == GameSource.GOG }
assertEquals(7L, failed.lifecycleGeneration)
assertNull(failed.accountScope)
assertEquals(SnapshotCompleteness.UNAVAILABLE, failed.completeness)
assertEquals(SnapshotReason.SOURCE_READ_FAILED, failed.reason)
```

Add a Custom failure assertion with `lifecycleGeneration == null`.

- [ ] **Step 3: Run both focused tests and verify red**

Run:

```bash
./gradlew :app:testLegacyDebugUnitTest \
  --tests "app.gamenative.library.canonical.source.OwnedCopySourceAdapterTest" \
  --tests "app.gamenative.library.canonical.CanonicalProjectionCoordinatorTest"
./gradlew :app:testModernDebugUnitTest \
  --tests "app.gamenative.library.canonical.source.OwnedCopySourceAdapterTest" \
  --tests "app.gamenative.library.canonical.CanonicalProjectionCoordinatorTest"
```

Expected: the Epic snapshot contains excluded rows and the coordinator fallback generation is null.

- [ ] **Step 4: Apply the Epic predicate before projection mapping**

Keep physical-row detection separate from visibility filtering so an intentionally excluded row is not reported as missing:

```kotlin
val copies = ledger.stableSourceIds.mapNotNull { stableSourceId ->
    val game = rowsById[stableSourceId]
    if (game == null) {
        missingRow = true
        return@mapNotNull null
    }
    if (!isVisibleInAllLibrary(game)) return@mapNotNull null
    OwnedCopyProjection(
        key = OwnedCopyKey(accountScope, source, stableSourceId),
        displayName = game.title,
        developer = game.developer,
        releaseYear = CanonicalNormalization.releaseYear(game.releaseDate),
        appType = CanonicalNormalization.appType(game.type),
        genreKeys = sourceQualifiedKeys("epic", game.genres),
    )
}
```

- [ ] **Step 5: Carry the coordinator fallback generation**

Inject the already-bound `AccountLifecycleState` into `CanonicalProjectionCoordinator` and change only the escaped-adapter fallback:

```kotlin
class CanonicalProjectionCoordinator @Inject constructor(
    adapters: Set<@JvmSuppressWildcards OwnedCopySourceAdapter>,
    private val runner: CanonicalProjectionRunner,
    private val diagnostics: CanonicalDiagnosticSink,
    private val gate: CanonicalProjectionGate,
    private val clock: CanonicalProjectionClock,
    private val accountLifecycleState: AccountLifecycleState,
)

private fun fallbackGeneration(source: GameSource): Long? =
    if (source == GameSource.CUSTOM_GAME) null else accountLifecycleState.generation(source)
```

Pass `lifecycleGeneration = fallbackGeneration(adapter.source)` to `sourceReadFailed`. Keep `accountScope = null`, preserving mutation-free unavailable semantics.

- [ ] **Step 6: Run focused tests in both flavors**

Expected: both commands from Step 3 pass.

- [ ] **Step 7: Commit and push**

```bash
git add app/src/main/java/app/gamenative/library/canonical/source/EpicOwnedCopySourceAdapter.kt \
  app/src/main/java/app/gamenative/library/canonical/CanonicalProjectionCoordinator.kt \
  app/src/test/java/app/gamenative/library/canonical/source/OwnedCopySourceAdapterTest.kt \
  app/src/test/java/app/gamenative/library/canonical/CanonicalProjectionCoordinatorTest.kt
git commit -m "fix: harden canonical projection fallbacks" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD
```

---

### Task 2: Introduce typed library-card presentation without changing gate-off behavior

**Files:**
- Create: `app/src/main/java/app/gamenative/library/canonical/CanonicalLibraryModels.kt` with `CanonicalCardKey` and its opaque Compose key only
- Create: `app/src/main/java/app/gamenative/ui/data/LibraryCard.kt`
- Modify: `app/src/main/java/app/gamenative/ui/data/LibraryState.kt`
- Modify: `app/src/main/java/app/gamenative/ui/model/LibraryViewModel.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/LibraryScreen.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryAppItem.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryGridCard.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryListCard.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryList.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryListPane.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryCarouselPane.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryDynamicBackdrop.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryDetailPane.kt`
- Create: `app/src/test/java/app/gamenative/ui/data/LibraryCardTest.kt`

- [ ] **Step 1: Add failing identity-boundary tests**

Test these exact rules:

```kotlin
val native = LibraryItem(appId = "STEAM_10", name = "Native")
val source = LibraryCard.fromSource(native)
assertEquals(native, source.sourceItemOrNull())
assertEquals("source:STEAM_10", source.composeKey)

val canonical = LibraryCard.canonical(
    key = CanonicalCardKey.Grouped(CanonicalGameId.parse("11111111-1111-1111-1111-111111111111")),
    index = 0,
    name = "Canonical",
    ownedSources = setOf(GameSource.STEAM, GameSource.GOG),
)
assertNull(canonical.sourceItemOrNull())
assertEquals(listOf(GameSource.STEAM, GameSource.GOG), canonical.orderedSources)
```

Add a test that promotional IDs remain `Promotion`, not `SourceCopy`, and a gate-off final-boundary test that compatibility/status and `GameCardStats` are copied onto the card exactly.

- [ ] **Step 2: Run the test and verify red**

```bash
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.ui.data.LibraryCardTest"
```

Expected: `LibraryCard` is unresolved.

- [ ] **Step 3: Add the complete presentation type**

Use a sealed identity and plain display fields. Do not place a canonical UUID into `LibraryItem.appId`. First create the card-key identity in package `app.gamenative.library.canonical`; use a digest for an independent copy’s Compose key so neither account scope nor source-native ID enters UI tooling output:

```kotlin
sealed interface CanonicalCardKey {
    data class Grouped(val canonicalId: CanonicalGameId) : CanonicalCardKey
    data class Independent(val copyKey: OwnedCopyKey) : CanonicalCardKey
}

internal fun CanonicalCardKey.stableComposeKey(): String = when (this) {
    is CanonicalCardKey.Grouped -> "group:${canonicalId.value}"
    is CanonicalCardKey.Independent -> {
        val raw = listOf(
            copyKey.accountScope.value,
            copyKey.source.name,
            copyKey.stableSourceId,
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { byte -> "%02x".format(byte) }
        "copy:${copyKey.source.name}:$digest"
    }
}
```

Then add the presentation type:

```kotlin
sealed interface LibraryCardIdentity {
    data class SourceCopy(val item: LibraryItem) : LibraryCardIdentity
    data class Canonical(val key: CanonicalCardKey) : LibraryCardIdentity
    data class Promotion(val id: String) : LibraryCardIdentity
}

data class LibraryCard(
    val identity: LibraryCardIdentity,
    val index: Int,
    val name: String,
    val iconUrl: String,
    val capsuleImageUrl: String,
    val headerImageUrl: String,
    val heroImageUrl: String,
    val gridHeroImageScale: Float,
    val ownedSources: Set<GameSource>,
    val compatibilityStatus: GameCompatibilityStatus?,
    val gameStats: GameCardStats?,
    val sizeBytes: Long,
    val isInstalled: Boolean,
    val isShared: Boolean,
    val isRecommended: Boolean,
    val recommendedGameId: String,
    val recRating: Int?,
    val recDiscount: String?,
    val recPrice: String?,
    val recBasePrice: String?,
    val recSeedCount: Int,
    val recSeedIconUrl: String?,
    val recStoreCard: Boolean,
    val recSource: String,
    val isFeatured: Boolean,
) {
    val composeKey: String
        get() = when (val value = identity) {
            is LibraryCardIdentity.SourceCopy -> "source:${value.item.appId}"
            is LibraryCardIdentity.Canonical -> "canonical:${value.key.stableComposeKey()}"
            is LibraryCardIdentity.Promotion -> "promotion:${value.id}"
        }

    val orderedSources: List<GameSource>
        get() = OWNED_SOURCE_ORDER.filter(ownedSources::contains)

    fun sourceItemOrNull(): LibraryItem? =
        (identity as? LibraryCardIdentity.SourceCopy)?.item

    companion object {
        val OWNED_SOURCE_ORDER = listOf(
            GameSource.STEAM,
            GameSource.GOG,
            GameSource.EPIC,
            GameSource.AMAZON,
            GameSource.CUSTOM_GAME,
        )

        fun fromSource(
            item: LibraryItem,
            compatibilityStatus: GameCompatibilityStatus? = item.compatibilityStatus,
            gameStats: GameCardStats? = null,
        ): LibraryCard = fromLibraryItem(
            identity = LibraryCardIdentity.SourceCopy(item),
            item = item,
            compatibilityStatus = compatibilityStatus,
            gameStats = gameStats,
        )

        fun fromPromotion(
            item: LibraryItem,
            compatibilityStatus: GameCompatibilityStatus? = item.compatibilityStatus,
            gameStats: GameCardStats? = null,
        ): LibraryCard = fromLibraryItem(
            identity = LibraryCardIdentity.Promotion(item.appId),
            item = item,
            compatibilityStatus = compatibilityStatus,
            gameStats = gameStats,
        )

        private fun fromLibraryItem(
            identity: LibraryCardIdentity,
            item: LibraryItem,
            compatibilityStatus: GameCompatibilityStatus?,
            gameStats: GameCardStats?,
        ): LibraryCard = LibraryCard(
            identity = identity,
            index = item.index,
            name = item.name,
            iconUrl = item.clientIconUrl,
            capsuleImageUrl = item.capsuleImageUrl,
            headerImageUrl = item.headerImageUrl,
            heroImageUrl = item.heroImageUrl,
            gridHeroImageScale = item.gridHeroImageScale,
            ownedSources = setOf(item.gameSource),
            compatibilityStatus = compatibilityStatus,
            gameStats = gameStats,
            sizeBytes = item.sizeBytes,
            isInstalled = item.isInstalled,
            isShared = item.isShared,
            isRecommended = item.isRecommended,
            recommendedGameId = item.recommendedGameId,
            recRating = item.recRating,
            recDiscount = item.recDiscount,
            recPrice = item.recPrice,
            recBasePrice = item.recBasePrice,
            recSeedCount = item.recSeedCount,
            recSeedIconUrl = item.recSeedIconUrl,
            recStoreCard = item.recStoreCard,
            recSource = item.recSource,
            isFeatured = item.isFeatured,
        )

        fun canonical(
            key: CanonicalCardKey,
            index: Int,
            name: String,
            iconUrl: String = "",
            capsuleImageUrl: String = "",
            headerImageUrl: String = "",
            heroImageUrl: String = "",
            gridHeroImageScale: Float = 1f,
            ownedSources: Set<GameSource>,
            compatibilityStatus: GameCompatibilityStatus? = null,
            gameStats: GameCardStats? = null,
            sizeBytes: Long = 0,
            isInstalled: Boolean = false,
            isShared: Boolean = false,
        ): LibraryCard = LibraryCard(
            identity = LibraryCardIdentity.Canonical(key),
            index = index,
            name = name,
            iconUrl = iconUrl,
            capsuleImageUrl = capsuleImageUrl,
            headerImageUrl = headerImageUrl,
            heroImageUrl = heroImageUrl,
            gridHeroImageScale = gridHeroImageScale,
            ownedSources = ownedSources,
            compatibilityStatus = compatibilityStatus,
            gameStats = gameStats,
            sizeBytes = sizeBytes,
            isInstalled = isInstalled,
            isShared = isShared,
            isRecommended = false,
            recommendedGameId = "",
            recRating = null,
            recDiscount = null,
            recPrice = null,
            recBasePrice = null,
            recSeedCount = 0,
            recSeedIconUrl = null,
            recStoreCard = false,
            recSource = "",
            isFeatured = false,
        )
    }
}
```

Keep `CanonicalCardKey` in `CanonicalLibraryModels.kt`; Task 3 extends that same file with the remaining canonical card types. No reflection or serialization round-trip is permitted.

- [ ] **Step 4: Migrate library state and visual components mechanically**

Rename `LibraryState.appInfoList` to `cards: List<LibraryCard>`. Update library-only list, carousel, backdrop, and card composables to consume `LibraryCard`. Key lazy items by `card.composeKey`, and pass `card.compatibilityStatus`/`card.gameStats` into card visuals instead of trying to unwrap a source item. Use `card.orderedSources` for badges in Task 9; until then render the first ordered source so gate-off visuals are unchanged.

`LibraryDetailPane` and `AppScreen` must still receive only `card.sourceItemOrNull()`. A canonical card is not created in this task, so a null result is treated as no source detail.

- [ ] **Step 5: Map the existing legacy pipeline at its final boundary**

Keep every existing filter, source mapping, recommendation, count, sort, and pagination operation on `LibraryItem`. Immediately before updating `LibraryState`, convert:

```kotlin
val cards = pagedList.map { item ->
    val compatibility = currentState.compatibilityMap[item.name]
    val stats = currentState.statsFor(item)
    if (item.isRecommended || item.isFeatured) {
        LibraryCard.fromPromotion(item, compatibility, stats)
    } else {
        LibraryCard.fromSource(item, compatibility, stats)
    }
}
```

Use `cards` for compatibility fetching and UI state. Do not change external callbacks or source `LibraryItem.appId` values.

- [ ] **Step 6: Run focused and existing library tests**

```bash
./gradlew :app:testLegacyDebugUnitTest \
  --tests "app.gamenative.ui.data.LibraryCardTest"
./gradlew :app:testModernDebugUnitTest \
  --tests "app.gamenative.ui.data.LibraryCardTest"
./gradlew --no-parallel :app:compileLegacyDebugKotlin
./gradlew --no-parallel :app:compileModernDebugKotlin
```

Expected: pass. Manually compare gate-off All/source tabs, selection, recommendation cards, pagination, and source detail navigation in previews or a separate test device. No emulator is required.

- [ ] **Step 7: Prove legacy executable contracts are unchanged**

```bash
git diff --exit-code de17ce05..HEAD -- \
  app/src/main/java/app/gamenative/ui/PluviaMain.kt \
  app/src/main/java/app/gamenative/sync/FrontendSyncManager.kt \
  app/src/main/java/app/gamenative/data/LibraryItem.kt \
  app/src/main/java/app/gamenative/utils/ContainerUtils.kt \
  app/src/main/java/app/gamenative/utils/IntentLaunchManager.kt
```

Expected: no output.

- [ ] **Step 8: Commit and push**

```bash
git add app/src/main/java/app/gamenative/library/canonical/CanonicalLibraryModels.kt \
  app/src/main/java/app/gamenative/ui/data/LibraryCard.kt \
  app/src/main/java/app/gamenative/ui/data/LibraryState.kt \
  app/src/main/java/app/gamenative/ui/model/LibraryViewModel.kt \
  app/src/main/java/app/gamenative/ui/screen/library \
  app/src/test/java/app/gamenative/ui/data/LibraryCardTest.kt
git commit -m "refactor: type library card identities" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD
```

---

### Task 3: Add the reactive canonical-library read model

**Files:**
- Modify: `app/src/main/java/app/gamenative/library/canonical/CanonicalLibraryModels.kt`
- Create: `app/src/main/java/app/gamenative/db/dao/CanonicalLibraryDao.kt`
- Modify: `app/src/main/java/app/gamenative/db/PluviaDatabase.kt`
- Modify: `app/src/main/java/app/gamenative/di/DatabaseModule.kt`
- Create: `app/src/test/java/app/gamenative/db/dao/CanonicalLibraryDaoTest.kt`

- [ ] **Step 1: Add failing DAO tests**

Use an in-memory `PluviaDatabase`. Insert one canonical, two present matches, one absent match, and one preference. Collect `observePresentGames().first()` and assert:

- one aggregate is returned;
- all relationships are transactionally attached, allowing the repository to distinguish present/absent;
- preference cardinality is zero or one;
- changing `store_match.is_present` invalidates the flow;
- changing the preferred-copy triple invalidates the flow;
- no table/entity/schema version is added.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.db.dao.CanonicalLibraryDaoTest"
```

Expected: unresolved DAO/model symbols.

- [ ] **Step 3: Add the aggregate and DAO**

```kotlin
data class CanonicalLibraryAggregate(
    @Embedded val game: CanonicalGameEntity,
    @Relation(
        parentColumn = "canonical_id",
        entityColumn = "canonical_id",
    )
    val matches: List<StoreMatchEntity>,
    @Relation(
        parentColumn = "canonical_id",
        entityColumn = "canonical_id",
    )
    val preferences: List<CanonicalGamePreferenceEntity>,
) {
    fun preferenceOrNull(): CanonicalGamePreferenceEntity? = preferences.singleOrNull()
}

@Dao
interface CanonicalLibraryDao {
    @Transaction
    @Query(
        """
        SELECT * FROM canonical_game
        WHERE EXISTS (
            SELECT 1 FROM store_match
            WHERE store_match.canonical_id = canonical_game.canonical_id
              AND store_match.is_present = 1
        )
        ORDER BY canonical_game.canonical_id
        """,
    )
    fun observePresentGames(): Flow<List<CanonicalLibraryAggregate>>
}
```

Extend `CanonicalLibraryModels.kt` with every remaining type in the “Core types” section. Keep the `CanonicalCardKey` and digest-based `stableComposeKey()` created in Task 2 unchanged, and add `OwnedCopyOperation`, `CopyUnavailableReason`, `CanonicalPublicFailure`, `OwnedCopySummary`, and `CanonicalLibraryCard` in the same package.

- [ ] **Step 4: Register the DAO without a migration**

Add `abstract fun canonicalLibraryDao(): CanonicalLibraryDao` to `PluviaDatabase`, and provide it in `DatabaseModule`. Do not increment version 27 and do not edit exported schema JSON.

- [ ] **Step 5: Run DAO tests and schema guard**

```bash
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.db.dao.CanonicalLibraryDaoTest"
./gradlew :app:testModernDebugUnitTest --tests "app.gamenative.db.dao.CanonicalLibraryDaoTest"
git diff --exit-code -- app/schemas
```

Expected: tests pass and schema diff is empty.

- [ ] **Step 6: Commit and push**

```bash
git add app/src/main/java/app/gamenative/library/canonical/CanonicalLibraryModels.kt \
  app/src/main/java/app/gamenative/db/dao/CanonicalLibraryDao.kt \
  app/src/main/java/app/gamenative/db/PluviaDatabase.kt \
  app/src/main/java/app/gamenative/di/DatabaseModule.kt \
  app/src/test/java/app/gamenative/db/dao/CanonicalLibraryDaoTest.kt
git commit -m "feat: expose canonical library aggregates" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD
```

---

### Task 4: Resolve owned copies into current runtime state

**Files:**
- Create: `app/src/main/java/app/gamenative/library/canonical/runtime/OwnedCopyRuntime.kt`
- Create: `app/src/main/java/app/gamenative/library/canonical/runtime/OwnedCopyRuntimeAdapter.kt`
- Create: `app/src/main/java/app/gamenative/library/canonical/runtime/SteamOwnedCopyRuntimeAdapter.kt`
- Create: `app/src/main/java/app/gamenative/library/canonical/runtime/GogOwnedCopyRuntimeAdapter.kt`
- Create: `app/src/main/java/app/gamenative/library/canonical/runtime/EpicOwnedCopyRuntimeAdapter.kt`
- Create: `app/src/main/java/app/gamenative/library/canonical/runtime/AmazonOwnedCopyRuntimeAdapter.kt`
- Create: `app/src/main/java/app/gamenative/library/canonical/runtime/CustomOwnedCopyRuntimeAdapter.kt`
- Modify: `app/src/main/java/app/gamenative/db/dao/LibraryPlayHistoryDao.kt`
- Modify: `app/src/main/java/app/gamenative/di/CanonicalLibraryModule.kt`
- Create: `app/src/test/java/app/gamenative/library/canonical/runtime/OwnedCopyRuntimeAdapterTest.kt`

- [ ] **Step 1: Write source-matrix tests before production code**

For each source, test available resolution, source facet-set fidelity, account-scope mismatch, entitlement loss, source-row change, and exact source-native `LibraryItem.appId`. Account-backed entitlement loss is `Hidden`; a key proven present in the current lifecycle ledger whose materialized source row/reference has disappeared is `Unavailable(SOURCE_ROW_CHANGED)`. A removed Custom row is `Hidden` because Custom has no separate entitlement ledger. Required expected IDs are:

```text
Steam  -> source prefix STEAM_ followed by the positive Steam AppID
GOG    -> source prefix GOG_ followed by the positive exact-decimal game ID when legacy bridging is supported
Epic   -> source prefix EPIC_ followed by the current local row ID; the durable key remains namespace/catalog
Amazon -> source prefix AMAZON_ followed by the current local row ID; the durable key remains product ID
Custom -> source prefix CUSTOM_GAME_ followed by the persisted local ID
```

A current GOG entitlement with a nonblank but zero, signed, whitespace-padded, overflowing, or non-decimal provider ID remains `OwnedCopyRuntimeResult.Available` with `libraryItem = null`, source-native artwork/facet fields intact, and no executable capabilities; Task 5 maps that sole allowed null-bridge state to summary reason `LEGACY_BRIDGE_UNSUPPORTED`. It is never coerced to zero and never disappears from the public library merely because the legacy screen assumes a positive exact-decimal `Int`. A blank/whitespace-only GOG ID remains excluded by the Stage 1 stable-identity contract and is not fabricated into an `OwnedCopyKey`. Epic DLC/Unreal rows return `Hidden`. Amazon capture requires the current product and entitlement reference. A scope-provider failure returns `Hidden`; another adapter exception after proving exact current-lifecycle ownership returns typed `Unavailable` with exception class only.

Assert the operation matrix from the plan, including absent GOG/Epic update and absent Store Page. Assert the registry rejects mixed-source input and an adapter response whose key set differs from the request, preventing a partial public list. Prove a play-history DAO emission and `notifyVolatileStateChanged(source)` each invalidate card assembly without carrying an app/provider ID. Also make one source runtime invalidation flow throw once, advance virtual time through the one-second retry, and assert collection restarts without terminating the canonical card flow.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.library.canonical.runtime.OwnedCopyRuntimeAdapterTest"
```

Expected: runtime contracts are unresolved.

- [ ] **Step 3: Add runtime contracts and registry**

Use the full `OwnedCopyRuntime`, `OwnedCopyRuntimeResult`, and `OwnedCopyRuntimeAdapter` contracts from the core-types section. Add:

```kotlin
@Singleton
class OwnedCopyRuntimeRegistry @Inject constructor(
    adapters: Set<@JvmSuppressWildcards OwnedCopyRuntimeAdapter>,
    private val playHistoryDao: LibraryPlayHistoryDao,
    private val diagnostics: CanonicalDiagnosticSink,
) {
    private val volatileInvalidations = MutableSharedFlow<GameSource>(extraBufferCapacity = 1)
    private val bySource = adapters.associateBy { it.source }.also { map ->
        check(map.size == adapters.size) { "Duplicate owned-copy runtime adapter" }
        check(map.keys == GameSource.entries.toSet()) { "Missing owned-copy runtime adapter" }
    }

    fun notifyVolatileStateChanged(source: GameSource) {
        volatileInvalidations.tryEmit(source)
    }

    fun invalidations(): Flow<Unit> = merge(
        playHistoryDao.getAll().map { Unit },
        volatileInvalidations.map { Unit },
        *bySource.values.map { adapter ->
            adapter.invalidations().retryWhen { error, attempt ->
                if (error is CancellationException) return@retryWhen false
                diagnostics.invalidationFailed(adapter.source, error::class)
                delay((1_000L shl attempt.coerceAtMost(6).toInt()).coerceAtMost(60_000L))
                true
            }
        }.toTypedArray(),
    )

    suspend fun resolve(key: OwnedCopyKey): OwnedCopyRuntimeResult =
        bySource.getValue(key.source).resolve(key)

    suspend fun resolveAll(
        source: GameSource,
        keys: Set<OwnedCopyKey>,
    ): Map<OwnedCopyKey, OwnedCopyRuntimeResult> {
        require(keys.all { it.source == source })
        return bySource.getValue(source).resolveAll(keys).also { results ->
            check(results.keys == keys) { "Runtime adapter returned an incomplete key set" }
        }
    }
}
```

`GameSource.entries` is allowed only for completeness validation here; presentation/source ordering must use the explicit order in `LibraryCard`.

- [ ] **Step 4: Implement the common account-safe resolution sequence in every adapter**

Each account-backed adapter performs this order:

1. Reject a key for another source.
2. Read current account scope and lifecycle generation. If this fails before current-account proof, return `Hidden`; Task 11 later records only a source/error-class batch failure from this boundary.
3. Return `Hidden` if there is no scope, it differs from `key.accountScope`, or the exact lifecycle ownership snapshot is not ready.
4. Check the exact key against that lifecycle’s ownership ledger. Return `Hidden` when it is absent: entitlement loss must not preserve a public copy.
5. Call the matching Stage 1 adapter’s `resolve(key)` to obtain the current immutable provider reference.
6. If resolution is null despite current-ledger presence, return `Unavailable(SOURCE_ROW_CHANGED)`; Epic rows failing the public visibility predicate remain `Hidden` instead.
7. Load the exact source row identified by the reference, then recheck account scope, lifecycle generation, and ledger presence.
8. Build `OwnedCopyRuntime`, current normalized developer/year/type evidence, source facet sets, artwork, and capabilities from current state using the same normalization/parsing rules as the matching Stage 1 source adapter. Set `libraryItem = null` and capabilities empty only for a current entitlement the legacy bridge cannot represent.
9. Convert `CancellationException` back to cancellation; convert another failure after exact current-ledger proof to `Unavailable(SOURCE_READ_FAILED, error::class)`.

Custom validates its current app-private account scope and matching key but has no lifecycle generation or separate ownership ledger; a missing persisted row is `Hidden`. Each runtime adapter’s `invalidations()` delegates the matching Stage 1 source adapter invalidations so source rows, ownership ledgers, and account lifecycle changes are observed. The registry adds play-history DAO emissions and the categorical manual volatile-state trigger used by existing install/custom-image events.

`resolve(key)` uses the point-read path needed for action capture/revalidation. `resolveAll` validates one source/account generation for the complete immutable key set, loads that source’s materialized rows and ownership snapshot once, maps all keys, then rechecks the same generation once before returning. It must not execute one Room query or launch one coroutine per copy.

- [ ] **Step 5: Implement concrete volatile fields and capabilities**

Add this read-only DAO query; it changes no schema:

```kotlin
@Query("SELECT * FROM library_play_history WHERE app_id = :appId")
suspend fun get(appId: String): LibraryPlayHistory?
```

Use `get(appId)` only for point action resolution. Each source `resolveAll` reads `LibraryPlayHistoryDao.getAll().first()` at most once for its whole key batch and maps by source-prefixed app ID; it must not call the point query per copy.

Use the authoritative fields below; do not persist them in canonical tables:

| Field | Steam | GOG | Epic | Amazon | Custom |
|---|---|---|---|---|---|
| installed | `SteamService.isAppInstalled(appId)` | `GOGGame.isInstalled` | `EpicGame.isInstalled` | `AmazonGame.isInstalled` | true |
| path | current Steam app dir | `installPath` | `installPath` | `installPath` | scanner folder |
| size | current resolved/installed size | `installSize` | `installSize` | `installSize` | current folder size or null |
| version | installed branch | null | `version` | `versionId` | null |
| last played | `LibraryPlayHistoryDao` source-prefixed key | `lastPlayed` | `lastPlayed` | `lastPlayed` | history DAO |
| playtime | current Steam/source value when available | `playTime` | `playTime` | `playTimeMinutes` | null |
| downloading | source download manager | source download manager | source download manager | source download manager | false |
| update | `SteamService.isUpdatePending` | false | false | `AmazonService.isUpdatePendingByAppId` | false |
| shared | current Steam library shared/family flag | false | false | false | false |

Build images exactly as the legacy `LibraryViewModel` does. Use `AmazonArtwork.layoutHeroFromProductJson`, Epic cover/square/portrait precedence, GOG vertical/icon/image precedence, Steam entity URL helpers, and custom scanner images. Runtime adapters may share small private mappers, but they must not call `LibraryViewModel` or duplicate credentials.

Capabilities are calculated from current state, not merely source type:

```kotlin
buildSet {
    if (libraryItem != null) add(OwnedCopyOperation.OPEN_SOURCE_DETAILS)
    if (libraryItem != null && supportsInstall && !isInstalled) add(OwnedCopyOperation.INSTALL)
    if (libraryItem != null && supportsPlay &&
        (isInstalled || source == GameSource.CUSTOM_GAME)
    ) {
        add(OwnedCopyOperation.PLAY)
    }
    if (libraryItem != null && (isDownloading || hasPartialDownload)) {
        add(OwnedCopyOperation.PAUSE_RESUME_DOWNLOAD)
        add(OwnedCopyOperation.CANCEL_DOWNLOAD)
    }
    if (libraryItem != null && supportsUpdate && isInstalled && updateAvailable) {
        add(OwnedCopyOperation.UPDATE)
    }
    if (libraryItem != null && supportsUninstall && (isInstalled || hasPartialDownload)) {
        add(OwnedCopyOperation.UNINSTALL)
    }
    if (libraryItem != null && source == GameSource.STEAM) {
        add(OwnedCopyOperation.EXPORT_SAVES)
        add(OwnedCopyOperation.IMPORT_SAVES)
    }
}
```

- [ ] **Step 6: Bind all five adapters into a Hilt set**

Add one `@Binds @IntoSet` method per runtime adapter in `CanonicalLibraryModule`.

- [ ] **Step 7: Run the matrix in both flavors**

```bash
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.library.canonical.runtime.OwnedCopyRuntimeAdapterTest"
./gradlew :app:testModernDebugUnitTest --tests "app.gamenative.library.canonical.runtime.OwnedCopyRuntimeAdapterTest"
```

Expected: pass with no network calls and no credential values in failures.

- [ ] **Step 8: Commit and push**

```bash
git add app/src/main/java/app/gamenative/library/canonical/runtime \
  app/src/main/java/app/gamenative/db/dao/LibraryPlayHistoryDao.kt \
  app/src/main/java/app/gamenative/di/CanonicalLibraryModule.kt \
  app/src/test/java/app/gamenative/library/canonical/runtime/OwnedCopyRuntimeAdapterTest.kt
git commit -m "feat: resolve canonical copy runtime state" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD
```

---

### Task 5: Assemble canonical cards before filtering and pagination

**Files:**
- Create: `app/src/main/java/app/gamenative/library/canonical/CanonicalLibraryRepository.kt`
- Create: `app/src/test/java/app/gamenative/library/canonical/CanonicalLibraryRepositoryTest.kt`

- [ ] **Step 1: Write failing grouping and visibility tests**

Cover these exact cases:

1. Steam `VERIFIED` plus GOG `HIGH` for one canonical emits one grouped card with two badges/copies.
2. A `REVIEW_REQUIRED` relationship sharing a canonical ID with a verified relationship emits its own `Independent(copyKey)` card.
3. `REJECTED` and `UNMATCHED` are independent.
4. A copy whose runtime result is `Hidden` contributes no title, alias, badge, count, or card.
5. A current-account `Unavailable` copy remains as a disabled stale summary even when it is the card’s only copy; a scope-provider failure is `Hidden`, never `Unavailable`.
6. A current GOG entitlement whose provider ID cannot enter the integer-only legacy bridge remains visible with `LEGACY_BRIDGE_UNSUPPORTED` and no actions.
7. Preferred copy is read only when all three columns parse as one `OwnedCopyKey`; malformed triples become null without rewriting the preference.
8. Card artwork uses the explicit runtime artwork fields: a direct Steam runtime copy, then the canonical primary-source runtime copy, then fixed source order, then the existing empty placeholder. It must not depend on a non-null executor `LibraryItem`, so a current entitlement with an unsupported legacy bridge can still show source artwork. Stage 2 preserves but does not interpret the unversioned `artworkOverrideJson` field.
9. Repeated identical aggregates/runtimes emit equal immutable card lists.
10. Relationships and sources are ordered Steam, GOG, Epic, Amazon, Custom without `GameSource.entries`.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.library.canonical.CanonicalLibraryRepositoryTest"
```

Expected: repository unresolved.

- [ ] **Step 3: Implement the reactive trigger boundary**

```kotlin
@Singleton
class CanonicalLibraryRepository @Inject constructor(
    private val dao: CanonicalLibraryDao,
    private val runtimeRegistry: OwnedCopyRuntimeRegistry,
) {
    fun observeCards(): Flow<List<CanonicalLibraryCard>> = combine(
        dao.observePresentGames(),
        runtimeRegistry.invalidations().onStart { emit(Unit) },
    ) { aggregates, _ -> aggregates }
        .mapLatest(::assemble)
        .distinctUntilChanged()
}
```

Freeze Room lists with `toList()` before suspension, group present keys by source, and call `runtimeRegistry.resolveAll(source, keys)` in at most five concurrent source coroutines. Never launch one coroutine or perform one DAO read per game. Cancellation remains cancellation.

- [ ] **Step 4: Implement trusted grouping exactly**

Flatten present relationships after runtime visibility resolution. Derive key as:

```kotlin
private fun StoreMatchEntity.ownedCopyKey(): OwnedCopyKey = OwnedCopyKey(
    accountScope = AccountScope.parse(accountScope),
    source = source,
    stableSourceId = stableSourceId,
)

private fun cardKey(match: StoreMatchEntity): CanonicalCardKey =
    if (match.confidence == MatchConfidence.VERIFIED ||
        match.confidence == MatchConfidence.HIGH
    ) {
        CanonicalCardKey.Grouped(CanonicalGameId.parse(match.canonicalId))
    } else {
        CanonicalCardKey.Independent(match.ownedCopyKey())
    }
```

Group by this key, never title, list position, artwork URL, Steam candidate, or source-native local row ID. For an independent card use that relationship’s evidence/native title rather than a sibling canonical title. For a grouped card use preference title override then canonical display name.

- [ ] **Step 5: Build safe copy summaries**

Available runtime state supplies volatile fields, capabilities, and `canSeparateMatch = true`. Current-account read failures create a disabled summary using only persisted evidence title/source, `canSeparateMatch = false`, and `unavailableReason`; never copy paths or IDs into diagnostics. `LEGACY_BRIDGE_UNSUPPORTED` remains an available current copy whose runtime artwork/facets can feed card presentation and a later re-resolved unmerge, but it exposes no executable action capabilities. Omit `Hidden` entirely.

Populate `isShared` from current runtime state (Steam’s current family/shared flag; false for sources that do not expose it), and set the card’s `isShared` when any emitted summary is shared. Match recovery in Task 9 re-resolves the runtime key and never reconstructs source metadata/facets from this display summary.

A card’s `ownedSources` is the set of emitted copy summaries. `aliases` includes canonical display name, match evidence display names, and available runtime native titles. Empty strings are removed; values remain in memory only and are not diagnostic attributes.

`steamCollectionAppIds` contains only positive exact-decimal IDs from Steam copy keys. No non-Steam identifier enters this set.

- [ ] **Step 6: Run repository tests in both flavors**

```bash
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.library.canonical.CanonicalLibraryRepositoryTest"
./gradlew :app:testModernDebugUnitTest --tests "app.gamenative.library.canonical.CanonicalLibraryRepositoryTest"
```

Expected: pass.

- [ ] **Step 7: Commit and push**

```bash
git add app/src/main/java/app/gamenative/library/canonical/CanonicalLibraryRepository.kt \
  app/src/test/java/app/gamenative/library/canonical/CanonicalLibraryRepositoryTest.kt
git commit -m "feat: assemble canonical library cards" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD
```

---

### Task 6: Persist a validated preferred copy

**Files:**
- Create: `app/src/main/java/app/gamenative/library/canonical/PreferredCopyRepository.kt`
- Modify: `app/src/main/java/app/gamenative/db/dao/CanonicalPreferenceDao.kt`
- Modify: `app/src/main/java/app/gamenative/db/dao/StoreMatchDao.kt`
- Create: `app/src/test/java/app/gamenative/library/canonical/PreferredCopyRepositoryTest.kt`

- [ ] **Step 1: Write failing transactional tests**

Test:

- a currently present copy belonging to the requested canonical is saved as the complete triple;
- another canonical’s copy is rejected;
- an absent copy is rejected;
- clearing nulls only the preferred triple;
- title/artwork overrides survive set and clear;
- account-scope A cannot select account-scope B’s key;
- DAO or validation failure rolls back;
- a temporarily unavailable runtime does not automatically clear a stored valid relationship preference.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.library.canonical.PreferredCopyRepositoryTest"
```

Expected: repository unresolved.

- [ ] **Step 3: Add explicit DAO reads, not a replacement SQL row**

Add `StoreMatchDao.getPresent(...)` with `is_present = 1`. Keep `CanonicalPreferenceDao.upsert`; do not use an INSERT statement that can null unrelated override columns.

- [ ] **Step 4: Implement transactional set and clear**

```kotlin
@Singleton
class PreferredCopyRepository @Inject constructor(
    private val db: PluviaDatabase,
) {
    suspend fun setPreferredCopy(
        canonicalId: CanonicalGameId,
        key: OwnedCopyKey,
        nowEpochMs: Long,
    ) = db.withTransaction {
        val match = db.storeMatchDao().getPresent(
            key.accountScope.value,
            key.source,
            key.stableSourceId,
        )
        require(match?.canonicalId == canonicalId.value) {
            "Preferred copy is not a present member of the canonical game"
        }
        val existing = db.canonicalPreferenceDao().get(canonicalId.value)
        db.canonicalPreferenceDao().upsert(
            (existing ?: CanonicalGamePreferenceEntity(
                canonicalId = canonicalId.value,
                preferredAccountScope = null,
                preferredSource = null,
                preferredStableSourceId = null,
                titleOverride = null,
                artworkOverrideJson = null,
                updatedAt = nowEpochMs,
            )).copy(
                preferredAccountScope = key.accountScope.value,
                preferredSource = key.source,
                preferredStableSourceId = key.stableSourceId,
                updatedAt = nowEpochMs,
            ),
        )
    }

    suspend fun clearPreferredCopy(
        canonicalId: CanonicalGameId,
        nowEpochMs: Long,
    ) = db.withTransaction {
        val existing = db.canonicalPreferenceDao().get(canonicalId.value) ?: return@withTransaction
        db.canonicalPreferenceDao().upsert(
            existing.copy(
                preferredAccountScope = null,
                preferredSource = null,
                preferredStableSourceId = null,
                updatedAt = nowEpochMs,
            ),
        )
    }
}
```

- [ ] **Step 5: Run tests in both flavors**

```bash
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.library.canonical.PreferredCopyRepositoryTest"
./gradlew :app:testModernDebugUnitTest --tests "app.gamenative.library.canonical.PreferredCopyRepositoryTest"
```

Expected: pass.

- [ ] **Step 6: Commit and push**

```bash
git add app/src/main/java/app/gamenative/library/canonical/PreferredCopyRepository.kt \
  app/src/main/java/app/gamenative/db/dao/CanonicalPreferenceDao.kt \
  app/src/main/java/app/gamenative/db/dao/StoreMatchDao.kt \
  app/src/test/java/app/gamenative/library/canonical/PreferredCopyRepositoryTest.kt
git commit -m "feat: remember preferred owned copies" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD
```

---

### Task 7: Select, capture, and revalidate one exact action target

**Files:**
- Create: `app/src/main/java/app/gamenative/library/canonical/action/OwnedCopyActionRouter.kt`
- Create: `app/src/main/java/app/gamenative/library/canonical/action/OwnedCopyActionGuard.kt`
- Create: `app/src/main/java/app/gamenative/library/canonical/CanonicalPublicLibraryGate.kt`
- Modify: `app/src/main/java/app/gamenative/PrefManager.kt`
- Modify: `app/src/main/java/app/gamenative/di/CanonicalLibraryModule.kt`
- Create: `app/src/test/java/app/gamenative/library/canonical/action/OwnedCopyActionRouterTest.kt`
- Create: `app/src/test/java/app/gamenative/library/canonical/action/OwnedCopyActionGuardTest.kt`

- [ ] **Step 1: Write the router precedence tests**

Use two- and three-copy cards to prove this exact order:

1. explicit key;
2. valid preferred key supporting the operation;
3. unique most-recent installed key for `PLAY` only;
4. sole capable key;
5. chooser.

A disabled public gate fails before adapter resolution. A tied/no-history multi-copy Play result is chooser. Multiple install-capable copies without a valid preference are chooser. A stale preferred copy is remembered but ignored for routing. An explicit key outside the card is `Unavailable(INVALID_EXPLICIT_COPY)`; an explicit member that no longer supports the requested operation is `Unavailable(NO_CAPABLE_COPY)`. Neither explicit failure may fall through to a sibling. Once a key is selected, capture failure—including an available entitlement whose legacy `LibraryItem` bridge is null—returns unavailable and does not try the next key.

- [ ] **Step 2: Write guard revalidation tests**

Capture each source reference, then mutate one fact before `revalidate`:

- lifecycle/account scope;
- ownership presence;
- Steam AppID;
- GOG exact decimal ID;
- Epic local row ID or namespace/catalog;
- Amazon local row ID, product ID, or entitlement ID;
- custom persisted ID;
- installed/download/update capability;
- public gate disabled after capture;
- current entitlement with `libraryItem == null` because the legacy bridge is unsupported.

Every mutation must fail with a fixed reason and zero sibling-adapter calls. Exact unchanged reference plus operation capability returns the current source-native `LibraryItem`.

- [ ] **Step 3: Run and verify red**

```bash
./gradlew :app:testLegacyDebugUnitTest \
  --tests "app.gamenative.library.canonical.action.OwnedCopyActionRouterTest" \
  --tests "app.gamenative.library.canonical.action.OwnedCopyActionGuardTest"
```

Expected: action contracts unresolved.

- [ ] **Step 4: Add fixed result types**

```kotlin
enum class ActionSelectionPolicy { EXPLICIT, PREFERRED, MOST_RECENT_PLAY, SOLE_COPY }

enum class ActionFailureReason {
    INVALID_EXPLICIT_COPY,
    NO_CAPABLE_COPY,
    COPY_UNAVAILABLE,
    TARGET_CHANGED,
    CAPABILITY_CHANGED,
    PREFERENCE_WRITE_FAILED,
    PUBLIC_FEATURE_DISABLED,
}

sealed interface OwnedCopyRouteResult {
    data class Ready(
        val guard: OwnedCopyActionGuard,
        val policy: ActionSelectionPolicy,
        val warning: ActionFailureReason? = null,
    ) : OwnedCopyRouteResult
    data class NeedsChooser(val capableKeys: List<OwnedCopyKey>) : OwnedCopyRouteResult
    data class Unavailable(val reason: ActionFailureReason) : OwnedCopyRouteResult
}

sealed interface ActionRevalidationResult {
    data class Ready(val libraryItem: LibraryItem) : ActionRevalidationResult
    data class Unavailable(val reason: ActionFailureReason) : ActionRevalidationResult
}
```

In the same task add the independent, default-off preference and gate so a captured action can check rollout state at execution time:

```kotlin
private val CANONICAL_PUBLIC_LIBRARY_ENABLED =
    booleanPreferencesKey("canonical_public_library_enabled")
var canonicalPublicLibraryEnabled: Boolean
    get() = getPref(CANONICAL_PUBLIC_LIBRARY_ENABLED, false)
    set(value) = setPref(CANONICAL_PUBLIC_LIBRARY_ENABLED, value)

fun interface CanonicalPublicLibraryGate {
    fun isEnabled(): Boolean
}

@Singleton
class PrefManagerCanonicalPublicLibraryGate @Inject constructor() : CanonicalPublicLibraryGate {
    override fun isEnabled(): Boolean =
        PrefManager.canonicalProjectionEnabled && PrefManager.canonicalPublicLibraryEnabled
}
```

Bind `PrefManagerCanonicalPublicLibraryGate` in `CanonicalLibraryModule`.

- [ ] **Step 5: Implement capture and guard**

The router resolves the selected key once. It constructs a guard only for `OwnedCopyRuntimeResult.Available` with a non-null `libraryItem` and the requested capability still present. A null legacy bridge, changed capability, hidden result, or unavailable result fails the selected target without trying a sibling. A valid runtime is captured as:

```kotlin
class OwnedCopyActionGuard internal constructor(
    val key: OwnedCopyKey,
    private val capturedReference: SourceOwnedCopyReference,
    val initialLibraryItem: LibraryItem,
    private val runtimeRegistry: OwnedCopyRuntimeRegistry,
    private val publicGate: CanonicalPublicLibraryGate,
) {
    suspend fun revalidate(operation: OwnedCopyOperation): ActionRevalidationResult {
        if (!publicGate.isEnabled()) {
            return ActionRevalidationResult.Unavailable(
                ActionFailureReason.PUBLIC_FEATURE_DISABLED,
            )
        }
        return when (val current = runtimeRegistry.resolve(key)) {
            is OwnedCopyRuntimeResult.Available -> when {
                current.copy.reference != capturedReference ->
                    ActionRevalidationResult.Unavailable(ActionFailureReason.TARGET_CHANGED)
                current.copy.libraryItem == null ->
                    ActionRevalidationResult.Unavailable(ActionFailureReason.COPY_UNAVAILABLE)
                operation !in current.copy.capabilities ->
                    ActionRevalidationResult.Unavailable(ActionFailureReason.CAPABILITY_CHANGED)
                else -> ActionRevalidationResult.Ready(current.copy.libraryItem)
            }
            else -> ActionRevalidationResult.Unavailable(ActionFailureReason.COPY_UNAVAILABLE)
        }
    }
}
```

The guard is memory-only. Do not make it `Parcelable`, serializable, a navigation argument, a saved-state value, or a Room entity.

- [ ] **Step 6: Implement deterministic selection**

Check `publicGate.isEnabled()` before inspecting the card or calling an adapter. Then filter `card.copies` to available summaries containing the requested operation. Validate explicit key membership before any preference logic. The most-recent rule applies only when there is exactly one maximum `lastPlayedEpochMs` greater than zero. Sort chooser keys by fixed source order for display only; sorting must not choose.

After successful explicit capture, persist preference only when the Copies-sheet call sets `rememberChoice = true`. A preference-write exception must not retarget or cancel the already safe action; return `Ready(warning = PREFERENCE_WRITE_FAILED)`. Task 11 later instruments that catch boundary using only the exception class, and Task 9 shows the fixed “choice was not remembered” message; no exception message reaches UI.

- [ ] **Step 7: Run action tests in both flavors**

```bash
./gradlew :app:testLegacyDebugUnitTest \
  --tests "app.gamenative.library.canonical.action.OwnedCopyActionRouterTest" \
  --tests "app.gamenative.library.canonical.action.OwnedCopyActionGuardTest"
./gradlew :app:testModernDebugUnitTest \
  --tests "app.gamenative.library.canonical.action.OwnedCopyActionRouterTest" \
  --tests "app.gamenative.library.canonical.action.OwnedCopyActionGuardTest"
```

Expected: pass.

- [ ] **Step 8: Commit and push**

```bash
git add app/src/main/java/app/gamenative/library/canonical/action \
  app/src/main/java/app/gamenative/library/canonical/CanonicalPublicLibraryGate.kt \
  app/src/main/java/app/gamenative/PrefManager.kt \
  app/src/main/java/app/gamenative/di/CanonicalLibraryModule.kt \
  app/src/test/java/app/gamenative/library/canonical/action
git commit -m "feat: capture canonical copy actions safely" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD
```

---

### Task 8: Gate and filter canonical cards in the existing library

**Files:**
- Create: `app/src/main/java/app/gamenative/library/canonical/CanonicalProjectionReadiness.kt`
- Modify: `app/src/main/java/app/gamenative/library/canonical/CanonicalProjectionCoordinator.kt`
- Modify: `app/src/main/java/app/gamenative/ui/model/LibraryViewModel.kt`
- Modify: `app/src/main/java/app/gamenative/ui/data/LibraryState.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/settings/SettingsGroupDebug.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/test/java/app/gamenative/library/canonical/CanonicalProjectionCoordinatorTest.kt`
- Create: `app/src/test/java/app/gamenative/ui/model/CanonicalLibraryViewModelTest.kt`

- [ ] **Step 1: Write gate-off and canonical-filter tests**

Prove:

- gate off calls the unchanged legacy pipeline and emits only `SourceCopy`/`Promotion` cards;
- public gate requires both `canonicalProjectionEnabled` and `canonicalPublicLibraryEnabled`;
- public gate on with no successful projection in the current process stays on the legacy pipeline rather than treating the DAO's initial empty emission as a valid empty library;
- readiness starts false, flips only after `CanonicalProjectionRunner.rebuild` returns successfully, remains false after a failed first rebuild, and stays true after later rebuild or diagnostic failures;
- the first successful projection wakes the canonical branch without requiring a second source invalidation;
- existing `LibraryInstallStatusChanged` and Custom-image callbacks notify the runtime registry by source category only and refresh volatile card state;
- structural canonical assembly failure invokes legacy recovery, publishes no partial list, and retries with capped backoff so a later valid emission restores canonical cards;
- a structurally returned list with duplicate card keys, an empty-copy card, or a key/canonical-ID mismatch uses `INVALID_CARD_STATE` legacy recovery rather than entering Compose;
- `AppFilter.EXPIRED` and the existing Recommended-only context use typed `UNSUPPORTED_LEGACY_CONTEXT` recovery because Stage 1 canonical storage does not model license expiry or recommendation-store cards;
- an empty valid canonical list remains empty and does not fall back;
- All emits one grouped card;
- app-type filters map only `GAME`, `APPLICATION`, `TOOL`, and `DEMO` to their exact `CanonicalAppType`; `DLC`, `SOUNDTRACK`, and `UNKNOWN` are not guessed into another existing filter;
- each source tab emits the grouped card once when one copy belongs to that source;
- source counts are unique card counts before pagination;
- search matches canonical title or source alias;
- installed means any copy installed;
- Shared matches when any current summary is shared and never infers sharing from a non-Steam source;
- recently played uses maximum copy time;
- size uses valid preferred copy, otherwise the sole installed copy, otherwise unknown-last;
- Steam collections match a grouped card through a Steam copy AppID;
- compatibility lookup/fetch uses canonical `displayName` first, then only deterministic sorted aliases already present in the existing cache; no compatibility result is persisted into canonical storage;
- device/GPU stats are looked up for each emitted `(source, nativeTitle)` tuple and combined component-wise by maximum, never summed across duplicate copies; filters/sorts and `LibraryCard.gameStats` use that same aggregate;
- grouping occurs before `take(endIndex)` and duplicate copies never straddle pages.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew :app:testLegacyDebugUnitTest \
  --tests "app.gamenative.library.canonical.CanonicalProjectionCoordinatorTest" \
  --tests "app.gamenative.ui.model.CanonicalLibraryViewModelTest"
```

Expected: readiness type/public branch unresolved and the new coordinator readiness assertions fail.

- [ ] **Step 3: Expose the existing default-off gate in Debug settings**

Task 7 already added and bound `CanonicalPublicLibraryGate`. Add a Debug settings switch backed by `PrefManager.canonicalPublicLibraryEnabled`. Its subtitle states that Stage 1 projection is a prerequisite, a library restart is required, and disabling the switch restores source-native cards. Toggling the public switch must not change `canonicalProjectionEnabled`.

- [ ] **Step 4: Add sticky projection readiness and branch at the ViewModel boundary**

Create the process-local readiness object:

```kotlin
@Singleton
class CanonicalProjectionReadiness @Inject constructor() {
    private val mutableReady = MutableStateFlow(false)

    val isReady: StateFlow<Boolean> = mutableReady.asStateFlow()

    internal fun markSucceeded() {
        mutableReady.value = true
    }
}
```

Inject it into `CanonicalProjectionCoordinator` and call `markSucceeded()` immediately after `runner.rebuild(...)` returns, before diagnostic emission. The signal is intentionally sticky: a later projection/read/diagnostic failure must not hide an already-built cache, whose copies are still checked by the runtime adapters. Cancellation must still escape unchanged. Update every direct coordinator construction in `CanonicalProjectionCoordinatorTest`.

Rename the existing `onFilterApps` body to `onFilterLegacyApps`. Add a wrapper driven by the public gate and `CanonicalProjectionReadiness.isReady`. Collect `CanonicalLibraryRepository.observeCards()` only while both are true, retain the latest immutable list, and call `onFilterCanonicalCards(page)` on updates. Legacy source flows continue running so startup and recovery are immediate. In the existing `LibraryInstallStatusChanged` and `CustomGameImagesFetched` callbacks, also call `runtimeRegistry.notifyVolatileStateChanged` with only the event source (or `CUSTOM_GAME`); never forward the event’s app ID. Gate-on/readiness-false uses the exact legacy pipeline with `MISSING_PROJECTION_PREREQUISITE`; after the first successful rebuild, the readiness emission activates canonical cards without waiting for another source invalidation.

Before retaining a repository emission, validate unique `CanonicalCardKey` values, non-empty copies, `ownedSources == copies.map { it.source }.toSet()`, and grouped/independent key consistency with the card’s canonical/copy identity. An invalid returned list uses `INVALID_CARD_STATE` legacy recovery and is not partially published.

Canonical assembly exceptions set a fixed `ASSEMBLY_FAILED` structural-failure state, record a typed fallback in Task 11, and call the legacy pipeline. Retry the canonical collection after one second with capped exponential backoff up to 60 seconds; preserve `CancellationException`. A later valid aggregate replaces fallback with canonical cards without requiring a gate toggle. `AppFilter.EXPIRED` and the existing Recommended-only context also call the legacy pipeline with `UNSUPPORTED_LEGACY_CONTEXT`; Stage 2 must not guess expiry or reinterpret promotional cards as ownership. Once readiness is true, do not fall back for zero cards, signed-out sources, or unavailable individual copies.

- [ ] **Step 5: Implement filters in the required order**

Within `onFilterCanonicalCards`:

1. Start from repository cards (already grouped).
2. Map active `AppFilter.GAME/APPLICATION/TOOL/DEMO` to exact `CanonicalAppType.GAME/APPLICATION/TOOL/DEMO` and filter; do not coerce `DLC`, `SOUNDTRACK`, or `UNKNOWN`.
3. Apply search over `displayName + aliases`.
4. Apply installed/status, compatibility, statistics, and Steam collections.
5. Compute each source’s unique-card count.
6. Apply All/source-tab admission.
7. Sort cards.
8. Set `totalAppsInFilter`.
9. Apply incremental pagination.
10. Convert to `LibraryCard.canonical` and prepend an existing promotional card only under the unchanged recommendation rules.

For compatibility, request only `displayName` for the page; while that result is absent, consult already-cached aliases in case-insensitive sorted order and use the first known status. Preserve the legacy fail-open behavior when no status is cached. For device/GPU stats, look up every emitted copy by its exact source/native-title provenance and create one `GameCardStats` whose numeric components are the maximum known values (nullable values remain null when all copies are unknown). This is a display/filter aggregate only; never sum provider statistics or write them to Room.

All admission uses enabled source preferences; a grouped All card is admitted when at least one copy source is enabled. Source tabs admit when the card owns that tab’s source. Badges still show every current owned source, not just the source that admitted the card.

- [ ] **Step 6: Run ViewModel tests in both flavors**

```bash
./gradlew :app:testLegacyDebugUnitTest \
  --tests "app.gamenative.library.canonical.CanonicalProjectionCoordinatorTest" \
  --tests "app.gamenative.ui.model.CanonicalLibraryViewModelTest"
./gradlew :app:testModernDebugUnitTest \
  --tests "app.gamenative.library.canonical.CanonicalProjectionCoordinatorTest" \
  --tests "app.gamenative.ui.model.CanonicalLibraryViewModelTest"
```

Expected: pass, including startup readiness and sticky-cache behavior.

- [ ] **Step 7: Re-run gate-off regression tests**

```bash
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.ui.data.LibraryCardTest"
./gradlew :app:testModernDebugUnitTest --tests "app.gamenative.ui.data.LibraryCardTest"
./gradlew --no-parallel :app:compileLegacyDebugKotlin
./gradlew --no-parallel :app:compileModernDebugKotlin
```

Expected: typed card mapping passes, the gate-off cases in `CanonicalLibraryViewModelTest` pass from Step 6, and both variants compile.

- [ ] **Step 8: Commit and push**

```bash
git add app/src/main/java/app/gamenative/library/canonical/CanonicalProjectionReadiness.kt \
  app/src/main/java/app/gamenative/library/canonical/CanonicalProjectionCoordinator.kt \
  app/src/main/java/app/gamenative/ui/model/LibraryViewModel.kt \
  app/src/main/java/app/gamenative/ui/data/LibraryState.kt \
  app/src/main/java/app/gamenative/ui/screen/settings/SettingsGroupDebug.kt \
  app/src/main/res/values/strings.xml \
  app/src/test/java/app/gamenative/library/canonical/CanonicalProjectionCoordinatorTest.kt \
  app/src/test/java/app/gamenative/ui/model/CanonicalLibraryViewModelTest.kt
git commit -m "feat: gate canonical library cards" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD
```

---

### Task 9: Render source badges and the Copies chooser

**Files:**
- Create: `app/src/main/java/app/gamenative/ui/screen/library/components/OwnedSourceBadges.kt`
- Create: `app/src/main/java/app/gamenative/ui/screen/library/components/CanonicalCopiesSheet.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/LibraryScreen.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryAppItem.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryGridCard.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryListCard.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryList.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryListPane.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryCarouselPane.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryDynamicBackdrop.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/components/LibraryDetailPane.kt`
- Modify: `app/src/main/java/app/gamenative/ui/model/LibraryViewModel.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/test/java/app/gamenative/ui/model/CanonicalLibraryViewModelTest.kt`
- Create: `app/src/androidTest/java/app/gamenative/ui/screen/library/CanonicalLibraryScreenTest.kt`

- [ ] **Step 1: Add Compose tests for badges and chooser semantics**

Use test tags `canonical-card`, `owned-source-badges`, `copies-sheet`, `copy-row:STEAM`, `copy-row:GOG`, `copy-row:EPIC`, `copy-row:AMAZON`, `copy-row:CUSTOM_GAME`, `preferred-copy`, and `separate-copy`. Assert:

- a Steam+GOG card has one card and two accessible source labels;
- badges follow Steam, GOG, Epic, Amazon, Custom order;
- selecting a multi-copy card with no preferred copy opens the sheet;
- a preferred or sole copy opens its source details through router capture;
- every canonical card exposes a distinct localized “Copies” action that opens the sheet even when normal selection would route directly;
- each row exposes only current capabilities;
- unavailable copies remain visible but all operation controls are disabled;
- checking “Always use this copy” and then choosing an operation updates the preferred marker; choosing an operation with the control unchecked does not rewrite preference;
- “Use automatic selection” clears the preferred triple without changing title/artwork overrides;
- back closes the sheet before leaving the library;
- gamepad focus enters rows/actions and returns to the originating card;
- an unmerge rechecks the public gate, re-resolves the key, forwards the current runtime developer/year/type/genre/tag/feature values exactly, and blocks after gate/account/entitlement change; a stale unavailable grouped copy exposes no “Separate” control;
- an unmerge/reset transaction failure keeps the sheet open and shows a fixed error without changing local grouping state;
- screen-reader descriptions include source and state but not raw IDs/account scopes.

- [ ] **Step 2: Compile Android tests and verify red**

```bash
./gradlew :app:compileLegacyDebugAndroidTestKotlin
```

Expected: new composables/test tags are unresolved.

- [ ] **Step 3: Render deterministic multi-source badges**

`OwnedSourceBadges` accepts only `List<GameSource>` and emits an icon plus localized content description per source. It never accepts a key, title, or source-native ID. Replace the single-source badge in list/grid/carousel cards when `card.identity is Canonical`; gate-off source cards remain visually unchanged.

- [ ] **Step 4: Add typed selection state**

In `LibraryScreen`, replace `selectedAppId: String?` with:

```kotlin
var selectedCardIdentity by remember { mutableStateOf<LibraryCardIdentity?>(null) }
var selectedSourceItem by remember { mutableStateOf<LibraryItem?>(null) }
var activeActionGuard by remember { mutableStateOf<OwnedCopyActionGuard?>(null) }
var pendingInitialOperation by remember { mutableStateOf<OwnedCopyOperation?>(null) }
```

Pass `onInitialOperationConsumed = { pendingInitialOperation = null }` to the source-screen boundary added in Task 10; the pending operation is memory-only and must be cleared before invocation.

A `SourceCopy` follows the existing detail path. A `Promotion` follows existing recommendation behavior. A `Canonical` asks the ViewModel/router for `OPEN_SOURCE_DETAILS`; `NeedsChooser` opens the sheet, `Ready` stores only the guard’s source-native `initialLibraryItem` and opens the source screen with `pendingInitialOperation = null`, and `Unavailable` shows a fixed localized message and refreshes canonical cards. Add a separate localized “Copies” card/detail action that always opens `CanonicalCopiesSheet` for a canonical identity without invoking router precedence; this is how a user changes or bypasses an existing preferred copy.

- [ ] **Step 5: Build the Copies sheet**

For each `OwnedCopySummary`, show source, source-native title, installed/download state, runtime path, size, branch/version, update state, playtime/last played, preferred marker, and only capability-backed operations. Paths are rendered directly from runtime state but are not written to logs, semantics IDs, diagnostic events, or saved state.

Each row has an unsaved, default-off “Always use this copy” control. An operation calls the router with that explicit key and `rememberChoice` equal to the control’s current value; an unchecked operation must never alter preference merely because the user chose that copy once. When a preferred copy exists, expose “Use automatic selection,” gate-check it, and call `PreferredCopyRepository.clearPreferredCopy` so later actions return to deterministic precedence without changing presentation overrides. `Ready` opens the source screen with guard and initial operation; when `warning == PREFERENCE_WRITE_FAILED`, it also shows the fixed “choice was not remembered” message. `NeedsChooser` cannot occur for an explicit key. `Unavailable` keeps the sheet open, refreshes, and shows a fixed state-changed message. Preference-clear failure likewise keeps the sheet open and shows a fixed message without clearing the marker locally.

- [ ] **Step 6: Add safe wrong-group recovery**

For a non-Steam copy in a grouped card containing at least two copies, show “Separate this copy” with a confirmation dialog only when `copy.canSeparateMatch`; a temporarily unavailable copy remains visible but cannot be separated from stale evidence. On confirmation, first require `CanonicalPublicLibraryGate.isEnabled()`, then have the ViewModel resolve `copy.key` through `OwnedCopyRuntimeRegistry` again. Proceed only for `OwnedCopyRuntimeResult.Available`, and reconstruct the mutation input from that current runtime result—not from stale UI text:

```kotlin
val projection = OwnedCopyProjection(
    key = runtime.key,
    displayName = runtime.nativeTitle,
    developer = runtime.developerKey,
    releaseYear = runtime.releaseYear,
    appType = runtime.appType,
    genreKeys = runtime.genreKeys,
    tagIds = runtime.tagIds,
    featureKeys = runtime.featureKeys,
)
canonicalMutationRepository.unmergeCopy(
    key = runtime.key,
    current = projection,
    nowEpochMs = clock.nowEpochMs(),
)
```

Close the sheet only after the transaction succeeds, then wait for the Room flow to render the new independent card. On failure or a re-resolution that is no longer `Available`, keep current UI state and show a fixed localized error; do not expose the exception message. For a user-rejected independent emitted copy, expose “Reset match decision” through `resetDecision`; require the public gate and re-resolve its key first, blocking `Hidden` (account/entitlement no longer current). Reset needs no source metadata, so a current-account `Unavailable` result still proves the account-scoped key. Inject the existing `CanonicalProjectionClock` into `LibraryViewModel` for mutation timestamps; do not call wall-clock time from a composable.

Do not add candidate search, free-form title matching, or an unverified Steam AppID text field. Record the absence of candidate-search UI in the cross-check; Stage 2 still provides the safety-critical unmerge/reset path and preserves the complete Stage 1 mutation backend.

- [ ] **Step 7: Run source and Android-test compilation**

```bash
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.ui.model.CanonicalLibraryViewModelTest"
./gradlew :app:testModernDebugUnitTest --tests "app.gamenative.ui.model.CanonicalLibraryViewModelTest"
./gradlew --no-parallel :app:compileLegacyDebugKotlin
./gradlew --no-parallel :app:compileModernDebugKotlin
./gradlew --no-parallel :app:compileLegacyDebugAndroidTestKotlin
./gradlew --no-parallel :app:compileModernDebugAndroidTestKotlin
```

Expected: ViewModel routing/mutation tests pass, production variants compile, and Android-test source compiles.

- [ ] **Step 8: Commit and push**

```bash
git add app/src/main/java/app/gamenative/ui/screen/library \
  app/src/main/java/app/gamenative/ui/model/LibraryViewModel.kt \
  app/src/main/res/values/strings.xml \
  app/src/test/java/app/gamenative/ui/model/CanonicalLibraryViewModelTest.kt \
  app/src/androidTest/java/app/gamenative/ui/screen/library/CanonicalLibraryScreenTest.kt
git commit -m "feat: add canonical copies chooser" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD
```

---

### Task 10: Guard source-native execution at the final operation boundary

**Files:**
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/LibraryAppScreen.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/appscreen/BaseAppScreen.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/appscreen/SteamAppScreen.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/appscreen/GOGAppScreen.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/appscreen/EpicAppScreen.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/appscreen/AmazonAppScreen.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/appscreen/CustomGameAppScreen.kt`
- Modify: `app/src/test/java/app/gamenative/library/canonical/action/OwnedCopyActionGuardTest.kt`
- Create: `app/src/test/java/app/gamenative/ui/screen/library/appscreen/CanonicalActionExecutionTest.kt`

- [ ] **Step 1: Add failing execution-boundary tests**

Test both entry modes:

- legacy `guard == null` calls the existing action exactly once;
- canonical guard revalidates immediately before Play, Install, Update, Uninstall, pause/resume, cancel, save export, and save import;
- account/entitlement/reference/capability change blocks the source method and returns a fixed unavailable callback;
- a successful initial check that opens a dialog is insufficient: changing state before confirm blocks the confirm commit;
- changing state while a document picker is open blocks save import/export after URI return;
- blocked execution never calls `onClickPlay`, download/delete/update service methods, `ContainerUtils`, or save transfer;
- process recreation with no in-memory guard does not reconstruct a canonical action; it returns to the Copies sheet/source library.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew :app:testLegacyDebugUnitTest \
  --tests "app.gamenative.library.canonical.action.OwnedCopyActionGuardTest" \
  --tests "app.gamenative.ui.screen.library.appscreen.CanonicalActionExecutionTest"
```

Expected: source screens do not accept a guard.

- [ ] **Step 3: Pass an optional guard without changing legacy calls**

Add optional parameters to `AppScreen` and `BaseAppScreen.Content`:

```kotlin
actionGuard: OwnedCopyActionGuard? = null,
initialOperation: OwnedCopyOperation? = null,
onInitialOperationConsumed: () -> Unit = {},
onCanonicalActionUnavailable: (ActionFailureReason) -> Unit = {},
```

Add one helper in `BaseAppScreen.Content`:

```kotlin
fun executeGuarded(
    operation: OwnedCopyOperation,
    action: (LibraryItem) -> Unit,
) {
    val guard = actionGuard
    if (guard == null) {
        action(libraryItem)
        return
    }
    uiScope.launch {
        when (val result = guard.revalidate(operation)) {
            is ActionRevalidationResult.Ready -> action(result.libraryItem)
            is ActionRevalidationResult.Unavailable ->
                onCanonicalActionUnavailable(result.reason)
        }
    }
}
```

Ensure `action` runs on the main dispatcher when it mutates Compose/source-screen state.

- [ ] **Step 4: Guard dynamic primary actions**

Choose the operation from current screen state:

- installed and not downloading -> `PLAY`;
- active/partial download resume -> `PAUSE_RESUME_DOWNLOAD`;
- not installed -> `INSTALL`;
- delete while downloading/partial -> `CANCEL_DOWNLOAD`;
- delete while installed/leftover -> `UNINSTALL`;
- update -> `UPDATE`.

Run the source method only with the `LibraryItem` returned by revalidation. The selected canonical key or card is never passed down.

- [ ] **Step 5: Guard confirmation commits in source dialogs**

Extend `AdditionalDialogs` with this exact parameter and pass `::executeGuarded` from `Content`:

```kotlin
guardedAction: (
    operation: OwnedCopyOperation,
    action: (LibraryItem) -> Unit,
) -> Unit,
```

At each Steam, GOG, Epic, and Amazon confirmation button, call for example:

```kotlin
guardedAction(OwnedCopyOperation.UNINSTALL) { currentItem ->
    performUninstall(context, currentItem)
}
```

Use the corresponding install/cancel/update operation for the other commits. Initial button checks may still occur for responsiveness, but every confirmation button checks again through this callback.

GOG/Epic update remains absent. Custom canonical UI exposes Play and source details only. Existing legacy calls use the same callback, whose null-guard branch executes exactly as before.

- [ ] **Step 6: Guard save URI callbacks**

In `BaseAppScreen`, wrap `exportSaves` and `importSaves` after the document picker returns and immediately before transfer with `EXPORT_SAVES`/`IMPORT_SAVES`. Do not log URI, title, container path, or key.

- [ ] **Step 7: Trigger one optional initial action safely**

Consume `initialOperation` once with `LaunchedEffect(actionGuard, initialOperation)`. Call `onInitialOperationConsumed()` before any suspension, then invoke the same guarded screen callback used by a visible button; that callback performs the execution-time revalidation. Clearing parent state first prevents recomposition, screen recreation, or a failed revalidation from replaying install/play.

- [ ] **Step 8: Run execution tests in both flavors**

```bash
./gradlew :app:testLegacyDebugUnitTest \
  --tests "app.gamenative.library.canonical.action.OwnedCopyActionGuardTest" \
  --tests "app.gamenative.ui.screen.library.appscreen.CanonicalActionExecutionTest"
./gradlew :app:testModernDebugUnitTest \
  --tests "app.gamenative.library.canonical.action.OwnedCopyActionGuardTest" \
  --tests "app.gamenative.ui.screen.library.appscreen.CanonicalActionExecutionTest"
```

Expected: pass.

- [ ] **Step 9: Prove canonical IDs cannot reach legacy executors**

Add a source scan/regression test that inspects all `LibraryItem` values emitted by runtime adapters and asserts the exact source prefixes. Also run:

```bash
git diff --exit-code de17ce05..HEAD -- \
  app/src/main/java/app/gamenative/ui/PluviaMain.kt \
  app/src/main/java/app/gamenative/sync/FrontendSyncManager.kt \
  app/src/main/java/app/gamenative/data/LibraryItem.kt \
  app/src/main/java/app/gamenative/utils/ContainerUtils.kt \
  app/src/main/java/app/gamenative/utils/IntentLaunchManager.kt
```

Expected: no output. Stage 2 integrates before these boundaries rather than modifying their public identity contracts.

- [ ] **Step 10: Commit and push**

```bash
git add app/src/main/java/app/gamenative/ui/screen/library/LibraryAppScreen.kt \
  app/src/main/java/app/gamenative/ui/screen/library/appscreen \
  app/src/test/java/app/gamenative/library/canonical/action/OwnedCopyActionGuardTest.kt \
  app/src/test/java/app/gamenative/ui/screen/library/appscreen/CanonicalActionExecutionTest.kt
git commit -m "feat: revalidate canonical actions at execution" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD
```

---

### Task 11: Add typed aggregate diagnostics for cards, runtime reads, selection, and revalidation

**Files:**
- Create: `app/src/main/java/app/gamenative/library/canonical/CanonicalLibraryDiagnostics.kt`
- Modify: `app/src/main/java/app/gamenative/diagnostics/DiagnosticEvent.kt`
- Modify: `app/src/main/java/app/gamenative/di/CanonicalLibraryModule.kt`
- Modify: `app/src/main/java/app/gamenative/library/canonical/CanonicalLibraryRepository.kt`
- Modify: `app/src/main/java/app/gamenative/library/canonical/runtime/SteamOwnedCopyRuntimeAdapter.kt`
- Modify: `app/src/main/java/app/gamenative/library/canonical/runtime/GogOwnedCopyRuntimeAdapter.kt`
- Modify: `app/src/main/java/app/gamenative/library/canonical/runtime/EpicOwnedCopyRuntimeAdapter.kt`
- Modify: `app/src/main/java/app/gamenative/library/canonical/runtime/AmazonOwnedCopyRuntimeAdapter.kt`
- Modify: `app/src/main/java/app/gamenative/library/canonical/runtime/CustomOwnedCopyRuntimeAdapter.kt`
- Modify: `app/src/main/java/app/gamenative/library/canonical/action/OwnedCopyActionRouter.kt`
- Modify: `app/src/main/java/app/gamenative/library/canonical/action/OwnedCopyActionGuard.kt`
- Modify: `app/src/main/java/app/gamenative/ui/model/LibraryViewModel.kt`
- Create: `app/src/test/java/app/gamenative/library/canonical/CanonicalLibraryDiagnosticsTest.kt`
- Modify: `app/src/test/java/app/gamenative/library/canonical/runtime/OwnedCopyRuntimeAdapterTest.kt`
- Modify: `app/src/test/java/app/gamenative/library/canonical/action/OwnedCopyActionRouterTest.kt`
- Modify: `app/src/test/java/app/gamenative/library/canonical/action/OwnedCopyActionGuardTest.kt`
- Modify: `app/src/test/java/app/gamenative/ui/model/CanonicalLibraryViewModelTest.kt`

- [ ] **Step 1: Add privacy-first failing tests**

Seed forbidden-looking canonical IDs, account scopes, raw source IDs, titles, paths, URLs, tokens, usernames, and exception messages in test objects. Exercise all new diagnostic calls and export the report. Assert repeated filter callbacks in the same fallback state emit one transition event rather than one event per source emission. Assert none of the seeded private values appears. Assert attributes are limited to:

```text
source, operation, capability, selection_policy, reason,
result_count, canonical_count, copy_count, error_type
```

Duration is allowed only through the event’s typed `durationMs` field, not as a free-form attribute.

Assert diagnostic facade methods cannot accept a title, path, URL, key, scope, ID, or free-form reason at compile time.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.library.canonical.CanonicalLibraryDiagnosticsTest"
```

Expected: diagnostics facade/selection attribute unresolved.

- [ ] **Step 3: Add one allowlisted attribute and typed facade**

Add `SELECTION_POLICY("selection_policy")` to `DiagnosticAttribute`. Define:

```kotlin
interface CanonicalLibraryDiagnosticSink {
    fun cardsProjected(
        resultCount: Int,
        canonicalCount: Int,
        copyCount: Int,
        elapsedMs: Long,
    )
    fun runtimeReadFailed(
        source: GameSource,
        errorClass: KClass<out Throwable>,
    )
    fun legacyFallback(reason: CanonicalPublicFailure, errorClass: KClass<out Throwable>? = null)
    fun routeSelected(
        source: GameSource,
        operation: OwnedCopyOperation,
        policy: ActionSelectionPolicy,
        capableCount: Int,
    )
    fun chooserRequired(operation: OwnedCopyOperation, capableCount: Int)
    fun routeFailed(
        source: GameSource?,
        operation: OwnedCopyOperation,
        reason: ActionFailureReason,
        errorClass: KClass<out Throwable>? = null,
    )
    fun revalidationFailed(
        source: GameSource,
        operation: OwnedCopyOperation,
        reason: ActionFailureReason,
    )
    fun routeSucceeded(source: GameSource, operation: OwnedCopyOperation)
}
```

The production implementation converts only enum `.name`, bounded non-negative counts, durations, and `errorClass.simpleName ?: "UNKNOWN_EXCEPTION"` to `FeatureDiagnostics.record`. It never receives the private values prohibited above. Bind the singleton production implementation to `CanonicalLibraryDiagnosticSink` in `CanonicalLibraryModule`; production repositories, adapters, router, guard, and ViewModel depend only on that interface.

- [ ] **Step 4: Instrument failure boundaries**

Record:

- card assembly aggregate success/failure;
- one runtime-read failure per source batch—or one point failure at a user gesture—using only source and exception class;
- public prerequisite disabled/skipped;
- structural fallback to legacy;
- action selection policy or chooser;
- capture unavailable, including preference-write exception class only;
- revalidation failure reason;
- successful handoff to the source action.

Runtime adapters call `runtimeReadFailed` only at their source-level catch boundaries. `resolveAll` must never record once per key. ViewModel records legacy fallback only when the fixed fallback reason changes and clears that state after a successful canonical publication, so ordinary source-flow churn does not fill the bounded report. Do not record one event per card/copy during a 900-game projection. Card assembly is aggregate; point action events occur only at user gestures.

- [ ] **Step 5: Run diagnostics tests in both flavors**

```bash
./gradlew :app:testLegacyDebugUnitTest \
  --tests "app.gamenative.library.canonical.CanonicalLibraryDiagnosticsTest" \
  --tests "app.gamenative.library.canonical.runtime.OwnedCopyRuntimeAdapterTest" \
  --tests "app.gamenative.library.canonical.action.OwnedCopyActionRouterTest" \
  --tests "app.gamenative.library.canonical.action.OwnedCopyActionGuardTest" \
  --tests "app.gamenative.ui.model.CanonicalLibraryViewModelTest"
./gradlew :app:testModernDebugUnitTest \
  --tests "app.gamenative.library.canonical.CanonicalLibraryDiagnosticsTest" \
  --tests "app.gamenative.library.canonical.runtime.OwnedCopyRuntimeAdapterTest" \
  --tests "app.gamenative.library.canonical.action.OwnedCopyActionRouterTest" \
  --tests "app.gamenative.library.canonical.action.OwnedCopyActionGuardTest" \
  --tests "app.gamenative.ui.model.CanonicalLibraryViewModelTest"
```

Expected: pass and exported seeded private strings are absent.

- [ ] **Step 6: Commit and push**

```bash
git add app/src/main/java/app/gamenative/library/canonical/CanonicalLibraryDiagnostics.kt \
  app/src/main/java/app/gamenative/diagnostics/DiagnosticEvent.kt \
  app/src/main/java/app/gamenative/di/CanonicalLibraryModule.kt \
  app/src/main/java/app/gamenative/library/canonical/CanonicalLibraryRepository.kt \
  app/src/main/java/app/gamenative/library/canonical/runtime \
  app/src/main/java/app/gamenative/library/canonical/action \
  app/src/main/java/app/gamenative/ui/model/LibraryViewModel.kt \
  app/src/test/java/app/gamenative/library/canonical/CanonicalLibraryDiagnosticsTest.kt \
  app/src/test/java/app/gamenative/library/canonical/runtime/OwnedCopyRuntimeAdapterTest.kt \
  app/src/test/java/app/gamenative/library/canonical/action/OwnedCopyActionRouterTest.kt \
  app/src/test/java/app/gamenative/library/canonical/action/OwnedCopyActionGuardTest.kt \
  app/src/test/java/app/gamenative/ui/model/CanonicalLibraryViewModelTest.kt
git commit -m "feat: diagnose canonical action routing" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD
```

---

### Task 12: Validate library scale, routing matrix, UI, and release build

**Files:**
- Create: `app/src/test/java/app/gamenative/library/canonical/CanonicalLibraryScaleTest.kt`
- Extend focused tests from Tasks 1–11 as findings require
- Do not change production behavior unless a failing test proves a defect; each correction is its own commit and push

- [ ] **Step 1: Add the fixed 1,500-copy/900-card fixture**

Reuse Stage 1’s deterministic scale shape: 900 direct Steam copies plus 600 confidently matched non-Steam copies. Resolve runtime state with fixed fake adapters. Assert:

- exactly 900 All cards;
- grouped cards contain correct sources;
- each source count is unique-card count;
- grouping precedes pagination at boundaries 49/50, 99/100, and final page;
- repeat assembly is exactly equal;
- no per-copy coroutine fan-out beyond five source batches;
- no action target, path, title, or account scope enters diagnostics.

Measure and report assembly/filter duration but do not invent the Stage 3 facet p95 contract here.

- [ ] **Step 2: Run all focused Stage 2 tests in both flavors**

```bash
TESTS=(
  app.gamenative.library.canonical.source.OwnedCopySourceAdapterTest
  app.gamenative.library.canonical.CanonicalProjectionCoordinatorTest
  app.gamenative.db.dao.CanonicalLibraryDaoTest
  app.gamenative.library.canonical.runtime.OwnedCopyRuntimeAdapterTest
  app.gamenative.library.canonical.CanonicalLibraryRepositoryTest
  app.gamenative.library.canonical.PreferredCopyRepositoryTest
  app.gamenative.library.canonical.action.OwnedCopyActionRouterTest
  app.gamenative.library.canonical.action.OwnedCopyActionGuardTest
  app.gamenative.library.canonical.CanonicalLibraryDiagnosticsTest
  app.gamenative.ui.data.LibraryCardTest
  app.gamenative.ui.model.CanonicalLibraryViewModelTest
  app.gamenative.ui.screen.library.appscreen.CanonicalActionExecutionTest
  app.gamenative.library.canonical.CanonicalLibraryScaleTest
)
for task in testLegacyDebugUnitTest testModernDebugUnitTest; do
  for test_class in "${TESTS[@]}"; do
    ./gradlew ":app:$task" --tests "$test_class" || exit 1
  done
done
```

Expected: every focused class passes in its own Gradle invocation, avoiding the inherited Windows/Room class-order contamination.

- [ ] **Step 3: Compile Compose tests and both Kotlin variants**

```bash
./gradlew --no-parallel :app:compileLegacyDebugKotlin
./gradlew --no-parallel :app:compileModernDebugKotlin
./gradlew --no-parallel :app:compileLegacyDebugAndroidTestKotlin
./gradlew --no-parallel :app:compileModernDebugAndroidTestKotlin
```

Expected: pass.

- [ ] **Step 4: Prove Room schema/migrations are unchanged**

```bash
git diff --exit-code de17ce05..HEAD -- app/schemas
./gradlew :app:testLegacyDebugUnitTest --tests "app.gamenative.db.migration.RoomMigrationTest"
./gradlew :app:testModernDebugUnitTest --tests "app.gamenative.db.migration.RoomMigrationTest"
```

Expected: no schema diff and focused migration-body tests pass. Device migration instrumentation need not be repeated because Stage 2 adds no entity/index/version; if schema output changes, stop and treat it as an unplanned migration task.

- [ ] **Step 5: Run repository-wide commands and record inherited limitations honestly**

```bash
./gradlew --no-parallel :app:testLegacyDebugUnitTest --continue
./gradlew --no-parallel :app:testModernDebugUnitTest --continue
./gradlew --no-parallel :app:lintLegacyDebug
```

Expected: compare with the recorded Stage 0/1 Windows baseline. No focused Stage 2 failure is acceptable. Do not claim a green global suite if the known path/order/lint failures remain.

- [ ] **Step 6: Build the Legacy release**

```bash
./gradlew :app:assembleLegacyRelease
```

Expected: pass.

- [ ] **Step 7: Run Compose instrumentation only on a separate device**

If deterministic Compose tests require instrumentation, create a temporary AVD named `GameNativeStage2`, launch it explicitly on port 5556, verify `adb -s emulator-5556 get-state` returns `device`, then run:

```bash
ANDROID_SERIAL=emulator-5556 ./gradlew :app:connectedLegacyDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.gamenative.ui.screen.library.CanonicalLibraryScreenTest
```

Expected: all Stage 2 Compose cases pass. Stop and delete only the temporary AVD. Never issue `adb -s emulator-5554`.

- [ ] **Step 8: Commit and push scale/test additions**

```bash
git add app/src/test/java/app/gamenative/library/canonical/CanonicalLibraryScaleTest.kt
git commit -m "test: validate canonical library routing" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD
```

If production corrections were required, commit and push each correction before this test commit with a specific `fix:` message.

---

### Task 13: Run the signed-in multi-store enablement matrix

**Files:**
- Create: `docs/superpowers/reviews/2026-07-29-steam-library-stage-2-live-matrix.md`
- Modify: `app/src/main/java/app/gamenative/PrefManager.kt` only after the matrix passes

- [ ] **Step 1: Prepare a privacy-safe signed-in test profile**

Use an authorized device or a separate temporary AVD. Do not touch `emulator-5554`. Credentials remain in the existing source credential stores and never enter notes, shell history, diagnostics, screenshots intended for publication, or Git.

Enable the Stage 1 projection, let each source complete its exact lifecycle generation, then enable the Stage 2 Debug switch.

- [ ] **Step 2: Execute the card/count matrix**

Record pass/fail and aggregate counts only for:

- Steam only, GOG only, Epic only, Amazon only;
- Steam+GOG, Steam+Epic, Steam+Amazon, and all signed-in sources;
- one verified duplicate pair in All and both source tabs;
- one unmatched/review-required copy remaining independent;
- source badges and unique counts;
- search alias behavior;
- installed/recent/size sorting;
- Expired and Recommended contexts returning to the unchanged legacy representation;
- pagination across at least two pages;
- sign-out removing that account’s cards without exposing stale titles;
- account A -> B -> A showing only the current lifecycle;
- preferred copy remaining scoped to the original `OwnedCopyKey`.

The document records source names, categorical outcomes, counts, and observed duration only. It contains no titles, IDs, scopes, usernames, paths, URLs, or screenshots with private library content.

- [ ] **Step 3: Execute every supported action**

For each `yes` in the Stage 2 operation matrix, verify chooser/capture, source-native screen, operation, and final state. For GOG/Epic update and Store Page, verify no canonical control is shown.

Open a Copies sheet, then change account/sign out before confirming an action; verify the action fails closed and does not choose another copy. Repeat with an install/uninstall confirmation open and with a Steam save document picker open.

- [ ] **Step 4: Validate recovery and diagnostics**

Disable the public switch and verify the legacy library/action paths return unchanged. Re-enable and verify canonical cards return after refresh. Export the feature report manually and inspect it for useful card/action outcomes and absence of every prohibited value.

- [ ] **Step 5: Decide the default from evidence**

If every matrix row passes and no unresolved Critical/High finding remains, change:

```kotlin
get() = getPref(CANONICAL_PUBLIC_LIBRARY_ENABLED, true)
```

Keep the Debug recovery switch so a user can return to source-native cards. Run focused ViewModel/router/guard tests and `assembleLegacyRelease`, then commit and push:

```bash
git add app/src/main/java/app/gamenative/PrefManager.kt \
  docs/superpowers/reviews/2026-07-29-steam-library-stage-2-live-matrix.md
git commit -m "feat: enable canonical library cards" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD
```

If the matrix cannot be executed or has an unresolved Critical/High failure, leave the default false, record the blocker and completed rows honestly, and run:

```bash
git add docs/superpowers/reviews/2026-07-29-steam-library-stage-2-live-matrix.md
git commit -m "docs: record canonical library live matrix" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD
```

Do not begin Stage 3. This is a gate outcome, not a reason to weaken revalidation or silently sample fewer sources.

---

### Task 14: Cross-check Stage 2 against the approved design

**Files:**
- Create: `docs/superpowers/reviews/2026-07-29-steam-library-stage-2-cross-check.md`
- Modify: `docs/superpowers/specs/2026-07-27-steam-normalized-library-design.md` only if implementation evidence changes the approved contract
- Modify: `docs/superpowers/plans/2026-07-27-steam-library-staged-roadmap.md` only if the stage boundary changes

- [ ] **Step 1: Record the exact implementation range and verification evidence**

Use the cross-check template from the staged roadmap. Include commit range, focused Legacy/Modern test results, Compose/instrumentation result, release build, full-suite/lint baseline outcome, schema no-diff, live matrix status, and diagnostic export inspection.

- [ ] **Step 2: Check named design requirements**

Cross-check:

- Sections 5.1 and 7.1 identity boundaries;
- Sections 6.1 `CanonicalLibraryRepository`, source adapters, and action router;
- Section 7.4 preferred-copy persistence;
- Section 9 action precedence;
- Sections 10.1–10.4 cards, copies, counts, and summary state;
- Section 14 stale/error/account behavior;
- Section 15 privacy and manual diagnostics;
- Sections 16–17 fallback and rollout;
- Sections 18.1, 18.3, 18.4, 18.5 tests;
- acceptance criteria 1–6 and the Stage 2 portion of criteria 15, 16, and 18.

- [ ] **Step 3: Record explicit, honest exclusions**

Document:

- rich canonical details remain Stage 4;
- genres/tags/popularity remain Stage 3;
- candidate-search/compare UI is not exposed in Stage 2, while safety-critical separate/reset and Stage 1 transactional mutations remain;
- Store Page is absent because current source screens do not implement a dependable operation;
- GOG/Epic update remains absent because their current handlers are empty;
- extended source-specific tools remain behind the source-detail authority boundary;
- Expired and Recommended contexts deliberately retain the legacy source-entry view because Stage 1 canonical rows do not carry license-expiry or promotional-store identity;
- any inherited Windows test/lint baseline limitation;
- whether default-on was blocked by the signed-in matrix.

If any exclusion contradicts the approved product contract rather than stage timing, update the design before marking the gate passed.

- [ ] **Step 4: Adjudicate findings**

A Critical/High issue in identity separation, duplicate grouping, account visibility, ambiguous selection, execution-time revalidation, legacy fallback, or diagnostics privacy blocks Stage 3. Fix it in its own commit, rerun owning tests, and update the cross-check. Medium/Low findings need an owner and later stage.

- [ ] **Step 5: Commit and push the cross-check**

```bash
git add docs/superpowers/reviews/2026-07-29-steam-library-stage-2-cross-check.md \
  docs/superpowers/specs/2026-07-27-steam-normalized-library-design.md \
  docs/superpowers/plans/2026-07-27-steam-library-staged-roadmap.md
git commit -m "docs: cross-check canonical library stage" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
git push fork HEAD
```

Omit unchanged paths from `git add`. Confirm `git status --short` is empty and `git rev-parse HEAD` matches `git rev-parse fork/codex/steam-normalized-game-details-spec`.

## Stage 2 exit gate

Stage 2 is complete only when:

- Epic snapshot visibility and fallback generations are closed.
- Gate-off legacy cards/actions remain source-native and regression-tested; gate-on stays legacy until the first successful in-process projection rather than publishing a false empty library.
- All/source tabs and counts operate on unique cards after trusted grouping and before pagination.
- Review-required/rejected/unmatched copies remain independent.
- Every card shows current owned sources; every currently validated entitlement remains visible, while a legacy-bridge-unsupported copy is honest and disabled rather than hidden or coerced.
- Preferred copy persists as the complete account-scoped key without overwriting presentation overrides.
- Ambiguous actions open a chooser; no arbitrary source-order tie-break chooses.
- Every canonical-originated core operation captures one source reference and revalidates the same reference at the final execution/confirmation boundary.
- Account/entitlement/reference/capability changes fail closed with no sibling fallback.
- Canonical IDs never enter legacy launch/container/frontend/intent contracts.
- New diagnostics are aggregate, typed, bounded, manual-export only, and privacy-inspected.
- Focused tests pass in both flavors, release build passes, and inherited global failures are recorded rather than hidden.
- The signed-in matrix either passes and enables default-on, or default-off remains with an explicit blocking finding.
- The Stage 2 design cross-check has no unresolved Critical/High finding before Stage 3 planning begins.
