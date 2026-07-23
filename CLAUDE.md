# CLAUDE.md — Design System Rules (Figma MCP Integration)

OllamaDev is a **native Android app** (Jetpack Compose, Kotlin, Material 3). There is no
CSS, no web bundler, no design-token JSON pipeline. Figma MCP tools that assume a
React/CSS stack (styled-components, Tailwind, CSS variables) do not apply directly — map
their concepts onto Compose equivalents as described below.

## 1. Design Tokens

- **Source of truth:** `app/src/main/java/com/example/ui/theme/{Color.kt,Theme.kt,Type.kt}`.
- **Color tokens** live in `Color.kt` as an "Immersive UI Palette" (9 named `Color(0xFF..)`
  vals: `ImmersivePrimary`, `ImmersiveSecondary`, `ImmersiveBackground`, `ImmersiveSurface`,
  `ImmersiveSurfaceVariant`, `ImmersiveBorder`, `ImmersiveOnBackground`, `ImmersiveOnSurface`,
  `ImmersiveTextSecondary`). Legacy aliases (`CyberBlue`, `DeepIndigo`, etc.) point at the
  same values — don't introduce new aliases, extend the Immersive* names instead.
- **`Theme.kt`** wires tokens into `MaterialTheme.colorScheme` via `DarkColorScheme`
  (active) and `LightColorScheme` (defined but inactive — app defaults to
  `darkTheme = true`, `dynamicColor = false`). Treat the app as **dark-only** unless asked
  to change that.
- **Typography** (`Type.kt`) only overrides `bodyLarge`; everything else is Material3
  default. When importing type styles from Figma, only create an override if it differs
  from Material3 defaults — don't fill in every slot speculatively.
- **No `Shape.kt` or `Dimens.kt` exists.** Spacing/corner-radius are inlined per-composable
  as raw `.dp` literals. The de-facto scale, derived by grep across `ui/*.kt`:
  - padding: 4, 6, 8, 10, 12, 14, 16, 24 dp
  - `Arrangement.spacedBy`: 2, 4, 6, 8, 10, 12, 16 dp
  - `RoundedCornerShape`: 0, 4, 6, 8, 10, 12, 16 dp
  When a Figma frame specifies spacing/radius, snap to the nearest value in this scale
  rather than importing the exact Figma pixel value — it keeps the UI visually consistent
  with the rest of the app. If a new value is genuinely needed repeatedly, propose adding
  `Dimens.kt` rather than scattering another one-off literal.
- **195 raw hex colors bypass the token file** (`grep -rn "Color(0xFF" app/src/main/java/com/example/ui/`).
  Recurring untokenized semantic colors: success green `#4CAF50`/`#4ADE80`, error red
  `#EF4444`/`#F44336`, warning orange `#FF9800`, `#FACC15` yellow, plus several
  near-duplicate near-black surfaces (`#15131A`, `#141219`, `#0F0F12`, `#0F0E12`). **When a
  Figma design uses one of these colors, reuse the existing hex/token rather than
  inventing a new near-duplicate** — grep first.
- **`res/values/colors.xml` and `themes.xml` are dead/legacy** (Android Studio template
  scaffolding, Material2 purple/teal palette). They are not wired into the Compose theme.
  Ignore them as a source of truth; `themes.xml` only prevents an ActionBar flash before
  Compose renders.

## 2. Component Library

- **No shared component package exists** (no `ui/components/`, no `Button.kt`/`Card.kt`
  wrappers). Each screen file defines its own local, private, reusable composables
  (e.g. `StatCard` in `DashboardScreen.kt`, `NodeCard` in `NodeScreen.kt`, `AgentCard` in
  `AgentScreen.kt`) and reuses raw Material3 primitives (`Card`, `Button`, `Surface`,
  `Text`, `Icon`) directly.
- **When implementing a Figma component:** first check whether an equivalent
  local composable already exists in the target screen file (or a sibling screen) before
  creating a new one. If a component clearly belongs across ≥2 screens, that's a signal to
  extract it — but there is no established convention for where; ask/propose a
  `ui/components/` package rather than duplicating silently.
- Screens: `app/src/main/java/com/example/ui/*.kt` (flat, no sub-packages besides `theme/`).
  Each top-level screen composable takes `viewModel: SwarmViewModel` plus navigation
  callback lambdas, e.g. `fun DashboardScreen(viewModel: SwarmViewModel, onNavigateToSwarm: (SwarmConfig) -> Unit, modifier: Modifier = Modifier)`.
- **State hoisting pattern:** `by viewModel.xxx.collectAsState()` at the top of the screen
  composable; ephemeral local UI state (dialog visibility, search text, selected tab) uses
  `remember { mutableStateOf(...) }`. There is one monolithic `SwarmViewModel.kt` (1500+
  lines) shared across all screens — new state should be added there following the existing
  `_backing: MutableStateFlow` / `public: StateFlow via asStateFlow()` pattern, not a new
  per-screen ViewModel.
- **Adaptive layout:** screens branch on `LocalConfiguration.current.screenWidthDp >= 600`
  to switch phone (stacked `Column`) vs. tablet/foldable (`Row`, master-detail) layouts.
  This check is duplicated per-screen (`MainActivity.kt`, `DashboardScreen.kt`,
  `RegistryBrowserDialog.kt`) — follow the same inline pattern for new screens rather than
  assuming a shared breakpoint utility exists.

## 3. Frameworks & Libraries

- **UI:** Jetpack Compose, **Material 3** (`androidx.compose.material3`), Kotlin 2.2.10,
  AGP 9.1.1, Compose BOM `2024.09.00` (`gradle/libs.versions.toml`).
- **Compose compiler:** modern K2 plugin (`org.jetbrains.kotlin.plugin.compose`), no
  separate compiler-extension version pin needed.
- **Navigation:** `navigation-compose` is a declared dependency but **unused** — actual
  navigation is a manual `when (activeTab)` switch in `MainActivity.kt` and
  `UnifiedWorkspaceScreen.kt`. Don't introduce `NavHost` for a single new screen; follow the
  existing tab-switch convention unless doing a deliberate nav refactor.
- **Image loading:** Coil (`coil-compose`) is a declared dependency but **unused** — no
  `AsyncImage`/remote image loading exists anywhere. There is currently no pattern to copy
  if a Figma design requires a remote image; you'd be establishing the first usage.
- **No CSS/styled-components/Tailwind** — not applicable, this is native Android.
- **Build system:** Gradle Kotlin DSL (`build.gradle.kts` + version catalog
  `gradle/libs.versions.toml`), not npm/webpack/vite.

## 4. Asset Management

- `app/src/main/res/` has **no `font/`, `raw/`, or `assets/` folders**. Only launcher icons
  exist under `drawable/` and `mipmap-*dpi/` (adaptive icon XML + webp fallbacks).
- **No custom vector/raster icon assets in the app.** Every in-app icon comes from the
  Material Icons Extended library — nothing to export from Figma as a drawable unless it's
  a genuinely new glyph unavailable in Material Symbols.
- If a Figma design needs a custom icon or image not covered by Material Icons, it should
  be added as a vector drawable under `res/drawable/` and referenced via
  `painterResource()` — there is no existing example of this in the codebase to follow, so
  match standard Android vector-asset conventions (24x24dp viewport, single-color, XML
  `<vector>`).

## 5. Icon System

- **Icon set: Material Icons Extended, `Rounded` variant only**
  (`androidx.compose.material.icons.rounded.*`), plus `AutoMirrored.Rounded.*` for
  direction-sensitive icons (`ArrowBack`, `Chat`, `CompareArrows`, `Grading`, `Send`).
- **When mapping a Figma icon to code, target `Icons.Rounded.<Name>` first.** Do not use
  `Icons.Filled`/`Icons.Outlined`/`Icons.Sharp` even though they're technically available —
  it would be visually inconsistent with the rest of the app.
- Usage pattern to follow exactly:
  ```kotlin
  Icon(
      imageVector = Icons.Rounded.Dashboard,
      contentDescription = "...",
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(24.dp),
  )
  ```
- No icon-size token exists — sizes are contextual raw literals (16dp small stat icons,
  24dp headers, 28dp logo-scale). Match the size of similar icons already in the target
  screen rather than picking arbitrarily.

## 6. Styling Approach

- Pure Compose styling: `MaterialTheme.colorScheme.*` for color, `MaterialTheme.typography.*`
  for text, direct `Modifier` chaining for spacing/shape/elevation. No CSS methodology
  applies.
- No global stylesheet beyond `MyApplicationTheme { ... }` wrapping `setContent {}` in
  `MainActivity.kt`. `res/values/themes.xml` only governs pre-Compose system chrome
  (status bar / splash flash prevention via `DeviceDefault.NoActionBar`), not in-app
  widget styling.
- "Responsive" design = the phone/tablet `screenWidthDp >= 600` branch described in §2, not
  CSS media queries.

## 7. Project Structure

```
app/src/main/java/com/example/
├── MainActivity.kt              # entry point, Scaffold + tab switch, MyApplicationTheme root
├── CrashLoggingApplication.kt
├── data/                        # services, Room DB/DAOs/entities, MCP + Ollama/Gemini clients
├── ui/                          # flat: one file per screen, plus theme/
│   ├── *Screen.kt / *Dialog.kt
│   └── theme/{Color,Theme,Type}.kt
└── viewmodel/
    └── SwarmViewModel.kt        # single shared ViewModel for the whole app
```

- Screens are flat under `ui/` — no feature-based subfolders. Follow this when adding a
  screen for a new Figma design: a new top-level `ui/<Name>Screen.kt`, not a nested package.

## Practical checklist when implementing a Figma design here

1. Reuse existing `Immersive*` color tokens or existing raw hex literals before adding new
   ones — grep `Color(0xFF` in `ui/` first.
2. Snap spacing/corner-radius to the de-facto scale in §1, don't import exact Figma px.
3. Use `Icons.Rounded.*` (or `AutoMirrored.Rounded.*`) — never Filled/Outlined/Sharp.
4. Check sibling screen files for an existing local composable before writing a new one.
5. New screen state goes into `SwarmViewModel.kt` following its `MutableStateFlow`/
   `asStateFlow()` pattern.
6. New screens are flat files under `ui/`, wired into the `when (activeTab)` switch in
   `MainActivity.kt` — not `NavHost`.
7. Default to dark theme only; don't build light-mode-specific variants unless asked.
