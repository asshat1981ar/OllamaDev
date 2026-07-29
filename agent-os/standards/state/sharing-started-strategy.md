# StateFlow Sharing Strategy: DB vs Derived

`SwarmViewModel` picks `SharingStarted` per Flow based on what backs it:

```kotlin
// DB-backed Flow: WhileSubscribed(5000)
val allNodes = db.ollamaNodeDao().getAllNodes()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

// Derived from a process-wide singleton: Eagerly
val totalTokensUsed: StateFlow<Int> = AgentStateStore.agentStates.map { map ->
    map.values.sumOf { it.totalTokensUsed }
}.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
```

- **`WhileSubscribed(5000)`** for any Flow sourced from a Room DAO. Stops the underlying
  query when no screen observes it, with a 5s grace period so a tab switch or
  config change doesn't restart the query. Use this for new DB-backed state.
- **`Eagerly`** for Flows derived from `AgentStateStore` (a process-wide singleton that
  keeps emitting regardless of subscribers — there's nothing to save by pausing
  collection) where the value should be correct immediately on first collection rather
  than waiting out the 5s window.
- **How to apply:** New DB-backed state → `WhileSubscribed(5000)`. New state derived from
  an always-running singleton/store → `Eagerly`. Don't default to `Eagerly` for DB Flows —
  it defeats the point of pausing unused queries.
