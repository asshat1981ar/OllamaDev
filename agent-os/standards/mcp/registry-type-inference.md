# Registry Server Type: Keyword Inference

`RegistryServerDetail.inferServerType()` guesses a server's category (`GitHub`,
`Database`, `Browser`, `Search`, `Docker`, `Slack`, `Filesystem`, or the fallback
`Gateway`) by lowercase keyword matching against the server's `name + description + title`.
Used to auto-populate `McpServer.type` on one-tap install from the registry browser.

```kotlin
fun RegistryServerDetail.inferServerType(): String {
    val text = "${name} ${description ?: ""} ${title ?: ""}".lowercase()
    return when {
        text.contains("github") -> "GitHub"
        // ...
        else -> "Gateway"
    }
}
```

- **Extending it:** add a new `when` branch as new server categories become common in the
  registry — this is meant to grow over time, not a fixed list.
- **`"Gateway"` is the generic fallback**, not just a display label: `ClaudeSkill`s with a
  specific `requiredMcpServerType` (e.g. `"GitHub"`, `"Database"`) won't auto-recommend or
  bind to a server that inferred as `"Gateway"`. A server misclassified as `Gateway` is
  effectively invisible to type-specific skill matching.
