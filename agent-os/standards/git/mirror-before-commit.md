# Mirror WorkspaceFiles Before Any Git Op

Workspace files live as Room rows or SAF `content://` URIs — there's no resolvable real
filesystem path for them. `GitService` operates on a real JGit repo rooted at a fixed,
app-private `workDir`, so callers must call `mirrorFiles()` to sync the current
`WorkspaceFile` set into `workDir` before any commit/status operation.

```kotlin
val files = db.workspaceFileDao().getAllFiles().first()
gitService.mirrorFiles(files)       // writes files into workDir, prunes stray ones
gitService.commitAll(author, email, message)
```

- **Common mistake:** calling `commitAll()` or `isClean()` without `mirrorFiles()` first —
  git then sees stale or missing content because `workDir` wasn't updated.
- `mirrorFiles()` prunes any file in `workDir` that's no longer in the `WorkspaceFile` set
  (except `.git`), so it's safe to call repeatedly — it's a full sync, not an append.
- **How to apply:** Any new caller that needs to inspect or commit the current workspace
  state must call `mirrorFiles()` immediately before, in the same operation.
