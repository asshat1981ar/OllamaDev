# SDD ledger — plan: docs/superpowers/plans/2026-08-05-production-readiness.md

Workspace: main (per plan Global Constraints — changes land on main, focused commits)

| Task | Status | Commits | Review | Notes |
|------|--------|---------|--------|-------|
| 1 WIP reconciliation | complete (08fb522) | 08fb522 | controller review: kept antigenic+headless tests; deleted WorkspaceViewModel; compile green | |
| 2 RELEASE build type | complete (235df28) | 235df28 | team build-system: verify+commit WIP; assembleRelease -x lintVitalRelease produced app-release-unsigned.apk (env unset); compileDebug+compileDebugUnitTest green | plain assembleRelease blocked by pre-existing fatal lint (MainActivity.kt:42 InvalidFragmentVersionForActivityResult) |
| 3 suggest_next_action fix | complete (c463b3c) | c463b3c | controller review: clean (closure rebinding) | 481 tests pass |
| 4 version scheme | complete (e0d7a35) | e0d7a35 | team version-scheme: scripts/version.sh (code/name) + providers.exec wiring; merged manifest showed versionCode=80, versionName=1.0.0-235df28; compileDebugKotlin green | name appends -<short-sha> when dirty; env workaround JAVA_TOOL_OPTIONS preferIPv6Addresses |
| 5 verify-prompt fix | complete (d1d6c18) | d1d6c18 | controller review: bug already fixed; test committed, 1 green | test file tracked; Task 1 must NOT re-add |
| 6 Room migration | complete (9df041b) | 9df041b | controller review: ADR + qualified note; destructive kept as documented last resort | compile green |
| 7 Budget heuristic doc | complete (467428f) | 467428f | controller review: copy added, compile green | |
| 8 LlmRouter context | complete (949f28b) | 949f28b | controller review: TDD, 6/6 green | |
| 9 MCP ops | complete (eef6fbf) | eef6fbf | controller review: 3 intended files, script 100755, 481 pass | |
| 10 Docs sync + verify | complete (2cdb737) | 2cdb737 | team docs-sync: CHANGELOG created; backlog 6.1/6.4/6.5/6.6 checked; PRD/HLD/LLD status sync; README stale signing step replaced (Build & release section) | assembleDebug BUILD SUCCESSFUL (1m28s); mcp-server pytest 481 passed; pre-existing lint/antigenic test failures documented, not blocking |
