# Extract Workspace/File/Git Domain into WorkspaceViewModel — Implementation Spec

> **For Hermes:** Use subagent-driven-development to implement this plan task-by-task. Read-only analysis is already complete; this document is the source of truth for scope and verification.

**Goal:** Extract the workspace/file/git domain from the 1,560-line `SwarmViewModel` into a new focused `WorkspaceViewModel`, leaving `SwarmViewModel` responsible for swarm execution, session, nodes, agents, MCP/skills, and voice state.

**Architecture:** The workspace domain is already cohesive: it owns `WorkspaceFile`/`GitCommit` database state, `GitService`, SAF folder import/sync, and the sandbox/self-healing UI state. Moving it to a dedicated `AndroidViewModel` reduces the god-class and gives the workspace panel a clear public API. `SwarmViewModel` will keep a read-only reference to selected workspace state only where swarm execution needs it (sandbox/self-healing), but file/git CRUD lives in `WorkspaceViewModel`.

**Tech Stack:** Jetpack Compose, Kotlin 2.2.10, Material 3, `androidx.lifecycle.ViewModel`/`AndroidViewModel`, `ViewModelProvider.Factory`, `StateFlow`/`MutableStateFlow`, Room-style DAO interfaces, Robolectric + Compose UI tests.

---

## Scope Boundary

**Move to `WorkspaceViewModel`:**
- State flows: `workspaceFiles`, `selectedFile`, `gitCommits`, `gitRepoName`, `gitCodename`, `gitRemoteUrl`, `isGitSynced`, `isGitSyncing`, `gitError`, `rootFolderUri`, `rootFolderName`, `isImportingFolder`, `folderImportStatus`.
- Functions: `selectFile`, `createFile`, `saveFile`, `saveFileContentSuspended`, `resolveConflict`, `deleteFile`, `uploadFile`, `importFolder`, `syncWorkspace`, `disconnectFolder`, `updateGitSettings`, `commitChanges`, `pushToGit`, `revertToCheckpoint`.
- Private helpers: `findOrCreateFileInTree`, `computeGitSyncState`, `walkAndImport`, `skippedDirNames`, `importableExtensions`, `MAX_IMPORT_FILE_BYTES`.
- Sandbox UI state: `isSandboxRunning`, `sandboxConsoleOutput`, `sandboxExitCode`, `sandboxLanguage`, `sandboxMemoryUsed`, `sandboxTimeMs`, `isSelfHealingEnabled`, `pendingSelfHealingPatch`, plus `runSandbox`, `setSelfHealingEnabled`, `acceptSelfHealingPatch`, `declineSelfHealingPatch`.
- Application `SharedPreferences` access for `root_folder_uri`, `root_folder_name`, `git_remote_url`, `git_last_pushed_hash`.
- `GitService` instantiation and the `gitWorkDir` lazy property.

**Stay in `SwarmViewModel`:**
- Node/agent/swarm/MCP/skill/session/voice/approval state and operations.
- `chatMessages` flow and `allTasks`/`selectedTaskId` (session orchestration).
- `PendingApprovalStore` re-exports (approval gates are a swarm/agentic concern).
- `AgentStateStore` re-exports.
- `SwarmEngine` retains ownership of `GitService` for agentic auto-checkpoint / agentic git commands; it is constructed with the **same** `GitService` instance used by `WorkspaceViewModel` so the on-disk repo is shared.

---

## 1. Exact State Flows and Functions to Move

### State flows to relocate (copy signatures exactly)

| Current member in `SwarmViewModel` | Current type | Destination visibility |
|---|---|---|
| `workspaceFiles` | `StateFlow<List<WorkspaceFile>>` | public `val` |
| `selectedFile` | `StateFlow<WorkspaceFile?>` | public `val` |
| `gitCommits` | `StateFlow<List<GitCommit>>` | public `val` |
| `gitRepoName` | `StateFlow<String>` | public `val` |
| `gitCodename` | `StateFlow<String>` | public `val` |
| `gitRemoteUrl` | `StateFlow<String>` | public `val` |
| `isGitSynced` | `StateFlow<Boolean>` | public `val` |
| `isGitSyncing` | `StateFlow<Boolean>` | public `val` |
| `gitError` | `StateFlow<String?>` | public `val` |
| `rootFolderUri` | `StateFlow<String?>` | public `val` |
| `rootFolderName` | `StateFlow<String?>` | public `val` |
| `isImportingFolder` | `StateFlow<Boolean>` | public `val` |
| `folderImportStatus` | `StateFlow<String>` | public `val` |
| `isSandboxRunning` | `StateFlow<Boolean>` | public `val` |
| `sandboxConsoleOutput` | `StateFlow<String>` | public `val` |
| `sandboxExitCode` | `StateFlow<Int?>` | public `val` |
| `sandboxLanguage` | `StateFlow<String>` | public `val` |
| `sandboxMemoryUsed` | `StateFlow<String>` | public `val` |
| `sandboxTimeMs` | `StateFlow<Long>` | public `val` |
| `isSelfHealingEnabled` | `StateFlow<Boolean>` | public `val` |
| `pendingSelfHealingPatch` | `StateFlow<Pair<WorkspaceFile, String>?>` | public `val` |

### Functions to relocate (signatures must remain identical)

```kotlin
// Selection / CRUD
fun selectFile(file: WorkspaceFile?)
fun createFile(filePath: String, content: String)
suspend fun saveFileContentSuspended(id: Int, content: String): WorkspaceFile?
fun saveFile(id: Int, content: String)
fun resolveConflict(id: Int, resolvedContent: String, keepLocal: Boolean)
fun deleteFile(file: WorkspaceFile)
fun uploadFile(filePath: String, content: String)

// Folder import / sync
fun importFolder(treeUri: Uri)
fun syncWorkspace(isAutomatic: Boolean = false)
fun disconnectFolder()

// Git settings / commit / push / revert
fun updateGitSettings(repoName: String, codename: String, remoteUrl: String, token: String)
fun commitChanges(message: String)
fun pushToGit()
fun revertToCheckpoint(commit: GitCommit)

// Sandbox / self-healing
fun runSandbox(file: WorkspaceFile)
fun setSelfHealingEnabled(enabled: Boolean)
fun acceptSelfHealingPatch()
fun declineSelfHealingPatch()
```

### Private helpers to relocate

```kotlin
private const val MAX_IMPORT_FILE_BYTES = 300 * 1024
private fun findOrCreateFileInTree(root: DocumentFile, relativePath: String): DocumentFile?
private fun computeGitSyncState()
private val skippedDirNames: Set<String>
private val importableExtensions: Set<String>
private suspend fun walkAndImport(
    dir: DocumentFile,
    maxFiles: Int,
    onFile: suspend (relativePath: String, doc: DocumentFile) -> Unit
): Boolean
private val gitWorkDir: File        // lazy, filesDir/git_workspace
private val gitService: GitService  // lazy, GitService(gitWorkDir)
```

---

## 2. New `WorkspaceViewModel` Public API

### New file

`app/src/main/java/com/example/viewmodel/WorkspaceViewModel.kt`

### Constructor (mirrors `SwarmViewModel` testability pattern)

```kotlin
class WorkspaceViewModel(
    application: Application,
    database: AppDatabaseInterface = AppDatabase.getDatabase(application),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val securePrefs: SecurePrefsInterface = RealSecurePrefs(application)
) : AndroidViewModel(application) {
    private val db = database
    private val prefs = application.getSharedPreferences("ollama_swarm_prefs", Context.MODE_PRIVATE)
    // ...
}
```

Keep constructor dependencies minimal and aligned with existing fakes (`FakeAppDatabase`, `FakeSecurePrefs`). `GitService` is constructed internally from `application.filesDir`; exposing it in the constructor would require a `GitService` fake that does not yet exist and is not necessary for the first slice.

### Public API surface

```kotlin
// File state
val workspaceFiles: StateFlow<List<WorkspaceFile>>
val selectedFile: StateFlow<WorkspaceFile?>

// Folder state
val rootFolderUri: StateFlow<String?>
val rootFolderName: StateFlow<String?>
val isImportingFolder: StateFlow<Boolean>
val folderImportStatus: StateFlow<String>

// Git state
val gitCommits: StateFlow<List<GitCommit>>
val gitRepoName: StateFlow<String>
val gitCodename: StateFlow<String>
val gitRemoteUrl: StateFlow<String>
val isGitSynced: StateFlow<Boolean>
val isGitSyncing: StateFlow<Boolean>
val gitError: StateFlow<String?>

// Sandbox / self-healing state
val isSandboxRunning: StateFlow<Boolean>
val sandboxConsoleOutput: StateFlow<String>
val sandboxExitCode: StateFlow<Int?>
val sandboxLanguage: StateFlow<String>
val sandboxMemoryUsed: StateFlow<String>
val sandboxTimeMs: StateFlow<Long>
val isSelfHealingEnabled: StateFlow<Boolean>
val pendingSelfHealingPatch: StateFlow<Pair<WorkspaceFile, String>?>

// Functions (same signatures as above)
fun selectFile(file: WorkspaceFile?)
fun createFile(filePath: String, content: String)
suspend fun saveFileContentSuspended(id: Int, content: String): WorkspaceFile?
fun saveFile(id: Int, content: String)
fun resolveConflict(id: Int, resolvedContent: String, keepLocal: Boolean)
fun deleteFile(file: WorkspaceFile)
fun uploadFile(filePath: String, content: String)
fun importFolder(treeUri: Uri)
fun syncWorkspace(isAutomatic: Boolean = false)
fun disconnectFolder()
fun updateGitSettings(repoName: String, codename: String, remoteUrl: String, token: String)
fun commitChanges(message: String)
fun pushToGit()
fun revertToCheckpoint(commit: GitCommit)
fun runSandbox(file: WorkspaceFile)
fun setSelfHealingEnabled(enabled: Boolean)
fun acceptSelfHealingPatch()
fun declineSelfHealingPatch()
```

### Factory

```kotlin
class WorkspaceViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WorkspaceViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WorkspaceViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
```

---

## 3. Files/Screens to Modify and How

### A. Create new file

**`app/src/main/java/com/example/viewmodel/WorkspaceViewModel.kt`**
- Copy all workspace/file/git/sandbox state, functions, and helpers from `SwarmViewModel` lines 57–61, 755–787, 795–1392, 1394–1540, 1214–1250.
- Remove the `ollamaService`, `mcpClient`, `registryClient`, `swarmEngine`, and all non-workspace members.
- Keep the `init` block that restores `rootFolderUri`/`rootFolderName` from prefs and calls `syncWorkspace(isAutomatic = true)` — this preserves the existing `MainActivity.onResume` + startup behavior.
- Keep the `AndroidViewModel(application)` base class and `prefs` initialization.

### B. Modify `SwarmViewModel`

**`app/src/main/java/com/example/viewmodel/SwarmViewModel.kt`**
- Remove all moved state flows and their private backing flows.
- Remove all moved functions and private helpers.
- Remove `MAX_IMPORT_FILE_BYTES`, `skippedDirNames`, `importableExtensions`, `walkAndImport`, `findOrCreateFileInTree`, `computeGitSyncState`, `gitWorkDir`, `gitService`.
- Change constructor to accept an externally supplied `GitService` (so `SwarmEngine` and `WorkspaceViewModel` share the same repo). Current constructor:

  ```kotlin
  class SwarmViewModel(
      application: Application,
      private val ollamaService: OllamaService = OllamaServiceDefault,
      private val mcpClient: McpClientInterface = McpClient(),
      private val registryClient: McpRegistryClientInterface = McpRegistryClient(),
      private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
      database: AppDatabaseInterface = AppDatabase.getDatabase(application),
      private val securePrefs: SecurePrefsInterface = RealSecurePrefs(application)
  )
  ```

  New constructor:

  ```kotlin
  class SwarmViewModel(
      application: Application,
      private val ollamaService: OllamaService = OllamaServiceDefault,
      private val mcpClient: McpClientInterface = McpClient(),
      private val registryClient: McpRegistryClientInterface = McpRegistryClient(),
      private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
      database: AppDatabaseInterface = AppDatabase.getDatabase(application),
      private val securePrefs: SecurePrefsInterface = RealSecurePrefs(application),
      gitService: GitService = GitService(File(application.filesDir, "git_workspace").apply { mkdirs() })
  )
  ```

- Update the `swarmEngine` lazy init to pass the injected `gitService`:

  ```kotlin
  private val swarmEngine by lazy { SwarmEngine(db, gitService, mcpClient, getApplication(), securePrefs, ollamaService, dispatcher) }
  ```

- **Keep** `pendingApproval`, `pendingFileChange`, `agentStates`, `totalTokensUsed`, `totalCostSavingsUsd`, `totalSandboxRuns` re-exports — they are swarm/agentic concerns.
- **Keep** `chatMessages` flow (session transcript).
- `runSandbox` currently mutates workspace state (`saveFileContentSuspended`) and reads `selectedFile`/`isSelfHealingEnabled`. After extraction, `SwarmViewModel` no longer needs `runSandbox`; it moves entirely to `WorkspaceViewModel`. `WorkspacePanel` will invoke `workspaceViewModel.runSandbox(...)` directly.

### C. Modify `MainActivity`

**`app/src/main/java/com/example/MainActivity.kt`**
- Instantiate both ViewModels:

  ```kotlin
  private val viewModel: SwarmViewModel by viewModels {
      SwarmViewModelFactory(application)
  }
  private val workspaceViewModel: WorkspaceViewModel by viewModels {
      WorkspaceViewModelFactory(application)
  }
  ```

- `onResume` currently calls `viewModel.syncWorkspace(isAutomatic = true)`. Change to:

  ```kotlin
  override fun onResume() {
      super.onResume()
      workspaceViewModel.syncWorkspace(isAutomatic = true)
  }
  ```

- Update `when (activeTab)` body:

  ```kotlin
  when (activeTab) {
      "session" -> SessionScreen(
          viewModel = viewModel,
          workspaceViewModel = workspaceViewModel
      )
      "manage" -> ManageScreen(viewModel = viewModel)
      "settings" -> SystemConfigScreen(viewModel = viewModel)
  }
  ```

- `pendingSelfHealingPatch` dialog currently lives inside `WorkspacePanel`; if hoisted to `MainActivity` (it is currently inside `IdeWorkspaceScreen.kt`), update it to use `workspaceViewModel`. The spec recommends leaving the dialog inside `WorkspacePanel` and only changing its ViewModel reference (see below).

### D. Modify `IdeWorkspaceScreen.kt` (`WorkspacePanel` and editor)

**`app/src/main/java/com/example/ui/IdeWorkspaceScreen.kt`**
- Change `WorkspacePanel` signature:

  ```kotlin
  @Composable
  fun WorkspacePanel(
      workspaceViewModel: WorkspaceViewModel,
      modifier: Modifier = Modifier
  )
  ```

- Replace every `viewModel.` inside `WorkspacePanel` with `workspaceViewModel.`:
  - `workspaceFiles`, `selectedFile`, `gitCommits`, `gitRepoName`, `gitCodename`, `gitRemoteUrl`, `isGitSynced`, `isGitSyncing`, `gitError`, `rootFolderUri`, `rootFolderName`, `isImportingFolder`, `folderImportStatus`, `pendingSelfHealingPatch`, `isSandboxRunning`, `sandboxConsoleOutput`, `sandboxExitCode`, `sandboxLanguage`, `sandboxMemoryUsed`, `sandboxTimeMs`, `isSelfHealingEnabled`.
  - `selectFile`, `createFile`, `saveFile`, `resolveConflict`, `deleteFile`, `uploadFile`, `importFolder`, `syncWorkspace`, `disconnectFolder`, `updateGitSettings`, `commitChanges`, `pushToGit`, `revertToCheckpoint`, `runSandbox`, `setSelfHealingEnabled`, `acceptSelfHealingPatch`, `declineSelfHealingPatch`.
- `CodeEditorSection` currently takes `viewModel: SwarmViewModel` only to read sandbox state and call `resolveConflict`/`runSandbox`. Change signature to:

  ```kotlin
  @Composable
  fun CodeEditorSection(
      workspaceViewModel: WorkspaceViewModel,
      selectedFile: WorkspaceFile?,
      isSynced: Boolean,
      onSave: (Int, String) -> Unit
  )
  ```

- `FileBrowserSection` and `GithubIntegrationSection` are already stateless lambdas — no signature change beyond the call-site `viewModel` rename.

### E. Modify `SessionScreen`

**`app/src/main/java/com/example/ui/SessionScreen.kt`**
- Change `SessionScreen` signature:

  ```kotlin
  @Composable
  fun SessionScreen(
      viewModel: SwarmViewModel,
      workspaceViewModel: WorkspaceViewModel,
      modifier: Modifier = Modifier
  )
  ```

- Replace the two `WorkspacePanel(viewModel = viewModel)` calls with `WorkspacePanel(workspaceViewModel = workspaceViewModel)`.
- Keep all other `viewModel.` references (session, tasks, voice, etc.) untouched.

### F. Modify `SystemConfigScreen`

**`app/src/main/java/com/example/ui/SystemConfigScreen.kt`**
- No workspace/file/git logic is present. Keep signature `fun SystemConfigScreen(viewModel: SwarmViewModel, ...)` and leave as-is.

### G. Modify tests

**`app/src/test/java/com/example/ui/UiTestBase.kt`**
- Add a `WorkspaceViewModel` alongside the existing `SwarmViewModel`:

  ```kotlin
  protected lateinit var viewModel: SwarmViewModel
  protected lateinit var workspaceViewModel: WorkspaceViewModel
  ```

- In `setUp`, construct both using the same `FakeAppDatabase` and `FakeSecurePrefs`:

  ```kotlin
  viewModel = SwarmViewModel(
      application = context,
      ollamaService = FakeOllamaService(),
      mcpClient = FakeMcpClient(),
      registryClient = FakeMcpRegistryClient(),
      dispatcher = testDispatcher,
      database = FakeAppDatabase(),
      securePrefs = FakeSecurePrefs(),
      gitService = GitService(File(context.cacheDir, "git-test-${System.nanoTime()}"))
  )
  workspaceViewModel = WorkspaceViewModel(
      application = context,
      database = viewModel.db,   // optional: share DB to keep seeded files visible
      dispatcher = testDispatcher,
      securePrefs = FakeSecurePrefs()
  )
  ```

  **Decision point:** sharing the `AppDatabaseInterface` instance means seeded `WorkspaceFile`s are visible to both ViewModels. This keeps `WorkspaceFileFlowTest` and `GitIntegrationTest` working with minimal changes. If isolation is preferred, create a second `FakeAppDatabase()` and update assertions accordingly. The recommendation is **share**.

- Add helper `setContent { WorkspacePanel(workspaceViewModel) }` usage pattern note in `UiTestBase` KDoc if desired, but not required.

**`app/src/test/java/com/example/ui/WorkspaceFileFlowTest.kt`**
- Change `setContent { WorkspacePanel(viewModel) }` to `setContent { WorkspacePanel(workspaceViewModel) }`.
- Change assertions from `viewModel.workspaceFiles.value` to `workspaceViewModel.workspaceFiles.value`.

**`app/src/test/java/com/example/ui/GitIntegrationTest.kt`**
- Change `setContent { WorkspacePanel(viewModel) }` to `setContent { WorkspacePanel(workspaceViewModel) }`.

**`app/src/test/java/com/example/ui/SessionVoiceInputTest.kt`**, **`SwarmDispatchTest.kt`**, **`AgentManagementTest.kt`**, **`MainNavigationTest.kt`**, **`NodeManagementTest.kt`**, **`McpServerAndRegistryTest.kt`**, **`DashboardFlowTest.kt`**, **`SettingsScreenTest.kt`**
- These tests only use non-workspace `SwarmViewModel` APIs. They do **not** need modification unless `UiTestBase` constructor changes force it. Keep `viewModel` references as-is.

### H. Modify other code that touches workspace state

Search results show no other screens reference workspace/file/git flows directly. `DashboardScreen`, `AgentScreen`, `NodeScreen`, `McpSkillsScreen` only use swarm/agent/node/MCP state. No changes required there.

---

## 4. Required Dependencies / Injection

### New `WorkspaceViewModel` dependencies

- `Application` (via `AndroidViewModel`)
- `AppDatabaseInterface` (constructor-injected; default `AppDatabase.getDatabase(application)`)
- `CoroutineDispatcher` (default `Dispatchers.IO`; override with `UnconfinedTestDispatcher` in tests)
- `SecurePrefsInterface` (default `RealSecurePrefs(application)`; override with `FakeSecurePrefs` in tests)
- `GitService` — **internally constructed** from `application.filesDir/git_workspace`. Do **not** expose it in the public constructor for this first slice; the only other consumer (`SwarmEngine`) already gets its own `GitService` injected in `SwarmViewModel`.

### `SwarmViewModel` dependency change

- Add `gitService: GitService` constructor parameter with a default so existing production call sites (`SwarmViewModelFactory`) keep compiling.
- In `SwarmViewModelFactory`, provide a `GitService` instance so `SwarmEngine` shares the same repo as `WorkspaceViewModel`:

  ```kotlin
  class SwarmViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
      private val gitService by lazy {
          GitService(File(application.filesDir, "git_workspace").apply { mkdirs() })
      }

      override fun <T : ViewModel> create(modelClass: Class<T>): T {
          if (modelClass.isAssignableFrom(SwarmViewModel::class.java)) {
              @Suppress("UNCHECKED_CAST")
              return SwarmViewModel(
                  application = application,
                  gitService = gitService
              ) as T
          }
          throw IllegalArgumentException("Unknown ViewModel class")
      }
  }
  ```

  **`WorkspaceViewModelFactory` must construct `GitService` with the same path** if it wants the repo to be shared. However, because `WorkspaceViewModel` constructs `GitService` internally, the factory has no direct control. To ensure sharing, either:
  1. **Pass `GitService` into `WorkspaceViewModel` constructor** (recommended if the implementation subagent wants deterministic sharing), OR
  2. Keep `WorkspaceViewModel` constructing internally and accept that production gets a second `GitService` instance at the same path. JGit is fine with multiple `Git` opens on the same directory as long as operations are serialized by the single coroutine scope. The existing `SwarmViewModel` already lazily constructs one `GitService`; having two is safe but slightly wasteful.

  **Spec decision:** add an optional `gitService: GitService` parameter to `WorkspaceViewModel` with a default internal instance. In production `MainActivity`, construct a single `GitService` and pass it to both ViewModel factories. This is cleaner and avoids hidden state duplication.

  ```kotlin
  class WorkspaceViewModel(
      application: Application,
      database: AppDatabaseInterface = AppDatabase.getDatabase(application),
      private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
      private val securePrefs: SecurePrefsInterface = RealSecurePrefs(application),
      gitService: GitService? = null
  ) : AndroidViewModel(application) {
      private val gitService = gitService ?: GitService(File(application.filesDir, "git_workspace").apply { mkdirs() })
      // ...
  }
  ```

  Then `MainActivity` holds a single `GitService` and passes it to both factories. `WorkspaceViewModelFactory` must accept `gitService`:

  ```kotlin
  class WorkspaceViewModelFactory(
      private val application: Application,
      private val gitService: GitService
  ) : ViewModelProvider.Factory {
      override fun <T : ViewModel> create(modelClass: Class<T>): T {
          if (modelClass.isAssignableFrom(WorkspaceViewModel::class.java)) {
              @Suppress("UNCHECKED_CAST")
              return WorkspaceViewModel(application, gitService = gitService) as T
          }
          throw IllegalArgumentException("Unknown ViewModel class")
      }
  }
  ```

  `SwarmViewModelFactory` similarly accepts the shared `gitService`.

### No DI framework

The project does not use Hilt/Koin/Dagger. Continue using manual `ViewModelProvider.Factory` in `MainActivity`. Do **not** introduce a DI framework for this refactor.

---

## 5. Test Plan Using Existing Fakes

### Existing fakes to reuse

- `FakeAppDatabase` — provides in-memory DAO implementations including `WorkspaceFileDao` and `GitCommitDao`.
- `FakeSecurePrefs` — in-memory git/MCP tokens.
- `FakeOllamaService`, `FakeMcpClient`, `FakeMcpRegistryClient` — only needed for `SwarmViewModel` tests; workspace tests do not need them.
- `GitService` real class — safe to use against `context.cacheDir` in tests; existing `SwarmEngine*Test` already does this.

### New unit test file

**`app/src/test/java/com/example/viewmodel/WorkspaceViewModelTest.kt`** (new)

Use Robolectric + `ApplicationProvider` because `WorkspaceViewModel` extends `AndroidViewModel` and uses `application.filesDir`/`contentResolver`. Reuse the `@RunWith(RobolectricTestRunner::class)` / `@Config(sdk = [34])` / `@ConscryptMode(ConscryptMode.Mode.OFF)` annotations from `UiTestBase`.

Minimum test cases:

1. **`createFile_insertsIntoDb_andSelectsIt()`**
   - Arrange: `WorkspaceViewModel(application=app, database=FakeAppDatabase(), dispatcher=UnconfinedTestDispatcher(), securePrefs=FakeSecurePrefs(), gitService=GitService(testDir))`
   - Act: `workspaceViewModel.createFile("hello.kt", "fun main(){}")`
   - Assert: `workspaceViewModel.workspaceFiles.value.any { it.filePath == "hello.kt" }` and `workspaceViewModel.selectedFile.value?.filePath == "hello.kt"`

2. **`saveFile_updatesContent()`**
   - Arrange: seeded fake DB with a `WorkspaceFile`.
   - Act: run blocking / `runTest` calling `saveFileContentSuspended(id, "new")`.
   - Assert: `workspaceViewModel.selectedFile.value?.content == "new"` and DB row updated.

3. **`deleteFile_removesRowAndClearsSelection()`**
   - Act: `deleteFile(file)`.
   - Assert: file no longer in `workspaceFiles`, `selectedFile` null if it was selected.

4. **`updateGitSettings_persistsRemoteUrl()`**
   - Act: `updateGitSettings("repo", "codename", "https://example.com/repo.git", "token")`.
   - Assert: `gitRemoteUrl.value` updated; `FakeSecurePrefs.getGitToken()` returns "token".

5. **`commitChanges_createsGitCommitRow()`**
   - Arrange: create a workspace file so `GitService` has changes to commit.
   - Act: `commitChanges("Initial commit")`.
   - Assert: `gitCommits.value.any { it.message == "Initial commit" }`.

6. **`revertToCheckpoint_restoresFiles()`**
   - Arrange: create file, commit, modify file, commit again.
   - Act: `revertToCheckpoint(firstCommit)`.
   - Assert: `workspaceFiles.value` reflects first-commit content.

7. **`setSelfHealingEnabled_togglesState()`**
   - Act: `setSelfHealingEnabled(false)`.
   - Assert: `isSelfHealingEnabled.value == false`.

### Existing UI tests to update and keep green

- `WorkspaceFileFlowTest` — update to use `workspaceViewModel`.
- `GitIntegrationTest` — update to use `workspaceViewModel`.

### Verification commands

```bash
# Fast unit test target (Robolectric + Compose UI tests)
./gradlew :app:testDebugUnitTest --tests "com.example.viewmodel.WorkspaceViewModelTest" --tests "com.example.ui.WorkspaceFileFlowTest" --tests "com.example.ui.GitIntegrationTest"

# Broader regression of UI layer
./gradlew :app:testDebugUnitTest --tests "com.example.ui.*"
```

> Note: the previous subagent reported Gradle daemon/SIGTERM issues in this WSL environment. The implementation subagent should run tests in a healthy environment; if WSL still fails, use `--no-daemon` and confirm the command returns real output rather than silently fabricating results.

---

## 6. Step-by-Step Implementation Order

### Task 1: Add shared `GitService` plumbing
**Objective:** Make `SwarmViewModel` accept an injected `GitService` so it can later share the repo with `WorkspaceViewModel`.

**Files:**
- Modify: `app/src/main/java/com/example/viewmodel/SwarmViewModel.kt`
- Modify: `app/src/main/java/com/example/MainActivity.kt`

**Steps:**
1. Add `gitService: GitService` parameter to `SwarmViewModel` constructor with default `GitService(File(application.filesDir, "git_workspace").apply { mkdirs() })`.
2. Update `swarmEngine by lazy { SwarmEngine(db, gitService, ...) }`.
3. Update `SwarmViewModelFactory` to accept `Application` only and construct `GitService` internally, OR accept an optional shared `GitService`.
4. In `MainActivity`, create a single `val gitService = GitService(File(application.filesDir, "git_workspace").apply { mkdirs() })` and pass it to `SwarmViewModelFactory`.
5. Run `./gradlew :app:compileDebugKotlin` to confirm SwarmViewModel still compiles.

### Task 2: Create `WorkspaceViewModel` and factory
**Objective:** Implement the new ViewModel with all workspace/file/git/sandbox state and functions.

**Files:**
- Create: `app/src/main/java/com/example/viewmodel/WorkspaceViewModel.kt`

**Steps:**
1. Define `WorkspaceViewModel` extending `AndroidViewModel(application)`.
2. Copy moved state flows, backing flows, init block, and functions from `SwarmViewModel`.
3. Accept optional `gitService` parameter; default to a new instance at `filesDir/git_workspace` if null.
4. Add `WorkspaceViewModelFactory`.
5. Keep all function signatures identical to the originals.
6. Run `./gradlew :app:compileDebugKotlin`.

### Task 3: Remove workspace state from `SwarmViewModel`
**Objective:** Delete the moved code from `SwarmViewModel`.

**Files:**
- Modify: `app/src/main/java/com/example/viewmodel/SwarmViewModel.kt`

**Steps:**
1. Remove moved state flows and backing flows.
2. Remove moved functions and helpers.
3. Remove `MAX_IMPORT_FILE_BYTES`, `skippedDirNames`, `importableExtensions`, `walkAndImport`, `findOrCreateFileInTree`, `computeGitSyncState`, `gitWorkDir`, `gitService` property (now a constructor parameter).
4. Confirm `SwarmViewModel` still compiles and only references `gitService` through the constructor parameter / `swarmEngine`.
5. Run `./gradlew :app:compileDebugKotlin`.

### Task 4: Update `WorkspacePanel` and `CodeEditorSection`
**Objective:** Switch the workspace UI to use `WorkspaceViewModel`.

**Files:**
- Modify: `app/src/main/java/com/example/ui/IdeWorkspaceScreen.kt`

**Steps:**
1. Change `WorkspacePanel` parameter to `workspaceViewModel: WorkspaceViewModel`.
2. Rename all `viewModel.` references inside `WorkspacePanel` to `workspaceViewModel.`.
3. Change `CodeEditorSection` parameter to `workspaceViewModel: WorkspaceViewModel`.
4. Update `CodeEditorSection` call sites (large-screen and compact branches).
5. Run `./gradlew :app:compileDebugKotlin`.

### Task 5: Update `SessionScreen` call sites
**Objective:** Pass `WorkspaceViewModel` into `SessionScreen` and forward it to `WorkspacePanel`.

**Files:**
- Modify: `app/src/main/java/com/example/ui/SessionScreen.kt`

**Steps:**
1. Add `workspaceViewModel: WorkspaceViewModel` to `SessionScreen` signature.
2. Replace `WorkspacePanel(viewModel = viewModel)` with `WorkspacePanel(workspaceViewModel = workspaceViewModel)` in both expanded and bottom-sheet usages.
3. Run `./gradlew :app:compileDebugKotlin`.

### Task 6: Update `MainActivity` ownership
**Objective:** Instantiate both ViewModels, fix `onResume`, and wire `SessionScreen`.

**Files:**
- Modify: `app/src/main/java/com/example/MainActivity.kt`

**Steps:**
1. Add imports for `WorkspaceViewModel` and `WorkspaceViewModelFactory`.
2. Create shared `gitService`.
3. Add `workspaceViewModel` property with `WorkspaceViewModelFactory`.
4. Update `onResume` to call `workspaceViewModel.syncWorkspace(...)`.
5. Update `SessionScreen(...)` call to pass `workspaceViewModel`.
6. Run `./gradlew :app:compileDebugKotlin`.

### Task 7: Update `UiTestBase` and existing workspace UI tests
**Objective:** Give tests access to both ViewModels and keep existing workspace UI tests green.

**Files:**
- Modify: `app/src/test/java/com/example/ui/UiTestBase.kt`
- Modify: `app/src/test/java/com/example/ui/WorkspaceFileFlowTest.kt`
- Modify: `app/src/test/java/com/example/ui/GitIntegrationTest.kt`

**Steps:**
1. Add `protected lateinit var workspaceViewModel: WorkspaceViewModel`.
2. In `setUp`, construct `workspaceViewModel` with same `FakeAppDatabase` and `FakeSecurePrefs` as `viewModel`, plus a `GitService` over `context.cacheDir`.
3. Update `WorkspaceFileFlowTest` to use `WorkspacePanel(workspaceViewModel)` and `workspaceViewModel.workspaceFiles`.
4. Update `GitIntegrationTest` similarly.
5. Run `./gradlew :app:testDebugUnitTest --tests "com.example.ui.WorkspaceFileFlowTest" --tests "com.example.ui.GitIntegrationTest"`.

### Task 8: Add `WorkspaceViewModelTest`
**Objective:** Provide direct unit coverage for the extracted domain.

**Files:**
- Create: `app/src/test/java/com/example/viewmodel/WorkspaceViewModelTest.kt`

**Steps:**
1. Use Robolectric + `UnconfinedTestDispatcher`.
2. Write the seven test cases listed in Section 5.
3. Run `./gradlew :app:testDebugUnitTest --tests "com.example.viewmodel.WorkspaceViewModelTest"`.

### Task 9: Full UI regression
**Objective:** Confirm no non-workspace tests broke.

**Steps:**
1. Run `./gradlew :app:testDebugUnitTest --tests "com.example.ui.*"`.
2. If failures appear, root-cause whether they stem from shared-state leakage (e.g. `PendingApprovalStore.reset()` not called) or from moved APIs, and fix.

### Task 10: Static checks
**Objective:** Confirm the app still builds and basic lint passes.

**Steps:**
1. `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`
2. `./gradlew :app:lintDebug` (optional; may surface unused imports after deletion).

---

## 7. Explicit Pitfalls and How to Avoid Them

### Pitfall 1: Two independent `GitService` instances at the same path
**Risk:** `WorkspaceViewModel` and `SwarmEngine` each create their own `GitService`, leading to confusing sync-state behavior if both touch the repo concurrently.
**Avoid:** Pass a single `GitService` instance from `MainActivity` into both `SwarmViewModelFactory` and `WorkspaceViewModelFactory`. Make `WorkspaceViewModel` accept `gitService` as an optional constructor parameter.

### Pitfall 2: `WorkspaceViewModel` `init` triggers `syncWorkspace` but there is no folder URI
**Risk:** The moved `init` block reads prefs and calls `syncWorkspace(isAutomatic = true)` if a URI was saved. `syncWorkspace` early-returns when `_rootFolderUri.value` is null, so this is safe, but verify the early-return remains after the move.
**Avoid:** Keep the guard `val uriStr = _rootFolderUri.value ?: return` at the top of `syncWorkspace`.

### Pitfall 3: `runSandbox` references removed `SwarmViewModel` functions
**Risk:** `runSandbox` uses `saveFileContentSuspended` and `swarmEngine.generateFreeform`. After moving it, `WorkspaceViewModel` does not have `swarmEngine`.
**Avoid:** `runSandbox` must accept the LLM generation dependency OR keep sandbox as a shared concern. Since the sandbox is purely a workspace/editor feature, the cleanest path is to inject `OllamaService` into `WorkspaceViewModel` for `generateFreeform`. However, that adds a dependency. A pragmatic alternative is to keep a lightweight `LlmRouter` inside `WorkspaceViewModel` initialized with `OllamaServiceDefault`, mirroring `SwarmEngine`. **Recommended:** add `private val ollamaService: OllamaService = OllamaServiceDefault` to `WorkspaceViewModel` constructor and use `LlmRouter`/`generateFreeform` directly in `runSandbox`.

### Pitfall 4: `AgenticActionExecutor` reads `git_remote_url` from `SharedPreferences`
**Risk:** `AgenticActionExecutor.executeAgenticGitCommand` reads the same prefs key (`git_remote_url`) that `WorkspaceViewModel.updateGitSettings` writes. This coupling remains unchanged; do not break it.
**Avoid:** Ensure `WorkspaceViewModel.updateGitSettings` continues to write `prefs.edit().putString("git_remote_url", remoteUrl).apply()`.

### Pitfall 5: `PendingApprovalStore` reset between tests
**Risk:** `PendingApprovalStore` is a process-wide singleton. If a test triggers an approval gate and does not reset, subsequent tests may see stale state.
**Avoid:** Add `PendingApprovalStore.reset()` to `UiTestBase.tearDown()` (or `setUp`). Verify existing tests that exercise approval gates already call reset.

### Pitfall 6: `UiTestBase.setUp` constructs two ViewModels with the same dispatcher
**Risk:** `UnconfinedTestDispatcher` instances are not equal; `Dispatchers.setMain(testDispatcher)` sets one, but if a ViewModel captures a different dispatcher, Compose/main coroutines may not pump.
**Avoid:** Use the **same** `testDispatcher` instance for `Dispatchers.setMain`, both ViewModels, and `SwarmViewModel`/`WorkspaceViewModel` dispatcher parameters.

### Pitfall 7: `WorkspaceFileFlowTest` uses `viewModel.workspaceFiles.value`
**Risk:** After extraction, `viewModel` no longer exposes `workspaceFiles`; tests will fail to compile.
**Avoid:** Update both workspace UI tests to reference `workspaceViewModel` before compilation.

### Pitfall 8: `SessionScreen` signature change breaks callers
**Risk:** `MainActivity` is the only caller of `SessionScreen`, but any preview/test calling it directly will break.
**Avoid:** Search the project for `SessionScreen(` call sites; only `MainActivity` exists. Update it and any `@Preview` if present (none found during analysis).

### Pitfall 9: `SystemConfigScreen` does not need `WorkspaceViewModel`
**Risk:** The previous summary suggested `SystemConfigScreen` might need `WorkspaceViewModel`. Analysis shows it only hosts `NodeScreen`/`AgentScreen`/`McpSkillsScreen`; no workspace logic is present.
**Avoid:** Do **not** change `SystemConfigScreen` signature.

### Pitfall 10: Compose recompositions from renamed `viewModel` parameter
**Risk:** Renaming the parameter from `viewModel` to `workspaceViewModel` inside `WorkspacePanel` is safe, but any test relying on semantics or `@Preview` default arguments may need updates.
**Avoid:** No `@Preview` for `WorkspacePanel` exists. Update test call sites only.

### Pitfall 11: `gitService.localHeadHash()` returns `null` before first commit
**Risk:** `computeGitSyncState` compares `gitService.localHeadHash()` to `prefs.getString("git_last_pushed_hash", null)`. Both null -> `true`. After the first commit, head is non-null and last-pushed is null -> `false`, which correctly shows "unpushed changes". Preserve this logic.
**Avoid:** Copy `computeGitSyncState` verbatim.

### Pitfall 12: `runSandbox` self-healing `CompletableDeferred` leaks across configuration changes
**Risk:** `selfHealingDeferred` is a plain nullable field; if the ViewModel is cleared mid-dialog, the deferred is never completed.
**Avoid:** This is pre-existing behavior; do not introduce new handling in this refactor. Note it for a future ViewModel-cleanup task.

### Pitfall 13: Losing `importFolder` persistent URI permission
**Risk:** `importFolder` calls `contentResolver.takePersistableUriPermission(...)`. This requires the launcher to return a tree URI with `Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION`. The launcher contract `OpenDocumentTree()` already does. Preserve the call exactly.
**Avoid:** Copy `importFolder` verbatim.

### Pitfall 14: `saveFileContentSuspended` is `suspend` and public
**Risk:** It is currently public so `runSandbox` can call it. After moving both to `WorkspaceViewModel`, it can remain public or become `internal`. Keep it public to avoid breaking any external callers (there are none outside `WorkspacePanel`).
**Avoid:** Keep signature unchanged.

### Pitfall 15: `SwarmViewModelFactory` default `gitService` causes duplicate repo with `WorkspaceViewModelFactory`
**Risk:** If each factory lazily constructs its own `GitService`, production has two instances at the same path.
**Avoid:** As noted in Section 4, construct one `GitService` in `MainActivity` and pass it to both factories. Make `WorkspaceViewModel` accept `gitService` as an optional constructor parameter.

---

## Open Questions / Notes for Implementer

1. **Sandbox LLM dependency:** decide whether to inject `OllamaService` into `WorkspaceViewModel` or construct `LlmRouter` internally. Recommended: add `ollamaService: OllamaService = OllamaServiceDefault` to `WorkspaceViewModel` and use `LlmRouter(ollamaService, db.ollamaNodeDao(), db.claudeSkillDao(), securePrefs, dispatcher)` for `generateFreeform` inside `runSandbox`.
2. **Test environment:** the previous subagent could not run `./gradlew test` due to WSL Gradle daemon issues. The implementation subagent must run tests in a working environment and report real output. If WSL remains broken, try `--no-daemon --stacktrace` and escalate rather than fabricate results.
3. **Next slices:** after this lands, the remaining `SwarmViewModel` slices are MCP/Skills, Node/Agent, and Session/Swarm execution. Keep this spec scoped to workspace/file/git only.
