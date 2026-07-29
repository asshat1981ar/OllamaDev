#!/usr/bin/env bash
set -euo pipefail

REPO="asshat1981ar/OllamaDev"
WORKFLOW="Android CI"
ARTIFACT="app-debug-apk"
OUTDIR="./ci-artifacts"

# Resolve open PRs created in this session by their branch prefix.
echo "Open PRs from this session:"
gh pr list --repo "$REPO" --head "pr/" --json number,headRefName,title \
  --jq '.[] | "#\(.number) \(.headRefName): \(.title)"'

# Poll CI checks for every backlog branch until they complete.
for branch in \
  pr/ci-debug-apk \
  pr/tier1-trust-visibility \
  pr/tier2-oversight \
  pr/tier3a-sandbox-server \
  pr/tier3bc-analytics-background \
  pr/tier5-mcp-write-test; do

  number=$(gh pr list --repo "$REPO" --head "$branch" --json number --jq '.[0].number // empty')
  if [ -z "$number" ]; then
    echo "No open PR for branch $branch; skipping."
    continue
  fi

  echo ""
  echo "Watching PR #$number (branch $branch)..."
  gh pr checks "$number" --repo "$REPO" --watch --interval 30
  status=$(gh pr view "$number" --repo "$REPO" --json state --jq '.state')
  echo "PR #$number state: $status"
done

# Download the APK artifact from the most recent successful Android CI run.
echo ""
echo "Fetching latest $WORKFLOW run..."
run_id=$(gh run list --repo "$REPO" --workflow "$WORKFLOW" --status completed --limit 1 \
  --json databaseId,status,conclusion \
  --jq '.[0] | select(.conclusion == "success") | .databaseId')

if [ -z "$run_id" ]; then
  echo "No successful $WORKFLOW run found. Exiting without downloading APK."
  exit 1
fi

echo "Latest successful run: $run_id"
rm -rf "$OUTDIR"
mkdir -p "$OUTDIR"
gh run download "$run_id" --repo "$REPO" -n "$ARTIFACT" -D "$OUTDIR"
echo "APK downloaded to $OUTDIR/$ARTIFACT/app-debug.apk"
