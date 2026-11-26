# Dev Console design system

The shared look of the live developer pages built around OllamaDev and its sibling projects: Code Review Triage, Release Notes Drafter, Error Triage Board, Completion Log and the Chimera Dev Console. Warm paper in light, warm charcoal in dark, one ochre accent, mono for machine data.

This folder is a web kit. It is separate from the Android app's Compose theme in `app/src/main/java/com/example/ui/theme/`; bringing the two together is on the [to-do list](TODO.md).

The browsable version, with live previews and usage notes for every component, is the Dev Console design system artifact on claude.ai.

## Files

| File | What it is |
| --- | --- |
| `tokens.json` | Source of truth: colors for light and dark, type styles, spacing, radii, shadow, sizes. |
| `tokens.css` | The tokens as CSS custom properties (`--accent`, `--surface`, `--space-16`…), generated from `tokens.json`. |
| `dev-console.css` | Component styles, all prefixed `dc-`. Load after `tokens.css`. |
| `dev-console.js` | 19 React 18 components on `window.DevConsole`. No build step, no imports. |
| `dev-console.d.ts` | Props for every component, as documentation. |
| `example.html` | A Code Review Triage page built from the kit, with sample data. |

## Use it

With React:

```html
<link rel="stylesheet" href="tokens.css">
<link rel="stylesheet" href="dev-console.css">
<script src="https://cdnjs.cloudflare.com/ajax/libs/react/18.3.1/umd/react.production.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/react-dom/18.3.1/umd/react-dom.production.min.js"></script>
<script src="dev-console.js"></script>
```

Then build with `window.DevConsole.PageHeader`, `Panel`, `ListRow`, `Pill`, `StatCard` and the rest. `example.html` shows a full page.

Without React, load `tokens.css` and `dev-console.css` and use the classes directly: `dc-shell`, `dc-header`, `dc-panel`, `dc-panel-head`, `dc-row`, `dc-pill`, `dc-btn`.

With Claude, point it at this folder or the design system artifact and ask for a page "using the Dev Console design system".

## Rules in brief

- The eyebrow names the connector: `MCP-connected · GitHub Integration`.
- Titles are two or three words with the last word accented.
- State is always a color plus a word: a status dot next to a title, a pill naming the state on every row.
- Keep last-good data on screen when a refresh fails and say so in a stale banner.
- Mono for ids, times and figures; sans for people's words.
