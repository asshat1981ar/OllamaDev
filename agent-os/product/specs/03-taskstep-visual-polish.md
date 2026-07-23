# Feature Spec: TaskStep Icon/Color Dispatch

Tier 1 · Backlog ref: `agent-os/backlog.md` #3 · PRD ref: FR14

## Problem

`TaskStepTimelineItem` (`TaskStepComponents.kt`) renders `step.actionType`
as a plain monospace label for every value — including the 7 new ones this
feature introduced (`VERIFYING`, `EXEC_RESULT`, `EXEC_RESULT_FAILED`,
`CHECKPOINT_COMMIT`, `FILE_CHANGE_APPLIED`, `FILE_CHANGE_REJECTED`,
`ACTION_DECLINED`). A running loop is a wall of identically-styled rows —
nothing distinguishes "verified cleanly" from "declined by user" at a
glance.

## Approach

Add a `when (step.actionType)` dispatch in `TaskStepTimelineItem` mapping
each actionType to an `Icons.Rounded.*` icon + semantic color, reusing the
app's existing untokenized semantic hexes (per `CLAUDE.md` §1: success
`#4CAF50`/`#4ADE80`, error `#EF4444`/`#F44336`, warning `#FF9800`) rather
than inventing new colors — grep `Color(0xFF` in `ui/` first per the
checklist in `CLAUDE.md`.

Suggested mapping (grep the codebase for existing icon choices in similar
contexts before finalizing):
| actionType | Icon | Tone |
|---|---|---|
| `VERIFYING` | `Icons.Rounded.FactCheck` or similar | neutral/info |
| `EXEC_RESULT` | `Icons.Rounded.CheckCircle` | success |
| `EXEC_RESULT_FAILED` | `Icons.Rounded.ErrorOutline` | error |
| `CHECKPOINT_COMMIT` | `Icons.Rounded.History` (already used for the revert button) | success/neutral |
| `FILE_CHANGE_APPLIED` | `Icons.Rounded.CheckCircle` | success |
| `FILE_CHANGE_REJECTED` | `Icons.Rounded.Block` or `Cancel` | warning |
| `ACTION_DECLINED` | `Icons.Rounded.Block` | warning |

## Acceptance criteria

- [ ] All 7 new action types render with a distinct icon and color from
      the existing 12+ pre-harness action types.
- [ ] Existing action types (`THINKING`, `OUTPUT`, `PLAN`, etc.) are
      unaffected — this is additive, not a redesign of the timeline.
- [ ] No new hex colors introduced beyond the documented recurring
      semantic set in `CLAUDE.md` §1.
- [ ] Icons come from `Icons.Rounded.*` only (`CLAUDE.md` §5).

## Files touched

- `app/src/main/java/com/example/ui/TaskStepComponents.kt`

## Standards to follow

- `CLAUDE.md` §1 (color reuse), §5 (icon system) — read both before
  picking any new hex or icon.

## Out of scope

- Restructuring `TaskStep.actionType` into an enum/sealed class — stays a
  bare `String` per existing convention; this is presentation-layer only.
