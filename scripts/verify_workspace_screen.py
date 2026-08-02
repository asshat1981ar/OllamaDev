"""Verify the Workspace Browser MCP integration against a live companion server.

This script exercises the same MCP tools that the WorkspaceScreen UI uses:
- list_workspace_files
- read_workspace_file
- write_workspace_file

Run with the companion server already running:
    cd /home/userland/ollamadev-mcp-server
    .venv/bin/python launch_server.py
    cd /home/userland/OllamaDev
    /home/userland/ollamadev-mcp-server/.venv/bin/python scripts/verify_workspace_screen.py
"""
import json
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


def extract_text(result: dict) -> str:
    """Concatenate all text items in the result content list."""
    content = result.get("content", [])
    return "\n".join(
        item.get("text", "")
        for item in content
        if item.get("type") == "text"
    )


def main() -> int:
    print(f"Verifying Workspace Browser MCP integration at {SERVER_URL}")

    # 1. List workspace files (matching WorkspaceScreen loadWorkspaceFiles)
    print("\n1. tools/call list_workspace_files")
    list_result = make_request(
        "tools/call",
        {"name": "list_workspace_files", "arguments": {"root": "app/src/main/java/com/example"}},
        tool_name="list_workspace_files",
    )
    files_text = extract_text(list_result)
    files = [line for line in files_text.splitlines() if line.strip()]
    print(f"   files in app/src/main/java/com/example: {len(files)}")
    if not files:
        print("ERROR: no files returned")
        return 1
    if not any("MainActivity.kt" in path for path in files):
        print("ERROR: MainActivity.kt not found in listing")
        return 1

    # 2. Read a file (matching WorkspaceScreen readWorkspaceFile)
    print("\n2. tools/call read_workspace_file")
    read_result = make_request(
        "tools/call",
        {"name": "read_workspace_file", "arguments": {"path": "app/src/main/java/com/example/MainActivity.kt"}},
        tool_name="read_workspace_file",
    )
    content = extract_text(read_result)
    print(f"   MainActivity.kt size: {len(content)} chars")
    if "class MainActivity" not in content:
        print("ERROR: MainActivity.kt content did not contain expected class declaration")
        return 1

    # 3. Write a temporary file (matching WorkspaceScreen saveWorkspaceFile)
    print("\n3. tools/call write_workspace_file")
    test_path = "app/src/debug/.mcp_workspace_verify.txt"
    write_result = make_request(
        "tools/call",
        {
            "name": "write_workspace_file",
            "arguments": {
                "path": test_path,
                "content": "mcp workspace verify ok",
                "create_dirs": True,
            },
        },
        tool_name="write_workspace_file",
    )
    write_text = extract_text(write_result)
    print(f"   write result: {write_text[:120]}")
    if "written" not in write_text.lower() and "ok" not in write_text.lower():
        print("ERROR: write_workspace_file did not report success")
        return 1

    # 4. Read back the temporary file
    print("\n4. tools/call read_workspace_file (verify write)")
    verify_result = make_request(
        "tools/call",
        {"name": "read_workspace_file", "arguments": {"path": test_path}},
        tool_name="read_workspace_file",
    )
    verify_content = extract_text(verify_result)
    if "mcp workspace verify ok" not in verify_content:
        print("ERROR: written file did not round-trip correctly")
        return 1

    print("\nAll Workspace Browser MCP integration checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
