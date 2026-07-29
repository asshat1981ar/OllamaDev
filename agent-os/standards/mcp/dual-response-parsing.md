# MCP: Every Call Goes Through buildRequest()/parseResponse()

A server can answer an MCP JSON-RPC call with either a single `application/json` body or
a `text/event-stream` (SSE) stream. `McpClient.parseResponse()` handles both, reading SSE
lines until the event whose JSON-RPC `id` matches the request's `expectedId` arrives.

```kotlin
val requestId = idCounter.getAndIncrement()
val request = buildRequest(serverUrl, "tools/call", params, session, authToken, requestId)
client.newCall(request).execute().use { response ->
    val parsed = parseResponse(response, requestId)
    // ...
}
```

- **Common mistake:** hand-rolling a new HTTP call for a new MCP method (e.g. a future
  `resources/list`) instead of reusing `buildRequest()`/`parseResponse()`. The two are
  coupled — the `requestId` passed into `buildRequest()` is exactly what `parseResponse()`
  matches against in the SSE case, so skipping `buildRequest()` breaks SSE response
  matching even if the JSON-body path happens to still work.
- **How to apply:** Any new MCP method call follows `initialize()`/`listTools()`/
  `callTool()`'s exact shape: get a `requestId` from `idCounter`, call `buildRequest()`,
  then `parseResponse()` with that same id.
