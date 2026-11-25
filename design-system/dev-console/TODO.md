# Dev Console: to-do list

What it would take to grow this kit from five web pages into the shared look of OllamaDev and the projects around it. Highest value first within each section.

## 1. Bring existing pages onto the kit

- [ ] Rebuild the five source pages (Code Review Triage, Release Notes Drafter, Error Triage Board, Completion Log, Chimera Dev Console) on `tokens.css` and `dev-console.js` instead of their copied stylesheets. This also gives them the darker `text-faint`, which now passes 4.5:1.
- [ ] Move Repo Pulse onto the kit. It already has the same parts (stat tiles, panels, chips, skeletons, a live status pill) in a purple palette.
- [ ] Decide whether the Plugin & Skill Hub joins too, or stays its own look.

## 2. Close the accessibility gaps

- [x] Light theme soft pills (`ok`, `danger`, `backlog` on their `-soft` fills) were 4.2–4.3:1. Light `ok`, `danger` and `backlog` are now darker and reach 4.6:1 or better.
- [x] Light theme stale banner (`accent` on `accent-soft`) was 3.6:1. Light `accent` is now `#845c1a`, 4.6:1 there and 5.7:1 under button text.
- [x] Inputs relied on a `border` hairline of about 1.45:1. Inputs and selects now use `border-control`, 3:1 or better in both themes.
- [ ] Check every preview with reduced motion on and with keyboard only.

## 3. Bring the tokens into the Android app

The app has its own dark-only "Immersive" palette in `Color.kt`, and `CLAUDE.md` notes 195 raw hex colors and no `Dimens.kt` or shape file.

- [ ] Decide: should the app adopt Dev Console colors, or keep Immersive and share only spacing, radii and semantic colors?
- [ ] Add `Dimens.kt` from the spacing tokens (the app's own scale of 4–16 dp is close to Dev Console's 4–22 px).
- [ ] Add a shape file from the radius tokens.
- [ ] Map `ok`, `danger`, `accent` and `started` to named Compose colors and replace the recurring raw greens, reds and oranges with them.
- [ ] Add shared composables for the parts every screen re-creates: status dot, pill, panel, stat card (one already lives inside `DashboardScreen.kt`).

## 4. One source for the tokens

- [ ] Add a script that turns `tokens.json` into `tokens.css` and a Kotlin token file, so both platforms read the same values.
- [ ] Add a CI check that fails when `tokens.css` or the Kotlin file is out of date with `tokens.json`.
- [ ] Keep the claude.ai design system artifact in step with this folder whenever tokens change.

## 5. Fill the component gaps

These came up in related pages but are not in the kit yet.

- [ ] Live status pill with a breathing dot (from Repo Pulse).
- [ ] Commit row with a dot rail and short SHA chip (from Repo Pulse).
- [ ] Data table for wider results, with a scrolling container.
- [ ] Tabs, for consoles with more than two panels.
- [ ] A small sparkline or bar for throughput figures on stat cards.
- [ ] An in-page confirm step for actions that change data (the Sentry update form applies straight away today).

## 6. Build new consoles with it

- [ ] GitHubOpsGem gate status: warn-week events per rule, ready-to-graduate rules.
- [ ] B200 gateway health: deployment state, latency, error rate.
- [ ] OllamaDev CI board: workflow runs across the repo's eight workflows.
