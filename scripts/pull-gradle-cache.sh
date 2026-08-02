#!/usr/bin/env bash
#
# pull-gradle-cache.sh — warm up the local Gradle home from the CI's GitHub Actions cache.
#
# The local aarch64 container cannot reuse the CI's *compiled* artifacts (x86_64-specific), but
# it CAN reuse the arch-independent parts: the Gradle distribution (wrapper/dists) and the
# Maven dependency jars (caches/modules-2). This script downloads the newest main-branch
# gradle-home cache entry and copies only those parts into ~/.gradle.
#
# Requirements: gh CLI authenticated, plus the actions-cache extension
#   gh extension install actions/gh-actions-cache
#
# Usage: scripts/pull-gradle-cache.sh [--repo owner/repo] [--list]
set -euo pipefail

REPO="asshat1981ar/OllamaDev"
KEY_PREFIX="gradle-home-v1|Linux|test"

for arg in "$@"; do
    case "$arg" in
        --repo) REPO="$2"; shift 2 ;;
        --list) MODE=LIST ;;
    esac
done

command -v gh >/dev/null 2>&1 || { echo "error: gh CLI not found" >&2; exit 1; }
if ! gh extension list 2>/dev/null | grep -q actions/gh-actions-cache; then
    echo "info: installing gh-actions-cache extension..."
    gh extension install actions/gh-actions-cache >/dev/null 2>&1 || { echo "error: could not install gh-actions-cache extension" >&2; exit 1; }
fi

echo "Listing gradle-home caches for $REPO..."
gh actions-cache list --repo "$REPO" --key "$KEY_PREFIX" 2>/dev/null | head -20

if [[ "${MODE:-}" == "LIST" ]]; then
    exit 0
fi

# Pick the newest cache line for the main branch:  KEY  |  BRANCH  |  SIZE  |  CREATED
line=$(gh actions-cache list --repo "$REPO" --key "$KEY_PREFIX" 2>/dev/null | grep -E 'main' | head -1)
if [[ -z "$line" ]]; then
    line=$(gh actions-cache list --repo "$REPO" --key "$KEY_PREFIX" 2>/dev/null | head -1)
fi
[[ -n "$line" ]] || { echo "error: no gradle-home cache found for $REPO" >&2; exit 1; }

cache_key=$(echo "$line" | awk -F'  +' '{print $1}' | xargs)
cache_size=$(echo "$line" | awk -F'  +' '{print $3}' | xargs)
echo "Restoring cache: $cache_key ($cache_size)"

tmpdir=$(mktemp -d)
trap 'rm -rf "$tmpdir"' EXIT

gh actions-cache get "$cache_key" --repo "$REPO" -S "$cache_size" -D "$tmpdir" >/dev/null 2>&1 \
    || { echo "error: cache download failed (key/size may have changed; re-run)" >&2; exit 1; }

mkdir -p "$tmpdir/extract"
tar -xzf "$tmpdir/"*.tar -C "$tmpdir/extract" 2>/dev/null || true

# Locate the Gradle home root inside the tarball (it may be nested under home/runner/.gradle).
root=$(find "$tmpdir/extract" -type d -name 'modules-2' -path '*caches*' 2>/dev/null | head -1)
if [[ -z "$root" ]]; then
    echo "warning: could not locate caches/modules-2 in the downloaded cache; nothing restored."
    exit 0
fi
gradle_home="${root%/caches/modules-2}"
echo "Gradle home root in cache: $gradle_home"

restored=0
for part in "caches/modules-2" "caches/modules-2-files" "wrapper/dists"; do
    if [ -d "$gradle_home/$part" ]; then
        mkdir -p "$HOME/.gradle/$(dirname "$part")"
        cp -rn "$gradle_home/$part" "$HOME/.gradle/$part" 2>/dev/null && { echo "restored ~/.gradle/$part"; restored=1; }
    fi
done

if [ "$restored" = "1" ]; then
    echo "Done. Local Gradle warm-up complete (arch-independent parts only)."
else
    echo "warning: nothing to restore (cache format unexpected)."
fi
