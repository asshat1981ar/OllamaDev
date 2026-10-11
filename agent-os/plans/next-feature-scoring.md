# OllamaDev — Next Feature Weighted Scoring System

Generated: 2026-07-29
Purpose: rank candidate next features by impact, differentiation, effort, risk, and architectural fit.

## Scoring criteria

| Criterion | Weight | Direction | Why it matters |
|---|---|---|---|
| User impact | 20% | higher = better | Daily value to a developer using the app |
| Strategic differentiation | 20% | higher = better | How much it separates OllamaDev from desktop/IDE agents and chat clients |
| Market timing / competitive pressure | 15% | higher = better | Trend fit and urgency vs. competitors |
| Implementation effort | 15% | higher = easier | Can we ship it without blocking other work |
| Architectural fit | 10% | higher = better | Reuses existing deep modules (`SwarmEngine`, `LlmRouter`, `AgenticActionExecutor`, `StepRunner`, `PendingApprovalStore`) |
| Technical risk | 10% | higher = lower risk | Likelihood of destabilizing tests or runtime |
| Quality / testability / trust | 10% | higher = better | Improves reliability or has clear verification path |

Scores are 1–5 per criterion. Weighted total = Σ(score × weight).

## Candidate features

| Rank | Feature | Score | Tags |
|---|---:|---|---|
| 1 | **Headless approval policy for background service** | **4.05** | Tier 6.3, background, trust, deadlock-fix |
| 2 | GitHub PR export from sprint/workspace artifacts | 3.95 | new, sharing, github, competitive-gap |
| 3 | Voice-driven follow-up and session memory | 3.85 | new, voice, mobile-differentiation |
| 4 | Persistent notification quick actions | 3.80 | new, mobile-ux, background |
| 5 | MCP skill marketplace / discovery UI | 3.80 | new, mcp, ecosystem, registry |
| 6 | On-device model download / manager | 3.50 | new, local-first, privacy, big-bet |
| 7 | AGENTIC_LOOP verify-prompt regression test | 3.35 | Tier 6.1, reliability, test-only |
| 8 | Room destructive-migration safeguard | 3.25 | Tier 6.4, data-safety, schema |
| 9 | Budget token heuristic precision | 2.75 | Tier 6.5, cost, trust, low-priority |
| 10 | Resolve orphan WorkspaceViewModel | 2.40 | Tier 6.2, technical-debt, investigation |

## Raw scores

```
headless_approval          user=4 diff=4 timing=3 effort=4 fit=5 risk=4 quality=5 -> 4.05
github_pr_export           user=5 diff=5 timing=5 effort=2 fit=3 risk=3 quality=3 -> 3.95
voice_followup             user=4 diff=5 timing=4 effort=3 fit=4 risk=3 quality=3 -> 3.85
notification_quick_actions user=4 diff=3 timing=4 effort=4 fit=4 risk=4 quality=4 -> 3.80
mcp_marketplace            user=4 diff=4 timing=5 effort=3 fit=4 risk=3 quality=3 -> 3.80
ondevice_model_manager     user=5 diff=5 timing=5 effort=1 fit=2 risk=2 quality=2 -> 3.50
verify_regression          user=2 diff=2 timing=2 effort=5 fit=5 risk=5 quality=5 -> 3.35
migration_safeguard        user=3 diff=2 timing=3 effort=4 fit=4 risk=4 quality=4 -> 3.25
budget_token_precision     user=2 diff=2 timing=2 effort=3 fit=4 risk=4 quality=4 -> 2.75
workspace_viewmodel_verdict user=1 diff=1 timing=1 effort=5 fit=3 risk=5 quality=3 -> 2.40
```

## Recommended next feature

**Headless approval policy for `AgenticLoopService`**

Rationale:
- Background execution was just shipped (Tier 3c) but the release notes explicitly flag the deadlock risk: an approval gate fired from a headless service blocks forever because the UI dialog cannot be shown.
- Fixing this unlocks the background-service feature's trust model without requiring new UI screens or external integrations.
- It builds directly on `PendingApprovalStore`, `AgenticActionExecutor`, and the existing notification helpers.
- It is testable with a fake approval gate and well-scoped.
- It is the cheapest high-leverage item on the Tier 6 backlog.

Implementation sketch:
1. Add `isHeadless` / `callerIsService` flag to the approval request path.
2. In `AgenticActionExecutor`, when a risky directive is parsed inside a headless context, auto-decline (or apply a configurable conservative policy) instead of suspending on `PendingApprovalStore`.
3. Record an `APPROVAL_SKIPPED_HEADLESS` `TaskStep` with the agent name, action type, and reason.
4. Surface the skip count + reason in the final foreground notification and in `SessionScreen` when the user reopens the app.
5. Add unit test `AgenticActionExecutorHeadlessApprovalTest` covering git-push and destructive MCP paths.

Strong runner-up: **GitHub PR export from sprint artifacts** — bigger strategic payoff (bridges local agent work to shareable review) but needs PAT handling, GitHub API integration, and new UI, so it is a larger bite.
