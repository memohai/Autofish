#!/usr/bin/env bash
# List all available MCP tools.
# Requires: MCP_TOKEN, MCP_SESSION
set -euo pipefail
source "$(dirname "$0")/env.sh"

[[ -z "$MCP_TOKEN" ]]   && echo "Error: export MCP_TOKEN=<token>" >&2   && exit 1
[[ -z "$MCP_SESSION" ]] && echo "Error: export MCP_SESSION=<id>" >&2    && exit 1

curl -s -X POST "${MCP_BASE}/mcp" \
    -H "Authorization: Bearer ${MCP_TOKEN}" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -H "Mcp-Session-Id: ${MCP_SESSION}" \
    -d '{
        "jsonrpc": "2.0",
        "id": 2,
        "method": "tools/list",
        "params": {}
    }' | python3 -m json.tool
