# Local Windows build storage

Use the tracked launcher for local GameNative builds instead of invoking the wrapper directly:

```powershell
python -B tools/local_gradle.py --plan
python -B tools/local_gradle.py --owner-task "Compile GameNative" -- :app:assembleModernDebug
python -B tools/local_gradle.py --owner-task "Test GameNative" -- :app:testModernDebugUnitTest --tests app.gamenative.build.TestTempIsolationBuildConfigTest
```

The launcher delegates to the installed `gradle-build-gate` skill. Its shared host lock, FIFO queue, resource limits, fail-fast policy, diagnostics and daemon retirement remain in charge. Submit once, retain the original managed operation, and service current owner-support requests promptly. Background launches still require the installed watchdog hook. Optional `--thread-name` must be the actual conversation title, not an invented label.

## Paths

Default storage is `E:/Temp/GameNative/checkout-<canonical-path-hash>/`. The checkout's canonical absolute path determines its stable namespace, so worktrees with the same name do not share intermediates. `--plan` prints paths without creating anything.

| Child | Contents |
| --- | --- |
| `build/_root`, `build/app`, `build/ubuntufs` | Root/module outputs, generated code, APKs, reports, test results and isolated test scratch |
| `project-cache` | Project-local Gradle cache (instead of checkout `.gradle`) |
| `kotlin` | Kotlin plugin persistent state (instead of checkout `.kotlin`) |
| `tmp` | Child TEMP/TMP/TMPDIR, Java scratch and heap dumps |
| `logs` | Unique installed-gate build logs |

For another checkout, invoke this launcher with `--project C:/path/to/GameNative`; the init script travels with the launcher and does not edit that checkout. `--storage-root` may select another external drive. Neither planning nor execution changes global settings, `GRADLE_USER_HOME`, Android SDK/NDK, maintained Room schemas or signing files.

AGP 8.8 unconditionally computes test paths relative to the source drive. The launcher therefore creates one ignored `.gamenative-local-build` **parent junction** pointing to the external `build` folder. Gradle receives explicit module build paths beneath that parent (`_root`, `app`, `ubuntufs`), while their bytes live on E:. This is not an `app/build` junction: `clean` deletes ordinary module children, never the junction itself. The launcher verifies the exact junction target before each run and refuses to replace any existing directory or mismatched link. Python 3.12+ on Windows is required.

CI and direct wrapper/IDE invocations **retain their original paths**. This is deliberately a launcher-scoped policy, not an automatic global/IDE redirection. Future local agent builds should use this launcher; old frozen verification scripts that assume `app/build` must explicitly consume the printed external paths before reuse.

## Other host work

The Windows XR native helpers honor child `GAMENATIVE_BUILD_ROOT` for their intermediate directories and retain their old defaults without it. Packaged native payloads remain maintained inputs under `app/src`. This does not itself run or qualify XR toolchains.

For standalone tools, use explicit output/cache options and an E:-based temporary directory. The launcher does not intercept unrelated shell commands: standalone Cargo/gnoverlay/provisioning scripts and OpenComposite staging are not automatically rerouted. Do not move SDKs or shared caches, or delete source-local native output, merely because a directory is named `build`.

Temporary output is expendable only after consumers finish. Inspect exact targets and use the installed `transactional-cleanup` helper; preserve required acceptance evidence and signing material, not obsolete APK copies. No Android device or new release is required to validate host-path changes.

## Verified checkpoint — 2026-10-16

- Ten launcher tests passed, including write-free planning, isolated checkout keys, override rejection, existing-directory protection, and once-only gate/exit propagation. Both Windows native helper scripts parsed and their default/external path branches were checked; native compilation was not run.
- With Gradle 9.8.0 / AGP 8.8.0 / Kotlin 2.1.21: external `clean`, debug-manifest generation, the focused `TestTempIsolationBuildConfigTest` (one passing case), and `assembleModernDebug` passed. The parent junction survived clean; all module build paths resolved to E:.
- A 259,935,231-byte debug APK was generated and verified on E:, then removed as unused qualification output. The production-signed RC22 APK for the separate pending tablet test was preserved. No Android action or production release occurred.
- Configuration-cache creation and subsequent reuse passed. Each invocation acquired the shared gate and verified daemon exit. The full build observed a 3 GiB Gradle heap with project-default in-process Kotlin, two workers/no parallel, and one 1 GiB test fork with fail-fast/no-ignore. The cache replay skips configuration-time profile markers; it is not a fresh profile observation. Native job hints are not measured native-concurrency guarantees.
- Qualification also exposed and fixed the debug asset merge's missing explicit dependency on `copyDebugManifest`; no runtime application behavior or release version was changed.
