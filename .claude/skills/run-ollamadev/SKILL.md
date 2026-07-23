---
name: run-ollamadev
description: Build, test, and screenshot the OllamaDev Android app (Ollama Swarm) headlessly, with no emulator/device available. Use when asked to build the app, run its tests, screenshot a screen, or verify a UI change actually renders.
---

OllamaDev ("Ollama Swarm") is a native Android app (Kotlin + Jetpack Compose,
Material 3). This container has **no Android emulator and no `/dev/kvm`**, so there
is no way to launch the real app and tap it like a device. Instead, drive it through
the JVM-based Robolectric + Compose test harness that already exists at
`app/src/test/java/com/example/ui/UiTestBase.kt` — it renders real screen composables
with all I/O (network, Room, secure prefs) swapped for in-memory fakes, runs entirely
on the JVM, and is the closest thing this project has to "run the app." For pixels,
use the screenshot driver at `app/src/test/java/com/example/ui/ScreenshotDriverTest.kt`,
added alongside this skill (it has to live in that Gradle test source set to compile —
see "Where the driver lives" below).

All paths below are relative to the repo root (`/home/dev/OllamaDev`).

## Prerequisites

Already present in this container — nothing to install:

- JDK 21 (`java -version` → Temurin 21.0.11) on `PATH`.
- Android SDK at `/home/dev/toolchain/android-sdk` (pointed to by `local.properties`),
  with `platforms/android-34` and `build-tools/34.0.0` + `36.0.0`. **No `emulator`
  package and no AVDs are installed**, and `/dev/kvm` does not exist — do not try to
  start an emulator here, it cannot work in this container (verified: no `emulator`
  binary under the SDK, `avdmanager list avd` returns empty, no KVM device node).
- `./gradlew` is executable and already has Gradle 9.3.1 downloaded in `~/.gradle/wrapper/dists`.
- `.env` already exists at repo root (real `GEMINI_API_KEY`/`OLLAMA_API_KEY`, gitignored).
  If it's ever missing, copy `.env.example` — the Secrets Gradle Plugin
  (`app/build.gradle.kts`) falls back to it and `googleServices.missingGoogleServicesStrategy = WARN`
  means a missing `google-services.json` doesn't fail the build either.

This device has only ~5.3GB RAM. `gradle.properties` at repo root is already tuned for
that (capped daemon heap, `workers.max=2`, SerialGC, `org.gradle.vfs.watch=false` since
inotify watches are unreliable under proot) — **do not change these values**, they were
set deliberately to avoid OOM kills, not left at defaults by accident.

## Build

```bash
./gradlew assembleDebug --console=plain
```

Verified: `BUILD SUCCESSFUL`, produces `app/build/outputs/apk/debug/app-debug.apk`.
(No signing config changes needed — despite the top-level README saying to remove a
`signingConfig = signingConfigs.getByName("debugConfig")` line, the current `debug {}`
build type in `app/build.gradle.kts` doesn't actually set one; that README step is
stale.) There is no install/launch step for this APK in this container — it's built to
confirm compilation only, not run.

## Run (agent path)

**Where the driver lives:** `ScreenshotDriverTest.kt` is a Kotlin/JUnit test class, so
it has to live inside `app/src/test/java/...` to be compiled and discovered by Gradle —
there's no way to run it as a standalone script outside the module's test source set.
It's committed there (not in this skill directory) for that reason; this `SKILL.md` is
its documentation.

There is no running app to attach to. Instead, **drive real screen composables
through Robolectric** — this is the direct-invocation layer, and it's what the
project's own flow tests already do:

```bash
# Run one existing UI flow test (fast sanity check that the harness works)
./gradlew testDebugUnitTest --tests "com.example.ui.DashboardFlowTest" --console=plain

# Run the whole UI + data test suite
./gradlew testDebugUnitTest --console=plain
```

To actually **see pixels**, use `recordRoborazziDebug` (plain `testDebugUnitTest` runs
the same `captureRoboImage()` calls but silently no-ops them — Roborazzi only writes
files under the `record`/`verify`/`compare` tasks, not the bare `test` task):

```bash
./gradlew recordRoborazziDebug --tests "com.example.ui.ScreenshotDriverTest" --console=plain
```

Screenshots land at `app/build/outputs/roborazzi-screens/{dashboard,agents,nodes,voice}.png`
(gitignored — under `app/build/`). `ScreenshotDriverTest.kt` (in this same `ui` test
package) renders `DashboardScreen`, `AgentScreen`, `NodeScreen`, and `VoiceScreen` at a
360x800dp phone viewport with the standard `UiTestBase` fakes. To screenshot a
different screen or state, add a `@Test` method there following the existing pattern:

```kotlin
@Config(qualifiers = "w360dp-h800dp")
@Test
fun myScreen() = runUiTest {
    setContent { MyScreen(viewModel) }
    advanceUntilIdle()
    composeRule.onRoot().captureRoboImage("build/outputs/roborazzi-screens/my_screen.png")
}
```

To exercise interaction (clicks, text entry, scrolling) rather than just render a
screen, write a test extending `UiTestBase` the way the existing flow tests do —
`DashboardFlowTest.kt`, `NodeManagementTest.kt`, `AgentManagementTest.kt`,
`VoiceScreenTest.kt`, `WorkspaceFileFlowTest.kt`, `GitIntegrationTest.kt`,
`McpServerAndRegistryTest.kt`, `SwarmDispatchTest.kt` are all real examples of this:

```kotlin
class MyFlowTest : UiTestBase() {
    @Config(qualifiers = "w360dp-h800dp")
    @Test
    fun clickingButtonDoesThing() = runUiTest {
        setContent { MyScreen(viewModel) }
        advanceUntilIdle()
        composeRule.onNodeWithTag("my_button").performClick()
        advanceUntilIdle()
        composeRule.onNodeWithText("Expected result").assertIsDisplayed()
    }
}
```

**If your change is only in the data layer** (`app/src/main/java/com/example/data/`)
and doesn't touch Compose UI, skip Robolectric entirely — write/run a plain JUnit test
next to the existing ones (`GitServiceTest.kt`, `McpClientTest.kt`) instead; those run
in one shared JVM with no Robolectric sandbox and are far faster:

```bash
./gradlew testDebugUnitTest --tests "com.example.data.GitServiceTest" --console=plain
```

## Run (human path)

Per the top-level `README.md`: open the project in Android Studio, let it sync, run
on an emulator or physical device via the IDE. Not usable in this container (no
GUI/emulator) — the agent path above is the only way to drive this app here.

## Test

```bash
./gradlew testDebugUnitTest --console=plain
```

17 test classes under `app/src/test/`, mixing plain JUnit (data layer) and
Robolectric+Compose (`ui` package). Confirmed passing: `DashboardFlowTest` (3 tests)
and the new `ScreenshotDriverTest` (4 tests) in isolation.

## Gotchas

- **No emulator is possible here, full stop.** No `emulator` binary in the SDK, no
  AVDs, no `/dev/kvm`. Don't burn time trying `avdmanager create avd` + `emulator
  -avd ...` — it was checked and there's nothing to boot it on. The Robolectric route
  above is the only "run the app" option in this container.
- **`adb` in `/home/dev/.local/opt/android-sdk/platform-tools/adb` fails to execute**
  (`cannot execute: required file not found`, exit 126) — likely an arch/loader
  mismatch on this aarch64 proot host. Irrelevant to the headless path since there's
  no device/emulator to connect to anyway, but don't assume `adb` works if some other
  task needs it.
- **`testDebugUnitTest` silently does not write Roborazzi images.** `captureRoboImage()`
  calls execute without error but produce no file unless you run the Gradle-plugin-generated
  `recordRoborazziDebug` (or `verifyRoborazziDebug`/`compareRoborazziDebug`) task instead of
  plain `test`. Easy to miss since the build still reports `BUILD SUCCESSFUL` either way.
- **Screenshots are not literal rendered pixels.** This host's Robolectric/native-graphics
  stack doesn't do full anti-aliased Skia rendering (no native graphics libs for
  linux-aarch64 under proot — same root cause as the Conscrypt/SQLite issues documented in
  `UiTestBase.kt`'s class doc). What `captureRoboImage` produces here is a semantics-tree
  debug visualization: colored boxes per Compose node with its bounds, tag, text content,
  and available actions overlaid as labels. It's still genuinely useful for verifying real
  copy/state/layout structure rendered (agent names, status text, counts, etc.) — just don't
  expect a normal-looking screenshot.
- **`RuntimeEnvironment.setQualifiers()` (used by `setCompactWidth()`/`setExpandedWidth()`
  in `UiTestBase`) only resizes resources, not the already-created Compose host view.**
  For anything below the fold, set the viewport size via `@Config(qualifiers = "w360dp-hNNNNdp")`
  on the test method instead (see the comment at the top of `DashboardFlowTest.kt`) — pick a
  generous height so `LazyColumn` actually composes the item you want to assert on/screenshot.
- **First cold run of any Robolectric test class is slow** (~2-4 min observed) because it
  loads the full `android-all` jar for API 34 per class (`forkEvery = 1` in
  `app/build.gradle.kts`, deliberately, so one corrupted sandbox doesn't take down the
  whole suite — see the comment there). Subsequent runs of the same class are much faster
  once Gradle's build cache/configuration cache has the compile outputs.

## Troubleshooting

- **`e: ... Unresolved reference 'onRoot'`** when writing a new Compose test: missing
  `import androidx.compose.ui.test.onRoot` — it's an extension function, not a method
  on `ComposeContentTestRule`.
- **`Cannot invoke "...ApplicationManager.getApplication()" because ... is null"` /
  `AWT-EventQueue-0` stack trace during `kspDebugUnitTestKotlin`**: harmless KSP/IntelliJ
  platform noise on this host, does not fail the build (observed alongside a `BUILD
  SUCCESSFUL` result) — ignore it.
