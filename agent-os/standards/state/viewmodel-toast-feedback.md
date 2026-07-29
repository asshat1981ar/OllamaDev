# One-off UI Feedback: Direct Toast, No Event Bus

`SwarmViewModel` is an `AndroidViewModel` and calls `Toast.makeText(app, ..., Toast.LENGTH_SHORT).show()`
directly for one-off user feedback (e.g. "Project imported", "Workspace disconnected").
There's no `SharedFlow`/`Channel` event-bus abstraction for screens to consume.

```kotlin
Toast.makeText(app, "Project imported: $rootName", Toast.LENGTH_SHORT).show()
```

- **Why:** Deliberate simplicity — the app is small enough that `AndroidViewModel` +
  direct `Toast` avoids `SharedFlow`/`Channel` boilerplate for a one-shot event.
- **Gotcha:** `Toast` must run on the main thread. Only call it from a coroutine still on
  the main dispatcher — never from inside a `withContext(dispatcher)` block that switches
  to `Dispatchers.IO` (the constructor-injected `dispatcher` param), or it crashes.
- **How to apply:** New one-off feedback follows this same direct-`Toast` pattern rather
  than introducing an event-bus for a single message.
