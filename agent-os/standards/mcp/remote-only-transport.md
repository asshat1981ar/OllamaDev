# MCP: Remote HTTP Transport Only

`McpClient` implements only MCP's Streamable HTTP transport, talking to remote/hosted
servers over HTTP(S). There's no stdio (local child-process) MCP server support.

- **Why:** Not a hard Android sandboxing wall — just not implemented. Could be revisited
  later (e.g. via Termux-style subprocess execution) if a real need comes up.
- **How to apply:** Don't assume stdio MCP servers work — the registry browser, skill
  binding, and `SwarmEngine`'s `MCP_CALL:` directive all only ever resolve a server's
  Streamable-HTTP URL (see `resolveStreamableHttpUrl()`). Adding stdio support would be a
  net-new subsystem (process spawning/lifecycle/stdio piping), not a small extension of
  `McpClient`.
