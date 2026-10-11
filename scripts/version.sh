#!/usr/bin/env bash
# Derive build versioning from git.
#   code: prints `git rev-list --count HEAD` (integer build number)
#   name: prints latest semver tag, or "1.0.0" if none; appends "-<short-sha>"
#         when the worktree is dirty.
# Prints exactly one value per invocation.
set -euo pipefail

subcommand="${1:-}"

case "$subcommand" in
  code)
    git rev-list --count HEAD
    ;;
  name)
    # Latest semver tag (v-prefix optional), else 1.0.0.
    tag="$(git describe --tags --match 'v[0-9]*' --match '[0-9]*' --abbrev=0 2>/dev/null || true)"
    if [ -z "$tag" ]; then
      name="1.0.0"
    else
      name="${tag#v}"
    fi
    # Append short sha when the worktree is dirty.
    if ! git diff --quiet HEAD; then
      short_sha="$(git rev-parse --short HEAD)"
      name="${name}-${short_sha}"
    fi
    printf '%s\n' "$name"
    ;;
  *)
    echo "usage: $0 {code|name}" >&2
    exit 1
    ;;
esac