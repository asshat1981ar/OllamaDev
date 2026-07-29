# Spike Tests for New Third-Party Libraries

When integrating a new third-party library, write a throwaway `<Library>SpikeTest.kt`
first to confirm it actually works on this toolchain, before building the real wrapper
class around it. `GitServiceSpikeTest.kt` (confirming JGit can init/add/commit) is the
existing example.

- **Location:** root `com.example` package (`app/src/test/java/com/example/`), not a
  feature subpackage — it's testing the library, not app logic.
- **Naming:** `<Library>SpikeTest`, e.g. `GitServiceSpikeTest`.
- **Rigor:** spikes are exploratory — they aren't held to the same coverage bar as real
  unit tests. Their job is to prove the library works here, not to test app behavior.
- Keep the spike in the repo after the real class is built (don't delete it) — it
  documents the minimal working usage of the library independent of app-specific wiring.
