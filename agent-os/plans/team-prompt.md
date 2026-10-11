# OllamaDev — Augmented Team Prompt (Tier-6 Circular Workflow)

> **How to use:** the orchestrator quotes this file in every cycle brief, together
> with the backlog item and the latest `tier6-cycle-log.md` entry. It is the
> standing context every subagent starts with — distilled from Cycle 0's codebase
> analysis, the Tier 1–5 release notes, the WorkspaceViewModel extraction spec,
> the standards index, and the installed skills/plugins inventory.
> **Last distilled:** 2026-07-29 (Cycle 0). Re-distill when facts go stale.

## 1. Mission & current state

- OllamaDev ("Ollama Swarm") is a native Android app — Kotlin 2.2.10, Jetpack
  Compose, Material 3, Room, JGit, MCP client — that orchestrates swarms of LLM
  agents against a pool of Ollama nodes. No emulator exists on this host.
- Backlog Tiers 1–5 are COMPLETE (PRs #7–16). Active queue: Tier 6 items
  6.1–6.6 in `agent-os/backlog.md`, impact-per-effort order.
- Deep modules (`CONTEXT.md`, `docs/adr/ADR-0001`): `LlmRouter` (single entry
  point for every LLM call, appends skills context to agent system prompts),
  `AgenticActionExecutor` (parses `MCP_CALL:`/`WRITE_FILE:`/git directives and
  performs real side effects behind the human-approval gate), `StepRunner`
  (shared 8-beat agent-step loop). `SwarmEngine` is thin policy composing them.

## 2. Carried-forward facts (Cycle 0 distillation)

- `SwarmEngine.kt` is 634 lines post-refactor; AGENTIC_LOOP act→verify pipeline
  at ~lines 386–450. The verify prompt interpolates `actResult.output`
  (declared :397) — the RELEASE_NOTES-flagged `decision` bug *looks* already
  fixed; cycle 6.1 locks it with a regression test.
- `app/src/main/java/com/example/viewmodel/WorkspaceViewModel.kt` (642 lines)
  is untracked and referenced nowhere — an extraction-in-progress from
  `SwarmViewModel.kt` (1500+ lines). Cycle 6.2 decides its fate. Do not wire it
  in, delete it, or build on it outside that cycle.
- Untracked and off-limits for commits/reverts: `.agents/`, `.cline/`,
  `.clineignore`, `.hermes/`, `agent-os/plans/`, `skills-lock.json`,
  `WorkspaceViewModel.kt`.
- Test stack: JUnit + Robolectric + Compose + Roborazzi. Fakes:
  `app/src/test/java/com/example/ui/FakeAppDatabase.kt`, `TestDoubles.kt`;
  data-layer fakes live beside their test classes.
- Host: 5.3 GB RAM (WSL, `PECOS1`). **Never run two Gradle builds concurrently.**
  Cold Robolectric test class = 2–4 min (`forkEvery = 1` is deliberate).
  `gradle.properties` is tuned for this host — do not change it.
- **Environment drift (verified 2026-07-29):** JDK 21 is gone — only OpenJDK 17
  exists; the build works on it. WSL IPv4 loopback is broken, so EVERY
  `./gradlew` invocation must be prefixed with
  `JAVA_TOOL_OPTIONS='-Djava.net.preferIPv6Addresses=true'` or the client dies
  with "Could not connect to the Gradle daemon" (Gradle 9 always forks a
  daemon, even `--no-daemon`). That flag also breaks Maven downloads (IPv6
  preferred externally) — if a build fails "Network is unreachable" on a
  dependency, fetch its pom+jar via `curl -4` into `/tmp/m2repo` and add
  `-I /tmp/local-repo.init.gradle` (recipe in the `run-ollamadev` skill).
  Also seen: an unidentified actor ran `gradlew --stop` mid-build — if a
  build dies with "stop command received", treat it as external
  interference, re-run, and report it. (A second agent session — `.opencode/`
  dir appeared 19:35 — was implementing backlog 6.3 in this tree on
  2026-07-29 ~19:43–19:50.)
- No emulator, no `/dev/kvm`, broken `adb`. Robolectric is the only way to "run
  the app" — see the `run-ollamadev` skill.

## 3. Working conventions (binding on every role)

1. **Re-read the relevant code first.** The backlog was written from a snapshot;
   code moves. Trust the working tree over any brief, including this one.
2. **Verify via the `run-ollamadev` skill**: `./gradlew assembleDebug
   --console=plain` + targeted `./gradlew testDebugUnitTest --tests <Class>
   --console=plain` at minimum. For screenshots use `recordRoborazziDebug`
   (plain `testDebugUnitTest` silently writes no images).
3. **No commits, pushes, or PRs.** This overrides the "commit your work" step
   in the subagent-driven-development implementer template. Leave changes in
   the working tree; reviewers review the working-tree diff.
4. **Evidence or it didn't happen.** Quote real command output and file:line
   references. If the environment is broken, escalate — never fabricate test
   results (hard rule carried from the WorkspaceViewModel spec).
5. **One backlog item per cycle.** Keep changes separable, matching how PR #1
   and PRs #7–16 were scoped.

## 4. Skills/plugins augmentation map — invoke, don't improvise

| Situation | Must invoke / use |
|---|---|
| Any code change | `test-driven-development` — RED→GREEN evidence in the report |
| Before reporting DONE | `verification-before-completion` — re-run the command, paste real output |
| Bug confirm/fix cycles (6.1, 6.3) | `systematic-debugging` — root cause before fix |
| Design/ADR cycles (6.4) | `brainstorming`/`writing-plans` patterns for the decision doc |
| Build/test/screenshot procedure | `run-ollamadev` skill (`.claude/skills/run-ollamadev/SKILL.md`) |
| Long Gradle runs | background-terminal capability — run in background, poll output |
| File reads | `gitignore-read-files-guard` plugin is active — `.gitignore`d paths (`.env`, `local.properties`, build outputs) are off-limits; never read them |
| Reviewer dispatches | `requesting-code-review` + the reviewer prompts in `.agents/skills/subagent-driven-development/` |
| Skill maintenance | `writing-skills` (only if a skill itself is the task) |

## 5. Standards digest (`agent-os/standards/` — one line each)

- **backend/swarm-engine-llm-pool** — all LLM calls through `LlmRouter`; no
  cloud/Gemini fallback exists.
- **data/interface-seam** — I/O behind interfaces with default-arg injection
  (`AppDatabaseInterface`, `SecurePrefsInterface`, `OllamaService`,
  `LlmRouterInterface`, `AgenticActionExecutorInterface`); test through fakes.
- **data/secure-prefs** — secrets via EncryptedSharedPreferences only.
- **git/mirror-before-commit** — sync `WorkspaceFile` rows into GitService's
  real workDir before any git op.
- **git/pat-https-push** — PAT-over-HTTPS only, no SSH.
- **mcp/dual-response-parsing** — MCP via `buildRequest()`/`parseResponse()`
  (JSON-or-SSE).
- **mcp/remote-only-transport** — Streamable-HTTP only, no stdio.
- **mcp/registry-type-inference** — extend the `when` branches for new types.
- **state/sharing-started-strategy** — `WhileSubscribed` for DB flows,
  `Eagerly` for singletons.
- **state/singleton-store-for-cross-cutting-state** — `AgentStateStore` /
  `PendingApprovalStore` are sanctioned singletons; do NOT inject them.
- **state/viewmodel-toast-feedback** — one-off UI feedback via Toast from the
  ViewModel, no event bus.
- **testing/screenshot-testing** — Roborazzi via Robolectric.
- **testing/spike-test-convention** — throwaway `<Library>SpikeTest.kt` before
  wrapping a new third-party library.
- **UI (CLAUDE.md)** — `Immersive*` color tokens (grep `Color(0xFF` before
  adding hex), `Icons.Rounded.*`/`AutoMirrored.Rounded.*` only, de-facto dp
  scale (pad 4–24, spacedBy 2–16, corners 0–16), dark-only, screens flat under
  `ui/`, state in `SwarmViewModel`'s `MutableStateFlow`/`asStateFlow()` pattern.

## 6. Role prompts

### 6a. Implementer (fresh subagent per cycle)

```
You are the implementer for Tier-6 cycle <N>: <backlog item title>.

## Standing context — read first, in this order:
1. agent-os/plans/team-prompt.md (sections 1–5 bind you)
2. The backlog item: agent-os/backlog.md, item <N>
3. The latest entry in agent-os/plans/tier6-cycle-log.md
Then re-read the relevant production/test code before touching anything.

## Your job
1. Ask questions NOW if anything is unclear (BLOCKED/NEEDS_CONTEXT beats guessing).
2. Implement exactly what the item specifies — nothing more (YAGNI).
3. Follow TDD: RED (failing output quoted) → GREEN (passing output quoted).
4. Verify per team-prompt §3.2 and paste real command output.
5. Self-review with fresh eyes: completeness, quality, discipline, tests.
6. DO NOT commit, push, or open PRs. Leave changes in the working tree.
7. Write your full report to <REPORT_FILE>, then reply with the §7 contract.

## Escalation
It is always OK to stop and say "this is too hard for me." Report BLOCKED or
NEEDS_CONTEXT with specifics: what you're stuck on, what you tried, what help
you need. Bad work is worse than no work.

## Code organization
Follow existing patterns (team-prompt §5). One clear responsibility per file.
Don't restructure outside your item. If a file grows beyond the item's intent,
report DONE_WITH_CONCERNS instead of splitting files on your own.
```

### 6b. Task reviewer (per cycle, after implementer)

Adapted from `.agents/skills/subagent-driven-development/task-reviewer-prompt.md`
with one change: cycles produce NO commits, so the "diff under review" is the
working-tree diff (`git diff` for tracked files, plus full content of new
files), captured by the orchestrator into <DIFF_FILE>. Verdicts: Spec
Compliance ✅/❌/⚠️, Strengths, Issues (Critical/Important/Minor with
file:line), Task quality (Approved | Needs fixes). Do not trust the
implementer's report — verify claims against the diff. Do not re-run the
suite; a focused test only for a specific named doubt.

### 6c. Scoped re-reviewer (per fix round)

Per `.agents/skills/subagent-driven-development/re-review-prompt.md`: verdict
each finding (ADDRESSED / NOT ADDRESSED with file:line), inspect the fix diff
for new breakage, out-of-scope observations are non-blocking. Fix-round cap:
5; rounds 1–3 resume the implementer, round 4+ gets a fresh implementer,
round 5 ends in orchestrator adjudication or BLOCKED.

### 6d. Final reviewer (after all cycles)

Whole-working-tree review: spec compliance across all cycle items, cross-cycle
regressions, triage of parked Minor findings. Most capable reviewer available.

## 7. Report contract

Implementer's final reply (≤15 lines; detail lives in <REPORT_FILE>):
- **Status:** DONE | DONE_WITH_CONCERNS | BLOCKED | NEEDS_CONTEXT
- Files changed (paths; "uncommitted working tree" — no SHAs exist)
- One-line test summary with evidence (e.g. "14/14 passing, output pristine")
- TDD evidence pointers (RED/GREEN command + output location in report file)
- Concerns, if any
- The report file path

## 8. Feed-forward contract

After each cycle the orchestrator:
1. Verifies the evidence (diff + test output) — review, don't trust.
2. Appends a distilled entry to `agent-os/plans/tier6-cycle-log.md` (newest on
   top): what changed, verdicts, file:line anchors, facts the next cycle needs.
3. Checks off the backlog item in `agent-os/backlog.md`.
4. Quotes the new latest entry into the next cycle's brief.
5. Parks unfixed Minor findings in the log for the final reviewer.
