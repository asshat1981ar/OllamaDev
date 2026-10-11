# Augmented Team Prompt & Tier-6 Team Launch — Design

**Date:** 2026-07-29
**Status:** Approved by user (plan-mode approval, then act-mode switch)
**Scope:** docs + orchestration + one scoped app code change (backlog 6.6)

## Context — previous outputs analyzed

| Source | What it contributed |
|---|---|
| `agent-os/plans/tier6-cycle-log.md` (Cycle 0) | Codebase facts: SwarmEngine 634 lines, act→verify pipeline ~386–450, orphan `WorkspaceViewModel.kt`, host constraints (5.3 GB RAM, one Gradle build at a time, Robolectric 2–4 min cold), no-commit rule, untracked-files rule |
| `agent-os/backlog.md` | Tiers 1–5 complete (PRs #7–16); Tier-6 queue 6.1–6.5; working conventions |
| `.hermes/plans/2026-07-27_Extract-WorkspaceViewModel-Spec.md` | Prior team's spec format, pitfalls, "never fabricate test results" rule; feeds 6.2 |
| `RELEASE_NOTES.md` | Known limitations = origin of Tier-6 items |
| `agent-os/standards/index.yml` + `CLAUDE.md` + `CONTEXT.md` | Standards/UI/deep-module digests for every brief |
| Skills/plugins inventory | Superpowers skills (`.agents/skills/`), `run-ollamadev` (`.claude/skills/`), Cline plugins (`background-terminal`, `gitignore-read-files-guard`) |

User request: analyze these outputs and, via LLM contextual augmentation, build the
team prompt from them — all four deliverables (prompt doc, team launch, protocol
upgrade, in-app prompt augmentation).

## Decision — Approach A: docs-first, app change becomes backlog item 6.6

1. Write the augmented team prompt + upgrade the cycle protocol.
2. Add "LlmRouter contextual prompt augmentation" to the backlog as item 6.6.
3. Launch one team (subagent-driven-development) to execute cycles 6.1 → 6.6,
   each briefed with the augmented prompt + latest cycle-log entry.

Alternatives rejected: (B) orchestrator implements the app change directly —
splits execution across two mechanisms and skips the implementer→reviewer loop;
(C) delegate everything with a minimal prompt — the foundational artifact gets
the least scrutiny.

## Deliverables

1. **`agent-os/plans/team-prompt.md`** — standing context quoted into every cycle
   brief: mission/state, carried-forward facts, binding conventions, skills/plugins
   augmentation map, standards digest, role prompts, report + feed-forward contracts.
2. **Protocol upgrade** — `tier6-cycle-log.md` BRIEF step references
   `team-prompt.md`; `backlog.md` conventions reference it; new item 6.6.
3. **Team execution** of cycles 6.1–6.6: fresh implementer subagent per cycle,
   task review after each, distilled outcome appended to the cycle log.
4. **Cycle 6.6 — LlmRouter contextual augmentation**: extend
   `LlmRouter.buildSkillsContext()` so `generateForAgent` system prompts also carry
   the directive reference (`WRITE_FILE:`, git directives — today only `MCP_CALL`
   is documented, a real gap), approval-gate awareness, and a compact standards
   reminder. Scoped to `generateForAgent` only; `generateFreeform`/`routePrompt`
   callers (routing orchestrator, consensus moderator, distillation) stay clean.
   Char-budget conscious (ties to 6.5). TDD via `LlmRouterTest`/`FakeAppDatabase`.

## Key adaptations from stock subagent-driven-development

- **No commits.** Repo convention (Cycle 0, backlog) overrides the SDD implementer
  template's "commit your work" step. Reviewers review the **working-tree diff**
  (`git diff` for tracked files + full content of new files) instead of commit ranges.
- **Sequential cycles**, not parallel: the feed-forward log requires it, and the
  5.3 GB RAM host forbids concurrent Gradle builds.
- Fix-round cap of 5 per cycle (SDD default), then adjudicate or report BLOCKED.

## Verification

- Every code cycle: `./gradlew assembleDebug --console=plain` + targeted
  `testDebugUnitTest --tests <Class>` per the `run-ollamadev` skill; real output
  quoted in the implementer report; no fabricated results (escalate instead).
- Final whole-branch review after all cycles (working-tree diff).
