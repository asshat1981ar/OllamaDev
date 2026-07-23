# Interface Seam for I/O Classes

Data/service classes that touch real I/O (Room/SQLite, network, Keystore crypto) get a
matching `*Interface.kt` alongside them. The real class implements it; a fake implements
it in tests.

```kotlin
interface McpClientInterface {
    suspend fun initialize(serverUrl: String, authToken: String?): Result<McpSession>
    // ...
}

class McpClient : McpClientInterface { /* real network calls */ }
```

Consumers (mainly `SwarmViewModel`) take the interface type as a constructor param
defaulting to the real implementation — no DI framework:

```kotlin
class SwarmViewModel(
    private val mcpClient: McpClientInterface = McpClient(),
    database: AppDatabaseInterface = AppDatabase.getDatabase(application),
    private val securePrefs: SecurePrefsInterface = RealSecurePrefs(application),
)
```

Tests override the default with a fake at construction time.

- **Why:** Robolectric has no SQLite support (native or legacy) on linux-aarch64, so DB
  tests need a fake `AppDatabaseInterface`. The same seam pattern is applied to network
  (`McpClient`, `McpRegistryClient`) and Keystore-backed crypto (`SecurePrefs`) so their
  tests avoid real I/O too.
- **Scope:** Only classes touching real I/O need this seam. Pure logic/computation
  classes (e.g. `SwarmEngine`'s own orchestration logic) don't need an interface *of
  their own* just for testability — but if such a class internally *calls* another
  class that does need the seam (e.g. `SwarmEngine` reading a git/MCP auth token), it
  must take that dependency as the interface type via its constructor too, not call the
  real singleton directly. `SwarmEngine` originally called `SecurePrefs.getGitToken()`/
  `getMcpToken()` directly instead of through `SecurePrefsInterface` — this compiled
  fine but caused intermittent `NoSuchAlgorithmException`/`KeyStoreException` test
  failures the first time a test actually exercised that code path (MCP tool calls,
  git push), since real Keystore crypto is unreliable under Robolectric on this device.
  Fixed by adding `securePrefs: SecurePrefsInterface` to `SwarmEngine`'s constructor.
- Existing interfaces: `AppDatabaseInterface`, `McpClientInterface`,
  `McpRegistryClientInterface`, `SecurePrefsInterface`.
