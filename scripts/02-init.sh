#!/usr/bin/env bash
# Initialize MCP session. Prints the Mcp-Session-Id for subsequent calls.
# Requires: MCP_TOKEN
set -euo pipefail
source "$(dirname "$0")/env.sh"

[[ -z "$MCP_TOKEN" ]] && echo "Error: export MCP_TOKEN=<token> first" >&2 && exit 1

RESP=$(curl -si -X POST "${MCP_BASE}/mcp" \
    -H "Authorization: Bearer ${MCP_TOKEN}" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -d '{
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": {
            "protocolVersion": "2025-03-26",
            "capabilities": {},
            "clientInfo": { "name": "mcp-test", "version": "0.1.0" }
        }
    }')

SESSION=$(echo "$RESP" | grep -i "mcp-session-id" | awk '{print $2}' | tr -d '\r\n')
BODY=$(echo "$RESP" | grep '^{')

echo "$BODY" | python3 -m json.tool
echo ""
echo "export MCP_SESSION=${SESSION}"
