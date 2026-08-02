#!/usr/bin/env bash
#
# delegate-build.sh — run an arbitrary gradlew/build task on a GitHub Actions runner and
# pull the artifacts back into the local workspace. This is the "delegate to GitHub" half of
# the remote-build-delegation mechanism (workflow: .github/workflows/remote-build.yml).
#
# Local builds live in an aarch64 userland that needs a QEMU/aapt2 emulation shim, is slow,
# and cannot render real Roborazzi pixels. Delegating to a fresh x86_64 runner side-steps all
# of that: real APKs, real JUnit reports, and real anti-aliased screenshots.
#
# Usage:
#   scripts/delegate-build.sh [options] [gradle-task...]
#
# Options:
#   -b, --branch <name>   Branch/ref to check out and build (default: current branch).
#   -c, --commit <msg>    Commit the current working-tree changes to <branch> and push first
#                         (workflow_dispatch cannot see uncommitted local changes).
#       --repo <owner/repo>  Repo to run against (default: asshat1981ar/OllamaDev).
#   -o, --outdir <dir>    Where to download artifacts (default: ./ci-artifacts).
#   -h, --help            Show this help.
#
# Examples:
#   scripts/delegate-build.sh :app:assembleDebug
#   scripts/delegate-build.sh ':app:testDebugUnitTest --tests com.example.ui.WorkspaceScreenTest'
#   scripts/delegate-build.sh -b pr/my-feature -c 'Verify workspace screen' ':app:recordRoborazziDebug --tests com.example.ui.ScreenshotDriverTest'
#
set -euo pipefail

REPO="asshat1981ar/OllamaDev"
WORKFLOW="Remote Build Delegation"
OUTDIR="./ci-artifacts"
BRANCH=""
COMMIT_MSG=""
TASK_ARGS=()

usage() { sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        -b|--branch) BRANCH="$2"; shift 2 ;;
        -c|--commit) COMMIT_MSG="$2"; shift 2 ;;
        --repo) REPO="$2"; shift 2 ;;
        -o|--outdir) OUTDIR="$2"; shift 2 ;;
        -h|--help) usage 0 ;;
        -*) echo "Unknown option: $1" >&2; usage 1 ;;
        *) TASK_ARGS+=("$1"); shift ;;
    esac
done

BRANCH="${BRANCH:-$(git branch --show-current)}"
if [[ -z "$BRANCH" ]]; then
    echo "error: could not determine current branch; pass one with -b" >&2; exit 1
fi
TASK="${TASK_ARGS[*]:-:app:testDebugUnitTest}"

# --- 0. Preconditions ------------------------------------------------------
command -v gh >/dev/null 2>&1 || { echo "error: gh CLI not found" >&2; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "error: not authenticated with gh" >&2; exit 1; }
# Make `git push` able to authenticate via the gh token (PAT-over-HTTPS per standards).
gh auth setup-git >/dev/null 2>&1 || true

# --- 1. Optional commit + push ---------------------------------------------
if [[ -n "$COMMIT_MSG" ]]; then
    if git diff --quiet && git diff --cached --quiet; then
        echo "Working tree clean; nothing to commit."
    else
        git add -A
        git commit -m "$COMMIT_MSG" >/dev/null
    fi
fi

if ! git ls-remote --exit-code origin "$BRANCH" >/dev/null 2>&1; then
    echo "Pushing branch '$BRANCH' to origin (not yet present remotely)..."
    git push -u origin "$BRANCH"
elif [[ -n "$COMMIT_MSG" ]]; then
    git push origin "$BRANCH"
fi

# --- 2. Trigger the dispatch workflow --------------------------------------
echo "Delegating to GitHub: repo=$REPO workflow=$WORKFLOW branch=$BRANCH"
echo "  gradle task: $TASK"
if gh workflow run "$WORKFLOW" --repo "$REPO" --ref "$BRANCH" -f task="$TASK"; then
    : # dispatched normally
else
    # GitHub only lets workflow_dispatch target workflows that exist on the default
    # branch (the actions API resolves workflows against main). A brand-new workflow
    # file is therefore NOT dispatchable until it lands on main. For the default task
    # fall back to the standard "Android CI" gate (fixed :app:testDebugUnitTest +
    # :app:assembleDebug); for custom tasks, fail with actionable guidance.
    if [[ "$TASK" == ":app:testDebugUnitTest" ]]; then
        echo "warning: '$WORKFLOW' is not dispatchable yet (workflows are only visible from the default branch)."
        echo "warning: falling back to the standard 'Android CI' gate; the task input is fixed."
        WORKFLOW="Android CI"
        gh workflow run "$WORKFLOW" --repo "$REPO" --ref "$BRANCH" || {
            echo "error: fallback dispatch to '$WORKFLOW' failed (is workflow_dispatch present on branch '$BRANCH'?)" >&2
            exit 1
        }
    else
        echo "error: '$WORKFLOW' not found; workflows can only be dispatched once they exist on the default branch." >&2
        echo "error: cannot run custom task '$TASK' until '$WORKFLOW' is merged to main." >&2
        echo "error: for the standard gate now, run: gh workflow run \"Android CI\" --repo \"$REPO\" --ref \"$BRANCH\"" >&2
        exit 1
    fi
fi

# Poll for the newest run of this workflow/branch (the one we just created).
for _ in $(seq 1 30); do
    RUN_ID="$(gh run list --repo "$REPO" --workflow "$WORKFLOW" --branch "$BRANCH" \
        --limit 1 --json databaseId,status --jq '.[0] | select(.status == "in_progress" or .status == "queued") | .databaseId' || true)"
    [[ -n "$RUN_ID" ]] && break
    sleep 2
done
if [[ -z "$RUN_ID" ]]; then
    echo "error: no queued/in-progress run found for workflow '$WORKFLOW' on branch '$BRANCH'" >&2
    exit 1
fi
echo "Run created: $RUN_ID"

# --- 3. Watch to completion --------------------------------------------------
# --exit-status returns non-zero when the run's conclusion is not "success".
set +e
gh run watch "$RUN_ID" --repo "$REPO" --exit-status --interval 30
watch_rc=$?
set -e

CONCLUSION="$(gh run view "$RUN_ID" --repo "$REPO" --json conclusion --jq '.conclusion')"
echo "Delegated run $RUN_ID conclusion: ${CONCLUSION:-unknown} (watch exit=$watch_rc)"

# --- 4. Download artifacts (best-effort, even on failure) ---------------------
rm -rf "$OUTDIR"
mkdir -p "$OUTDIR"
gh run download "$RUN_ID" --repo "$REPO" -D "$OUTDIR" 2>/dev/null \
    && echo "Artifacts downloaded to $OUTDIR" \
    || echo "No artifacts to download for run $RUN_ID (or download failed)."
echo "Run page: https://github.com/$REPO/actions/runs/$RUN_ID"

# --- 5. Fail if the delegated build did not succeed ---------------------------
if [[ "$(gh run view "$RUN_ID" --repo "$REPO" --json conclusion --jq '.conclusion')" != "success" ]]; then
    echo "Delegated build FAILED." >&2
    exit 1
fi
echo "Delegated build succeeded."
