# Official integration host checkpoint — 2026-10-03

## Scope and authority

This checkpoint integrates official `cdd82053255c00ada1f58ae63372576b519867b8` into fork base `1358240396e16d3864d4715901a5c9a55c75ff2b`. It is host-verified, not live accepted and not a production release. The original dirty checkout was not used for implementation. No agents or device work were used.

A fresh origin query during final verification found `8ea964fcfeddf37920305f14a49f98d1a0d45127`, adding AI support/account UI and main-window active-time reporting. Integrate and verify that delta next; this checkpoint does not claim it is the latest upstream state.

## Owning Android tests

Each flavor was run separately, in three bounded foreground groups. All 27 selector patterns matched; the groups cover 33 distinct classes without overlap.

| Group | Legacy | Modern |
| --- | --- | --- |
| Render/ViewModel/discovery/scale | 113 passed | 113 passed |
| Repository/runtime/catalog/action guards and execution | 246 passed | 246 passed |
| Migrations/provider/account/auth/channel/launch/storage | 169 passed, 1 skipped | 169 passed, 1 skipped |
| Total | 528 passed, 1 skipped, 0 failures/errors | 528 passed, 1 skipped, 0 failures/errors |

The single skip per flavor is the Windows `unix:nlink` hardlink test, not an ignored failure. Conservative folder-size fallback tests passed. Android/Linux hardlink behavior remains an acceptance boundary.

Commands use `:app:testLegacyDebugUnitTest` or a separate `:app:testModernDebugUnitTest`, `-ProomSchemaDirectory=D:/Temp/gamenative-merge-schema-7uy0ky0n/schemas`, and the complete selector references in the integration plan. Never place both tasks before one trailing filter set. The attempted background route was denied by the worktree watchdog-registry hook; its watchdog was canceled, and all six final groups ran foreground.

Evidence logs:

- `D:/Temp/gamenative-final-Legacy-render-xf8se06n.log`
- `D:/Temp/gamenative-final-Modern-render-wtkrx64o.log`
- `D:/Temp/gamenative-final-Legacy-authority-zl1vg62c.log`
- `D:/Temp/gamenative-final-Modern-authority-6xq_z5_x.log`
- `D:/Temp/gamenative-final-Legacy-integration-bysv5ja_.log`
- `D:/Temp/gamenative-final-Modern-integration-y33ox2v0.log`

## Database preservation

Target version 29 has 17 exact historical fixture cases through the registered Room builder plus four destructive-recovery cases. Shared versions 17–25 are byte-identical in both pinned histories. Both collided 26/27 shapes, official 28, later fork-26 ownership-ledger rows, all historical table rows/columns, target-schema validation, foreign keys, added defaults, and empty/nonempty recipe/manifest sequences are exercised.

New coverage exposed a real 24→25 defect: rebuilding recipe and overwrite-manifest tables retained surviving IDs but reset deleted-ID high-watermarks. RED reproduced recipe 99→7, manifest 199→7, and empty manifest 199→0. The migration now captures both sequences before rebuild and restores their maxima afterward. The initial setup attempt used Windows Python's `bash` resolution, which launched WSL and could not find the Windows SDK; that was an environment error, not behavioral RED. Final runs explicitly used Git Bash.

Published repository exports 26/27 were freshly byte-compared with fork HEAD. Export 29 was freshly byte-compared with the isolated generated schema. Unsupported versions 7–16 retain their existing destructive-recovery boundary; legacy pending diagnostics are acknowledged under their original identities without discarding unacknowledged markers.

## Native/build boundaries

- Linux-native overlay core bench: AddressSanitizer/UBSan, **246 passed, 0 failed**; no production native edits. Mounted NTFS permission-mode failures were not treated as source defects. Final evidence: `D:/Temp/gamenative-overlay-linux-green-4nx84uo6/test.log` and `compiler.log`. Existing compiler warnings remain.
- `:app:assembleLegacyDebug`: successful, 3m59s (`D:/Temp/gamenative-assemble-Legacy-ZEhp7G.log`).
- `:app:assembleModernDebug`: successful, 1m57s (`D:/Temp/gamenative-assemble-Modern-1TiW3R.log`).
- These builds package prebuilt JNI libraries, not rebuilt Rust/LSFG implementations. AGP reported unable-to-strip warnings and packaged those libraries unchanged.
- Downloader and overlay source/build inputs were byte-equivalent to pinned official state. Packaged `libgndownload.so` (both Legacy ABIs, Modern arm64) and `libgnoverlay.so` (arm64) were byte-compared against repository inputs and the exact official Git blobs.
- Windows Rust is 1.94; WSL Rust/Cargo is 1.75. The full Linux Rust downloader host suite and Android native rebuild were not run. They are explicitly tracked below, not implied by APK assembly.

Debug APK SHA-256:

- Legacy: `e84b935f5f7180ce552e8dc0cc8c498d0391b6de2ddb7260c4717872b2ce3904`
- Modern: `38ac125857271d7dd27b67380651488238dc22eef9759610916df0fa885b69f3`

These APKs are debug previews; no production signer or device acceptance is claimed.

## Architectural audit and remaining acceptance

Preserved boundaries: canonical identity versus exact account-owned-copy actions; hidden/VR presentation without entitlement loss; curated-section intersection and synchronous render supersession; all special launches through PLAY revalidation; canceled/retired provider enrichment rejection; atomic provider credentials; package-derived launch action; side-by-side analytics/updater policy; immutable published schemas; imported proprietary binary restrictions.

Original checkout audit: branch `codex/steam-normalized-game-details-spec`, HEAD `8311ec59f47ac821ce3d31c036b4bc3b6d83961c`, twelve tracked modifications and both media-layout source/test files retained. No unmerged paths. Unstaged whitespace check passed; staged check remained nonzero due to inherited upstream/vendor formatting. No broad vendor formatting rewrite was performed.

- **U01 CLOSED (host):** ten new GOG/Epic provider tests plus account/auth owners pass in both flavors; cancellation assertion allows coroutine exception copying while requiring original causal identity.
- **U03 CLOSED (host):** 17–24 and collided-version preservation paths pass in both flavors.
- **U02 HOST CHECKPOINT VERIFIED:** sanitizer bench, APK assembly and exact imported native binary provenance established. Runtime/native acceptance is not closed.
- **U05 OPEN — native downloader rebuild/suite:** target `app/src/main/cpp/gn-download/rust` and `tools/build-gn-download.sh`; acceptance is a compatible isolated Linux toolchain running the locked host suite and, when native sources are changed or replacement binaries are produced, cross-build/provenance checks for both shipped ABIs. Never bypass package restrictions.
- **U06 OPEN — newer official delta:** target commits `4535ba9ac` and `8ea964fcf`; acceptance requires design-completeness/channel/account/action audit, owning tests and host APK assembly before continuing fork roadmap work.
- **LIVE01 OPEN — integrated live acceptance:** target signed upgrades, real provider snapshots/community/resolver/detail interactions, controller/UI and storage/native behavior; acceptance requires explicit coordinated live-testing authority at the actual gate.

D3 field completeness, L1 safe manual LSFG import, S1–S3 journals/recovery and the existing visible-core ledger remain open. A merge checkpoint or upstream feature name does not close them. Parent task remains IN_PROGRESS; next action is the newer official delta, then the existing roadmap. Publish only to the fork, without force; do not push official origin or declare an unverified release.
