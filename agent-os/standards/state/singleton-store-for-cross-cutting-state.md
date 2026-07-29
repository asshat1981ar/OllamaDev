# Singleton Object for State Written from Non-ViewModel Classes

`AgentStateStore` is a Kotlin `object` (not ViewModel-owned state) wrapping a
`MutableStateFlow<Map<Int, AgentMetrics>>`. `SwarmViewModel` reads it directly
(`val agentStates: StateFlow<...> = AgentStateStore.agentStates`); `SwarmEngine` writes to
it during task execution via `setAgentActive()` / `recordExecutionMetrics()`.

```kotlin
object AgentStateStore {
    private val _agentStates = MutableStateFlow<Map<Int, AgentMetrics>>(emptyMap())
    val agentStates: StateFlow<Map<Int, AgentMetrics>> = _agentStates.asStateFlow()
    fun setAgentActive(agentId: Int, isActive: Boolean, status: String = "Idle") { /* ... */ }
}
```

- **Why:** `SwarmEngine` is a plain class instantiated inside `SwarmViewModel` (not a
  ViewModel itself), so it can't hold a reference back into ViewModel-owned `StateFlow`s —
  that would invert the dependency direction. A singleton object is the only way for
  `SwarmEngine` to publish live per-agent activity that `SwarmViewModel`/the UI observes.
- **How to apply:** New state that must be *written* from a non-ViewModel class (a
  service, engine, or other plain class) follows this same singleton-object +
  `MutableStateFlow` pattern, rather than threading a callback/listener through every
  layer between the writer and `SwarmViewModel`.
