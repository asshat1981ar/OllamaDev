# Feature Spec: Batched Multi-File WRITE_FILE Review

Tier 2 · Backlog ref: `agent-os/backlog.md` #5 · PRD ref: FR16

## Problem

Each `WRITE_FILE: <path>` line in an act step's output triggers its own
`executeAgenticFileWrite` call → its own dedicated content round-trip → its
own `PendingApprovalStore.requestFileChangeReview` → its own dialog. A task
that legitimately touches 5 files means 5 sequential blocking dialogs
across the same act step, each requiring a separate user gesture.

## Approach

1. In `parseAndExecuteAgenticActions`, instead of calling
   `executeAgenticFileWrite` once per `WRITE_FILE:` line as encountered,
   collect all `WRITE_FILE:` paths from one `output` into a list first.
2. Add a new function (e.g. `executeAgenticFileWriteBatch`) that:
   - Runs the dedicated content round-trip for each path (can be
     sequential or parallelized with `async`/`awaitAll` — sequential is
     simpler and matches the existing single-file latency profile; only
     parallelize if the extra complexity is justified by measured latency).
   - Builds a `List<PendingFileChange>` and requests ONE batched review via
     a new `PendingApprovalStore.requestFileChangeReviewBatch(changes): List<Boolean>`
     (or `Map<String, Boolean>` keyed by path) — new method, additive to
     the existing single-file one (keep `requestFileChangeReview` for
     backward compatibility / potential future single-file callers).
3. New dialog state (`pendingFileChangeBatch: StateFlow<List<PendingFileChange>?>`)
   and a `MainActivity.kt` dialog rendering a scrollable list of diffs with
   per-file accept/reject toggles plus an "Apply all" / "Reject all"
   shortcut.

## Acceptance criteria

- [ ] A task with N `WRITE_FILE:` directives in one act-step output
      produces exactly one dialog, not N.
- [ ] Per-file accept/reject is possible, not just all-or-nothing (unless
      product decides all-or-nothing is acceptable for v1 — note the
      simplification explicitly if so).
- [ ] Single-`WRITE_FILE:` act steps still work (regression check against
      `SwarmEngineFileWriteTest`).

## Files touched

- `app/src/main/java/com/example/data/SwarmEngine.kt` (`parseAndExecuteAgenticActions`, new batch function)
- `app/src/main/java/com/example/data/PendingApprovalStore.kt` (new batch gate)
- `app/src/main/java/com/example/MainActivity.kt` (new batched dialog)
- `app/src/test/java/com/example/data/SwarmEngineFileWriteTest.kt` (extend with a multi-file case)

## Standards to follow

- `agent-os/standards/state/singleton-store-for-cross-cutting-state.md` —
  same `CompletableDeferred` pattern, just batched payload.
- `CLAUDE.md` §2, §6 — dialog conventions, spacing/scroll for a
  potentially long list of diffs (`overflow-x`/scroll container per file
  diff, not the whole dialog scrolling awkwardly).

## Out of scope

- Cross-file conflict detection (two proposed files touching the same
  logical resource) — out of scope, each file reviewed independently.
- Re-using this batched mechanism for the existing single-file self-healing
  dialog — leave that as-is.
