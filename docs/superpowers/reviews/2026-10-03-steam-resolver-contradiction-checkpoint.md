# Steam Resolver Contradiction Checkpoint

**Date:** 2026-10-03
**Base:** `52533d4ebd05609f9a1453c6af0af51b9d83a697`, `feat/minor-medium-backlog`
**Authority:** Visible-core specification Section 6.3, requirement 3; ledger R13.

## Root cause and narrow correction

The POC-scoring port (`75bdf6b29`) allowed exact title (0.56), same year (0.14), and compatible game type (0.10) to meet the 0.80 automatic threshold even when known source developer evidence contradicted all available candidate developer/publisher keys. The previous conflict regression had no release year, so it did not exercise this boundary.

`SteamCatalogCandidatePolicy` now carries an explicit normalized party-conflict flag and vetoes automatic acceptance when it is true. Scores/ranking and review candidates are unchanged. Missing source/candidate party evidence is not a contradiction. A source party matching the candidate publisher remains corroboration. Existing accepted/manual/direct identities, resolver version, guarded mutations, ownership and source actions are untouched.

## Fresh host verification

The planned Android command stopped during configuration, before tests:

```bash
./gradlew --offline --no-daemon --no-parallel :app:testLegacyDebugUnitTest --tests 'app.gamenative.library.canonical.catalog.SteamCatalogCandidatePolicyTest'
```

Outcome: missing cached Android/Hilt/Kotlin plugin dependencies. The wrapper downloaded Gradle 8.12.1 despite the task's offline flag. This is not behavioral RED or a passing Android variant gate. Modern Android execution was not attempted because it shares the missing configuration dependencies.

A small reusable JVM-only runner compiles the actual production policy/models/normalization/enum sources and their actual JUnit classes, with no replacement models or mocks. It uses Gradle's bundled Kotlin 2.0.21 compiler, JUnit 4.13.2 and Java 21; it does not establish the project's Kotlin 2.1.21/Android variant build gate. Generated classes use an automatically removed, uniquely owned directory under `D:/Temp`.

```bash
python tools/verify-steam-catalog-policy.py \
  --gradle-home 'C:/Users/darka/.gradle/wrapper/dists/gradle-8.12.1-bin/eumc4uhoysa37zql93vfjkxy0/gradle-8.12.1' \
  --temp-root D:/Temp
```

- **RED before the production edit:** 32 tests, 2 failures. `matchingYearDoesNotOverrideDeveloperConflict` and `matchingYearDoesNotOverridePublisherOnlyConflict` each returned `AutoAccept(42)` instead of `ReviewRequired([42])`.
- **GREEN after the production edit:** 32 tests passed, zero failures.
- Positive regressions cover unknown candidate-party evidence and matching publisher/year; existing tests cover unknown source evidence, title/edition/type, prior-year ambiguity, and publisher-as-developer corroboration.
- `git diff --check`: passed.

## Inline diff-to-design check

The correction restores the existing no-contradiction acceptance requirement without weakening title/type/edition/score/margin gates. It preserves explicit manual correction and does not automatically rewrite historical decisions. No network provider, account authority, source action, database schema, UI or release identity changes are included.

## Remaining acceptance

R13 stays open for the integrated Android gate and a later explicitly authorized live acceptance checkpoint, including checking whether historical automatic identities were affected. No current device, real-catalog accuracy, full-suite, APK or release claim is made here. Stages 4–8 and the dirty main-checkout work remain preserved.
