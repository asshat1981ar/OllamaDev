# Claude automation

Claude Code Action is wired into this repo with five workflows. All are pinned by SHA, run with `permissions: {}` at the top level, and are advisory. None is a required check, and none should be: when the subscription's usage limit is reached, Claude jobs fail.

| Workflow | Trigger | What it does |
|---|---|---|
| `claude.yml` | `@claude` in an issue, PR comment or review (owner only) | Edits files and pushes a `claude/` branch. It cannot run Gradle or any repo script; CI runs the tests on its pushes |
| `claude-review.yml` | PR to `main` touching app/build files (non-draft, same repo) | Read-only multi-lens review of the diff. It does not read PR comments |
| `claude-screenshot-review.yml` | PR labeled `claude:screens` | Job `screenshots` runs Gradle on base and head with no secrets and uploads PNGs; job `design-review` checks out base only, downloads the PNGs and has Claude check them against the CLAUDE.md design rules |
| `claude-mcp-contract.yml` | Weekly (Wed) or manual | Compares tools the app calls with `ollamadev-mcp-server`; keeps one issue "MCP contract drift". No verdict is a WARN, never a pass |
| `agent-approval-check.yml` | PRs and comments | Requires your approval on PRs containing Claude commits |

## What each job can and cannot do

- `claude.yml` holds the OAuth token and a write-scoped App token, and tag mode checks out the PR head. So it refuses cross-repo (fork) PRs through the `Refuse cross-repo PRs` step (fail closed), allowlists only read-only `git` and `gh` commands, and sets `CLAUDE_CODE_SUBPROCESS_ENV_SCRUB=1`. No PR code is executed by an allowlisted tool.
- `.claude/settings.json` holds deny rules only. Each workflow grants its own tools through `--allowedTools`.
- `claude-screenshot-review.yml` does run PR head code (Gradle) in the `screenshots` job. That job has `contents: read` only, no secrets and no `id-token`, and only for same-repo PRs. The Claude job never sees head code, only PNGs that survived a plain-file, size-capped filter.

## Residual risks

- `--permission-mode auto` has a safety reviewer that may approve an unlisted command in `claude.yml`. The fork refusal and the env scrub reduce the impact; they do not remove it.
- Same-repo branches can carry hostile files (for example `CLAUDE.md` imports). Only people with write access can create them.
- Screenshot PNGs are attacker-influenced input to a model, from same-repo branches only.
- The download-artifact pin (`d3f86a10...`) was copied from another repo's workflow and carries a `# v4` comment; confirm the tag before relying on it.
- Firewall hosts for Gradle and Maven are unverified until the first run on that runner.

## Turn it on

1. Add the repo secret `CLAUDE_CODE_OAUTH_TOKEN`.
2. Install the Claude GitHub App (https://github.com/apps/claude) on this repo. Claude comments then come from claude[bot] and its pushes trigger CI.
3. Create the label `claude:screens`.
4. Keep `asshat1981ar/ollamadev-mcp-server` public (the contract job checks it out unauthenticated).
5. Optional repo variables: `CLAUDE_MODEL` (overrides the model for every job), `CLAUDE_RUNNER=ubuntu-24.04-firewall` once enrolled (uses `.github/egress-firewall.yaml`), `CLAUDE_PAUSE=true` to stop all Claude jobs.
6. After one green run of `agent-approval-check`, you may mark it as a required status check on `main`. It is deterministic and holds no model call. Do not require any `claude*` job.

## Commands

- `@claude <task>` in an issue or PR comment.
- `/approve <head-sha>` on a PR to satisfy the approval check.
- Add the label `claude:screens` to a UI PR for the screenshot review.

## Notes

- `android-ci.yml` only runs on PRs to `main`. Open the PR for a `claude/` branch, or run `gh workflow run "Android CI" --ref <branch>`.
- New unit-test packages must be added to a shard filter in `android-ci.yml`, or they never run in the merge gate.
- If the subscription's usage limit is reached, Claude jobs fail; the merge gate is unaffected.
