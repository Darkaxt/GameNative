# GameNative Dual-Package Durable Fork Design

**Status:** Approved direction
**Date:** 2026-08-03
**Applies to:** `Darkaxt/GameNative` fork releases

## 1. Goal

Publish a permanent side-by-side GameNative fork that can remain installed beside the official app and upgrade indefinitely without erasing the fork user's accounts, Winlator containers, Wine prefixes, mod state, saves, preferences, or database.

Retain the current same-package fork artifact long enough to preserve its existing upgrade lineage. Keep fork-only package, branding, signing, and release changes out of the future official Steam-first pull request.

## 2. Product contract

The fork publishes two stable tracks:

| Track | Application ID | Purpose |
|---|---|---|
| Compatibility | `app.gamenative` | Upgrades existing Darkaxt fork installations; cannot coexist with official GameNative |
| Side-by-side | `app.gamenative.darkaxt` | Primary testing installation; coexists with official GameNative and upgrades in place forever |

Both tracks use the existing persistent Darkaxt fork signing certificate. Android still treats them as independent update lineages because an update identity is the package name plus signing lineage.

The Kotlin/Java namespace remains `app.gamenative`. Only the side-by-side runtime application ID changes.

The side-by-side package ID and signing identity are permanent after first publication. They must never be renamed, re-signed, or replaced with debug signing.

## 3. Explicit migration boundary

The first `app.gamenative.darkaxt` installation is a clean app installation. One final sign-in and container setup is accepted. Every later side-by-side release must upgrade that installation without clearing its private data.

A differently named and differently signed app cannot read the official app's private sandbox or Android Keystore. Therefore the fork will not claim to migrate the following automatically from an already installed official build:

- account sessions or tokens;
- Room databases or DataStores;
- Wine prefixes and private containers;
- private mod caches, rollback data, or mod-manager records;
- private internal game installations;
- Android Keystore keys.

The transition instructions may use capabilities already available in the official app:

1. Export Steam saves where available.
2. Export desired container configuration JSON.
3. Move reusable game installations to genuinely public/removable storage where supported.
4. Record custom-game folders.
5. Install `app.gamenative.darkaxt`, authenticate once, import supported saves/configuration, and validate a launch.
6. Keep the official app installed until the side-by-side installation is confirmed.

Tokens and credential files are never exported or copied. Reauthentication is mandatory. A future full container exporter requires an official-signed upstream release and is outside this first delivery.

## 4. Data durability contract

Normal Android package upgrades preserve private data only when package and signing identities remain stable. The side-by-side release process therefore enforces all of the following:

- `app.gamenative.darkaxt` is immutable.
- Every production side-by-side APK uses the persistent fork certificate.
- `versionCode` is strictly increasing for every published update.
- Room migrations remain forward compatible; no destructive fallback is introduced for versions produced by side-by-side releases.
- Existing DataStore names, database names, container IDs, and private directory layout remain stable unless an explicit tested migration accompanies the change.
- Release installation is an update, never an uninstall/reinstall instruction.
- CI verifies package name, signing certificate, version code, and upgrade behavior before publication.

The protected state includes:

- `files/imagefs_shared/home/xuser-*` containers and Wine prefixes;
- container `.container` configuration;
- private workshop content and mod caches/backups;
- Room mod metadata and canonical-library data;
- controller profiles;
- app preferences and authenticated sessions created by the side-by-side app;
- internal saves and configurations.

Caches and redownloadable image archives may be repaired independently, but repair must not delete user containers or mod state.

## 5. Runtime package portability

Changing only `applicationId` is unsafe because production code contains `/data/data/app.gamenative` assumptions. Before publishing the side-by-side package, all private paths must derive from the runtime application context.

### 5.1 Kotlin and Java

Private roots use `Context.dataDir`, `Context.filesDir`, `Context.cacheDir`, or a small injected path abstraction. Production code must not construct `/data/data/<package>` strings.

The initial conversion covers at least:

- container media-conversion and E-drive paths;
- DXVK cache paths;
- Wine E-drive repair and path checks;
- Bionic controller shared-memory paths;
- any generated Wine registry or mount entry containing the package-private root.

Existing `FileProvider` authorities continue to use `${applicationId}` or `BuildConfig.APPLICATION_ID`.

### 5.2 Native code

Native components must not embed `app.gamenative`. The Android launcher passes the resolved private root to native processes through one fixed environment variable or an existing launch-configuration boundary. Native code fails closed when that value is absent rather than guessing the official package path.

### 5.3 Imported configuration

Container configuration import must not restore absolute references to another package sandbox. Imported values are validated and reconstructed relative to the receiving app's runtime paths.

A repository check blocks new production occurrences of `/data/data/app.gamenative`, `/data/user/0/app.gamenative`, and equivalent hard-coded private roots.

## 6. Build and branding architecture

Add a permanent fork-side release build type mirrored by the `ubuntufs` dynamic feature, following the existing paired `release-gold` structure. Its contract is:

- application ID suffix: `.darkaxt`;
- production optimizations inherited from `release`;
- no debug signing;
- explicit `BuildConfig` release-channel value;
- distinct app label such as **GameNative Darkaxt**;
- visibly distinct launcher icon/badge so official and fork installations cannot be confused;
- package-aware updater and analytics policy.

The existing `release` build remains unchanged for the compatibility artifact. The exact generated Gradle variant/task names are asserted during implementation before workflow changes are committed.

Tagged fork releases initially publish four APKs:

1. standard compatibility (`app.gamenative`);
2. XR compatibility (`app.gamenative`);
3. standard side-by-side (`app.gamenative.darkaxt`);
4. XR side-by-side (`app.gamenative.darkaxt`).

Modern variants remain outside the tagged-release expansion unless they are already required by the release channel.

Artifact names must contain `compat` or `side-by-side`; release notes must explain that only the side-by-side artifact coexists with official GameNative.

## 7. External storage isolation

Package-private storage is naturally isolated, but GameNative also supports public `<volume>/GameNative` roots. Two installed apps mutating the same public game tree can race during downloads, updates, moves, mod deployment, or deletion.

The side-by-side build therefore defaults new public storage to a distinct root such as `<volume>/GameNative-Darkaxt`. It does not silently adopt the official app's public root.

Users may explicitly select an existing public game folder for reuse only after a warning that both apps must not concurrently update or mod the same files. Internal official-app storage and `Android/data/app.gamenative` are not presented as migratable locations.

The fork's private Wine prefixes and mod-manager state remain isolated even when a public game directory is reused.

## 8. Channel-aware integrations

### 8.1 Updates

A fork build must never offer or install an official-package APK.

The first side-by-side release disables the existing official update endpoint for fork builds. Upgrades are installed from verified Darkaxt GitHub releases. A later in-app fork updater may be enabled only when it:

- selects the correct compatibility or side-by-side artifact;
- validates expected package name before launching PackageInstaller;
- validates the persistent fork signing certificate;
- rejects lower/equal version codes and cross-channel APKs;
- never logs release URLs or private installation paths into diagnostics.

### 8.2 Launch actions and shortcuts

Internal launch actions derive from `BuildConfig.APPLICATION_ID`. Pinned shortcuts explicitly target their own package/component and use the matching channel action. No implicit `app.gamenative.LAUNCH_GAME` intent emitted by the side-by-side app may route into the official installation.

### 8.3 Deep links

Internal proprietary deep links are channel-namespaced where possible. Standardized external schemes such as `nxm://` cannot be renamed without breaking their ecosystem; Android may show a chooser when both apps are installed. Release notes instruct users to select GameNative Darkaxt when they intend to modify the side-by-side installation.

OAuth flows currently completed inside private WebViews remain independent per package and are revalidated in both installations.

### 8.4 Analytics and integrity

Fork builds do not silently pollute official analytics. They either disable PostHog or attach an explicit fixed fork release-channel attribute approved by the diagnostics/privacy contract.

Play Integrity or backend attestation may reject a distinct package/signing identity. Such rejection must degrade only the dependent optional capability and must not clear accounts, containers, or library state.

Local feature diagnostics remain manual-export only and preserve the existing forbidden-data rules.

## 9. Signing and workflow gates

GitHub Actions reconstructs the same persistent fork keystore from the existing fork secrets for both package IDs. Secret values are never printed or exported.

For every published APK, CI verifies:

- expected package ID;
- expected version code/name;
- expected non-secret fork certificate SHA-256;
- APK signature schemes;
- artifact checksum;
- artifact filename/channel agreement.

The workflow fails if two artifacts have the same package ID when one is labeled side-by-side, or if an artifact's signer differs from the persistent fork certificate.

Because dual packaging increases build time, workflows build only release variants actually published and receive a timeout based on observed cold-cache duration. Redundant preflight builds are avoided; the tagged workflow is the authoritative signing/publication gate.

## 10. Upgrade and coexistence validation

Before the first side-by-side public release, use an exclusively owned test device/temporary AVD—not an occupied device—to prove:

1. Compatibility and side-by-side APKs install simultaneously.
2. A representative official/same-ID installation and `app.gamenative.darkaxt` launch independently.
3. A previous side-by-side signed build upgrades to the candidate without uninstall.
4. Sentinel preferences, Room rows, container files, Wine-prefix files, saves, controller profiles, and mod files survive that upgrade.
5. The upgraded container launches and sees its DXVK cache, E-drive, controller bridge, and native evshim paths.
6. Shortcuts launch the originating package.
7. FileProvider sharing uses the originating package authority.
8. Steam, GOG, Epic, Amazon, and Nexus authentication state created in the side-by-side app survives its upgrade.
9. The updater cannot select an official or compatibility APK for the side-by-side package.
10. Public storage defaults are isolated and explicit shared-root selection warns before use.
11. Removing one package does not remove the other package's private state.

The user-facing live gate then asks for one real upgrade over an installed side-by-side prerelease and complaint-driven validation. No release may instruct a user to uninstall the side-by-side app as a routine update step.

## 11. Upstream PR isolation

Fork packaging and Steam-first product work use separate histories:

- An upstream PR branch starts directly from current `origin/master`.
- Only Steam-first feature commits and generic package-portability fixes are reconstructed/cherry-picked onto it.
- Fork signing, package suffix, branding, version bumps, release notes, and workflow artifacts remain on the fork release branch.
- Generic context-derived private-path fixes are upstream-appropriate because they also repair existing alternate application IDs such as `.gold`.

The current fork release branch is not opened directly as the official PR because its history contains fork signing and publication commits.

## 12. Security and privacy

The dual-package work does not weaken Android sandboxing to avoid one-time setup.

It will not:

- request root;
- add `sharedUserId`;
- expose an exported private-data provider;
- enable Android backup for credentials;
- copy token files or Android Keystore ciphertext;
- log package-private paths, account identifiers, tokens, titles, or URLs;
- share mod databases or Wine prefixes between concurrently installed packages.

A future official-to-fork exporter, if upstream accepts one, must use an explicit SAF archive, omit all credentials, preserve filesystem metadata safely, rewrite package-dependent paths, and be designed separately.

## 13. Acceptance criteria

The design is complete when:

1. Existing same-ID fork users retain an upgrade artifact.
2. A stable `app.gamenative.darkaxt` artifact installs beside official GameNative.
3. Side-by-side upgrades preserve all representative private user state without uninstall.
4. Native and Wine paths contain no hard-coded official sandbox dependency.
5. Updates, shortcuts, providers, labels, diagnostics, and release assets are channel-correct.
6. Public storage does not silently create cross-app mutation races.
7. Every APK is signed and verified with the persistent fork identity.
8. Fork-only changes are excluded from the official Steam-first PR.
9. The one-time transition instructions are honest about reauthentication and unsupported private-data migration.
