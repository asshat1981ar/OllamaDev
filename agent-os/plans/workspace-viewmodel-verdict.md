# WorkspaceViewModel Verdict

**Date:** 2026-07-29
**Investigator:** Hermes Agent
**Status:** Investigation complete; recommendation: **delete**.

## Findings

1. `WorkspaceViewModel.kt` exists at `app/src/main/java/com/example/viewmodel/WorkspaceViewModel.kt` but is **not referenced anywhere** in `main` or `test` sources (only self-reference).
2. It is a near-complete duplicate of workspace/file/git state already present in `SwarmViewModel.kt`:
   - `rootFolderUri`, `rootFolderName`, `isImportingFolder`, `folderImportStatus`
   - `workspaceFiles`, `selectedFile`
   - `gitCommits`, `gitRepoName`, `gitCodename`, `gitRemoteUrl`, `isGitSynced`, `isGitSyncing`, `gitError`
   - `selectFile`, `createFile`, `saveFileContentSuspended`, `saveFile`, `deleteFile`, `uploadFile`, `importFolder`, `syncWorkspace`, `disconnectFolder`, `commitChanges`, `pushToGit`, `revertToCheckpoint`, `updateGitSettings`, `acceptSelfHealingPatch`, `declineSelfHealingPatch`
3. The live UI uses `SwarmViewModel`, not `WorkspaceViewModel`. `IdeWorkspaceScreen.WorkspacePanel`, `SessionScreen`, `MainActivity`, `SprintPlannerScreen`, `DashboardScreen`, `AgentScreen`, `NodeScreen`, etc. all take `viewModel: SwarmViewModel`.
4. There is no `ViewModelProvider.Factory` or `by viewModels()` invocation that returns `WorkspaceViewModel`.
5. It appears to be an extraction attempt from the monolithic `SwarmViewModel.kt` that was abandoned before wiring it in.

## Recommendation

**Delete `WorkspaceViewModel.kt`** and do not wire it in, because:
- Wiring it would require changing every screen constructor and factory.
- The two ViewModels would fight over the same `WorkspaceFile`/`GitCommit` DAO state.
- `SwarmViewModel` is already the single source of truth used by all screens.
- Removing it reduces confusion and prevents future edits from accidentally targeting the wrong file.

## Safe-deletion steps

1. Delete `app/src/main/java/com/example/viewmodel/WorkspaceViewModel.kt`.
2. Run a global search for `WorkspaceViewModel` to confirm no references remain.
3. Run `./gradlew :app:compileDebugKotlin` to confirm the build still compiles.
4. Commit.

## Risks

- Low. The file is unreachable today. The only risk is if a future refactor intended to re-introduce it; if so, the git history preserves it.
