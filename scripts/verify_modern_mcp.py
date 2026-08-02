"""Verify that the OllamaDev companion MCP server speaks the modern 2026-07-28 transport.

This script mirrors the request shapes that the updated Android McpClient sends:
- `tools/list` probe with `MCP-Protocol-Version: 2026-07-28`, `mcp-method: tools/list`,
  and a `_meta` envelope.
- `tools/call` with `mcp-method: tools/call` and `mcp-name: <tool>` headers.

Run with the companion server already running:
    cd /home/userland/ollamadev-mcp-server
    .venv/bin/python launch_server.py
    cd /home/userland/OllamaDev
    /home/userland/ollamadev-mcp-server/.venv/bin/python scripts/verify_modern_mcp.py
"""
import os
import sys

import requests

SERVER_URL = os.environ.get("MCP_SERVER_URL", "http://localhost:5000/mcp")
WORKSPACE_ROOT = os.environ.get("WORKSPACE_ROOT", "/home/userland/OllamaDev")


def make_request(method: str, params: dict, tool_name: str | None = None) -> dict:
    request_id = 1
    headers = {
        "content-type": "application/json",
        "MCP-Protocol-Version": "2026-07-28",
        "mcp-method": method,
    }
    if tool_name:
        headers["mcp-name"] = tool_name

    body = {
        "jsonrpc": "2.0",
        "id": request_id,
        "method": method,
        "params": {
            **params,
            "_meta": {
                "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                "io.modelcontextprotocol/clientCapabilities": {},
            },
        },
    }

    resp = requests.post(SERVER_URL, headers=headers, json=body, timeout=60)
    resp.raise_for_status()
    data = resp.json()
    if "error" in data:
        raise RuntimeError(f"MCP error: {data['error']}")
    return data["result"]


def main() -> int:
    print(f"Verifying modern MCP transport at {SERVER_URL}")

    # 1. Modern tools/list probe (what tryModernConnect sends)
    print("\n1. tools/list probe")
    list_result = make_request("tools/list", {})
    tools = list_result.get("tools", [])
    print(f"   tools count: {len(tools)}")
    if len(tools) == 0:
        print("ERROR: no tools advertised")
        return 1
    print(f"   first tool: {tools[0]['name']}")

    # 2. Modern tools/call with mcp-name (what callTool sends)
    print("\n2. tools/call ping")
    ping_result = make_request("tools/call", {"name": "ping", "arguments": {}}, tool_name="ping")
    content = ping_result.get("content", [])
    text = ""
    for item in content:
        if item.get("type") == "text":
            text = item.get("text", "")
            break
    print(f"   ping result: {text[:120]}")
    if "OllamaDev Toolbox" not in text:
        print("ERROR: ping did not return expected server identity")
        return 1

    # 3. Exercise a workspace tool to prove workspace integration works
    print("\n3. tools/call list_workspace_files")
    files_result = make_request(
        "tools/call",
        {"name": "list_workspace_files", "arguments": {"root": "app/src/main/java/com/example/data"}},
        tool_name="list_workspace_files",
    )
    files = [
        item.get("text", "")
        for item in files_result.get("content", [])
        if item.get("type") == "text"
    ]
    print(f"   files in data/ package: {len(files)}")
    if not any("McpClient.kt" in line for line in files):
        print("ERROR: McpClient.kt not found in workspace listing")
        return 1

    print("\nAll modern MCP transport checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
