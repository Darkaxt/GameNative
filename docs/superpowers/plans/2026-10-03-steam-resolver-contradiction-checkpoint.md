# Steam Resolver Contradiction Checkpoint Implementation Plan

> **For agentic workers:** Execute inline using executing-plans. Do not spawn subagents. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent a corroborating release year from overriding contradictory known developer/publisher evidence during automatic Steam matching.

**Architecture:** Repair only the pure candidate policy's automatic-acceptance gate. Preserve candidate ranking, review/manual correction, unknown-evidence handling, publisher corroboration, source-native actions, sticky existing decisions, and the current resolver version. Do not automatically detach historical matches; inspecting their impact requires the later live acceptance gate.

**Tech Stack:** Kotlin, JUnit 4, existing Gradle Legacy/Modern unit-test variants.

**Authority:** Existing visible-core specification Section 6.3, requirement 3; user-approved reconstruction roadmap on 2026-10-03. This checkpoint does not replace Stages 4–8 or close their unresolved ledger items.

## Execution checkpoint — 2026-10-03

Tasks 1–2 are implemented with actual-source JVM RED/GREEN evidence (32 tests, two expected pre-fix failures, then 32 passes). The Android Gradle task is blocked before tests by missing cached dependencies; its Legacy/Modern gates remain open rather than being checked off. `tools/verify-steam-catalog-policy.py` provides the bounded JVM fallback using the installed Gradle distribution. The focused report and new ledger R13 record the exact verification limits and live follow-up. No Android build/device/provider test was counted as passed.

## Task 1: Reproduce the automatic-acceptance defect

**Files:**
- Modify: `app/src/test/java/app/gamenative/library/canonical/catalog/SteamCatalogCandidatePolicyTest.kt`

- [ ] Add an equal-year regression using the existing helpers:

```kotlin
@Test
fun matchingYearDoesNotOverrideDeveloperConflict() {
    val result = policy.evaluate(
        source = source(title = "Example", developer = "Studio A", year = 2020),
        candidates = listOf(candidate(42, "Example", "Studio B", 2020)),
    )
    assertEquals(CatalogDecision.ReviewRequired(listOf(42)), result)
}
```

- [ ] Add equivalent publisher-only contradiction coverage and positive guards for absent candidate-party evidence and publisher corroboration with a matching year. Expected results are respectively `ReviewRequired(listOf(42))`, `AutoAccept(42)`, and `AutoAccept(42)`.
- [ ] Run the owning policy class offline in Legacy:

```bash
./gradlew --offline --no-daemon --no-parallel :app:testLegacyDebugUnitTest --tests 'app.gamenative.library.canonical.catalog.SteamCatalogCandidatePolicyTest'
```

Expected before the repair: the equal-year contradiction tests fail with actual `AutoAccept(42)`. Environment/compilation failure is not RED behavioral evidence.

## Task 2: Repair the acceptance gate and verify

**Files:**
- Modify: `app/src/main/java/app/gamenative/library/canonical/catalog/SteamCatalogCandidatePolicy.kt`

- [ ] Retain the existing developer/publisher normalization and add:

```kotlin
val developerConflict = sourceDeveloperKey.isNotEmpty() &&
    candidateDeveloperKeys.isNotEmpty() &&
    !developerExact
```

Carry `developerConflict` in `ScoredCandidate`, set it in `score`, and require `!top.developerConflict` in `selectDecision` before auto-acceptance. Do not change scores, ordering, ambiguity selection, or explicit manual confirmation.

- [ ] Run the policy and normalization owning classes offline in Legacy, then Modern:

```bash
./gradlew --offline --no-daemon --no-parallel :app:testLegacyDebugUnitTest --tests 'app.gamenative.library.canonical.catalog.SteamCatalogCandidatePolicyTest' --tests 'app.gamenative.library.canonical.catalog.SteamCatalogNormalizationTest'
./gradlew --offline --no-daemon --no-parallel :app:testModernDebugUnitTest --tests 'app.gamenative.library.canonical.catalog.SteamCatalogCandidatePolicyTest' --tests 'app.gamenative.library.canonical.catalog.SteamCatalogNormalizationTest'
```

Expected: all selected tests pass, including bare title/year, unknown-party, publisher-match, closest-prior-year, edition-conflict, and developer-conflict cases. Do not run opt-in live providers, instrumentation, or broad suites.

## Task 3: Record and preserve the checkpoint

**Files:**
- Modify: `docs/superpowers/specs/2026-08-08-steam-resolution-community-visible-core-design.md` (append dedicated R13 row; retain every unresolved row)
- Create: `docs/superpowers/reviews/2026-10-03-steam-resolver-contradiction-checkpoint.md`

- [ ] Record root cause, exact RED/GREEN commands/results, preserved boundaries, and remaining live acceptance in R13 and the focused report. No release/device success claim follows from host tests.
- [ ] Inspect the complete checkpoint diff once against the named requirement, then commit only this checkpoint and push `fork/feat/minor-medium-backlog` without force. Preserve the dirty main checkout.
- [ ] Continue host-side Stage 4 work only after this checkpoint's host gate is settled. Pause before any live endpoint/device acceptance; upstream synchronization remains required before a future release.
