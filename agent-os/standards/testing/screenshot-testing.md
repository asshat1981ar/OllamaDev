# Headless Screenshot Testing (Roborazzi)

No emulator/device is available in this container, so screenshots come from Robolectric +
Roborazzi instead. `ScreenshotDriverTest` (`app/src/test/java/com/example/ui/`) renders
real screens wired to `UiTestBase`'s fakes and dumps a PNG.

```kotlin
class ScreenshotDriverTest : UiTestBase() {
    @Config(qualifiers = "w360dp-h800dp")
    @Test
    fun dashboard() = runUiTest {
        setContent { DashboardScreen(viewModel, onNavigateToSwarm = {}) }
        advanceUntilIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi-screens/dashboard.png")
    }
}
```

- **Always call `advanceUntilIdle()` before `captureRoboImage()`.** Skipping it is the
  most common mistake — the capture fires before the composition settles and produces a
  screenshot of a loading/stale state instead of the real result.
- Add a screenshot test on request (e.g. verifying a UI change actually renders), not
  automatically for every new screen.
- Run via the `run-ollamadev` skill; output lands in `app/build/outputs/roborazzi-screens/`.
- Use `@Config(qualifiers = "w360dp-h800dp")` for phone-width, `"w900dp-h800dp"` for
  expanded/tablet — match whichever breakpoint the screen under test actually branches on.
