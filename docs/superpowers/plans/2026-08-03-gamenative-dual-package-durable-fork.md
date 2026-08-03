# GameNative Dual-Package Durable Fork Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish a permanently upgradeable `app.gamenative.darkaxt` prerelease beside the supported `app.gamenative` compatibility track, while preserving fork login, Winlator containers, prefixes, mods, saves, and settings across every later side-by-side upgrade.

**Architecture:** First contain unit-test temporary files and replace every package-specific private path with context-derived runtime paths. Then add a paired `releaseDarkaxt` build type, fork channel policy, isolated public-storage root, channel-scoped intents, and disabled official updater/analytics. The authoritative tagged workflow builds, signs, identifies, and publishes four APKs; a separately owned AVD proves coexistence and state-preserving replacement before publication.

**Tech Stack:** Kotlin 2.1.21, Java 17, Android Gradle Plugin, Jetpack Compose, Room, DataStore, Winlator Java/native code, Gradle test tasks, GitHub Actions, bundletool, apksigner, adb.

---

## Delivery rules

- Work in place on `codex/steam-normalized-game-details-spec`; do not create a worktree.
- Before each release, fetch `origin` and integrate official upstream changes before tagging.
- Push every task commit to `fork/codex/steam-normalized-game-details-spec` immediately.
- Never push directly to official `origin`.
- Every delegated worker prompt must include: **“IMPORTANT: Do not invoke Agent and do not spawn/delegate to any subagents. Work alone.”**
- Run one implementation worker per task. Do not run implementation workers in parallel.
- Do not run repeated review loops. After Task 7, run exactly one focused Critical/High release-blocker review and permit at most one correction commit.
- Never touch occupied `emulator-5554`. Task 8 starts only after claiming a separate temporary AVD for this thread.
- Never print GitHub secret values or the keystore. Production APKs must use the persistent fork certificate.
- Keep fork-only package suffix, branding, versioning, signing, release workflow, and notes out of the later official PR.
- Preserve the diagnostics privacy contract: do not persist or export tokens, account IDs, SteamIDs, usernames, game titles, match-candidate titles, search text, install paths, full URLs, review bodies, or discussion bodies.

## File map

### Test hygiene

- Modify `app/build.gradle.kts` — force every Gradle JVM test task to use and clean a task-specific directory under `app/build/test-tmp`.
- Create `app/src/test/java/app/gamenative/build/TestTempIsolationBuildConfigTest.kt` — source regression for build-local temp containment.

### Runtime package portability

- Create `app/src/main/java/com/winlator/core/RuntimePaths.java` — the single Java-accessible boundary for private storage, imagefs, media-conversion, drive, DXVK, and gamepad paths.
- Modify `app/src/main/java/com/winlator/container/Container.java` — remove package-private path constants; use an empty drive sentinel until a `Context` resolves defaults.
- Modify `app/src/main/java/com/winlator/container/ContainerData.kt` — use the same empty drive sentinel.
- Modify `app/src/main/java/app/gamenative/PrefManager.kt` — resolve the default drive mapping from the initialized application context.
- Modify `app/src/main/java/app/gamenative/utils/ContainerUtils.kt` — normalize imported/default drive mappings before persistence.
- Modify `app/src/main/java/app/gamenative/utils/IntentLaunchManager.kt` — treat blank drive overrides as “not supplied.”
- Modify `app/src/main/java/com/winlator/core/DXVKHelper.java` — derive cache path from `ImageFs`.
- Modify `app/src/main/java/com/winlator/core/WineUtils.java` — repair E-drive paths against `Context.dataDir` and avoid logging paths.
- Modify `app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt` — request media-conversion variables from `RuntimePaths`.
- Modify `app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java` — use the runtime gamepad directory and pass `EVSHIM_BASE_PATH`.
- Modify `app/src/main/cpp/evshim/evshim.c` — fail closed when `EVSHIM_BASE_PATH` is absent.
- Create `app/src/test/java/com/winlator/core/RuntimePathsTest.kt` — owning path and import-rebasing tests.
- Modify `app/src/test/java/com/winlator/core/WineUtilsTest.kt` — E-drive repair regression.
- Create `app/src/test/java/app/gamenative/build/PackagePrivatePathGuardTest.kt` — repository-wide production-source guard.

### Side-by-side build and channel policy

- Modify `app/build.gradle.kts` — add `releaseDarkaxt`, immutable `.darkaxt` suffix, label/icon placeholders, channel fields, updater/analytics policy, and public-root name.
- Modify `ubuntufs/build.gradle.kts` — mirror `releaseDarkaxt`.
- Modify `app/src/main/AndroidManifest.xml` — package-aware launch action, internal deep-link host, round icon, and alias icon placeholders.
- Create `app/src/main/res/mipmap-anydpi-v26/ic_launcher_darkaxt.xml` and `ic_launcher_darkaxt_round.xml` — visibly distinct adaptive icon using the existing alternate-color artwork.
- Create `app/src/test/java/app/gamenative/build/DarkaxtBuildContractTest.kt` — source contract for package, label, icon, channel fields, and dynamic-feature parity.
- Create `app/src/main/java/app/gamenative/utils/ReleaseChannelPolicy.kt` — pure policy used by updater and analytics initialization.
- Modify `app/src/main/java/app/gamenative/PluviaApp.kt` — do not initialize official PostHog in the side-by-side channel.
- Modify `app/src/main/java/app/gamenative/utils/UpdateChecker.kt` — return without network access when the official updater is disabled.
- Modify `app/src/main/java/app/gamenative/utils/ShortcutUtils.kt` and `IntentLaunchManager.kt` — derive launch action from `BuildConfig.APPLICATION_ID`.
- Modify `app/src/main/java/app/gamenative/utils/StorageUtils.kt` — default side-by-side public installs to `GameNative-Darkaxt`.
- Create `app/src/test/java/app/gamenative/utils/ReleaseChannelPolicyTest.kt`, `IntentLaunchManagerTest.kt`, and `StorageUtilsTest.kt` — pure channel, action, and storage tests.

### Release and validation

- Modify `.github/workflows/tagged-release.yml` — build/sign/verify four channel-labelled APKs and publish accurate notes.
- Create `tools/verify-release-apks.sh` — reusable package/version/signer/signature/checksum gate.
- Create `tools/tests/verify-release-apks-test.sh` — fixture-driven filename/package mapping test using fake Android tools.
- Create `docs/testing/gamenative-dual-package-upgrade-matrix.md` — exact AVD ownership and validation record template.
- Create `docs/releases/gamenative-darkaxt-transition.md` — honest one-time setup and future in-place-upgrade instructions.
- Create `docs/upstream/gamenative-steam-first-pr-boundary.md` — exact upstream-eligible versus fork-only commit boundary.

---

### Task 1: Contain all JVM test temporary files

**Files:**
- Modify: `app/build.gradle.kts:1-2,225-229`
- Create: `app/src/test/java/app/gamenative/build/TestTempIsolationBuildConfigTest.kt`

- [ ] **Step 1: Write the failing source regression**

```kotlin
package app.gamenative.build

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TestTempIsolationBuildConfigTest {
    @Test
    fun unitTestsUseBuildLocalTemporaryDirectoriesWithCleanup() {
        val buildScript = File(repositoryRoot(), "app/build.gradle.kts").readText()

        assertTrue(buildScript.contains("tasks.withType<Test>().configureEach"))
        assertTrue(buildScript.contains("buildDirectory.dir(\"test-tmp/\$name\")"))
        assertTrue(buildScript.contains("systemProperty(\"java.io.tmpdir\""))
        assertTrue(buildScript.contains("isolatedTmpDir.deleteRecursively()"))
    }

    private fun repositoryRoot(): File = generateSequence(
        File(checkNotNull(System.getProperty("user.dir"))),
    ) { it.parentFile }.first { File(it, "app/src/main/java").isDirectory }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
./gradlew :app:testLegacyDebugUnitTest --tests app.gamenative.build.TestTempIsolationBuildConfigTest --no-daemon --no-parallel
```

Expected: FAIL because `tasks.withType<Test>().configureEach` is absent.

- [ ] **Step 3: Configure every JVM test task to use a build-local root**

Add `import org.gradle.api.tasks.testing.Test` and this top-level block after `android { ... }`:

```kotlin
tasks.withType<Test>().configureEach {
    val isolatedTmpDir = layout.buildDirectory.dir("test-tmp/$name").get().asFile
    systemProperty("java.io.tmpdir", isolatedTmpDir.absolutePath)
    doFirst {
        isolatedTmpDir.deleteRecursively()
        check(isolatedTmpDir.mkdirs() || isolatedTmpDir.isDirectory) {
            "Could not create isolated test temp directory"
        }
    }
    doLast {
        isolatedTmpDir.deleteRecursively()
    }
}
```

This contains leaks after failed assertions under `app/build`, cleans leftovers at the next run, and removes them after normal completion. Do not individually rewrite dozens of existing `createTempDirectory` tests in this task.

- [ ] **Step 4: Verify GREEN and prove the JVM property**

Run:

```bash
./gradlew :app:testLegacyDebugUnitTest --tests app.gamenative.build.TestTempIsolationBuildConfigTest --no-daemon --no-parallel
```

Expected: PASS. Confirm any temporary directory created by the test worker is below `app/build/test-tmp/testLegacyDebugUnitTest`, not `%LOCALAPPDATA%/Temp`.

- [ ] **Step 5: Commit and push the checkpoint**

```bash
git add app/build.gradle.kts app/src/test/java/app/gamenative/build/TestTempIsolationBuildConfigTest.kt
git commit -m "test: contain GameNative JVM temp files"
git push fork HEAD:codex/steam-normalized-game-details-spec
```

### Task 2: Introduce context-derived Winlator runtime paths

**Files:**
- Create: `app/src/main/java/com/winlator/core/RuntimePaths.java`
- Create: `app/src/test/java/com/winlator/core/RuntimePathsTest.kt`
- Modify: `app/src/main/java/com/winlator/container/Container.java:50-58,90`
- Modify: `app/src/main/java/com/winlator/container/ContainerData.kt:27`
- Modify: `app/src/main/java/app/gamenative/PrefManager.kt:55-58,373-378`
- Modify: `app/src/main/java/app/gamenative/utils/ContainerUtils.kt:451-498,657-675,861-887`
- Modify: `app/src/main/java/app/gamenative/utils/IntentLaunchManager.kt:204,286`

- [ ] **Step 1: Write failing runtime-path tests**

```kotlin
@RunWith(RobolectricTestRunner::class)
class RuntimePathsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun privateStorageAndGamepadPathsFollowRuntimeDataDirectory() {
        assertEquals(File(context.dataDir, "storage"), RuntimePaths.storageDir(context))
        assertEquals(File(context.filesDir, "gamepad_shm"), RuntimePaths.gamepadSharedMemoryDir(context))
        assertTrue(RuntimePaths.defaultDrives(context).contains("E:${context.dataDir}/storage"))
        assertFalse(RuntimePaths.defaultDrives(context).contains("app.gamenative/storage"))
    }

    @Test
    fun importedDriveMappingsAreRebasedWithoutRetainingAnotherSandbox() {
        val imported = "D:/storage/emulated/0/DownloadE:/data/user/0/another.package/storage"
        assertEquals(
            "D:/storage/emulated/0/DownloadE:${context.dataDir}/storage",
            RuntimePaths.rebasePrivateStorageDrive(context, imported),
        )
    }

    @Test
    fun blankImportedDrivesResolveToRuntimeDefaults() {
        assertEquals(RuntimePaths.defaultDrives(context), RuntimePaths.resolveDrives(context, ""))
    }

    @Test
    fun importedPrivatePathsAreRebasedToTheReceivingSandbox() {
        assertEquals(
            "${context.dataDir}/files/imagefs/home/xuser/tool.exe",
            RuntimePaths.rebasePrivatePath(
                context,
                "/data/data/another.package/files/imagefs/home/xuser/tool.exe",
            ),
        )
    }
}
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```bash
./gradlew :app:testLegacyDebugUnitTest --tests com.winlator.core.RuntimePathsTest --no-daemon --no-parallel
```

Expected: compilation FAIL because `RuntimePaths` does not exist.

- [ ] **Step 3: Add the focused Java path boundary**

Create `RuntimePaths.java` with these public operations and no stored global `Context`:

```java
public final class RuntimePaths {
    private static final Pattern PRIVATE_ROOT = Pattern.compile(
            "/data/(?:data|user/0)/[^/]+"
    );

    private RuntimePaths() {}

    public static File storageDir(Context context) {
        return new File(context.getDataDir(), "storage");
    }

    public static File gamepadSharedMemoryDir(Context context) {
        return new File(context.getFilesDir(), "gamepad_shm");
    }

    public static String defaultDrives(Context context) {
        return "D:" + Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                + "E:" + storageDir(context).getAbsolutePath();
    }

    public static String rebasePrivatePath(Context context, String value) {
        if (value == null) return "";
        Matcher matcher = PRIVATE_ROOT.matcher(value);
        return matcher.replaceAll(Matcher.quoteReplacement(context.getDataDir().getAbsolutePath()));
    }

    public static String rebasePrivateStorageDrive(Context context, String drives) {
        return rebasePrivatePath(context, drives);
    }

    public static String resolveDrives(Context context, String drives) {
        String rebased = rebasePrivateStorageDrive(context, drives);
        return rebased.isBlank() ? defaultDrives(context) : rebased;
    }
}
```

Include the required Android, `File`, regex imports. Do not accept a package name as input; the runtime `Context` is authoritative.

- [ ] **Step 4: Remove static private-root defaults and normalize all entry paths**

Apply these exact semantics:

```java
// Container.java
public static final String DEFAULT_DRIVES = "";
private String drives = DEFAULT_DRIVES;
```

```kotlin
// PrefManager.kt
private lateinit var appContext: Context

fun init(context: Context) {
    appContext = context.applicationContext
    dataStore = appContext.datastore
    // retain existing migration body
}

var drives: String
    get() = RuntimePaths.resolveDrives(appContext, getPref(DRIVES, ""))
    set(value) { setPref(DRIVES, RuntimePaths.resolveDrives(appContext, value)) }
```

At `ContainerUtils.applyToContainer`, normalize every imported package-private field before assigning it:

```kotlin
container.drives = RuntimePaths.resolveDrives(context, containerData.drives)
container.envVars = RuntimePaths.rebasePrivatePath(context, containerData.envVars)
container.executablePath = RuntimePaths.rebasePrivatePath(context, containerData.executablePath)
container.installPath = RuntimePaths.rebasePrivatePath(context, containerData.installPath)
```

When a custom `ContainerData` has blank drives, copy the current `PrefManager.drives`; otherwise normalize its supplied mappings. In `IntentLaunchManager`, use `""` when JSON omits drives and merge with:

```kotlin
drives = override.drives.ifBlank { base.drives }
```

This makes imported configuration package-neutral instead of serializing the receiving package path into a static default.

- [ ] **Step 5: Run owning tests**

Run:

```bash
./gradlew :app:testLegacyDebugUnitTest \
  --tests com.winlator.core.RuntimePathsTest \
  --tests app.gamenative.utils.IntentLaunchManagerTest \
  --no-daemon --no-parallel
```

Expected: PASS. If `IntentLaunchManagerTest` does not yet exist on the branch, run only `RuntimePathsTest` here; Task 6 creates the action-focused test.

- [ ] **Step 6: Commit and push the generic checkpoint**

```bash
git add app/src/main/java/com/winlator/core/RuntimePaths.java \
  app/src/main/java/com/winlator/container/Container.java \
  app/src/main/java/com/winlator/container/ContainerData.kt \
  app/src/main/java/app/gamenative/PrefManager.kt \
  app/src/main/java/app/gamenative/utils/ContainerUtils.kt \
  app/src/main/java/app/gamenative/utils/IntentLaunchManager.kt \
  app/src/test/java/com/winlator/core/RuntimePathsTest.kt
git commit -m "fix: derive Winlator storage paths at runtime"
git push fork HEAD:codex/steam-normalized-game-details-spec
```

### Task 3: Convert launch-time Java and native paths and block regressions

**Files:**
- Modify: `app/src/main/java/com/winlator/core/DXVKHelper.java:18-24`
- Modify: `app/src/main/java/com/winlator/core/WineUtils.java:39-64`
- Modify: `app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt:3727`
- Modify: `app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java:188-239`
- Modify: `app/src/main/cpp/evshim/evshim.c:80-89`
- Modify: `app/src/test/java/com/winlator/core/RuntimePathsTest.kt`
- Modify: `app/src/test/java/com/winlator/core/WineUtilsTest.kt`
- Create: `app/src/test/java/app/gamenative/build/PackagePrivatePathGuardTest.kt`

- [ ] **Step 1: Add failing path and repository-guard tests**

Add tests asserting:

```kotlin
@Test
fun mediaAndDxvkPathsStayUnderResolvedImageFs() {
    val root = File(context.filesDir, "custom-imagefs")
    val paths = RuntimePaths.mediaConversionEnvVars(root)
    assertTrue(paths.all { it.substringAfter('=').startsWith(root.absolutePath) })
    assertEquals(
        File(root, ImageFs.CACHE_PATH.removePrefix("/")).absolutePath,
        RuntimePaths.dxvkCachePath(root),
    )
}
```

Create the repository guard:

```kotlin
class PackagePrivatePathGuardTest {
    @Test
    fun productionSourcesContainNoOfficialPrivateRoot() {
        val root = repositoryRoot()
        val forbidden = listOf(
            "/data/data/" + "app.gamenative",
            "/data/user/0/" + "app.gamenative",
        )
        val violations = File(root, "app/src/main").walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java", "c", "cpp", "h", "xml") }
            .flatMap { file ->
                file.readLines().asSequence().mapIndexedNotNull { index, line ->
                    forbidden.firstOrNull(line::contains)?.let {
                        "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1}"
                    }
                }
            }.toList()
        assertTrue("Hard-coded package roots: ${violations.joinToString()}", violations.isEmpty())
    }

    private fun repositoryRoot(): File = generateSequence(
        File(checkNotNull(System.getProperty("user.dir"))),
    ) { it.parentFile }.first { File(it, "app/src/main").isDirectory }
}
```

- [ ] **Step 2: Run and verify RED**

Run:

```bash
./gradlew :app:testLegacyDebugUnitTest \
  --tests com.winlator.core.RuntimePathsTest \
  --tests app.gamenative.build.PackagePrivatePathGuardTest \
  --no-daemon --no-parallel
```

Expected: FAIL listing the remaining Java/native official roots.

- [ ] **Step 3: Complete `RuntimePaths` launch helpers**

Add:

```java
public static String[] mediaConversionEnvVars(File imageFsRoot) {
    File home = new File(imageFsRoot, "home/xuser");
    return new String[] {
        "MEDIACONV_AUDIO_DUMP_FILE=" + new File(home, "audio.dmp"),
        "MEDIACONV_VIDEO_DUMP_FILE=" + new File(home, "video.dmp"),
        "MEDIACONV_VIDEO_TRANSCODED_FILE=" + new File(home, "transcoded.mkv"),
        "MEDIACONV_AUDIO_TRANSCODED_FILE=" + new File(home, "transcoded.wav"),
        "MEDIACONV_BLANK_AUDIO_FILE=" + new File(home, "blank.wav"),
        "MEDIACONV_BLANK_VIDEO_FILE=" + new File(home, "blank.mkv"),
    };
}

public static String dxvkCachePath(File imageFsRoot) {
    return new File(imageFsRoot, ImageFs.CACHE_PATH.replaceFirst("^/", "")).getAbsolutePath();
}
```

- [ ] **Step 4: Replace each launch-time hard-coded path**

Use `RuntimePaths.dxvkCachePath(imageFs.getRootDir())` in `DXVKHelper`.

In `WineUtils`, normalize the existing drive string once, add missing D/E from `RuntimePaths.defaultDrives(context)`, create the E target only when its canonical path equals `RuntimePaths.storageDir(context).getCanonicalPath()`, and replace the full-path log with fixed text:

```java
Log.d("WineUtils", "Container drive mapping repaired");
```

In `XServerScreen`, iterate:

```kotlin
for (envVar in RuntimePaths.mediaConversionEnvVars(ImageFs.find(context).rootDir)) {
    val parts = envVar.split("=", limit = 2)
    if (parts.size == 2) envVars.put(parts[0], parts[1])
}
```

In `BionicProgramLauncherComponent`, obtain `Context` before creating files, create each `gamepad*.mem` under `RuntimePaths.gamepadSharedMemoryDir(context)`, and set:

```java
envVars.put("EVSHIM_BASE_PATH", context.getFilesDir().getAbsolutePath());
```

In `evshim.c`, remove the official fallback:

```c
if (!base || !*base) {
    out[0] = '\0';
    return;
}
```

Every caller of `build_gamepad_dir` must return an error without creating/opening a file when the result is empty.

- [ ] **Step 5: Extend `WineUtilsTest` for repair behavior**

Mock a container with a legacy `/data/user/0/old.package/storage` E drive, call `createDosdevicesSymlinks`, and verify `container.setDrives(...)` receives the runtime `context.dataDir/storage` path and no old package path.

- [ ] **Step 6: Run targeted generic-path tests**

Run:

```bash
./gradlew :app:testLegacyDebugUnitTest \
  --tests com.winlator.core.RuntimePathsTest \
  --tests com.winlator.core.WineUtilsTest \
  --tests app.gamenative.build.PackagePrivatePathGuardTest \
  --no-daemon --no-parallel
```

Expected: PASS with no forbidden production path.

- [ ] **Step 7: Commit and push the upstream-eligible path fix**

```bash
git add app/src/main/java/com/winlator/core/RuntimePaths.java \
  app/src/main/java/com/winlator/core/DXVKHelper.java \
  app/src/main/java/com/winlator/core/WineUtils.java \
  app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt \
  app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java \
  app/src/main/cpp/evshim/evshim.c \
  app/src/test/java/com/winlator/core/RuntimePathsTest.kt \
  app/src/test/java/com/winlator/core/WineUtilsTest.kt \
  app/src/test/java/app/gamenative/build/PackagePrivatePathGuardTest.kt
git commit -m "fix: make Winlator launch paths package-neutral"
git push fork HEAD:codex/steam-normalized-game-details-spec
```

Record this commit SHA as upstream-eligible.

### Task 4: Add the permanent side-by-side build identity and branding

**Files:**
- Modify: `app/build.gradle.kts:58-85,164-196`
- Modify: `ubuntufs/build.gradle.kts:36-48`
- Modify: `app/src/main/AndroidManifest.xml:35-43,61-83,110-129`
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher_darkaxt.xml`
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher_darkaxt_round.xml`
- Create: `app/src/test/java/app/gamenative/build/DarkaxtBuildContractTest.kt`

- [ ] **Step 1: Write the failing build-contract test**

The test reads both Gradle scripts and the manifest and asserts these exact contracts:

```kotlin
assertTrue(appGradle.contains("create(\"releaseDarkaxt\")"))
assertTrue(appGradle.contains("applicationIdSuffix = \".darkaxt\""))
assertTrue(appGradle.contains("resValue(\"string\", \"app_name\", \"GameNative Darkaxt\")"))
assertTrue(appGradle.contains("buildConfigField(\"String\", \"RELEASE_CHANNEL\", \"\\\"darkaxt-side-by-side\\\"\")"))
assertTrue(featureGradle.contains("create(\"releaseDarkaxt\")"))
assertTrue(manifest.contains("${'$'}{applicationId}.LAUNCH_GAME"))
assertTrue(manifest.contains("${'$'}{internalDeepLinkHost}"))
assertTrue(manifest.contains("${'$'}{icon}"))
assertTrue(manifest.contains("${'$'}{altIcon}"))
```

- [ ] **Step 2: Run and verify RED**

```bash
./gradlew :app:testLegacyDebugUnitTest --tests app.gamenative.build.DarkaxtBuildContractTest --no-daemon --no-parallel
```

Expected: FAIL because `releaseDarkaxt` and placeholders are absent.

- [ ] **Step 3: Add immutable channel fields and the paired build type**

In `defaultConfig`, add:

```kotlin
buildConfigField("String", "RELEASE_CHANNEL", "\"compatibility\"")
buildConfigField("boolean", "OFFICIAL_UPDATER_ENABLED", "true")
buildConfigField("boolean", "OFFICIAL_ANALYTICS_ENABLED", "true")
buildConfigField("String", "PUBLIC_INSTALL_DIR_NAME", "\"GameNative\"")
manifestPlaceholders["internalDeepLinkHost"] = "pluvia"
manifestPlaceholders["altIcon"] = "@mipmap/ic_launcher_alt"
```

Add:

```kotlin
create("releaseDarkaxt") {
    initWith(getByName("release"))
    matchingFallbacks += listOf("release")
    applicationIdSuffix = ".darkaxt"
    resValue("string", "app_name", "GameNative Darkaxt")
    buildConfigField("String", "RELEASE_CHANNEL", "\"darkaxt-side-by-side\"")
    buildConfigField("boolean", "OFFICIAL_UPDATER_ENABLED", "false")
    buildConfigField("boolean", "OFFICIAL_ANALYTICS_ENABLED", "false")
    buildConfigField("String", "PUBLIC_INSTALL_DIR_NAME", "\"GameNative-Darkaxt\"")
    manifestPlaceholders["internalDeepLinkHost"] = "pluvia-darkaxt"
    manifestPlaceholders["icon"] = "@mipmap/ic_launcher_darkaxt"
    manifestPlaceholders["roundIcon"] = "@mipmap/ic_launcher_darkaxt_round"
    manifestPlaceholders["altIcon"] = "@mipmap/ic_launcher_darkaxt"
}
```

Mirror it in `ubuntufs`:

```kotlin
create("releaseDarkaxt") {
    initWith(getByName("release"))
    matchingFallbacks += listOf("release")
}
```

Do not alter namespace `app.gamenative` or the existing compatibility `release` application ID.

- [ ] **Step 4: Make manifest channels package-aware**

Use:

```xml
<application
    android:icon="${icon}"
    android:roundIcon="${roundIcon}"
    android:label="@string/app_name">
```

Change the internal deep-link host to `${internalDeepLinkHost}`, the launch action to `${applicationId}.LAUNCH_GAME`, the default alias icon to `${icon}`, and the alternate alias icon to `${altIcon}`. Keep the standardized `nxm` scheme unchanged.

Create both adaptive-icon XML resources using the already shipped alternate-color foreground/background drawables so the side-by-side installation is visibly different without adding binary artwork:

```xml
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_gold_background" />
    <foreground android:drawable="@drawable/ic_launcher_gold_foreground" />
</adaptive-icon>
```

- [ ] **Step 5: Verify exact generated variants before CI work**

Run:

```bash
./gradlew :app:tasks --all --no-daemon | rg "bundleLegacy(Release|ReleaseDarkaxt)|bundleLegacyXr(Release|ReleaseDarkaxt)"
./gradlew :app:processLegacyReleaseDarkaxtMainManifest :app:processLegacyXrReleaseDarkaxtMainManifest --no-daemon --no-parallel
```

Expected task names:

- `bundleLegacyRelease`
- `bundleLegacyXrRelease`
- `bundleLegacyReleaseDarkaxt`
- `bundleLegacyXrReleaseDarkaxt`

Expected: both manifest tasks succeed. If AGP generates a different task name, update this plan and the workflow to the observed name before proceeding; do not guess in CI.

- [ ] **Step 6: Run GREEN contract test**

```bash
./gradlew :app:testLegacyDebugUnitTest --tests app.gamenative.build.DarkaxtBuildContractTest --no-daemon --no-parallel
```

Expected: PASS.

- [ ] **Step 7: Commit and push fork-only packaging**

```bash
git add app/build.gradle.kts ubuntufs/build.gradle.kts app/src/main/AndroidManifest.xml \
  app/src/main/res/mipmap-anydpi-v26/ic_launcher_darkaxt.xml \
  app/src/main/res/mipmap-anydpi-v26/ic_launcher_darkaxt_round.xml \
  app/src/test/java/app/gamenative/build/DarkaxtBuildContractTest.kt
git commit -m "feat: add permanent Darkaxt package channel"
git push fork HEAD:codex/steam-normalized-game-details-spec
```

Record this and later commits as fork-only.

### Task 5: Isolate the side-by-side public install root

**Files:**
- Modify: `app/src/main/java/app/gamenative/utils/StorageUtils.kt:101-147`
- Modify: `app/src/main/java/app/gamenative/ui/screen/library/appscreen/SteamAppScreen.kt:1032`
- Create: `app/src/test/java/app/gamenative/utils/StorageUtilsTest.kt`

- [ ] **Step 1: Write failing root-policy tests**

```kotlin
class StorageUtilsTest {
    @Test
    fun sideBySideRootUsesChannelName() {
        val appFiles = File("/storage/1234/Android/data/app.gamenative.darkaxt/files")
        assertEquals(
            File("/storage/1234/GameNative-Darkaxt"),
            StorageUtils.publicInstallRoot(appFiles, "GameNative-Darkaxt"),
        )
    }

    @Test
    fun compatibilityRootRemainsUnchanged() {
        val appFiles = File("/storage/1234/Android/data/app.gamenative/files")
        assertEquals(
            File("/storage/1234/GameNative"),
            StorageUtils.publicInstallRoot(appFiles, "GameNative"),
        )
    }

    @Test
    fun officialRootIsClassifiedAsSharedForSideBySideChannel() {
        assertTrue(StorageUtils.isSharedCompatibilityRoot(File("/storage/1234/GameNative")))
        assertFalse(StorageUtils.isSharedCompatibilityRoot(File("/storage/1234/GameNative-Darkaxt")))
    }
}
```

- [ ] **Step 2: Run and verify RED**

```bash
./gradlew :app:testLegacyDebugUnitTest --tests app.gamenative.utils.StorageUtilsTest --no-daemon --no-parallel
```

Expected: compilation FAIL because the overload and classifier do not exist.

- [ ] **Step 3: Make the root name channel-driven**

Implement:

```kotlin
fun publicInstallRoot(
    appFilesDir: File,
    installDirName: String = BuildConfig.PUBLIC_INSTALL_DIR_NAME,
): File? {
    val path = appFilesDir.absolutePath
    val idx = path.indexOf("/Android/data/")
    if (idx <= 0) return null
    return File(path.substring(0, idx), installDirName)
}

fun isSharedCompatibilityRoot(root: File): Boolean = root.name == "GameNative"
```

Keep `preferredInstallRoot` and `resolveLegacyGameDir` calling the default overload so they receive the current build channel automatically. Replace the direct `GameNative/Steam` fallback in `SteamAppScreen` with `File(StorageUtils.publicInstallRoot(...), "Steam")` or the already selected `PrefManager.externalStoragePath`; no UI path may recreate a hard-coded compatibility root in the side build.

Do not add automatic migration from `GameNative` to `GameNative-Darkaxt`. The first side-by-side install is clean, and the release notes explicitly require choosing/importing reusable public content. If a future folder picker offers the compatibility root, it must call `isSharedCompatibilityRoot` and show a concurrent-update/mod warning before saving it.

- [ ] **Step 4: Run owning tests**

```bash
./gradlew :app:testLegacyDebugUnitTest --tests app.gamenative.utils.StorageUtilsTest --no-daemon --no-parallel
```

Expected: PASS.

- [ ] **Step 5: Commit and push**

```bash
git add app/src/main/java/app/gamenative/utils/StorageUtils.kt \
  app/src/main/java/app/gamenative/ui/screen/library/appscreen/SteamAppScreen.kt \
  app/src/test/java/app/gamenative/utils/StorageUtilsTest.kt
git commit -m "fix: isolate Darkaxt public game storage"
git push fork HEAD:codex/steam-normalized-game-details-spec
```

### Task 6: Make updater, analytics, shortcuts, and deep links channel-aware

**Files:**
- Create: `app/src/main/java/app/gamenative/utils/ReleaseChannelPolicy.kt`
- Modify: `app/src/main/java/app/gamenative/PluviaApp.kt:118-130`
- Modify: `app/src/main/java/app/gamenative/utils/UpdateChecker.kt:24-53`
- Modify: `app/src/main/java/app/gamenative/utils/IntentLaunchManager.kt:18-35`
- Modify: `app/src/main/java/app/gamenative/utils/ShortcutUtils.kt:89-101`
- Create: `app/src/test/java/app/gamenative/utils/ReleaseChannelPolicyTest.kt`
- Create or modify: `app/src/test/java/app/gamenative/utils/IntentLaunchManagerTest.kt`

- [ ] **Step 1: Write failing pure policy tests**

```kotlin
class ReleaseChannelPolicyTest {
    @Test
    fun sideBySideDisablesOfficialNetworkChannels() {
        val policy = ReleaseChannelPolicy(
            applicationId = "app.gamenative.darkaxt",
            releaseChannel = "darkaxt-side-by-side",
            officialUpdaterEnabled = false,
            officialAnalyticsEnabled = false,
        )
        assertFalse(policy.mayCheckOfficialUpdates)
        assertFalse(policy.mayInitializeOfficialAnalytics)
        assertEquals("app.gamenative.darkaxt.LAUNCH_GAME", policy.launchAction)
    }
}
```

Add an intent test that supplies the expected action as a parameter and proves an official action is rejected for a side-by-side parser.

- [ ] **Step 2: Run and verify RED**

```bash
./gradlew :app:testLegacyDebugUnitTest \
  --tests app.gamenative.utils.ReleaseChannelPolicyTest \
  --tests app.gamenative.utils.IntentLaunchManagerTest \
  --no-daemon --no-parallel
```

Expected: compilation FAIL because `ReleaseChannelPolicy` and parameterized action parsing are absent.

- [ ] **Step 3: Add one pure release policy**

```kotlin
data class ReleaseChannelPolicy(
    val applicationId: String,
    val releaseChannel: String,
    val officialUpdaterEnabled: Boolean,
    val officialAnalyticsEnabled: Boolean,
) {
    val launchAction: String = "$applicationId.LAUNCH_GAME"
    val mayCheckOfficialUpdates: Boolean = officialUpdaterEnabled && releaseChannel == "compatibility"
    val mayInitializeOfficialAnalytics: Boolean = officialAnalyticsEnabled && releaseChannel == "compatibility"

    companion object {
        fun current() = ReleaseChannelPolicy(
            applicationId = BuildConfig.APPLICATION_ID,
            releaseChannel = BuildConfig.RELEASE_CHANNEL,
            officialUpdaterEnabled = BuildConfig.OFFICIAL_UPDATER_ENABLED,
            officialAnalyticsEnabled = BuildConfig.OFFICIAL_ANALYTICS_ENABLED,
        )
    }
}
```

- [ ] **Step 4: Wire channel boundaries without broad PostHog call-site churn**

`PluviaApp` initializes and registers PostHog only inside:

```kotlin
if (ReleaseChannelPolicy.current().mayInitializeOfficialAnalytics) {
    // existing PostHog setup and app_opened capture
}
```

The SDK remains uninitialized in side-by-side builds, making existing direct captures local no-ops rather than sending fork events to official analytics.

`UpdateChecker.checkForUpdate` begins with:

```kotlin
if (!ReleaseChannelPolicy.current().mayCheckOfficialUpdates) return@withContext null
```

No request is created and no official URL is logged in the side channel. Do not add a fork updater in this release.

Expose `IntentLaunchManager.launchAction(applicationId: String = BuildConfig.APPLICATION_ID)` and compare against that value. `ShortcutUtils` uses the same method and explicitly scopes the intent:

```kotlin
val intent = Intent(IntentLaunchManager.launchAction()).apply {
    setPackage(BuildConfig.APPLICATION_ID)
    component = ComponentName(context, MainActivity::class.java)
    // retain existing extras
}
```

The manifest changes from Task 4 already namespace the proprietary filter and internal deep link; `nxm://` remains unchanged and may show Android's chooser.

- [ ] **Step 5: Run owning tests and compile both channels**

```bash
./gradlew :app:testLegacyDebugUnitTest \
  --tests app.gamenative.utils.ReleaseChannelPolicyTest \
  --tests app.gamenative.utils.IntentLaunchManagerTest \
  --no-daemon --no-parallel
./gradlew :app:compileLegacyReleaseKotlin :app:compileLegacyReleaseDarkaxtKotlin --no-daemon --no-parallel
```

Expected: tests PASS and both compilation tasks succeed.

Before Task 7 raises the version code, create the nonproduction v27 side-by-side baseline used only for the AVD upgrade proof:

```bash
./gradlew :app:bundleLegacyReleaseDarkaxt --no-daemon --no-parallel
java -jar tools/bundletool-all-1.17.2.jar build-apks \
  --bundle=app/build/outputs/bundle/legacyReleaseDarkaxt/app-legacy-releaseDarkaxt.aab \
  --output="$CLAUDE_JOB_DIR/tmp/gamenative-side-baseline-v27.apks" \
  --mode=universal
unzip -p "$CLAUDE_JOB_DIR/tmp/gamenative-side-baseline-v27.apks" universal.apk \
  > "$CLAUDE_JOB_DIR/tmp/gamenative-side-baseline-v27.apk"
```

Expected: the baseline identifies as `app.gamenative.darkaxt`, version code 27, and is signed by the local bundletool/debug test key. It is never published. Task 8 builds the v28 test candidate with the same local key to prove a real increasing-version upgrade; production artifacts are verified separately against the persistent fork key.

- [ ] **Step 6: Commit and push**

```bash
git add app/src/main/java/app/gamenative/utils/ReleaseChannelPolicy.kt \
  app/src/main/java/app/gamenative/PluviaApp.kt \
  app/src/main/java/app/gamenative/utils/UpdateChecker.kt \
  app/src/main/java/app/gamenative/utils/IntentLaunchManager.kt \
  app/src/main/java/app/gamenative/utils/ShortcutUtils.kt \
  app/src/test/java/app/gamenative/utils/ReleaseChannelPolicyTest.kt \
  app/src/test/java/app/gamenative/utils/IntentLaunchManagerTest.kt
git commit -m "fix: isolate Darkaxt release integrations"
git push fork HEAD:codex/steam-normalized-game-details-spec
```

### Task 7: Build and verify four signed release artifacts in CI

**Files:**
- Modify: `app/build.gradle.kts:67-68`
- Modify: `.github/workflows/tagged-release.yml:169-308,321-395`
- Create: `tools/verify-release-apks.sh`
- Create: `tools/tests/verify-release-apks-test.sh`
- Create: `docs/releases/gamenative-darkaxt-transition.md`

- [ ] **Step 1: Write the failing shell contract test**

The test creates four empty fixture names plus fake `apkanalyzer`/`apksigner` executables and asserts that `verify-release-apks.sh` expects this map:

```text
gamenative-v1.1.3-rc2-compat.apk                 -> app.gamenative
gamenative-v1.1.3-rc2-compat-legacy-xr.apk       -> app.gamenative
gamenative-v1.1.3-rc2-side-by-side.apk            -> app.gamenative.darkaxt
gamenative-v1.1.3-rc2-side-by-side-legacy-xr.apk  -> app.gamenative.darkaxt
```

The fake analyzer returns version code `28`, version name `1.1.3-rc2`, and the package implied by the filename. The test then changes one side-by-side package to `app.gamenative` and expects the verifier to fail.

- [ ] **Step 2: Run and verify RED**

```bash
bash tools/tests/verify-release-apks-test.sh
```

Expected: FAIL because `tools/verify-release-apks.sh` is absent.

- [ ] **Step 3: Implement the reusable artifact gate**

`tools/verify-release-apks.sh` accepts:

```text
verify-release-apks.sh <tag> <version-code> <version-name> <expected-cert-sha256> <apk-dir>
```

It must:

1. Require exactly the four filenames above for the supplied tag.
2. Map `compat*` to `app.gamenative` and `side-by-side*` to `app.gamenative.darkaxt`.
3. Read application ID, version code, and version name with `apkanalyzer manifest` commands.
4. Run `apksigner verify --verbose --print-certs` and require verification plus at least APK Signature Scheme v2.
5. Normalize and compare the signer SHA-256 to the non-secret expected digest.
6. Write `SHA256SUMS`, then run `sha256sum --check SHA256SUMS`.
7. Print only artifact names and fixed verification outcomes; do not print secret paths or URLs.

- [ ] **Step 4: Run the shell test GREEN**

```bash
bash tools/tests/verify-release-apks-test.sh
```

Expected: PASS for the correct map and a deliberate nonzero result for the mislabeled side artifact.

- [ ] **Step 5: Bump the release identity once**

Set:

```kotlin
versionCode = 28
versionName = "1.1.3-rc2"
```

The tag is `v1.1.3-rc2`. Do not publish any version code lower than 28 for `app.gamenative.darkaxt` after this prerelease.

- [ ] **Step 6: Expand only the authoritative tagged workflow**

Add a `workflow_dispatch` boolean input named `publish_release` with default `false`. A tag-push run performs the authoritative build/sign/verify job and uploads the APKs, checksums, and `release-body.md`, but its `release` job is gated off. This creates signed candidate artifacts for Task 8 without publishing them. A manual dispatch may publish only when `publish_release` is true; the first dual-package release instead reuses the already validated tag-run artifacts in Task 9 to avoid a redundant rebuild.

Build:

```bash
./gradlew \
  :app:bundleLegacyRelease \
  :app:bundleLegacyXrRelease \
  :app:bundleLegacyReleaseDarkaxt \
  :app:bundleLegacyXrReleaseDarkaxt
```

Stage four AABs, extract four universal APKs, sign all four with the existing persistent fork keystore step, rename them with `compat` or `side-by-side`, and call:

```bash
tools/verify-release-apks.sh \
  "$RELEASE_TAG" 28 "1.1.3-rc2" \
  "90d491f4c194d4f6e9efaf2ba1a548e59388edd9ecbd96853d330fe6a9c260c9" \
  .
```

Set a workflow timeout from observed cold-cache duration (start with 75 minutes) and do not add a redundant signed master build. Build `release-body.md` in the build job and include it in the uploaded artifact. Gate the existing release job with:

```yaml
if: ${{ github.event_name == 'workflow_dispatch' && inputs.publish_release }}
```

A normal `push` of `v1.1.3-rc2` must therefore leave no public GitHub Release until Task 8 passes.

Release notes must state:

- `side-by-side` is the recommended artifact and coexists with official GameNative;
- `compat` upgrades existing Darkaxt `app.gamenative` installs but cannot coexist with official;
- side-by-side requires one final sign-in/container setup on first install;
- every later side-by-side release installs over it without uninstalling;
- official private tokens, containers, and Keystore data cannot be copied;
- default public root is `GameNative-Darkaxt`;
- `nxm://` may show a chooser;
- updater is disabled and updates come from verified Darkaxt GitHub releases;
- all four APKs use the displayed persistent fork certificate digest.

- [ ] **Step 7: Add one-time transition instructions**

`docs/releases/gamenative-darkaxt-transition.md` must provide this ordered flow:

1. Export supported Steam saves and container JSON from the existing app.
2. Keep official GameNative installed.
3. Install `side-by-side`, authenticate once, import supported data, and create/validate one container.
4. Do not copy token files, Keystore ciphertext, or private `Android/data` content.
5. Use `GameNative-Darkaxt` for new public installations; do not concurrently update/mod one shared game tree from both apps.
6. For every later fork update, install the newer side-by-side APK over the installed app; never uninstall first.

- [ ] **Step 8: Run targeted local verification**

```bash
bash tools/tests/verify-release-apks-test.sh
./gradlew :app:testLegacyDebugUnitTest \
  --tests app.gamenative.build.TestTempIsolationBuildConfigTest \
  --tests app.gamenative.build.PackagePrivatePathGuardTest \
  --tests app.gamenative.build.DarkaxtBuildContractTest \
  --tests com.winlator.core.RuntimePathsTest \
  --tests com.winlator.core.WineUtilsTest \
  --tests app.gamenative.utils.ReleaseChannelPolicyTest \
  --tests app.gamenative.utils.IntentLaunchManagerTest \
  --tests app.gamenative.utils.StorageUtilsTest \
  --no-daemon --no-parallel
./gradlew :app:bundleLegacyRelease :app:bundleLegacyReleaseDarkaxt --no-daemon --no-parallel
```

Expected: shell tests PASS, targeted tests PASS, both standard bundles build successfully, and test temp files remain under `app/build`.

- [ ] **Step 9: Commit and push**

```bash
git add app/build.gradle.kts .github/workflows/tagged-release.yml \
  tools/verify-release-apks.sh tools/tests/verify-release-apks-test.sh \
  docs/releases/gamenative-darkaxt-transition.md
git commit -m "ci: publish dual signed GameNative channels"
git push fork HEAD:codex/steam-normalized-game-details-spec
```

- [ ] **Step 10: Run the single focused review**

Dispatch one reviewer only. Scope the review to Critical/High release blockers in Tasks 1-7:

- data deletion or incompatible package/signing/version identity;
- unresolved package-private paths;
- side package accidentally using official updater/analytics/action;
- public-root collision;
- wrong artifact/package/signer mapping;
- CI secret exposure;
- missing owning tests that allow one of those failures.

If no Critical/High finding survives verification, make no review commit. If findings survive, use one correction worker and one correction commit, rerun only affected owning tests plus the targeted command from Step 8, push it, and stop reviewing.

### Task 8: Prove coexistence and state-preserving replacement on an owned AVD

**Files:**
- Create: `docs/testing/gamenative-dual-package-upgrade-matrix.md`
- No production code changes unless the single Task 7 review correction requires them.

- [ ] **Step 1: Claim a separate AVD explicitly**

Record in the matrix:

```markdown
- Owner: dual-package release thread
- AVD: GameNativeDualPackageApi35
- Serial: emulator-5560
- Started: copy the UTC timestamp emitted by the launch command
- Forbidden serial: emulator-5554
```

Start `GameNativeDualPackageApi35` on port 5560 with a clean snapshot, set `OWNED_SERIAL=emulator-5560`, assert it is not `emulator-5554`, and wait for boot completion. If port 5560 is occupied, choose the next free even emulator port and record that concrete serial before continuing. Every adb command in this task must include `-s "$OWNED_SERIAL"`.

- [ ] **Step 2: Obtain production-signed candidates and a local higher-version test candidate**

Fetch `origin/master` and require it to be an ancestor of `HEAD`; if not, integrate it and rerun Task 7 Step 8 before proceeding. Require a clean tree and verify `v1.1.3-rc2` does not already exist remotely. Then push the tested candidate to fork master, create the immutable annotated tag, and push the tag:

```bash
git fetch origin master
git merge-base --is-ancestor origin/master HEAD
git status --short
git ls-remote --tags fork v1.1.3-rc2
git push fork HEAD:master
git tag -a v1.1.3-rc2 -m "GameNative 1.1.3 RC2 dual-package prerelease"
git push fork v1.1.3-rc2
```

The tag-push workflow from Task 7 builds, signs, verifies, and uploads all four production candidates while its release job remains gated off. Download them to `$CLAUDE_JOB_DIR/tmp`; never use `%LOCALAPPDATA%/Temp`.

Before install, rerun `tools/verify-release-apks.sh` locally against the downloaded production artifacts. Expected: four packages, version 28/`1.1.3-rc2`, persistent fork digest, valid schemes, checksums pass.

Also build/extract the current v28 `legacyReleaseDarkaxt` universal APK locally:

```bash
./gradlew :app:bundleLegacyReleaseDarkaxt --no-daemon --no-parallel
java -jar tools/bundletool-all-1.17.2.jar build-apks \
  --bundle=app/build/outputs/bundle/legacyReleaseDarkaxt/app-legacy-releaseDarkaxt.aab \
  --output="$CLAUDE_JOB_DIR/tmp/gamenative-side-candidate-v28.apks" \
  --mode=universal
unzip -p "$CLAUDE_JOB_DIR/tmp/gamenative-side-candidate-v28.apks" universal.apk \
  > "$CLAUDE_JOB_DIR/tmp/gamenative-side-candidate-v28.apk"
```

Confirm the v27 baseline and v28 candidate share the local test signer, identify as `app.gamenative.darkaxt`, and report version codes 27 and 28 respectively. These local APKs are solely for the increasing-version state-preservation test and are never published.

- [ ] **Step 3: Prove compatibility and side-by-side coexistence**

```bash
adb -s "$OWNED_SERIAL" install gamenative-v1.1.3-rc2-compat.apk
adb -s "$OWNED_SERIAL" install gamenative-v1.1.3-rc2-side-by-side.apk
adb -s "$OWNED_SERIAL" shell pm list packages | rg "package:app.gamenative(\.darkaxt)?$"
```

Expected: both package IDs appear. Launch each explicit component and verify distinct labels/icons and independent first-run state.

- [ ] **Step 4: Seed representative state in the local v27 side baseline**

After recording production coexistence, uninstall only the empty production side candidate, install `$CLAUDE_JOB_DIR/tmp/gamenative-side-baseline-v27.apk`, and confirm its package remains `app.gamenative.darkaxt`. The compatibility production app remains installed and demonstrates coexistence throughout.

Using the local v27 side-by-side UI only:

- change one harmless preference;
- create one test container and launch it once;
- create a file inside its Wine prefix;
- create/import one harmless controller profile;
- create one harmless mod-cache entry or local test mod;
- create one local save/config file;
- allow the app to create its Room database and DataStores.

Do not use real credentials in the temporary AVD. Account-session survival is deferred to the user-facing live upgrade gate, where the user's already authenticated side installation upgrades normally.

Record fixed sentinel categories and hashes only; do not record game titles, paths, account identifiers, or tokens.

- [ ] **Step 5: Prove an increasing-version in-place upgrade preserves state**

Install the locally extracted v28 side candidate over the locally signed v27 baseline:

```bash
adb -s "$OWNED_SERIAL" install -r "$CLAUDE_JOB_DIR/tmp/gamenative-side-candidate-v28.apk"
```

Expected: `Success`, package remains `app.gamenative.darkaxt`, version code changes 27 → 28, no uninstall occurs, the side package UID remains unchanged, and all seeded preference/database/container/prefix/mod/profile/save categories remain visible.

This proves Android upgrade durability using the same immutable package and one stable test signing lineage. Separately, the production candidates prove the immutable package and persistent fork signing identity. The first production side release has no prior production side artifact; the mandatory next live upgrade will provide the first persistent-fork-key production upgrade proof.

- [ ] **Step 6: Validate production channel routing and runtime paths**

After recording the local v27 → v28 state-survival result, uninstall only the local-test-signed side package and reinstall the already verified production-signed side candidate. This signer transition is intentionally a clean test-device install and is not presented as a user migration path.

Verify:

- a pinned shortcut opens `app.gamenative.darkaxt`, never `app.gamenative`;
- the side app does not call the official update endpoint;
- internal deep links route to their originating channel;
- `nxm://` shows a chooser when both handlers are present;
- FileProvider authority is `app.gamenative.darkaxt.fileprovider` for side shares;
- new public root is `GameNative-Darkaxt`;
- DXVK cache, E drive, media conversion, gamepad shared memory, and evshim work under side runtime paths;
- removing compatibility does not remove side private state.

Record PASS/FAIL and fixed reason codes only.

- [ ] **Step 7: Stop and remove only the owned AVD**

Stop the owned serial and remove only `GameNativeDualPackageApi35`. Do not touch `emulator-5554` or any other device. Keep the matrix document; remove downloaded APK copies from `$CLAUDE_JOB_DIR/tmp` after checksum and release verification are complete.

- [ ] **Step 8: Commit and push the evidence document**

```bash
git add docs/testing/gamenative-dual-package-upgrade-matrix.md
git commit -m "test: validate dual-package upgrade durability"
git push fork HEAD:codex/steam-normalized-game-details-spec
```

Do not mark this task complete if any package, signer, path, shortcut, storage, or state-preservation check fails.

### Task 9: Sync upstream and publish the signed side-by-side prerelease

**Files:**
- Modify only if needed after upstream conflict resolution: files already owned by Tasks 1-8.
- Release assets: four APKs plus `SHA256SUMS`.

- [ ] **Step 1: Recheck upstream before making the candidate public**

```bash
git fetch origin master
git merge-base --is-ancestor origin/master v1.1.3-rc2
git log --oneline --left-right v1.1.3-rc2...origin/master
```

Expected: the ancestor check passes. If official upstream advanced after Task 8 created the tag, do not publish RC2: integrate upstream, rerun Task 7 Step 8 and affected AVD checks, bump to version code 29/next tag, and leave RC2 unpublished. Never move an immutable tag.

- [ ] **Step 2: Verify the immutable candidate and clean evidence commit**

```bash
git status --short
git rev-parse v1.1.3-rc2^{commit}
git ls-remote --tags fork v1.1.3-rc2
git push fork HEAD:master
```

Expected: clean tree, local and remote tag targets agree, and fork master contains the Task 8 evidence commit. Do not recreate or force-update the tag.

- [ ] **Step 3: Publish the already validated assets without rebuilding**

Use the four APKs, `SHA256SUMS`, and `release-body.md` downloaded from the exact Task 8 tag-push run:

```bash
gh release create v1.1.3-rc2 \
  --repo Darkaxt/GameNative \
  --title "GameNative dual-package RC2 — v1.1.3-rc2" \
  --prerelease \
  --notes-file "$CLAUDE_JOB_DIR/tmp/published-v1.1.3-rc2/release-body.md" \
  "$CLAUDE_JOB_DIR/tmp/published-v1.1.3-rc2/gamenative-v1.1.3-rc2-compat.apk" \
  "$CLAUDE_JOB_DIR/tmp/published-v1.1.3-rc2/gamenative-v1.1.3-rc2-compat-legacy-xr.apk" \
  "$CLAUDE_JOB_DIR/tmp/published-v1.1.3-rc2/gamenative-v1.1.3-rc2-side-by-side.apk" \
  "$CLAUDE_JOB_DIR/tmp/published-v1.1.3-rc2/gamenative-v1.1.3-rc2-side-by-side-legacy-xr.apk" \
  "$CLAUDE_JOB_DIR/tmp/published-v1.1.3-rc2/SHA256SUMS"
```

This outward-facing publication is authorized by the standing live-test release instruction, but only after Task 8 passes. Do not dispatch a second build.

- [ ] **Step 4: Verify the exact tag build and public release state**

Query the exact tag-push run used by Task 8 once. Expected: its build job succeeded, its release job was skipped by the prepublication gate, and its artifact supplied the files passed to `gh release create`. Then query `v1.1.3-rc2` and require exactly five public assets: four APKs plus `SHA256SUMS`. Do not launch duplicate workflows because a watcher disconnects.

- [ ] **Step 5: Download and independently verify published assets**

Download to `$CLAUDE_JOB_DIR/tmp`, run:

```bash
tools/verify-release-apks.sh \
  v1.1.3-rc2 28 1.1.3-rc2 \
  90d491f4c194d4f6e9efaf2ba1a548e59388edd9ecbd96853d330fe6a9c260c9 \
  "$CLAUDE_JOB_DIR/tmp/published-v1.1.3-rc2"
```

Expected: all four package/channel mappings, version metadata, signature schemes, signer digest, and checksums PASS.

- [ ] **Step 6: Verify release notes and report direct links**

Verify notes no longer say uninstall official for the side-by-side APK. They must clearly distinguish:

- `side-by-side`: installs beside official, preferred, one final setup;
- `compat`: same package as official, only for existing Darkaxt upgrade lineage.

Report the release URL and direct standard side-by-side APK link. Delete only the downloaded verification copies from `$CLAUDE_JOB_DIR/tmp`; do not clean unrelated Windows temp data.

### Task 10: Freeze the official-PR boundary and start the live durability gate

**Files:**
- Create: `docs/upstream/gamenative-steam-first-pr-boundary.md`

- [ ] **Step 1: Record exact commit classifications**

Create a table with each implementation commit SHA and one classification:

```markdown
| Commit | Classification | Official PR action |
|---|---|---|
| test-temp containment | tooling-neutral | cherry-pick only if upstream wants test hygiene |
| runtime storage paths | upstream-eligible | reconstruct/cherry-pick |
| launch/native paths | upstream-eligible | reconstruct/cherry-pick |
| package suffix/branding | fork-only | exclude |
| storage channel root | fork-only | exclude |
| updater/analytics/channel actions | split: generic action logic only | reconstruct generic hunk; exclude fork policy |
| dual-artifact workflow/version/release notes | fork-only | exclude |
| AVD/release evidence | fork-only | exclude |
```

Use actual SHAs, not labels, in the committed document.

- [ ] **Step 2: Define the future clean PR reconstruction command**

Document:

```bash
git fetch origin master
git switch -c upstream/steam-first-final origin/master
# cherry-pick only recorded upstream-eligible commits; reconstruct mixed commits by file/hunk
git diff --check origin/master...HEAD
```

The future PR branch must start at then-current `origin/master`; never open the fork release branch directly.

- [ ] **Step 3: Commit and push the boundary document**

```bash
git add docs/upstream/gamenative-steam-first-pr-boundary.md
git commit -m "docs: record GameNative upstream PR boundary"
git push fork HEAD:codex/steam-normalized-game-details-spec
git push fork HEAD:master
```

- [ ] **Step 4: Start complaint-driven live validation**

Ask the user to install the standard `side-by-side` APK without uninstalling official GameNative, authenticate once, create/import one representative container, and perform one launch. For the next side-by-side release, require installation over this RC2 and confirm Steam, GOG, Epic, Amazon, and Nexus signed-in indicators plus container, prefix, mods, saves, profiles, Room state, and settings survive. Collect only fixed pass/fail categories; never request or export tokens, account identifiers, usernames, titles, paths, or URLs.

Do not begin the broad Steam-first Task 6/final official PR gate until this live result is available. Do not ask the user to uninstall `app.gamenative.darkaxt` as an update procedure.

---

## Focused acceptance checklist

- [ ] JVM tests use `app/build/test-tmp`, not Windows global temp.
- [ ] Production source guard finds no hard-coded official private root.
- [ ] `RuntimePaths` drives all private storage, DXVK, media, Wine repair, gamepad, and evshim paths.
- [ ] Compatibility remains `app.gamenative`; side-by-side is exactly `app.gamenative.darkaxt`.
- [ ] Both dynamic-feature variants build.
- [ ] Label and launcher icon visibly distinguish the side package.
- [ ] Official updater and PostHog initialization are disabled in side-by-side builds.
- [ ] Launch actions, shortcuts, internal deep links, and FileProvider authorities are package-scoped.
- [ ] Side public installs default to `GameNative-Darkaxt`.
- [ ] CI signs and verifies four channel-labelled APKs with the persistent fork certificate.
- [ ] Version code is 28 and future side-by-side versions must be higher.
- [ ] Owned-AVD coexistence and replacement matrix passes without touching `emulator-5554`.
- [ ] Published release assets and notes are independently verified.
- [ ] Fork-only commits are excluded from the future official PR reconstruction.
- [ ] The first live side install requires one honest final setup; later installs never require uninstall/relogin/container recreation.
