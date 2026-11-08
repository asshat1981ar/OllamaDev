# Claude automation

Claude Code Action is wired into this repo with five workflows. All are pinned by SHA, run with `permissions: {}` at the top level, and are advisory (none is a required check).

| Workflow | Trigger | What it does |
|---|---|---|
| `claude.yml` | `@claude` in an issue, PR comment or review (owner only) | Implements on a `claude/` branch, runs Gradle tests |
| `claude-review.yml` | PR to `main` touching app/build files (non-draft, same repo) | Read-only multi-lens review |
| `claude-screenshot-review.yml` | PR labeled `claude:screens` | Records base and head Roborazzi screenshots and checks them against the CLAUDE.md design rules |
| `claude-mcp-contract.yml` | Weekly (Wed) or manual | Compares tools the app calls with `ollamadev-mcp-server`; keeps one issue "MCP contract drift" |
| `agent-approval-check.yml` | PRs and comments | Requires your approval on PRs containing Claude commits |

## Turn it on

1. Add the repo secret `CLAUDE_CODE_OAUTH_TOKEN`.
2. Install the Claude GitHub App (https://github.com/apps/claude) on this repo. Claude comments then come from claude[bot] and its pushes trigger CI.
3. Create the label `claude:screens`.
4. Keep `asshat1981ar/ollamadev-mcp-server` public (the contract job checks it out unauthenticated).
5. Optional repo variables: `CLAUDE_MODEL` (overrides the model for every job), `CLAUDE_RUNNER=ubuntu-24.04-firewall` once enrolled (uses `.github/egress-firewall.yaml`), `CLAUDE_PAUSE=true` to stop all Claude jobs.
6. After one green run, mark `agent-approval-check` as a required status check on `main`.

## Commands

- `@claude <task>` in an issue or PR comment.
- `/approve <head-sha>` on a PR to satisfy the approval check.
- Add the label `claude:screens` to a UI PR for the screenshot review.

## Notes

- `android-ci.yml` only runs on PRs to `main`. Open the PR for a `claude/` branch, or run `gh workflow run "Android CI" --ref <branch>`.
- New unit-test packages must be added to a shard filter in `android-ci.yml`, or they never run in the merge gate.
- If the subscription's usage limit is reached, Claude jobs fail; the merge gate is unaffected.
- Firewall hosts for Gradle and Maven are unverified until the first run on that runner.
