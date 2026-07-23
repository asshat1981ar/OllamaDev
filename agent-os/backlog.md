# OllamaDev — Agentic Harness Backlog (self-prompt)

Paste this whole file back to me (or run it via `/loop`) to resume systematic,
highest-impact-first work on the agentic coding harness shipped in PR #1
(`7e72f8c`). Work top to bottom — each tier is ordered by impact-per-effort,
not by dependency, so within a tier pick whichever the user cares about most.

## How to work through this

For each item:
1. **Re-read the relevant code** (file paths are given below) before touching
   anything — the backlog was written from a snapshot, code may have moved.
2. **Plan briefly in chat** (not a new plan-mode session unless the change is
   large/ambiguous) — one paragraph on the approach, referencing existing
   patterns from `CLAUDE.md` / `agent-os/standards/`.
3. **Implement.**
4. **Verify** via the `run-ollamadev` skill: build + relevant unit tests at
   minimum; screenshot if the change touches UI.
5. **Stop and summarize** — do not commit, push, or open a PR without being
   asked again. Each tier is a natural checkpoint to report back at.
6. **Check off the item below** (edit this file) so re-runs don't repeat work.

Don't batch multiple backlog items into one commit later — keep them
separable, matching how PR #1 was scoped.

---

## Tier 1 — cheap, high leverage (do these first)

- [ ] **Surface MCP risk-reasoning to the user.** `isRiskyMcpCall()` in
      `SwarmEngine.kt` silently decides *why* a call was gated (a real
      `destructiveHint`/`readOnlyHint` annotation vs. a keyword match) but
      that reasoning never reaches the approval dialog — right now a gated
      call just looks arbitrary. Thread the matched reason into
      `PendingApproval.detail` (already a field) and render it in the
      `MainActivity.kt` approval dialog. Small, no schema change, directly
      improves trust in a feature just shipped.
- [ ] **Cost/budget guardrail for cloud-preferred routing.** `preferCloud`
      routing (added this session) means every agentic-loop task can burn up
      to 16 iterations × 2 LLM calls against the paid Ollama Cloud Gateway
      with zero spend visibility. Add a running token/cost estimate (reuse
      the existing `(prompt.length + output.length) / 2 + 100` approximation
      pattern already used for `AgentStateStore.recordExecutionMetrics`) and
      either a soft warning or a configurable per-task cap before the loop
      starts iteration N+1. This is a real-money risk introduced this
      session — treat it as closer to a bug than a feature.
- [ ] **TaskStep icon/color dispatch.** `TaskStepTimelineItem` in
      `TaskStepComponents.kt` renders every `actionType` (including the new
      `VERIFYING`/`EXEC_RESULT`/`CHECKPOINT_COMMIT`/`FILE_CHANGE_APPLIED`/
      `FILE_CHANGE_REJECTED`/`ACTION_DECLINED` values) as plain monospace
      text. Add a `when(actionType)` icon/semantic-color mapping (flagged as
      an explicit deferred item in the original plan) so a running loop is
      scannable at a glance instead of a wall of identical-looking rows.

## Tier 2 — moderate effort, closes real UX gaps

- [ ] **"Awaiting Approval" agent status.** `AgentMetrics.status` has no
      value for "blocked waiting on you" — the dialog is the only signal,
      invisible if the user is on another tab. `executeAgenticGitCommand`/
      `executeAgenticMcpCall` only receive `agentName: String`, not an
      `agentId`, so this needs either a DB lookup by name or threading an
      id through the call chain — scope that decision explicitly before
      starting. Pair with a distinct badge color in `AgentScreen.kt`
      (`#FF9800`, already a documented recurring semantic color per
      `CLAUDE.md` §1).
- [ ] **Batch multi-file WRITE_FILE review.** Today each `WRITE_FILE:`
      directive triggers its own `PendingFileChange` round-trip and its own
      dialog — a task touching 5 files means 5 sequential approvals. Extend
      `PendingApprovalStore`/`executeAgenticFileWrite` to collect all
      `WRITE_FILE:` directives from one act-step's output and request one
      batched review (list of diffs, single accept/reject-per-file or
      accept-all). This is the biggest usability gap in the file-write path
      as shipped.

## Tier 3 — bigger bets (highest ceiling, most scope)

- [ ] **Real MCP test-runner integration.** The verify phase assumes *some*
      Connected MCP server can run tests, but no such server exists in this
      project today — every `SwarmEngineVerifyLoopTest` scripts a fake one.
      This is the single item that would make "real execution" actually
      real instead of hypothetical. Scope it down before starting: either
      (a) write and document a minimal reference execution-sandbox MCP
      server (even just "run pytest/gradle in a container, return
      pass/fail + output") as a companion project, or (b) find and wire up
      an existing open-source MCP test-runner server via the registry
      browser (`RegistryBrowserDialog.kt`) and document the setup. Don't
      start this without picking (a) or (b) explicitly first — it's easy to
      scope-creep into building a whole sandboxing platform.
- [ ] **In-app task analytics.** `SwarmTask.executionTimeMs`/`tokenUsage`
      are already recorded but nothing reads them in aggregate. A new
      screen (flat file under `ui/`, wired into `MainActivity.kt`'s
      `when (activeTab)`, state in `SwarmViewModel.kt` per the existing
      pattern) showing cost/time trends per `SwarmConfig` and
      `[UNRESOLVED]` rate over time would answer "is the harness actually
      working" without leaving the app.
- [ ] **Background execution + notification.** Long agentic-loop runs
      currently require keeping `SessionScreen` open. A foreground
      `Service` + notification channel would let a task run unattended.
      Highest infra cost of anything on this list (Android service
      lifecycle, notification permissions on API 33+) — do this last unless
      it's specifically requested sooner.

---

## Deferred, not re-litigated here

These were flagged as explicit open decisions in the original plan and are
intentionally *not* on this backlog — revisit only if they start causing
real friction:
- `OllamaNode.isCloud` explicit field vs. the current name/URL heuristic in
  `isCloudGatewayNode()`.
- `GitCommit.stepId` FK (currently only `taskId`) for finer per-step
  checkpoint traceability.
