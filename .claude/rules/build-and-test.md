# Build and test (OllamaDev)

CLAUDE.md covers design-system rules; the commands CI runs live here (R4).

- JDK 17, Gradle wrapper (`./gradlew`), as in `.github/workflows/gradle-task.yml`.
- Main CI gate (android-ci): `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`
- Unit tests only: `./gradlew :app:testDebugUnitTest`
- Lint only: `./gradlew :app:lintDebug`
- Screenshot baselines (screenshots workflow): `./gradlew :app:recordRoborazziDebug --tests com.example.ui.ScreenshotDriverTest`
- On the aarch64 Android userland, run `./scripts/setup_aapt2_qemu.sh` first (see CLAUDE.md, "Build environment note").
- If a local run fails where CI passes, trust CI and note the difference (S11).
