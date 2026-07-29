# Secrets Go Through SecurePrefs

Any credential/secret (auth tokens, PATs, future API keys) is stored via
`SecurePrefs`/`SecurePrefsInterface` (`EncryptedSharedPreferences` + Keystore), never as
a plain Room column or plain `SharedPreferences`.

```kotlin
interface SecurePrefsInterface {
    fun getGitToken(): String?
    fun setGitToken(token: String)
    fun getMcpToken(serverId: Int): String?
    fun setMcpToken(serverId: Int, token: String)
    fun clearMcpToken(serverId: Int)
}
```

Everything else (nodes, agents, swarm configs, tasks, chat messages) goes through Room
via `AppDatabase`.

- **Why:** Room/SQLite database files are unencrypted on disk — a secret stored there is
  recoverable from a rooted device or an unencrypted backup. `SecurePrefs` wraps
  `EncryptedSharedPreferences` with an AES256-GCM Keystore-backed master key.
- **How to apply:** Adding a new credential (e.g. a future API key)? Add a getter/setter
  pair to `SecurePrefsInterface`/`SecurePrefs`, not a new Room entity column.
